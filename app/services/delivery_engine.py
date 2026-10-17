"""复合发货引擎：固定内容(网盘链接) + 卡密池一卡一发，按订单号幂等。

流程：命中规则 -> 原子占卡 -> 写发货日志(order_id 唯一) -> 发消息 -> 官方虚拟发货确认。
任何一步失败，同一 order_id 重放都不会多占卡、不会重复发。
"""
import sqlite3
from typing import Optional
from loguru import logger
from app.db.database import get_db


class OutOfStock(Exception):
    pass


def match_rule(conn, item_title: str) -> Optional[sqlite3.Row]:
    """按关键词最长匹配，避免 '会员' 抢走 '年度会员' 的单"""
    rows = conn.execute(
        "SELECT * FROM auto_delivery_rules WHERE enabled=1 ORDER BY length(keyword) DESC"
    ).fetchall()
    return next((r for r in rows if r["keyword"] and r["keyword"] in (item_title or "")), None)


def build_delivery(order_id: str, user_id: str, chat_id: str, item_title: str) -> Optional[dict]:
    """返回 {content, keyword, auto_confirm, replay}；无规则返回 None；卡密售罄抛 OutOfStock"""
    conn = get_db()
    try:
        conn.execute("BEGIN IMMEDIATE")  # 写锁，串行化同库并发占卡
        done = conn.execute(
            "SELECT keyword, delivery_content, confirm_status FROM delivery_logs WHERE order_id=?", (order_id,)
        ).fetchone()
        if done and done["confirm_status"] == "out_of_stock":  # 补卡后重试：清掉缺货占位再走正常流程
            conn.execute("DELETE FROM delivery_logs WHERE order_id=? AND confirm_status='out_of_stock'", (order_id,))
            done = None
        if done:  # 幂等重放：同一订单直接返回上次内容
            conn.execute("COMMIT")
            return {"content": done["delivery_content"], "keyword": done["keyword"],
                    "auto_confirm": True, "replay": True}

        rule = match_rule(conn, item_title)
        if not rule:
            conn.execute("COMMIT")
            return None

        parts = []
        if (rule["delivery_content"] or "").strip():
            parts.append(rule["delivery_content"].strip())
        if rule["use_card_pool"]:
            card = conn.execute(
                """UPDATE card_keys SET is_used=1, order_id=?, used_at=CURRENT_TIMESTAMP
                   WHERE id=(SELECT id FROM card_keys WHERE keyword=? AND is_used=0 ORDER BY id LIMIT 1)
                   RETURNING card_content""",
                (order_id, rule["keyword"]),
            ).fetchone()
            if not card:
                conn.execute("ROLLBACK")
                raise OutOfStock(rule["keyword"])
            parts.append(f"卡密：{card['card_content']}")

        content = "\n".join(parts)
        conn.execute(
            """INSERT INTO delivery_logs (order_id, user_id, chat_id, keyword, delivery_content, confirm_status)
               VALUES (?, ?, ?, ?, ?, 'pending')""",
            (order_id, user_id, chat_id, rule["keyword"], content),
        )
        conn.execute("COMMIT")
        return {"content": content, "keyword": rule["keyword"],
                "auto_confirm": bool(rule["auto_confirm"]), "replay": False}
    except Exception:
        if conn.in_transaction:
            conn.execute("ROLLBACK")
        raise
    finally:
        conn.close()


# 状态机：pending ─发消息→ sent ─官方确认→ confirmed
#                 └失败→ send_failed      └失败→ confirm_failed ─重试→ confirmed
TRANSITIONS = {
    "sent": ("pending", "send_failed"),
    "send_failed": ("pending",),
    "confirmed": ("sent", "confirm_failed"),
    "confirm_failed": ("sent", "confirm_failed"),
}


def mark_status(order_id: str, to: str) -> bool:
    """条件更新 = 乐观锁：只有处于合法前置状态才会迁移，并发重复回调只有一个能成功"""
    allowed = TRANSITIONS.get(to)
    if not allowed:
        raise ValueError(f"非法目标状态 {to}")
    conn = get_db()
    try:
        q = f"UPDATE delivery_logs SET confirm_status=? WHERE order_id=? AND confirm_status IN ({','.join('?' * len(allowed))})"
        ok = conn.execute(q, (to, order_id, *allowed)).rowcount == 1
    finally:
        conn.close()
    logger.info(f"订单 {order_id} -> {to} {'✓' if ok else '(跳过：状态不匹配)'}")
    return ok


def record_out_of_stock(order_id: str, user_id: str, chat_id: str, keyword: str):
    """缺货也落一条日志，订单 Tab 可见并可补卡后重试"""
    conn = get_db()
    conn.execute(
        """INSERT OR IGNORE INTO delivery_logs (order_id, user_id, chat_id, keyword, delivery_content, confirm_status)
           VALUES (?, ?, ?, ?, '', 'out_of_stock')""", (order_id, user_id, chat_id, keyword))
    conn.close()


async def deliver_order(order_id: str, user_id: str, chat_id: str, item_title: str, send_message, confirm):
    """编排入口（由 WSS 订单事件调用）。send_message/confirm 为注入的异步函数，便于测试与替换。"""
    from app.core import events
    try:
        plan = build_delivery(order_id, user_id, chat_id, item_title)
    except OutOfStock as e:
        record_out_of_stock(order_id, user_id, chat_id, str(e))
        events.publish("delivery", {"order_id": order_id, "status": "out_of_stock"})
        return
    if plan is None:
        return
    if not plan["replay"] or _status(order_id) in ("pending", "send_failed"):
        try:
            await send_message(chat_id, user_id, plan["content"])
            mark_status(order_id, "sent")
        except Exception as ex:
            logger.error(f"订单 {order_id} 发货消息失败: {ex}")
            mark_status(order_id, "send_failed")
            events.publish("delivery", {"order_id": order_id, "status": "send_failed"})
            return
    if plan["auto_confirm"] and _status(order_id) in ("sent", "confirm_failed"):
        ok, _ = await confirm(order_id)
        mark_status(order_id, "confirmed" if ok else "confirm_failed")
    events.publish("delivery", {"order_id": order_id, "status": _status(order_id)})


def _status(order_id: str):
    conn = get_db()
    row = conn.execute("SELECT confirm_status FROM delivery_logs WHERE order_id=?", (order_id,)).fetchone()
    conn.close()
    return row["confirm_status"] if row else None
