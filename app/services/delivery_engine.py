"""自动发货状态机。

            ┌──────────── 无库存 ───────────► OUT_OF_STOCK ──(补货后 retry)──┐
  PAID ──► RESERVED ──► SENT ──► CONFIRMED                                    │
   ▲          │  发送异常   │ 确认失败                                         │
   │          ▼            ▼                                                  │
   │     SEND_FAILED   CONFIRM_FAILED ──(重试 confirm，不再重发卡密)            │
   │          │ 超过 MAX_ATTEMPTS                                              │
   │          ▼                                                               │
   └────── MANUAL（人工处理；卡仍为 reserved，不会被别人拿走）◄────────────────┘

原则
1. 每次迁移都是 CAS：UPDATE ... WHERE order_id=? AND state=?，rowcount==0 说明别人已推进，直接放弃。
2. 卡密一旦为某订单 reserved，重试永远发同一张卡；只有确认未发出（取消/退款）才 release。
3. SENT 与"卡密标记 delivered"在同一事务；买家消息发出前不标记，发出后立即标记。
4. 网络调用（发消息、确认发货）不在事务内执行，避免长时间持有写锁。
"""
import asyncio
from dataclasses import dataclass
from typing import Awaitable, Callable, Optional

from loguru import logger

from app.db.database import write_tx
from app.services.card_pool import CardPool

PAID, RESERVED, SENT, CONFIRMED = "PAID", "RESERVED", "SENT", "CONFIRMED"
OUT_OF_STOCK, SEND_FAILED, CONFIRM_FAILED, MANUAL = "OUT_OF_STOCK", "SEND_FAILED", "CONFIRM_FAILED", "MANUAL"
TERMINAL = {CONFIRMED, MANUAL}
MAX_ATTEMPTS = 3

SendFn = Callable[[str, str], Awaitable[None]]            # (chat_id, text) -> None，失败抛异常
ConfirmFn = Callable[[str], Awaitable[tuple]]              # (order_id) -> (ok, msg)


@dataclass
class PaidOrder:
    order_id: str
    buyer_id: str
    chat_id: str
    item_id: str
    keyword: str            # 用于匹配 auto_delivery_rules.keyword


