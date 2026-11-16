package com.example.limbuszhcn.translation

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** UI receives only configuration and a presence flag, never the decrypted key. */
internal class AiSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("ai-translation-private", Context.MODE_PRIVATE)
    fun config() = AiConfig(prefs.getString("endpoint", null) ?: AiConfig().endpoint,
        prefs.getString("model", null) ?: AiConfig().model, prefs.getBoolean("missingOnly", true), prefs.getInt("limit", 100))
    fun hasKey() = !prefs.getString("credential", null).isNullOrEmpty()
    /** A connection test reads credentials but never commits configuration. */
    fun test(config: AiConfig, replacement: String, transport: AiHttpTransport = AiHttpTransport(AiTranslationClient::post)): AiResult {
        val settings = config.validated()
        val credential = aiModelsKey(settings.endpoint, this.config().endpoint, replacement) { if (hasKey()) key() else "" }
        return AiTranslationClient(settings, credential, transport).translate(listOf(AiText("connection", "接続中…")))
    }
    fun save(config: AiConfig, replacement: String) {
        val c = config.validated()
        val old = this.config()
        require(c.endpoint == old.endpoint || replacement.isNotBlank() || !hasKey()) {
            "更换接口地址时请重新填写 Key，避免将旧凭据发送到其他服务"
        }
        val edit = prefs.edit().putString("endpoint", c.endpoint).putString("model", c.model)
            .putBoolean("missingOnly", c.missingOnly).putInt("limit", c.limit)
        if (replacement.isNotBlank()) {
            require(replacement.length <= 1024 && replacement.none(Char::isWhitespace)) { "API Key 格式无效" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
            val encrypted = cipher.doFinal(replacement.toByteArray(Charsets.UTF_8))
            edit.putString("credential", Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP))
        }
        check(edit.commit()) { "API 配置未保存，请重试" }
    }
    fun clearKey() { check(prefs.edit().remove("credential").commit()) }
    fun key(): String {
        val encoded = requireNotNull(prefs.getString("credential", null)) { "请先保存 API Key" }
        try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP); require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) { error("无法读取本机 Key，请重新填写；迁移不会携带 API 凭据") }
    }
    private fun encryptionKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }
    companion object { private const val ALIAS = "bybus.ai.credential.v1" }
}
