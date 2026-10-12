package com.example.limbuszhcn

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.limbuszhcn.tracking.ProjectIssue
import com.example.limbuszhcn.tracking.ProjectTrackerState
import com.example.limbuszhcn.tracking.ProjectTrackingState
import com.example.limbuszhcn.tracking.ProjectSource
import com.example.limbuszhcn.tracking.GitHubTrackerState
import com.example.limbuszhcn.tracking.ZeroGitHub
import com.example.limbuszhcn.tracking.ZeroProject
import com.example.limbuszhcn.ui.theme.LauncherColors as C
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
internal fun ProjectCard(tracking: ProjectTrackingState, onClick: () -> Unit) {
    val state = tracking.paratranz
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = C.Panel, contentColor = C.Ink) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(tracking.source.title, color = C.Muted, style = MaterialTheme.typography.labelMedium)
                    Text("都市零协会", style = MaterialTheme.typography.titleMedium)
                }
                LauncherGlyph(Glyph.Chevron, C.Accent, Modifier.size(18.dp))
            }
            Spacer(Modifier.height(22.dp))
            val snapshot = state.snapshot
            if (tracking.source == ProjectSource.GitHub) {
                val github = tracking.github
                github.snapshot?.let { release ->
                    Text(release.tag, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(8.dp))
                    Text("正式发布 · ${time(release.publishedAtMillis, full = true)}",
                        color = C.Muted, style = MaterialTheme.typography.bodySmall)
                    if (github.issue != null) Text("上次同步记录 · 暂未刷新", color = C.Muted,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                } ?: Text(if (github.loading) "正在读取最新发布…" else "暂时无法连接 GitHub",
                    color = C.Muted, style = MaterialTheme.typography.bodySmall)
            } else if (snapshot != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(percent(snapshot.translatedFraction), style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Medium)
                    Text("已翻译", color = C.Muted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 10.dp, bottom = 5.dp))
                }
                snapshot.translatedFraction?.let { progress ->
                    val animated by animateFloatAsState(progress, tween(350), label = "project progress")
                    LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth()
                        .padding(top = 12.dp).height(5.dp).clip(RoundedCornerShape(4.dp)),
                        color = C.Accent, trackColor = C.AccentSoft)
                }
                Spacer(Modifier.height(12.dp))
                Text(if (state.loading) "正在刷新…" else
                    "${if (state.issue != null) "上次记录" else "同步于"} ${time(snapshot.fetchedAtMillis)}",
                    color = C.Muted, style = MaterialTheme.typography.bodySmall)
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (state.loading) {
                        CircularProgressIndicator(Modifier.size(14.dp), color = C.Muted, strokeWidth = 1.5.dp)
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(if (state.loading) "正在读取项目进度" else "暂时无法获取进度",
                        color = C.Muted, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(end = 12.dp))
                    Text("ParaTranz", color = C.Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
internal fun ProjectDetails(tracking: ProjectTrackingState, onRefresh: () -> Unit,
                           onSelectSource: (ProjectSource) -> Unit, onOpenLink: (String) -> Unit) {
    val retryAt = tracking.github.retryAtMillis.takeIf { tracking.source == ProjectSource.GitHub }
    // One UI wake-up to re-enable the control; this does not schedule any network request.
    val waiting by produceState(retryAt != null && retryAt > System.currentTimeMillis(), retryAt) {
        value = retryAt != null && retryAt > System.currentTimeMillis()
        if (value) delay((retryAt!! - System.currentTimeMillis()).coerceAtLeast(0))
        value = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconTile(Glyph.Globe, accent = true)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("都市零协会", style = MaterialTheme.typography.titleMedium)
                Text("边狱巴士 · 简体中文", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onRefresh, enabled = !tracking.loading && !waiting,
                modifier = Modifier.size(48.dp).semantics { contentDescription = "刷新项目状态" }) {
                if (tracking.loading) CircularProgressIndicator(Modifier.size(21.dp), strokeWidth = 2.dp, color = C.Accent)
                else LauncherGlyph(Glyph.Refresh, if (waiting) C.Muted else C.Accent, Modifier.size(20.dp))
            }
        }
        SourceTabs(ProjectSource.entries, tracking.source, { it.title }, "项目来源", onSelectSource)
        Crossfade(tracking.source, animationSpec = tween(180), label = "project source") { source ->
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (source == ProjectSource.GitHub) GitHubDetails(tracking.github, onOpenLink)
                else ParaTranzDetails(tracking.paratranz, onOpenLink)
            }
        }
    }
}

