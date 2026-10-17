import re
import sqlite3
from typing import Any, Optional, Tuple

from loguru import logger

from app.db.cards import MAX_QUANTITY, StockLockError, claim_cards, format_cards_message
from app.services.delivery_service import XianyuDeliveryService

GENERIC_KEYWORDS = {"*", "默认", "通用"}

# 订单事件里可能承载规格/数量的字段名（闲鱼不同推送版本命名不一，需用真实 WSS 报文校准）
SKU_KEYS = ("skuDesc", "sku_desc", "skuInfo", "skuText", "specDesc", "spec_desc", "skuName", "propertiesName")
QTY_KEYS = ("buyAmount", "buy_amount", "quantity", "itemNum", "item_num", "buyQuantity", "amount", "count")

def _norm(text: str) -> str:
    """规格文本归一：去空白、全角冒号/分号转半角、小写"""
    text = (text or "").replace("：", ":").replace("；", ";").replace("，", ",")
    return re.sub(r"\s+", "", text).lower()


def _walk(obj: Any, keys: Tuple[str, ...]):
    if isinstance(obj, dict):
        for k, v in obj.items():
            if k in keys and v not in (None, "", [], {}):
                yield v
            yield from _walk(v, keys)
    elif isinstance(obj, list):
        for v in obj:
            yield from _walk(v, keys)


def parse_order_meta(payload: Any) -> Tuple[str, int]:
    """从订单事件 / 订单详情里尽力提取 (规格文本, 购买件数)。取不到规格返回 ''，取不到件数返回 1。"""
    sku_desc = ""
    for v in _walk(payload, SKU_KEYS):
        sku_desc = v if isinstance(v, str) else str(v)
        break
    qty = 1
    for v in _walk(payload, QTY_KEYS):
        try:
            n = int(str(v).strip())
        except ValueError:
            continue
        if n >= 1:
            qty = n
            break
    return sku_desc.strip(), qty


