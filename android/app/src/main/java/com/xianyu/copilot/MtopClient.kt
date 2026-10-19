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
        val status: String
    )

    // ---------------- 核心请求 ----------------

    private data class ApiResult(val success: Boolean, val message: String, val json: JSONObject?)

    /**
     * 执行一次 Mtop 调用。
     * @param api 形如 "mtop.taobao.idle.logistic.consign.dummy"
     * @param version 形如 "1.0"
     * @param dataObj 请求体数据；内部会 toString 并参与签名
     */
    private fun callMtop(
        cookie: String,
        api: String,
        version: String,
        dataObj: JSONObject,
        extraQuery: Map<String, String> = emptyMap()
    ): ApiResult {
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
                if (!resp.isSuccessful) {
                    return ApiResult(false, "HTTP ${resp.code}", null)
                }
                val json = runCatching { JSONObject(raw) }.getOrNull()
                    ?: return ApiResult(false, "响应解析失败", null)
                val retArr: JSONArray? = json.optJSONArray("ret")
                val ret0 = retArr?.optString(0).orEmpty()
                // 成功标志：ret[0] 以 SUCCESS 结尾（如 "SUCCESS::调用成功" / "SUCCESS::SUCCESS"）
                val ok = ret0.contains("SUCCESS")
                val msg = if (ok) "成功" else ret0.ifEmpty {
                    json.optJSONObject("data")?.optString("msg").orEmpty().ifEmpty { "失败" }
                }
                ApiResult(ok, msg, json)
            }
        } catch (e: Exception) {
            Log.e(TAG, "callMtop($api) error", e)
            ApiResult(false, "请求异常: ${e.message ?: e.javaClass.simpleName}", null)
        }
    }

    private fun encode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    // ---------------- 业务接口 ----------------

    /**
     * 官方"无需物流"发货。
     * @return Pair(是否成功, 提示信息)
     */
    fun confirmDummyShipping(cookie: String, orderId: String, tradeText: String): Pair<Boolean, String> {
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
                dataObj = data
            )
            r.success to r.message
        } catch (e: Exception) {
            Log.e(TAG, "confirmDummyShipping error", e)
            false to "异常: ${e.message}"
        }
    }

    /**
     * 校验 Cookie 有效性：拉一次基础用户信息接口。
     * @return Pair(是否有效, 用户名/状态)
     */
    fun testCookie(cookie: String): Pair<Boolean, String> {
        if (cookie.isBlank()) return false to "Cookie 为空"
        return try {
            val r = callMtop(
                cookie = cookie,
                api = "mtop.taobao.idle.user.info.get",
                version = "1.0",
                dataObj = JSONObject()
            )
            if (!r.success) return false to r.message
            val data = r.json?.optJSONObject("data")
            val nick = data?.optString("nick")
                ?.takeIf { it.isNotBlank() }
                ?: data?.optString("userNick")
                    ?.takeIf { it.isNotBlank() }
                ?: "已登录"
            true to nick
        } catch (e: Exception) {
            Log.e(TAG, "testCookie error", e)
            false to "异常: ${e.message}"
        }
    }

    /**
     * 查询卖家已卖出订单，过滤出待发货等状态。
     * 主接口失败时回退备用接口。
     */
    fun querySoldOrders(cookie: String): List<SoldOrder> {
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
                val r = callMtop(cookie, api, ver, data)
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
            out.add(SoldOrder(orderId, title, sku, buyer, status))
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
