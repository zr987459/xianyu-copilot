package com.xianyu.copilot

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 闲鱼 Mtop 协议客户端（OkHttp3）。
 * 所有公开方法均不抛异常，统一返回 Pair<Boolean, String> 或空列表。
 */
object MtopClient {

    private const val TAG = "MtopClient"
    private const val BASE_URL = "https://h5api.m.goofish.com/h5/"
    private const val APP_KEY = MtopSigner.DEFAULT_APP_KEY
    private const val ORIGIN = "https://www.goofish.com"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 待发货订单数据模型。 */
    data class SoldOrder(
        val orderId: String,
        val title: String,
        val sku: String,
        val buyerNick: String,
        val status: String,
        val quantity: Int = 1
    )

    // ---------------- 核心请求 ----------------

    private data class ApiResult(val success: Boolean, val message: String, val json: JSONObject?)

    /**
     * mtop 令牌过期标志（两种拼写官方都存在）。
     */
    private val TOKEN_EXPIRED_MARKERS = arrayOf(
        "FAIL_SYS_TOKEN_EXOIRED",
        "FAIL_SYS_TOKEN_EXPIRED",
        "FAIL_SYS_TOKEN_EMPTY",
        "令牌过期",
        "令牌为空"
    )

    private fun isTokenExpired(ret: String): Boolean =
        TOKEN_EXPIRED_MARKERS.any { ret.contains(it) }

    /**
     * 从响应 Set-Cookie 中提取新令牌并合并回 Cookie 字符串。
     *
     * 按 Hark 审查意见：
     *  - _m_h5_tk 与 _m_h5_tk_enc 必须**成对**更新，只换其中一个会校验失败；
     *  - 精确匹配两个字段名，不做前缀模糊匹配；
     *  - 跳过 Max-Age=0 / 已过期的删除指令，那不是新令牌。
     */
    private fun mergeSetCookie(cookie: String, setCookies: List<String>): String {
        if (setCookies.isEmpty()) return cookie
        val jar = MtopSigner.parseCookies(cookie)
        var changed = false
        for (raw in setCookies) {
            // 删除指令：Max-Age=0 或已过期，忽略
            val lower = raw.lowercase()
            if (lower.contains("max-age=0")) continue

            val first = raw.substringBefore(';').trim()
            val idx = first.indexOf('=')
            if (idx <= 0) continue
            val name = first.substring(0, idx).trim()
            val value = first.substring(idx + 1).trim()
            if (value.isEmpty()) continue

            if (name == "_m_h5_tk" || name == "_m_h5_tk_enc") {
                if (jar[name] != value) {
                    jar[name] = value
                    changed = true
                }
            }
        }
        return if (changed) MtopSigner.rebuildCookie(jar) else cookie
    }

    /**
     * 同一账号刷新令牌的单飞锁。
     * 修复：并发两处同时刷新时，后写的整串 cookie 会把别人刚更新的字段冲掉。
     */
    private val refreshLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun lockFor(unb: String): Any =
        refreshLocks.getOrPut(unb.ifBlank { "_anon" }) { Any() }

