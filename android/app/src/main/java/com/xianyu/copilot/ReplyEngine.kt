package com.xianyu.copilot

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 自动回复引擎。优先级（参考 xianyu-auto-reply 的 get_keyword_reply / get_ai_reply / get_default_reply）：
 *   1. 关键词规则（商品专属 > 通用；精确 / 包含）
 *   2. 商品专属回复（item_replies）
 *   3. AI 回复（意图识别 + 议价轮数 / 最大优惠 / 底价约束）
 *   4. 默认回复（可设置每个会话只回一次）
 * 发送前统一过敏感词；同一会话防抖合并连发、冷却、重复内容去重；人工接管期间不回复。
 */
object ReplyEngine {

    private const val TAG = "ReplyEngine"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, Job>()
    private val lastReply = ConcurrentHashMap<String, Pair<String, Long>>()

    data class Decision(val text: String?, val source: String)

    /** 防抖：买家连发多条时，只对最后一条回复。 */
    fun schedule(context: Context, accountId: String, chatId: String, buyerId: String, buyerNick: String, itemId: String, text: String) {
        val db = CardDatabase.get(context)
        if (db.getSetting("auto_reply_enabled", "true") != "true") return
        val key = "$accountId:$chatId"
        val debounce = db.getSetting("reply_debounce_ms", "2500").toLongOrNull()?.coerceIn(0L, 30_000L) ?: 2500L
        pending.remove(key)?.cancel()
        pending[key] = scope.launch {
            delay(debounce)
            pending.remove(key)
            runCatching { replyNow(context, accountId, chatId, buyerId, buyerNick, itemId, text) }
                .onFailure { Log.w(TAG, "reply failed", it) }
        }
    }

    private suspend fun replyNow(context: Context, accountId: String, chatId: String, buyerId: String, buyerNick: String, itemId: String, text: String) {
        val ops = OpsDatabase.get(context)
        val db = CardDatabase.get(context)
        if (ops.pausedUntil(accountId, chatId) > System.currentTimeMillis()) return
        val key = "$accountId:$chatId"
        val cooldown = (db.getSetting("reply_cooldown_sec", "3").toLongOrNull() ?: 3L) * 1000L
        val last = lastReply[key]
        if (last != null && System.currentTimeMillis() - last.second < cooldown) return

        val d = decide(context, accountId, chatId, buyerId, buyerNick, itemId, text)
        val reply = d.text?.trim().orEmpty()
        if (reply.isBlank()) return
        // 同一会话 60 秒内不重复发同样的内容（防两边机器人互相回复死循环）
        if (last != null && last.first == reply && System.currentTimeMillis() - last.second < 60_000L) return
        val filtered = filterSensitive(context, reply) ?: return
        val (ok, msg) = ImManager.send(context, accountId, chatId, buyerId, filtered, kind = 1)
        if (ok) lastReply[key] = filtered to System.currentTimeMillis()
        else Log.w(TAG, "auto reply send failed: $msg")
    }

    /** 纯决策（不发送），也供「测试回复」接口使用。 */
    fun decide(context: Context, accountId: String, chatId: String, buyerId: String, buyerNick: String, itemId: String, text: String): Decision {
        val ops = OpsDatabase.get(context)
        val db = CardDatabase.get(context)
        val msg = text.trim()
        fun vars(s: String) = s.replace("{买家}", buyerNick.ifBlank { "亲" })
            .replace("{send_user_name}", buyerNick.ifBlank { "亲" })
            .replace("{商品ID}", itemId)
            .replace("{消息}", msg)

        // 1) 关键词
        for (r in ops.enabledKeywordRules(accountId)) {
            if (r.itemId.isNotBlank() && r.itemId != itemId) continue
            val hit = r.keyword.split('|', '｜').map { it.trim() }.filter { it.isNotEmpty() }.any { k ->
                if (r.matchType == "exact") msg.equals(k, ignoreCase = true) else msg.contains(k, ignoreCase = true)
            }
            if (hit) return Decision(vars(r.reply), "keyword#${r.id}")
        }

        // 2) 商品专属回复
        val ir = ops.itemReply(accountId, itemId)
        if (ir != null && ir.optLong("enabled", 1) == 1L && ir.optString("reply").isNotBlank()) {
            return Decision(vars(ir.optString("reply")), "item")
        }

        // 3) AI
        val cfg = AiClient.loadConfig(context)
        if (cfg.enabled && cfg.apiKey.isNotBlank()) {
            val r = aiReply(context, cfg, accountId, chatId, itemId, msg, ir)
            if (!r.text.isNullOrBlank()) return r
        }

        // 4) 默认回复
        if (db.getSetting("default_reply_enabled", "false") == "true") {
            val once = db.getSetting("default_reply_once", "true") == "true"
            if (!once || !ops.hasAutoReplied(accountId, chatId, 1)) {
                val t = db.getSetting("default_reply_text", "")
                if (t.isNotBlank()) return Decision(vars(t), "default")
            }
        }
        return Decision(null, "none")
    }