@Composable
private fun ParaTranzDetails(state: ProjectTrackerState, onOpenLink: (String) -> Unit) {
    state.snapshot?.let { snapshot ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.Background).padding(22.dp)) {
            Text(percent(snapshot.translatedFraction), fontSize = 42.sp, lineHeight = 50.sp, fontWeight = FontWeight.Medium)
            Text("词条已翻译", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            snapshot.translatedFraction?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 18.dp)
                    .height(5.dp).clip(RoundedCornerShape(4.dp)), color = C.Accent, trackColor = C.Line)
            }
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ProjectMetric("总词条", snapshot.total, Modifier.weight(1f))
                ProjectMetric("已翻译", snapshot.translated, Modifier.weight(1f))
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                ProjectMetric("已校对", snapshot.checked, Modifier.weight(1f))
                ProjectMetric("已审核", snapshot.reviewed, Modifier.weight(1f))
            }
        }
        DetailRow("同步时间", time(snapshot.fetchedAtMillis, full = true))
        snapshot.modifiedAt?.let { DetailRow("词条更新", time(Instant.parse(it).toEpochMilli(), full = true)) }
    }
    if (state.issue != null || state.snapshot == null) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Background).padding(20.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (state.loading) "正在读取进度" else if (state.snapshot != null) "本次未能刷新" else "进度暂不可用",
                style = MaterialTheme.typography.titleSmall)
            Text(if (state.loading) "正在连接 ParaTranz。" else when (state.issue) {
                ProjectIssue.Restricted -> "项目接口暂未向当前访问者开放，可前往项目网页查看。"
                ProjectIssue.Unavailable -> "项目接口暂不可用，可稍后刷新或前往 ParaTranz 查看。"
                ProjectIssue.RateLimited -> "ParaTranz 暂时限制了请求，请稍后再试。"
                ProjectIssue.InvalidData -> "暂时无法解析项目统计，请稍后再试。"
                else -> "暂时无法连接 ParaTranz，请稍后再试。"
            }, color = C.Muted, style = MaterialTheme.typography.bodyMedium)
            if (state.snapshot != null && !state.loading) {
                Text("当前显示的是上次同步记录。", color = C.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    FilledTonalButton(onClick = { onOpenLink(ZeroProject.URL) }, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
        shape = RoundedCornerShape(18.dp), colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = C.AccentSoft, contentColor = C.OnAccentSoft)) {
        Text("打开 ParaTranz")
        Spacer(Modifier.width(10.dp))
        LauncherGlyph(Glyph.External, C.OnAccentSoft, Modifier.size(18.dp))
    }
    Text("项目统计不代表本机汉化覆盖率。译文正式发布后，会在启动游戏时检查更新。",
        color = C.Muted, style = MaterialTheme.typography.bodySmall)
    HorizontalDivider(color = C.Line)
    SheetAction(Glyph.File, "原文对照快照", subtitle = "2026/08/04 冻结记录", external = true) {
        onOpenLink(ZeroProject.SNAPSHOT_URL)
    }
}

@Composable
private fun GitHubDetails(state: GitHubTrackerState, onOpenLink: (String) -> Unit) {
    state.snapshot?.let { release ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(C.Background).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (state.issue == null) "最新正式发布" else "上次同步的正式发布", color = C.Muted, style = MaterialTheme.typography.labelMedium)
            Text(release.tag, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
            Text(time(release.publishedAtMillis, full = true), color = C.Muted, style = MaterialTheme.typography.bodyMedium)
        }
        DetailRow("同步时间", time(release.fetchedAtMillis, full = true))
    }
    if (state.issue != null || state.snapshot == null) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(C.Background).padding(20.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (state.loading) "正在读取发布记录" else if (state.snapshot == null) "暂时无法获取更新" else "本次未能刷新",
                style = MaterialTheme.typography.titleSmall)
            Text(if (state.loading) "正在连接零协会 GitHub。" else when (state.issue) {
                ProjectIssue.RateLimited -> "当前网络的 GitHub 查询触发了限流。" +
                    if (state.retryAtMillis != null && state.retryAtMillis > System.currentTimeMillis())
                        "可在 ${time(state.retryAtMillis)} 后刷新。" else "请稍后刷新。"
                ProjectIssue.Unavailable -> "尚未获取到正式发布，可打开 GitHub 查看。"
                ProjectIssue.Restricted -> "GitHub 暂未允许本次访问，请稍后再试。"
                ProjectIssue.InvalidData -> "发布信息未通过校验，请稍后刷新。"
                else -> "暂时无法连接 GitHub，请稍后刷新。"
            }, color = C.Muted, style = MaterialTheme.typography.bodyMedium)
            if (state.snapshot != null) Text("当前显示上次同步记录。", color = C.Muted,
                style = MaterialTheme.typography.bodySmall)
        }
    }
    FilledTonalButton(onClick = { onOpenLink(ZeroGitHub.RELEASES) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = C.AccentSoft, contentColor = C.OnAccentSoft)) {
        Text("查看 GitHub 发布")
        Spacer(Modifier.width(10.dp))
        LauncherGlyph(Glyph.External, C.OnAccentSoft, Modifier.size(18.dp))
    }
    Text("显示零协会正式汉化包的发布状态。每次启动游戏前仍会检查更新。",
        color = C.Muted, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ProjectMetric(label: String, count: Long?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(count?.let { NumberFormat.getIntegerInstance(Locale.CHINA).format(it) } ?: "—",
            style = MaterialTheme.typography.titleLarge)
        Text(label, color = C.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

private fun percent(value: Float?): String = value?.let {
    NumberFormat.getPercentInstance(Locale.CHINA).apply {
        minimumFractionDigits = 1; maximumFractionDigits = 1
    }.format(it.toDouble())
} ?: "—"

private fun time(millis: Long, full: Boolean = false): String =
    DateTimeFormatter.ofPattern(if (full) "yyyy/MM/dd HH:mm" else "MM/dd HH:mm")
        .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(millis))
