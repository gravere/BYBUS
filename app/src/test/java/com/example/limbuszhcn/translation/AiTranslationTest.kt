package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.ContainerWorkspaceStorage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import kotlin.io.path.writeText

class AiTranslationTest {
    @get:Rule val temp = TemporaryFolder()
    private fun response(rows: JSONArray, finish: String = "stop") = JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("finish_reason", finish)
            .put("message", JSONObject().put("content", JSONObject().put("translations", rows).toString()))))
        .put("usage", JSONObject().put("prompt_tokens", 20).put("completion_tokens", 10)).toString()

    @Test fun protectedNumbersFormattingAndParagraphsAreRestoredExactly() {
        val p = ProtectedTranslation("<b>火傷</b> {0} 12%\n次のターン")
        val translated = p.masked.replace("火傷", "烧伤").replace("次のターン", "下一回合")
        assertEquals("<b>烧伤</b> {0} 12%\n下一回合", p.restore(translated))
        assertThrows(IllegalArgumentException::class.java) { p.restore(translated.replace("⟦P2⟧", "")) }
        assertThrows(IllegalArgumentException::class.java) { p.restore(translated + "增加5点") }
        assertThrows(IllegalArgumentException::class.java) { p.restore(translated + "<b>附加</b>") }
    }

    @Test fun numberedArgumentsCanMoveForChineseGrammarButMarkupCannotBeReversed() {
        val p = ProtectedTranslation("<b>火傷</b>を{0}付与する。\n威力が2増加する。")
        val masked = "施加⟦P2⟧层⟦P0⟧烧伤⟦P1⟧。⟦P3⟧威力增加⟦P4⟧。"
        val value = p.restore(masked)
        assertEquals("施加{0}层<b>烧伤</b>。\n威力增加2。", value)
        assertEquals(masked, p.maskTranslated(value))
        assertThrows(IllegalArgumentException::class.java) {
            p.restore("⟦P1⟧烧伤⟦P0⟧⟦P2⟧⟦P3⟧⟦P4⟧")
        }
    }

    @Test fun gameKeywordKeysAndSignedValuesCannotBeRewritten() {
        val p = ProtectedTranslation("[Burn] 威力+2、回数-1")
        assertEquals("[Burn] 强度+2、次数-1", p.restore(p.masked.replace("威力", "强度").replace("回数", "次数")))
        assertThrows(IllegalArgumentException::class.java) { p.restore("[Burn]⟦P1⟧⟦P2⟧") }
        assertThrows(IllegalArgumentException::class.java) { p.restore(p.masked + "[Bleed]") }
    }

    @Test fun plainDataAndExactIdsAreRequiredBeforeAnyTranslationCanBeUsed() {
        var calls = 0
        val good = AiTranslationClient(AiConfig(), "fixture", AiHttpTransport { _, _, body ->
            calls++
            val request = JSONObject(body)
            assertEquals("json_object", request.getJSONObject("response_format").getString("type"))
            response(JSONArray().put(JSONObject().put("id", "a").put("text", "连接中")))
        })
        assertEquals("连接中", good.translate(listOf(AiText("a", "接続中"))).translations["a"])
        assertEquals(1, calls)
        for (body in listOf(response(JSONArray().put(JSONObject().put("id", "wrong").put("text", "连接中"))),
            response(JSONArray(), "length"), response(JSONArray()))) {
            val bad = AiTranslationClient(AiConfig(), "fixture", AiHttpTransport { _, _, _ -> body })
            assertThrows(Exception::class.java) { bad.translate(listOf(AiText("a", "接続中"))) }
        }
    }

    @Test fun invalidEndpointsAreRejectedBeforeSendingCredentials() {
        for (url in listOf("http://example.com/v1", "https://user:secret@example.com/v1", "https://example.com/v1?key=x", "https://example.com:8080/v1")) {
            val client = AiTranslationClient(AiConfig(endpoint = url), "fixture", AiHttpTransport { _, _, _ -> error("must not send") })
            assertThrows(IllegalArgumentException::class.java) { client.translate(listOf(AiText("a", "接続中"))) }
        }
    }

    @Test fun limitedJobsResumeFromValidatedCacheWithoutChangingMetadata() {
        val cache = temp.root.toPath().resolve("cache")
        val localize = temp.root.toPath().resolve("Localize"); Files.createDirectories(localize.resolve("jp"))
        val original = """{"dataList":[{"id":1,"content":"接続中","power":7},{"id":2,"content":"開始する","power":9}]}"""
        localize.resolve("jp/JP_MainUIText.json").writeText(original)
        var calls = 0
        val config = AiConfig(limit = 1, missingOnly = false)
        val client = AiTranslationClient(config, "fixture", AiHttpTransport { _, _, body ->
            calls++
            val inputs = JSONArray(JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content"))
            val rows = JSONArray()
            for (i in 0 until inputs.length()) rows.put(JSONObject().put("id", inputs.getJSONObject(i).getString("id")).put("text", "已翻译"))
            response(rows)
        })
        val storage = ContainerWorkspaceStorage(localize)
        val first = AiTranslationJob(cache, storage, config, client).run("zero", null)
        assertEquals(1, calls); assertEquals(1, first.remaining)
        val partial = java.util.zip.ZipFile(first.archive.toFile()).use { zip ->
            JSONObject(zip.getInputStream(zip.getEntry("jp/JP_MainUIText.json")).bufferedReader().readText())
        }
        assertFalse(partial.getJSONArray("dataList").getJSONObject(1).has("content"))
        val second = AiTranslationJob(cache, storage, config, client).run("zero", null)
        assertEquals(2, calls); assertEquals(1, second.progress.reused); assertEquals(0, second.remaining)
        val third = AiTranslationJob(cache, storage, config, client).run("zero", null)
        assertEquals(2, calls); assertEquals(2, third.progress.reused)
        val sources = TranslationSources(cache)
        val source = sources.addGenerated(third.archive, "zero", third.identity, "AI 汉化")
        val prepared = sources.prepare(source)
        val result = JSONObject(String(Files.readAllBytes(prepared.directory.resolve(prepared.manifest.entries.single().cachePath))))
        assertEquals(7, result.getJSONArray("dataList").getJSONObject(0).getInt("power"))
        assertEquals(2, result.getJSONArray("dataList").getJSONObject(1).getInt("id"))
        assertEquals(original, String(Files.readAllBytes(localize.resolve("jp/JP_MainUIText.json"))))
    }

    @Test fun supplementPreservesExplicitSameTextAndOnlyTranslatesMissingFields() {
        val cache = temp.root.toPath().resolve("cache")
        val localize = temp.root.toPath().resolve("Localize"); Files.createDirectories(localize.resolve("jp"))
        localize.resolve("jp/JP_MainUIText.json").writeText("\uFEFF" + """{"dataList":[{"id":1,"name":"石","content":"開始する"},{"id":2,"content":"待機中"}]}""")
        val archive = temp.root.toPath().resolve("base.zip")
        java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("MainUIText.json"))
            zip.write("""{"dataList":[{"id":1,"name":"石"},{"id":2,"content":"等待中"}]}""".toByteArray())
            zip.closeEntry()
        }
        val sources = TranslationSources(cache)
        val base = Files.newInputStream(archive).use { sources.import(it, "base.zip") }
        var requested = 0
        val config = AiConfig(missingOnly = true)
        val client = AiTranslationClient(config, "fixture", AiHttpTransport { _, _, body ->
            val rows = JSONArray(JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content"))
            requested += rows.length()
            assertEquals("開始する", rows.getJSONObject(0).getString("text"))
            response(JSONArray().put(JSONObject().put("id", rows.getJSONObject(0).getString("id")).put("text", "开始")))
        })
        val generated = AiTranslationJob(cache, ContainerWorkspaceStorage(localize), config, client).run(base.id, sources.prepare(base))
        assertEquals(1, requested)
        val document = java.util.zip.ZipFile(generated.archive.toFile()).use { zip ->
            JSONObject(zip.getInputStream(zip.getEntry("jp/JP_MainUIText.json")).bufferedReader().readText())
        }.getJSONArray("dataList")
        assertEquals("石", document.getJSONObject(0).getString("name"))
        assertEquals("开始", document.getJSONObject(0).getString("content"))
        assertEquals("等待中", document.getJSONObject(1).getString("content"))
        java.util.zip.ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("MainUIText.json"))
            zip.write("""{"dataList":[{"id":1,"name":"石"},{"id":2,"content":"稍候"}]}""".toByteArray())
            zip.closeEntry()
        }
        val updated = Files.newInputStream(archive).use { sources.import(it, "base.zip", base.id) }
        val resumed = AiTranslationJob(cache, ContainerWorkspaceStorage(localize), config, client).run(base.id, sources.prepare(updated))
        assertEquals(1, requested)
        assertEquals(1, resumed.progress.reused)
    }

    @Test fun cancellationNeverCreatesASuccessfulSourceAndResumesCompletedBatches() {
        val cache = temp.root.toPath().resolve("cache")
        val localize = temp.root.toPath().resolve("Localize"); Files.createDirectories(localize.resolve("jp"))
        localize.resolve("jp/JP_MainUIText.json").writeText("""{"dataList":[{"id":1,"content":"接続中"}]}""")
        var cancel = false; var calls = 0
        val config = AiConfig(missingOnly = false)
        val client = AiTranslationClient(config, "fixture", AiHttpTransport { _, _, body ->
            calls++; cancel = true
            val id = JSONArray(JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content")).getJSONObject(0).getString("id")
            response(JSONArray().put(JSONObject().put("id", id).put("text", "连接中")))
        })
        val storage = ContainerWorkspaceStorage(localize)
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            AiTranslationJob(cache, storage, config, client, { cancel }).run("zero", null)
        }
        assertEquals(1, TranslationSources(cache).read().sources.size)
        cancel = false
        val resumed = AiTranslationJob(cache, storage, config, client).run("zero", null)
        assertEquals(1, calls); assertEquals(1, resumed.progress.reused)
    }

    @Test fun noDownloadedOriginalsCannotCreateAnEmptySuccessfulPack() {
        val config = AiConfig()
        val client = AiTranslationClient(config, "fixture", AiHttpTransport { _, _, _ -> error("must not send") })
        assertThrows(IllegalArgumentException::class.java) {
            AiTranslationJob(temp.root.toPath(), ContainerWorkspaceStorage(temp.root.toPath().resolve("Localize")), config, client).run("zero", null)
        }
    }
}
