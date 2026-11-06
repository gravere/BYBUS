package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.LimbusSafetyAlert
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.io.path.readText
import kotlin.io.path.writeText

class StartupDiagnosticsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = temporary.root.toPath()

    @Test fun safetyAlertSurvivesProcessExitAndCannotBecomeANewLaunchResult() {
        val receipt = RuntimeReceipt(root)
        receipt.beginLaunch()
        val request = root.resolve("launch.request").readText().trim()
        root.resolve("runtime-state.properties").writeText(
            "request=$request\npid=101\nstate=hooks_installed\nfontState=ready")
        val summary = LimbusSafetyAlert.summarize(2, "Error code: 30010 private@example.test")
        assertFalse(receipt.recordSafetyAlert(99, summary))
        assertTrue(receipt.recordSafetyAlert(101, summary))
        assertTrue(receipt.description().contains("安全提示（30010）"))
        assertFalse(receipt.description().contains("已加载"))
        val saved = root.resolve("safety-alert.properties").readText()
        assertFalse(saved.contains("example"))
        assertTrue(receipt.diagnosticSummary().contains("safetyAlertCodes=30010"))
        receipt.beginLaunch()
        root.resolve("safety-alert.properties").writeText(saved)
        assertFalse(receipt.description().contains("30010"))
        assertTrue(receipt.diagnosticSummary().contains("safetyAlertObserved=false"))
    }

    @Test fun emptyAndUnrecognizedMessagesStillRecordTheSafetyAlertWithoutInventingACause() {
        val receipt = RuntimeReceipt(root)
        receipt.beginLaunch()
        assertTrue(receipt.recordSafetyAlert(101, LimbusSafetyAlert.summarize(1, "安全环境异常")))
        assertTrue(receipt.description().contains("未提供编号"))
        assertTrue(receipt.diagnosticSummary().contains("safetyAlertCategory=unknown"))
    }

    @Test fun reportsActualIndexModeAndOnlyCurrentRuntimeCounters() {
        val receipt = RuntimeReceipt(root)
        receipt.beginLaunch()
        assertTrue(receipt.diagnosticSummary().contains("translationLaunchMode=none"))
        val request = root.resolve("launch.request").readText().trim()
        root.resolve("launch-mode.txt").writeText("startup")
        root.resolve("runtime-state.properties").writeText(
            "request=$request\npid=101\nstate=hooks_installed\nfontState=ready\nentries=30\nterms=4\nsecret=private")
        val result = receipt.diagnosticSummary()
        assertTrue(result.contains("translationLaunchMode=startup"))
        assertTrue(result.contains("runtime_entries=30"))
        assertTrue(result.contains("runtimeReceiptCurrent=true"))
        assertFalse(result.contains("private"))
        receipt.beginLaunch()
        assertTrue(receipt.diagnosticSummary().contains("runtimeReceiptCurrent=false"))
    }
}
