"""卡密池：一卡一单、并发安全、幂等。

并发模型
- 所有抢占都在 BEGIN IMMEDIATE 事务里完成：SQLite 同一时刻只有一个写事务，
  "选卡 + 占卡" 是一条 UPDATE ... WHERE id=(SELECT ...) RETURNING，不存在读后写窗口。
- uq_card_reserved_order 唯一索引兜底：即便多进程/多实例同时为同一订单抢卡，也只会成功一张。
- 同一订单重复调用 reserve() 返回同一张卡（幂等），重试发货不会多耗卡。
需要 SQLite >= 3.35（RETURNING）。
"""
import sqlite3
from typing import Optional

from app.db.database import write_tx


class CardPool:
    def __init__(self, conn: sqlite3.Connection):
        self.conn = conn

    def reserve(self, keyword: str, order_id: str) -> Optional[sqlite3.Row]:
        with write_tx(self.conn):
            row = self.conn.execute(
                "SELECT id, card_content, status FROM card_keys WHERE reserved_order=?",
                (order_id,)).fetchone()
            if row:
                return row
            return self.conn.execute(
                """UPDATE card_keys
                   SET status='reserved', reserved_order=?, reserved_at=CURRENT_TIMESTAMP
                   WHERE id=(SELECT id FROM card_keys
                             WHERE keyword=? AND status='available'
                             ORDER BY id LIMIT 1)
                   RETURNING id, card_content, status""",
                (order_id, keyword)).fetchone()

    def mark_delivered(self, card_id: int, order_id: str) -> bool:
        """在调用方事务内使用（与订单状态迁移同一事务）。"""
        cur = self.conn.execute(
            """UPDATE card_keys SET status='delivered', is_used=1, order_id=?, used_at=CURRENT_TIMESTAMP
               WHERE id=? AND reserved_order=? AND status IN ('reserved','delivered')""",
            (order_id, card_id, order_id))
        return cur.rowcount == 1

    def release(self, order_id: str) -> int:
        """仅用于"确定没有发出去"的场景（如订单被取消/退款），否则宁可留给人工。"""
        with write_tx(self.conn):
            return self.conn.execute(
                """UPDATE card_keys SET status='available', reserved_order=NULL, reserved_at=NULL
                   WHERE reserved_order=? AND status='reserved'""", (order_id,)).rowcount

    def stock(self, keyword: str) -> dict:
        r = self.conn.execute(
            """SELECT SUM(status='available') a, SUM(status='reserved') r,
                      SUM(status='delivered') d, COUNT(*) t FROM card_keys WHERE keyword=?""",
            (keyword,)).fetchone()
        return {"available": r["a"] or 0, "reserved": r["r"] or 0,
                "delivered": r["d"] or 0, "total": r["t"] or 0}
