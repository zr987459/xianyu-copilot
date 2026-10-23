package com.xianyu.copilot

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * v1.5.0 新增 REST 接口（由 [LocalServer] 在鉴权之后转发进来，沿用其 X-Api-Token / Host 校验）。
 * 返回 (HTTP 状态码, JSON)；未知路径返回 null 交回 LocalServer 处理。
 */
class ApiV2(private val context: Context) {

    private val db get() = CardDatabase.get(context)
    private val ops get() = OpsDatabase.get(context)
    private val persist: (String) -> Unit = { AccountManager.persistRefreshedCookie(context, it) }

    /** 回复 / 守护相关可配置项及默认值（白名单，前端不能写任意 key）。 */
    private val SETTINGS = linkedMapOf(
        "auto_reply_enabled" to "true",
        "reply_debounce_ms" to "2500",
        "reply_cooldown_sec" to "3",
        "manual_pause_minutes" to "30",
        "default_reply_enabled" to "false",
        "default_reply_text" to "",
        "default_reply_once" to "true",
        "sensitive_mode" to "replace",
        "im_enabled" to "true",
        "im_all_accounts" to "true",
        "local_notify_enabled" to "true",
        "low_stock_threshold" to "3",
        "auto_polish_time" to ""
    )

    private val AI_KEYS = linkedMapOf(
        "ai_enabled" to "false",
        "ai_provider" to "openai",
        "ai_base_url" to "https://api.deepseek.com/v1",
        "ai_model" to "deepseek-chat",
        "ai_max_discount_percent" to "10",
        "ai_max_discount_amount" to "100",
        "ai_max_bargain_rounds" to "3",
        "ai_temperature" to "0.7"
    )

    private fun ok(o: JSONObject = JSONObject()): Pair<Int, JSONObject> = 200 to o.put("ok", true).put("success", true)
    private fun err(code: Int, msg: String): Pair<Int, JSONObject> =
        code to JSONObject().put("ok", false).put("success", false).put("error", msg).put("message", msg)

