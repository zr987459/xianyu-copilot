package com.xianyu.copilot

import android.util.Log
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
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

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // 强制使用 HTTP/1.1，避免阿里 mtop 网关 / 手机 VPN 代理下 HTTP/2 握手 RST_STREAM 报错 connection closed
            .protocols(listOf(Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** 待发货订单数据模型（增强买家信息）。 */
    data class SoldOrder(
        val orderId: String,
        val title: String,
        val sku: String,
        val buyerNick: String,
        val status: String,
        val quantity: Int = 1,
        val price: String = "",
        val buyerCity: String = "",
        val buyerMessage: String = "",
        val createTime: String = ""
    )

    /** 在售商品统一数据模型。 */
    data class OnSaleItem(
        val id: String,
        val title: String,
        val price: String,
        val imageUrl: String,
        val status: String,
        val detailUrl: String,
        val source: String,
        val onSale: Boolean = true
    )

    /** 上传成功的图片信息。 */
    data class UploadedImage(
        val url: String,
        val widthSize: Int = 800,
        val heightSize: Int = 800
    )

    /** 在售商品查询结果封装。 */
    data class OnSaleResult(
        val ok: Boolean,
        val items: List<OnSaleItem>,
        val source: String,
        val error: String = ""
    )

    // ---------------- 核心请求 ----------------

    private data class ApiResult(
        val success: Boolean,
        val message: String,
        val json: JSONObject?,
        val retCode: String = ""
    )

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
     * 登录态彻底失效（令牌刷新也救不回来），需要用户重新登录。
     * 与 isTokenExpired 区分：令牌过期是「可自愈」的，这个是「必须重新登录」。
     */
    private val SESSION_DEAD_MARKERS = arrayOf(
        "FAIL_SYS_SESSION_EXPIRED",
        "FAIL_SYS_SESSION_EXOIRED",
        "FAIL_SYS_LOGIN",
        "SESSION_EXPIRED",
        "请重新登录",
        "未登录"
    )

    /**
     * 是否真的登录态失效。
     *
     * 注意：FAIL_SYS_USER_VALIDATE 属于风控/滑块，不是掉登录，绝不能判失效，
     * 否则用户会看到「需重新登录」而实际只需稍后重试。
     */
    fun isSessionDead(ret: String): Boolean {
        if (ret.isBlank()) return false
        if (isRiskControl(ret)) return false
        return SESSION_DEAD_MARKERS.any { ret.contains(it, ignoreCase = true) }
    }

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
        onCookieRefreshed: ((String) -> Unit)? = null,
        onResult: ((Boolean, String) -> Unit)? = null,
        allowStoredBase: Boolean = true,
        origin: String = ORIGIN,
        referer: String = "$ORIGIN/",
        extraHeaders: Map<String, String> = emptyMap()
    ): ApiResult {
        val unb = MtopSigner.parseCookies(cookie)["unb"].orEmpty()

        fun finish(success: Boolean, message: String, json: JSONObject?, retCode: String): ApiResult {
            onResult?.invoke(success, retCode.ifBlank { message })
            return ApiResult(success, message, json, retCode)
        }

        val first = callMtopOnce(cookie, api, version, dataObj, extraQuery, origin, referer, extraHeaders)
        if (!first.tokenExpired) {
            return finish(first.success, first.message, first.json, first.retCode)
        }

        synchronized(lockFor(unb)) {
            val baseCookie = (if (allowStoredBase) currentCookieFor(unb) else null) ?: cookie
            val oldToken = MtopSigner.parseCookies(baseCookie)["_m_h5_tk"]

            if (baseCookie != cookie) {
                val retry = callMtopOnce(baseCookie, api, version, dataObj, extraQuery, origin, referer, extraHeaders)
                if (!retry.tokenExpired && retry.success) {
                    onCookieRefreshed?.invoke(baseCookie)
                    return finish(retry.success, retry.message, retry.json, retry.retCode)
                }
            }

            val merged = mergeSetCookie(baseCookie, first.setCookies)
            val newToken = MtopSigner.parseCookies(merged)["_m_h5_tk"]
            if (merged == baseCookie || newToken == oldToken) {
                // 令牌已过期且拿不到新令牌 ⇒ 登录态实际已失效，映射成明确失效码，
                // 否则上层只看 FAIL_SYS_TOKEN_EXOIRED 会误以为是可自愈的普通错误。
                Log.w(TAG, "callMtop($api) 令牌未能刷新，判定登录态失效: ${first.retCode}")
                return finish(false, first.message, first.json, "FAIL_SYS_SESSION_EXPIRED")
            }

            Log.i(TAG, "callMtop($api) 令牌已刷新，重签重试一次")
            onCookieRefreshed?.invoke(merged)

            val retry = callMtopOnce(merged, api, version, dataObj, extraQuery, origin, referer, extraHeaders)
            if (retry.tokenExpired) {
                Log.w(TAG, "callMtop($api) 刷新后仍过期，判定登录态失效")
                return finish(false, retry.message, retry.json, "FAIL_SYS_SESSION_EXPIRED")
            }
            return finish(retry.success, retry.message, retry.json, retry.retCode)
        }
    }

    /**
     * 按 unb 读该账号自己的最新 cookie。
     *
     * 修复串号：之前直接返回 getActiveCookie()，登录第二个账号时会把
     * 已激活账号 A 的 cookie 当成 B 的基准。现在只按 user_id 查，
     * 查不到（登录尚未入库）就返回 null，绝不回退到 active 账号。
     */
    private fun currentCookieFor(unb: String): String? {
        if (unb.isBlank()) return null
        return runCatching {
            val ctx = QrLoginService.SessionHolder.context ?: return null
            CardDatabase.get(ctx).getAccountCookie(unb).takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private data class RawResult(
        val success: Boolean,
        val message: String,
        val json: JSONObject?,
        val tokenExpired: Boolean = false,
        val setCookies: List<String> = emptyList(),
        val retCode: String = ""
    )

    private fun callMtopOnce(
        cookie: String,
        api: String,
        version: String,
        dataObj: JSONObject,
        extraQuery: Map<String, String> = emptyMap(),
        origin: String = ORIGIN,
        referer: String = "$ORIGIN/",
        extraHeaders: Map<String, String> = emptyMap()
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

            // 修复 HTTP 415：闲鱼 Mtop H5 网关仅支持 application/x-www-form-urlencoded，
            // 必须通过表单提交 data 参数。原先使用 application/json 导致被网关直接 415 拒收。
            val body = FormBody.Builder()
                .add("data", dataStr)
                .build()
            val reqB = Request.Builder()
                .url(urlBuilder.toString())
                .header("Cookie", cookie)
                .header("User-Agent", UA)
                .header("Origin", origin)
                .header("Referer", referer)
                .header("Accept", "application/json")
                .header("Connection", "keep-alive")
                .header("Sec-Fetch-Site", "same-site")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
            extraHeaders.forEach { (k, v) -> reqB.header(k, v) }
            val request = reqB.post(body).build()

            client.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                // 先抓 Set-Cookie：令牌过期时新 _m_h5_tk 就挂在这里
                val setCookies = resp.headers.values("Set-Cookie")
                if (!resp.isSuccessful) {
                    return RawResult(
                        false, "HTTP ${resp.code}", null,
                        setCookies = setCookies, retCode = "HTTP_${resp.code}"
                    )
                }
                val json = runCatching { JSONObject(raw) }.getOrNull()
                    ?: return RawResult(
                        false, "响应解析失败", null,
                        setCookies = setCookies, retCode = "PARSE_ERROR"
                    )
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
                    setCookies = setCookies,
                    retCode = ret0
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
        onCookieRefreshed: ((String) -> Unit)? = null,
        onResult: ((Boolean, String) -> Unit)? = null
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
                onCookieRefreshed = onCookieRefreshed,
                onResult = onResult
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
                extraQuery = mapOf(
                    "spm_cnt" to "a21ybx.im.0.0",
                    "spm_pre" to "a21ybx.item.want.1.12523da6waCtUp",
                    "log_id" to "12523da6waCtUp"
                ),
                onCookieRefreshed = { refreshed = it },
                allowStoredBase = false
            )
            if (!r.success) {
                // 用 retCode 优先，才能识别出「令牌刷新失败」被映射的会话失效码
                return CookieCheck(false, cookie = refreshed, message = r.retCode.ifBlank { r.message })
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
     * 查询卖家已卖出待发货订单。
     * 官方标准卖家后台接口（mtop.taobao.idle.trade.merchant.sold.get）优先，
     * 失败回退 H5 接口，且绝不把候选轮询失败误报为登录失效。
     */
    /** 上次成功的订单通道，优先复用，避免每轮扫描都试 3 个接口触发风控。 */
    @Volatile
    private var lastSoldChannel: String = "seller"

    fun querySoldOrders(
        cookie: String,
        onCookieRefreshed: ((String) -> Unit)? = null,
        onResult: ((Boolean, String) -> Unit)? = null
    ): List<SoldOrder> {
        if (cookie.isBlank()) return emptyList()

        // 1) 卖家平台已售订单标准接口（seller.goofish.com 官方接口）
        val sellerData = JSONObject().apply {
            put("pageNumber", 1)
            put("rowsPerPage", 20)
            put("orderIds", "")
            put("queryCode", "NOT_SHIP")
            put("orderSearchParam", "{}")
        }
        val rSeller = callMtop(
            cookie = cookie,
            api = "mtop.taobao.idle.trade.merchant.sold.get",
            version = "1.0",
            dataObj = sellerData,
            extraQuery = mapOf(
                "spm_cnt" to "a21107h.42826273.0.0",
                "valueType" to "string"
            ),
            origin = "https://seller.goofish.com",
            referer = "https://seller.goofish.com/",
            extraHeaders = mapOf("idle_site_biz_code" to "COMMONPRO"),
            onCookieRefreshed = onCookieRefreshed
        )
        if (rSeller.success) {
            val list = parseOrders(rSeller.json)
            // 成功但结构不认识：打日志并继续回退，避免静默不发货
            if (list.isEmpty() && !hasOrderArray(rSeller.json)) {
                Log.w(TAG, "querySoldOrders 卖家接口成功但未识别出订单结构，回退备用通道")
            } else {
                lastSoldChannel = "seller"
                onResult?.invoke(true, "成功")
                return list
            }
        }

        // 只有风控才短路。会话类错误必须继续走 H5 通道：
        // 这个卖家接口尚未用真实账号验证，若它对普通卖家恒报会话错误，
        // 直接短路会造成「界面显示在线，却一单都不发」这种更隐蔽的问题。
        val sellerErr = rSeller.retCode.ifBlank { rSeller.message }
        if (isRiskControl(sellerErr)) {
            Log.w(TAG, "querySoldOrders 卖家接口触碰风控，短路返回: $sellerErr")
            onResult?.invoke(false, sellerErr)
            return emptyList()
        }
        if (isSessionDead(sellerErr)) {
            Log.w(TAG, "querySoldOrders 卖家接口报会话失效，继续尝试 H5 通道")
        }

        // 2) 备用：移动端 H5 候选接口
        // 若上次成功通道是某个 H5 接口，优先把它排到最前，减少每轮请求数
        val allApis = listOf(
            "mtop.taobao.idle.sold.query" to "1.0",
            "mtop.idle.order.sold.list" to "1.0"
        )
        val apis = allApis.sortedByDescending { it.first == lastSoldChannel }
        var lastErr = sellerErr
        var sessionDeadErr: String? = null
        for ((api, ver) in apis) {
            try {
                val data = JSONObject().apply {
                    put("pageNumber", 1)
                    put("pageSize", 20)
                    put("orderStatus", "WAIT_SELLER_SEND_GOODS")
                }
                val r = callMtop(
                    cookie, api, ver, data,
                    onCookieRefreshed = onCookieRefreshed
                )
                if (r.success) {
                    val list = parseOrders(r.json)
                    lastSoldChannel = api
                    onResult?.invoke(true, "成功")
                    return list
                } else {
                    val e = r.retCode.ifBlank { r.message }
                    // 风控立即短路
                    if (isRiskControl(e)) {
                        onResult?.invoke(false, e)
                        return emptyList()
                    }
                    // 失效码要保住，不能被后面的普通错误覆盖掉
                    if (isSessionDead(e)) {
                        lastErr = e
                        sessionDeadErr = e
                        continue
                    }
                    if (sessionDeadErr == null) lastErr = e
                }
            } catch (e: Exception) {
                lastErr = e.message ?: "异常"
                Log.e(TAG, "querySoldOrders($api) error", e)
            }
        }

        // 所有通道都失败才回调；若其中出现过会话失效，优先上报失效码
        onResult?.invoke(false, sessionDeadErr ?: lastErr)
        return emptyList()
    }

    /** 判断响应里是否存在可识别的订单数组（用于区分「真的没订单」与「结构不认识」）。 */
    private fun hasOrderArray(root: JSONObject?): Boolean {
        val data = root?.optJSONObject("data") ?: return false
        return data.optJSONArray("orderList") != null ||
            data.optJSONArray("list") != null ||
            data.optJSONArray("itemSearchResponseList") != null ||
            data.optJSONObject("data")?.optJSONArray("orderList") != null ||
            data.optJSONObject("result")?.optJSONArray("list") != null
    }

    /** 兼容不同返回结构的订单解析（包含 PC 卖家结构与 H5 结构）。 */
    private fun parseOrders(root: JSONObject?): List<SoldOrder> {
        if (root == null) return emptyList()
        val data = root.optJSONObject("data") ?: return emptyList()

        val arr: JSONArray? = data.optJSONArray("orderList")
            ?: data.optJSONArray("list")
            ?: data.optJSONArray("itemSearchResponseList")
            ?: data.optJSONObject("data")?.optJSONArray("orderList")
            ?: data.optJSONObject("result")?.optJSONArray("list")
        if (arr == null) return emptyList()

        val out = ArrayList<SoldOrder>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val common = o.optJSONObject("commonData")
            val buyerVo = o.optJSONObject("buyerInfoVO")
            val priceVo = o.optJSONObject("priceVO")

            val orderId = firstNonBlank(o, "orderId", "bizOrderId", "orderStr", "id")
                ?: (if (common != null) firstNonBlank(common, "orderId", "id") else null)
                ?: continue

            val title = firstNonBlank(o, "itemTitle", "title", "auctionTitle")
                ?: (if (common != null) firstNonBlank(common, "itemTitle", "title") else null)
                ?: ""

            val sku = firstNonBlank(o, "skuDesc", "sku", "propertyValue") ?: ""

            val buyer = firstNonBlank(o, "buyerNick", "buyerName", "buyer")
                ?: (if (buyerVo != null) firstNonBlank(buyerVo, "userNick", "nick", "name", "buyerNick") else null)
                ?: ""

            val status = firstNonBlank(o, "orderStatus", "status", "orderStatusName")
                ?: (if (common != null) firstNonBlank(common, "orderStatus", "status") else null)
                ?: "待发货"

            val qtyStr = firstNonBlank(o, "buyAmount", "buyQuantity", "itemNum", "quantity", "count")
                ?: (if (priceVo != null) firstNonBlank(priceVo, "buyNum", "count", "quantity") else null)
            val qty = qtyStr?.toIntOrNull()?.coerceAtLeast(1) ?: 1

            val price = firstNonBlank(o, "actualPayFee", "totalPrice", "orderPrice", "price", "amount")
                ?: (if (priceVo != null) firstNonBlank(priceVo, "totalPrice", "price") else null)
                ?: ""

            val city = firstNonBlank(o, "receiverCity", "buyerCity", "city", "provCity", "buyerLocation")
                ?: (if (buyerVo != null) firstNonBlank(buyerVo, "city", "address", "receiverCity") else null)
                ?: ""

            val msg = firstNonBlank(o, "buyerMessage", "buyerRemark", "memo", "note", "message") ?: ""

            val cTime = firstNonBlank(o, "createTime", "orderCreateTime", "orderTime", "gmtCreate", "payTime")
                ?: (if (common != null) firstNonBlank(common, "createTime", "orderTime") else null)
                ?: ""

            out.add(SoldOrder(orderId, title, sku, buyer, status, qty, price, city, msg, cTime))
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

    // ==================== 在售商品列表 ====================

    fun isRiskControlPublic(m: String): Boolean = isRiskControl(m)

    /**
     * 查询在售商品列表（采纳 Hark 审查死角补丁：第1页探测锁定源，翻页不跨源串台）。
     */
    fun queryOnSaleItems(
        cookie: String,
        page: Int = 1,
        pageSize: Int = 20,
        source: String? = null,
        onCookieRefreshed: ((String) -> Unit)? = null,
        onResult: ((Boolean, String) -> Unit)? = null
    ): OnSaleResult {
        if (cookie.isBlank()) return OnSaleResult(false, emptyList(), "", "未绑定账号")
        val userId = MtopSigner.parseCookies(cookie)["unb"].orEmpty()
        var anySuccess = false
        var lastErr = ""

        fun tryPersonal(grouped: Boolean): List<OnSaleItem>? {
            val d = JSONObject().apply {
                put("pageNumber", page)
                put("pageSize", pageSize)
                if (grouped) {
                    put("needGroupInfo", false)
                    put("groupName", "在售")
                    put("defaultGroup", true)
                }
                if (userId.isNotBlank()) put("userId", userId)
            }
            val r = callMtop(
                cookie = cookie,
                api = "mtop.idle.web.xyh.item.list",
                version = "1.0",
                dataObj = d,
                extraQuery = mapOf("spm_cnt" to "a21ybx.im.0.0"),
                onCookieRefreshed = onCookieRefreshed,
                onResult = onResult
            )
            if (!r.success) {
                lastErr = r.retCode.ifBlank { r.message }
                return null
            }
            anySuccess = true
            val items = mapPersonalItems(r.json)
            // 全量兜底时过滤出非在售
            return if (grouped) items else items.filter { it.onSale }
        }

        fun tryShop(): List<OnSaleItem>? {
            val d = JSONObject().apply {
                put("pageNo", page)
                put("pageSize", pageSize)
                put("bizType", "commonPro")
                put("searchRequest", "{}")
                put("itemStatus", "0")
            }
            val r = callMtop(
                cookie = cookie,
                api = "mtop.alibaba.idle.seller.pc.common.item.search",
                version = "1.0",
                dataObj = d,
                extraQuery = mapOf(
                    "needLoginPC" to "true",
                    "accountSite" to "xianyu",
                    "spm_cnt" to "a21107h.42826273.0.0"
                ),
                onCookieRefreshed = onCookieRefreshed,
                onResult = onResult,
                origin = "https://seller.goofish.com",
                referer = "https://seller.goofish.com/?site=COMMONPRO",
                extraHeaders = mapOf("idle_site_biz_code" to "COMMONPRO")
            )
            if (!r.success) {
                lastErr = r.retCode.ifBlank { r.message }
                return null
            }
            anySuccess = true
            return mapShopItems(r.json)
        }

        // 翻页：锁定第 1 页命中的源，绝不换源
        if (source != null) {
            val items = when (source) {
                "shop" -> tryShop()
                "all" -> tryPersonal(grouped = false)
                else -> tryPersonal(grouped = true)
            }
            return if (items == null) OnSaleResult(false, emptyList(), source, lastErr)
                   else OnSaleResult(true, items, source)
        }

        // 第 1 页自动探测
        tryPersonal(grouped = true)?.let { if (it.isNotEmpty()) return OnSaleResult(true, it, "personal") }
        if (isRiskControl(lastErr)) return OnSaleResult(false, emptyList(), "", lastErr)

        tryShop()?.let { if (it.isNotEmpty()) return OnSaleResult(true, it, "shop") }
        if (isRiskControl(lastErr)) return OnSaleResult(false, emptyList(), "", lastErr)

        tryPersonal(grouped = false)?.let { if (it.isNotEmpty()) return OnSaleResult(true, it, "all") }

        return OnSaleResult(anySuccess, emptyList(), "", if (anySuccess) "" else lastErr)
    }

    private val RISK_MARKERS = arrayOf("RGV587_ERROR", "FAIL_SYS_USER_VALIDATE", "LIMIT", "captcha", "被挤爆")
    private fun isRiskControl(msg: String): Boolean = RISK_MARKERS.any { msg.contains(it, ignoreCase = true) }

    private fun mapPersonalItems(root: JSONObject?): List<OnSaleItem> {
        val data = root?.optJSONObject("data") ?: return emptyList()
        val cards = data.optJSONArray("cardList")
            ?: data.optJSONArray("itemList")
            ?: data.optJSONArray("items")
            ?: data.optJSONArray("list")
            ?: return emptyList()
        val out = ArrayList<OnSaleItem>(cards.length())
        for (i in 0 until cards.length()) {
            val raw = cards.optJSONObject(i) ?: continue
            val card = raw.optJSONObject("cardData") ?: raw
            val id = firstNonBlank(card, "id", "itemId", "item_id") ?: continue
            val title = firstNonBlank(card, "title", "itemTitle", "name") ?: ""
            val price = card.optJSONObject("priceInfo")?.optString("price").orEmpty()
                .ifBlank { firstNonBlank(card, "price", "soldPrice", "reservePrice") ?: "" }
            val picUrl = card.optJSONObject("picInfo")?.optString("url").orEmpty()
                .ifBlank { card.optJSONObject("picInfo")?.optString("picUrl").orEmpty() }
                .ifBlank { firstNonBlank(card, "picUrl", "imageUrl", "image", "itemImageUrl", "cover") ?: "" }
            val isOnSale = card.optInt("itemStatus") == 0
            val status = card.optString("itemStatusDesc").takeIf { it.isNotBlank() && it != "null" }
                ?: if (isOnSale) "在售" else "已下架"
            val detailUrl = "https://www.goofish.com/item?id=$id"
            out.add(OnSaleItem(id, title, price, picUrl, status, detailUrl, "personal", isOnSale))
        }
        return out
    }

    private fun mapShopItems(root: JSONObject?): List<OnSaleItem> {
        val data = root?.optJSONObject("data")?.optJSONObject("data")
            ?: root?.optJSONObject("data")
            ?: return emptyList()
        val list = data.optJSONArray("itemSearchResponseList")
            ?: data.optJSONArray("itemList")
            ?: data.optJSONArray("items")
            ?: data.optJSONArray("list")
            ?: return emptyList()
        val out = ArrayList<OnSaleItem>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val id = firstNonBlank(o, "itemId", "id", "item_id") ?: continue
            val title = firstNonBlank(o, "title", "itemTitle", "name") ?: ""
            val price = firstNonBlank(o, "reservePrice", "price", "soldPrice") ?: ""
            val picUrl = firstNonBlank(o, "itemImageUrl", "imageUrl", "picUrl", "cover") ?: ""
            val status = firstNonBlank(o, "itemStatusDesc", "status")?.ifBlank { "在售" } ?: "在售"
            val detailUrl = "https://www.goofish.com/item?id=$id"
            out.add(OnSaleItem(id, title, price, picUrl, status, detailUrl, "shop"))
        }
        return out
    }

    // ==================== 图片直传 ====================

    private const val STREAM_UPLOAD_URL =
        "https://stream-upload.goofish.com/api/upload.api?floderId=0&appkey=fleamarket&_input_charset=utf-8"

    /**
     * 上传图片到闲鱼官方图床。
     */
    fun uploadImage(
        cookie: String,
        bytes: ByteArray,
        filename: String = "upload.jpg"
    ): Pair<Boolean, UploadedImage?> {
        if (cookie.isBlank() || bytes.isEmpty()) return false to null
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds) }
        val w = if (bounds.outWidth > 0) bounds.outWidth else 800
        val h = if (bounds.outHeight > 0) bounds.outHeight else 800

        val mediaType = "image/jpeg".toMediaTypeOrNull()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, bytes.toRequestBody(mediaType))
            .build()

        val req = Request.Builder()
            .url(STREAM_UPLOAD_URL)
            .header("Cookie", cookie)
            .header("Origin", "https://seller.goofish.com")
            .header("Referer", "https://seller.goofish.com/?site=COMMONPRO")
            .header("User-Agent", UA)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "*/*")
            .post(body)
            .build()

        return try {
            client.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                val json = runCatching { JSONObject(txt) }.getOrNull() ?: return false to null
                val obj = json.optJSONObject("object") ?: json
                val url = obj.optString("url")
                if (url.isNotBlank()) {
                    true to UploadedImage(url, w, h)
                } else {
                    Log.w(TAG, "uploadImage 失败: $txt")
                    false to null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "uploadImage exception", e)
            false to null
        }
    }

    // ==================== 发布商品 ====================

    fun publishItem(
        cookie: String,
        title: String,
        desc: String,
        price: Double,
        images: List<UploadedImage> = emptyList(),
        isVirtual: Boolean = true,
        onCookieRefreshed: ((String) -> Unit)? = null,
        onResult: ((Boolean, String) -> Unit)? = null
    ): Pair<Boolean, String> {
        if (cookie.isBlank()) return false to "未绑定账号"
        if (title.isBlank()) return false to "标题不能为空"
        if (price <= 0.0) return false to "价格需大于 0"
        if (images.isEmpty()) return false to "请至少上传一张商品主图"

        val imgList = JSONArray()
        images.forEachIndexed { idx, img ->
            imgList.put(JSONObject().apply {
                put("url", img.url)
                put("heightSize", img.heightSize)
                put("widthSize", img.widthSize)
                put("major", idx == 0)
                put("type", 0)
                put("status", "done")
                put("extraInfo", JSONObject().apply {
                    put("isH", "false")
                    put("isT", "false")
                    put("raw", "false")
                })
            })
        }

        // 修复浮点精度截断（避免 19.99 截成 1998 分）
        val priceCent = try {
            java.math.BigDecimal(price.toString())
                .multiply(java.math.BigDecimal(100))
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .toLong().toString()
        } catch (_: Exception) {
            Math.round(price * 100).toString()
        }

        val data = JSONObject().apply {
            put("freebies", false)
            put("itemTypeStr", "b")
            put("quantity", "1")
            put("simpleItem", "true")
            put("imageInfoDOList", imgList)
            put("itemTextDTO", JSONObject().apply {
                put("title", title)
                put("desc", desc)
            })
            put("itemLabelExtList", JSONArray())
            put("itemPriceDTO", JSONObject().apply {
                put("origPriceInCent", priceCent)
                put("priceInCent", priceCent)
            })
            // 修复：虚拟商品无需邮寄运费设置（supportFreight=!isVirtual）
            put("itemPostFeeDTO", JSONObject().apply {
                put("canFreeShipping", true)
                put("supportFreight", !isVirtual)
                put("onlyTakeSelf", false)
            })
            // 修复：空对象 {} 可能被判参数缺失，直接不传，由闲鱼按卖家账号默认地址处理
            put("uniqueCode", "${System.currentTimeMillis()}")
            put("sourceId", "pcMainPublish")
            put("bizcode", "pcMainPublish")
            put("publishScene", "pcMainPublish")
        }

        val r = callMtop(
            cookie = cookie,
            api = "mtop.idle.pc.idleitem.publish",
            version = "1.0",
            dataObj = data,
            extraQuery = mapOf("spm_cnt" to "a21ybx.publish.0.0"),
            onCookieRefreshed = onCookieRefreshed,
            onResult = onResult
        )
        if (!r.success) return false to r.message
        val itemId = r.json?.optJSONObject("data")?.let { firstNonBlank(it, "itemId", "id") }.orEmpty()
        return true to (if (itemId.isNotBlank()) "发布成功！商品ID: $itemId" else "商品发布成功")
    }
}
