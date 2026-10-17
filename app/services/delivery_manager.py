import sqlite3
import os
import json
from datetime import datetime
from loguru import logger
from app.services.delivery_service import XianyuDeliveryService

class AutoDeliveryManager:
    """自动发货管理器 (支持：固定内容、独立卡密、以及两者同时发放的复合模式)"""
    
    def __init__(self, db_path, xianyu_session):
        self.db_path = db_path
        self.delivery_service = XianyuDeliveryService(xianyu_session)

    def match_rule(self, item_title_or_id):
        """根据标题或ID匹配发货规则"""
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        c = conn.cursor()
        rules = c.execute("SELECT * FROM auto_delivery_rules WHERE enabled=1").fetchall()
        conn.close()

        for r in rules:
            kw = r['keyword'].strip()
            if kw and (kw in item_title_or_id):
                return dict(r)
        
        # 兜底通用规则
        for r in rules:
            if r['keyword'].strip() in ['*', '默认', '通用']:
                return dict(r)

        return None

    def pop_card_key_atomic(self, keyword, order_id):
        """从卡密池中原子提取一张未使用卡密并核销（事务锁防超卖）"""
        conn = sqlite3.connect(self.db_path)
        conn.row_factory = sqlite3.Row
        try:
            conn.execute("PRAGMA busy_timeout=5000;")
            conn.execute("BEGIN IMMEDIATE;")
            # 若该订单已有分配记录，直接幂等返回
            hit = conn.execute(
                "SELECT card_content FROM card_keys WHERE order_id=? LIMIT 1",
                (order_id,),
            ).fetchone()
            if hit:
                conn.commit()
                return hit["card_content"]

            row = conn.execute(
                """
                UPDATE card_keys
                   SET is_used=1, order_id=?, used_at=CURRENT_TIMESTAMP
                 WHERE id = (SELECT id FROM card_keys
                              WHERE keyword=? AND is_used=0
                              ORDER BY id ASC LIMIT 1)
                RETURNING card_content;
                """,
                (order_id, keyword),
            ).fetchone()
            conn.commit()
            return row["card_content"] if row else None
        except Exception as e:
            conn.rollback()
            logger.error(f"提取卡密异常: {e}")
            return None
        finally:
            conn.close()

    def record_log(self, order_id, user_id, chat_id, keyword, content, confirm_status):
        conn = sqlite3.connect(self.db_path)
        c = conn.cursor()
        c.execute('''
        INSERT INTO delivery_logs (order_id, user_id, chat_id, keyword, delivery_content, confirm_status)
        VALUES (?, ?, ?, ?, ?, ?)
        ''', (order_id, user_id, chat_id, keyword, content, confirm_status))
        conn.commit()
        conn.close()

    async def handle_paid_order(self, ws, send_msg_func, user_id, chat_id, order_id, item_title=""):
        """当检测到等待卖家发货时触发"""
        logger.info(f"⚡ 开始触发自动发货处理: 买家={user_id}, 会话={chat_id}, 订单号={order_id}")
        
        rule = self.match_rule(item_title)
        if not rule:
            logger.warning(f"⚠️ 未找到匹配的自动发货规则 (商品: {item_title})，跳过发货")
            return False

        keyword = rule['keyword']
        fixed_content = (rule.get('delivery_content') or '').strip()
        use_card_pool = bool(rule.get('use_card_pool', 0))

        content_parts = []

        # 1. 如果有固定内容（网盘/说明/软件地址），加入
        if fixed_content:
            content_parts.append(f"【下载与使用说明】：\n{fixed_content}")

        # 2. 如果开启了卡密池，原子抽取一张专属独享卡密
        if use_card_pool:
            card = self.pop_card_key_atomic(keyword, order_id)
            if card:
                content_parts.append(f"【您的专属卡密/激活码】：\n{card}")
                logger.info(f"🎫 从卡密池为订单 {order_id} 抽取并核销卡密: {card}")
            else:
                logger.error(f"🔴 卡密池库存不足！关键词: {keyword}")
                content_parts.append("【专属卡密】：⚠️ 当前卡密正在火速补货中，店主已收到通知，会第一时间补发给您！")

        if not content_parts:
            logger.warning(f"⚠️ 规则 [{keyword}] 未配置任何发货内容")
            return False

        delivery_text = "\n\n".join(content_parts)
        full_msg = f"【自动发货】亲，感谢您的购买！宝贝内容如下：\n\n{delivery_text}\n\n如有任何问题随时发消息联系，祝您生活愉快！"

        # 发送私信
        await send_msg_func(ws, chat_id, user_id, full_msg)
        logger.info(f"✅ 发货消息已通过私聊送达买家: {user_id}")

        # 3. 自动确认发货接口
        confirm_status = "未开启自动确认"
        if rule.get('auto_confirm', 1) and order_id and "正在火速补货" not in delivery_text:
            logger.info(f"🚀 正在调用闲鱼官方无需物流接口自动确认发货 (订单: {order_id})...")
            success, msg = self.delivery_service.confirm_dummy_shipping(order_id, trade_text="虚拟商品已自动交付")
            confirm_status = "成功" if success else f"失败: {msg}"

        # 4. 记录日志
        self.record_log(order_id, user_id, chat_id, keyword, delivery_text, confirm_status)
        return True
