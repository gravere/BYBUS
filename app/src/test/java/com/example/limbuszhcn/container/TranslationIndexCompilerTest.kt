package com.example.limbuszhcn.container

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.nio.file.Files
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.readText

class TranslationIndexCompilerTest {
    @Test
    fun numericLabelsRequireResourceContextAndCannotRewriteTimers() {
        val inputs = listOf("30", "04:30", "133 / 133", "251.91", "<color=yellow>30</color>")
        val compiled = TranslationIndexCompiler().apply {
            inputs.forEachIndexed { i, source ->
                add(TranslationIndexRecord("JP_Floors.json", "floor-$i", "name", source, "第${i}层", "floor-$i"))
            }
            add(TranslationIndexRecord("JP_UI.json", "no-identity", "name", "60", "六十"))
            add(TranslationIndexRecord("JP_UI.json", "word", "name", "Sinner", "罪人"))
        }.compile("v", "archive")
        assertEquals(mapOf("Sinner" to "罪人"), compiled.uniqueEntries)
        assertEquals(mapOf("Sinner" to "罪人"), compiled.termEntries)
        inputs.forEachIndexed { i, source ->
            assertEquals("第${i}层", compiled.contextualEntries[TranslationIndexCompiler.contextKey("floor-$i", "name", source)])
        }
        assertEquals(inputs.size, compiled.contextualEntries.size)
    }

    @Test
    fun unchangedApprovedHanLabelsKeepFontProvenanceWithoutLatinOrKanaRules() {
        val compiled = TranslationIndexCompiler().apply {
            listOf("罪人", "特性", "NORMAL", "123", "回復する", "……").forEach {
                add(record("JP_UI.json", "label", "content", it, it))
            }
        }.compile("v", "archive")
        assertEquals(mapOf("特性" to "特性", "罪人" to "罪人"), compiled.uniqueEntries)
        assertTrue(compiled.termEntries.isEmpty())
        assertEquals(0, compiled.sourceRecordCount)
        assertFalse(compiled.inputSha256 == TranslationIndexCompiler().compile("v", "archive").inputSha256)
    }

    @Test
    fun fontOnlyIdentityCannotResolveOrOverrideAmbiguousTranslations() {
        val compiler = TranslationIndexCompiler().apply {
            add(record("JP_UI.json", "same", "content", "囚人", "囚人"))
            add(record("JP_UI.json", "a", "content", "囚人", "罪人"))
            add(record("JP_UI.json", "same2", "dialog", "了解", "了解"))
            add(record("JP_UI.json", "b", "dialog", "了解", "明白"))
            add(record("JP_UI.json", "c", "dialog", "了解", "收到"))
        }
        val compiled = compiler.compile("v", "archive")
        assertEquals("罪人", compiled.uniqueEntries["囚人"])
        assertNull(compiled.uniqueEntries["了解"])
        assertFalse(compiled.termEntries.containsKey("了解"))
    }

    @Test
    fun descriptionClausesCannotOverrideStandaloneGlossaryNames() {
        val compiled = TranslationIndexCompiler().apply {
            add(record("JP_AbnormalityGuides.json", "butterfly", "name", "羅生蝶", "罗生蝶"))
            add(record("JP_Bufs.json", "condition", "desc", "羅生蝶、追加効果", "与罗生蝶，附加效果"))
            add(record("JP_Bufs.json", "conditional", "desc", "開花E.G.O、追加効果", "若处于绽放E.G.O，附加效果"))
            add(record("JP_UI.json", "ego", "content", "開花E.G.O", "绽放E.G.O"))
        }.compile("2026092102", "archive")
        assertEquals("罗生蝶", compiled.uniqueEntries["羅生蝶"])
        assertEquals("罗生蝶", compiled.termEntries["羅生蝶"])
        assertEquals("绽放E.G.O", compiled.uniqueEntries["開花E.G.O"])
        assertEquals("绽放E.G.O", compiled.termEntries["開花E.G.O"])
    }

    @Test
    fun keepsOnlyUnambiguousSourceMappingsInFastIndex() {
        val compiler = TranslationIndexCompiler()
        compiler.add(record("MainUIText.json", "1", "content", "Sinner", "罪人"))
        compiler.add(record("OtherUIText.json", "2", "content", "Sinner", "罪人"))
        compiler.add(record("Battle.json", "3", "dialog", "Ready", "准备"))
        compiler.add(record("Story.json", "4", "dialog", "Ready", "出发"))
        compiler.add(record("Same.json", "5", "teller", "Dante", "Dante"))

        val compiled = compiler.compile("2026071001", "archive")

        assertEquals(mapOf("Sinner" to "罪人"), compiled.uniqueEntries)
        assertEquals("罪人", compiled.termEntries["Sinner"])
        assertEquals(4, compiled.sourceRecordCount)
        assertEquals(1, compiled.conflictCount)
        assertEquals(0, compiled.dominantEntryCount)
        assertEquals(64, compiled.inputSha256.length)
    }

