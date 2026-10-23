package com.xianyu.copilot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 统一发货引擎：IM「已付款」消息、订单轮询、手动「立即发货」三条入口都汇到这里。
 *
 * 幂等由三层保证（任何一层都能单独挡住重复发货）：
 *  1. delivery_tasks 表 order_id 主键 + CAS 状态机（PENDING → RUNNING → DONE/FAILED/NO_RULE）；
 *  2. 进程内 [mutex] 串行处理；
 *  3. 原有的 hasOrderDelivered + claimCardsAtomic（卡密按 order_id 唯一索引 CAS 出库）。
 */
object DeliveryEngine {

    private const val TAG = "DeliveryEngine"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    /** IM 触发但还缺商品标题时，最多等轮询补齐这么久，之后退化为按 itemId 查详情 / 空标题匹配。 */
    private const val WAIT_TITLE_MS = 3 * 60_000L

    data class Outcome(val ok: Boolean, val message: String, val cardsDelivered: Int = 0, val duplicated: Boolean = false)

    /** 账号健康回写钩子（只给守护进程当前轮询的激活账号用，保持 v1.4.2 失效判定逻辑不变）。 */
    data class HealthHook(val accountId: String, val record: (Boolean, String) -> Unit)

    /**
     * 登记一单待发货。返回 true 表示首次发现该订单。
     */
    fun enqueue(
        context: Context, orderId: String, accountId: String, source: String,
        chatId: String = "", buyerId: String = "", buyerNick: String = "", itemId: String = "",
        title: String = "", sku: String = "", quantity: Int = 1, price: String = "",
        processNow: Boolean = true
    ): Boolean {
        if (orderId.isBlank()) return false
        val ops = OpsDatabase.get(context)
        val newOrder = ops.upsertOrder(orderId, accountId, itemId, title, sku, quantity, price, buyerId, buyerNick,
            chatId, "待发货", source)
        val first = ops.enqueueTask(orderId, accountId, chatId, buyerId, buyerNick, itemId, title, sku, quantity,
            source, System.currentTimeMillis())
        // 用户后来补了规则：轮询再次看到这单时让 NO_RULE 的任务重新参与匹配
        if (!first) {
            val t = ops.task(orderId)
            if (t?.status == "NO_RULE" && CardDatabase.get(context).matchRule(t.title.ifBlank { title }, t.sku.ifBlank { sku }) != null) {
                ops.rescheduleTask(orderId, "NO_RULE", System.currentTimeMillis(), "规则已补充")
            }
        }
        if (newOrder && !CardDatabase.get(context).hasOrderDelivered(orderId)) {
            Notifier.notify(context, Notifier.Event.NEW_ORDER, "新订单待发货",
                listOf(title.ifBlank { "商品 $itemId" }, "买家：${buyerNick.ifBlank { buyerId }}", "订单：$orderId", if (price.isNotBlank()) "金额：¥$price" else "")
                    .filter { it.isNotBlank() && !it.endsWith("：") }.joinToString("\n"),
                dedupeKey = orderId, throttleMs = 3600_000L)
        }
        if (processNow) scope.launch { runCatching { processDue(context, null) } }
        return first
    }

    /** 处理所有到期任务。 */
    suspend fun processDue(context: Context, hook: HealthHook?): Int = mutex.withLock {
        val ops = OpsDatabase.get(context)
        ops.recoverRunningTasks()
        var done = 0
        for (t in ops.dueTasks(System.currentTimeMillis())) {
            if (!ops.casTask(t.orderId, "PENDING", "RUNNING")) continue
            val out = runCatching { deliver(context, t, hook) }.getOrElse {
                Log.w(TAG, "deliver ${t.orderId} crashed", it)
                ops.casTask(t.orderId, "RUNNING", "FAILED", it.message ?: "异常")
                Outcome(false, it.message ?: "异常")
            }
            if (out.ok && !out.duplicated) done++
        }
        done
    }

