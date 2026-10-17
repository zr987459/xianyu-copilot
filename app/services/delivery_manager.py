import asyncio
import sqlite3
from loguru import logger
from app.services.delivery_service import XianyuDeliveryService
from app.db.cards import claim_cards, StockLockError, MAX_QUANTITY
from app.services.delivery_plan import (
    resolve_plan, format_delivery_messages, format_shortage_notice,
)

# delivery_logs.confirm_status 里这些前缀表示「还没真正发出」，补货 / 改规则后可以重新处理
PENDING_PREFIXES = ("缺货", "待人工")


class AutoDeliveryManager:
    """自动发货管理器：固定内容 / 独享卡密 / 两者复合；T-015 起支持多套餐（SKU）与一次拍多件。"""

    def __init__(self, db_path, xianyu_session):
        self.db_path = db_path
        self.delivery_service = XianyuDeliveryService(xianyu_session)
        self._order_locks = {}

    # ---------- 规则 ----------
    def _conn(self):
        conn = sqlite3.connect(self.db_path, timeout=15)
        conn.row_factory = sqlite3.Row
        return conn

    def match_rule(self, item_title_or_id):
        """根据标题或ID匹配发货规则（主关键词）"""
        conn = self._conn()
        try:
            rules = conn.execute("SELECT * FROM auto_delivery_rules WHERE enabled=1").fetchall()
        finally:
            conn.close()
        for r in rules:
            kw = (r['keyword'] or '').strip()
            if kw and kw not in ('*', '默认', '通用') and kw in (item_title_or_id or ''):
                return dict(r)
        for r in rules:
            if (r['keyword'] or '').strip() in ['*', '默认', '通用']:
                return dict(r)
        return None

    def rule_variants(self, rule_id):
        conn = self._conn()
        try:
            return [dict(r) for r in conn.execute(
                "SELECT * FROM delivery_rule_skus WHERE rule_id=? AND enabled=1 ORDER BY id", (rule_id,))]
        finally:
            conn.close()

    # ---------- 日志 / 幂等 ----------
    def already_delivered(self, order_id):
        """WSS 重推“已付款”事件时，同一订单不再重复发消息、重复确认（缺货 / 待人工的记录不算发过）"""
        conn = self._conn()
        try:
            return conn.execute(
                "SELECT 1 FROM delivery_logs WHERE order_id=? "
                "AND confirm_status NOT LIKE '缺货%' AND confirm_status NOT LIKE '待人工%' LIMIT 1",
                (order_id,),
            ).fetchone() is not None
        finally:
            conn.close()

    def _has_status(self, order_id, prefix):
        conn = self._conn()
        try:
            return conn.execute(
                "SELECT 1 FROM delivery_logs WHERE order_id=? AND confirm_status LIKE ? LIMIT 1",
                (order_id, prefix + '%')).fetchone() is not None
        finally:
            conn.close()

    def record_log(self, order_id, user_id, chat_id, keyword, content, confirm_status,
                   item_title="", sku_desc="", card_sku="", quantity=1):
        conn = self._conn()
        try:
            conn.execute('''
            INSERT INTO delivery_logs (order_id, user_id, chat_id, keyword, delivery_content, confirm_status,
                                       item_title, sku_desc, card_sku, quantity)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ''', (order_id, user_id, chat_id, keyword, content, confirm_status,
                  item_title or "", sku_desc or "", card_sku or "", quantity))
            conn.commit()
        finally:
            conn.close()

    def _log_once(self, order_id, prefix, **kw):
        if not self._has_status(order_id, prefix):
            self.record_log(order_id=order_id, **kw)

    # ---------- 主流程 ----------
    async def handle_paid_order(self, ws, send_msg_func, user_id, chat_id, order_id,
                                item_title="", sku_desc="", quantity=1):
        """检测到「等待卖家发货」时触发。

        sku_desc：订单里的规格文字（如「套餐:年卡」），由监听器从订单详情 / 消息卡片里取；
        quantity：购买件数。两者都可缺省，缺省时等同于旧版「一单一卡」行为。
        """
        if not order_id:
            logger.error("⚠️ 付款事件缺少订单号，转人工处理（无单号无法保证一单一卡）")
            return False
        lock = self._order_locks.setdefault(order_id, asyncio.Lock())
        try:
            async with lock:  # 同一订单的重复推送串行处理，避免同一批卡密发两遍
                return await self._handle(ws, send_msg_func, user_id, chat_id, order_id,
                                          item_title, sku_desc, quantity)
        finally:
            if not lock.locked():
                self._order_locks.pop(order_id, None)

    async def _handle(self, ws, send_msg_func, user_id, chat_id, order_id, item_title, sku_desc, quantity):
        tail = f"***{order_id[-4:]}"
        try:
            qty = int(quantity or 1)
        except (TypeError, ValueError):
            qty = 1
        qty = max(qty, 1)
        logger.info(f"⚡ 自动发货: 订单={tail} 规格={sku_desc or '-'} 数量={qty}")
        meta = dict(user_id=user_id, chat_id=chat_id, item_title=item_title,
                    sku_desc=sku_desc, quantity=qty)

        if self.already_delivered(order_id):
            logger.info(f"订单 {tail} 已发过货，忽略重复付款事件")
            return True

        rule = self.match_rule(item_title)
        if not rule:
            logger.warning(f"⚠️ 未找到匹配的自动发货规则 (商品: {item_title})，跳过发货")
            return False
        keyword = rule['keyword']

        if qty > MAX_QUANTITY:
            logger.error(f"订单 {tail} 数量 {qty} 超过自动发货上限 {MAX_QUANTITY}，转人工")
            self._log_once(order_id, "待人工:数量", keyword=keyword, content="",
                           confirm_status=f"待人工:数量{qty}超过上限{MAX_QUANTITY}", **meta)
            return False

        plan = resolve_plan(rule, self.rule_variants(rule['id']), sku_desc)
        if plan is None:
            # 规则配了套餐却对不上：宁可转人工，也不能把月卡发给拍年卡的买家
            logger.error(f"订单 {tail} 规格「{sku_desc}」未匹配规则 [{keyword}] 的任何套餐，转人工")
            self._log_once(order_id, "待人工:套餐", keyword=keyword, content="",
                           confirm_status="待人工:套餐未匹配", **meta)
            return False
        meta["card_sku"] = plan.card_sku

        cards = []
        if plan.use_card_pool:
            try:
                res = claim_cards(self.db_path, keyword, order_id, qty, plan.card_sku)
            except StockLockError:
                logger.warning(f"卡密库繁忙，订单 {tail} 稍后重试（未通知买家）")
                return False
            if not res.complete:
                logger.error(f"🔴 库存不足: [{keyword}/{plan.card_sku or '默认'}] 需要 {qty} 张，可用 {res.available}")
                if not self._has_status(order_id, "缺货"):
                    notice = format_shortage_notice(plan, qty, res.available)
                    await send_msg_func(ws, chat_id, user_id, notice)
                    self.record_log(order_id=order_id, keyword=keyword, content=notice,
                                    confirm_status="缺货待补发", **meta)
                return False
            cards = res.cards
            logger.info(f"🎫 订单 {tail} 已绑定 {len(cards)} 张卡密")  # 不落卡密明文

        msgs = format_delivery_messages(plan, cards, qty)
        if not msgs:
            logger.warning(f"⚠️ 规则 [{keyword}] 未配置任何发货内容")
            return False
        for m in msgs:
            await send_msg_func(ws, chat_id, user_id, m)
        logger.info(f"✅ 发货消息已送达买家（{len(msgs)} 条）")

        confirm_status = "未开启自动确认"
        if plan.auto_confirm:
            success, msg = await asyncio.to_thread(
                self.delivery_service.confirm_dummy_shipping, order_id, "虚拟商品已自动交付")
            confirm_status = "成功" if success else f"失败: {msg}"

        self.record_log(order_id=order_id, keyword=keyword, content="\n\n".join(msgs),
                        confirm_status=confirm_status, **meta)
        return True

    async def retry_pending(self, ws, send_msg_func, limit=50):
        """补货或改完套餐规则后调用：把「缺货待补发 / 待人工」且尚未发出的订单重新走一遍。
        返回本次成功发出的订单数。监听器可在每次导入卡密后、或每隔几分钟调用一次。"""
        conn = self._conn()
        try:
            rows = conn.execute("""
                SELECT order_id, user_id, chat_id, keyword, item_title, sku_desc, quantity, MAX(id) AS last_id
                  FROM delivery_logs
                 WHERE (confirm_status LIKE '缺货%' OR confirm_status LIKE '待人工%')
                   AND order_id NOT IN (SELECT order_id FROM delivery_logs
                                         WHERE confirm_status NOT LIKE '缺货%'
                                           AND confirm_status NOT LIKE '待人工%')
                 GROUP BY order_id ORDER BY last_id LIMIT ?""", (limit,)).fetchall()
        finally:
            conn.close()
        done = 0
        for r in rows:
            ok = await self.handle_paid_order(ws, send_msg_func, r['user_id'], r['chat_id'], r['order_id'],
                                              r['item_title'] or r['keyword'], r['sku_desc'] or "",
                                              r['quantity'] or 1)
            done += bool(ok) and self.already_delivered(r['order_id'])
        return done
