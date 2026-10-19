package com.xianyu.copilot

import android.util.Log
import java.security.MessageDigest
import java.util.UUID
import kotlin.random.Random

/**
 * 闲鱼 Mtop 签名与 Cookie 工具。全部为纯函数，无状态，可在任意线程调用。
 */
object MtopSigner {

    private const val TAG = "MtopSigner"
    const val DEFAULT_APP_KEY = "34839810"

    /** 纯 Kotlin MD5（字节数组 -> 32 位小写 hex）。 */
    fun md5(input: String): String = md5(input.toByteArray(Charsets.UTF_8))

    fun md5(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        val sb = StringBuilder(32)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        return sb.toString()
    }

    /**
     * Mtop 签名算法：md5(token + "&" + t + "&" + appKey + "&" + data)
     * token 为 Cookie 中 _m_h5_tk 的下划线前部分；未登录时为空串。
     */
    fun generateSign(token: String, t: String, appKey: String, data: String): String =
        md5("$token&$t&$appKey&$data")

    /** 毫秒时间戳字符串，作为 mtop 的 t 参数。 */
    fun nowT(): String = System.currentTimeMillis().toString()

    /** 32 位小写十六进制随机串，用作设备标识。 */
    fun generateDeviceId(): String {
        val raw = buildString(32) {
            repeat(32) { append(HEX[Random.nextInt(16)]) }
        }
        return raw
    }

    /** 闲鱼/淘宝 mid：常见形态为 24~36 位 hex，这里生成稳定可用的 36 位。 */
    fun generateMid(): String = UUID.randomUUID().toString().replace("-", "")

    /** 标准 UUID。 */
    fun generateUuid(): String = UUID.randomUUID().toString()

    private val HEX = "0123456789abcdef".toCharArray()

    /**
     * 解析 Cookie 字符串为 name->value 映射。
     * 容忍 "k=v; k2=v2"、多余分号/空格、值中含 '=' 的情况。
     */
    fun parseCookies(cookieStr: String?): MutableMap<String, String> {
        val map = LinkedHashMap<String, String>()
        if (cookieStr.isNullOrBlank()) return map
        cookieStr.split(';').forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) return@forEach
            val name = pair.substring(0, idx).trim()
            val value = pair.substring(idx + 1).trim()
            if (name.isNotEmpty()) map[name] = value
        }
        return map
    }

    /**
     * 从 Cookie 中抽取 _m_h5_tk 的下划线前部分作为 token。
     * 例如 "_m_h5_tk=abc123_1699999999999" -> "abc123"。
     * 不存在或格式异常时返回空串（未登录场景签名 token 为空）。
     */
    fun extractToken(cookieStr: String?): String {
        val value = parseCookies(cookieStr)["_m_h5_tk"] ?: return ""
        val idx = value.indexOf('_')
        return if (idx > 0) value.substring(0, idx) else value
    }

    /** 重新拼接 Cookie；会剔除过期的 _m_h5_tk（后续由 Set-Cookie 刷新）。 */
    fun rebuildCookie(cookies: Map<String, String>, dropKeys: Set<String> = emptySet()): String =
        cookies.entries
            .filter { it.key !in dropKeys }
            .joinToString("; ") { "${it.key}=${it.value}" }

    /** 安全兜底：任何签名异常都不向外抛。 */
    fun safeSign(token: String, t: String, appKey: String, data: String): String =
        try {
            generateSign(token, t, appKey, data)
        } catch (e: Exception) {
            Log.e(TAG, "generateSign failed", e)
            ""
        }
}
