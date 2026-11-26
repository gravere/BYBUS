package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.LimbusSafetyAlert
import com.example.limbuszhcn.launchGameExitCodes
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.readText
import kotlin.io.path.writeText

class RuntimeLaunchStatusTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = temporary.root.toPath()
    private val receipt get() = RuntimeReceipt(root)
    private fun record(fields: String) {
        val request = root.resolve("launch.request").readText().trim()
        root.resolve("runtime-state.properties").writeText("request=$request\npid=101\n$fields")
    }

    @Test fun exportedGameCodeBelongsToTheSameLaunchEvenIfItChangesDuringCollection() {
        receipt.beginLaunch()
        record("state=hooks_installed")
        val matching = receipt.diagnosticSummary { pid, started ->
            assertEquals(101, pid)
            assertEquals(receipt.status().startedAtMillis, started)
            listOf("50025")
        }
        assertTrue(matching.contains("gameReportedExitCodes=50025"))
        val changed = receipt.diagnosticSummary { _, _ -> receipt.beginLaunch(); listOf("50025") }
        assertFalse(changed.contains("gameReportedExitCodes="))
        receipt.diagnosticSummary { _, _ -> error("No current game receipt") }
    }

    @Test fun returningHomeKeepsOnlyTheMatchingGamesOwnExitCode() {
        receipt.beginLaunch()
        record("state=hooks_installed\nfontState=pending")
        val start = System.currentTimeMillis() - 4_000
        Files.setLastModifiedTime(root.resolve("launch.request"), FileTime.fromMillis(start))
        val exit = GameProcessExit(101, start + 2_000, "信号 9")
        val noLogRead = { _: Int, _: Long, _: Long -> error("Should not read logcat") }
        assertNull(receipt.status(noLogRead) { null }.issue)
        assertNull(receipt.status(noLogRead) { exit.copy(timestamp = start - 1) }.issue)
        val status = receipt.status({ pid, from, until ->
            assertEquals(101, pid)
            assertEquals(start, from)
            assertEquals(exit.timestamp, until)
            listOf("70034", "private text", "70034")
        }) { exit }
        assertEquals("游戏报告了退出错误码 70034。", status.issue)
        assertEquals(status.issue, receipt.status(noLogRead) { null }.issue)
        assertTrue(receipt.description().contains("70034"))
        val export = receipt.diagnosticSummary { _, _ -> error("Log no longer available") }
        assertTrue(export.contains("gameReportedExitCodes=70034\n"))
        assertFalse(export.contains("private text"))
        record("pid=202\nstate=hooks_installed")
        assertNull(receipt.status(noLogRead) { null }.issue)
        receipt.beginLaunch()
        record("state=hooks_installed")
        assertNull(receipt.status(noLogRead) { null }.issue)
    }

    @Test fun aReplacementProcessCannotInheritAnExitBeingRead() {
        receipt.beginLaunch()
        record("state=hooks_installed")
        val summary = receipt.diagnosticSummary { _, _ ->
            record("pid=202\nstate=hooks_installed")
            listOf("70034")
        }
        assertFalse(summary.contains("gameReportedExitCodes="))
        assertNull(receipt.status().issue)
        record("state=hooks_installed")
        val start = requireNotNull(receipt.status().startedAtMillis)
        val status = receipt.status({ _, _, _ ->
            record("pid=202\nstate=hooks_installed")
            listOf("70034")
        }) { GameProcessExit(101, start, "信号 9") }
        assertNull(status.issue)
        assertFalse(receipt.description().contains("70034"))
    }

    @Test fun aConfirmedEarlyExitAfterAndroidRestartsTheSameGameKeepsItsFirstCode() {
        receipt.beginLaunch()
        val start = System.currentTimeMillis() - 10_000
        Files.setLastModifiedTime(root.resolve("launch.request"), FileTime.fromMillis(start))
        record("pid=6388\nstate=hooks_installed\nfontState=pending")
        fun epoch(offset: Long): String = (start + offset).let {
            "${it / 1000}.${(it % 1000).toString().padStart(3, '0')}"
        }
        val game = LocalContainerRuntime.GAME_PACKAGE
        val logs = listOf(
            "${epoch(380)} 6320 6320 I LimbusVA: VClient.bindApplication before makeApplication package=$game process=$game info=fixture",
            "${epoch(680)} 6320 6320 E AppSealing: Kill Process [50040]"
        )
        val finalExit = GameProcessExit(6388, start + 4_280, "信号 9")
        val status = receipt.status({ pid, since, until ->
            launchGameExitCodes(logs.asSequence(), pid, since, until) { prior ->
                (start + 1_119).takeIf { prior == 6320 }
            }
        }) { finalExit }
        assertEquals("游戏报告了退出错误码 50040。", status.issue)
        assertTrue(receipt.diagnosticSummary().contains("gameReportedExitCodes=50040\n"))
        receipt.beginLaunch()
        assertFalse(receipt.diagnosticSummary().contains("50040"))
    }

    @Test fun missingOrPreviousReceiptNeverClaimsSuccessfulStartup() {
        assertEquals("尚无启动记录", receipt.status().result)
        receipt.beginLaunch()
        record("state=hooks_installed\nfontState=ready")
        val old = root.resolve("runtime-state.properties").readText()
        receipt.beginLaunch()
        root.resolve("runtime-state.properties").writeText(old)
        assertEquals("尚未收到游戏响应", receipt.status().result)
        assertEquals("尚未收到确认", receipt.status().font)
    }

    @Test fun loadedAndActuallyReadAreSeparateAndFailureTakesPriority() {
        receipt.beginLaunch()
        record("state=hooks_installed\nfontState=ready\nliveResourceMerges=0")
        assertEquals("已加载汉化功能", receipt.status().translation)
        assertEquals("汉化与字体已加载", receipt.status().result)
        record("state=hooks_installed\nfontState=ready\nliveResourceMerges=3")
        assertEquals("已读取汉化文本", receipt.status().translation)
        receipt.recordSafetyAlert(101, LimbusSafetyAlert.summarize(2, "Error code: 70036"))
        assertEquals("游戏发出了安全提示", receipt.status().result)
        assertTrue(receipt.status().issue!!.contains("70036"))
        assertTrue(receipt.description().contains("70036"))
        record("state=hooks_installed\nfontState=ready\nliveResourceMerges=9")
        assertEquals("游戏发出了安全提示", receipt.status().result)
        receipt.beginLaunch()
        record("state=hooks_installed\nfontState=failed")
        assertEquals("加载失败", receipt.status().font)
        assertNotNull(receipt.status().issue)
    }

    @Test fun earlyProcessExitMatchesThisLaunchAndPreservesTheGamesOwnAlert() {
        receipt.beginLaunch()
        record("state=hooks_installed\nfontState=pending")
        val start = requireNotNull(receipt.status().startedAtMillis)
        val exit = GameProcessExit(101, start + 1248, "信号 9")
        assertEquals("游戏在启动后不久退出", receipt.status { exit }.result)
        assertEquals("信号 9", receipt.status { exit }.issue)
        for (other in listOf(exit.copy(pid = 102), exit.copy(timestamp = start - 1), exit.copy(timestamp = start + 60_000))) {
            assertNull(receipt.status { other }.issue)
        }
        receipt.recordSafetyAlert(101, LimbusSafetyAlert.summarize(2, "Error code: 70036"))
        assertTrue(receipt.status { error("不应覆盖游戏的提示") }.issue!!.contains("70036"))
    }

    @Test fun confirmedBattleExitIsVisibleWithoutReadingAnEntireSession() {
        receipt.beginLaunch(181)
        val start = System.currentTimeMillis() - 3_600_000
        Files.setLastModifiedTime(root.resolve("launch.request"), FileTime.fromMillis(start))
        record("state=hooks_installed\nfontState=ready\nliveResourceMerges=1")
        val exit = GameProcessExit(101, System.currentTimeMillis() - 1_000, "Android 因内存不足结束了游戏进程。", true, 181)
        val status = receipt.status({ pid, from, until ->
            assertEquals(101, pid)
            assertEquals(exit.timestamp - 60_000, from)
            assertEquals(exit.timestamp, until)
            emptyList()
        }) { exit }
        assertEquals("游戏运行中已退出", status.result)
        assertEquals(exit.detail, status.issue)
        assertEquals("已读取汉化文本", status.translation)
        // A different slot, build or pre-launch exit must not be attributed to this session.
        for (other in listOf(exit.copy(pid = 102), exit.copy(versionCode = 180),
            exit.copy(timestamp = start - 1), exit.copy(gameCheckpoint = false))) {
            assertNull(receipt.status { other }.issue)
        }
    }

    @Test fun updatingTheAppDoesNotRelabelTheLastGamesBuild() {
        receipt.beginLaunch(169)
        record("state=hooks_installed\nfontState=ready")
        val summary = receipt.diagnosticSummary(181)
        assertTrue(summary.contains("runtimeLaunchAppVersionCode=169\n"))
        assertTrue(summary.contains("runtimeLaunchRelation=different_build\n"))
        assertTrue(receipt.diagnosticSummary(169).contains("runtimeLaunchRelation=same_build\n"))
        receipt.beginLaunch(181)
        assertTrue(receipt.diagnosticSummary(181).contains("runtimeLaunchRelation=same_build\n"))
    }

    @Test fun legacyLaunchIsMarkedAsPredatingTheUpdateWithoutInventingItsVersion() {
        receipt.beginLaunch()
        val start = requireNotNull(receipt.status().startedAtMillis)
        val summary = receipt.diagnosticSummary(181, start + 1)
        assertTrue(summary.contains("runtimeLaunchAppVersionCode=unavailable\n"))
        assertTrue(summary.contains("runtimeLaunchRelation=before_app_update\n"))
        assertTrue(receipt.diagnosticSummary(181, start - 1).contains("runtimeLaunchRelation=unknown\n"))
        // A mismatched context file must not assign another launch's version.
        root.resolve("launch-context.properties").writeText("request=stale\nappVersionCode=123\n")
        assertTrue(receipt.diagnosticSummary(181).contains("runtimeLaunchAppVersionCode=unavailable\n"))
    }
}
