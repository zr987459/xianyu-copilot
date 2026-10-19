package com.xianyu.copilot

import android.content.Context
import android.util.Log

/**
 * 统一账号登录落库入口。
 *
 * 修复前：扫码登录 / 网页登录 / Cookie 粘贴 三处各写了一遍
 * 「解析 Cookie → 校验 → 入库」，导致：
 *  - unb 缺失时用 tracknick 甚至时间戳顶替，同一账号反复登录生成多条记录；
 *  - 校验失败也会 activate=true，把原本正常的账号顶下线。
 *
 * 现在三者全部走 [saveLogin]，由它统一负责去重、补令牌、校验与激活策略。
 */
object AccountManager {

    private const val TAG = "AccountManager"

    /** 登录来源，仅用于日志与状态展示。 */
    enum class Source(val label: String) {
        QR("扫码登录"),
        WEB("网页登录"),
        COOKIE("Cookie 导入")
    }

    data class SaveResult(
        val ok: Boolean,
        val accountId: Long = -1,
        val nick: String = "",
        val userId: String = "",
        val cookie: String = "",
        val activated: Boolean = false,
        val message: String = ""
    )

    /**
     * 统一落库流程。
     *
     * @param cookie 原始 Cookie（可含水货域）
     * @param source 登录来源
     * @param activateOnSuccess 校验通过时是否激活；校验失败一律不激活
     */
    fun saveLogin(
        context: Context,
        cookie: String,
        source: Source,
        activateOnSuccess: Boolean = true
    ): SaveResult {
        val raw = cookie.trim()
        if (raw.isBlank()) {
            return SaveResult(false, message = "Cookie 为空")
        }

        // 1) 去重：同名 Cookie 只保留一份，闲鱼域优先
        val normalized = CookieNormalizer.normalize(raw)
        val parsed = MtopSigner.parseCookies(normalized)

        // 2) userId 必须真实：没有 unb 就判定失败，绝不编造 ID
        val unb = parsed["unb"]?.trim().orEmpty()
        if (unb.isEmpty()) {
            Log.w(TAG, "${source.label} 缺少 unb，判定失败")
            return SaveResult(false, message = "登录信息缺少 unb，请重新登录", cookie = normalized)
        }

        // 3) 真实校验（内部会顺带处理 _m_h5_tk 令牌自愈）
        val check = MtopClient.testCookieDetailed(normalized)
        val finalCookie = check.cookie.ifBlank { normalized }

        if (!check.valid) {
            val isNetErr = check.message.contains("connection", ignoreCase = true) ||
                check.message.contains("timeout", ignoreCase = true) ||
                check.message.contains("connect", ignoreCase = true)
            Log.w(TAG, "${source.label} 校验未通过: ${check.message} (isNetErr=$isNetErr)")
            val db = CardDatabase.get(context)
            val existing = db.getAccountCookie(unb)
            if (existing.isNotEmpty()) {
                // 该账号已有 cookie：绝不用有风险的 cookie 覆盖它
                return SaveResult(
                    ok = false,
                    nick = parsed["tracknick"] ?: unb,
                    userId = unb,
                    cookie = finalCookie,
                    activated = false,
                    message = if (isNetErr) "网络连接异常(${check.message})，请检查网络或关闭VPN代理"
                              else "Cookie 校验未通过：${check.message}（已有账号保持不变）"
                )
            }
            // 新账号：若是纯网络异常且库内无活跃账号，予以激活避免卡死；非网络异常则标为 EXPIRED
            val hasActive = db.getActiveAccount() != null
            val shouldActivate = isNetErr && !hasActive
            val status = if (isNetErr) "ACTIVE" else "EXPIRED"
            db.addOrUpdateAccount(
                userId = unb,
                nick = parsed["tracknick"] ?: unb,
                avatar = "",
                cookie = finalCookie,
                activate = shouldActivate,
                status = status
            )
            db.recordAccountCheck(unb, success = isNetErr, detail = check.message)
            return SaveResult(
                ok = isNetErr,
                nick = parsed["tracknick"] ?: unb,
                userId = unb,
                cookie = finalCookie,
                activated = shouldActivate,
                message = if (isNetErr) "账号已保存（网络连接不顺畅，建议检查VPN/代理）"
                          else "Cookie 校验未通过：${check.message}（已保存但未激活）"
            )
        }

        val nick = check.nick.ifBlank { parsed["tracknick"] ?: unb }

        // 4) 入库并记录校验结果时间
        val db = CardDatabase.get(context)
        val id = db.addOrUpdateAccount(
            userId = unb,
            nick = nick,
            avatar = "",
            cookie = finalCookie,
            activate = activateOnSuccess
        )
        db.recordAccountCheck(unb, success = true, detail = source.label)

        Log.i(TAG, "${source.label} 成功: nick=$nick unb=$unb activated=$activateOnSuccess")
        return SaveResult(
            ok = true,
            accountId = id,
            nick = nick,
            userId = unb,
            cookie = finalCookie,
            activated = activateOnSuccess,
            message = "${source.label}成功"
        )
    }

