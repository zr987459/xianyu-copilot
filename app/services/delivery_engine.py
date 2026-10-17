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
            "SELECT keyword, delivery_content FROM delivery_logs WHERE order_id=?", (order_id,)
        ).fetchone()
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


def mark_status(order_id: str, status: str):
    """状态机：pending -> sent -> confirmed | confirm_failed | out_of_stock"""
    conn = get_db()
    conn.execute("UPDATE delivery_logs SET confirm_status=? WHERE order_id=?", (status, order_id))
    conn.close()
    logger.info(f"订单 {order_id} -> {status}")
