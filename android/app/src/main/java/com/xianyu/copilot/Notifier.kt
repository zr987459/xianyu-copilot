package com.xianyu.copilot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 通知中心：企业微信 / 钉钉 / 飞书 / Bark / Telegram / 自定义 Webhook + 本机系统通知。
 * 消息格式参考 xianyu-guanjia 的 _send_*_notification。
 */
object Notifier {

    private const val TAG = "Notifier"
    private const val ALERT_CHANNEL = "xianyu_copilot_alerts"

    enum class Event(val key: String, val label: String) {
        NEW_MESSAGE("message", "新消息"),
        NEW_ORDER("order", "新订单"),
        DELIVERY_OK("delivery_ok", "发货成功"),
        DELIVERY_FAIL("delivery_fail", "发货失败"),
        LOW_STOCK("low_stock", "库存不足"),
        ACCOUNT_EXPIRED("account", "账号失效"),
        TEST("test", "测试")
    }

    val TYPES = listOf("wecom", "dingtalk", "feishu", "bark", "telegram", "webhook")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val throttle = ConcurrentHashMap<String, Long>()
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * 异步推送。[dedupeKey] 非空时同一 key 在 [throttleMs] 内只推一次（避免同一会话刷屏）。
     */
    fun notify(
        context: Context, event: Event, title: String, content: String,
        dedupeKey: String = "", throttleMs: Long = 60_000L
    ) {
        if (dedupeKey.isNotBlank()) {
            val k = "${event.key}:$dedupeKey"
            val now = System.currentTimeMillis()
            val last = throttle[k] ?: 0L
            if (now - last < throttleMs) return
            throttle[k] = now
            if (throttle.size > 2000) throttle.clear()
        }
        val app = context.applicationContext
        scope.launch {
            runCatching { localNotify(app, event, title, content) }
            val channels = runCatching {
                OpsDatabase.get(app).queryJson("SELECT * FROM notify_channels WHERE enabled=1")
            }.getOrNull() ?: return@launch
            for (i in 0 until channels.length()) {
                val ch = channels.optJSONObject(i) ?: continue
                val events = ch.optString("events").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                if (event != Event.TEST && events.isNotEmpty() && event.key !in events) continue
                val cfg = runCatching { JSONObject(ch.optString("config", "{}")) }.getOrDefault(JSONObject())
                val (ok, msg) = send(ch.optString("type"), cfg, title, content, event)
                if (!ok) Log.w(TAG, "channel ${ch.optLong("id")} failed: $msg")
            }
        }
    }

    /** 同步发送到单个渠道（测试按钮用）。 */
    fun send(type: String, cfg: JSONObject, title: String, content: String, event: Event = Event.TEST): Pair<Boolean, String> {
        val text = "【闲鱼管家】$title\n$content"
        return try {
            when (type) {
                "wecom" -> post(cfg.optString("url"),
                    JSONObject().put("msgtype", "text").put("text", JSONObject().put("content", text)))
                "dingtalk" -> {
                    var url = cfg.optString("url")
                    val secret = cfg.optString("secret")
                    if (secret.isNotBlank()) {
                        val ts = System.currentTimeMillis()
                        val sign = hmacB64("$ts\n$secret".toByteArray(), secret.toByteArray())
                        url += (if (url.contains('?')) "&" else "?") + "timestamp=$ts&sign=" + URLEncoder.encode(sign, "UTF-8")
                    }
                    post(url, JSONObject().put("msgtype", "text").put("text", JSONObject().put("content", text)))
                }
                "feishu" -> {
                    val body = JSONObject().put("msg_type", "text").put("content", JSONObject().put("text", text))
                    val secret = cfg.optString("secret")
                    if (secret.isNotBlank()) {
                        val ts = System.currentTimeMillis() / 1000
                        // 飞书签名：以 timestamp+"\n"+secret 为 key 对空串做 HmacSHA256
                        body.put("timestamp", ts.toString())
                        body.put("sign", hmacB64(ByteArray(0), "$ts\n$secret".toByteArray()))
                    }
                    post(cfg.optString("url"), body)
                }
                "bark" -> {
                    // url 形如 https://api.day.app/<key>
                    val url = cfg.optString("url").trimEnd('/')
                    post(url, JSONObject().put("title", "闲鱼管家 · $title").put("body", content).put("group", "xianyu"))
                }
                "telegram" -> {
                    val token = cfg.optString("token")
                    val chatId = cfg.optString("chat_id")
                    if (token.isBlank() || chatId.isBlank()) return false to "缺少 token / chat_id"
                    val base = cfg.optString("api_base").ifBlank { "https://api.telegram.org" }.trimEnd('/')
                    post("$base/bot$token/sendMessage", JSONObject().put("chat_id", chatId).put("text", text))
                }
                "webhook" -> post(cfg.optString("url"), JSONObject()
                    .put("event", event.key).put("title", title).put("content", content)
                    .put("text", text).put("ts", System.currentTimeMillis()))
                else -> false to "未知渠道类型 $type"
            }
        } catch (e: Exception) {
            false to "异常: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun post(url: String, body: JSONObject): Pair<Boolean, String> {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false to "URL 无效"
        val req = Request.Builder().url(url).post(body.toString().toRequestBody(JSON_TYPE)).build()
        MtopClient.sharedClient.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return false to "HTTP ${resp.code} ${txt.take(120)}"
            // 钉钉/企微成功也是 200，错误码在 errcode
            val j = runCatching { JSONObject(txt) }.getOrNull()
            val errcode = j?.optInt("errcode", 0) ?: 0
            val code = j?.optInt("code", 0) ?: 0
            return if (errcode != 0) false to "errcode=$errcode ${j?.optString("errmsg")}"
            else if (j != null && j.has("StatusCode") && j.optInt("StatusCode") != 0) false to txt.take(120)
            else if (code != 0 && code != 200 && j?.has("msg") == true) false to "code=$code ${j.optString("msg")}"
            else true to "已发送"
        }
    }

    private fun hmacB64(data: ByteArray, key: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(data), Base64.NO_WRAP)
    }

    private fun localNotify(context: Context, event: Event, title: String, content: String) {
        val db = CardDatabase.get(context)
        if (db.getSetting("local_notify_enabled", "true") != "true") return
        if (event == Event.TEST) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(ALERT_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL, "闲鱼管家提醒", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pi = launch?.let {
            android.app.PendingIntent.getActivity(
                context, 0, it,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }
        val n = NotificationCompat.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(content.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.take(500)))
            .setAutoCancel(true)
            .apply { if (pi != null) setContentIntent(pi) }
            .build()
        runCatching { nm.notify(2000 + (event.ordinal * 7919 + title.hashCode()).mod(5000), n) }
    }
}
