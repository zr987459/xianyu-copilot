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

    /**
     * 每个实例启动时生成的随机访问令牌。
     *
     * 修复背景：旧版 127.0.0.1:9005 的 api 路由无任何鉴权，且带 Access-Control-Allow-Origin 通配，
     * 手机上任意网页都能直接调用 import_cookie / accounts_switch 窃取或篡改账号。
     *
     * 现在：
     *  - API 请求必须携带 X-Api-Token，否则 401；
     *  - 不再回 CORS 通配头，外部页面无法读取响应；
     *  - 校验 Host 头，阻断 DNS rebinding；
     *  - 令牌既不进 HTML 也不收 query 参数，只通过 AndroidBridge.getApiToken() 交给 App 自己的 WebView。
     */
    val apiToken: String = generateToken()

    init {
        INSTANCE = this
    }

    companion object {
        /** 当前进程内唯一实例，供 AndroidBridge 取令牌。 */
        @Volatile private var INSTANCE: LocalServer? = null

        fun current(): LocalServer? = INSTANCE
    }

    private fun generateToken(): String {
        val bytes = ByteArray(24)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { String.format("%02x", it) }
    }

    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var running = false

    /** 是否已真正绑定 9005。MainActivity 据此判断，避免加载被其它 App 抢占的端口。 */
    @Volatile var listening = false
        private set
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
                listening = true
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
                listening = false
                running = false
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
            400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 429 -> "Too Many Requests"
            500 -> "Internal Server Error"
            else -> "OK"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(resp.status).append(' ').append(reason).append("\r\n")
        sb.append("Content-Type: ").append(resp.contentType).append("\r\n")
        sb.append("Content-Length: ").append(resp.body.size).append("\r\n")
        sb.append("Connection: close\r\n")
        // 安全：不再回 Access-Control-Allow-Origin: *，
        // 外部页面无法带自定义请求头发起简单请求，也读不到响应体。
        sb.append("X-Content-Type-Options: nosniff\r\n")
        sb.append("Cache-Control: no-store\r\n")
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
        // 第一道门：Host 头必须是本机地址，阻断 DNS rebinding
        if (!hasValidHost(req)) {
            return Response(403, "text/plain", "forbidden".toByteArray(StandardCharsets.UTF_8))
        }
        if (req.method == "OPTIONS") {
            return Response(204, "text/plain", ByteArray(0))
        }
        if (req.path.startsWith("/api/")) {
            // 安全门禁：所有 api 路由必须携带正确令牌，否则 401。
            // 令牌只由 App 自己 WebView 携带，外部页面拿不到。
            if (!isAuthorized(req)) {
                return jsonError(401, "unauthorized")
            }
            return routeApi(req)
        }
        return try { serveStatic(req.path) }
        catch (_: FileNotFoundException) { jsonError(404, "not found") }
    }

    /**
     * 校验请求令牌：只接受 X-Api-Token 头与 Authorization: Bearer。
     *
     * 修复：不再接受 ?token= 查询参数——它会进访问日志、历史记录，容易被旁路泄露。
     */
    private fun isAuthorized(req: Request): Boolean {
        val fromHeader = req.headers["x-api-token"].orEmpty()
        if (fromHeader.isNotEmpty() && constantTimeEquals(fromHeader, apiToken)) return true

        val bearer = req.headers["authorization"].orEmpty()
        if (bearer.startsWith("Bearer ", ignoreCase = true)) {
            val t = bearer.substring(7).trim()
            if (t.isNotEmpty() && constantTimeEquals(t, apiToken)) return true
        }
        return false
    }

    /**
     * Host 头白名单，防 DNS rebinding。
     *
     * 恶意域名解析到 127.0.0.1 后，浏览器会认为它与目标同源；校验 Host 可确保
     * 只有真正指向本机的请求才被处理。
     */
    private fun hasValidHost(req: Request): Boolean {
        val host = req.headers["host"].orEmpty().trim().lowercase()
        if (host.isEmpty()) return false
        return host == "127.0.0.1:$PORT" ||
            host == "localhost:$PORT" ||
            host == "[::1]:$PORT" ||
            host == "127.0.0.1" ||
            host == "localhost"
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private val apiV2 by lazy { ApiV2(context) }

    private fun routeApi(req: Request): Response {
        val path = req.path
        // v1.5.0 新接口（消息 / 自动回复 / AI / 通知 / 订单 / 商品管理 / 采集）
        try {
            apiV2.handle(req.method, path, req.query, req.body)?.let { (code, obj) -> return jsonResponse(obj, code) }
        } catch (e: Throwable) {
            return jsonError(500, "api error: ${e.message ?: e.javaClass.simpleName}")
        }
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
                path == "/api/items/on_sale" || path == "/api/publish/list" -> apiGetOnSaleItems(req.query)
                path == "/api/publish/upload_image" -> if (req.method == "POST") apiUploadImage(req.body) else methodNotAllowed()
                path == "/api/publish/item" || path == "/api/publish" -> if (req.method == "POST") apiPublish(req.body) else methodNotAllowed()
                path == "/api/config" || path == "/api/cookie" -> when (req.method) {
                    "GET" -> apiGetConfig()
                    "POST" -> apiPostConfig(req.body)
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
            val ops = OpsDatabase.get(context)
            put("total_msgs", ops.count("SELECT COUNT(*) FROM messages"))
            put("total_users", ops.count("SELECT COUNT(*) FROM conversations"))
            put("total_items", ops.count("SELECT COUNT(*) FROM items"))
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
            // 有效性取最近一次真实校验结果，而不是「cookie 非空」
            val lastCheck = activeAcc?.let { db.getAccountCheck(it.userId) }
            val checkedOk = lastCheck == null || lastCheck.first == 0L || lastCheck.second
            cookieObj.put("valid", cookie.isNotBlank() && checkedOk)
            cookieObj.put("lastCheckAt", lastCheck?.first ?: 0L)
            cookieObj.put("lastCheckMsg", lastCheck?.third ?: "")
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

        // 修复：旧版校验失败也 activate=true，会把当前正常账号顶下线。
        // 现在统一走 AccountManager：失败就只保存不激活，并如实告知原因。
        val result = AccountManager.saveLogin(
            context,
            cookie,
            AccountManager.Source.COOKIE,
            activateOnSuccess = true
        )

        if (!result.ok) {
            val out = JSONObject()
            out.put("valid", false)
            out.put("nick", result.nick)
            out.put("userId", result.userId)
            out.put("activated", false)
            out.put("message", result.message)
            // 400：校验未通过，但已保留记录，不激活
            return jsonResponse(JSONObject().put("ok", false).put("success", false).put("message", result.message).put("data", out), 400)
        }

        val out = JSONObject()
        out.put("id", result.accountId)
        out.put("valid", true)
        out.put("nick", result.nick)
        out.put("userId", result.userId)
        out.put("activated", result.activated)
        out.put("message", result.message)
        return jsonOk(out)
    }

    private fun apiCreateQrCode(): Response {
        val (ok, qrData) = QrLoginService.generateQrCode()
        if (!ok || qrData == null) {
            return jsonError(500, "获取登录二维码失败，请检查网络重试")
        }
        val out = JSONObject().apply {
            // 会话 ID：后续轮询扫码状态时带回
            put("token", qrData.sessionId)
            put("sessionId", qrData.sessionId)
            put("lgToken", qrData.sessionId)
            // 本地渲染的二维码图片（data-url），前端直接 <img src>
            put("qrImage", qrData.qrImageDataUrl)
            put("qrDataUrl", qrData.qrImageDataUrl)
            // 二维码原始内容（调试 / 手输备用）
            put("qrCodeText", qrData.qrContent)
        }
        return jsonOk(out)
    }

    private fun apiCheckQrCode(query: Map<String, String>): Response {
        val sessionId = query["sessionId"]?.trim().orEmpty()
            .ifBlank { query["lgToken"]?.trim().orEmpty() }
            .ifBlank { query["token"]?.trim().orEmpty() }
        if (sessionId.isEmpty()) return jsonError(400, "sessionId required")
        val res = QrLoginService.checkQrStatus(sessionId)
        val out = JSONObject().apply {
            put("status", res.status.name.lowercase())
            put("statusCode", res.status.name)
            put("message", res.message)
            put("nick", res.nick ?: "")
            put("userId", res.userId ?: "")
            if (res.status == QrLoginService.Status.VERIFICATION_REQUIRED) {
                put("verificationUrl", res.verificationUrl ?: "")
            }
        }
        // 扫码失败/过期也要让前端能清楚看到原因，不能静默返回 ok
        return if (res.status == QrLoginService.Status.ERROR) {
            jsonResponse(JSONObject().put("ok", true).put("success", true).put("data", out), 200)
        } else {
            jsonOk(out)
        }
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
            o.put("delay_minutes", r.delayMinutes)
            o.put("template_id", r.templateId)
            o.put("send_im", if (r.sendIm) 1 else 0)
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
        val delay = json.optInt("delay_minutes", 0)
        val templateId = json.optLong("template_id", 0L)
        val sendIm = if (json.has("send_im")) json.optInt("send_im", 1) == 1 else true
        val savedId = db.saveRule(id, keyword, sku, usePool, fixedContent, tradeText, delay, templateId, sendIm)
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
        // 令牌过期时自动刷新并回写，避免「打开页面就要求重新登录」
        val list = MtopClient.querySoldOrders(
            cookie,
            onCookieRefreshed = { refreshed ->
                AccountManager.persistRefreshedCookie(context, refreshed)
            }
        )
        val arr = JSONArray()
        for (o in list) {
            val item = JSONObject().apply {
                put("orderId", o.orderId)
                put("title", o.title)
                put("sku", o.sku)
                put("buyer", o.buyerNick)
                put("status", o.status)
                put("quantity", o.quantity)
                put("price", o.price)
                put("buyerCity", o.buyerCity)
                put("buyerMessage", o.buyerMessage)
                put("createTime", o.createTime)
                put("buyerId", o.buyerId)
                put("itemId", o.itemId)
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

        val acc = json.optString("account").ifBlank { db.getActiveAccount()?.userId.orEmpty() }
        // v1.5.0：走统一发货引擎（模板 / IM 私信 / 幂等任务表），与自动发货完全一致
        val r = kotlinx.coroutines.runBlocking {
            DeliveryEngine.shipNow(context, orderId, acc, title, sku, qty,
                json.optString("buyerId"), json.optString("buyer"), json.optString("itemId"))
        }
        val out = JSONObject().apply {
            put("ok", r.ok)
            put("success", r.ok)
            put("duplicated", r.duplicated)
            put("message", r.message)
            put("cardsDelivered", r.cardsDelivered)
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

    private fun apiGetOnSaleItems(query: Map<String, String>): Response {
        val db = CardDatabase.get(context)
        val cookie = db.getActiveCookie()
        if (cookie.isBlank()) return jsonOk(JSONObject().put("items", JSONArray()).put("count", 0))
        val page = query["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        val src = query["source"]?.takeIf { it in setOf("personal", "shop", "all") && page > 1 }
        val res = MtopClient.queryOnSaleItems(
            cookie = cookie,
            page = page,
            source = src,
            onCookieRefreshed = { refreshed ->
                AccountManager.persistRefreshedCookie(context, refreshed)
            }
        )
        if (!res.ok) {
            val status = if (MtopClient.isRiskControlPublic(res.error)) 429 else 400
            val msg = if (res.error.isBlank()) "获取在售宝贝失败，请检查账号状态或网络" else "获取在售宝贝失败: ${res.error}"
            return jsonError(status, msg)
        }
        val arr = JSONArray()
        val ops = OpsDatabase.get(context)
        val accId = db.getActiveAccount()?.userId.orEmpty()
        for (it in res.items) {
            runCatching { ops.upsertItem(it.id, accId, it.title, it.price, "", it.imageUrl, it.status) }
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("title", it.title)
                put("price", it.price)
                put("imageUrl", it.imageUrl)
                put("status", it.status)
                put("detailUrl", it.detailUrl)
                put("source", it.source)
                put("onSale", it.onSale)
            })
        }
        return jsonOk(JSONObject().put("items", arr).put("count", arr.length()).put("source", res.source).put("ok", true))
    }

    private fun apiUploadImage(body: String): Response {
        val json = try { JSONObject(body) } catch (_: Exception) { null }
            ?: return jsonError(400, "invalid json")
        val raw = json.optString("data", "").ifBlank { json.optString("image", "") }
        if (raw.isBlank()) return jsonError(400, "image data required")

        val b64 = if (raw.contains(",")) raw.substringAfter(',') else raw
        val bytes = try {
            android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
        } catch (_: Exception) { return jsonError(400, "invalid base64 image") }

        val db = CardDatabase.get(context)
        val cookie = db.getActiveCookie()
        if (cookie.isBlank()) return jsonError(400, "未绑定闲鱼账号")

        val filename = json.optString("filename", "img_${System.currentTimeMillis()}.jpg")
        val (ok, img) = MtopClient.uploadImage(cookie, bytes, filename)
        if (!ok || img == null) return jsonError(500, "图片上传到闲鱼图床失败，请检查网络")

        val out = JSONObject().apply {
            put("url", img.url)
            put("width", img.widthSize)
            put("height", img.heightSize)
        }
        return jsonOk(out)
    }

    private fun apiPublish(body: String): Response {
        val json = try { JSONObject(body) } catch (_: Exception) { null }
            ?: return jsonError(400, "invalid json")
        val db = CardDatabase.get(context)
        val cookie = db.getActiveCookie()
        if (cookie.isBlank()) return jsonError(400, "未绑定闲鱼账号")

        val title = json.optString("title", "").trim()
        val price = json.optDouble("price", 0.0)
        val desc = json.optString("desc", "").trim()

        val imgArr = json.optJSONArray("images")
        val images = ArrayList<MtopClient.UploadedImage>()
        if (imgArr != null) {
            for (i in 0 until imgArr.length()) {
                val item = imgArr.opt(i)
                if (item is JSONObject) {
                    val u = item.optString("url")
                    val w = item.optInt("width", 800)
                    val h = item.optInt("height", 800)
                    if (u.isNotBlank()) images.add(MtopClient.UploadedImage(u, w, h))
                } else if (item is String && item.isNotBlank()) {
                    images.add(MtopClient.UploadedImage(item))
                }
            }
        }

        val (ok, res) = MtopClient.publishItem(
            cookie = cookie,
            title = title,
            desc = desc,
            price = price,
            images = images,
            onCookieRefreshed = { refreshed ->
                AccountManager.persistRefreshedCookie(context, refreshed)
            }
        )
        val out = JSONObject().apply {
            put("ok", ok)
            put("message", res)
        }
        return if (ok) jsonOk(out) else jsonError(400, res)
    }

    private fun apiGetConfig(): Response {
        val db = CardDatabase.get(context)
        val cookie = db.getSetting("cookie", "")
        val nick = db.getSetting("cookie_nick", "")
        val o = JSONObject()
        o.put("cookie", cookie)
        o.put("nick", nick)
        o.put("hasCookie", cookie.isNotBlank())
        // 修复：旧版 valid = cookie.isNotBlank() 是假的有效性；
        // 且 last_check 是全局的，切号后显示的是上一个账号的结果。
        // 现在读「当前激活账号自己」的校验状态。
        val unb = MtopSigner.parseCookies(cookie)["unb"].orEmpty()
        val (lastTs, lastOk, lastDetail) =
            if (unb.isBlank()) Triple(0L, false, "") else db.getAccountCheck(unb)
        val valid = cookie.isNotBlank() && lastOk
        o.put("valid", valid)
        o.put("lastCheckAt", lastTs)
        o.put("lastCheckOk", lastOk)
        o.put("lastCheckDetail", lastDetail)
        o.put("daemon_enabled", db.getSetting("daemon_enabled", "true") == "true")
        return jsonOk(o)
    }

    private fun apiPostConfig(body: String): Response {
        val json = try { JSONObject(body) } catch (_: Exception) { null }
            ?: return jsonError(400, "invalid json")
        val cookie = json.optString("cookie", "").trim()
        val db = CardDatabase.get(context)
        if (cookie.isNotEmpty()) {
            // 统一走 AccountManager：包含去重、令牌自愈、真实校验与激活策略
            val result = AccountManager.saveLogin(
                context,
                cookie,
                AccountManager.Source.COOKIE,
                activateOnSuccess = true
            )
            return jsonOk(
                JSONObject()
                    .put("valid", result.ok)
                    .put("nick", if (result.ok) result.nick else "")
                    .put("userId", result.userId)
                    .put("activated", result.activated)
                    .put("message", result.message)
            )
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
        // 注入访问令牌：仅对 App 自己的 WebView 页面生效（同源）。
        // 外部浏览器访问没有 app 注入令牌也无所谓，但 static 页本身不含敏感数据。
        // 修复：不再把令牌写进 HTML。
        // 本机任何 App 只要 GET / 就能读到令牌，等于鉴权形同虚设。
        // 现在令牌只能通过 AndroidBridge.getApiToken() 从 App 自己的 WebView 拿到。
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
