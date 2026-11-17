package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.ContainerWorkspaceStorage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AiPromptTest {
    @get:Rule val temp = TemporaryFolder()
    private fun reply(id: String, text: String) = JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "stop")
        .put("message", JSONObject().put("content", JSONObject().put("translations", JSONArray().put(JSONObject().put("id", id).put("text", text))).toString())))).toString()

    @Test fun globalStyleAndReferenceTermsReachTheModelButCannotDisableLocalNameChecks() {
        val config = AiConfig(prompt = "用自然中文，保持角色差异。")
        var captured = ""
        val correct = AiTranslationClient(config, "fixture", AiHttpTransport { _, _, body ->
            captured = body; reply("one", "浮士德知道答案。")
        })
        val input = AiText("one", "ファウストは答えを知っている。", "jp/StoryData/JP_scene.json /dataList/0/content | speaker=ファウスト",
            references = listOf("問いは何か。" to "问题是什么？"))
        assertEquals("浮士德知道答案。", correct.translate(listOf(input)).translations["one"])
        val request = JSONObject(captured).getJSONArray("messages")
        assertTrue(request.getJSONObject(0).getString("content").contains(config.prompt))
        val row = JSONArray(request.getJSONObject(1).getString("content")).getJSONObject(0)
        assertEquals("浮士德", row.getJSONObject("terminology").getString("ファウスト"))
        assertEquals("问题是什么？", row.getJSONArray("reference_translations").getJSONObject(0).getString("translated"))
        val wrong = AiTranslationClient(config.copy(prompt = "名字可以随意变化"), "fixture", AiHttpTransport { _, _, _ -> reply("one", "浮士得知道答案。") })
        assertThrows(IllegalArgumentException::class.java) { wrong.translate(listOf(input)) }
    }

    @Test fun terminologySkipsFormattingAndRespectsLongNamesAndAsciiWordBoundaries() {
        assertTrue(AiTerminology.DEFAULT.forText("<link=ファウスト>开始</link>", "").values.isEmpty())
        val terms = AiTerminology(mapOf("Burn" to "烧伤", "イサン" to "李箱", "イサンの影" to "李箱之影"), setOf("イサン"))
        assertTrue(terms.forText("Burning", "JP_Skills").values.isEmpty())
        val found = terms.forText("[Burn] Burn イサンの影", "JP_Skills")
        assertEquals(mapOf("Burn" to "烧伤", "イサンの影" to "李箱之影"), found.values)
        assertThrows(IllegalArgumentException::class.java) { AiTerminology.validate("燃烧与李箱之影", found) }
        AiTerminology.validate("烧伤与李箱之影", found)
        for (bad in listOf("", " ", "a".repeat(AiTranslationPrompt.MAX_LENGTH + 1), "x\u0000")) {
            assertThrows(IllegalArgumentException::class.java) { AiConfig(prompt = bad).validated() }
        }
        assertEquals(AiTranslationPrompt.DEFAULT, AiConfig().validated().prompt)
    }

    @Test fun referencePackageSuppliesGlossaryAndNeighborsInsteadOfExistingAiWording() {
        val cache = temp.root.toPath().resolve("cache"); val root = temp.root.toPath().resolve("Localize")
        Files.createDirectories(root.resolve("jp"))
        val jpCharacters = """{"dataList":[{"id":1,"name":"ウーティス"}]}"""
        val jp = """{"dataList":[{"id":1,"teller":"ウーティス","content":"ウーティスが来た。"},{"id":2,"content":"出発しよう。"}]}"""
        Files.write(root.resolve("jp/JP_Characters.json"), jpCharacters.toByteArray())
        Files.write(root.resolve("jp/JP_MainUIText.json"), jp.toByteArray())
        val store = TranslationSources(cache)
        fun pack(name: String, line: String): PreparedTranslation {
            val path = temp.root.toPath().resolve(name)
            ZipOutputStream(Files.newOutputStream(path)).use { zip ->
                mapOf("Characters.json" to """{"dataList":[{"id":1,"name":"奥提斯"}]}""",
                    "MainUIText.json" to """{"dataList":[{"id":2,"content":"$line"}]}""").forEach { (file, text) ->
                    zip.putNextEntry(ZipEntry(file)); zip.write(text.toByteArray()); zip.closeEntry()
                }
            }
            return store.prepare(Files.newInputStream(path).use { store.import(it, name) })
        }
        val official = pack("official.zip", "我们出发吧。"); val ai = pack("ai.zip", "走喽！")
        val cfg = AiConfig(); var calls = 0
        val client = AiTranslationClient(cfg, "fixture", AiHttpTransport { _, _, body ->
            val input = JSONArray(JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content"))
            assertEquals(1, input.length()); val row = input.getJSONObject(0); calls++
            assertTrue(row.getString("context").contains("speaker=ウーティス"))
            assertEquals("奥提斯", row.getJSONObject("terminology").getString("ウーティス"))
            assertEquals("我们出发吧。", row.getJSONArray("reference_translations").getJSONObject(0).getString("translated"))
            reply(row.getString("id"), "奥提斯来了。")
        })
        AiTranslationJob(cache, ContainerWorkspaceStorage(root), cfg, client).run("zero", ai, reference = official)
        assertEquals(1, calls)
    }

    @Test fun promptChangeDoesNotSilentlyReuseOldStyleForNewRequests() {
        val cache = temp.root.toPath().resolve("cache"); val root = temp.root.toPath().resolve("Localize")
        Files.createDirectories(root.resolve("jp"))
        Files.write(root.resolve("jp/JP_MainUIText.json"), """{"dataList":[{"id":1,"content":"開始する"}]}""".toByteArray())
        val seen = mutableListOf<String>()
        fun run(prompt: String): AiJobResult {
            val cfg = AiConfig(prompt = prompt)
            val client = AiTranslationClient(cfg, "fixture", AiHttpTransport { _, _, body ->
                seen += prompt; val row = JSONArray(JSONObject(body).getJSONArray("messages").getJSONObject(1).getString("content")).getJSONObject(0)
                reply(row.getString("id"), "开始")
            })
            return AiTranslationJob(cache, ContainerWorkspaceStorage(root), cfg, client).run("zero", null, "a".repeat(32))
        }
        assertEquals(1, run("第一种文风").progress.completed)
        assertEquals(1, run("第一种文风").progress.reused)
        assertEquals(1, run("第二种文风").progress.completed)
        assertEquals(listOf("第一种文风", "第二种文风"), seen)
    }
}