    @Test
    fun resolvesDominantTerminologyAndExtractsBracketedTerms() {
        val compiler = TranslationIndexCompiler()
        repeat(10) { index ->
            compiler.add(record("Main$index.json", index.toString(), "content", "囚人", "罪人"))
        }
        compiler.add(record("Story.json", "other", "title", "囚人", "罪人们"))
        compiler.add(record("Keyword.json", "before", "content", "[攻撃前]", "[攻击前]"))
        compiler.add(record("Keyword.json", "tremor", "name", "振動", "震颤"))
        compiler.add(record("Keyword.json", "above", "content", "以上", "或以上"))
        compiler.add(record(
            "UnitKeyword.json",
            "sister",
            "content",
            "<color=#d40000><s>長姉</s></color>",
            "<color=#d40000><s>长姊</s></color>"
        ))
        compiler.add(record(
            "Keyword.json",
            "formatted-bracket-name",
            "name",
            "代行[テスト指令]",
            "代行[测试指令]"
        ))

        val compiled = compiler.compile("2026071001", "archive")

        assertEquals("罪人", compiled.uniqueEntries["囚人"])
        assertEquals("罪人", compiled.termEntries["囚人"])
        assertEquals("攻击前", compiled.termEntries["攻撃前"])
        assertEquals("震颤", compiled.termEntries["振動"])
        assertEquals("长姊", compiled.termEntries["長姉"])
        assertEquals("测试指令", compiled.termEntries["テスト指令"])
        assertEquals("或以上", compiled.termEntries["以上"])
        assertEquals(1, compiled.dominantEntryCount)
    }

    /** 验证剧情、章节关联文本和解锁编号进入显示字段策略；同文汉字保留字体身份。 */
    @Test
    fun includesVerifiedStoryAndUnlockDisplayFields() {
        val compiler = TranslationIndexCompiler().apply {
            add(record("Guide.json", "story", "story", "記録を読む。", "阅读记录。"))
            add(record("Unlock.json", "chapter", "relatedChapterText", "第九章", "第九章"))
            add(record("Unlock.json", "condition", "openConditionNumber", "九・五―十四", "9.5-14"))
        }

        val compiled = compiler.compile("2026080601", "archive")

        assertEquals("阅读记录。", compiled.uniqueEntries["記録を読む。"])
        assertEquals("9.5-14", compiled.uniqueEntries["九・五―十四"])
        assertEquals("第九章", compiled.uniqueEntries["第九章"])
        assertTrue(TranslationTextPolicy.isDisplayField("story"))
        assertTrue(TranslationTextPolicy.isDisplayField("relatedChapterText"))
        assertTrue(TranslationTextPolicy.isDisplayField("openConditionNumber"))
    }

    /** 验证确定损坏的 Unicode/control 文本被跳过，而剧情符号文本仍被保留。 */
    @Test
    fun rejectsDefinitivelyCorruptedDisplayText() {
        val compiler = TranslationIndexCompiler().apply {
            add(record("Broken.json", "replacement", "content", "壊れた一", "损坏\uFFFD文本"))
            add(record("Broken.json", "control", "content", "壊れた二", "损坏\u0085文本"))
            add(record("Good.json", "symbol", "content", "信号", "?!>@!#$@!?!>#!#!"))
        }

        val compiled = compiler.compile("2026080601", "archive")

        assertFalse(compiled.uniqueEntries.containsKey("壊れた一"))
        assertFalse(compiled.uniqueEntries.containsKey("壊れた二"))
        assertEquals("?!>@!#$@!?!>#!#!", compiled.uniqueEntries["信号"])
    }

    /** 验证格式化术语全部从下载包派生，并优先采用包内关键词词典。 */
    @Test
    fun derivesFormattedTermsFromDownloadedGlossaries() {
        val compiler = TranslationIndexCompiler().apply {
            // 普通页面即使重复使用同一日文，也不能压过 Bufs 中的正式关键词译名。
            repeat(4) { index ->
                add(record("Passives.json", "passive-$index", "name", "呼吸", "喘息未定"))
            }
            add(record("Localize/jp/JP_Bufs.json", "Breath", "name", "呼吸", "呼吸法"))
            add(record("Localize/jp/JP_Bufs-a1c9p1.json", "KarmaOfIndexAlly", "name", "カルマ", "业"))
            add(record(
                "Localize/jp/JP_Bufs-a1c9p3.json",
                "StackRienSpecialSkill",
                "name",
                "代行[ヘルメス]",
                "代行[赫尔墨斯]"
            ))
            add(record(
                "Bufs-a1c9p3.json",
                "coin-condition",
                "desc",
                "- 打撃ダメージを与える\n- 破壊されずに命中時、自身に呼吸を2付与",
                "- 造成打击伤害\n- 未摧毁并命中时，使自身获得2层呼吸法"
            ))
        }

        val compiled = compiler.compile("2026080601", "archive")

        assertEquals("呼吸法", compiled.uniqueEntries["呼吸"])
        assertEquals("呼吸法", compiled.termEntries["呼吸"])
        assertEquals("业", compiled.termEntries["カルマ"])
        assertEquals("赫尔墨斯", compiled.termEntries["ヘルメス"])
        assertEquals("未摧毁并命中时", compiled.termEntries["破壊されずに命中時"])
        assertFalse(compiled.termEntries.containsKey("喘息未定"))
    }