class DeliveryEngine:
    def __init__(self, conn, send_fn: SendFn, confirm_fn: ConfirmFn):
        self.conn = conn
        self.pool = CardPool(conn)
        self.send_fn = send_fn
        self.confirm_fn = confirm_fn
        self._locks: dict[str, asyncio.Lock] = {}

    # ---------- 基础 ----------
    def _cas(self, order_id, frm, to, **fields) -> bool:
        sets = ", ".join(f"{k}=?" for k in fields)
        sql = f"UPDATE delivery_orders SET state=?, updated_at=CURRENT_TIMESTAMP{', ' + sets if sets else ''} " \
              f"WHERE order_id=? AND state=?"
        return self.conn.execute(sql, (to, *fields.values(), order_id, frm)).rowcount == 1

    def _get(self, order_id):
        return self.conn.execute("SELECT * FROM delivery_orders WHERE order_id=?", (order_id,)).fetchone()

    def _rule(self, keyword):
        return self.conn.execute(
            "SELECT * FROM auto_delivery_rules WHERE keyword=? AND enabled=1", (keyword,)).fetchone()

    # ---------- 入口 ----------
    async def on_paid(self, o: PaidOrder):
        """闲鱼推送"买家已付款"时调用；重复推送安全。"""
        with write_tx(self.conn):
            self.conn.execute(
                """INSERT OR IGNORE INTO delivery_orders(order_id,buyer_id,chat_id,item_id,keyword,state)
                   VALUES(?,?,?,?,?,?)""", (o.order_id, o.buyer_id, o.chat_id, o.item_id, o.keyword, PAID))
        await self.advance(o.order_id)

    async def advance(self, order_id: str):
        lock = self._locks.setdefault(order_id, asyncio.Lock())
        async with lock:                       # 进程内串行；跨进程靠 CAS
            for _ in range(6):                 # 每步推进一格，防止死循环
                row = self._get(order_id)
                if row is None or row["state"] in TERMINAL:
                    return
                step = getattr(self, f"_on_{row['state'].lower()}", None)
                if step is None or not await step(row):
                    return

    # ---------- 各状态 ----------
    async def _on_paid(self, row) -> bool:
        rule = self._rule(row["keyword"])
        if rule is None:
            with write_tx(self.conn):
                return self._cas(row["order_id"], PAID, MANUAL, last_error="no_rule")
        parts = [rule["delivery_content"] or ""]
        card_id = None
        if rule["use_card_pool"]:
            card = self.pool.reserve(row["keyword"], row["order_id"])
            if card is None:
                with write_tx(self.conn):
                    self._cas(row["order_id"], PAID, OUT_OF_STOCK, last_error="out_of_stock")
                logger.warning(f"订单 {row['order_id']} 卡密库存不足")
                return False
            card_id = card["id"]
            parts.append(f"卡密：{card['card_content']}")
        content = "\n".join(p for p in parts if p.strip())
        with write_tx(self.conn):
            return self._cas(row["order_id"], PAID, RESERVED, card_id=card_id, content=content)

    async def _on_reserved(self, row) -> bool:
        try:
            await self.send_fn(row["chat_id"], row["content"])
        except Exception as e:  # 发送结果不确定：保留卡，进入重试
            with write_tx(self.conn):
                self._cas(row["order_id"], RESERVED, SEND_FAILED,
                          attempts=row["attempts"] + 1, last_error=f"send:{type(e).__name__}")
            return False
        with write_tx(self.conn):
            if row["card_id"]:
                self.pool.mark_delivered(row["card_id"], row["order_id"])
            ok = self._cas(row["order_id"], RESERVED, SENT)
            self.conn.execute(
                "INSERT INTO delivery_logs(order_id,user_id,chat_id,keyword,delivery_content,confirm_status) "
                "VALUES(?,?,?,?,?,?)",
                (row["order_id"], row["buyer_id"], row["chat_id"], row["keyword"], "[已发送]", "pending"))
            return ok

    async def _on_send_failed(self, row) -> bool:
        with write_tx(self.conn):
            to = MANUAL if row["attempts"] >= MAX_ATTEMPTS else RESERVED
            return self._cas(row["order_id"], SEND_FAILED, to) and to == RESERVED

    async def _on_sent(self, row) -> bool:
        rule = self._rule(row["keyword"])
        if rule is not None and not rule["auto_confirm"]:
            with write_tx(self.conn):
                return self._cas(row["order_id"], SENT, CONFIRMED, last_error="auto_confirm_off")
        ok, msg = await self.confirm_fn(row["order_id"])
        with write_tx(self.conn):
            self.conn.execute("UPDATE delivery_logs SET confirm_status=? WHERE order_id=?",
                              ("ok" if ok else "failed", row["order_id"]))
            if ok:
                return self._cas(row["order_id"], SENT, CONFIRMED)
            return self._cas(row["order_id"], SENT, CONFIRM_FAILED,
                             attempts=row["attempts"] + 1, last_error=f"confirm:{str(msg)[:80]}") and False

    async def _on_confirm_failed(self, row) -> bool:
        with write_tx(self.conn):
            to = MANUAL if row["attempts"] >= MAX_ATTEMPTS else SENT
            return self._cas(row["order_id"], CONFIRM_FAILED, to) and to == SENT

    async def _on_out_of_stock(self, row) -> bool:
        with write_tx(self.conn):            # 补货后由 retry_pending() 拉回 PAID
            avail = self.pool.stock(row["keyword"])["available"]
            return avail > 0 and self._cas(row["order_id"], OUT_OF_STOCK, PAID)

    # ---------- 定时补偿 ----------
    async def retry_pending(self):
        rows = self.conn.execute(
            "SELECT order_id FROM delivery_orders WHERE state IN (?,?,?,?,?) "
            "AND updated_at < datetime('now','-30 seconds')",
            (PAID, RESERVED, SEND_FAILED, CONFIRM_FAILED, OUT_OF_STOCK)).fetchall()
        for r in rows:
            try:
                await self.advance(r["order_id"])
            except Exception as e:
                logger.error(f"补偿推进 {r['order_id']} 失败: {type(e).__name__}")