class AutoDeliveryManager:
    """自动发货管理器：固定内容 / 独享卡密 / 二者复合，支持多规格 (SKU) 与一单多件。"""

    def __init__(self, db_path, xianyu_session):
        self.db_path = db_path
        self.delivery_service = XianyuDeliveryService(xianyu_session)

    def _conn(self):
        conn = sqlite3.connect(self.db_path, timeout=15)
        conn.row_factory = sqlite3.Row
        return conn

    # ---------- 规则匹配 ----------
    def match_rule(self, item_title_or_id: str) -> Optional[dict]:
        """标题/商品ID 命中主规则；多个命中时最长关键词优先（避免“月卡”抢走“网易云月卡”）。"""
        conn = self._conn()
        try:
            rules = [dict(r) for r in conn.execute("SELECT * FROM auto_delivery_rules WHERE enabled=1")]
        finally:
            conn.close()
        text = item_title_or_id or ""
        hits = [r for r in rules if (r["keyword"] or "").strip()
                and r["keyword"].strip() not in GENERIC_KEYWORDS and r["keyword"].strip() in text]
        if hits:
            return max(hits, key=lambda r: len(r["keyword"].strip()))
        for r in rules:
            if (r["keyword"] or "").strip() in GENERIC_KEYWORDS:
                return r
        return None

    def rule_skus(self, rule_id: int):
        conn = self._conn()
        try:
            return [dict(r) for r in conn.execute(
                "SELECT * FROM delivery_rule_skus WHERE rule_id=? ORDER BY id", (rule_id,))]
        finally:
            conn.close()

    def match_sku(self, rule: dict, sku_desc: str) -> Tuple[Optional[dict], str]:
        """返回 (套餐行, 状态)。状态: none=规则未细分套餐 / matched / unmatched=有细分但对不上（转人工）。

        套餐名与别名都参与匹配，命中多个时取最长的那个词（“专业版年卡” 优先于 “年卡”）。
        """
        skus = self.rule_skus(rule["id"])
        if not skus:
            return None, "none"
        text = _norm(sku_desc)
        if not text:
            return None, "unmatched"
        best, best_len = None, 0
        for s in skus:
            tokens = [s["sku_name"]] + [a for a in re.split(r"[,，|/、\n]", s["aliases"] or "") if a.strip()]
            for t in tokens:
                t = _norm(t)
                if t and t in text and len(t) > best_len:
                    best, best_len = s, len(t)
        return (best, "matched") if best else (None, "unmatched")

    # ---------- 订单状态 ----------
    def get_order(self, order_id: str) -> Optional[dict]:
        conn = self._conn()
        try:
            r = conn.execute("SELECT * FROM delivery_orders WHERE order_id=?", (order_id,)).fetchone()
            return dict(r) if r else None
        finally:
            conn.close()

    def upsert_order(self, order_id, **fields):
        cols = ["order_id"] + list(fields)
        vals = [order_id] + list(fields.values())
        updates = ", ".join(f"{k}=excluded.{k}" for k in fields) + ", updated_at=CURRENT_TIMESTAMP"
        conn = self._conn()
        try:
            conn.execute(
                f"INSERT INTO delivery_orders ({', '.join(cols)}) VALUES ({', '.join('?' * len(cols))}) "
                f"ON CONFLICT(order_id) DO UPDATE SET {updates}",
                vals,
            )
            conn.commit()
        finally:
            conn.close()

    def pending_orders(self):
        """缺货待补发的订单（补货后由监听进程逐个调用 handle_paid_order 重跑即可，幂等）"""
        conn = self._conn()
        try:
            return [dict(r) for r in conn.execute(
                "SELECT * FROM delivery_orders WHERE status IN ('partial','pending_stock') ORDER BY created_at")]
        finally:
            conn.close()

    def record_log(self, order_id, user_id, chat_id, keyword, content, confirm_status):
        conn = self._conn()
        try:
            conn.execute(
                "INSERT INTO delivery_logs (order_id, user_id, chat_id, keyword, delivery_content, confirm_status) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (order_id, user_id, chat_id, keyword, content, confirm_status),
            )
            conn.commit()
        finally:
            conn.close()

    # ---------- 主流程 ----------
    async def handle_paid_order(self, ws, send_msg_func, user_id, chat_id, order_id,
                                item_title="", sku_desc="", quantity=1, order_payload=None):
        """“等待卖家发货”时触发；补货后对 pending_orders() 再调一次即可补发余量。

        sku_desc / quantity 可直接传入，也可传原始 order_payload 由 parse_order_meta 解析。
        """
        if not order_id:
            logger.error("⚠️ 付款事件缺少订单号，转人工处理（无单号无法保证一单一卡）")
            return False
        tail = order_id[-4:]

        prev = self.get_order(order_id)
        if prev and prev["status"] in ("delivered", "manual"):
            logger.info(f"订单 ***{tail} 状态为 {prev['status']}，忽略重复事件")
            return prev["status"] == "delivered"

        if order_payload is not None:
            p_sku, p_qty = parse_order_meta(order_payload)
            sku_desc = sku_desc or p_sku
            quantity = quantity if quantity and quantity > 1 else p_qty
        if prev:  # 补发时沿用首单记录，防止重推事件里数量/规格丢失
            sku_desc = sku_desc or prev["sku_desc"]
            quantity = max(int(quantity or 1), int(prev["quantity"] or 1))
            item_title = item_title or prev["item_title"] or ""
        try:
            quantity = int(quantity or 1)
        except (TypeError, ValueError):
            quantity = 1

        rule = self.match_rule(item_title)
        if not rule:
            logger.warning(f"⚠️ 未找到匹配的自动发货规则 (商品: {item_title})，跳过发货")
            return False
        keyword = rule["keyword"]

        if quantity < 1 or quantity > MAX_QUANTITY:
            self.upsert_order(order_id, rule_id=rule["id"], keyword=keyword, sku_desc=sku_desc,
                              quantity=quantity, status="manual", user_id=user_id, chat_id=chat_id,
                              item_title=item_title)
            logger.error(f"订单 ***{tail} 件数 {quantity} 超出自动发货上限 {MAX_QUANTITY}，转人工")
            return False

        sku_row, sku_state = self.match_sku(rule, sku_desc)
        if sku_state == "unmatched":
            # 有套餐细分却对不上：宁可转人工，也不能把月卡发成年卡
            self.upsert_order(order_id, rule_id=rule["id"], keyword=keyword, sku_desc=sku_desc,
                              quantity=quantity, status="manual", user_id=user_id, chat_id=chat_id,
                              item_title=item_title)
            logger.warning(f"订单 ***{tail} 规格「{sku_desc}」未匹配规则 [{keyword}] 的任何套餐，转人工")
            return False

        if sku_row:
            sku = sku_row["sku_name"]
            fixed_content = (sku_row["delivery_content"] or "").strip() or (rule.get("delivery_content") or "").strip()
            use_card_pool = bool(sku_row["use_card_pool"])
        else:
            sku = ""
            fixed_content = (rule.get("delivery_content") or "").strip()
            use_card_pool = bool(rule.get("use_card_pool", 0))
        allow_partial = (rule.get("shortage_policy") or "partial") != "hold"
        is_resend = prev is not None and prev["status"] in ("partial", "pending_stock")

        self.upsert_order(order_id, rule_id=rule["id"], keyword=keyword, sku=sku, sku_desc=sku_desc,
                          quantity=quantity, user_id=user_id, chat_id=chat_id, item_title=item_title,
                          status=prev["status"] if prev else "processing")

        parts = []
        if fixed_content and not is_resend:
            parts.append(f"【下载与使用说明{(' · ' + sku) if sku else ''}】：\n{fixed_content}")

        complete, delivered = True, quantity
        if use_card_pool:
            try:
                res = claim_cards(self.db_path, keyword, order_id, quantity=quantity,
                                  sku=sku, allow_partial=allow_partial)
            except StockLockError:
                logger.warning(f"卡密库繁忙，订单 ***{tail} 稍后重试（未通知买家）")
                return False
            complete, delivered = res.complete, res.delivered
            to_send = res.newly_claimed
            if to_send:
                note = f"（补发第 {to_send[0]['order_seq']}-{to_send[-1]['order_seq']} 件）" if is_resend else ""
                parts.append(format_cards_message(to_send, quantity, sku, note))
            if not complete:
                if res.delivered:
                    parts.append(f"⚠️ 本单共 {quantity} 件，已发 {res.delivered} 件，剩余 {res.shortage} 件正在补货，"
                                 f"到货后会自动补发到这里，无需重新下单。")
                else:
                    parts.append(f"⚠️ 本单共 {quantity} 件，当前库存正在补货，到货后会一次性自动发齐，无需重新下单。")
            logger.info(f"🎫 订单 ***{tail} [{keyword}/{sku or '通用'}] 本次绑定 {len(to_send)} 张，"
                        f"累计 {res.delivered}/{quantity}")
            if is_resend and not to_send:
                logger.info(f"订单 ***{tail} 仍缺货，暂不打扰买家")
                return False

        if not parts:
            logger.warning(f"⚠️ 规则 [{keyword}] 未配置任何发货内容")
            return False

        header = "【自动补发】亲，您的剩余宝贝到啦：" if is_resend else "【自动发货】亲，感谢您的购买！宝贝内容如下："
        full_msg = f"{header}\n\n" + "\n\n".join(parts) + "\n\n如有任何问题随时发消息联系，祝您生活愉快！"
        await send_msg_func(ws, chat_id, user_id, full_msg)
        logger.info(f"✅ 发货消息已送达买家（订单 ***{tail}）")

        if complete:
            status, confirm_status = "delivered", "未开启自动确认"
            if rule.get("auto_confirm", 1):
                ok, msg = self.delivery_service.confirm_dummy_shipping(order_id, trade_text="虚拟商品已自动交付")
                confirm_status = "成功" if ok else f"失败: {msg}"
        else:
            # 没发齐绝不确认发货：否则订单会走向自动确认收货，买家却没拿到全部卡密
            status = "partial" if delivered else "pending_stock"
            confirm_status = f"缺货待补发 {quantity - delivered}/{quantity}"

        self.upsert_order(order_id, delivered_count=delivered, status=status)
        self.record_log(order_id, user_id, chat_id, f"{keyword}{('/' + sku) if sku else ''}",
                        full_msg, confirm_status)
        return complete