    @Test
    fun activatesImmutableBinaryIndexAndPointer() {
        val root = Files.createTempDirectory("limbus-index-test")
        val compiler = TranslationIndexCompiler().apply {
            add(record("MainUIText.json", "1", "content", "Sinner", "罪人"))
        }
        val compiled = compiler.compile("2026071001", "archive-sha")

        val indexFile = TranslationIndexStore(root).activate(compiled)

        assertTrue(indexFile.exists())
        assertTrue(indexFile.parent.fileName.toString().contains("-s9-"))
        assertEquals(indexFile.toString().replace('\\', '/'), root.resolve("active-index.path").readText().trim())
        DataInputStream(indexFile.inputStream()).use { input ->
            assertEquals("LZTI1\u0000", String(input.readNBytes(6), Charsets.US_ASCII))
            assertEquals(9, input.readInt())
            assertEquals(1, input.readInt())
            assertEquals(1, input.readInt())
            assertEquals(0, input.readInt()) // No redundant contexts for unambiguous text.
            assertEquals("Sinner", input.readSizedUtf8())
            assertEquals("罪人", input.readSizedUtf8())
            assertEquals("Sinner", input.readSizedUtf8())
            assertEquals("罪人", input.readSizedUtf8())
        }
        val properties = Properties().apply {
            indexFile.parent.resolve("manifest.properties").inputStream().use(::load)
        }
        assertEquals("1", properties.getProperty("uniqueEntryCount"))
        assertEquals("1", properties.getProperty("termEntryCount"))
        assertEquals("1", properties.getProperty("sourceRecordCount"))
        assertEquals("0", properties.getProperty("conflictCount"))
    }

    @Test
    fun deactivationOnlyRemovesActivePointer() {
        val root = Files.createTempDirectory("limbus-index-test")
        val compiled = TranslationIndexCompiler().apply {
            add(record("MainUIText.json", "1", "content", "Sinner", "罪人"))
        }.compile("2026071001", "archive")
        val store = TranslationIndexStore(root)
        val indexFile = store.activate(compiled)

        assertTrue(store.deactivate())

        assertFalse(root.resolve("active-index.path").exists())
        assertTrue(indexFile.exists())
    }

    @Test
    fun persistsAcrossStoreInstancesAndRepairsCorruptIndex() {
        val root = Files.createTempDirectory("limbus-persistent-index")
        val compiled = TranslationIndexCompiler().apply {
            add(record("MainUIText.json", "1", "content", "囚人", "罪人"))
        }.compile("2026092102", "archive")
        val indexFile = TranslationIndexStore(root).activate(compiled)
        val expected = Files.readAllBytes(indexFile)

        assertEquals("2026092102", TranslationIndexStore(root).activeManifest()?.getProperty("version"))
        Files.write(indexFile, byteArrayOf(1, 2, 3))
        assertNull(TranslationIndexStore(root).activeManifest())

        TranslationIndexStore(root).activate(compiled)
        assertArrayEquals(expected, Files.readAllBytes(indexFile))
        assertEquals("2026092102", TranslationIndexStore(root).activeManifest()?.getProperty("version"))
        Files.delete(indexFile)
        assertNull(TranslationIndexStore(root).activeManifest())
        TranslationIndexStore(root).activate(compiled)
        assertArrayEquals(expected, Files.readAllBytes(indexFile))
    }

    @Test
    fun refusesIndexPointerOutsidePersistentVersions() {
        val firstRoot = Files.createTempDirectory("limbus-index-first")
        val secondRoot = Files.createTempDirectory("limbus-index-second")
        val compiled = TranslationIndexCompiler().apply {
            add(record("MainUIText.json", "1", "content", "囚人", "罪人"))
        }.compile("2026092102", "archive")
        val outsideIndex = TranslationIndexStore(firstRoot).activate(compiled)
        Files.write(secondRoot.resolve("active-index.path"), outsideIndex.toString().toByteArray(Charsets.UTF_8))

        assertNull(TranslationIndexStore(secondRoot).activeManifest())
    }

