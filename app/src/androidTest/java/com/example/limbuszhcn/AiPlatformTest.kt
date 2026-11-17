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
