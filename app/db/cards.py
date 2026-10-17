"""卡密出库：全项目唯一的取卡入口（routes 与 delivery_manager 共用，避免两份实现漂移）。"""
import sqlite3


class StockLockError(RuntimeError):
    """拿不到写锁（并发过高 / 磁盘慢）。不是缺货，调用方应稍后重试，不能告诉买家“补货中”。"""


def claim_card(db_path: str, keyword: str, order_id: str):
    """原子提取一张可用卡密并绑定到订单。

    - 同一 order_id 重复调用返回同一张卡（幂等）。
    - 返回 None 表示该关键词确实无库存。
    - 拿不到锁抛 StockLockError。
    """
    keyword = (keyword or "").strip()
    order_id = (order_id or "").strip()
    if not keyword or not order_id:
        # 空订单号会让所有“无单号”的订单命中同一张卡（重复发给不同买家）
        raise ValueError("keyword 与 order_id 均不能为空")
    conn = sqlite3.connect(db_path, timeout=15, isolation_level=None)
    conn.row_factory = sqlite3.Row
    try:
        conn.execute("PRAGMA busy_timeout=15000;")
        try:
            conn.execute("BEGIN IMMEDIATE;")
        except sqlite3.OperationalError as e:
            raise StockLockError(str(e)) from e
        hit = conn.execute(
            "SELECT id, card_content FROM card_keys WHERE order_id=? LIMIT 1", (order_id,)
        ).fetchone()
        if hit:
            conn.execute("COMMIT;")
            return dict(hit)
        row = conn.execute(
            """
            UPDATE card_keys
               SET is_used=1, order_id=?, used_at=CURRENT_TIMESTAMP
             WHERE id = (SELECT id FROM card_keys
                          WHERE keyword=? AND is_used=0
                          ORDER BY id ASC LIMIT 1)
            RETURNING id, card_content;
            """,
            (order_id, keyword),
        ).fetchone()
        conn.execute("COMMIT;")
        return dict(row) if row else None
    except BaseException:
        if conn.in_transaction:
            conn.execute("ROLLBACK;")
        raise
    finally:
        conn.close()