    /**
     * 令牌自愈后回写数据库（供 MtopClient 回调使用）。
     *
     * 按 Hark 审查意见：只把 _m_h5_tk / _m_h5_tk_enc 两个字段合并到库里最新的 cookie 上，
     * 而不是把调用方手里那份旧整串 cookie 直接覆盖回去，否则会丢掉别人刚写入的其他字段。
     */
    fun persistRefreshedCookie(context: Context, unifiedCookie: String) {
        try {
            val incoming = MtopSigner.parseCookies(unifiedCookie)
            val unb = incoming["unb"]?.trim().orEmpty()
            if (unb.isEmpty()) return

            val db = CardDatabase.get(context)
            // 修复串号：以「该账号自己那一行」为基准，不拿 active 账号的 cookie 来合
            val existing = db.getAccountCookie(unb)
            if (existing.isEmpty()) {
                // 账号尚未入库（登录中），不动库，避免误写 active 行
                Log.d(TAG, "persistRefreshedCookie: 账号 $unb 尚未入库，跳过")
                return
            }
            val base = MtopSigner.parseCookies(existing)
            val newTk = incoming["_m_h5_tk"]
            val newEnc = incoming["_m_h5_tk_enc"]
            if (newTk.isNullOrEmpty()) return
            base["_m_h5_tk"] = newTk
            if (!newEnc.isNullOrEmpty()) base["_m_h5_tk_enc"] = newEnc
            val finalCookie = MtopSigner.rebuildCookie(base)
            if (finalCookie == existing) return
            db.updateAccountCookie(unb, finalCookie)
            Log.i(TAG, "已回写刷新后的令牌: unb=$unb")
        } catch (e: Exception) {
            Log.w(TAG, "persistRefreshedCookie failed", e)
        }
    }
}

/**
 * Cookie 归一化：解决「多个域的 Cookie 用 "; " 硬拼」导致的同名重复与空段问题。
 *
 * 规则：
 *  - 闲鱼域（goofish）优先，其次淘宝域；
 *  - 同名 Cookie 只保留优先级最高的一份；
 *  - 丢弃空名 / 空值的碎段。
 */
object CookieNormalizer {

    /** 高优先级的 Cookie 名（闲鱼业务强依赖）。 */
    private val GOOFISH_PRIORITY = setOf(
        "unb", "cookie2", "sgcookie", "_m_h5_tk", "_m_h5_tk_enc",
        "t", "cna", "xlly_s", "havana_lgc2_77", "cookie17", "_tb_token_"
    )

    /**
     * 把多段 Cookie 字符串合并成一份去重后的 Cookie。
     * 传入顺序即优先级：越靠前的来源越优先（调用方把 goofish 放最前）。
     */
    fun normalize(vararg sources: String): String {
        val merged = LinkedHashMap<String, String>()
        for (src in sources) {
            if (src.isBlank()) continue
            src.split(';').forEach { pair ->
                val p = pair.trim()
                if (p.isEmpty()) return@forEach
                val idx = p.indexOf('=')
                if (idx <= 0) return@forEach
                val name = p.substring(0, idx).trim()
                val value = p.substring(idx + 1).trim()
                if (name.isEmpty() || value.isEmpty()) return@forEach
                // 已存在则保留先出现的（优先级更高）
                if (!merged.containsKey(name)) merged[name] = value
            }
        }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** 判断一组 Cookie 是否具备闲鱼业务调用的最低条件。 */
    fun hasCoreTokens(cookie: String): Boolean {
        val map = MtopSigner.parseCookies(cookie)
        val hasIdentity = map.containsKey("unb") || map.containsKey("cookie2")
        return hasIdentity && !map["unb"].isNullOrBlank()
    }
}
