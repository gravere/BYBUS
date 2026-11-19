package com.example.limbuszhcn.translation

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class AiKeyChoice(val id: String, val name: String, val endpoint: String, val model: String)

/** Only non-secret names and connection settings leave the credential store. */
internal class AiSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("ai-translation-private", Context.MODE_PRIVATE)
    fun config() = AiConfig(prefs.getString("endpoint", null) ?: AiConfig().endpoint,
        prefs.getString("model", null) ?: AiConfig().model, prefs.getBoolean("missingOnly", true), prefs.getInt("limit", 100),
        prefs.getBoolean("includeMissing", true), prefs.getBoolean("includeReview", false),
        prefs.getString("prompt", null) ?: AiTranslationPrompt.DEFAULT)
    private class Entry(val choice: AiKeyChoice, val encrypted: String)

    private fun entries(): MutableList<Entry> {
        val json = prefs.getString("keys", null)
        if (json == null) {
            val legacy = prefs.getString("credential", null) ?: return mutableListOf()
            val c = config()
            return mutableListOf(Entry(AiKeyChoice("legacy", "原有密钥", c.endpoint, c.model), legacy))
        }
        val array = JSONArray(json)
        return MutableList(array.length()) { i ->
            val obj = array.getJSONObject(i)
            Entry(AiKeyChoice(obj.getString("id"), obj.getString("name"), obj.getString("endpoint"),
                obj.getString("model")), obj.getString("encrypted"))
        }
    }

    private fun activeId(): String? = prefs.getString("activeKey", null)
        ?: if (!prefs.contains("keys") && prefs.contains("credential")) "legacy" else null

    fun choices(): Pair<String?, List<AiKeyChoice>> = synchronized(lock) {
        activeId() to entries().map { it.choice }
    }

    fun hasKey() = synchronized(lock) { activeId() != null }

    private fun commit(edit: SharedPreferences.Editor, keys: List<Entry>, selected: String?) {
        val array = JSONArray()
        for (entry in keys) {
            val choice = entry.choice
            array.put(JSONObject().put("id", choice.id).put("name", choice.name).put("endpoint", choice.endpoint)
                .put("model", choice.model).put("encrypted", entry.encrypted))
        }
        check(edit.putString("keys", array.toString()).putString("activeKey", selected)
            .remove("credential").commit()) { "密钥未保存，请重试" }
    }

    private fun storeConnection(edit: SharedPreferences.Editor, endpoint: String, model: String, raw: String, name: String) {
        val keys = entries()
        val oldId = activeId()
        var active = keys.find { it.choice.id == oldId && it.choice.endpoint == endpoint }
        if (raw.isNotEmpty()) {
            require(raw.length <= 1024 && raw.none(Char::isWhitespace)) { "API Key 格式无效" }
            require(name.length <= 48 && name.none(Char::isISOControl) && !name.contains(raw)) { "请填写简短的密钥名称" }
            val same = keys.find { it.choice.endpoint == endpoint && runCatching { decrypt(it) }.getOrNull() == raw }
            val id = same?.choice?.id ?: UUID.randomUUID().toString()
            val label = name.ifEmpty { same?.choice?.name ?: generateSequence(1) { it + 1 }.map { "密钥 $it" }
                .first { candidate -> keys.none { it.choice.name == candidate } } }
            require(keys.none { it.choice.id != id && it.choice.name == label }) { "已有同名密钥，请换一个名称" }
            val choice = AiKeyChoice(id, label, endpoint, model)
            active = Entry(choice, same?.encrypted ?: encrypt(choice, raw))
        } else if (active != null) {
            active = Entry(active.choice.copy(model = model), active.encrypted)
        }
        if (active != null) {
            val index = keys.indexOfFirst { it.choice.id == active.choice.id }
            if (index < 0) keys.add(active) else keys[index] = active
        }
        commit(edit.putString("endpoint", endpoint).putString("model", model), keys, active?.choice?.id)
    }

    fun saveKey(endpoint: String, model: String, raw: String, name: String) = synchronized(lock) {
        val address = normalizedAiEndpoint(endpoint)
        require(raw.isNotBlank()) { "请填写 API Key" }
        val recentModel = entries().firstOrNull { it.choice.endpoint == address }?.choice?.model.orEmpty()
        storeConnection(prefs.edit(), address, model.trim().takeIf(::validAiModelId) ?: recentModel, raw.trim(), name.trim())
    }

    fun selectKey(id: String) = synchronized(lock) {
        val keys = entries()
        val choice = keys.firstOrNull { it.choice.id == id }?.choice ?: error("密钥已移除")
        commit(prefs.edit().putString("endpoint", choice.endpoint).putString("model", choice.model), keys, id)
    }
    /** A connection test reads credentials but never commits configuration. */
    fun test(config: AiConfig, replacement: String, transport: AiHttpTransport = AiHttpTransport(AiTranslationClient::post)): AiResult {
        val settings = config.validated()
        val credential = requestKey(settings.endpoint, replacement)
        return AiTranslationClient(settings, credential, transport).translate(listOf(AiText("connection", "接続中…")))
    }
    fun save(config: AiConfig, replacement: String) = synchronized(lock) {
        val c = config.validated()
        val edit = prefs.edit()
            .putBoolean("missingOnly", c.missingOnly).putInt("limit", c.limit)
            .putBoolean("includeMissing", c.includeMissing).putBoolean("includeReview", c.includeReview).putString("prompt", c.prompt)
        storeConnection(edit, c.endpoint, c.model, replacement.trim(), "")
    }
    fun clearKey(id: String? = activeId()) = synchronized(lock) {
        val kept = entries().filterNot { it.choice.id == id }
        commit(prefs.edit(), kept, activeId()?.takeUnless { it == id })
    }
    fun savePrompt(value: String) {
        check(prefs.edit().putString("prompt", AiTranslationPrompt.validate(value)).commit()) { "提示词未保存，请重试" }
    }
    fun requestKey(endpoint: String, replacement: String): String = synchronized(lock) {
        aiModelsKey(endpoint, config().endpoint, replacement.trim()) { if (hasKey()) key(endpoint) else "" }
    }

    fun key(endpoint: String = config().endpoint): String = synchronized(lock) {
        val address = normalizedAiEndpoint(endpoint)
        val chosen = entries().firstOrNull { it.choice.id == activeId() && it.choice.endpoint == address }
            ?: error("请先选择此服务的密钥")
        decrypt(chosen)
    }

    private fun encrypt(choice: AiKeyChoice, value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        cipher.updateAAD((choice.id + "\n" + choice.endpoint).toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return "2." + Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(entry: Entry): String {
        try {
            val bytes = Base64.decode(entry.encrypted.removePrefix("2."), Base64.NO_WRAP)
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            if (entry.encrypted.startsWith("2.")) {
                cipher.updateAAD((entry.choice.id + "\n" + entry.choice.endpoint).toByteArray(Charsets.UTF_8))
            }
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
    companion object {
        private val lock = Any()
        private const val ALIAS = "bybus.ai.credential.v1"
    }
}