    /** 手动「立即发货」：忽略延时，同步执行并返回结果。 */
    suspend fun shipNow(
        context: Context, orderId: String, accountId: String, title: String, sku: String, quantity: Int
    ): Outcome {
        val db = CardDatabase.get(context)
        if (db.hasOrderDelivered(orderId)) return Outcome(true, "该订单此前已发货，未重复发送", duplicated = true)
        val ops = OpsDatabase.get(context)
        ops.upsertOrder(orderId, accountId, "", title, sku, quantity, "", "", "", "", "待发货", "MANUAL")
        ops.enqueueTask(orderId, accountId, "", "", "", "", title, sku, quantity, "MANUAL", System.currentTimeMillis())
        return mutex.withLock {
            val t = ops.task(orderId) ?: return@withLock Outcome(false, "任务登记失败")
            // 手动发货允许重试 FAILED / NO_RULE / 尚在延时中的任务
            val from = t.status
            if (from == "DONE") return@withLock Outcome(true, "该订单此前已发货，未重复发送", duplicated = true)
            if (from == "RUNNING") return@withLock Outcome(false, "该订单正在发货中")
            if (!ops.casTask(orderId, from, "RUNNING")) return@withLock Outcome(false, "该订单正在处理")
            deliver(context, t.copy(title = t.title.ifBlank { title }, sku = t.sku.ifBlank { sku }), null, manual = true)
        }
    }

    private suspend fun deliver(context: Context, t: OpsDatabase.Task, hook: HealthHook?, manual: Boolean = false): Outcome {
        val db = CardDatabase.get(context)
        val ops = OpsDatabase.get(context)
        val orderId = t.orderId

        fun finish(status: String, msg: String) { ops.casTask(orderId, "RUNNING", status, msg) }

        if (db.hasOrderDelivered(orderId)) {
            finish("DONE", "此前已发货")
            return Outcome(true, "该订单此前已发货，未重复发送", duplicated = true)
        }

        val accountId = t.accountId.ifBlank { db.getActiveAccount()?.userId.orEmpty() }
        var cookie = if (accountId.isNotBlank()) db.getAccountCookie(accountId) else ""
        if (cookie.isBlank()) cookie = db.getActiveCookie()
        if (cookie.isBlank()) { finish("FAILED", "未绑定账号"); return Outcome(false, "未绑定闲鱼账号") }
        val persist: (String) -> Unit = { AccountManager.persistRefreshedCookie(context, it) }

        // 1) 标题：IM 推送只有 itemId，优先等轮询补齐，其次查商品缓存 / 详情接口
        var title = t.title
        if (title.isBlank() && t.itemId.isNotBlank()) {
            title = ops.item(t.itemId)?.optString("title").orEmpty()
            if (title.isBlank()) {
                val (d, _) = MtopClient.getItemDetail(cookie, t.itemId, persist)
                if (d != null) {
                    title = d.title
                    ops.upsertItem(t.itemId, accountId, d.title, d.price, d.desc, d.images.firstOrNull().orEmpty(), "")
                }
            }
        }
        if (title.isBlank() && !manual && System.currentTimeMillis() - t.createdAt < WAIT_TITLE_MS) {
            ops.rescheduleTask(orderId, "RUNNING", System.currentTimeMillis() + 30_000L, "等待订单标题")
            return Outcome(false, "等待订单标题")
        }

        // 2) 规则
        val rule = db.matchRule(title, t.sku)
        if (rule == null && manual) {
            // 与 v1.4.x 一致：没有规则时手动发货只做「无需物流」确认
            val (ok, msg) = MtopClient.confirmDummyShipping(cookie, orderId, "虚拟商品已自动交付", persist)
            db.recordLog(orderId, "手动发货", "", if (ok) "发货完成" else "发货反馈: $msg")
            finish(if (ok) "DONE" else "FAILED", msg)
            ops.setOrderStatus(orderId, if (ok) "已发货" else "发货失败")
            return Outcome(ok, if (ok) "发货成功！" else msg)
        }
        if (rule == null) {
            finish("NO_RULE", "无匹配规则")
            return Outcome(false, "没有匹配的发货规则")
        }

        // 3) 延时发货
        if (!manual && rule.delayMinutes > 0) {
            val due = t.createdAt + rule.delayMinutes * 60_000L
            if (System.currentTimeMillis() < due) {
                ops.rescheduleTask(orderId, "RUNNING", due, "延时发货中")
                return Outcome(false, "延时 ${rule.delayMinutes} 分钟后发货")
            }
        }

        // 4) 出库（CAS），重试时复用该订单已出库的卡密，绝不再抢新卡
        val qty = t.quantity.coerceAtLeast(1)
        val cards = ArrayList<String>()
        if (rule.usePool) {
            cards.addAll(db.claimCardsAtomic(rule.keyword, rule.sku, orderId, qty))
            if (cards.isEmpty()) cards.addAll(db.getCardsByOrder(orderId))
            if (cards.isEmpty()) {
                val msg = "卡密库存不足（${rule.keyword}${if (rule.sku.isNotBlank()) " / ${rule.sku}" else ""}）"
                finish("FAILED", msg)
                db.recordLog(orderId, rule.keyword, "", "发货失败: $msg")
                Notifier.notify(context, Notifier.Event.DELIVERY_FAIL, "自动发货失败", "$msg\n订单：$orderId",
                    dedupeKey = orderId)
                Notifier.notify(context, Notifier.Event.LOW_STOCK, "卡密已售罄", msg,
                    dedupeKey = rule.keyword + rule.sku, throttleMs = 6 * 3600_000L)
                return Outcome(false, msg)
            }
        }

        val content = render(context, rule, cards, t.copy(title = title), qty)

        // 5) 官方「无需物流」确认发货
        val record: ((Boolean, String) -> Unit)? = if (hook != null && hook.accountId == accountId) hook.record else null
        val tradeText = rule.tradeText.ifBlank { "虚拟商品已自动交付" }
        val (ok, msg) = MtopClient.confirmDummyShipping(cookie, orderId, tradeText, persist, record)

        // 6) 把发货内容通过 IM 私信给买家（官方发货备注有长度限制，卡密主要靠这条消息送达）
        var imNote = ""
        if (rule.sendIm && content.isNotBlank()) {
            val (imOk, imMsg) = ImManager.sendToBuyer(context, accountId, t.chatId, t.buyerId, t.itemId, content)
            imNote = if (imOk) " · 已私信买家" else " · 私信未发出($imMsg)"
        }

        db.recordLog(orderId, rule.keyword, content,
            (if (ok) (if (manual) "发货完成" else "自动发货完成") else "发货反馈: $msg") + imNote)
        finish(if (ok) "DONE" else "FAILED", if (ok) "完成$imNote" else msg)
        ops.setOrderStatus(orderId, if (ok) "已发货" else "发货失败")

        if (ok) {
            Notifier.notify(context, Notifier.Event.DELIVERY_OK, "发货成功",
                "${title.ifBlank { rule.keyword }} × $qty\n订单：$orderId$imNote", dedupeKey = orderId)
        } else {
            Notifier.notify(context, Notifier.Event.DELIVERY_FAIL, "确认发货失败", "订单：$orderId\n$msg", dedupeKey = orderId)
        }

        // 7) 低库存预警
        if (rule.usePool) {
            val threshold = db.getSetting("low_stock_threshold", "3").toLongOrNull() ?: 3L
            val left = db.availableCount(rule.keyword, rule.sku)
            if (left <= threshold) {
                Notifier.notify(context, Notifier.Event.LOW_STOCK, "卡密库存偏低",
                    "${rule.keyword}${if (rule.sku.isNotBlank()) " / ${rule.sku}" else ""} 仅剩 $left 张",
                    dedupeKey = rule.keyword + rule.sku, throttleMs = 6 * 3600_000L)
            }
        }
        return Outcome(ok, if (ok) "发货成功！$imNote" else msg, cards.size)
    }