    /**
     * 执行一次 Mtop 调用（带令牌自愈）。
     * 遇到 FAIL_SYS_TOKEN_EMPTY / FAIL_SYS_TOKEN_EXPIRED 时，读取响应 Set-Cookie 的
     * 新 _m_h5_tk 合并回 Cookie 后重签重试一次，并通过 [onCookieRefreshed] 回写数据库。
     *
     * 按 Hark 审查意见：
     *  - 首次 EMPTY 属于正常握手，不是错误；
     *  - 按账号 single-flight，拿锁后重读一次避免重复刷新；
     *  - 重试上限 1 次，不递归。
     */
    private fun callMtop(
        cookie: String,
        api: String,
        version: String,
        dataObj: JSONObject,
        extraQuery: Map<String, String> = emptyMap(),
        onCookieRefreshed: ((String) -> Unit)? = null
    ): ApiResult {
        val first = callMtopOnce(cookie, api, version, dataObj, extraQuery)
        if (!first.tokenExpired) return ApiResult(first.success, first.message, first.json)

        // 令牌过期 / 为空：在账号锁内完成刷新，避免并发互盖
        val unb = MtopSigner.parseCookies(cookie)["unb"].orEmpty()
        synchronized(lockFor(unb)) {
            // 拿锁后重新校一下：若别人已刷新，直接用新令牌重签即可
            val baseCookie = currentCookieFor(unb) ?: cookie
            val oldToken = MtopSigner.parseCookies(baseCookie)["_m_h5_tk"]

            // 先尝试用最新 cookie 直接重签（可能是别人刚刷新的）
            if (baseCookie != cookie) {
                val retry = callMtopOnce(baseCookie, api, version, dataObj, extraQuery)
                if (!retry.tokenExpired && retry.success) {
                    onCookieRefreshed?.invoke(baseCookie)
                    return ApiResult(retry.success, retry.message, retry.json)
                }
            }

            val merged = mergeSetCookie(baseCookie, first.setCookies)
            val newToken = MtopSigner.parseCookies(merged)["_m_h5_tk"]
            if (merged == baseCookie || newToken == oldToken) {
                // 没拿到新令牌：多半是登录态失效或被风控，交给登录模块处理
                Log.w(TAG, "callMtop($api) 令牌未更新，登录态可能已失效: ${first.message}")
                return ApiResult(first.success, first.message, first.json)
            }

            Log.i(TAG, "callMtop($api) 令牌已刷新，重签重试一次")
            onCookieRefreshed?.invoke(merged)

            val retry = callMtopOnce(merged, api, version, dataObj, extraQuery)
            if (retry.tokenExpired) {
                Log.w(TAG, "callMtop($api) 刷新后仍过期，停止重试")
            }
            return ApiResult(retry.success, retry.message, retry.json)
        }
    }

