package com.xianyu.copilot

import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 闲鱼扫码登录（passport.goofish.com 真实链路）。
 *
 * 链路依据 xianyu-auto-reply 的 qr_login/manager.py：
 *  1. GET  passport.goofish.com/mini_login.htm       -> 解析 window.viewData 拿 loginFormData
 *  2. GET  passport.goofish.com/newlogin/qrcode/generate.do (带 loginFormData)
 *                                                    -> content.data.codeContent（二维码内容，不是图片 URL）+ t + ck
 *  3. 本地把 codeContent 渲染成二维码 PNG（base64）
 *  4. POST passport.goofish.com/newlogin/qrcode/query.do (带 loginFormData + t + ck)
 *                                                    -> content.data.qrCodeStatus
 *  5. CONFIRMED 后 CookieJar 里才有 unb / cookie2 等闲鱼域身份
 *
 * 关键差异（相对旧实现）：
 *  - 旧实现走 qrlogin.taobao.com，全程拿不到 goofish 域 Cookie，业务接口必然失败；
 *  - 会话状态是**有状态**的：一次扫码 = 一个独立 CookieJar，全程同一个 UA；
 *  - query.do 必须回传 generate.do 给的 t 与 ck，光靠 Cookie 不够。
 */
object QrLoginService {

    private const val TAG = "QrLoginService"

    private const val HOST = "https://passport.goofish.com"
    private const val URL_MINI_LOGIN = "$HOST/mini_login.htm"
    private const val URL_GENERATE = "$HOST/newlogin/qrcode/generate.do"
    private const val URL_QUERY = "$HOST/newlogin/qrcode/query.do"

    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    enum class Status { WAITING, SCANNED, EXPIRED, SUCCESS, VERIFICATION_REQUIRED, ERROR }

    data class QrData(
        /** 会话 ID，前端轮询时带回 */
        val sessionId: String,
        /** 本地生成的二维码图片 data-url（base64 PNG） */
        val qrImageDataUrl: String,
        /** 二维码原始内容（调试用） */
        val qrContent: String
    )

    data class CheckResult(
        val status: Status,
        val message: String,
        val nick: String? = null,
        val userId: String? = null,
        val verificationUrl: String? = null
    )

    /**
     * 单个扫码会话。每个会话持有自己的 CookieJar，互不干扰。
     * 旧实现用全局单例 CookieJar，刷新二维码或开第二个弹窗会冲掉正在进行的会话。
     */
    private class Session(val id: String) {
        val jar = MemoryCookieJar()
        val client: OkHttpClient = OkHttpClient.Builder()
            .cookieJar(jar)
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /** mini_login.htm 里解析出的表单参数 */
        val formParams = LinkedHashMap<String, String>()
        var t: String = ""
        var ck: String = ""
        var qrContent: String = ""
        var createdAt: Long = System.currentTimeMillis()
        var finished: Boolean = false
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    /** 会话最长存活 5 分钟，过期自动回收。 */
    private const val SESSION_TTL_MS = 5 * 60 * 1000L

