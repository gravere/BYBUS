package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.*
import com.example.limbuszhcn.gamefs.GameTextLanguage
import com.example.limbuszhcn.update.sha256
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ExperienceRegressionTest {
    @get:Rule val temp = TemporaryFolder()
    private val cache get() = temp.root.toPath().resolve("cache")
    @Test fun pricesMatchExactProviderAndModelAndManualRatesSurviveRestart() {
        val json = """{"openai":{"models":{"model-a":{"cost":{"input":2,"output":8,"cache_read":0.5}}}}} """
        var fetched = 0
        val prices = AiPricing(cache) { fetched++; json }
        val automatic = prices.find("https://api.openai.com/v1/", "model-a")!!
        assertEquals(2.5, automatic.cost(1_000_000, 100_000, 200_000), .000001)
        assertNull(prices.find("https://relay.example/v1", "model-a"))
        assertNull(prices.find("https://api.openai.com/v1", "model-a-unknown"))
        assertEquals(1, fetched)
        prices.save("https://relay.example/v1", "model-a", AiPrice(4.0, 6.0, 1.0, "CNY"))
        val restored = AiPricing(cache) { error("unexpected download") }.find("https://relay.example/v1", "model-a")!!
        assertTrue(restored.manual); assertEquals("CNY", restored.currency)
        assertEquals(automatic, prices.find("https://api.openai.com/v1", "model-a"))
        prices.save("https://relay.example/v1", "model-a", null)
        assertNull(prices.find("https://relay.example/v1", "model-a"))
        assertThrows(IllegalArgumentException::class.java) { AiPrice(Double.NaN, 1.0).validated() }
    }
    @Test fun failedResponseStillRecordsActualUsageAndMissingUsageIsUnknown() {
        val response = JSONObject().put("usage", JSONObject().put("prompt_tokens", 80).put("completion_tokens", 20)
            .put("prompt_tokens_details", JSONObject().put("cached_tokens", 40)))
            .put("choices", JSONArray().put(JSONObject().put("finish_reason", "length"))).toString()
        var usage = AiUsage()
        assertThrows(IllegalArgumentException::class.java) {
            AiTranslationClient(AiConfig(), "fixture", AiHttpTransport { _,_,_ -> response })
                .translate(listOf(AiText("one", "確認です"))) { usage = it }
        }
        assertEquals(AiUsage(80,20,40,true), usage)
        val record = AiProgress(inputTokens=80,outputTokens=20,cachedTokens=40,elapsedMillis=2345,requests=1,price=AiPrice(2.0,8.0))
        AiUsageStore(cache).save(record); assertEquals(record, AiUsageStore(cache).read())
        assertThrows(Exception::class.java) {
            AiTranslationClient(AiConfig(), "fixture", AiHttpTransport { _,_,_ -> "{}" })
                .translate(listOf(AiText("one", "確認です"))) { usage = it }
        }
        assertFalse(usage.known)
    }
    @Test fun aHundredThousandEntryPlanIsWrittenOnceAndRecoveredWithoutExpandingItsScope() {
        val ids = (0 until 100_001).mapTo(linkedSetOf()) { AiTranslationJob.digest("field-$it") }
        val plan = AiTaskPlan(mapOf("jp/JP_Test.json" to ids), mapOf("jp/JP_Test.json" to "b".repeat(64)), 120_000)
        val task = AiTaskSnapshot("a".repeat(32),AiConfig(limit=MAX_AI_TASK_ENTRIES),"global","全局","c".repeat(32),"全局 AI",null,plan=plan)
        val tasks = AiTaskStore(cache); tasks.write(task)
        val file = tasks.root.resolve("plan.json")
        Files.setLastModifiedTime(file,FileTime.fromMillis(1000))
        tasks.write(task.copy(progress=AiProgress(completed=24,limit=plan.total)))
        assertEquals(1000, Files.getLastModifiedTime(file).toMillis())
        assertTrue(Files.size(tasks.root.resolve("task.json")) < 16_000)
        val recovered = AiTaskStore(cache).read()!!
        assertEquals(ids, recovered.plan!!.selected.values.single()); assertEquals(120_000,recovered.plan!!.available)
        Files.write(file, "{}".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { AiTaskStore(cache).read() }
    }
    @Test fun pairedRecordCacheRebuildsCorruptionAndChangedText() {
        val root = temp.root.toPath().resolve("pairs"); var builds=0
        val expected = listOf(TranslationIndexRecord("jp/JP_A.json","1","content","original","中文"))
        fun read(text: String = "original") = TranslationPairCache(root).records("jp/JP_A.json",text,"中文") { builds++; expected }
        assertEquals(expected,read()); assertEquals(expected,read()); assertEquals(1,builds)
        val file = Files.list(root).use { it.findFirst().get() }
        val bytes=Files.readAllBytes(file);bytes[bytes.lastIndex-2]=(bytes[bytes.lastIndex-2].toInt() xor 1).toByte();Files.write(file,bytes)
        assertEquals(expected,read()); assertEquals(2,builds)
        read("updated");assertEquals(3,builds)
    }
    @Test fun familiesKeepVersionsWithTheirBaseAndHandleDeletedOrLegacyParents() {
        val a=TranslationSource("ai-"+"a".repeat(32),"日语 AI","ai",baseId="zero",updatedAtMillis=10)
        val b=a.copy(id="ai-"+"b".repeat(32),name="英语 AI",updatedAtMillis=20)
        val global=a.copy(id="ai-"+"c".repeat(32),baseId=null,independent=true)
        val removed=a.copy(id="ai-"+"d".repeat(32),baseId="local-deleted",baseName="自选包")
        val groups=sourceFamilies(listOf(TranslationSources.OFFICIAL,a,b,global,removed))
        assertEquals(listOf(b,a),groups.first().versions)
        assertEquals("全局 AI 翻译",groups[1].name);assertEquals("自选包",groups[2].name)
        assertNull(groups[2].base)
    }
    @Test fun noticesAreExactWholeBodiesAndCannotEnterGlobalTranslationOrFontMaps() {
        val body = "<b>更新通知</b>\n" + "これは公開のお知らせです。\n".repeat(500)
        val parts=NoticeTexts.chunks(body)!!
        assertTrue(parts.size>1); assertEquals(body,parts.joinToString("")); assertTrue(parts.all { it.length<=6000 })
        val notices=NoticeTexts(cache);notices.capture(GameTextLanguage.Japanese,body)
        val storage=NoticeGameStorage(ContainerWorkspaceStorage(temp.root.toPath().resolve("Localize")),cache)
        assertEquals(1,storage.textResources(GameTextLanguage.Japanese).size)
        assertTrue(storage.textResources(GameTextLanguage.English).isEmpty())
        val original=NoticeTexts.document(parts).toString()
        val translated=NoticeTexts.document(parts.map { it.replace("これは公開のお知らせです。","这是公开公告。") }).toString()
        val zip=temp.root.toPath().resolve("notice.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use {
            it.putNextEntry(ZipEntry(notices.key(GameTextLanguage.Japanese,body)));it.write(TranslationSnapshot.encode(original,JSONObject(translated)).toByteArray());it.closeEntry()
        }
        val pack=LocalTranslationArchive(cache).extract(zip,"local-${zip.sha256()}")
        val snapshot=LiveTranslationStore(cache).prepare(pack.directory,pack.manifest) { GameTextLanguage.Japanese.resourceKey(it) }
        assertEquals(0,snapshot.labels)
        System.getProperty("bybus.test.catalogue")?.let { Files.copy(cache.resolve("translation-index/live-fonts.bin"),java.nio.file.Paths.get(it),java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        val runtime=NoticeRuntime(cache,snapshot)
        assertEquals(body.replace("これは公開のお知らせです。","这是公开公告。"),runtime.translate("jp",body))
        assertNull(runtime.translate("en",body)); assertNull(runtime.translate("jp",body+"新しい内容"))
        assertNull(LiveResourceTranslator(cache,snapshot).translate(notices.key(GameTextLanguage.Japanese,body),original))
        assertTrue(TranslationInspection(storage,pack).sharedTranslations().isEmpty())
        val field=TranslationInspection(storage,null).read(notices.key(GameTextLanguage.Japanese,body)).fields.first()
        assertTrue(AiTextSelection.missing(field,mapOf(field.text to "其他地方的译文")))
        runtime.close()
    }
}