    /** 从数据库读当前账号的最新 cookie（供并发刷新时重读）。 */
    private fun currentCookieFor(unb: String): String? {
        if (unb.isBlank()) return null
        return runCatching {
            val ctx = QrLoginService.SessionHolder.context ?: return null
            CardDatabase.get(ctx).getActiveCookie().takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private data class RawResult(
        val success: Boolean,
        val message: String,
        val json: JSONObject?,
        val tokenExpired: Boolean = false,
        val setCookies: List<String> = emptyList()
    )

    private fun callMtopOnce(
        cookie: String,
        api: String,
        version: String,
        dataObj: JSONObject,
        extraQuery: Map<String, String> = emptyMap()
    ): RawResult {
        return try {
            val dataStr = dataObj.toString()
            val t = MtopSigner.nowT()
            val token = MtopSigner.extractToken(cookie)
            val sign = MtopSigner.generateSign(token, t, APP_KEY, dataStr)

            // 参数按签名一致性拼装；sign/t/data 均需与实际一致
            val urlBuilder = StringBuilder(BASE_URL)
                .append(api).append('/').append(version).append('/')
                .append("?jsv=2.7.2")
                .append("&appKey=").append(APP_KEY)
                .append("&t=").append(t)
                .append("&sign=").append(sign)
                .append("&v=").append(version)
                .append("&type=originaljson")
                .append("&accountSite=xianyu")
                .append("&dataType=json")
                .append("&timeout=20000")
                .append("&api=").append(api)
                .append("&sessionOption=AutoLoginOnly")
            extraQuery.forEach { (k, v) ->
                urlBuilder.append('&').append(k).append('=').append(encode(v))
            }

            val body = dataStr.toRequestBody(JSON_TYPE)
            val request = Request.Builder()
                .url(urlBuilder.toString())
                .header("Cookie", cookie)
                .header("User-Agent", UA)
                .header("Origin", ORIGIN)
                .header("Referer", "$ORIGIN/")
                .header("Accept", "application/json")
                .post(body)
                .build()

            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                // 先抓 Set-Cookie：令牌过期时新 _m_h5_tk 就挂在这里
                val setCookies = resp.headers.values("Set-Cookie")
                if (!resp.isSuccessful) {
                    return RawResult(false, "HTTP ${resp.code}", null, setCookies = setCookies)
                }
                val json = runCatching { JSONObject(raw) }.getOrNull()
                    ?: return RawResult(false, "响应解析失败", null, setCookies = setCookies)
                val retArr: JSONArray? = json.optJSONArray("ret")
                val ret0 = retArr?.optString(0).orEmpty()
                // 成功标志：ret[0] 以 SUCCESS 结尾（如 "SUCCESS::调用成功" / "SUCCESS::SUCCESS"）
                val ok = ret0.contains("SUCCESS")
                val msg = if (ok) "成功" else ret0.ifEmpty {
                    json.optJSONObject("data")?.optString("msg").orEmpty().ifEmpty { "失败" }
                }
                RawResult(
                    success = ok,
                    message = msg,
                    json = json,
                    tokenExpired = isTokenExpired(ret0),
                    setCookies = setCookies
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "callMtopOnce($api) error", e)
            RawResult(false, "请求异常: ${e.message ?: e.javaClass.simpleName}", null)
        }
    }

    private fun encode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    // ---------------- 业务接口 ----------------

    /**
     * 官方"无需物流"发货。
     * @return Pair(是否成功, 提示信息)
     */
    fun confirmDummyShipping(
        cookie: String,
        orderId: String,
        tradeText: String,
        onCookieRefreshed: ((String) -> Unit)? = null
    ): Pair<Boolean, String> {
        if (cookie.isBlank()) return false to "Cookie 为空"
        if (orderId.isBlank()) return false to "订单号为空"
        return try {
            val data = JSONObject().apply {
                put("orderId", orderId)
                put("tradeText", tradeText)
                put("picList", JSONArray())
                put("newUnconsign", true)
            }
            val r = callMtop(
                cookie = cookie,
                api = "mtop.taobao.idle.logistic.consign.dummy",
                version = "1.0",
                dataObj = data,
                onCookieRefreshed = onCookieRefreshed
            )
            r.success to r.message
        } catch (e: Exception) {
            Log.e(TAG, "confirmDummyShipping error", e)
            false to "异常: ${e.message}"
        }
    }

    /**
     * 校验 Cookie 有效性：拉一次「当前登录用户」接口。
     *
     * 用 mtop.taobao.idlemessage.pc.loginuser.get，这是闲鱼网页端真实会调用的接口
     * （参考 xianyu-auto-reply 的 login_renew_task），比未经验证的
     * mtop.taobao.idle.user.info.get 可靠得多。
     *
     * @return Pair(是否有效, 昵称或错误说明)
     */
    fun testCookie(cookie: String): Pair<Boolean, String> {
        val r = testCookieDetailed(cookie)
        return r.valid to (r.nick.ifBlank { r.message })
    }

    data class CookieCheck(
        val valid: Boolean,
        val nick: String = "",
        val unb: String = "",
        /** 令牌自愈后的最新 Cookie（可能比传入的更新） */
        val cookie: String = "",
        val message: String = ""
    )

    fun testCookieDetailed(cookie: String): CookieCheck {
        if (cookie.isBlank()) return CookieCheck(false, message = "Cookie 为空")
        return try {
            var refreshed = cookie
            val r = callMtop(
                cookie = cookie,
                api = "mtop.taobao.idlemessage.pc.loginuser.get",
                version = "1.0",
                dataObj = JSONObject(),
                onCookieRefreshed = { refreshed = it }
            )
            if (!r.success) {
                return CookieCheck(false, cookie = refreshed, message = r.message)
            }
            val data = r.json?.optJSONObject("data")
            val nick = data?.let {
                firstNonBlank(it, "fishNick", "nick", "userNick", "displayName")
            }.orEmpty()
            val unb = data?.let { firstNonBlank(it, "unb", "userId", "uid") }.orEmpty()
                .ifBlank { MtopSigner.parseCookies(refreshed)["unb"].orEmpty() }
            CookieCheck(
                valid = true,
                nick = nick,
                unb = unb,
                cookie = refreshed,
                message = "成功"
            )
        } catch (e: Exception) {
            Log.e(TAG, "testCookieDetailed error", e)
            CookieCheck(false, message = "异常: ${e.message}")
        }
    }

    /**
     * 查询卖家已卖出订单，过滤出待发货等状态。
     * 主接口失败时回退备用接口。
     */
    fun querySoldOrders(
        cookie: String,
        onCookieRefreshed: ((String) -> Unit)? = null
    ): List<SoldOrder> {
        if (cookie.isBlank()) return emptyList()
        val apis = listOf(
            "mtop.taobao.idle.sold.query" to "1.0",
            "mtop.idle.order.sold.list" to "1.0"
        )
        for ((api, ver) in apis) {
            try {
                val data = JSONObject().apply {
                    put("pageNumber", 1)
                    put("pageSize", 20)
                    put("orderStatus", "WAIT_SELLER_SEND_GOODS")
                }
                val r = callMtop(cookie, api, ver, data, onCookieRefreshed = onCookieRefreshed)
                if (!r.success) continue
                val list = parseOrders(r.json)
                if (list.isNotEmpty() || r.success) return list
            } catch (e: Exception) {
                Log.e(TAG, "querySoldOrders($api) error", e)
            }
        }
        return emptyList()
    }

    /** 兼容不同返回结构的订单解析。 */
    private fun parseOrders(root: JSONObject?): List<SoldOrder> {
        if (root == null) return emptyList()
        val data = root.optJSONObject("data") ?: return emptyList()

        val arr: JSONArray? = data.optJSONArray("orderList")
            ?: data.optJSONArray("list")
            ?: data.optJSONObject("data")?.optJSONArray("orderList")
            ?: data.optJSONObject("result")?.optJSONArray("list")
        if (arr == null) return emptyList()

        val out = ArrayList<SoldOrder>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val orderId = firstNonBlank(
                o, "orderId", "bizOrderId", "orderStr", "id"
            ) ?: continue
            val title = firstNonBlank(
                o, "itemTitle", "title", "auctionTitle"
            ) ?: ""
            val sku = firstNonBlank(o, "skuDesc", "sku", "propertyValue") ?: ""
            val buyer = firstNonBlank(o, "buyerNick", "buyerName", "buyer") ?: ""
            val status = firstNonBlank(o, "orderStatus", "status", "orderStatusName") ?: ""
            val qtyStr = firstNonBlank(o, "buyAmount", "buyQuantity", "itemNum", "quantity", "count")
            val qty = qtyStr?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            out.add(SoldOrder(orderId, title, sku, buyer, status, qty))
        }
        return out
    }

    private fun firstNonBlank(o: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = o.optString(k)
            if (v.isNotBlank() && v != "null") return v
        }
        return null
    }

    /**
     * 闲鱼免浏览器上架接口。
     * @return Pair(是否成功, 商品 ID 或错误信息)
     */
    fun publishItem(
        cookie: String,
        title: String,
        price: String,
        desc: String,
        categoryId: String
    ): Pair<Boolean, String> {
        if (cookie.isBlank()) return false to "Cookie 为空"
        if (title.isBlank()) return false to "标题为空"
        return try {
            val data = JSONObject().apply {
                put("title", title)
                put("desc", desc)
                put("price", price)
                put("quantity", 1)
                put("categoryId", categoryId)
                put("itemType", "b")
                put("imageUrls", JSONArray())
                put("deliveryWay", 1) // 无需物流
            }
            val r = callMtop(
                cookie = cookie,
                api = "mtop.taobao.idle.item.publish",
                version = "1.0",
                dataObj = data
            )
            if (!r.success) return false to r.message
            val itemId = r.json?.optJSONObject("data")
                ?.let { d -> firstNonBlank(d, "itemId", "id", "item_id") }
                ?: "发布成功"
            true to itemId
        } catch (e: Exception) {
            Log.e(TAG, "publishItem error", e)
            false to "异常: ${e.message}"
        }
    }
}
