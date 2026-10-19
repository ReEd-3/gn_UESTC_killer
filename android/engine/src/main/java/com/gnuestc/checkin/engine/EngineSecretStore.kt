package com.gnuestc.checkin.engine

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [修复 2026-10-19] 引擎侧的敏感信息加密（与主应用 SecretStore 同一方案）：
 * 使用 AndroidKeyStore 中的 AES-256-GCM 密钥（不可导出），密文格式：
 * enc:v1:<Base64(IV)>:<Base64(密文)>。
 * 目前用于保护主应用同步过来的 SESSION Cookie（此前为明文存储）。
 */
object EngineSecretStore {

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "gn_engine_master_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128
    private const val PREFIX = "enc:v1:"

    /** 判断字符串是否为本工具生成的密文 */
    fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    /** 加密；Keystore 不可用时返回 null（由调用方决定降级策略） */
    fun encrypt(plain: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        buildString {
            append(PREFIX)
            append(Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            append(':')
            append(Base64.encodeToString(ct, Base64.NO_WRAP))
        }
    }.getOrNull()

    /** 解密；非密文或解密失败返回 null */
    fun decrypt(stored: String): String? {
        if (!isEncrypted(stored)) return null
        return try {
            val parts = stored.removePrefix(PREFIX).split(":")
            if (parts.size != 2) {
                null
            } else {
                val iv = Base64.decode(parts[0], Base64.NO_WRAP)
                val ct = Base64.decode(parts[1], Base64.NO_WRAP)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                String(cipher.doFinal(ct), Charsets.UTF_8)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** 获取或创建密钥库中的主密钥（多线程安全；密钥只在系统密钥库中，不进应用文件） */
    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
