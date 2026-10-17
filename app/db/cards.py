"""卡密出库：全项目唯一的取卡入口（routes 与 delivery_manager 共用，避免两份实现漂移）。

T-015 起支持：
- sku：同一商品的不同套餐（月卡 / 季卡 / 年卡、基础版 / 专业版）各有独立卡池
- quantity：一次拍 N 件，单个事务内一次性出库 N 张独享卡密，要么全给、要么一张不占
"""
import sqlite3
import unicodedata
from dataclasses import dataclass, field
from typing import List, Optional

MAX_QUANTITY = 50  # 单笔订单最多自动发几张；超过转人工，防止异常数量一把清空卡池


class StockLockError(RuntimeError):
    """拿不到写锁（并发过高 / 磁盘慢）。不是缺货，调用方应稍后重试，不能告诉买家“补货中”。"""


def normalize_sku(sku: Optional[str]) -> str:
    """套餐名入库 / 比较前统一：全角转半角、去掉所有空白。大小写保留用于展示，比较时另行 casefold。"""
    s = unicodedata.normalize("NFKC", sku or "")
    return "".join(s.split())


@dataclass
class ClaimResult:
    requested: int
    cards: List[dict] = field(default_factory=list)  # [{id, card_content, line}]，按 line 升序
    available: int = 0                                # 本次判断时该套餐剩余可用张数
    newly_claimed: int = 0

    @property
    def complete(self) -> bool:
        return len(self.cards) >= self.requested

    @property
    def shortage(self) -> int:
        return max(0, self.requested - len(self.cards))


def _connect(db_path):
    conn = sqlite3.connect(db_path, timeout=15, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout=15000;")
    return conn


def claim_cards(db_path: str, keyword: str, order_id: str, quantity: int = 1,
                sku: str = "", allow_partial: bool = False) -> ClaimResult:
    """原子批量出库：为 order_id 绑定 quantity 张 (keyword, sku) 的独享卡密。

    - 幂等：同一 order_id 重复调用返回已绑定的同一批卡（顺序、编号不变），不会多占。
    - 全有或全无（默认）：库存不足 quantity 时一张都不占，返回 complete=False，
      避免“买 5 张先发 2 张”后闲鱼侧无法部分确认发货、买家纠纷。
    - allow_partial=True 时先占能占的，后续补货再次调用会从第 len+1 张继续补齐。
    - 拿不到锁抛 StockLockError；参数非法抛 ValueError。
    """
    keyword = (keyword or "").strip()
    order_id = (order_id or "").strip()
    sku = normalize_sku(sku)
    if not keyword or not order_id:
        # 空订单号会让所有“无单号”的订单命中同一批卡（重复发给不同买家）
        raise ValueError("keyword 与 order_id 均不能为空")
    if not isinstance(quantity, int) or isinstance(quantity, bool) or not 1 <= quantity <= MAX_QUANTITY:
        raise ValueError(f"quantity 必须是 1~{MAX_QUANTITY} 的整数")

    conn = _connect(db_path)
    try:
        try:
            conn.execute("BEGIN IMMEDIATE;")
        except sqlite3.OperationalError as e:
            raise StockLockError(str(e)) from e

        held = [dict(r) for r in conn.execute(
            "SELECT id, card_content, order_line AS line FROM card_keys "
            "WHERE order_id=? ORDER BY order_line, id", (order_id,))]
        need = quantity - len(held)
        if need <= 0:
            conn.execute("COMMIT;")
            return ClaimResult(requested=quantity, cards=held)

        available = conn.execute(
            "SELECT COUNT(*) FROM card_keys WHERE keyword=? AND sku=? AND is_used=0",
            (keyword, sku)).fetchone()[0]
        if available < need and not allow_partial:
            conn.execute("COMMIT;")
            return ClaimResult(requested=quantity, cards=held, available=available)

        next_line = max((h["line"] or 0 for h in held), default=0) + 1
        new = []
        for i in range(min(need, available)):
            row = conn.execute(
                """
                UPDATE card_keys
                   SET is_used=1, order_id=?, order_line=?, used_at=CURRENT_TIMESTAMP
                 WHERE id = (SELECT id FROM card_keys
                              WHERE keyword=? AND sku=? AND is_used=0
                              ORDER BY id ASC LIMIT 1)
                RETURNING id, card_content, order_line AS line;
                """,
                (order_id, next_line + i, keyword, sku),
            ).fetchone()
            if row is None:  # 理论上不会发生：IMMEDIATE 锁内已数过库存
                break
            new.append(dict(row))
        conn.execute("COMMIT;")
        return ClaimResult(requested=quantity, cards=held + new,
                           available=available - len(new), newly_claimed=len(new))
    except BaseException:
        if conn.in_transaction:
            conn.execute("ROLLBACK;")
        raise
    finally:
        conn.close()


def claim_card(db_path: str, keyword: str, order_id: str, sku: str = ""):
    """兼容旧调用：单张出库。返回 {id, card_content} 或 None（缺货）。"""
    res = claim_cards(db_path, keyword, order_id, 1, sku)
    if not res.cards:
        return None
    c = res.cards[0]
    return {"id": c["id"], "card_content": c["card_content"]}


def stock_by_sku(conn, keyword: Optional[str] = None) -> List[dict]:
    """库存分布：[{keyword, sku, total, unused, used}]，sku 为空串表示默认套餐。"""
    q = ("SELECT keyword, sku, COUNT(*) AS total, "
         "SUM(CASE WHEN is_used=0 THEN 1 ELSE 0 END) AS unused, "
         "SUM(CASE WHEN is_used=1 THEN 1 ELSE 0 END) AS used FROM card_keys")
    args = ()
    if keyword:
        q += " WHERE keyword=?"
        args = (keyword,)
    q += " GROUP BY keyword, sku ORDER BY keyword, sku"
    return [{**dict(r), "unused": r["unused"] or 0, "used": r["used"] or 0}
            for r in conn.execute(q, args)]
