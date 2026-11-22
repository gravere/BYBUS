package com.example.limbuszhcn

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.example.limbuszhcn.container.GameLanguageStore
import com.example.limbuszhcn.container.LocalContainerRuntime
import com.example.limbuszhcn.update.toLowerHex
import com.lody.virtual.client.ipc.VPackageManager
import java.io.File
import java.security.MessageDigest

/** Only the game's package metadata; no account, preference, URL or installed-app inventory. */
internal object GameInstallationDiagnostics {
    @Suppress("DEPRECATION")
    fun collect(context: Context): String = buildString {
        val pkg = LocalContainerRuntime.GAME_PACKAGE
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES
            else PackageManager.GET_SIGNATURES
        fun section(label: String, load: () -> PackageInfo?) {
            try {
                val info = load()
                if (info == null) { appendLine("$label=absent"); return }
                appendLine("$label.version=${info.versionName}")
                appendLine("$label.code=${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()}")
                val signers = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
                appendLine("$label.signers=${signers.orEmpty().take(4).joinToString(",") {
                    MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toLowerHex()
                }}")
                appendLine("$label.splits=${info.splitNames.orEmpty().take(32).joinToString(",")}")
                info.applicationInfo?.let { app ->
                    // Report only lengths, never the source paths or private storage layout.
                    val paths = listOfNotNull(app.sourceDir) + app.splitSourceDirs.orEmpty()
                    appendLine("$label.apkBytes=${paths.take(33).joinToString(",") { File(it).length().toString() }}")
                }
            } catch (error: Exception) {
                appendLine("$label=unavailable:${error.javaClass.simpleName}")
            }
        }
        section("system") { pm.getPackageInfo(pkg, flags) }
        section("container") { VPackageManager.get().getPackageInfo(pkg, flags, 0) }
        try {
            val installer = if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
                else pm.getInstallerPackageName(pkg)
            appendLine("system.installer=${installer ?: "none"}")
        } catch (error: Exception) {
            appendLine("system.installer=unavailable:${error.javaClass.simpleName}")
        }
        val language = GameLanguageStore(context.filesDir.toPath().resolve("translation-cache")).read()
        appendLine("observedLanguage=${language?.language?.code ?: "not_observed"}")
        appendLine("scope=package-metadata-only; installer is not proof of APK authenticity")
    }.take(8192)
}
