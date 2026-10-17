import sqlite3
from loguru import logger

from app.db.card_pool import (OutOfStock, claim_card_for_order, reserve_fixed_delivery, mark_delivery)
from app.services.delivery_service import XianyuDeliveryService

WILDCARDS = ("*", "默认", "通用")
RESTOCK_NOTICE = "【专属卡密】：⚠️ 当前卡密正在火速补货中，店主已收到通知，会第一时间补发给您！"


class AutoDeliveryManager:
    """自动发货管理器（固定内容 / 独享卡密 / 两者复合）。卡密出库统一走 app.db.card_pool。"""

    def __init__(self, db_path, xianyu_session):
        self.db_path = db_path
        self.delivery_service = XianyuDeliveryService(xianyu_session)

    def match_rule(self, item_title_or_id: str):
        """最长关键词优先：避免短词（如“卡”）抢先命中“月卡/年卡”这类更具体的规则。"""
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        try:
            rules = [dict(r) for r in conn.execute("SELECT * FROM auto_delivery_rules WHERE enabled=1")]
        finally:
            conn.close()
        text = item_title_or_id or ""
        specific = [r for r in rules if (r["keyword"] or "").strip() and r["keyword"].strip() not in WILDCARDS
                    and r["keyword"].strip() in text]
        if specific:
            return max(specific, key=lambda r: len(r["keyword"].strip()))
        for r in rules:
            if (r["keyword"] or "").strip() in WILDCARDS:
                return r
        return None

    @staticmethod
    def _compose(fixed: str, card: str = "") -> str:
        parts = []
        if fixed:
            parts.append(f"【下载与使用说明】：\n{fixed}")
        if card:
            parts.append(f"【您的专属卡密/激活码】：\n{card}")
        return "\n\n".join(parts)

    async def handle_paid_order(self, ws, send_msg_func, user_id, chat_id, order_id, item_title=""):
        """检测到“等待卖家发货”时触发。按 order_id 幂等：同一订单重复事件不会重复发送。"""
        logger.info(f"⚡ 自动发货: 买家={user_id}, 会话={chat_id}, 订单={order_id}")
        if not order_id:
            logger.warning("⚠️ 缺少订单号，无法幂等发货，跳过")
            return False

        rule = self.match_rule(item_title)
        if not rule:
            logger.warning(f"⚠️ 无匹配发货规则 (商品: {item_title})")
            return False

        keyword = rule["keyword"]
        fixed = (rule.get("delivery_content") or "").strip()
        use_pool = bool(rule.get("use_card_pool", 0))

        # 1) 锁定出库 + 记账（claimed）
        try:
            if use_pool:
                rec = claim_card_for_order(order_id, keyword, user_id, chat_id,
                                           compose=lambda card: self._compose(fixed, card))
            elif fixed:
                rec = reserve_fixed_delivery(order_id, keyword, user_id, chat_id, self._compose(fixed))
            else:
                logger.warning(f"⚠️ 规则 [{keyword}] 未配置任何发货内容")
                return False
        except OutOfStock:
            logger.error(f"🔴 卡密池库存不足: {keyword}（订单 {order_id} 未记账，补货后可手动补发）")
            notice = self._compose(fixed) + ("\n\n" if fixed else "") + RESTOCK_NOTICE
            await send_msg_func(ws, chat_id, user_id, f"【自动发货】亲，感谢您的购买！\n\n{notice}")
            return False
        except Exception:
            logger.exception(f"出库异常（订单 {order_id}），本次不发送，避免把数据库错误当成缺货")
            return False

        if rec["idempotent"] and rec["status"] == "sent":
            logger.info(f"↩️ 订单 {order_id} 已发过货，忽略重复事件")
            return True

        # 2) 发送私信（失败的单子保留同一张卡，补发复用）
        full_msg = (f"【自动发货】亲，感谢您的购买！宝贝内容如下：\n\n{rec['content']}\n\n"
                    f"如有任何问题随时发消息联系，祝您生活愉快！")
        try:
            await send_msg_func(ws, chat_id, user_id, full_msg)
        except Exception as e:
            logger.error(f"❌ 订单 {order_id} 私信发送失败: {e}")
            mark_delivery(order_id, "failed")
            return False
        # 注意：不再把卡密明文写进日志
        logger.info(f"✅ 订单 {order_id} 发货消息已送达 (card_id={rec.get('card_id')})")

        # 3) 自动确认发货
        confirm = "已发货"
        if rule.get("auto_confirm", 1):
            ok, msg = self.delivery_service.confirm_dummy_shipping(order_id, trade_text="虚拟商品已自动交付")
            confirm = "已发货·已确认" if ok else f"已发货·确认失败: {msg}"[:120]
        mark_delivery(order_id, "sent", confirm)
        return True
