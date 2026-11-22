package com.example.limbuszhcn.selfupdate

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseNotesTest {
    @Test fun labelsAreSeparatedFromBodyAndOldNotesKeepTheirMeaning() {
        val notes = categorizedReleaseNotes("- [修复] 登录返回失败\n- 优化：更新重试\n- **新增** 分类标签\n- 更新：使用说明\n- 保留离线汉化")
        assertEquals(listOf(ReleaseNoteCategory.Fixed, ReleaseNoteCategory.Improved, ReleaseNoteCategory.Added,
            ReleaseNoteCategory.Updated, ReleaseNoteCategory.Updated), notes.map { it.category })
        assertEquals(listOf("登录返回失败", "更新重试", "分类标签", "使用说明", "保留离线汉化"), notes.map { it.text })
        assertEquals("登录流程", categorizedReleaseNotes("修复登录流程").single().text)
    }
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
