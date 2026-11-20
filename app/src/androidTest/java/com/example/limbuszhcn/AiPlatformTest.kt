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
    @Test fun androidRestartReusesGoodAnswersFromAPartiallyRejectedBatch() {
        val parent = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.toPath()
        val root = java.nio.file.Files.createTempDirectory(parent, "ai-partial-test-")
        try {
            val localize = java.nio.file.Files.createDirectories(root.resolve("Localize/jp"))
            val raw = """{"dataList":[{"id":1,"content":"First"},{"id":2,"content":"Second"},{"id":3,"content":"Third"}]}"""
            java.nio.file.Files.write(localize.resolve("JP_MainUIText.json"), raw.toByteArray())
            val storage = com.example.limbuszhcn.container.ContainerWorkspaceStorage(localize.parent)
            val tasks = AiTaskStore(root); val config = AiConfig(missingOnly = false, limit = 3)
            val task = tasks.create(config, TranslationSource("global", "全局 AI 翻译", independent = true), null, null)
            var calls = 0; val counts = mutableListOf<Int>()
            val client = AiTranslationClient(config, "non-secret-fixture", AiHttpTransport { _, _, body ->
                calls++
                val input = JSONArray(JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content"))
                counts += input.length()
                val output = JSONArray()
                repeat(input.length()) { i -> output.put(JSONObject().put("id", input.getJSONObject(i).getString("id"))
                    .put("text", if (calls == 1 && i == 1) "错误999" else "中文译文")) }
                JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop").put("message",
                    JSONObject().put("content", JSONObject().put("translations", output).toString())))).toString()
            })
            assertTrue(runCatching {
                AiTranslationJob(root, storage, config, client).run(task.baseId, null, task.outputIdentity, session = tasks)
            }.exceptionOrNull() is AiBatchValidationException)
            assertEquals(2, tasks.read()!!.progress.completed)
            val fresh = AiTaskStore(root)
            assertEquals(AiTaskPhase.Paused, fresh.recover(TranslationSources(root).read())!!.phase)
            assertEquals(1, calls)
            val result = AiTranslationJob(root, storage, config, client)
                .run(task.baseId, null, task.outputIdentity, session = fresh)
            assertEquals(listOf(3, 1), counts)
            assertEquals(2, result.progress.reused); assertEquals(1, result.progress.completed)
            assertEquals(3, result.aiEntryCount)
            assertTrue(java.nio.file.Files.isRegularFile(result.archive))
        } finally { com.example.limbuszhcn.storage.AtomicFiles.deleteOwnedTree(parent, root) }
    }

    @Test fun androidPartialValidationKeepsGoodRowsAndNormalizesOnlyNewWhitespace() {
        val source = "<b>Burn</b> {0}\n2%\\nNext"
        val protected = ProtectedTranslation(source)
        val text = protected.masked.replace("Burn", "烧伤\n效果")
        val rows = JSONArray().put(JSONObject().put("id", "good").put("text", text))
            .put(JSONObject().put("id", "bad").put("text", "错误9"))
        var calls = 0
        val result = AiTranslationClient(AiConfig(), "non-secret-fixture", AiHttpTransport { _, _, _ ->
            calls++
            JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("content", JSONObject().put("translations", rows).toString())))).toString()
        }).translate(listOf(AiText("good", source), AiText("bad", "Wrong"), AiText("missing", "Wait")), allowPartial = true)
        assertEquals(1, calls)
        assertEquals(mapOf("good" to source.replace("Burn", "烧伤 效果")), result.translations)
        assertEquals(result.translations["good"], protected.restore(result.protectedTranslations.getValue("good")))
        assertEquals(listOf(AiValidationReason.AddedNumber, AiValidationReason.Missing), result.rejected.map { it.reason })
    }

    @Test fun androidJsonCannotSilentlyOverwriteDuplicateKeysOrAcceptDeepOrLenientData() {
        for (text in listOf("{\"a\":1,\"a\":2}", "{\"a\":1,\"\\u0061\":2}",
            "{unquoted:1}", "{'a':1}", "{\"a\":1,}", "{\"a\":[1,]}", "{\"a\":NaN}",
            "[".repeat(40) + "0" + "]".repeat(40))) {
            assertTrue(text, runCatching { parseAiResponseObject(text) }.exceptionOrNull() is AiResponseFormatException)
        }
    }

    @Test fun androidFencedResponsesPreserveGameDataAndRejectIncompleteObjects() {
        val source = "<b>攻撃 {0}</b>\n[Burn] 12%"
        val masked = ProtectedTranslation(source).masked.replace("攻撃", "攻击")
        val json = JSONObject().put("translations", JSONArray().put(JSONObject()
            .put("id", "entry").put("text", masked))).toString()
        fun run(content: String) = AiTranslationClient(AiConfig(), "non-secret-fixture", AiHttpTransport { _, _, _ ->
            JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                .put("message", JSONObject().put("content", content)))).toString()
        }).translate(listOf(AiText("entry", source)))
        for (content in listOf(json, "```json\n$json\n```", "```\r\n$json\r\n```"))
            assertEquals(source.replace("攻撃", "攻击"), run(content).translations["entry"])
        for (content in listOf("```json\n$json", "$json trailing", "$json$json"))
            assertTrue(runCatching { run(content) }.exceptionOrNull() is AiResponseFormatException)
        assertTrue(runCatching { run("```json\n${json.replace("⟦P0⟧", "")}\n```") }.isFailure)
    }

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