    private fun aiReply(
        context: Context, cfg: AiClient.Config, accountId: String, chatId: String, itemId: String,
        msg: String, itemReply: JSONObject?
    ): Decision {
        val ops = OpsDatabase.get(context)
        val intent = AiClient.detectIntent(msg)
        val bargain = ops.bargainCount(accountId, chatId)
        if (intent == "price" && bargain > cfg.maxBargainRounds) {
            return Decision("抱歉，这已经是最优惠的价格了，不能再便宜了哦~", "ai-limit")
        }
        // 商品信息：本地缓存，缺失时查一次详情
        var item = ops.item(itemId)
        if ((item == null || item.optString("title").isBlank()) && itemId.isNotBlank()) {
            val cookie = CardDatabase.get(context).getAccountCookie(accountId)
            val (d, _) = MtopClient.getItemDetail(cookie, itemId) { AccountManager.persistRefreshedCookie(context, it) }
            if (d != null) {
                ops.upsertItem(itemId, accountId, d.title, d.price, d.desc, d.images.firstOrNull().orEmpty(), "")
                item = ops.item(itemId)
            }
        }
        val title = item?.optString("title").orEmpty().ifBlank { "未知" }
        val price = item?.optString("price").orEmpty().ifBlank { "未知" }
        val desc = item?.optString("description").orEmpty().take(500).ifBlank { "无" }
        val floor = itemReply?.optString("floor_price").orEmpty()

        val history = ops.recentMessages(accountId, chatId, 12)
        val ctx = StringBuilder()
        for (i in 0 until history.length()) {
            val m = history.optJSONObject(i) ?: continue
            if (m.optLong("kind") == 3L) continue
            ctx.append(if (m.optString("direction") == "out") "卖家" else "买家").append(": ").append(m.optString("content")).append('\n')
        }
        val user = """商品信息：
商品标题: $title
商品价格: ${price}元
商品描述: $desc

对话历史：
$ctx
议价设置：
- 当前议价次数：$bargain
- 最大议价轮数：${cfg.maxBargainRounds}
- 最大优惠百分比：${cfg.maxDiscountPercent}%
- 最大优惠金额：${cfg.maxDiscountAmount}元${if (floor.isNotBlank()) "\n- 最低成交价（绝不能低于）：${floor}元" else ""}

用户消息：$msg

请直接给出回复内容（不要解释）："""
        val (reply, err) = AiClient.chat(cfg, AiClient.prompt(context, intent), user)
        if (reply == null) Log.w(TAG, "AI 回复失败: $err")
        // 模型判定无需回复时返回空
        val clean = reply?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() && it != "no_reply" }
        return Decision(clean, "ai-$intent")
    }

    /** 敏感词：replace 模式替换为设定值（默认 ***），block 模式命中则不发。返回 null 表示拦截。 */
    fun filterSensitive(context: Context, text: String): String? {
        val words = OpsDatabase.get(context).sensitiveWords()
        if (words.isEmpty()) return text
        val block = CardDatabase.get(context).getSetting("sensitive_mode", "replace") == "block"
        var out = text
        for ((w, rep) in words) {
            if (w.isBlank() || !out.contains(w, ignoreCase = true)) continue
            if (block) return null
            out = out.replace(w, rep.ifBlank { "*".repeat(w.length.coerceAtMost(6)) }, ignoreCase = true)
        }
        return out
    }
}
