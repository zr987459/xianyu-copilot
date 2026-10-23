package com.xianyu.copilot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 消息中枢：按账号维护 [ImClient]，把推送解析成会话 / 消息入库，并分发给
 * 自动回复（[ReplyEngine]）、发货引擎（[DeliveryEngine]）与通知（[Notifier]）。
 *
 * 推送字段解析规则移植自 xianyu-auto-reply 的 handle_message（MIT）：
 *   message["1"]["2"]  会话 cid（xxx@goofish）
 *   message["1"]["5"]  消息时间（毫秒）
 *   message["1"]["10"] {senderUserId, senderNick/reminderTitle, reminderContent, reminderUrl(itemId=..), sessionType}
 *   message["3"]["redReminder"]  订单状态红点：等待买家付款 / 等待卖家发货 / 交易关闭
 * 以上路径与订单号提取正则均需 real-device calibration。
 */
object ImManager : ImClient.Listener {

    private const val TAG = "ImManager"
    private lateinit var app: Context
    private val clients = ConcurrentHashMap<String, ImClient>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 最近一次自动发出的文本，用于识别 WS 回显，避免把自动回复误判为「人工接管」。 */
    private val recentAutoOut = ConcurrentHashMap<String, Pair<String, Long>>()

    /** 付款类系统消息（触发自动发货），参考 _is_auto_delivery_trigger。 */
    private val DELIVERY_TRIGGERS = listOf("[我已付款，等待你发货]", "[已付款，待发货]", "我已付款，等待你发货", "[记得及时发货]")

    /** 交易类系统文案：展示但不自动回复。 */
    private val SYSTEM_SKIP = setOf(
        "[我已拍下，待付款]", "[等待买家付款]", "[你关闭了订单，钱款已原路退返]", "[不想宝贝被砍价?设置不砍价回复  ]",
        "AI正在帮你回复消息，不错过每笔订单", "发来一条消息", "发来一条新消息", "[买家确认收货，交易成功]",
        "快给ta一个评价吧~", "快给ta一个评价吧～", "卖家人不错？送Ta闲鱼小红花", "[你已确认收货，交易成功]", "[你已发货]",
        "已发货", "[我已发货，请查看发货凭证]", "买家确认收货，交易成功", "我完成了评价", "[我完成了评价]", "[交易关闭]",
        "[交易成功，等待卖家发货]", "[卡片消息]"
    )

    fun init(context: Context) {
        if (!::app.isInitialized) app = context.applicationContext
    }

    fun client(accountId: String): ImClient? = clients[accountId]

    fun states(): JSONObject {
        val o = JSONObject()
        for ((k, c) in clients) o.put(k, JSONObject().put("state", c.state.name).put("detail", c.stateDetail).put("connectedAt", c.connectedAt))
        return o
    }

    /** 按设置与账号状态启停各账号的 IM 连接，DaemonService 每轮调用一次。 */
    fun sync(context: Context) {
        init(context)
        val db = CardDatabase.get(app)
        val enabled = db.getSetting("im_enabled", "true") == "true" && db.getSetting("daemon_enabled", "true") == "true"
        val all = db.getSetting("im_all_accounts", "true") == "true"
        val wanted = if (!enabled) emptySet() else db.getAccounts()
            .filter { it.status != "EXPIRED" && it.cookie.isNotBlank() && (all || it.isActive) }
            .map { it.userId }.toSet()
        for (id in clients.keys.toList()) if (id !in wanted) clients.remove(id)?.stop()
        for (id in wanted) if (!clients.containsKey(id)) {
            val c = ImClient(app, id, this)
            clients[id] = c
            c.start()
        }
    }

    fun stopAll() {
        clients.values.forEach { runCatching { it.stop() } }
        clients.clear()
    }

    override fun onImState(client: ImClient, state: ImClient.State, detail: String) {
        if (state == ImClient.State.CONNECTED) scope.launch { runCatching { syncSessions(client.accountId) } }
    }

    // ------------------------------------------------------------ 推送解析

    private val ORDER_PATTERNS = listOf(
        Regex("orderId[=:](\\d{10,})"),
        Regex("order_detail\\?id=(\\d{10,})"),
        Regex("bizOrderId[=:](\\d{10,})"),
        Regex("\"orderId\"\\s*:\\s*\"?(\\d{10,})"),
        Regex("\"bizOrderId\"\\s*:\\s*\"?(\\d{10,})")
    )

    fun extractOrderId(raw: String): String {
        val s = raw.replace("\\\"", "\"").replace("\\/", "/")
        for (p in ORDER_PATTERNS) p.find(s)?.let { return it.groupValues[1] }
        return ""
    }

    private fun extractItemId(m10: JSONObject?, raw: String): String {
        val url = m10?.optString("reminderUrl").orEmpty()
        if (url.contains("itemId=")) return url.substringAfter("itemId=").substringBefore('&')
        return Regex("itemId[\\\\\"=:]+(\\d{6,})").find(raw.replace("\\\"", "\""))?.groupValues?.get(1).orEmpty()
    }

