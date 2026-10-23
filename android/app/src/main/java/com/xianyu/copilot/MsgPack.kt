package com.xianyu.copilot

import org.json.JSONArray
import org.json.JSONObject

/**
 * 极简 MessagePack 解码器（只读），把闲鱼 IM 同步包里 base64 + msgpack 编码的消息
 * 还原成 org.json 结构，便于后续按字段路径（如 "1" -> "10" -> "reminderContent"）取值。
 *
 * 移植自 xianyu-auto-reply / xianyu-guanjia 的 utils/xianyu_utils.MessagePackDecoder（MIT）。
 * map 的 key 一律转字符串（整数 key 1 → "1"），与参考实现 json.dumps 后的结构一致。
 */
object MsgPack {

    class Reader(private val data: ByteArray) {
        private var pos = 0

        private fun u8(): Int {
            if (pos >= data.size) throw IllegalStateException("msgpack: unexpected end")
            return data[pos++].toInt() and 0xFF
        }

        private fun bytes(n: Int): ByteArray {
            if (n < 0 || pos + n > data.size) throw IllegalStateException("msgpack: unexpected end")
            val out = data.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        private fun u16(): Int = (u8() shl 8) or u8()
        private fun u32(): Long = ((u8().toLong() shl 24) or (u8().toLong() shl 16) or (u8().toLong() shl 8) or u8().toLong())
        private fun i64(): Long {
            var v = 0L
            repeat(8) { v = (v shl 8) or u8().toLong() }
            return v
        }

        private fun str(n: Int): String = String(bytes(n), Charsets.UTF_8)

        fun value(): Any? {
            val b = u8()
            return when {
                b <= 0x7f -> b
                b in 0x80..0x8f -> map(b and 0x0f)
                b in 0x90..0x9f -> array(b and 0x0f)
                b in 0xa0..0xbf -> str(b and 0x1f)
                b == 0xc0 -> null
                b == 0xc2 -> false
                b == 0xc3 -> true
                b == 0xc4 -> String(bytes(u8()), Charsets.UTF_8)
                b == 0xc5 -> String(bytes(u16()), Charsets.UTF_8)
                b == 0xc6 -> String(bytes(u32().toInt()), Charsets.UTF_8)
                b == 0xca -> java.lang.Float.intBitsToFloat(u32().toInt()).toDouble()
                b == 0xcb -> java.lang.Double.longBitsToDouble(i64())
                b == 0xcc -> u8()
                b == 0xcd -> u16()
                b == 0xce -> u32()
                b == 0xcf -> i64() // uint64 超过 Long 上限的情况在 IM 中不会出现
                b == 0xd0 -> u8().toByte().toInt()
                b == 0xd1 -> u16().toShort().toInt()
                b == 0xd2 -> u32().toInt()
                b == 0xd3 -> i64()
                b == 0xd9 -> str(u8())
                b == 0xda -> str(u16())
                b == 0xdb -> str(u32().toInt())
                b == 0xdc -> array(u16())
                b == 0xdd -> array(u32().toInt())
                b == 0xde -> map(u16())
                b == 0xdf -> map(u32().toInt())
                // ext 类型：跳过负载
                b == 0xd4 -> { bytes(2); null }
                b == 0xd5 -> { bytes(3); null }
                b == 0xd6 -> { bytes(5); null }
                b == 0xd7 -> { bytes(9); null }
                b == 0xd8 -> { bytes(17); null }
                b == 0xc7 -> { val n = u8(); bytes(n + 1); null }
                b == 0xc8 -> { val n = u16(); bytes(n + 1); null }
                b == 0xc9 -> { val n = u32().toInt(); bytes(n + 1); null }
                b >= 0xe0 -> b - 0x100 // negative fixint
                else -> throw IllegalStateException("msgpack: unknown byte 0x${Integer.toHexString(b)}")
            }
        }

        private fun array(n: Int): JSONArray {
            val arr = JSONArray()
            repeat(n) { arr.put(value() ?: JSONObject.NULL) }
            return arr
        }

        private fun map(n: Int): JSONObject {
            val obj = JSONObject()
            repeat(n) {
                val k = value()?.toString() ?: "null"
                obj.put(k, value() ?: JSONObject.NULL)
            }
            return obj
        }
    }

    /** 解码 base64 + msgpack；失败返回 null。 */
    fun decodeBase64(b64: String): Any? = runCatching {
        var s = b64.trim()
        val pad = s.length % 4
        if (pad != 0) s += "=".repeat(4 - pad)
        val raw = android.util.Base64.decode(s, android.util.Base64.DEFAULT)
        Reader(raw).value()
    }.getOrNull()
}
