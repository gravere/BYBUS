package com.example.limbuszhcn.translation

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AiPriceDetailsTest {
    @get:Rule val temp = TemporaryFolder()
    private val catalog = """{"openai":{"name":"OpenAI","models":{"model-a":{"name":"Fast Model","cost":{"input":2,"output":8,"cache_read":0.5}},"invalid":{"cost":{"input":-1,"output":5}}}},"relay":{"name":"Another Provider","api":"https://provider.example/v1","models":{"model-a":{"name":"Fast Model","cost":{"input":3,"output":9}},"unknown":{}}}}"""
    @Test fun searchUsesNamesIdsAndProvidersWithoutAutoPricingAnUnknownRelay() {
        val options = AiPricing.options(JSONObject(catalog), 123)
        assertEquals(2, options.size); assertEquals(2, options.map { it.id }.toSet().size)
        assertEquals(1, options.count { it.matches("openAI FAST") });assertEquals(2, options.count { it.matches("model-a") })
        assertEquals(1, options.count { it.matches("Another Provider") });assertFalse(options.first().matches("not-found"))
        assertNull(AiPricing.match(JSONObject(catalog), "https://private.example/v1", "model-a", 123))
    }
    @Test fun pickedReferenceSurvivesRefreshRestartAndStaleCatalogueWithoutChangingTheTargetModel() {
        val cache = temp.root.toPath();var raw = catalog
        val store = AiPricing(cache) { raw }
        val first = store.lookup("https://private.example/v1", "my-alias")
        val chosen = first.options.first { it.providerId == "openai" }.selectedPrice()
        store.save("https://private.example/v1", "my-alias", chosen)
        raw = catalog.replace("\"input\":2", "\"input\":4")
        val fresh = store.lookup("https://private.example/v1", "my-alias", true)
        assertEquals(chosen, fresh.price);assertEquals("my-alias", fresh.model)
        assertEquals(4.0, fresh.options.first { it.providerId == "openai" }.price.input, 0.0)
        val restarted = AiPricing(cache) { error("offline") }.lookup("https://private.example/v1", "my-alias", true)
        assertEquals(chosen, restarted.price);assertEquals(2, restarted.options.size);assertNotNull(restarted.message)
        store.save("https://private.example/v1", "my-alias", null)
        assertNull(store.find("https://private.example/v1", "my-alias"))
    }
    @Test fun testResultAppliesOnlyToTheTestedConnectionAndPrompt() {
        val config = AiConfig(model = "model-a")
        val result = AiTestResult(AiTestResult.fingerprint(config, "fixture"), config.model, "连接中…", 1200)
        assertTrue(result.matches(config, "fixture"));assertFalse(result.matches(config.copy(model = "model-b"), "fixture"))
        assertFalse(result.matches(config, "changed"));assertFalse(result.matches(config.copy(prompt = "另一套提示词"), "fixture"))
        assertFalse(result.inputFingerprint.contains("fixture"))
    }
    private fun pack(name: String, text: String) = temp.root.toPath().resolve(name).also { file ->
        ZipOutputStream(Files.newOutputStream(file)).use { zip -> zip.putNextEntry(ZipEntry("MainUIText.json"))
            zip.write("""{"dataList":[{"id":1,"content":"$text"}]}""".toByteArray());zip.closeEntry() }
    }
    @Test fun realChangeCountsAndBoundedExamplesSurviveRestartAndAnOldConfirmationCannotUndoANewChange() {
        val changes = AiChangeCollector(AiChangeKind.Translation)
        changes.record("jp/StoryData/JP_Test.json", "original", null, "新增")
        changes.record("jp/JP_Skill.json", "original", "旧译文", "新译文")
        changes.record("jp/JP_Skill.json", "same", "原样", "原样")
        changes.record("jp/JP_Skill.json", "remove", "待移除", null)
        val summary = changes.snapshot();assertEquals(4, summary.total);assertEquals(3, summary.examples.size)
        assertEquals(listOf(1,1,1,1), listOf(summary.added,summary.replaced,summary.removed,summary.unchanged))
        assertEquals(summary, AiChangeSummary.read(summary.json()))
        val store = TranslationSources(temp.root.toPath().resolve("cache"))
        val first = store.addGenerated(pack("one.zip", "第一批"), "zero", "a".repeat(32), "AI", summary = summary)
        val point = TranslationSources(store.cache).read().undo!!
        assertEquals(first.updatedAtMillis, point.summary!!.atMillis)
        store.rename(first.id,"我的汉化")
        assertEquals(point,store.read().undo)
        store.addGenerated(pack("two.zip", "第二批"), "zero", "a".repeat(32), "AI", summary = summary)
        val second = store.read().undo!!
        assertThrows(IllegalArgumentException::class.java) { store.undoGenerated(point) }
        val restored = store.undoGenerated(second)
        assertEquals(first.revision, restored.selected.revision);assertEquals("我的汉化", restored.selected.name)
    }
    @Test fun legacyUndoRemainsUsableWithoutInventingEntryDetails() {
        val store = TranslationSources(temp.root.toPath().resolve("cache"))
        store.addGenerated(pack("legacy.zip","译文"),"zero","b".repeat(32),"旧包")
        val point = TranslationSources(store.cache).read().undo!!
        assertNull(point.summary);assertEquals("zero",store.undoGenerated(point).selectedId)
    }
}
