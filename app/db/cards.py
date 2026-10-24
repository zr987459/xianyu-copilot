"""卡密出库：全项目唯一的取卡入口（routes 与 delivery_manager 共用，避免多份实现漂移）。

一单多件：同一订单的第 1..N 张卡用 (order_id, order_seq) 唯一绑定。
重复调用只补发缺的那几件，已绑定的原样返回，所以“补货后重跑”和“事件重推”都是幂等的。
"""
import sqlite3
from dataclasses import dataclass, field
from typing import List, Optional

MAX_QUANTITY = 50  # 单笔订单最多自动出库张数，超过转人工，防止异常数量一次掏空库存


class StockLockError(RuntimeError):
    """拿不到写锁（并发过高 / 磁盘慢）。不是缺货，调用方应稍后重试，不能告诉买家“补货中”。"""


@dataclass
class ClaimResult:
    requested: int
    cards: List[dict] = field(default_factory=list)   # [{id, card_content, order_seq}]，按 order_seq 升序
    newly_claimed: List[dict] = field(default_factory=list)  # 本次调用新绑定的（只需给买家发这部分）

    @property
    def delivered(self) -> int:
        return len(self.cards)

    @property
    def shortage(self) -> int:
        return max(self.requested - len(self.cards), 0)

    @property
    def complete(self) -> bool:
        return self.shortage == 0


def _connect(db_path: str) -> sqlite3.Connection:
    conn = sqlite3.connect(db_path, timeout=15, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA busy_timeout=15000;")
    return conn


def claim_cards(db_path: str, keyword: str, order_id: str, quantity: int = 1,
                sku: str = "", allow_partial: bool = True) -> ClaimResult:
    """在一个 BEGIN IMMEDIATE 事务里为订单原子出库 quantity 张卡。

    - sku=''：商品通用池；sku='月卡'：只从该套餐的卡里出，绝不跨套餐挪用。
    - 已绑定 k 张时只补 quantity-k 张（幂等 + 缺货补发）。
    - allow_partial=False（hold 策略）：库存不够补齐时本次一张都不出，等补货后再一次性发齐。
    - 拿不到锁抛 StockLockError；参数非法抛 ValueError。
    """
    keyword = (keyword or "").strip()
    order_id = (order_id or "").strip()
    sku = (sku or "").strip()
    if not keyword or not order_id:
        # 空订单号会让所有“无单号”的订单命中同一批卡（重复发给不同买家）
        raise ValueError("keyword 与 order_id 均不能为空")
    if isinstance(quantity, bool) or not isinstance(quantity, int) or quantity < 1 or quantity > MAX_QUANTITY:
        raise ValueError(f"quantity 必须在 1..{MAX_QUANTITY} 之间")

    conn = _connect(db_path)
    try:
        try:
            conn.execute("BEGIN IMMEDIATE;")
        except sqlite3.OperationalError as e:
            raise StockLockError(str(e)) from e

        rows = conn.execute(
            "SELECT id, card_content, order_seq, keyword, sku FROM card_keys WHERE order_id=? ORDER BY order_seq ASC",
            (order_id,),
        ).fetchall()
        # 同一订单的卡必须来自同一个池：已绑定的卡与本次 (keyword, sku) 不一致时拒绝补发，
        # 否则规则/规格改动后重跑会出现“前 2 张月卡 + 第 3 张年卡”的混发订单。
        for r in rows:
            if (r["keyword"] or "").strip() != keyword or (r["sku"] or "").strip() != sku:
                raise ValueError(
                    f"订单已从 [{r['keyword']}/{r['sku'] or '通用'}] 出库，拒绝从 [{keyword}/{sku or '通用'}] 补发"
                )
        existing = [{"id": r["id"], "card_content": r["card_content"], "order_seq": r["order_seq"]} for r in rows]
        result = ClaimResult(requested=quantity, cards=existing)
        need = quantity - len(existing)
        if need <= 0:
            conn.execute("COMMIT;")
            result.cards = existing[:quantity] if len(existing) > quantity else existing
            return result

        available = conn.execute(
            "SELECT COUNT(*) FROM card_keys WHERE keyword=? AND sku=? AND is_used=0",
            (keyword, sku),
        ).fetchone()[0]
        if available < need and not allow_partial:
            conn.execute("COMMIT;")
            return result

        next_seq = max([c["order_seq"] or 0 for c in existing], default=0) + 1
        for _ in range(min(need, available)):
            row = conn.execute(
                """
                UPDATE card_keys
                   SET is_used=1, order_id=?, order_seq=?, used_at=CURRENT_TIMESTAMP
                 WHERE id = (SELECT id FROM card_keys
                              WHERE keyword=? AND sku=? AND is_used=0
                              ORDER BY id ASC LIMIT 1)
                   AND is_used=0
                RETURNING id, card_content, order_seq;
                """,
                (order_id, next_seq, keyword, sku),
            ).fetchone()
            if row is None:  # 理论上持锁期间不会发生，防御性退出
                break
            result.newly_claimed.append(dict(row))
            next_seq += 1
        conn.execute("COMMIT;")
        result.cards = existing + result.newly_claimed
        return result
    except BaseException:
        if conn.in_transaction:
            conn.execute("ROLLBACK;")
        raise
    finally:
        conn.close()


def claim_card(db_path: str, keyword: str, order_id: str, sku: str = "") -> Optional[dict]:
    """兼容旧接口：一单一卡。缺货返回 None。"""
    res = claim_cards(db_path, keyword, order_id, quantity=1, sku=sku)
    return res.cards[0] if res.cards else None


CIRCLED = "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳"


def format_cards_message(cards: List[dict], total: int, sku: str = "", note: str = "") -> str:
    """多卡密消息：标题 + 带序号的卡密列表 + 温馨提示。

    cards 为 [{order_seq, card_content}]；序号用 order_seq，补发时编号与首发连续（④⑤ 接着 ①②③）。
    """
    lines = [f"【您的专属卡密{(' · ' + sku) if sku else ''} × {total}】"]
    if note:
        lines.append(note)
    for c in sorted(cards, key=lambda x: int(x.get("order_seq") or 0)):
        seq = int(c.get("order_seq") or 0)
        mark = CIRCLED[seq - 1] if 1 <= seq <= len(CIRCLED) else f"{seq}."
        lines.append(f"{mark} {c['card_content']}")
    tip = "温馨提示：每张卡密仅限激活一次，请妥善保存，勿截图外传。"
    if total > 1:
        tip = f"温馨提示：本单共 {total} 件，每件一张独立卡密，按序号逐个激活即可；请妥善保存，勿截图外传。"
    lines.append(tip)
    return "\n".join(lines)


def stock_by_sku(db_path: str, keyword: Optional[str] = None) -> List[dict]:
    """库存分布：[{keyword, sku, unused, used}]"""
    conn = _connect(db_path)
    try:
        q = ("SELECT keyword, sku, SUM(is_used=0) AS unused, SUM(is_used=1) AS used "
             "FROM card_keys {} GROUP BY keyword, sku ORDER BY keyword, sku")
        if keyword:
            rows = conn.execute(q.format("WHERE keyword=?"), (keyword,)).fetchall()
        else:
            rows = conn.execute(q.format("")).fetchall()
        return [{"keyword": r["keyword"], "sku": r["sku"], "unused": r["unused"] or 0, "used": r["used"] or 0}
                for r in rows]
    finally:
        conn.close()
