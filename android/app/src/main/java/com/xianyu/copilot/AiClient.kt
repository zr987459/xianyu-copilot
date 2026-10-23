package com.xianyu.copilot

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 大模型调用：OpenAI 兼容（DeepSeek / 通义 / Kimi / OpenRouter 等）与 Anthropic Messages API。
 * 配置全部存在本机 app_settings（API Key 经 SecureStore 加密），不会进仓库。
 */
object AiClient {

    private const val TAG = "AiClient"

    data class Config(
        val enabled: Boolean,
        val provider: String,   // openai | anthropic
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val maxDiscountPercent: Double,
        val maxDiscountAmount: Double,
        val maxBargainRounds: Int,
        val temperature: Double
    )

    fun loadConfig(context: Context): Config {
        val db = CardDatabase.get(context)
        return Config(
            enabled = db.getSetting("ai_enabled", "false") == "true",
            provider = db.getSetting("ai_provider", "openai"),
            baseUrl = db.getSetting("ai_base_url", "https://api.deepseek.com/v1"),
            apiKey = SecureStore.decrypt(db.getSetting("ai_api_key", "")),
            model = db.getSetting("ai_model", "deepseek-chat"),
            maxDiscountPercent = db.getSetting("ai_max_discount_percent", "10").toDoubleOrNull() ?: 10.0,
            maxDiscountAmount = db.getSetting("ai_max_discount_amount", "100").toDoubleOrNull() ?: 100.0,
            maxBargainRounds = db.getSetting("ai_max_bargain_rounds", "3").toIntOrNull() ?: 3,
            temperature = db.getSetting("ai_temperature", "0.7").toDoubleOrNull() ?: 0.7
        )
    }

    private val http by lazy {
        MtopClient.sharedClient.newBuilder()
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    /** 返回 (回复文本或 null, 错误说明)。 */
    fun chat(cfg: Config, system: String, user: String, maxTokens: Int = 200): Pair<String?, String> {
        if (cfg.apiKey.isBlank()) return null to "未配置 API Key"
        return try {
            if (cfg.provider.equals("anthropic", true)) anthropic(cfg, system, user, maxTokens)
            else openai(cfg, system, user, maxTokens)
        } catch (e: Exception) {
            Log.w(TAG, "chat failed", e)
            null to "请求异常: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun openai(cfg: Config, system: String, user: String, maxTokens: Int): Pair<String?, String> {
        var base = cfg.baseUrl.trim().trimEnd('/')
        if (base.isBlank()) base = "https://api.openai.com/v1"
        val url = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val body = JSONObject()
            .put("model", cfg.model)
            .put("max_tokens", maxTokens)
            .put("temperature", cfg.temperature)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        http.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return null to "HTTP ${resp.code}: ${txt.take(160)}"
            val j = JSONObject(txt)
            val content = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            return if (content.isBlank()) null to "空回复" else content.trim() to "成功"
        }
    }

    private fun anthropic(cfg: Config, system: String, user: String, maxTokens: Int): Pair<String?, String> {
        var base = cfg.baseUrl.trim().trimEnd('/')
        if (base.isBlank()) base = "https://api.anthropic.com"
        val url = when {
            base.endsWith("/v1/messages") -> base
            base.endsWith("/v1") -> "$base/messages"
            else -> "$base/v1/messages"
        }
        val body = JSONObject()
            .put("model", cfg.model)
            .put("max_tokens", maxTokens)
            .put("temperature", cfg.temperature)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", user)))
        val req = Request.Builder().url(url)
            .header("x-api-key", cfg.apiKey)
            .header("anthropic-version", "2023-06-01")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        http.newCall(req).execute().use { resp ->
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return null to "HTTP ${resp.code}: ${txt.take(160)}"
            val arr = JSONObject(txt).optJSONArray("content") ?: return null to "空回复"
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                if (c.optString("type") == "text") sb.append(c.optString("text"))
            }
            return if (sb.isBlank()) null to "空回复" else sb.toString().trim() to "成功"
        }
    }

    // ------------------------------------------------------------ 提示词

    /** 内置提示词来自仓库 prompts/（assets/prompts），可在设置里按意图覆盖。 */
    fun prompt(context: Context, intent: String): String {
        val custom = CardDatabase.get(context).getSetting("ai_prompt_$intent", "")
        if (custom.isNotBlank()) return custom
        return runCatching {
            context.assets.open("prompts/${intent}_prompt.txt").use { String(it.readBytes(), Charsets.UTF_8) }
        }.getOrDefault(
            "你是一位资深闲鱼卖家客服。语言要求：简短友好，每句≤10字，总字数≤40字。结合商品信息回答。"
        )
    }

    /** 本地关键词意图识别（参考 ai_reply_engine.detect_intent，避免为分类额外调用一次模型）。 */
    fun detectIntent(message: String): String {
        val m = message.lowercase()
        val price = listOf("便宜", "优惠", "刀", "降价", "包邮", "价格", "多少钱", "能少", "还能", "最低", "底价",
            "实诚价", "能到", "包个邮", "给个价", "什么价", "砍价", "便宜点", "少点")
        if (price.any { m.contains(it) }) return "price"
        val tech = listOf("怎么用", "参数", "坏了", "故障", "设置", "说明书", "功能", "用法", "教程", "驱动", "型号", "规格", "支持", "兼容")
        if (tech.any { m.contains(it) }) return "tech"
        return "default"
    }
}
