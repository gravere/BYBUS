package com.example.limbuszhcn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.content.ContextWrapper
import com.example.limbuszhcn.translation.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises Android ICU and org.json, with an in-memory API response and no network request. */
@RunWith(AndroidJUnit4::class)
class AiPlatformTest {
    @Test fun keysPersistIndependentlyOfModelsAndStayWithTheirService() {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("keyring-probe-$name", mode)
        }
        val prefs = context.getSharedPreferences("ai-translation-private", 0)
        prefs.edit().clear().commit()
        val endpoint = "https://example.test/v1"
        val otherPath = "https://example.test/another/v1"
        try {
            val store = AiSettingsStore(context)
            store.saveKey(endpoint, "", "fixture-one", "个人")
            val first = requireNotNull(store.choices().first)
            assertEquals("", store.config().model)
            store.saveKey(endpoint, "demo-a", "fixture-two", "工作")
            val second = requireNotNull(store.choices().first)
            store.saveKey(otherPath, "demo-b", "fixture-three", "其他接口")
            val third = requireNotNull(store.choices().first)
            val reopened = AiSettingsStore(context)
            assertEquals(3, reopened.choices().second.size)
            assertEquals("fixture-three", reopened.key(otherPath))
            assertEquals("", reopened.requestKey(endpoint, ""))
            assertTrue(runCatching { reopened.key(endpoint) }.isFailure)
            reopened.selectKey(second)
            assertEquals("demo-a", reopened.config().model)
            assertEquals("fixture-two", reopened.key(endpoint))
            val encryptedBefore = prefs.getString("keys", "")!!
            reopened.renameKey(second, "备用服务")
            reopened.reorderKeys(listOf(third, second, first))
            val again = AiSettingsStore(context)
            assertEquals(listOf(third, second, first), again.choices().second.map { it.id })
            assertEquals(second, again.choices().first)
            assertEquals("备用服务", again.choices().second[1].name)
            assertEquals("fixture-two", again.key(endpoint))
            val beforeValues = org.json.JSONArray(encryptedBefore).let { a -> (0 until a.length()).associate { a.getJSONObject(it).getString("id") to a.getJSONObject(it).getString("encrypted") } }
            val afterValues = org.json.JSONArray(prefs.getString("keys", "")!!).let { a -> (0 until a.length()).associate { a.getJSONObject(it).getString("id") to a.getJSONObject(it).getString("encrypted") } }
            assertEquals(beforeValues, afterValues)
            assertEquals("", reopened.requestKey(otherPath, ""))
            reopened.selectKey(first)
            assertEquals("fixture-one", reopened.key(endpoint))
            assertTrue(runCatching { reopened.saveKey(endpoint, "", "fixture-new", "备用服务") }.isFailure)
            assertEquals(first, reopened.choices().first)
            reopened.clearKey(third)
            assertEquals(first, reopened.choices().first)
            reopened.clearKey(first)
            assertFalse(reopened.hasKey())
            assertEquals(second, reopened.choices().second.single().id)
            assertTrue(prefs.all.values.none { it.toString().contains("fixture-") })
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun legacyEncryptedKeySurvivesAddingAndSwitching() {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("legacy-key-probe-$name", mode)
        }
        val prefs = context.getSharedPreferences("ai-translation-private", 0)
        prefs.edit().clear().commit()
        try {
            val store = AiSettingsStore(context)
            store.saveKey(AiConfig().endpoint, "", "fixture-seed", "准备")
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            val ks = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, ks.getKey("bybus.ai.credential.v1", null))
            val original = android.util.Base64.encodeToString(cipher.iv + cipher.doFinal("fixture-old".toByteArray()), android.util.Base64.NO_WRAP)
            prefs.edit().clear().putString("credential", original).putString("model", "old-model").commit()
            val old = requireNotNull(store.choices().first)
            assertEquals("fixture-old", store.key())
            store.saveKey("https://example.test/v1", "other-model", "fixture-new", "第二把")
            assertFalse(prefs.contains("credential"))
            AiSettingsStore(context).selectKey(old)
            assertEquals("fixture-old", store.key(AiConfig().endpoint))
            assertEquals("old-model", store.config().model)
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun globalPromptPersistsIndependentlyAndRestoresWithoutChangingCredentials() {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("prompt-probe-$name", mode)
        }
        val prefs = context.getSharedPreferences("ai-translation-private", 0)
        prefs.edit().clear().commit()
        try {
            val store = AiSettingsStore(context)
            store.savePrompt("保持角色语气与固定译名。")
            assertFalse(store.hasKey())
            assertEquals("保持角色语气与固定译名。", AiSettingsStore(context).config().prompt)
            store.save(store.config().copy(model = "saved-model"), "non-secret-fixture")
            store.savePrompt(AiTranslationPrompt.DEFAULT)
            assertEquals(AiTranslationPrompt.DEFAULT, AiSettingsStore(context).config().prompt)
            assertEquals("saved-model", store.config().model); assertEquals("non-secret-fixture", store.key())
            assertTrue(runCatching { store.savePrompt(" ") }.isFailure)
            assertEquals(AiTranslationPrompt.DEFAULT, store.config().prompt)
        } finally { prefs.edit().clear().commit() }
    }
    @Test fun testingNeverPersistsDraftSettingsOrCredentialsEvenWhenItFails() {
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences("platform-probe-$name", mode)
        }
        val prefs = context.getSharedPreferences("ai-translation-private", 0)
        prefs.edit().clear().commit()
        try {
            val store = AiSettingsStore(context)
            store.save(AiConfig(), "old-non-secret-fixture")
            val draft = AiConfig(endpoint = "https://example.com/v1", model = "draft")
            val result = store.test(draft, "new-non-secret-fixture", AiHttpTransport { url, key, _ ->
                assertEquals("https://example.com/v1/chat/completions", url)
                assertEquals("new-non-secret-fixture", key)
                """{"choices":[{"finish_reason":"stop","message":{"content":"{\"translations\":[{\"id\":\"connection\",\"text\":\"连接中…\"}]}"}}]}"""
            })
            assertEquals("连接中…", result.translations["connection"])
            assertTrue(runCatching { store.test(draft, "new-non-secret-fixture", AiHttpTransport { _, _, _ -> error("fixture rejection") }) }.isFailure)
            assertEquals(AiConfig(), store.config());assertEquals("old-non-secret-fixture", store.key())
        } finally { prefs.edit().clear().commit() }
    }
    @Test fun androidRegexProtectsPlaceholdersMarkupAndLineBreaks() {
        val original = "<b>攻撃 {0}</b>\n[Burn] 12%"
        val protected = ProtectedTranslation(original)
        assertEquals(original, protected.restore(protected.masked))
        assertTrue(runCatching { protected.restore("错误 999") }.isFailure)
        assertEquals(original, ProtectedTranslation(original).restore(protected.masked))
    }
    @Test fun androidTranslationTestCompletesWithoutSendingNetworkTraffic() {
        var calls = 0
        val client = AiTranslationClient(AiConfig(), "non-secret-fixture", AiHttpTransport { _, _, request ->
            calls++
            val rows = JSONArray(JSONObject(request).getJSONArray("messages").getJSONObject(1).getString("content"))
            assertEquals("接続中…", rows.getJSONObject(0).getString("text"))
            JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message",
                JSONObject().put("content", """{"translations":[{"id":"connection","text":"连接中…"}]}""")))).toString()
        })
        repeat(2) { assertEquals("连接中…", client.translate(listOf(AiText("connection", "接続中…"))).translations["connection"]) }
        assertEquals(2, calls)
    }
}
