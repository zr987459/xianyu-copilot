package com.xianyu.copilot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 闲鱼 IM 长连接客户端（每个账号一个实例）。
 *
 * 协议移植自 xianyu-auto-reply / xianyu-guanjia 的 XianyuAutoAsync.py（MIT）：
 *  1. mtop.taobao.idlemessage.pc.login.token 换 accessToken；
 *  2. 连接 wss://wss-goofish.dingtalk.com/，发送 lwp=/reg 注册，再 /r/SyncStatus/ackDiff；
 *  3. 每 15s 发 lwp=/! 心跳；服务端推送必须回 ACK（code=200 + 原 mid/sid）；
 *  4. 推送体 body.syncPushPackage.data[].data 为 base64：能直接解析成 JSON 的是系统提示，
 *     否则是 msgpack 编码的聊天消息；
 *  5. 发消息走 /r/MessageSend/sendByReceiverScope，内容为 base64({"contentType":1,"text":{"text":..}})。
 *
 * 字段路径（"1"→"10"→reminderContent 等）来自参考项目，未用本 App 真机抓包校准过，
 * 结构若变化需 real-device calibration；解析失败只会丢消息，不会影响守护进程。
 */
class ImClient(
    private val context: Context,
    val accountId: String,
    private val listener: Listener
) {
    interface Listener {
        fun onImMessage(client: ImClient, message: JSONObject)
        fun onImState(client: ImClient, state: State, detail: String)
    }

    enum class State { STOPPED, CONNECTING, CONNECTED, RECONNECTING, FAILED }

    companion object {
        private const val TAG = "ImClient"
        private const val WSS_URL = "wss://wss-goofish.dingtalk.com/"
        private const val IM_APP_KEY = "444e9908a51d1cb236a27862abc769c9"
        private const val HEARTBEAT_MS = 15_000L
        private const val HEARTBEAT_TIMEOUT_MS = 45_000L
        private const val TOKEN_TTL_MS = 60 * 60 * 1000L
        private const val UA_WS =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"
        private const val UA_REG =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36 DingTalk(2.1.5) OS(Windows/10) Browser(Chrome/133.0.0.0) DingWeb/2.1.5 IMPaaS DingWeb/2.1.5"

        fun generateMid(): String = "${Random.nextInt(1000)}${System.currentTimeMillis()} 0"
        fun generateUuid(): String = "-${System.currentTimeMillis()}1"

        /** 与参考 generate_device_id 同形：36 位 UUID 风格 + "-" + userId。 */
        fun generateDeviceId(userId: String): String {
            val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
            val sb = StringBuilder()
            for (i in 0 until 36) {
                when (i) {
                    8, 13, 18, 23 -> sb.append('-')
                    14 -> sb.append('4')
                    19 -> sb.append(chars[(Random.nextInt(16) and 0x3) or 0x8])
                    else -> sb.append(chars[Random.nextInt(16)])
                }
            }
            return "$sb-$userId"
        }
    }

    private val wsClient: OkHttpClient by lazy {
        MtopClient.sharedClient.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    @Volatile private var ws: WebSocket? = null
    @Volatile private var lastRecvAt = 0L
    @Volatile var state: State = State.STOPPED
        private set
    @Volatile var stateDetail: String = ""
        private set
    @Volatile var connectedAt: Long = 0L
        private set

    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()

    private fun setState(s: State, detail: String = "") {
        state = s
        stateDetail = detail
        runCatching { listener.onImState(this, s, detail) }
    }

    private fun db() = CardDatabase.get(context)

    private fun deviceId(): String {
        val key = "im_did_$accountId"
        val existing = db().getSetting(key, "")
        if (existing.isNotBlank()) return existing
        val did = generateDeviceId(accountId)
        db().saveSetting(key, did)
        return did
    }

    fun isConnected(): Boolean = state == State.CONNECTED && ws != null

    fun start() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch { runLoop() }
    }

    fun stop() {
        loopJob?.cancel()
        loopJob = null
        runCatching { ws?.close(1000, "stop") }
        ws = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        setState(State.STOPPED, "已停止")
    }

    private suspend fun runLoop() {
        var backoff = 5_000L
        while (currentCoroutineContext().isActive) {
            val cookie = db().getAccountCookie(accountId)
            if (cookie.isBlank()) {
                setState(State.FAILED, "账号无 Cookie")
                delay(60_000L)
                continue
            }
            setState(State.CONNECTING, "获取 IM 令牌")
            val (token, err) = MtopClient.getImToken(cookie, deviceId()) { refreshed ->
                AccountManager.persistRefreshedCookie(context, refreshed)
            }
            if (token == null) {
                // 令牌失败不改账号状态（避免误判失效），只在 IM 状态里展示
                setState(State.FAILED, "IM 令牌获取失败: ${err.take(60)}")
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(5 * 60_000L)
                continue
            }
            val stableFor = connectOnce(db().getAccountCookie(accountId).ifBlank { cookie }, token)
            if (stableFor > 60_000L) backoff = 5_000L
            if (!currentCoroutineContext().isActive) break
            setState(State.RECONNECTING, "连接断开，${backoff / 1000}s 后重连")
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(5 * 60_000L)
        }
    }

    /** 建立一次连接并阻塞到断开，返回本次连接持续时长。 */
    private suspend fun connectOnce(cookie: String, token: String): Long {
        val closed = CompletableDeferred<String>()
        val startAt = System.currentTimeMillis()
        val req = Request.Builder()
            .url(WSS_URL)
            .header("Cookie", cookie)
            .header("Origin", "https://www.goofish.com")
            .header("User-Agent", UA_WS)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Cache-Control", "no-cache")
            .header("Pragma", "no-cache")
            .build()

        val socket = wsClient.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                lastRecvAt = System.currentTimeMillis()
                scope.launch { register(webSocket, token) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                lastRecvAt = System.currentTimeMillis()
                runCatching { handleFrame(webSocket, text) }.onFailure { Log.w(TAG, "frame error", it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                closed.complete("closing $code $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                closed.complete("closed $code $reason")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                closed.complete("failure ${t.message ?: t.javaClass.simpleName} ${response?.code ?: ""}")
            }
        })
        ws = socket

        val hb = scope.launch {
            delay(3_000L)
            while (isActive && !closed.isCompleted) {
                socket.send(JSONObject().put("lwp", "/!").put("headers", JSONObject().put("mid", generateMid())).toString())
                delay(HEARTBEAT_MS)
                val now = System.currentTimeMillis()
                if (now - lastRecvAt > HEARTBEAT_TIMEOUT_MS) {
                    Log.w(TAG, "[$accountId] heartbeat timeout, reconnect")
                    socket.cancel()
                    closed.complete("heartbeat timeout")
                }
                // 令牌一小时刷新一次：主动断开，外层循环会重新换 token
                if (now - startAt > TOKEN_TTL_MS) {
                    socket.close(1000, "token refresh")
                    closed.complete("token refresh")
                }
            }
        }

        val reason = try { closed.await() } finally {
            hb.cancel()
            if (ws === socket) ws = null
            pending.values.forEach { it.cancel() }
            pending.clear()
        }
        Log.i(TAG, "[$accountId] ws ended: $reason")
        connectedAt = 0L
        return System.currentTimeMillis() - startAt
    }

    private suspend fun register(socket: WebSocket, token: String) {
        val reg = JSONObject()
            .put("lwp", "/reg")
            .put("headers", JSONObject()
                .put("cache-header", "app-key token ua wv")
                .put("app-key", IM_APP_KEY)
                .put("token", token)
                .put("ua", UA_REG)
                .put("dt", "j")
                .put("wv", "im:3,au:3,sy:6")
                .put("sync", "0,0;0;0;")
                .put("did", deviceId())
                .put("mid", generateMid()))
        socket.send(reg.toString())
        delay(1_000L)
        val now = System.currentTimeMillis()
        val ack = JSONObject()
            .put("lwp", "/r/SyncStatus/ackDiff")
            .put("headers", JSONObject().put("mid", generateMid()))
            .put("body", JSONArray().put(JSONObject()
                .put("pipeline", "sync")
                .put("tooLong2Tag", "PNM,1")
                .put("channel", "sync")
                .put("topic", "sync")
                .put("highPts", 0)
                .put("pts", now * 1000)
                .put("seq", 0)
                .put("timestamp", now)))
        socket.send(ack.toString())
        connectedAt = System.currentTimeMillis()
        setState(State.CONNECTED, "已连接")
    }

    private fun handleFrame(socket: WebSocket, text: String) {
        val msg = runCatching { JSONObject(text) }.getOrNull() ?: return
        val headers = msg.optJSONObject("headers") ?: JSONObject()
        val mid = headers.optString("mid")

        // 1) RPC 回包（按 mid 唤醒）
        if (mid.isNotBlank()) {
            pending.remove(mid)?.let { it.complete(msg); return }
        }
        // 2) 心跳 / 普通回包：没有 lwp 的帧不需要 ACK
        if (!msg.has("lwp") && msg.has("code")) return

        // 3) 服务端推送：先 ACK
        runCatching {
            val ackHeaders = JSONObject()
                .put("mid", mid.ifBlank { generateMid() })
                .put("sid", headers.optString("sid"))
            for (k in listOf("app-key", "ua", "dt")) if (headers.has(k)) ackHeaders.put(k, headers.get(k))
            socket.send(JSONObject().put("code", 200).put("headers", ackHeaders).toString())
        }

        val pkg = msg.optJSONObject("body")?.optJSONObject("syncPushPackage") ?: return
        val arr = pkg.optJSONArray("data") ?: return
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val data = item.optString("data")
            if (data.isBlank()) continue
            val decoded = decodePayload(data) ?: continue
            runCatching { listener.onImMessage(this, decoded) }.onFailure { Log.w(TAG, "listener error", it) }
        }
    }

    /** base64 → JSON（系统提示，含 chatType，忽略） / msgpack（聊天消息）。 */
    private fun decodePayload(data: String): JSONObject? {
        val plain = runCatching {
            String(android.util.Base64.decode(data, android.util.Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()
        if (plain != null) {
            val j = runCatching { JSONObject(plain) }.getOrNull()
            if (j != null) return if (j.has("chatType")) null else j
        }
        return MsgPack.decodeBase64(data) as? JSONObject
    }

    // ------------------------------------------------------------ 主动调用

    /** 发送一个 LWP 请求并等待同 mid 回包。 */
    suspend fun rpc(lwp: String, body: Any, timeoutMs: Long = 12_000L): JSONObject? {
        val socket = ws ?: return null
        val mid = generateMid()
        val d = CompletableDeferred<JSONObject>()
        pending[mid] = d
        val frame = JSONObject().put("lwp", lwp).put("headers", JSONObject().put("mid", mid)).put("body", body)
        if (!socket.send(frame.toString())) {
            pending.remove(mid)
            return null
        }
        return try { withTimeout(timeoutMs) { d.await() } } catch (_: Exception) { null } finally { pending.remove(mid) }
    }

    /** 发文本消息。cid/toUserId 不带 @goofish 后缀。 */
    suspend fun sendText(chatId: String, toUserId: String, text: String): Pair<Boolean, String> {
        if (!isConnected()) return false to "IM 未连接"
        if (chatId.isBlank()) return false to "缺少会话 ID"
        val payload = JSONObject().put("contentType", 1).put("text", JSONObject().put("text", text))
        val b64 = android.util.Base64.encodeToString(payload.toString().toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val receivers = JSONArray()
        if (toUserId.isNotBlank()) receivers.put("$toUserId@goofish")
        receivers.put("$accountId@goofish")
        val body = JSONArray()
            .put(JSONObject()
                .put("uuid", generateUuid())
                .put("cid", "$chatId@goofish")
                .put("conversationType", 1)
                .put("content", JSONObject().put("contentType", 101)
                    .put("custom", JSONObject().put("type", 1).put("data", b64)))
                .put("redPointPolicy", 0)
                .put("extension", JSONObject().put("extJson", "{}"))
                .put("ctx", JSONObject().put("appVersion", "1.0").put("platform", "web"))
                .put("mtags", JSONObject())
                .put("msgReadStatusSetting", 1))
            .put(JSONObject().put("actualReceivers", receivers))
        val resp = rpc("/r/MessageSend/sendByReceiverScope", body)
            ?: return false to "发送超时"
        val code = resp.optInt("code", 200)
        return if (code in 200..299) true to "已发送" else false to "发送失败 code=$code"
    }

    /** 与买家建立 / 复用单聊会话，返回 cid（不带后缀）。 */
    suspend fun createChat(toUserId: String, itemId: String): String? {
        val body = JSONArray().put(JSONObject()
            .put("pairFirst", "$toUserId@goofish")
            .put("pairSecond", "$accountId@goofish")
            .put("bizType", "1")
            .put("extension", JSONObject().put("itemId", itemId))
            .put("ctx", JSONObject().put("appVersion", "1.0").put("platform", "web")))
        val resp = rpc("/r/SingleChatConversation/create", body, 15_000L) ?: return null
        val rb = resp.optJSONObject("body") ?: return null
        val conv = rb.optJSONObject("singleChatConversation") ?: rb.optJSONObject("conversation") ?: rb
        return conv.optString("cid").substringBefore('@').ifBlank { null }
    }

    /** 拉取会话历史（/r/MessageManager/listUserMessages）。 */
    suspend fun listMessages(chatId: String, limit: Int = 40): JSONArray? {
        val body = JSONArray().put("$chatId@goofish").put(false).put(9007199254740991L).put(limit).put(false)
        val resp = rpc("/r/MessageManager/listUserMessages", body, 15_000L) ?: return null
        return resp.optJSONObject("body")?.optJSONArray("userMessageModels")
    }
}
