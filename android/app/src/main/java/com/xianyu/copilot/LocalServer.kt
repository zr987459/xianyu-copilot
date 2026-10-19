package com.xianyu.copilot

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * 内置微型 HTTP 服务端：监听 127.0.0.1:9005，提供 assets/web 静态资源与移动端 REST API。
 */
class LocalServer(private val context: Context) {

    private val HOST = "127.0.0.1"
    private val PORT = 9005
    private val MAX_BODY = 4 * 1024 * 1024 // 4MB
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false
    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "LocalServer-worker").apply { isDaemon = true }
    }

    /** 启动服务 */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread(name = "LocalServer-accept", isDaemon = true) {
            var ss: ServerSocket? = null
            try {
                ss = ServerSocket(PORT, 50, InetAddress.getByName(HOST))
                serverSocket = ss
                while (running) {
                    val client = try {
                        ss.accept()
                    } catch (e: Exception) {
                        if (!running) break else continue
                    }
                    pool.execute { handleClient(client) }
                }
            } catch (_: Throwable) {
            } finally {
                try { ss?.close() } catch (_: Exception) {}
            }
        }
    }

    /** 停止服务 */
    @Synchronized
    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        pool.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val request = readRequest(input) ?: return
            val response = try {
                route(request)
            } catch (e: Throwable) {
                jsonError(500, "internal error: ${e.message ?: e.javaClass.simpleName}")
            }
            writeResponse(output, response)
        } catch (_: Throwable) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String
    )

    private fun readRequest(input: InputStream): Request? {
        val headerBuf = ByteArrayOutputStream()
        var prev3 = 0
        var prev2 = 0
        var prev1 = 0
        var b: Int
        while (true) {
            b = input.read()
            if (b == -1) return null
            headerBuf.write(b)
            if (prev1 == '\r'.code && prev2 == '\n'.code && prev3 == '\r'.code && b == '\n'.code) break
            prev3 = prev2; prev2 = prev1; prev1 = b
            if (headerBuf.size() > 64 * 1024) return null
        }
        val headerText = headerBuf.toString(StandardCharsets.ISO_8859_1.name())
        val lines = headerText.split("\r\n")
        if (lines.isEmpty()) return null
        val first = lines[0].split(" ")
        if (first.size < 2) return null
        val method = first[0].uppercase()
        val rawTarget = first[1]
        val qIndex = rawTarget.indexOf('?')
        val rawPath = if (qIndex >= 0) rawTarget.substring(0, qIndex) else rawTarget
        val queryStr = if (qIndex >= 0) rawTarget.substring(qIndex + 1) else ""
        val path = decodePath(rawPath)
        val query = parseQuery(queryStr)

        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) continue
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            if (contentLength > MAX_BODY) return null
            val buf = ByteArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = input.read(buf, read, contentLength - read)
                if (n == -1) break
                read += n
            }
            if (read < contentLength) return null
            String(buf, 0, read, StandardCharsets.UTF_8)
        } else ""

        return Request(method, path, query, headers, body)
    }

    private fun decodePath(raw: String): String =
        try { URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw }

    private fun parseQuery(qs: String): Map<String, String> {
        if (qs.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        for (part in qs.split("&")) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            try {
                if (eq >= 0) {
                    val k = URLDecoder.decode(part.substring(0, eq), "UTF-8")
                    val v = URLDecoder.decode(part.substring(eq + 1), "UTF-8")
                    if (!map.containsKey(k)) map[k] = v
                } else {
                    map[URLDecoder.decode(part, "UTF-8")] = ""
                }
            } catch (_: Exception) {}
        }
        return map
    }

    private data class Response(
        val status: Int,
        val contentType: String,
        val body: ByteArray,
        val extraHeaders: Map<String, String> = emptyMap()
    )

    private fun writeResponse(out: OutputStream, resp: Response) {
        val reason = when (resp.status) {
            200 -> "OK"; 201 -> "Created"; 204 -> "No Content"
            400 -> "Bad Request"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(resp.status).append(' ').append(reason).append("\r\n")
        sb.append("Content-Type: ").append(resp.contentType).append("\r\n")
        sb.append("Content-Length: ").append(resp.body.size).append("\r\n")
        sb.append("Connection: close\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, POST, DELETE, OPTIONS\r\n")
        sb.append("Access-Control-Allow-Headers: Content-Type\r\n")
        for ((k, v) in resp.extraHeaders) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.ISO_8859_1))
        if (resp.body.isNotEmpty()) out.write(resp.body)
        out.flush()
    }

    private fun jsonResponse(obj: Any?, status: Int = 200): Response {
        val body = when (obj) {
            null -> "{}"
            is JSONObject -> obj.toString()
            is JSONArray -> obj.toString()
            else -> JSONObject().put("value", obj).toString()
        }
        return Response(status, "application/json; charset=utf-8",
            body.toByteArray(StandardCharsets.UTF_8))
    }

    private fun jsonError(status: Int, msg: String): Response =
        jsonResponse(JSONObject().put("ok", false).put("error", msg), status)

    private fun jsonOk(data: JSONObject = JSONObject()): Response {
        val o = JSONObject()
        o.put("ok", true)
        o.put("success", true)
        for (k in data.keys()) o.put(k, data.get(k))
        return jsonResponse(o)
    }

    private fun route(req: Request): Response {
        if (req.method == "OPTIONS") {
            return Response(204, "text/plain", ByteArray(0))
        }
        if (req.path.startsWith("/api/")) {
            return routeApi(req)
        }
        return try { serveStatic(req.path) }
        catch (_: FileNotFoundException) { jsonError(404, "not found") }
    }

    private fun routeApi(req: Request): Response {
        val path = req.path
        return try {
            when {
                path == "/api/stats" -> apiStats()
                path == "/api/accounts" || path == "/api/accounts/list" -> when (req.method) {
                    "GET" -> apiGetAccounts()
                    else -> methodNotAllowed()
                }
                path == "/api/accounts/switch" -> if (req.method == "POST") apiSwitchAccount(req.body) else methodNotAllowed()
                path == "/api/accounts/delete" -> if (req.method == "POST" || req.method == "DELETE") apiDeleteAccount(req.body, req.query) else methodNotAllowed()
                path == "/api/accounts/import_cookie" || path == "/api/auth/cookie/login" -> if (req.method == "POST") apiImportCookie(req.body) else methodNotAllowed()
                path == "/api/auth/qrcode/create" -> if (req.method == "POST") apiCreateQrCode() else methodNotAllowed()
                path == "/api/auth/qrcode/check" -> if (req.method == "GET") apiCheckQrCode(req.query) else methodNotAllowed()
                path == "/api/chats" -> jsonOk(JSONObject().put("chats", JSONArray()))
                path == "/api/chat/messages" -> jsonOk(JSONObject().put("messages", JSONArray()))
                path == "/api/cards/stock" -> apiCardsStock(req.query)
                path == "/api/cards/list" || path == "/api/cards" -> when (req.method) {
                    "GET" -> apiGetCards(req.query)
                    "POST" -> apiPostCards(req.body)
                    "DELETE" -> apiDeleteCard(req.query)
                    else -> methodNotAllowed()
                }
                path == "/api/cards/add" -> apiPostCards(req.body)
                path == "/api/cards/import" -> apiPostCardsImport(req.body)
                path.startsWith("/api/cards/") && req.method == "DELETE" -> {
                    val idStr = path.substringAfterLast('/')
                    apiDeleteCard(mapOf("id" to idStr))
                }
                path == "/api/delivery/rules" || path == "/api/rules" -> when (req.method) {
                    "GET" -> apiGetRules()
                    "POST" -> apiPostRule(req.body)
                    "DELETE" -> apiDeleteRule(req.query)
                    else -> methodNotAllowed()
                }
                path.startsWith("/api/delivery/rules/") && req.method == "DELETE" -> {
                    val idStr = path.substringAfterLast('/')
                    apiDeleteRule(mapOf("id" to idStr))
                }
                path == "/api/delivery/logs" || path == "/api/orders" || path == "/api/delivery/orders" -> apiGetOrders()
                path == "/api/orders/waiting" -> apiGetWaitingOrders()
                path == "/api/delivery/ship_now" -> if (req.method == "POST") apiShipNow(req.body) else methodNotAllowed()
                path == "/api/publish/item" || path == "/api/publish" -> apiPublish(req.body)
                path == "/api/publish/list" -> jsonOk(JSONObject().put("items", JSONArray()))
                path == "/api/config" || path == "/api/cookie" -> when (req.method) {
                    "GET" -> apiGetConfig()
                    "POST" -> apiPostConfig(req.body)
                    else -> methodNotAllowed()
                }
                path == "/api/prompts" -> when (req.method) {
                    "GET" -> jsonOk(JSONObject().put("prompts", JSONObject()))
                    "POST" -> jsonOk()
                    else -> methodNotAllowed()
                }
                path == "/api/daemon" -> apiGetDaemon()
                path == "/api/daemon/toggle" -> apiToggleDaemon()
                else -> jsonError(404, "unknown api: $path")
            }
        } catch (e: Throwable) {
            jsonError(500, "api error: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun methodNotAllowed() = jsonError(405, "method not allowed")

    // ---------------------------------------------------------------- 业务处理

    private fun apiStats(): Response {
        val db = CardDatabase.get(context)
        val stats = db.getStats()
        val activeAcc = db.getActiveAccount()
        val cookie = db.getActiveCookie()
        val nick = activeAcc?.nick ?: db.getSetting("cookie_nick", "")

        val res = JSONObject().apply {
            put("total_msgs", 0)
            put("total_users", 0)
            put("total_items", 0)
            put("total_delivered", stats.todayDelivered)
            put("available_cards", stats.availableCards)
            put("used_cards", stats.usedCards)
            put("todayDelivered", stats.todayDelivered)
            put("availableCards", stats.availableCards)
            put("usedCards", stats.usedCards)
            put("totalRules", stats.totalRules)

            val cookieObj = JSONObject()
            cookieObj.put("hasCookie", cookie.isNotBlank())
            cookieObj.put("nick", nick)
            cookieObj.put("userId", activeAcc?.userId ?: "")
            cookieObj.put("valid", cookie.isNotBlank())
            put("cookie", cookieObj)

            val accObj = JSONObject()
            accObj.put("hasAccount", activeAcc != null)
            accObj.put("id", activeAcc?.id ?: 0L)
            accObj.put("nick", nick)
            accObj.put("userId", activeAcc?.userId ?: "")
            accObj.put("status", activeAcc?.status ?: "NONE")
            put("account", accObj)

            val daemon = JSONObject()
            daemon.put("running", DaemonService.isRunning)
            daemon.put("enabled", db.getSetting("daemon_enabled", "true") == "true")
            put("daemon", daemon)
        }
        return jsonOk(res)
    }

    private fun apiGetAccounts(): Response {
        val db = CardDatabase.get(context)
        val accounts = db.getAccounts()
        val active = db.getActiveAccount()
        val arr = JSONArray()
        for (a in accounts) {
            val o = JSONObject()
            o.put("id", a.id)
            o.put("userId", a.userId)
            o.put("nick", a.nick)
            o.put("avatar", a.avatar)
            o.put("isActive", a.isActive)
            o.put("status", a.status)
            o.put("updatedAt", a.updatedAt)
            arr.put(o)
        }
        val out = JSONObject()
        out.put("accounts", arr)
        out.put("activeId", active?.id ?: 0L)
        out.put("count", arr.length())
        return jsonOk(out)
    }

    private fun apiSwitchAccount(body: String): Response {
        val json = try { JSONObject(body) } catch (_: Exception) { null }
            ?: return jsonError(400, "invalid json")
        val id = json.optLong("id", 0L)
        if (id <= 0) return jsonError(400, "id required")
        val db = CardDatabase.get(context)
        val ok = db.switchAccount(id)
        return if (ok) jsonOk() else jsonError(404, "account not found")
    }

    private fun apiDeleteAccount(body: String, query: Map<String, String>): Response {
        val db = CardDatabase.get(context)
        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        val id = query["id"]?.toLongOrNull() ?: json.optLong("id", 0L)
        if (id > 0) {
            val ok = db.deleteAccount(id)
            return jsonOk(JSONObject().put("deleted", ok))
        }
        val uid = json.optString("uid", "").ifBlank { query["uid"].orEmpty() }
        if (uid.isNotBlank()) {
            val acc = db.getAccounts().firstOrNull { it.userId == uid }
            if (acc != null) {
                val ok = db.deleteAccount(acc.id)
                return jsonOk(JSONObject().put("deleted", ok))
            }
        }
        return jsonError(400, "id or uid required")
    }

    private fun apiImportCookie(body: String): Response {
        val json = try { JSONObject(body) } catch (_: Exception) { null }
            ?: return jsonError(400, "invalid json")
        val cookie = json.optString("cookie", "").trim()
        if (cookie.isEmpty()) return jsonError(400, "cookie required")

        val (ok, testNick) = MtopClient.testCookie(cookie)
        val map = MtopSigner.parseCookies(cookie)
        val unb = map["unb"] ?: map["tracknick"] ?: ("uid_" + System.currentTimeMillis().toString().takeLast(8))
        val nick = if (ok && testNick.isNotBlank()) testNick else (map["tracknick"] ?: unb)

        val db = CardDatabase.get(context)
        val id = db.addOrUpdateAccount(
            userId = unb,
            nick = nick,
            avatar = "",
            cookie = cookie,
            activate = true
        )

        val out = JSONObject()
        out.put("id", id)
        out.put("valid", ok)
        out.put("nick", nick)
        out.put("userId", unb)
        out.put("message", if (ok) "登录成功" else "已添加，但Cookie可能已过期")
        return jsonOk(out)
    }

    private fun apiCreateQrCode(): Response {
        val (ok, qrData) = QrLoginService.generateQrCode()
        if (!ok || qrData == null) {
            return jsonError(500, "获取登录二维码失败，请检查网络重试")
        }
        val out = JSONObject().apply {
            put("token", qrData.lgToken)
            put("lgToken", qrData.lgToken)
            put("qrcode_url", qrData.qrImgUrl)
            put("qrImgUrl", qrData.qrImgUrl)
            put("qrCodeText", qrData.qrCodeText)
        }
        return jsonOk(out)
    }

    private fun apiCheckQrCode(query: Map<String, String>): Response {
        val lgToken = query["lgToken"]?.trim().orEmpty().ifBlank { query["token"]?.trim().orEmpty() }
        if (lgToken.isEmpty()) return jsonError(400, "lgToken required")
        val res = QrLoginService.checkQrStatus(context, lgToken)
        val out = JSONObject().apply {
            put("status", res.status.name.lowercase())
            put("statusCode", res.status.name)
            put("message", res.message)
            put("nick", res.nick ?: "")
            put("userId", res.userId ?: "")
        }
        return jsonOk(out)
    }

    private fun apiCardsStock(query: Map<String, String>): Response {
        val db = CardDatabase.get(context)
        val cards = db.getCards("", -1)
        val map = LinkedHashMap<String, Int>()
        for (c in cards) {
            if (!c.isUsed) {
                val key = if (c.sku.isNotBlank()) "${c.keyword} / ${c.sku}" else c.keyword
                map[key] = (map[key] ?: 0) + 1
            }
        }
        val arr = JSONArray()
        for ((k, count) in map) {
            arr.put(JSONObject().put("name", k).put("count", count))
        }
        return jsonOk(JSONObject().put("stock", arr))
    }

    private fun apiGetCards(query: Map<String, String>): Response {
        val keyword = query["keyword"]?.trim() ?: ""
        val statusParam = query["status"]?.toIntOrNull() ?: -1
        val db = CardDatabase.get(context)
        val list = db.getCards(keyword, statusParam)

        val arr = JSONArray()
        for (c in list) {
            val o = JSONObject()
            o.put("id", c.id)
            o.put("keyword", c.keyword)
            o.put("sku", c.sku)
            o.put("card_content", c.cardContent)
            o.put("is_used", if (c.isUsed) 1 else 0)
            o.put("order_id", c.orderId ?: "")
            o.put("used_at", c.usedAt ?: 0L)
            o.put("created_at", c.createdAt)
            arr.put(o)
        }
        val out = JSONObject()
        out.put("cards", arr)
        out.put("list", arr)
        out.put("count", arr.length())
        return jsonOk(out)
    }

    private fun apiPostCards(body: String): Response {
        val json = try { JSONObject(body) } catch (_: Exception) { null }
            ?: return jsonError(400, "invalid json")
        val keyword = json.optString("keyword", "").trim()
        val sku = json.optString("sku", "").trim()
        val content = json.optString("card_content", "").ifBlank { json.optString("content", "") }
        if (keyword.isEmpty() || content.isEmpty()) {
            return jsonError(400, "keyword and content required")
        }
        val lines = listOf(content)
        val db = CardDatabase.get(context)
        val (added, duplicated) = db.addCards(keyword, sku, lines)
        val out = JSONObject()
        out.put("added", added)
        out.put("duplicated", duplicated)
        return jsonOk(out)
    }

    private fun apiPostCardsImport(body: String): Response {
        val json = try { JSONObject(body) } catch (_: Exception) { null }
            ?: return jsonError(400, "invalid json")
        val keyword = json.optString("keyword", "").trim()
        val sku = json.optString("sku", "").trim()
        val content = json.optString("cards_text", "").ifBlank { json.optString("content", "") }
        if (keyword.isEmpty() || content.isEmpty()) {
            return jsonError(400, "keyword and cards_text required")
        }
        val lines = content.split('\n', '\r')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (lines.isEmpty()) return jsonError(400, "content empty")

        val db = CardDatabase.get(context)
        val (added, duplicated) = db.addCards(keyword, sku, lines)
        val out = JSONObject()
        out.put("added", added)
        out.put("duplicates", duplicated)
        out.put("total", lines.size)
        return jsonOk(out)
    }

    private fun apiDeleteCard(query: Map<String, String>): Response {
        val id = query["id"]?.toLongOrNull() ?: return jsonError(400, "id required")
        val db = CardDatabase.get(context)
        val deleted = db.deleteCard(id)
        val out = JSONObject().put("deleted", deleted)
        return jsonOk(out)
    }

    private fun apiGetRules(): Response {
        val db = CardDatabase.get(context)
        val rules = db.getRules()
        val arr = JSONArray()
        for (r in rules) {
            val o = JSONObject()
            o.put("id", r.id)
            o.put("keyword", r.keyword)
            o.put("sku", r.sku)
            o.put("use_pool", if (r.usePool) 1 else 0)
            o.put("fixed_content", r.fixedContent)
            o.put("trade_text", r.tradeText)
            o.put("created_at", r.createdAt)
            arr.put(o)
        }
        val out = JSONObject()
        out.put("rules", arr)
        out.put("count", arr.length())
        return jsonOk(out)
    }

    private fun apiPostRule(body: String): Response {
        val json = try { JSONObject(body) } catch (_: Exception) { null }
            ?: return jsonError(400, "invalid json")
        val id = json.optLong("id", 0L)
        val keyword = json.optString("keyword", "").trim()
        val sku = json.optString("sku", "").trim()
        val usePool = if (json.has("use_pool")) json.optInt("use_pool") == 1 else json.optBoolean("usePool", true)
        val fixedContent = json.optString("fixed_content", "").ifBlank { json.optString("fixedContent", "") }.trim()
        val tradeText = json.optString("trade_text", "").ifBlank { json.optString("tradeText", "自动发货已完成") }.trim()
        if (keyword.isEmpty()) return jsonError(400, "keyword required")

        val db = CardDatabase.get(context)
        val savedId = db.saveRule(id, keyword, sku, usePool, fixedContent, tradeText)
        val out = JSONObject().put("id", savedId)
        return jsonOk(out)
    }

    private fun apiDeleteRule(query: Map<String, String>): Response {
        val id = query["id"]?.toLongOrNull() ?: return jsonError(400, "id required")
        val db = CardDatabase.get(context)
        val deleted = db.deleteRule(id)
        val out = JSONObject().put("deleted", deleted)
        return jsonOk(out)
    }

    private fun apiGetWaitingOrders(): Response {
        val db = CardDatabase.get(context)
        val cookie = db.getActiveCookie()
        if (cookie.isBlank()) return jsonOk(JSONObject().put("orders", JSONArray()).put("count", 0))
        val list = MtopClient.querySoldOrders(cookie)
        val arr = JSONArray()
        for (o in list) {
            val item = JSONObject().apply {
                put("orderId", o.orderId)
                put("title", o.title)
                put("sku", o.sku)
                put("buyer", o.buyerNick)
                put("status", o.status)
                put("quantity", o.quantity)
            }
            arr.put(item)
        }
        return jsonOk(JSONObject().put("orders", arr).put("count", arr.length()))
    }

    private fun apiShipNow(body: String): Response {
        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        val orderId = json.optString("orderId", "").trim()
        val title = json.optString("title", "").trim()
        val sku = json.optString("sku", "").trim()
        val qty = json.optInt("quantity", 1).coerceAtLeast(1)

        val db = CardDatabase.get(context)
        val cookie = db.getActiveCookie()
        if (cookie.isBlank()) return jsonError(400, "未绑定闲鱼账号")
        if (orderId.isBlank()) return jsonError(400, "订单号不能为空")

        val rule = db.matchRule(title, sku)
        val tradeText = rule?.tradeText?.ifBlank { "虚拟商品已自动交付" } ?: "虚拟商品已自动交付"

        // 幂等：该订单是否已经发过货（卡密已出库 / 已有成功日志），避免重复发送
        val alreadyShipped = db.hasOrderDelivered(orderId)
        if (alreadyShipped) {
            return jsonOk(
                JSONObject().apply {
                    put("ok", true)
                    put("success", true)
                    put("duplicated", true)
                    put("message", "该订单此前已发货，未重复发送")
                    put("cardsDelivered", 0)
                }
            )
        }

        val cardList = mutableListOf<String>()
        if (rule?.usePool == true) {
            cardList.addAll(db.claimCardsAtomic(rule.keyword, rule.sku, orderId, qty))
        }

        val content = buildString {
            if (cardList.isNotEmpty()) {
                append("🎫 卡密已发放 [${cardList.size}件]:\n")
                cardList.forEachIndexed { i, c -> append("${i + 1}. $c\n") }
            }
            if (rule?.fixedContent?.isNotBlank() == true) {
                if (cardList.isNotEmpty()) append("\n")
                append(rule.fixedContent)
            }
        }.trim()

        val (ok, msg) = MtopClient.confirmDummyShipping(cookie, orderId, tradeText)
        db.recordLog(orderId, rule?.keyword ?: "手动发货", content, if (ok) "发货完成" else "发货反馈: $msg")

        val out = JSONObject().apply {
            put("ok", ok)
            put("success", ok)
            put("message", if (ok) "发货成功！" else msg)
            put("cardsDelivered", cardList.size)
        }
        return jsonResponse(out)
    }

    private fun apiGetOrders(): Response {
        val db = CardDatabase.get(context)
        val logs = db.getLogs(200)
        val arr = JSONArray()
        for (l in logs) {
            val o = JSONObject()
            o.put("id", l.id)
            o.put("order_id", l.orderId)
            o.put("keyword", l.keyword)
            o.put("delivery_content", l.deliveryContent)
            o.put("confirm_status", l.confirmStatus)
            o.put("created_at", l.createdAt)
            arr.put(o)
        }
        val out = JSONObject()
        out.put("logs", arr)
        out.put("orders", arr)
        out.put("count", arr.length())
        return jsonOk(out)
    }

    private fun apiPublish(body: String): Response {
        val json = try { JSONObject(body) } catch (_: Exception) { null }
            ?: return jsonError(400, "invalid json")
        val db = CardDatabase.get(context)
        val cookie = db.getSetting("cookie", "")
        if (cookie.isBlank()) return jsonError(400, "请先在设置中填写闲鱼 Cookie")

        val title = json.optString("title", "").trim()
        val price = json.optString("price", "").trim()
        val desc = json.optString("desc", "").trim()
        val categoryId = json.optString("categoryId", "").ifBlank { json.optString("category_id", "") }.trim()

        val (ok, res) = MtopClient.publishItem(cookie, title, price, desc, categoryId)
        val out = JSONObject()
        out.put("ok", ok)
        out.put("message", res)
        return jsonResponse(out)
    }

    private fun apiGetConfig(): Response {
        val db = CardDatabase.get(context)
        val cookie = db.getSetting("cookie", "")
        val nick = db.getSetting("cookie_nick", "")
        val o = JSONObject()
        o.put("cookie", cookie)
        o.put("nick", nick)
        o.put("hasCookie", cookie.isNotBlank())
        o.put("daemon_enabled", db.getSetting("daemon_enabled", "true") == "true")
        return jsonOk(o)
    }

    private fun apiPostConfig(body: String): Response {
        val json = try { JSONObject(body) } catch (_: Exception) { null }
            ?: return jsonError(400, "invalid json")
        val cookie = json.optString("cookie", "").trim()
        val db = CardDatabase.get(context)
        if (cookie.isNotEmpty()) {
            val (ok, nickOrMsg) = MtopClient.testCookie(cookie)
            db.saveSetting("cookie", cookie)
            if (ok) db.saveSetting("cookie_nick", nickOrMsg)
            return jsonOk(JSONObject().put("valid", ok).put("nick", if (ok) nickOrMsg else "").put("message", nickOrMsg))
        }
        if (json.has("daemon_enabled")) {
            val enabled = json.optBoolean("daemon_enabled", true)
            db.saveSetting("daemon_enabled", enabled.toString())
            if (enabled) DaemonService.start(context) else DaemonService.stop(context)
        }
        return jsonOk()
    }

    private fun apiGetDaemon(): Response {
        val db = CardDatabase.get(context)
        val o = JSONObject()
        o.put("running", DaemonService.isRunning)
        o.put("enabled", db.getSetting("daemon_enabled", "true") == "true")
        return jsonOk(o)
    }

    private fun apiToggleDaemon(): Response {
        val db = CardDatabase.get(context)
        val current = db.getSetting("daemon_enabled", "true") == "true"
        val nowEnabled = !current
        db.saveSetting("daemon_enabled", nowEnabled.toString())

        if (nowEnabled) {
            DaemonService.start(context)
        } else {
            DaemonService.stop(context)
        }

        val o = JSONObject()
        o.put("enabled", nowEnabled)
        o.put("running", DaemonService.isRunning)
        return jsonOk(o)
    }

    // ---------------------------------------------------------------- 静态资源

    private fun serveStatic(path: String): Response {
        val assetPath = when {
            path == "/" || path == "/index.html" || path.isEmpty() -> "web/index.html"
            path.startsWith("/web/") -> path.removePrefix("/")
            path.startsWith("/") -> "web" + path
            else -> "web/$path"
        }
        if (assetPath.contains("..") || assetPath.startsWith("/")) {
            return jsonError(404, "not found")
        }
        val bytes = try {
            context.assets.open(assetPath).use { it.readBytesCompat() }
        } catch (_: FileNotFoundException) {
            return jsonError(404, "not found")
        } catch (_: Exception) {
            return jsonError(500, "read asset failed")
        }
        val mime = mimeType(assetPath)
        return Response(200, mime, bytes)
    }

    private fun InputStream.readBytesCompat(): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = read(buf)
            if (n == -1) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun mimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> "text/html; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "js", "mjs" -> "application/javascript; charset=utf-8"
            "json" -> "application/json; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "ico" -> "image/x-icon"
            "txt" -> "text/plain; charset=utf-8"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            else -> "application/octet-stream"
        }
    }
}