    /**
     * 发货模板渲染。可用变量：{卡密} {卡密列表} {订单号} {买家} {商品} {规格} {数量} {时间} {附言}
     */
    fun render(context: Context, rule: CardDatabase.DeliveryRule, cards: List<String>, t: OpsDatabase.Task, qty: Int): String {
        val tpl = OpsDatabase.get(context).template(rule.templateId)
        val numbered = cards.mapIndexed { i, c -> "${i + 1}. $c" }.joinToString("\n")
        if (tpl.isNullOrBlank()) {
            // 未绑定模板：保持 v1.4.x 的默认格式
            return buildString {
                if (cards.isNotEmpty()) {
                    append("🎫 自动发货卡密 [${cards.size}件]:\n")
                    append(numbered).append('\n')
                }
                if (rule.fixedContent.isNotBlank()) {
                    if (cards.isNotEmpty()) append("\n")
                    append(rule.fixedContent)
                }
            }.trim()
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date())
        return tpl
            .replace("{卡密列表}", numbered)
            .replace("{卡密}", cards.joinToString("\n"))
            .replace("{订单号}", t.orderId)
            .replace("{买家}", t.buyerNick.ifBlank { "亲" })
            .replace("{商品}", t.title)
            .replace("{规格}", t.sku)
            .replace("{数量}", qty.toString())
            .replace("{时间}", time)
            .replace("{附言}", rule.fixedContent)
            .trim()
    }
}