    @Test
    fun requiresNinetyPercentOfJapaneseSourcesBeforeActivation() {
        assertTrue(hasSufficientJapaneseSources(90, 100))
        assertTrue(hasSufficientJapaneseSources(9, 10))
        assertFalse(hasSufficientJapaneseSources(89, 100))
        assertFalse(hasSufficientJapaneseSources(0, 0))
    }

    @Test
    fun resolvesLuxcavationByIdentityFieldAndOriginalWithoutGuessingGlobalText() {
        val compiler = TranslationIndexCompiler()
        fun paired(file: String, id: String, source: String, target: String) {
            TranslationJsonPairer.pair("jp/JP_$file.json",
                """{"dataList":[{"id":"$id","content":"$source"}]}""",
                """{"dataList":[{"id":"$id","content":"$target"}]}""")
                .records.forEach(compiler::add)
        }
        paired("ProjectGS", "project_gs_exp", "経験値", "经验值")
        paired("RewardDungeonUI", "reward_dungeon_exp", "経験値", "经验")
        // Matching a reused id still requires the entire original and display field.
        paired("Other", "reward_dungeon_exp", "別の原文", "另一段原文")
        val compiled = compiler.compile("1", "archive")
        assertNull(compiled.uniqueEntries["経験値"])
        assertEquals("经验", compiled.contextualEntries[TranslationIndexCompiler.contextKey("reward_dungeon_exp", "content", "経験値")])
        assertEquals("经验值", compiled.contextualEntries[TranslationIndexCompiler.contextKey("project_gs_exp", "content", "経験値")])
        assertEquals(2, compiled.contextualEntries.size)
        assertNull(compiled.contextualEntries[TranslationIndexCompiler.contextKey("reward_dungeon_exp", "name", "経験値")])

        val file = TranslationIndexStore(Files.createTempDirectory("limbus-context")).activate(compiled)
        DataInputStream(file.inputStream()).use { input ->
            input.skipNBytes(10)
            val regular = input.readInt()
            val terms = input.readInt()
            val contexts = input.readInt()
            repeat(regular + terms) { input.readSizedUtf8(); input.readSizedUtf8() }
            val loaded = (0 until contexts).associate { input.readSizedUtf8() to input.readSizedUtf8() }
            assertEquals(compiled.contextualEntries, loaded)
            assertEquals(-1, input.read())
        }
    }

    @Test
    fun refusesConflictingContextAndPreservesMinorityContextOfDominantTranslation() {
        val compiler = TranslationIndexCompiler().apply {
            add(record("A", "diagA", "content", "同文", "第一").copy(stableId = "1"))
            add(record("B", "diagB", "content", "同文", "第二").copy(stableId = "1"))
            repeat(4) { add(record("C$it", "$it", "content", "提示", "提示甲").copy(stableId = "common$it")) }
            add(record("D", "other", "content", "提示", "提示乙").copy(stableId = "other"))
        }.compile("1", "archive")
        assertNull(compiler.contextualEntries[TranslationIndexCompiler.contextKey("1", "content", "同文")])
        assertEquals("提示甲", compiler.uniqueEntries["提示"])
        assertEquals("提示乙", compiler.contextualEntries[TranslationIndexCompiler.contextKey("other", "content", "提示")])
        assertNull(TranslationIndexCompiler.contextKey("bad\u001fid", "content", "同文"))
    }

    @Test
    fun schemaUpgradeRetainsValidatedPreviousGenerationButDoesNotReuseIt() {
        val root = Files.createTempDirectory("limbus-schema-upgrade")
        val old = root.resolve("versions/old-s8/index.bin")
        Files.createDirectories(old.parent)
        java.io.DataOutputStream(Files.newOutputStream(old)).use { output ->
            output.write("LZTI1\u0000".toByteArray()); output.writeInt(8)
            output.writeInt(0); output.writeInt(0)
        }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(old))
            .joinToString("") { "%02x".format(it) }
        Files.write(old.parent.resolve("manifest.properties"), "schema=8\nversion=old\nindexSha256=$hash\n".toByteArray())
        Files.write(root.resolve("active-index.path"), old.toString().toByteArray())
        val store = TranslationIndexStore(root)
        assertNull(store.activeManifest())
        val compiled = TranslationIndexCompiler().apply {
            add(record("A", "1", "content", "文章", "中文"))
        }.compile("new", "archive")
        val active = store.activate(compiled)
        assertEquals(setOf("old", "new"), store.pruneAndRetainedVersions())
        assertTrue(Files.isRegularFile(old))
        assertTrue(Files.isRegularFile(active))
    }

    private fun record(
        path: String,
        id: String,
        field: String,
        source: String,
        translation: String
    ) = TranslationIndexRecord(path, id, field, source, translation)

    private fun DataInputStream.readSizedUtf8(): String {
        val size = readInt()
        return String(readNBytes(size), Charsets.UTF_8)
    }
}