    override fun onImMessage(client: ImClient, message: JSONObject) {
        val accountId = client.accountId
        val raw = message.toString()
        val m1 = message.optJSONObject("1")
        val m10 = m1?.optJSONObject("10")
        val red = message.optJSONObject("3")?.optString("redReminder").orEmpty()
        val content = m10?.optString("reminderContent").orEmpty()
        if (content.isBlank() && red.isBlank()) return

        val chatId = m1?.optString("2").orEmpty().substringBefore('@')
        val senderId = m10?.optString("senderUserId").orEmpty()
            .ifBlank { message.optString("1").takeIf { it.contains('@') }?.substringBefore('@').orEmpty() }
        val senderNick = m10?.optString("senderNick").orEmpty().ifBlank { m10?.optString("reminderTitle").orEmpty() }
        val ts = m1?.optLong("5", 0L)?.takeIf { it > 0 } ?: System.currentTimeMillis()
        val itemId = extractItemId(m10, raw)
        val sessionType = m10?.optString("sessionType").orEmpty()
        val ops = OpsDatabase.get(app)
        val db = CardDatabase.get(app)
        val outgoing = senderId.isNotBlank() && senderId == accountId

        // 1) 订单状态红点
        if (red.isNotBlank()) {
            val orderId = extractOrderId(raw)
            when (red) {
                "等待卖家发货" -> if (orderId.isNotBlank()) DeliveryEngine.enqueue(app, orderId, accountId, "IM",
                    chatId, senderId.takeIf { !outgoing }.orEmpty(), senderNick, itemId)
                "等待买家付款" -> if (orderId.isNotBlank()) ops.upsertOrder(orderId, accountId, itemId, "", "", 1, "",
                    senderId, senderNick, chatId, "待付款", "IM")
                "交易关闭" -> if (orderId.isNotBlank()) ops.setOrderStatus(orderId, "交易关闭")
            }
            if (chatId.isNotBlank()) {
                ops.saveMessage(accountId, chatId, "in", senderId, senderNick, "[$red]", itemId, ts, kind = 3,
                    buyerId = senderId.takeIf { !outgoing }.orEmpty(), countUnread = false)
            }
            if (content.isBlank()) return
        }
        if (chatId.isBlank() || sessionType == "30") return // 群聊不处理

        // 2) 自己发出的消息：自动回复回显跳过；否则是人工在手机上回复 → 暂停该会话自动回复
        if (outgoing) {
            val recent = recentAutoOut[chatId]
            val isEcho = recent != null && recent.first == content && System.currentTimeMillis() - recent.second < 30_000L
            if (isEcho) return
            ops.saveMessage(accountId, chatId, "out", senderId, senderNick, content, itemId, ts, kind = 0)
            val pauseMin = db.getSetting("manual_pause_minutes", "30").toLongOrNull() ?: 30L
            if (pauseMin > 0) ops.setPaused(accountId, chatId, System.currentTimeMillis() + pauseMin * 60_000L)
            return
        }

        val isTrigger = DELIVERY_TRIGGERS.any { content.contains(it) }
        val isSystem = content in SYSTEM_SKIP || isTrigger
        val intent = if (isSystem) "" else AiClient.detectIntent(content)
        val inserted = ops.saveMessage(accountId, chatId, "in", senderId, senderNick, content, itemId, ts,
            kind = if (isSystem) 3 else 0, intent = intent, buyerId = senderId, buyerNick = senderNick)
        if (!inserted) return // 重复推送

        // 3) 付款消息 → 自动发货（与轮询共用幂等任务表）
        if (isTrigger) {
            val orderId = extractOrderId(raw)
            if (orderId.isNotBlank()) {
                DeliveryEngine.enqueue(app, orderId, accountId, "IM", chatId, senderId, senderNick, itemId)
            } else {
                Log.w(TAG, "付款消息未解析出订单号，交给订单轮询兜底: chat=$chatId")
            }
            return
        }
        if (isSystem) return

        // 4) 通知 + 自动回复
        Notifier.notify(app, Notifier.Event.NEW_MESSAGE, "新消息 · ${senderNick.ifBlank { "买家" }}", content,
            dedupeKey = "$accountId:$chatId", throttleMs = 60_000L)
        ReplyEngine.schedule(app, accountId, chatId, senderId, senderNick, itemId, content)
    }

    // ------------------------------------------------------------ 主动发送

    /**
     * 发文本并入库。kind：0 人工 / 1 自动回复 / 2 发货内容。
     */
    suspend fun send(context: Context, accountId: String, chatId: String, toUserId: String, text: String, kind: Int): Pair<Boolean, String> {
        init(context)
        val c = clients[accountId] ?: return false to "该账号 IM 未启动"
        var to = toUserId
        if (to.isBlank()) to = OpsDatabase.get(app).conversation(accountId, chatId)?.optString("buyer_id").orEmpty()
        val r = c.sendText(chatId, to, text)
        if (r.first) {
            recentAutoOut[chatId] = text to System.currentTimeMillis()
            OpsDatabase.get(app).saveMessage(accountId, chatId, "out", accountId, "我", text, "",
                System.currentTimeMillis(), kind = kind, countUnread = false)
        }
        return r
    }