    private fun body(raw: String): JSONObject = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())

    /** 请求里的 account（userId），缺省为当前激活账号。 */
    private fun account(q: Map<String, String>, b: JSONObject? = null): String =
        (b?.optString("account").orEmpty()).ifBlank { q["account"].orEmpty() }
            .ifBlank { db.getActiveAccount()?.userId.orEmpty() }

    private fun cookieOf(acc: String): String = db.getAccountCookie(acc).ifBlank { db.getActiveCookie() }

    fun handle(method: String, path: String, q: Map<String, String>, raw: String): Pair<Int, JSONObject>? {
        val b = if (method == "POST" || method == "PUT") body(raw) else JSONObject()
        return when (path) {
            "/api/dashboard" -> dashboard()
            "/api/im/status" -> imStatus()
            "/api/chats" -> chats(q)
            "/api/chat/messages" -> messages(q)
            "/api/chat/send" -> if (method == "POST") send(b) else err(405, "method not allowed")
            "/api/chat/pause" -> if (method == "POST") pause(b) else err(405, "method not allowed")
            "/api/chat/test_reply" -> testReply(b)

            "/api/settings", "/api/reply/settings" -> if (method == "POST") saveSettings(b, SETTINGS) else getSettings(SETTINGS)
            "/api/ai/config" -> if (method == "POST") saveAi(b) else getAi()
            "/api/ai/test" -> aiTest(b)
            "/api/prompts" -> if (method == "POST") savePrompts(b) else prompts()

            "/api/reply/keywords" -> crud(method, q, b, "keyword_rules",
                listOf("account_id", "keyword", "match_type", "reply", "item_id", "enabled", "priority"),
                "SELECT * FROM keyword_rules ORDER BY priority DESC, id DESC", required = listOf("keyword", "reply"))
            "/api/reply/items" -> crud(method, q, b, "item_replies",
                listOf("account_id", "item_id", "reply", "floor_price", "enabled"),
                "SELECT * FROM item_replies ORDER BY id DESC", required = listOf("item_id"))
            "/api/sensitive" -> crud(method, q, b, "sensitive_words", listOf("word", "replacement"),
                "SELECT * FROM sensitive_words ORDER BY id DESC", required = listOf("word"))
            "/api/templates" -> crud(method, q, b, "ship_templates", listOf("name", "content"),
                "SELECT * FROM ship_templates ORDER BY id DESC", required = listOf("name", "content"))
            "/api/notify/channels" -> notifyChannels(method, q, b)
            "/api/notify/test" -> notifyTest(b)

            "/api/orders/list" -> ordersList(q)
            "/api/orders/detail" -> orderDetail(q)
            "/api/orders/retry" -> if (method == "POST") orderRetry(b) else err(405, "method not allowed")
            "/api/delivery/tasks" -> ok(JSONObject().put("tasks",
                ops.queryJson("SELECT * FROM delivery_tasks ORDER BY updated_at DESC LIMIT 200")))

            "/api/items/polish" -> itemAction(b) { c, id -> MtopClient.polishItem(c, id, persist) }
            "/api/items/downshelf" -> itemAction(b) { c, id -> MtopClient.downshelfItem(c, id, persist) }
            "/api/items/reshelf" -> itemAction(b) { c, id -> MtopClient.reshelfItem(c, id, persist) }
            "/api/items/price" -> itemAction(b) { c, id ->
                val price = b.optDouble("price", 0.0)
                val orig = b.optDouble("origPrice", 0.0).takeIf { it > 0 }
                MtopClient.updateItemPrice(c, id, price, orig, persist)
            }
            "/api/items/polish_all" -> polishAll(b)
            "/api/items/detail" -> itemDetail(q["itemId"].orEmpty(), account(q))

            "/api/collect/parse" -> collect(b, relist = false)
            "/api/collect/relist" -> collect(b, relist = true)
            else -> null
        }
    }

    // ------------------------------------------------------------ 首页看板

    private fun dashboard(): Pair<Int, JSONObject> {
        val t0 = OpsDatabase.todayStart().toString()
        val acc = db.getActiveAccount()?.userId.orEmpty()
        val revenue = ops.queryJson("SELECT price FROM orders WHERE created_at>=? AND status<>'交易关闭' AND status<>'待付款'", t0)
        var sum = java.math.BigDecimal.ZERO
        for (i in 0 until revenue.length()) {
            val p = revenue.optJSONObject(i)?.optString("price").orEmpty().replace("¥", "").trim()
            p.toBigDecimalOrNull()?.let { sum = sum.add(it) }
        }
        val deliveredOk = db.getLogs(500).count { it.createdAt >= t0.toLong() && it.confirmStatus.contains("完成") }
        val stockLow = JSONArray()
        val threshold = db.getSetting("low_stock_threshold", "3").toLongOrNull() ?: 3L
        for (r in db.getRules()) if (r.usePool) {
            val left = db.availableCount(r.keyword, r.sku)
            if (left <= threshold) stockLow.put(JSONObject().put("keyword", r.keyword).put("sku", r.sku).put("left", left))
        }
        return ok(JSONObject()
            .put("todayMessages", ops.count("SELECT COUNT(*) FROM messages WHERE ts>=? AND direction='in' AND kind<>3", t0))
            .put("todayAutoReplies", ops.count("SELECT COUNT(*) FROM messages WHERE ts>=? AND direction='out' AND kind=1", t0))
            .put("todayOrders", ops.count("SELECT COUNT(*) FROM orders WHERE created_at>=?", t0))
            .put("todayDelivered", deliveredOk)
            .put("todayRevenue", sum.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())
            .put("unread", ops.count("SELECT COALESCE(SUM(unread),0) FROM conversations WHERE account_id=?", acc))
            .put("pendingTasks", ops.count("SELECT COUNT(*) FROM delivery_tasks WHERE status IN ('PENDING','RUNNING')"))
            .put("failedTasks", ops.count("SELECT COUNT(*) FROM delivery_tasks WHERE status='FAILED'"))
            .put("lowStock", stockLow)
            .put("im", ImManager.states())
            .put("account", acc)
            .put("autoPolish", db.getSetting("auto_polish_result", "")))
    }

    // ------------------------------------------------------------ 消息

    private fun imStatus(): Pair<Int, JSONObject> {
        val st = ImManager.states()
        val arr = JSONArray()
        for (a in db.getAccounts()) {
            val s = st.optJSONObject(a.userId)
            arr.put(JSONObject().put("userId", a.userId).put("nick", a.nick).put("isActive", a.isActive)
                .put("status", a.status)
                .put("im", s?.optString("state") ?: "STOPPED").put("detail", s?.optString("detail").orEmpty())
                .put("unread", ops.count("SELECT COALESCE(SUM(unread),0) FROM conversations WHERE account_id=?", a.userId)))
        }
        return ok(JSONObject().put("accounts", arr).put("enabled", db.getSetting("im_enabled", "true") == "true"))
    }

    private fun chats(q: Map<String, String>): Pair<Int, JSONObject> {
        val acc = account(q)
        if (acc.isBlank()) return ok(JSONObject().put("chats", JSONArray()).put("account", ""))
        if (q["refresh"] == "1") runCatching { ImManager.init(context); ImManager.syncSessions(acc) }
        val kw = q["q"].orEmpty().trim()
        val list = if (kw.isBlank())
            ops.queryJson("SELECT * FROM conversations WHERE account_id=? ORDER BY last_ts DESC LIMIT 300", acc)
        else ops.queryJson(
            "SELECT * FROM conversations WHERE account_id=? AND (buyer_nick LIKE ? OR last_msg LIKE ? OR item_id LIKE ?) ORDER BY last_ts DESC LIMIT 300",
            acc, "%$kw%", "%$kw%", "%$kw%")
        val now = System.currentTimeMillis()
        for (i in 0 until list.length()) {
            val c = list.getJSONObject(i)
            c.put("paused", c.optLong("paused_until") > now)
            val it = ops.item(c.optString("item_id"))
            if (it != null && c.optString("item_title").isBlank()) c.put("item_title", it.optString("title"))
        }
        val state = ImManager.client(acc)?.state?.name ?: "STOPPED"
        return ok(JSONObject().put("chats", list).put("account", acc).put("im", state)
            .put("unread", ops.count("SELECT COALESCE(SUM(unread),0) FROM conversations WHERE account_id=?", acc)))
    }

    private fun messages(q: Map<String, String>): Pair<Int, JSONObject> {
        val acc = account(q)
        val chatId = q["chatId"].orEmpty()
        if (acc.isBlank() || chatId.isBlank()) return err(400, "account / chatId required")
        var synced = 0
        if (q["sync"] == "1") synced = runBlocking { runCatching { ImManager.syncHistory(acc, chatId) }.getOrDefault(-1) }
        ops.markRead(acc, chatId)
        val conv = ops.conversation(acc, chatId) ?: JSONObject()
        conv.put("paused", conv.optLong("paused_until") > System.currentTimeMillis())
        return ok(JSONObject().put("messages", ops.recentMessages(acc, chatId, (q["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500)))
            .put("conversation", conv).put("synced", synced))
    }

    private fun send(b: JSONObject): Pair<Int, JSONObject> {
        val acc = account(emptyMap(), b)
        val chatId = b.optString("chatId")
        val text = b.optString("text").trim()
        if (chatId.isBlank() || text.isBlank()) return err(400, "chatId / text required")
        val (sent, msg) = runBlocking { ImManager.send(context, acc, chatId, "", text, kind = 0) }
        if (!sent) return err(400, msg)
        // 人工在 App 内回复 ⇒ 视为人工接管
        val pauseMin = db.getSetting("manual_pause_minutes", "30").toLongOrNull() ?: 30L
        if (pauseMin > 0 && b.optBoolean("pause", true)) ops.setPaused(acc, chatId, System.currentTimeMillis() + pauseMin * 60_000L)
        return ok(JSONObject().put("message", msg))
    }

    private fun pause(b: JSONObject): Pair<Int, JSONObject> {
        val acc = account(emptyMap(), b)
        val chatId = b.optString("chatId")
        if (chatId.isBlank()) return err(400, "chatId required")
        val minutes = b.optLong("minutes", 30L)
        val until = if (minutes <= 0) 0L else System.currentTimeMillis() + minutes * 60_000L
        ops.setPaused(acc, chatId, until)
        return ok(JSONObject().put("paused_until", until))
    }

    private fun testReply(b: JSONObject): Pair<Int, JSONObject> {
        val acc = account(emptyMap(), b)
        val text = b.optString("text")
        if (text.isBlank()) return err(400, "text required")
        val d = ReplyEngine.decide(context, acc, b.optString("chatId").ifBlank { "__test__" }, "", "测试买家",
            b.optString("itemId"), text)
        val filtered = d.text?.let { ReplyEngine.filterSensitive(context, it) }
        return ok(JSONObject().put("reply", filtered ?: JSONObject.NULL).put("source", d.source)
            .put("blocked", d.text != null && filtered == null).put("intent", AiClient.detectIntent(text)))
    }

    // ------------------------------------------------------------ 设置

    private fun getSettings(keys: Map<String, String>): Pair<Int, JSONObject> {
        val o = JSONObject()
        for ((k, def) in keys) o.put(k, db.getSetting(k, def))
        return ok(JSONObject().put("settings", o))
    }

    private fun saveSettings(b: JSONObject, keys: Map<String, String>): Pair<Int, JSONObject> {
        val src = b.optJSONObject("settings") ?: b
        var n = 0
        for (k in keys.keys) if (src.has(k)) { db.saveSetting(k, src.opt(k).toString()); n++ }
        if (src.has("im_enabled") || src.has("im_all_accounts")) runCatching { ImManager.sync(context) }
        return ok(JSONObject().put("saved", n))
    }

    private fun getAi(): Pair<Int, JSONObject> {
        val o = JSONObject()
        for ((k, def) in AI_KEYS) o.put(k, db.getSetting(k, def))
        val key = SecureStore.decrypt(db.getSetting("ai_api_key", ""))
        o.put("ai_api_key_set", key.isNotBlank())
        o.put("ai_api_key_mask", if (key.length > 8) key.take(3) + "****" + key.takeLast(4) else if (key.isNotBlank()) "****" else "")
        return ok(JSONObject().put("config", o))
    }

    private fun saveAi(b: JSONObject): Pair<Int, JSONObject> {
        val src = b.optJSONObject("config") ?: b
        for (k in AI_KEYS.keys) if (src.has(k)) db.saveSetting(k, src.opt(k).toString().trim())
        val key = src.optString("ai_api_key").trim()
        if (key.isNotBlank() && !key.contains("****")) db.saveSetting("ai_api_key", SecureStore.encrypt(key))
        if (src.optBoolean("clear_key", false)) db.saveSetting("ai_api_key", "")
        return ok()
    }

    private fun aiTest(b: JSONObject): Pair<Int, JSONObject> {
        val cfg = AiClient.loadConfig(context)
        val (r, e) = AiClient.chat(cfg, AiClient.prompt(context, "default"),
            "商品信息：测试商品\n用户消息：${b.optString("text").ifBlank { "在吗？还有货吗" }}\n请直接给出回复内容：", 120)
        return if (r != null) ok(JSONObject().put("reply", r)) else err(400, e)
    }

    private fun prompts(): Pair<Int, JSONObject> {
        val o = JSONObject()
        for (i in listOf("default", "price", "tech")) {
            o.put(i, AiClient.prompt(context, i))
            o.put("${i}_custom", db.getSetting("ai_prompt_$i", "").isNotBlank())
        }
        return ok(JSONObject().put("prompts", o))
    }

    private fun savePrompts(b: JSONObject): Pair<Int, JSONObject> {
        val src = b.optJSONObject("prompts") ?: b
        for (i in listOf("default", "price", "tech")) if (src.has(i)) db.saveSetting("ai_prompt_$i", src.optString(i).trim())
        return ok()
    }

    // ------------------------------------------------------------ 通用 CRUD

    private fun crud(
        method: String, q: Map<String, String>, b: JSONObject, table: String, cols: List<String>,
        listSql: String, required: List<String>
    ): Pair<Int, JSONObject> = when (method) {
        "GET" -> ok(JSONObject().put("list", ops.queryJson(listSql)))
        "POST" -> {
            val missing = required.firstOrNull { b.optString(it).isBlank() }
            if (missing != null) err(400, "$missing required")
            else ok(JSONObject().put("id", ops.saveRow(table, b, cols)))
        }
        "DELETE" -> {
            val id = q["id"]?.toLongOrNull() ?: b.optLong("id", 0L)
            if (id <= 0) err(400, "id required") else ok(JSONObject().put("deleted", ops.deleteRow(table, id)))
        }
        else -> err(405, "method not allowed")
    }

    private fun notifyChannels(method: String, q: Map<String, String>, b: JSONObject): Pair<Int, JSONObject> {
        if (method == "POST") {
            val type = b.optString("type")
            if (type !in Notifier.TYPES) return err(400, "不支持的渠道类型")
            val cfg = b.optJSONObject("config") ?: JSONObject()
            b.put("config", cfg.toString())
            val events = b.opt("events")
            if (events is JSONArray) b.put("events", (0 until events.length()).joinToString(",") { events.optString(it) })
            return ok(JSONObject().put("id", ops.saveRow("notify_channels", b, listOf("type", "name", "config", "events", "enabled"))))
        }
        if (method == "GET") {
            val list = ops.queryJson("SELECT * FROM notify_channels ORDER BY id DESC")
            // 回给前端时把密钥类字段打码
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                val cfg = runCatching { JSONObject(o.optString("config")) }.getOrDefault(JSONObject())
                for (k in listOf("secret", "token")) if (cfg.optString(k).isNotBlank()) cfg.put(k, "****")
                o.put("config", cfg)
            }
            return ok(JSONObject().put("list", list).put("types", JSONArray(Notifier.TYPES))
                .put("events", JSONArray(Notifier.Event.values().filter { it != Notifier.Event.TEST }.map {
                    JSONObject().put("key", it.key).put("label", it.label)
                })))
        }
        return crud(method, q, b, "notify_channels", emptyList(), "", emptyList())
    }

    private fun notifyTest(b: JSONObject): Pair<Int, JSONObject> {
        val id = b.optLong("id", 0L)
        val (type, cfg) = if (id > 0) {
            val row = ops.queryOne("SELECT * FROM notify_channels WHERE id=?", id.toString()) ?: return err(404, "渠道不存在")
            row.optString("type") to runCatching { JSONObject(row.optString("config")) }.getOrDefault(JSONObject())
        } else b.optString("type") to (b.optJSONObject("config") ?: JSONObject())
        val (sent, msg) = Notifier.send(type, cfg, "测试通知", "这是一条来自闲鱼管家的测试消息 ✅")
        return if (sent) ok(JSONObject().put("message", msg)) else err(400, msg)
    }

    // ------------------------------------------------------------ 订单

    private fun ordersList(q: Map<String, String>): Pair<Int, JSONObject> {
        val st = q["status"].orEmpty()
        val sql = "SELECT o.*, t.status AS task_status, t.last_error AS task_error, t.scheduled_at AS task_due " +
            "FROM orders o LEFT JOIN delivery_tasks t ON t.order_id=o.order_id " +
            (if (st.isNotBlank()) "WHERE o.status=? " else "") + "ORDER BY o.created_at DESC LIMIT 200"
        val list = if (st.isNotBlank()) ops.queryJson(sql, st) else ops.queryJson(sql)
        return ok(JSONObject().put("orders", list))
    }

    private fun orderDetail(q: Map<String, String>): Pair<Int, JSONObject> {
        val orderId = q["orderId"].orEmpty()
        if (orderId.isBlank()) return err(400, "orderId required")
        val local = ops.queryOne("SELECT * FROM orders WHERE order_id=?", orderId) ?: JSONObject()
        val acc = local.optString("account_id").ifBlank { account(q) }
        val r = MtopClient.orderDetail(cookieOf(acc), orderId, persist)
        val out = JSONObject().put("local", local).put("task", ops.queryOne("SELECT * FROM delivery_tasks WHERE order_id=?", orderId) ?: JSONObject.NULL)
            .put("logs", JSONArray(db.getLogs(500).filter { it.orderId == orderId }.map {
                JSONObject().put("status", it.confirmStatus).put("content", it.deliveryContent).put("created_at", it.createdAt)
            }))
        if (r.success) {
            val d = r.data
            // 结构未校准：尽力提取状态文案，原始数据一并返回供排查
            val status = d?.let { findString(it, listOf("orderStatusName", "statusName", "orderStatusDesc", "tradeStatusDesc"), 0) }.orEmpty()
            if (status.isNotBlank()) ops.setOrderStatus(orderId, status)
            out.put("remoteStatus", status).put("remote", d ?: JSONObject())
        } else out.put("remoteError", r.error)
        return ok(out)
    }

    private fun findString(o: JSONObject, keys: List<String>, depth: Int): String? {
        if (depth > 5) return null
        for (k in keys) o.optString(k).takeIf { it.isNotBlank() && it != "null" }?.let { return it }
        for (k in o.keys()) {
            val v = o.opt(k)
            if (v is JSONObject) findString(v, keys, depth + 1)?.let { return it }
        }
        return null
    }

    private fun orderRetry(b: JSONObject): Pair<Int, JSONObject> {
        val orderId = b.optString("orderId")
        if (orderId.isBlank()) return err(400, "orderId required")
        val local = ops.queryOne("SELECT * FROM orders WHERE order_id=?", orderId) ?: JSONObject()
        val r = runBlocking {
            DeliveryEngine.shipNow(context, orderId, local.optString("account_id").ifBlank { account(emptyMap(), b) },
                local.optString("title"), local.optString("sku"), local.optInt("quantity", 1))
        }
        return (if (r.ok) 200 else 400) to JSONObject().put("ok", r.ok).put("success", r.ok)
            .put("message", r.message).put("cardsDelivered", r.cardsDelivered).put("duplicated", r.duplicated)
    }

    // ------------------------------------------------------------ 商品

    private fun itemAction(b: JSONObject, fn: (String, String) -> Pair<Boolean, String>): Pair<Int, JSONObject> {
        val id = b.optString("itemId")
        if (id.isBlank()) return err(400, "itemId required")
        val cookie = cookieOf(account(emptyMap(), b))
        if (cookie.isBlank()) return err(400, "未绑定闲鱼账号")
        val (r, msg) = fn(cookie, id)
        return if (r) ok(JSONObject().put("message", msg)) else err(400, msg)
    }

    private fun polishAll(b: JSONObject): Pair<Int, JSONObject> {
        val cookie = cookieOf(account(emptyMap(), b))
        if (cookie.isBlank()) return err(400, "未绑定闲鱼账号")
        val res = MtopClient.queryOnSaleItems(cookie, onCookieRefreshed = persist)
        if (!res.ok) return err(400, "获取在售商品失败: ${res.error}")
        var okN = 0
        val fails = JSONArray()
        for (it in res.items) {
            val (r, m) = MtopClient.polishItem(cookie, it.id, persist)
            if (r) okN++ else fails.put(JSONObject().put("id", it.id).put("error", m))
            Thread.sleep(1500)
        }
        return ok(JSONObject().put("message", "擦亮 $okN/${res.items.size}").put("fails", fails))
    }

    private fun itemDetail(itemId: String, acc: String): Pair<Int, JSONObject> {
        if (itemId.isBlank()) return err(400, "itemId required")
        val (d, e) = MtopClient.getItemDetail(cookieOf(acc), itemId, persist)
        if (d == null) return err(400, e)
        ops.upsertItem(itemId, acc, d.title, d.price, d.desc, d.images.firstOrNull().orEmpty(), "")
        return ok(JSONObject().put("item", JSONObject().put("itemId", d.itemId).put("title", d.title).put("price", d.price)
            .put("desc", d.desc).put("images", JSONArray(d.images))))
    }

    /** 采集：从闲鱼商品链接（或纯数字 ID）解析出商品；relist=true 时把图片转存到自己账号的图床。 */
    private fun collect(b: JSONObject, relist: Boolean): Pair<Int, JSONObject> {
        val input = b.optString("url").ifBlank { b.optString("itemId") }.trim()
        val itemId = Regex("[?&]id=(\\d{6,})").find(input)?.groupValues?.get(1)
            ?: Regex("itemId=(\\d{6,})").find(input)?.groupValues?.get(1)
            ?: Regex("^(\\d{6,})$").find(input)?.groupValues?.get(1)
            ?: return err(400, "无法识别商品链接，请粘贴 goofish.com/item?id=... 或商品 ID")
        val acc = account(emptyMap(), b)
        val cookie = cookieOf(acc)
        if (cookie.isBlank()) return err(400, "未绑定闲鱼账号")
        val (d, e) = MtopClient.getItemDetail(cookie, itemId, persist)
        if (d == null) return err(400, "获取商品详情失败: $e")
        val out = JSONObject().put("itemId", itemId).put("title", d.title).put("price", d.price).put("desc", d.desc)
            .put("sourceImages", JSONArray(d.images))
        if (relist) {
            val imgs = JSONArray()
            for (u in d.images.take(9)) {
                val bytes = MtopClient.downloadBytes(u) ?: continue
                val (up, img) = MtopClient.uploadImage(cookie, bytes, "collect_${System.currentTimeMillis()}.jpg")
                if (up && img != null) imgs.put(JSONObject().put("url", img.url).put("width", img.widthSize).put("height", img.heightSize))
            }
            out.put("images", imgs)
            if (imgs.length() == 0 && d.images.isNotEmpty()) out.put("warning", "图片转存失败，请手动上传主图")
        }
        return ok(out)
    }
}
