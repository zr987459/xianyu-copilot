import re
import sqlite3
import time
from typing import Any, Optional, Tuple

from loguru import logger

from app.db.cards import MAX_QUANTITY, StockLockError, claim_cards, format_cards_message
from app.services.delivery_service import XianyuDeliveryService

GENERIC_KEYWORDS = {"*", "默认", "通用"}
LEASE_SECONDS = 90  # 单次处理（发消息 + 确认发货）的最长占用时间；进程崩溃后过期自动释放
RETRYABLE = ("processing", "partial", "pending_stock", "confirm_failed")

# 订单事件里可能承载规格/数量的字段名（闲鱼不同推送版本命名不一，需用真实 WSS 报文校准）
SKU_KEYS = ("skuDesc", "sku_desc", "skuInfo", "skuText", "specDesc", "spec_desc", "skuName", "propertiesName")
# 按优先级排列；刻意不含 amount / count：闲鱼报文里它们常是金额（分）或消息计数，
# 误读会把 9.9 元的订单当成 9 件、一次掏走 9 张卡
QTY_KEYS = ("buyAmount", "buy_amount", "buyQuantity", "buy_quantity", "quantity", "itemNum", "item_num")

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
    for key in QTY_KEYS:  # 按字段优先级取，而不是按报文里出现的先后
        for v in _walk(payload, (key,)):
            try:
                n = int(str(v).strip())
            except ValueError:
                continue
            if n >= 1:
                return sku_desc.strip(), n
    return sku_desc.strip(), 1


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

    def pending_orders(self, keyword: Optional[str] = None, sku: Optional[str] = None):
        """需要重跑的订单：缺货待补发、确认发货失败、处理中途中断（租约已过期）。

        补货后由监听进程逐个调用 handle_paid_order 重跑即可（幂等，只补未送达的件）。
        """
        q = ("SELECT * FROM delivery_orders WHERE status IN ('partial','pending_stock','confirm_failed','processing')"
             " AND (lease_until IS NULL OR lease_until < ?)")
        args = [time.time()]
        if keyword is not None:
            q += " AND keyword=?"; args.append(keyword)
        if sku is not None:
            q += " AND sku=?"; args.append(sku)
        conn = self._conn()
        try:
            return [dict(r) for r in conn.execute(q + " ORDER BY created_at", args)]
        finally:
            conn.close()

    def acquire_lease(self, order_id: str, **init_fields) -> bool:
        """原子占用订单：首单插入并占用；已存在则仅当未完结且租约空闲时占用。拿不到返回 False。"""
        now = time.time()
        conn = self._conn()
        try:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute("SELECT status, lease_until FROM delivery_orders WHERE order_id=?",
                               (order_id,)).fetchone()
            if row is None:
                cols = ["order_id", "status", "lease_until"] + list(init_fields)
                vals = [order_id, "processing", now + LEASE_SECONDS] + list(init_fields.values())
                conn.execute(f"INSERT INTO delivery_orders ({', '.join(cols)}) VALUES ({', '.join('?' * len(cols))})",
                             vals)
                conn.execute("COMMIT"); return True
            if row["status"] not in RETRYABLE or (row["lease_until"] or 0) > now:
                conn.execute("COMMIT"); return False
            conn.execute("UPDATE delivery_orders SET lease_until=?, updated_at=CURRENT_TIMESTAMP WHERE order_id=?",
                         (now + LEASE_SECONDS, order_id))
            conn.execute("COMMIT"); return True
        except BaseException:
            if conn.in_transaction:
                conn.execute("ROLLBACK")
            raise
        finally:
            conn.close()

    def release_lease(self, order_id: str):
        conn = self._conn()
        try:
            conn.execute("UPDATE delivery_orders SET lease_until=NULL WHERE order_id=?", (order_id,))
            conn.commit()
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
        """“等待卖家发货”时触发；补货、发送失败、确认失败后再调一次即可（幂等）。

        可靠性约定：
        - 同一订单同一时刻只有一个调用能进入（租约），WSS 重推 / 多 worker 不会重复发消息或重复确认；
        - 卡密“已绑定”和“已送达”分开记：绑卡后发消息失败，重跑会把已绑定未送达的卡再发一次，绝不丢卡；
        - 只有全部件数送达后才确认发货；确认失败进入 confirm_failed，重跑只重试确认，不再打扰买家。
        """
        if not order_id:
            logger.error("⚠️ 付款事件缺少订单号，转人工处理（无单号无法保证一单一卡）")
            return False
        tail = order_id[-4:]

        prev = self.get_order(order_id)
        if prev and prev["status"] not in RETRYABLE:
            logger.info(f"订单 ***{tail} 状态为 {prev['status']}，忽略重复事件")
            return prev["status"] == "delivered"

        if order_payload is not None:
            p_sku, p_qty = parse_order_meta(order_payload)
            sku_desc = sku_desc or p_sku
            quantity = quantity if quantity and quantity > 1 else p_qty
        if prev:  # 重跑时沿用首单记录，防止重推事件里数量/规格丢失
            sku_desc = sku_desc or prev["sku_desc"]
            # 件数以首单记录为准、一经写入即锁定：重推事件里的件数可能误读/被篡改，
            # 若取 max，补货后会按放大的件数继续给同一订单出卡（掏空库存）
            locked = int(prev["quantity"] or 1)
            try:
                incoming = int(quantity or 1)
            except (TypeError, ValueError):
                incoming = locked
            if incoming not in (1, locked):
                logger.warning(f"订单 ***{tail} 重推事件件数 {incoming} 与首单 {locked} 不一致，按首单处理")
            quantity = locked
            item_title = item_title or prev["item_title"] or ""
            user_id = user_id or prev["user_id"]
            chat_id = chat_id or prev["chat_id"]
        try:
            quantity = int(quantity or 1)
        except (TypeError, ValueError):
            quantity = 1

        rule = self.match_rule(item_title)
        if not rule:
            logger.warning(f"⚠️ 未找到匹配的自动发货规则 (商品: {item_title})，跳过发货")
            return False
        keyword = rule["keyword"]

        if not self.acquire_lease(order_id, rule_id=rule["id"], keyword=keyword, sku_desc=sku_desc,
                                  quantity=quantity, user_id=user_id, chat_id=chat_id, item_title=item_title):
            logger.info(f"订单 ***{tail} 正由其他任务处理或已完结，跳过本次事件")
            return False
        try:
            return await self._process_leased(send_msg_func, ws, user_id, chat_id, order_id, tail,
                                               rule, keyword, item_title, sku_desc, quantity)
        finally:
            self.release_lease(order_id)

    async def _process_leased(self, send_msg_func, ws, user_id, chat_id, order_id, tail,
                              rule, keyword, item_title, sku_desc, quantity):
        order = self.get_order(order_id)

        def _manual(reason):
            self.upsert_order(order_id, rule_id=rule["id"], keyword=keyword, sku_desc=sku_desc,
                              quantity=quantity, status="manual", user_id=user_id, chat_id=chat_id,
                              item_title=item_title)
            logger.warning(f"订单 ***{tail} {reason}，转人工")
            return False

        if quantity < 1 or quantity > MAX_QUANTITY:
            return _manual(f"件数 {quantity} 超出自动发货上限 {MAX_QUANTITY}")

        sku_row, sku_state = self.match_sku(rule, sku_desc)
        if sku_state == "unmatched":
            # 有套餐细分却对不上：宁可转人工，也不能把月卡发成年卡
            return _manual(f"规格「{sku_desc}」未匹配规则 [{keyword}] 的任何套餐")

        if sku_row:
            sku = sku_row["sku_name"]
            fixed_content = (sku_row["delivery_content"] or "").strip() or (rule.get("delivery_content") or "").strip()
            use_card_pool = bool(sku_row["use_card_pool"])
        else:
            sku = ""
            fixed_content = (rule.get("delivery_content") or "").strip()
            use_card_pool = bool(rule.get("use_card_pool", 0))
        allow_partial = (rule.get("shortage_policy") or "partial") != "hold"

        # 套餐一旦确定就锁死：重跑时规格文本若变了也不换池
        if order.get("sku") and order["sku"] != sku:
            return _manual(f"套餐由「{order['sku']}」变为「{sku}」")
        sent_count = int(order.get("sent_count") or 0)
        fixed_sent = bool(order.get("fixed_sent"))
        already_told = fixed_sent or sent_count > 0
        self.upsert_order(order_id, rule_id=rule["id"], keyword=keyword, sku=sku, sku_desc=sku_desc,
                          quantity=quantity, user_id=user_id, chat_id=chat_id, item_title=item_title)

        parts = []
        if fixed_content and not fixed_sent:
            parts.append(f"【下载与使用说明{(' · ' + sku) if sku else ''}】：\n{fixed_content}")

        to_send, bound = [], quantity
        if use_card_pool:
            try:
                res = claim_cards(self.db_path, keyword, order_id, quantity=quantity,
                                  sku=sku, allow_partial=allow_partial)
            except StockLockError:
                logger.warning(f"卡密库繁忙，订单 ***{tail} 稍后重试（未通知买家）")
                return False
            bound = res.delivered
            # 发“已绑定但还没送达”的那部分（含上次发消息失败的件），而不只是本次新绑定的
            to_send = [c for c in res.cards if int(c["order_seq"] or 0) > sent_count]
            if to_send:
                note = (f"（补发第 {to_send[0]['order_seq']}-{to_send[-1]['order_seq']} 件）"
                        if sent_count > 0 else "")
                parts.append(format_cards_message(to_send, quantity, sku, note))
            if bound < quantity and (to_send or not already_told):
                if bound:
                    parts.append(f"⚠️ 本单共 {quantity} 件，已发 {bound} 件，剩余 {quantity - bound} 件正在补货，"
                                 f"到货后会自动补发到这里，无需重新下单。")
                else:
                    parts.append(f"⚠️ 本单共 {quantity} 件，当前库存正在补货，到货后会一次性自动发齐，无需重新下单。")
            logger.info(f"🎫 订单 ***{tail} [{keyword}/{sku or '通用'}] 待送达 {len(to_send)} 张，"
                        f"累计绑定 {bound}/{quantity}")

        complete = bound >= quantity
        if parts:
            header = ("【自动补发】亲，您的剩余宝贝到啦：" if already_told and to_send
                      else "【自动发货】亲，感谢您的购买！宝贝内容如下：")
            full_msg = f"{header}\n\n" + "\n\n".join(parts) + "\n\n如有任何问题随时发消息联系，祝您生活愉快！"
            try:
                await send_msg_func(ws, chat_id, user_id, full_msg)
            except Exception as e:
                # 卡已绑定在本单名下但未送达：保持可重跑状态，下次会原样再发，不会换卡也不会丢卡
                self.upsert_order(order_id, status=order["status"] if order["status"] in RETRYABLE else "processing")
                logger.error(f"订单 ***{tail} 发货消息发送失败，稍后重试: {type(e).__name__}")
                return False
            new_sent = max([int(c["order_seq"]) for c in to_send], default=sent_count)
            self.upsert_order(order_id, sent_count=new_sent, fixed_sent=1 if (fixed_content or fixed_sent) else 0,
                              delivered_count=bound)
            sent_count = new_sent
            logger.info(f"✅ 发货消息已送达买家（订单 ***{tail}）")
        elif not already_told and not use_card_pool:
            logger.warning(f"⚠️ 规则 [{keyword}] 未配置任何发货内容")
            return _manual("规则未配置发货内容")
        else:
            full_msg = ""
            logger.info(f"订单 ***{tail} 无新内容可发，暂不打扰买家")

        delivered_all = complete and (not use_card_pool or sent_count >= quantity)
        if not delivered_all:
            # 没发齐绝不确认发货：否则订单会走向自动确认收货，买家却没拿到全部卡密
            status = "partial" if bound else "pending_stock"
            confirm_status = f"缺货待补发 {quantity - bound}/{quantity}"
            self.upsert_order(order_id, delivered_count=bound, status=status, confirm_status=confirm_status)
            if full_msg:
                self.record_log(order_id, user_id, chat_id, f"{keyword}{('/' + sku) if sku else ''}",
                                full_msg, confirm_status)
            return False

        status, confirm_status = "delivered", "未开启自动确认"
        if rule.get("auto_confirm", 1):
            try:
                ok, msg = self.delivery_service.confirm_dummy_shipping(order_id, trade_text="虚拟商品已自动交付")
            except Exception as e:  # 网络异常等
                ok, msg = False, type(e).__name__
            confirm_status = "成功" if ok else f"失败: {msg}"
            if not ok:
                status = "confirm_failed"  # 买家已拿到全部内容；只需重跑确认发货
        self.upsert_order(order_id, delivered_count=bound, status=status, confirm_status=confirm_status)
        self.record_log(order_id, user_id, chat_id, f"{keyword}{('/' + sku) if sku else ''}",
                        full_msg or "（重试确认发货）", confirm_status)
        return status == "delivered"