    /** 发货内容私信：优先已知会话，其次按买家查会话，最后用 SingleChatConversation/create 建会话。 */
    suspend fun sendToBuyer(context: Context, accountId: String, chatId: String, buyerId: String, itemId: String, text: String): Pair<Boolean, String> {
        init(context)
        val ops = OpsDatabase.get(app)
        var cid = chatId.ifBlank { ops.chatIdForBuyer(accountId, buyerId) }
        if (cid.isBlank() && buyerId.isNotBlank()) {
            cid = clients[accountId]?.createChat(buyerId, itemId).orEmpty()
        }
        if (cid.isBlank()) return false to "找不到买家会话"
        return send(context, accountId, cid, buyerId, text, kind = 2)
    }

    // ------------------------------------------------------------ 会话 / 历史同步

    /** 拉会话列表（HTTP session.sync），解析规则参考 utils/xy_chat_sync.parse_session_item。 */
    fun syncSessions(accountId: String): Int {
        val db = CardDatabase.get(app)
        val cookie = db.getAccountCookie(accountId)
        if (cookie.isBlank()) return 0
        val r = MtopClient.syncSessions(cookie) { AccountManager.persistRefreshedCookie(app, it) }
        if (!r.success) return 0
        val arr = r.data?.optJSONArray("sessions") ?: return 0
        val ops = OpsDatabase.get(app)
        var n = 0
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val session = item.optJSONObject("session") ?: continue
            val st = session.optInt("sessionType", 0)
            if (st != 0 && st != 1) continue
            val sid = session.optString("sessionId").substringBefore('@')
            if (sid.isBlank()) continue
            val ui = session.optJSONObject("userInfo") ?: JSONObject()
            val summary = item.optJSONObject("message")?.optJSONObject("summary") ?: JSONObject()
            val ext = session.optJSONObject("extension") ?: session.optJSONObject("ext")
            val itemId = ext?.optString("itemId").orEmpty()
            val ts = summary.optLong("ts", 0L).takeIf { it > 0 } ?: session.optLong("modifyTime", 0L)
            ops.upsertConversation(
                accountId, sid,
                ui.optString("userId").ifBlank { ui.optString("uid") },
                ui.optString("nick").ifBlank { ui.optString("fishNick") }.ifBlank { ui.optString("displayName") },
                itemId,
                summary.optString("summary").ifBlank { summary.optString("content") },
                ts, 0,
                avatar = ui.optString("logo").ifBlank { ui.optString("avatar") }
            )
            n++
        }
        return n
    }

    /** 拉单个会话历史（/r/MessageManager/listUserMessages），解析参考 fetch_chat_history。 */
    suspend fun syncHistory(accountId: String, chatId: String, limit: Int = 40): Int {
        val c = clients[accountId] ?: return -1
        val models: JSONArray = c.listMessages(chatId, limit) ?: return -1
        val ops = OpsDatabase.get(app)
        var n = 0
        for (i in 0 until models.length()) {
            val msg = models.optJSONObject(i)?.optJSONObject("message") ?: continue
            val ext = msg.optJSONObject("extension") ?: JSONObject()
            val data = msg.optJSONObject("content")?.optJSONObject("custom")?.optString("data").orEmpty()
            var text = "[消息]"
            if (data.isNotBlank()) {
                val payload = runCatching {
                    JSONObject(String(android.util.Base64.decode(data, android.util.Base64.DEFAULT), Charsets.UTF_8))
                }.getOrNull()
                if (payload != null) {
                    text = when (payload.optInt("contentType")) {
                        1 -> payload.optJSONObject("text")?.optString("text").orEmpty()
                        2 -> "[图片]"
                        else -> payload.optJSONObject("text")?.optString("text").orEmpty().ifBlank { "[消息]" }
                    }
                }
            }
            val sender = ext.optString("senderUserId")
            val dir = if (sender == accountId) "out" else "in"
            var ts = msg.optLong("createAt", 0L).takeIf { it > 0 } ?: msg.optLong("sendTime", 0L)
            if (ts in 1..999_999_999_999L) ts *= 1000
            if (ts <= 0) ts = System.currentTimeMillis()
            val nick = ext.optString("reminderTitle").ifBlank { ext.optString("senderNick") }
            if (ops.saveMessage(accountId, chatId, dir, sender, if (dir == "in") nick else "我", text.ifBlank { "[消息]" },
                    ext.optString("itemId"), ts, kind = 0,
                    buyerId = if (dir == "in") sender else "", buyerNick = if (dir == "in") nick else "",
                    countUnread = false)) n++
        }
        return n
    }
}
