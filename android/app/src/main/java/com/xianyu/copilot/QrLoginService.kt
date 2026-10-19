package com.xianyu.copilot

import android.content.Context
import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * 淘宝/闲鱼扫码登录核心服务。
 * - generateQrCode(): 生成二维码
 * - checkQrStatus():  轮询扫码状态，成功则自动入库
 */
object QrLoginService {

    private const val TAG = "QrLoginService"

    private const val URL_GEN = "https://qrlogin.taobao.com/qrcodelogin/generateQRCode4Login.do"
    private const val URL_CHECK = "https://qrlogin.taobao.com/qrcodelogin/qrcodeLoginCheck.do"
    private const val REFERER = "https://login.taobao.com/"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    private val memCookieJar = object : CookieJar {
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
        fun clear() = synchronized(store) { store.clear() }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(memCookieJar)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    data class QrData(
        val lgToken: String,
        val qrImgUrl: String,
        val qrCodeText: String
    )

    enum class Status { WAITING, SCANNED, EXPIRED, SUCCESS, ERROR }

    data class CheckResult(
        val status: Status,
        val message: String,
        val nick: String? = null,
        val userId: String? = null
    )

    // ==================== 1. 生成二维码 ====================

    fun generateQrCode(): Pair<Boolean, QrData?> {
        return try {
            memCookieJar.clear()
            val ts = System.currentTimeMillis()
            val url = "$URL_GEN?from=xianyu&appkey=34839810&_ksTS=$ts&callback=cb"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .header("Accept", "application/json, text/plain, */*")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "generateQrCode http ${resp.code}")
                    return false to null
                }
                val body = resp.body?.string().orEmpty()
                val json = extractJson(body) ?: run {
                    Log.w(TAG, "generateQrCode non-json: $body")
                    return false to null
                }

                val content = json.optJSONObject("content") ?: json

                val lgToken = content.optString("lgToken").ifBlank {
                    content.optString("lgtoken")
                }
                val rawUrl = content.optString("url")
                if (lgToken.isBlank() || rawUrl.isBlank()) {
                    Log.w(TAG, "generateQrCode missing fields: $body")
                    return false to null
                }

                val imgUrl = when {
                    rawUrl.startsWith("//") -> "https:$rawUrl"
                    rawUrl.startsWith("http") -> rawUrl
                    else -> "https://$rawUrl"
                }
                val qrCodeText =
                    "https://login.m.taobao.com/qrcodeCheck.htm?lgToken=$lgToken&tbScanOpenType=Notification"

                true to QrData(lgToken, imgUrl, qrCodeText)
            }
        } catch (e: Exception) {
            Log.e(TAG, "generateQrCode error", e)
            false to null
        }
    }

    // ==================== 2. 轮询扫码状态 ====================

    fun checkQrStatus(context: Context, lgToken: String): CheckResult {
        return try {
            val ts = System.currentTimeMillis()
            val url = "$URL_CHECK?lgToken=$lgToken&defaulturl=&_ksTS=$ts&callback=cb"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .header("Accept", "application/json, text/plain, */*")
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return CheckResult(Status.ERROR, "状态查询失败 HTTP ${resp.code}")
                }
                val body = resp.body?.string().orEmpty()
                val json = extractJson(body) ?: run {
                    Log.w(TAG, "checkQrStatus non-json: $body")
                    return CheckResult(Status.ERROR, "无法解析状态响应")
                }
                val content = json.optJSONObject("content") ?: json
                val code = content.optString("code")

                when (code) {
                    "10000" -> CheckResult(Status.WAITING, "等待手机扫码...")
                    "10001" -> CheckResult(Status.SCANNED, "已扫码，请在手机上点击确认...")
                    "10004" -> CheckResult(Status.EXPIRED, "二维码已过期，请刷新")
                    "10006" -> handleSuccess(context, content)
                    else -> CheckResult(Status.ERROR, "未知状态码: $code")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkQrStatus error", e)
            CheckResult(Status.ERROR, "网络异常: ${e.message}")
        }
    }

    // ==================== 3. 登录成功：抓 Cookie 并入库 ====================

    private fun handleSuccess(context: Context, content: JSONObject): CheckResult {
        val redirectUrl = when {
            content.optString("url").isNotBlank() -> content.optString("url")
            content.optString("redirectUrl").isNotBlank() -> content.optString("redirectUrl")
            content.optString("defaulturl").isNotBlank() -> content.optString("defaulturl")
            else -> "https://login.taobao.com/"
        }.let {
            when {
                it.startsWith("//") -> "https:$it"
                it.startsWith("http") -> it
                else -> "https://$it"
            }
        }

        try {
            val followReq = Request.Builder()
                .url(redirectUrl)
                .header("User-Agent", UA)
                .header("Referer", REFERER)
                .get()
                .build()
            client.newCall(followReq).execute().use { r ->
                r.body?.bytes()
            }
        } catch (e: Exception) {
            Log.w(TAG, "follow redirect error (cookie may be partial)", e)
        }

        val cookies = memCookieJar.snapshot()
        if (cookies.isEmpty()) {
            return CheckResult(Status.ERROR, "登录成功但未获取到 Cookie")
        }

        val cookieStr = cookies.joinToString("; ") { "${it.name}=${it.value}" }
        val map = cookies.associate { it.name to it.value }

        val unb = firstNonBlank(
            map["unb"],
            map["UREFUSERID"],
            decodeCookieValue(map["tracknick"])
        )
        var nick = firstNonBlank(
            decodeCookieValue(map["tracknick"]),
            decodeCookieValue(map["_nk_"]),
            decodeCookieValue(map["nick"]),
            unb
        )

        val (ok, testNick) = MtopClient.testCookie(cookieStr)
        if (ok && testNick.isNotBlank()) {
            nick = testNick
        }

        val effectiveUid = if (unb.isNotBlank()) unb else "xianyu_" + System.currentTimeMillis().toString().takeLast(8)

        return try {
            CardDatabase.get(context).addOrUpdateAccount(
                userId = effectiveUid,
                nick = nick.ifBlank { effectiveUid },
                avatar = "",
                cookie = cookieStr,
                activate = true
            )
            Log.i(TAG, "login success, user=$effectiveUid nick=$nick")
            CheckResult(Status.SUCCESS, "扫码成功，已同步账号: ${nick.ifBlank { effectiveUid }}", nick, effectiveUid)
        } catch (e: Exception) {
            Log.e(TAG, "save account error", e)
            CheckResult(Status.ERROR, "账号保存失败: ${e.message}")
        }
    }

    private fun extractJson(raw: String): JSONObject? {
        val s = raw.trim()
        if (s.startsWith("{")) {
            return runCatching { JSONObject(s) }.getOrNull()
        }
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        if (start >= 0 && end > start) {
            return runCatching { JSONObject(s.substring(start, end + 1)) }.getOrNull()
        }
        return null
    }

    private fun decodeCookieValue(v: String?): String {
        if (v.isNullOrBlank()) return ""
        return runCatching { URLDecoder.decode(v, "UTF-8") }.getOrDefault(v)
    }

    private fun firstNonBlank(vararg v: String?): String {
        for (s in v) if (!s.isNullOrBlank()) return s
        return ""
    }
}