    private fun gcSessions() {
        val now = System.currentTimeMillis()
        val it = sessions.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.createdAt > SESSION_TTL_MS) {
                e.value.client.dispatcher.executorService.shutdown()
                it.remove()
            }
        }
    }

    // ==================== 1. 生成二维码 ====================

    fun generateQrCode(): Pair<Boolean, QrData?> {
        gcSessions()
        val session = Session("s_" + System.currentTimeMillis() + "_" + (1000..9999).random())
        return try {
            // Step 1: 打开登录页，取 loginFormData
            if (!loadLoginParams(session)) {
                Log.w(TAG, "loadLoginParams failed")
                return false to null
            }

            // Step 2: 生成二维码
            val genUrl = HttpUrl.Builder().scheme("https").host("passport.goofish.com").addPathSegments("newlogin/qrcode/generate.do").apply {
                session.formParams.forEach { (k, v) -> addQueryParameter(k, v) }
            }.build()

            val req = Request.Builder()
                .url(genUrl)
                .header("User-Agent", UA)
                .header("Referer", URL_MINI_LOGIN)
                .header("Origin", HOST)
                .header("Accept", "application/json, text/plain, */*")
                .get()
                .build()

            session.client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val json = extractJson(body) ?: run {
                    Log.w(TAG, "generate non-json: ${body.take(200)}")
                    return false to null
                }
                val content = json.optJSONObject("content")
                if (content?.optBoolean("success") != true) {
                    Log.w(TAG, "generate failed: ${body.take(300)}")
                    return false to null
                }
                val data = content.optJSONObject("data") ?: return false to null

                val codeContent = data.optString("codeContent")
                if (codeContent.isBlank()) {
                    Log.w(TAG, "generate missing codeContent")
                    return false to null
                }
                session.qrContent = codeContent
                // query.do 必须回传这两个
                session.t = data.optString("t")
                session.ck = data.optString("ck")
                session.formParams["t"] = session.t
                session.formParams["ck"] = session.ck

                // Step 3: 本地渲染二维码（codeContent 是链接文本，不是图片地址）
                val dataUrl = QrRenderer.toBase64Png(codeContent)
                if (dataUrl.isBlank()) {
                    Log.w(TAG, "qr render failed")
                    return false to null
                }

                sessions[session.id] = session
                Log.i(TAG, "二维码生成成功 session=${session.id}")
                true to QrData(session.id, dataUrl, codeContent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "generateQrCode error", e)
            false to null
        }
    }

    /** 打开 mini_login.htm，解析 window.viewData 里的 loginFormData。 */
    private fun loadLoginParams(session: Session): Boolean {
        return try {
            val url = HttpUrl.Builder().scheme("https").host("passport.goofish.com").addPathSegments("mini_login.htm").apply {
                addQueryParameter("lang", "zh_cn")
                addQueryParameter("appName", "xianyu")
                addQueryParameter("appEntrance", "web")
                addQueryParameter("styleType", "vertical")
                addQueryParameter("bizParams", "")
                addQueryParameter("notLoadSsoView", "false")
                addQueryParameter("notKeepLogin", "false")
                addQueryParameter("isMobile", "false")
                addQueryParameter("qrCodeFirst", "false")
                addQueryParameter("stie", "77")
                addQueryParameter("rnd", Math.random().toString())
            }.build()

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .get()
                .build()

            session.client.newCall(req).execute().use { resp ->
                val html = resp.body?.string().orEmpty()
                val marker = "window.viewData"
                val start = html.indexOf(marker)
                if (start < 0) {
                    Log.w(TAG, "viewData not found (len=${html.length})")
                    return false
                }
                val eq = html.indexOf('=', start)
                if (eq < 0) return false
                val braceStart = html.indexOf('{', eq)
                if (braceStart < 0) return false
                val braceEnd = matchBrace(html, braceStart)
                if (braceEnd <= braceStart) return false

                val jsonStr = html.substring(braceStart, braceEnd + 1)
                val viewData = runCatching { JSONObject(jsonStr) }.getOrNull() ?: run {
                    Log.w(TAG, "viewData parse failed")
                    return false
                }
                val form = viewData.optJSONObject("loginFormData") ?: run {
                    Log.w(TAG, "loginFormData missing")
                    return false
                }
                session.formParams.clear()
                form.keys().forEach { k ->
                    session.formParams[k] = form.optString(k)
                }
                session.formParams["umidTag"] = "SERVER"
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadLoginParams error", e)
            false
        }
    }

    /** 从 open 位置的 '{' 开始，返回与之配对的 '}' 下标（跳过字符串内的括号）。 */
    private fun matchBrace(s: String, open: Int): Int {
        var depth = 0
        var inStr = false
        var esc = false
        var i = open
        while (i < s.length) {
            val c = s[i]
            when {
                esc -> esc = false
                c == '\\' && inStr -> esc = true
                c == '"' -> inStr = !inStr
                !inStr && c == '{' -> depth++
                !inStr && c == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    // ==================== 2. 轮询扫码状态 ====================

    fun checkQrStatus(sessionId: String): CheckResult {
        val session = sessions[sessionId]
            ?: return CheckResult(Status.ERROR, "登录会话已失效，请刷新二维码")
        // 修复：前端每 2 秒轮询，但 finishSuccess 里的网络校验常超过 2 秒。
        // 这里在锁内先判断/占位，避免并发轮询重复走 query.do 导致重复落库。
        if (session.finished) {
            return CheckResult(Status.WAITING, "登录处理中，请稍候…")
        }
        if (System.currentTimeMillis() - session.createdAt > SESSION_TTL_MS) {
            releaseSession(sessionId)
            return CheckResult(Status.EXPIRED, "二维码已过期，请刷新")
        }

        return try {
            val form = FormBody.Builder().apply {
                session.formParams.forEach { (k, v) -> add(k, v) }
            }.build()

            val req = Request.Builder()
                .url(URL_QUERY)
                .header("User-Agent", UA)
                .header("Referer", URL_MINI_LOGIN)
                .header("Origin", HOST)
                .header("Accept", "application/json, text/plain, */*")
                .post(form)
                .build()

            session.client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val json = extractJson(body)
                    ?: return CheckResult(Status.ERROR, "状态响应解析失败")
                val content = json.optJSONObject("content")
                    ?: return CheckResult(Status.ERROR, "状态响应缺少 content")
                val data = content.optJSONObject("data")
                    ?: return CheckResult(Status.ERROR, "状态响应缺少 data")

                when (data.optString("qrCodeStatus").uppercase()) {
                    "NEW" -> CheckResult(Status.WAITING, "等待手机扫码…")
                    "SCANED" -> CheckResult(Status.SCANNED, "已扫码，请在手机上确认")
                    "EXPIRED" -> {
                        releaseSession(sessionId)
                        CheckResult(Status.EXPIRED, "二维码已过期，请刷新")
                    }
                    "CONFIRMED" -> {
                        // 锁内抢占：只有一个轮询能进入落库流程
                        synchronized(session) {
                            if (session.finished) {
                                return CheckResult(Status.WAITING, "登录处理中，请稍候…")
                            }
                            // 风控二次验证：此时还拿不到可用的 unb
                            if (data.optBoolean("iframeRedirect")) {
                                val vUrl = data.optString("iframeRedirectUrl")
                                session.finished = true
                                return CheckResult(
                                    Status.VERIFICATION_REQUIRED,
                                    "触发安全验证，需要完成人脸/滑块验证",
                                    verificationUrl = vUrl
                                )
                            }
                            session.finished = true
                        }
                        finishSuccess(sessionId, session)
                    }
                    else -> {
                        releaseSession(sessionId)
                        CheckResult(Status.EXPIRED, "登录已取消或二维码失效，请刷新")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkQrStatus error", e)
            CheckResult(Status.ERROR, "网络异常: ${e.message}")
        }
    }

    /** CONFIRMED：从会话 CookieJar 里取 goofish 域全部 Cookie 落库。 */
    private fun finishSuccess(sessionId: String, session: Session): CheckResult {
        val cookies = session.jar.snapshot()
        if (cookies.isEmpty()) {
            releaseSession(sessionId)
            return CheckResult(Status.ERROR, "登录成功但未获取到 Cookie")
        }

        // 闲鱼域优先，同名去重
        val goofish = cookies.filter { it.domain.contains("goofish") }
        val others = cookies.filter { !it.domain.contains("goofish") }
        val merged = CookieNormalizer.normalize(
            goofish.joinToString("; ") { "${it.name}=${it.value}" },
            others.joinToString("; ") { "${it.name}=${it.value}" }
        )

        val unb = MtopSigner.parseCookies(merged)["unb"].orEmpty()
        if (unb.isBlank()) {
            releaseSession(sessionId)
            Log.w(TAG, "CONFIRMED 但没有 unb，无法确定账号")
            return CheckResult(
                Status.ERROR,
                "登录未拿到账号标识（unb），可能触发了风控，请重试或改用网页登录"
            )
        }

        // 统一走 AccountManager：校验失败不会激活，也不会编造 ID
        val holder = SessionHolder
        val ctx = holder.context
        if (ctx == null) {
            releaseSession(sessionId)
            return CheckResult(Status.ERROR, "内部错误：上下文未初始化")
        }

        val result = AccountManager.saveLogin(ctx, merged, AccountManager.Source.QR)
        releaseSession(sessionId)

        return if (result.ok) {
            Log.i(TAG, "扫码登录成功 unb=$unb nick=${result.nick}")
            CheckResult(Status.SUCCESS, "登录成功", result.nick, result.userId)
        } else {
            CheckResult(Status.ERROR, result.message, result.nick, result.userId)
        }
    }

    private fun releaseSession(sessionId: String) {
        sessions.remove(sessionId)?.let {
            runCatching { it.client.dispatcher.executorService.shutdown() }
        }
    }

    // ==================== 工具 ====================

    private fun extractJson(raw: String): JSONObject? {
        val s = raw.trim()
        if (s.startsWith("{")) return runCatching { JSONObject(s) }.getOrNull()
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return runCatching { JSONObject(s.substring(start, end + 1)) }.getOrNull()
        }
        return null
    }

    /** 单会话 CookieJar（内存）。 */
    private class MemoryCookieJar : CookieJar {
        private val store = mutableListOf<Cookie>()
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(store) {
                for (c in cookies) {
                    store.removeAll { it.name == c.name && it.domain == c.domain && it.path == c.path }
                    store.add(c)
                }
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val now = System.currentTimeMillis()
            synchronized(store) {
                store.removeAll { it.expiresAt < now }
                return store.filter { it.matches(url) }
            }
        }
        fun snapshot(): List<Cookie> = synchronized(store) { store.toList() }
    }

    /** ApplicationContext 注入点（避免每次调用都要传 Context）。 */
    object SessionHolder {
        var context: android.content.Context? = null
    }

    fun init(context: android.content.Context) {
        SessionHolder.context = context.applicationContext
    }
}

/** URL 参数编码（旧实现直接把 lgToken 拼进 URL，未编码）。 */
internal fun String.urlEncode(): String =
    runCatching { URLEncoder.encode(this, "UTF-8") }.getOrDefault(this)
