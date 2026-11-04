package com.example.limbuszhcn.selfupdate

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {
    @Test fun legacyPlainLinesRemainSeparatePoints() {
        assertEquals(listOf("启动按钮居中", "更新内容分点展示"),
            releaseNoteItems("启动按钮居中\r\n\r\n更新内容分点展示"))
    }

    @Test fun visibleListMarkersAreNotDuplicated() {
        assertEquals(listOf("第一项", "第二项", "第三项", "第四项", "第五项"),
            releaseNoteItems("- 第一项\n* 第二项\n• 第三项\n1. 第四项\n2、第五项"))
    }

    @Test fun emptyLinesAndMarkersDoNotBecomeRowsAndVersionTextSurvives() {
        assertEquals(listOf("v1.0.17 已发布", "1.0.17 版本说明", "保留 3.5 MB 的说明"),
            releaseNoteItems(" \n- \n v1.0.17 已发布 \n1.0.17 版本说明\n保留 3.5 MB 的说明\n"))
    }
}
