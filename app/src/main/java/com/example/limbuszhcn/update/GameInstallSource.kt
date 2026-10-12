package com.example.limbuszhcn.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

internal data class GameInstallSource(val packageName: String? = null, val label: String = "来源未知") {
    val canOpen: Boolean get() = packageName != null
}

/** Installer attribution is only a navigation hint, never a trust/compatibility decision. */
internal object GameStores {
    const val APKPURE_PAGE = "https://apkpure.net/limbus-company/com.ProjectMoon.LimbusCompany"
    const val UPDATE_ACTION = "com.example.limbuszhcn.SHOW_GAME_UPDATES"
    private val systemInstallers = setOf("com.android.shell", "com.android.packageinstaller", "com.google.android.packageinstaller",
        "com.miui.packageinstaller", "com.vivo.packageinstaller", "com.samsung.android.packageinstaller")
    private val labels = mapOf("com.android.vending" to "Google Play", "com.uptodown" to "Uptodown",
        "com.apkpure.aegon" to "APKPure", "com.qooapp.qoohelper" to "QooApp", "com.taptap" to "TapTap",
        "com.taptap.global" to "TapTap")

    fun describe(installer: String?, visibleLabel: String?): GameInstallSource {
        if (installer == null) return GameInstallSource()
        if (installer == "com.example.limbuszhcn") return GameInstallSource(label = "BYBUS 文件安装")
        if (installer in systemInstallers) return GameInstallSource(label = "本地安装")
        val name = labels[installer] ?: visibleLabel?.filterNot(Char::isISOControl)?.trim()?.take(40)
        return if (name.isNullOrBlank()) GameInstallSource(label = "其他安装来源") else GameInstallSource(installer, name)
    }

    fun installedSource(context: Context): GameInstallSource = runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val installer = if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(PlayListing.PACKAGE).installingPackageName
            else pm.getInstallerPackageName(PlayListing.PACKAGE)
        val label = installer?.let { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() }.getOrNull() }
        describe(installer, label)
    }.getOrDefault(GameInstallSource())

    fun sourceFor(installer: GameInstallSource): GameUpdateSource? = when (installer.packageName) {
        "com.android.vending" -> GameUpdateSource.GooglePlay
        "com.uptodown" -> GameUpdateSource.Uptodown
        else -> null
    }

    fun openSource(context: Context, source: GameInstallSource) {
        val pkg = requireNotNull(source.packageName) { "无法打开此安装来源，请选择下方渠道" }
        val details = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${PlayListing.PACKAGE}")).setPackage(pkg)
        val resolved = context.packageManager.resolveActivity(details, PackageManager.MATCH_DEFAULT_ONLY)
        if (resolved?.activityInfo?.exported == true && runCatching { context.startActivity(details) }.isSuccess) return
        val launcher = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: error("此来源没有可打开的商店页面，请选择其他渠道")
        context.startActivity(launcher)
    }

    fun openPage(context: Context, url: String) {
        require(url == APKPURE_PAGE || GameUpdateSource.entries.any { it.page == url }) { "Unknown game page" }
        val page = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
        if (url == GameUpdateSource.GooglePlay.page &&
            runCatching { context.startActivity(Intent(page).setPackage("com.android.vending")) }.isSuccess) return
        val resolved = context.packageManager.resolveActivity(page, PackageManager.MATCH_DEFAULT_ONLY)
        val target = resolved?.activityInfo
        if (resolved != null && resolved.match > 0 && target != null && target.exported && target.packageName != context.packageName) {
            page.setClassName(target.packageName, target.name)
            if (runCatching { context.startActivity(page) }.isSuccess) return
        }
        context.startActivity(Intent.createChooser(Intent(page).setComponent(null), "打开更新渠道"))
    }
}
