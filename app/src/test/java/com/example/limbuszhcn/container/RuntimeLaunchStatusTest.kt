package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.LimbusSafetyAlert
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
        assertEquals("启动时出现问题", receipt.status().result)
        assertTrue(receipt.status().issue!!.contains("70036"))
        receipt.beginLaunch()
        record("state=hooks_installed\nfontState=failed")
        assertEquals("加载失败", receipt.status().font)
        assertNotNull(receipt.status().issue)
    }
}
