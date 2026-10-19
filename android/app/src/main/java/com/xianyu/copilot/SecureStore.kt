package com.xianyu.copilot

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 账号 Cookie 静态加密（AES-GCM + Android Keystore）。
 *
 * 修复背景：旧版 Cookie 以明文写进 SQLite，且 allowBackup=true，
 * 一旦手机备份、root 或数据库文件被读取，账号就被完全接管。
 *
 * 设计要点：
 *  - 密钥存在 Android Keystore（不可导出，硬件级保护）；
 *  - 密文格式 "enc:v1:" + base64(iv || ciphertext)，带版本前缀便于兼容明文旧数据；
 *  - 解密失败（例如密钥失效 / 换机器恢复备份）一律返回原文，
 *    绝不因为加解密问题导致 APP 崩溃或账号数据凭空丢失。
 */
object SecureStore {

    private const val TAG = "SecureStore"
    private const val KEY_ALIAS = "xianyu_copilot_cookie_key"
    private const val PREFIX = "enc:v1:"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12

    /** 加密；已加密或空串直接原样返回。失败时降级为明文（保证可用性）。 */
    fun encrypt(plain: String?): String {
        if (plain.isNullOrEmpty()) return ""
        if (plain.startsWith(PREFIX)) return plain
        return try {
            val key = getOrCreateKey() ?: return plain
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val packed = ByteArray(iv.size + ct.size)
            System.arraycopy(iv, 0, packed, 0, iv.size)
            System.arraycopy(ct, 0, packed, iv.size, ct.size)
            PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "encrypt failed, fallback to plain", e)
            plain
        }
    }

    /** 解密；非密文（旧版明文数据）原样返回。 */
    fun decrypt(stored: String?): String {
        if (stored.isNullOrEmpty()) return ""
        if (!stored.startsWith(PREFIX)) return stored
        return try {
            val key = getOrCreateKey() ?: return stored
            val packed = Base64.decode(stored.removePrefix(PREFIX), Base64.NO_WRAP)
            if (packed.size <= IV_BYTES) return ""
            val iv = packed.copyOfRange(0, IV_BYTES)
            val ct = packed.copyOfRange(IV_BYTES, packed.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (e: Exception) {
            // 密钥失效 / 换机恢复备份：返回原文，避免数据凭空丢失
            Log.w(TAG, "decrypt failed, return raw", e)
            stored
        }
    }

    private fun getOrCreateKey(): SecretKey? {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
                ?: run {
                    val kg = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES,
                        ANDROID_KEYSTORE
                    )
                    kg.init(
                        KeyGenParameterSpec.Builder(
                            KEY_ALIAS,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                        )
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setKeySize(256)
                            .build()
                    )
                    kg.generateKey()
                }
        } catch (e: Exception) {
            Log.w(TAG, "keystore unavailable", e)
            null
        }
    }
}
