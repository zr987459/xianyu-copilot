"""
卡密池出库：全项目唯一入口（API 手动发货 与 机器人自动发货 共用）。

两阶段发货：
  1) claim_*        在一个 BEGIN IMMEDIATE 事务里“锁卡 + 记账”，状态 claimed
  2) 发送私信 / 确认发货
  3) mark_delivery  标记 sent 或 failed
失败的单子不回收卡密（内容可能已发出），补发时复用同一张卡。
"""
from typing import Callable, Optional, Literal

from app.db.database import db, write_tx


class OutOfStock(Exception):
    pass


def _default_compose(fixed: str, card: str) -> str:
    return (fixed.strip() + "\n" if fixed and fixed.strip() else "") + card


def _existing(conn, order_id):
    r = conn.execute(
        "SELECT card_id, delivery_content, status FROM delivery_logs WHERE order_id=?", (order_id,)
    ).fetchone()
    if r:
        return {"order_id": order_id, "card_id": r["card_id"], "content": r["delivery_content"],
                "status": r["status"], "idempotent": True}
    return None


def claim_card_for_order(order_id: str, keyword: str, user_id: str = "", chat_id: str = "",
                         fixed_content: str = "",
                         compose: Optional[Callable[[str], str]] = None) -> dict:
    """
    - 同一 order_id 重复调用 → 返回同一份发货记录（幂等：WebSocket 重放 / 重试不会多发）
    - 并发抢最后一张 → 只有一个事务 rowcount==1，其余抛 OutOfStock
    - 唯一索引 uq_card_order / uq_delivery_order 兜底
    compose(card_text) -> 最终存档的发货文本；不传则为 “固定内容\n卡密”。
    """
    if not order_id:
        raise ValueError("order_id required")
    with db() as conn, write_tx(conn):
        hit = _existing(conn, order_id)
        if hit:
            return hit
        card = conn.execute(
            "SELECT id, card_content FROM card_keys WHERE keyword=? AND is_used=0 ORDER BY id LIMIT 1",
            (keyword,),
        ).fetchone()
        if not card:
            raise OutOfStock(keyword)
        cur = conn.execute(
            "UPDATE card_keys SET is_used=1, order_id=?, used_at=CURRENT_TIMESTAMP WHERE id=? AND is_used=0",
            (order_id, card["id"]),
        )
        if cur.rowcount != 1:
            raise OutOfStock(keyword)
        content = compose(card["card_content"]) if compose else _default_compose(fixed_content, card["card_content"])
        conn.execute(
            "INSERT INTO delivery_logs (order_id, user_id, chat_id, keyword, delivery_content, confirm_status, "
            "status, card_id, updated_at) VALUES (?,?,?,?,?, '待发送', 'claimed', ?, CURRENT_TIMESTAMP)",
            (order_id, user_id, chat_id, keyword, content, card["id"]),
        )
        return {"order_id": order_id, "card_id": card["id"], "content": content,
                "status": "claimed", "idempotent": False}


def reserve_fixed_delivery(order_id: str, keyword: str, user_id: str, chat_id: str, content: str) -> dict:
    """纯固定内容（不走卡密池）也要按订单幂等记账，避免重复推送。"""
    with db() as conn, write_tx(conn):
        hit = _existing(conn, order_id)
        if hit:
            return hit
        conn.execute(
            "INSERT INTO delivery_logs (order_id, user_id, chat_id, keyword, delivery_content, confirm_status, "
            "status, updated_at) VALUES (?,?,?,?,?, '待发送', 'claimed', CURRENT_TIMESTAMP)",
            (order_id, user_id, chat_id, keyword, content),
        )
        return {"order_id": order_id, "card_id": None, "content": content, "status": "claimed", "idempotent": False}


def mark_delivery(order_id: str, status: Literal["sent", "failed"], confirm_status: Optional[str] = None):
    label = confirm_status or {"sent": "已发货", "failed": "发送失败"}[status]
    with db() as conn:
        conn.execute(
            "UPDATE delivery_logs SET status=?, confirm_status=?, updated_at=CURRENT_TIMESTAMP WHERE order_id=?",
            (status, label, order_id),
        )
