package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.ContainerWorkspaceStorage
import com.example.limbuszhcn.container.GameLanguageStore
import com.example.limbuszhcn.gamefs.GameTextLanguage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.writeText

class CoverageLanguageTest {
    @get:Rule val temp = TemporaryFolder()
    private val root get() = temp.root.toPath()
    private fun game(locale: String, texts: List<String>) {
        val dir = Files.createDirectories(root.resolve("Localize/$locale"))
        val data = JSONArray().apply { texts.forEachIndexed { i, text -> put(JSONObject().put("id", i + 1).put("content", text)) } }
        dir.resolve("${locale.uppercase()}_MainUIText.json").writeText(JSONObject().put("dataList", data).toString())
    }
    private fun base(sources: TranslationSources): Pair<TranslationSource, PreparedTranslation> {
        val archive = root.resolve("fixture.zip")
        ZipOutputStream(Files.newOutputStream(archive)).use {
            it.putNextEntry(ZipEntry("MainUIText.json"))
            it.write("""{"dataList":[{"id":1,"content":"开始"}]}""".toByteArray())
            it.closeEntry()
        }
        val source = Files.newInputStream(archive).use { sources.import(it, "fixture.zip") }
        return source to sources.prepare(source)
    }

    @Test fun countsOnlySelectedLanguageAndInvalidatesEvenWhenSwitchingBack() {
        game("jp", listOf("開始する")); game("en", listOf("Start", "Untranslated text"))
        game("kr", listOf("시작", "첫 번째 내용", "두 번째 내용"))
        val cache = root.resolve("cache"); val language = GameLanguageStore(cache)
        val storage = ContainerWorkspaceStorage(root.resolve("Localize"), emptyMap(), language::read)
        val (source, base) = base(TranslationSources(cache)); val store = CoverageStore(cache)
        assertThrows(IllegalArgumentException::class.java) { TranslationCoverage.scan(storage, base, source.name) }
        language.observe("en")
        val english = TranslationCoverage.scan(storage, base, source.name)
        assertEquals(2, english.total); assertEquals(1, english.missing)
        assertEquals(GameTextLanguage.English, english.language)
        assertTrue(english.details.all { it.resource.startsWith("en/") })
        val input = store.inputs(source, storage)
        store.save(input, english, store.beginCheck(input))
        language.observe("en")
        assertFalse(store.read(store.inputs(source, storage))!!.needsCheck)
        language.observe("kr")
        assertNull(store.read(store.inputs(source, storage)))
        val korean = TranslationCoverage.scan(storage, base, source.name)
        assertEquals(3, korean.total); assertEquals(2, korean.missing)
        assertTrue(korean.details.all { it.resource.startsWith("kr/") })
        val next = store.inputs(source, storage)
        store.save(next, korean, store.beginCheck(next))
        language.observe("en")
        val previousEnglish = CoverageStore(cache).read(store.inputs(source, storage))!!
        assertTrue(previousEnglish.needsCheck)
        assertEquals(2, previousEnglish.report.total)
        assertNotEquals(input.fingerprint, store.inputs(source, storage).fingerprint)
        assertEquals("en/StoryData/EN_scene.json", GameTextLanguage.English.resourceKey("kr/StoryData/KR_scene.json"))
    }

    @Test fun englishAndKoreanAiJobsTranslateTheirOwnCheckedRowsAndReimportCleanly() {
        game("jp", listOf("開始する", "日本語だけ"))
        for ((locale, missing) in listOf("en" to "Untranslated text", "kr" to "아직 번역되지 않은 문장")) {
            game(locale, listOf(if (locale == "en") "Start" else "시작", missing))
            val cache = root.resolve("cache-$locale"); val language = GameLanguageStore(cache); language.observe(locale)
            val storage = ContainerWorkspaceStorage(root.resolve("Localize"), emptyMap(), language::read)
            val sources = TranslationSources(cache); val (source, base) = base(sources)
            var calls = 0
            val config = AiConfig(limit = 1)
            val client = AiTranslationClient(config, "fixture", AiHttpTransport { _, _, body ->
                calls++
                val rows = JSONArray(JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content"))
                assertEquals(1, rows.length()); assertEquals(missing, rows.getJSONObject(0).getString("text"))
                val answer = JSONObject().put("translations", JSONArray().put(JSONObject()
                    .put("id", rows.getJSONObject(0).getString("id")).put("text", "补充的中文")))
                JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
                    .put("message", JSONObject().put("content", answer.toString()))))
                    .put("usage", JSONObject().put("prompt_tokens", 1).put("completion_tokens", 1)).toString()
            })
            val result = AiTranslationJob(cache, storage, config, client).run(source.id, base)
            assertEquals(1, calls)
            val generated = sources.addGenerated(result.archive, source.id, result.identity, "补充汉化")
            val report = TranslationCoverage.scan(storage, sources.prepare(generated), generated.name)
            assertEquals(2, report.total); assertEquals(0, report.missing); assertEquals(0, report.review)
        }
    }

    @Test fun pausedTaskCannotChangeItsOriginalLanguageBeforeCreatingThePlan() {
        game("en", listOf("Start", "Untranslated text")); game("kr", listOf("시작", "미번역 문장"))
        val cache = root.resolve("cache"); val language = GameLanguageStore(cache); language.observe("en")
        val storage = ContainerWorkspaceStorage(root.resolve("Localize"), emptyMap(), language::read)
        val sources = TranslationSources(cache); val (_, base) = base(sources)
        val config = AiConfig(limit = 1); val session = AiTaskStore(cache)
        val task = session.createForSelection(config, sources, base, base, null, storage)
        assertEquals("en", task.binding!!.language)
        language.observe("kr")
        val client = AiTranslationClient(config, "fixture", AiHttpTransport { _, _, _ -> error("Must not send a paid request") })
        assertThrows(IllegalArgumentException::class.java) {
            AiTranslationJob(cache, storage, config, client).run(task.baseId, base, task.outputIdentity, session = session)
        }
        assertEquals("en", session.read()!!.binding!!.language)
        assertNull(session.read()!!.plan)
    }
}
