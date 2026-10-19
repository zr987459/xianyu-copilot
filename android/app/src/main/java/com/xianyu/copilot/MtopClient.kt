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
        val source: String
    )

    /** 上传成功的图片信息。 */
    data class UploadedImage(
        val url: String,
        val widthSize: Int = 800,
        val heightSize: Int = 800
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
        "FAIL_SYS_USER_VALIDATE",
        "FAIL_SYS_LOGIN",
        "SESSION_EXPIRED",
        "请重新登录",
        "未登录"
    )

    private fun isSessionDead(ret: String): Boolean =
        SESSION_DEAD_MARKERS.any { ret.contains(it, ignoreCase = true) }

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
        /**
         * 是否允许拿库里该账号的 cookie 作为刷新基准。
         * 登录校验时必须为 false：此时库里是「旧会话」，用它做基准会把刚登录的新 cookie
         * 顺回旧的，或把新令牌拼到已失效的 cookie2/sgcookie 上导致新登录被判失败。
         */
        allowStoredBase: Boolean = true
    ): ApiResult {
        val unb = MtopSigner.parseCookies(cookie)["unb"].orEmpty()

        fun finish(success: Boolean, message: String, json: JSONObject?, retCode: String): ApiResult {
            // 每次调用的最终结果都回调出去，供守护层写账号健康状态
            onResult?.invoke(success, retCode.ifBlank { message })
            return ApiResult(success, message, json, retCode)
        }

        val first = callMtopOnce(cookie, api, version, dataObj, extraQuery)
        if (!first.tokenExpired) {
            return finish(first.success, first.message, first.json, first.retCode)
        }

        // 令牌过期 / 为空：在账号锁内完成刷新，避免并发互盖
        synchronized(lockFor(unb)) {
            // 拿锁后重新校一下：若别人已刷新，直接用新令牌重签即可
            val baseCookie = (if (allowStoredBase) currentCookieFor(unb) else null) ?: cookie
            val oldToken = MtopSigner.parseCookies(baseCookie)["_m_h5_tk"]

            // 先尝试用最新 cookie 直接重签（可能是别人刚刷新的）
            if (baseCookie != cookie) {
                val retry = callMtopOnce(baseCookie, api, version, dataObj, extraQuery)
                if (!retry.tokenExpired && retry.success) {
                    onCookieRefreshed?.invoke(baseCookie)
                    return finish(retry.success, retry.message, retry.json, retry.retCode)
                }
            }

            val merged = mergeSetCookie(baseCookie, first.setCookies)
            val newToken = MtopSigner.parseCookies(merged)["_m_h5_tk"]
            if (merged == baseCookie || newToken == oldToken) {
                // 没拿到新令牌：令牌刷新也救不回来 ⇒ 登录态已失效
                Log.w(TAG, "callMtop($api) 令牌未能刷新，判定登录态失效: ${first.retCode}")
                return finish(false, first.message, first.json, first.retCode)
            }

            Log.i(TAG, "callMtop($api) 令牌已刷新，重签重试一次")
            onCookieRefreshed?.invoke(merged)

            val retry = callMtopOnce(merged, api, version, dataObj, extraQuery)
            if (retry.tokenExpired) {
                Log.w(TAG, "callMtop($api) 刷新后仍过期，判定登录态失效")
                return finish(false, retry.message, retry.json, retry.retCode)
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

            // 修复 HTTP 415：闲鱼 Mtop H5 网关仅支持 application/x-www-form-urlencoded，
            // 必须通过表单提交 data 参数。原先使用 application/json 导致被网关直接 415 拒收。
            val body = FormBody.Builder()
                .add("data", dataStr)
                .build()
            val request = Request.Builder()
                .url(urlBuilder.toString())
                .header("Cookie", cookie)
                .header("User-Agent", UA)
                .header("Origin", ORIGIN)
                .header("Referer", "$ORIGIN/")
                .header("Accept", "application/json")
                .header("Connection", "keep-alive")
                .header("Sec-Fetch-Site", "same-site")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Dest", "empty")
                .post(body)
                .build()

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
        onCookieRefreshed: ((String) -> Unit)? = null,
        onResult: ((Boolean, String) -> Unit)? = null
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
                val r = callMtop(
                    cookie, api, ver, data,
                    onCookieRefreshed = onCookieRefreshed,
                    onResult = onResult
                )
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
            val price = firstNonBlank(o, "actualPayFee", "totalPrice", "orderPrice", "price", "amount") ?: ""
            val city = firstNonBlank(o, "receiverCity", "buyerCity", "city", "provCity", "buyerLocation") ?: ""
            val msg = firstNonBlank(o, "buyerMessage", "buyerRemark", "memo", "note", "message") ?: ""
            val cTime = firstNonBlank(o, "createTime", "orderCreateTime", "orderTime", "gmtCreate", "payTime") ?: ""
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

    /**
     * 查询在售商品列表（个人版优先，明确报错且非风控才回退鱼小铺）。
     */
    fun queryOnSaleItems(
        cookie: String,
        page: Int = 1,
        pageSize: Int = 20,
        onCookieRefreshed: ((String) -> Unit)? = null,
        onResult: ((Boolean, String) -> Unit)? = null
    ): Pair<Boolean, List<OnSaleItem>> {
        if (cookie.isBlank()) return false to emptyList()
        val userId = MtopSigner.parseCookies(cookie)["unb"].orEmpty()

        // 1) 个人版优先
        val personalData = JSONObject().apply {
            put("needGroupInfo", false)
            put("pageNumber", page)
            put("pageSize", pageSize)
            put("groupName", "在售")
            put("groupId", "58877261")
            put("defaultGroup", true)
            if (userId.isNotBlank()) put("userId", userId)
        }
        val personal = callMtop(
            cookie = cookie,
            api = "mtop.idle.web.xyh.item.list",
            version = "1.0",
            dataObj = personalData,
            extraQuery = mapOf("spm_cnt" to "a21ybx.im.0.0"),
            onCookieRefreshed = onCookieRefreshed,
            onResult = onResult
        )
        if (personal.success) {
            val items = mapPersonalItems(personal.json)
            Log.i(TAG, "queryOnSaleItems 个人版成功，获取 ${items.size} 件")
            return true to items
        }

        // 风控错误绝不回退
        if (isRiskControl(personal.retCode.ifBlank { personal.message })) {
            Log.w(TAG, "queryOnSaleItems 个人版触碰风控，终止回退: ${personal.retCode}")
            return false to emptyList()
        }

        // 2) 鱼小铺回退
        Log.i(TAG, "queryOnSaleItems 个人版非风控失败(${personal.retCode})，回退鱼小铺")
        val shopData = JSONObject().apply {
            put("pageNo", page)
            put("pageSize", pageSize)
            put("bizType", "commonPro")
            put("searchRequest", "{}")
            put("itemStatus", "0")
        }
        val shop = callMtop(
            cookie = cookie,
            api = "mtop.alibaba.idle.seller.pc.common.item.search",
            version = "1.0",
            dataObj = shopData,
            extraQuery = mapOf("needLoginPC" to "true", "accountSite" to "xianyu"),
            onCookieRefreshed = onCookieRefreshed,
            onResult = onResult
        )
        if (shop.success) {
            val items = mapShopItems(shop.json)
            return true to items
        }
        return false to emptyList()
    }

    private val RISK_MARKERS = arrayOf("RGV587_ERROR", "FAIL_SYS_USER_VALIDATE", "LIMIT", "captcha", "被挤爆")
    private fun isRiskControl(msg: String): Boolean = RISK_MARKERS.any { msg.contains(it, ignoreCase = true) }

    private fun mapPersonalItems(root: JSONObject?): List<OnSaleItem> {
        val data = root?.optJSONObject("data") ?: return emptyList()
        val cards = data.optJSONArray("cardList") ?: return emptyList()
        val out = ArrayList<OnSaleItem>(cards.length())
        for (i in 0 until cards.length()) {
            val card = cards.optJSONObject(i)?.optJSONObject("cardData") ?: continue
            val id = card.optString("id").ifBlank { card.optString("itemId") }
            if (id.isBlank()) continue
            val title = card.optString("title")
            val price = card.optJSONObject("priceInfo")?.optString("price").orEmpty()
                .ifBlank { card.optString("price") }
            val picUrl = card.optJSONObject("picInfo")?.optString("url").orEmpty()
                .ifBlank { card.optJSONObject("picInfo")?.optString("picUrl").orEmpty() }
            val status = if (card.optInt("itemStatus") == 0) "在售" else "已下架"
            val detailUrl = "https://www.goofish.com/item?id=$id"
            out.add(OnSaleItem(id, title, price, picUrl, status, detailUrl, "personal"))
        }
        return out
    }

    private fun mapShopItems(root: JSONObject?): List<OnSaleItem> {
        val data = root?.optJSONObject("data")?.optJSONObject("data") ?: return emptyList()
        val list = data.optJSONArray("itemSearchResponseList") ?: return emptyList()
        val out = ArrayList<OnSaleItem>(list.length())
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val id = o.optString("itemId")
            if (id.isBlank()) continue
            val title = o.optString("title")
            val price = o.optString("reservePrice")
            val picUrl = o.optString("itemImageUrl")
            val status = o.optString("itemStatusDesc").ifBlank { "在售" }
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
            // 修复：虚拟商品无需邮寄运费设置（supportFreight=false）
            put("itemPostFeeDTO", JSONObject().apply {
                put("canFreeShipping", true)
                put("supportFreight", false)
                put("onlyTakeSelf", false)
            })
            // 修复：不伪造上海 GPS/行政区划，由平台按账号默认设置定位
            put("itemAddrDTO", JSONObject())
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
