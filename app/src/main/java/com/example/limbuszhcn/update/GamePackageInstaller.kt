package com.example.limbuszhcn.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import com.example.limbuszhcn.LaunchCoordinator
import com.example.limbuszhcn.container.AndroidInstalledGameImporter
import com.example.limbuszhcn.storage.AtomicFiles
import com.lody.virtual.helper.compat.LimbusNativeProfile
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID

internal data class GameInstallReview(val version: String, val code: Long, val apkCount: Int, val bytes: Long)
internal data class PreparedGameUpdate(val apks: List<Path>, val review: GameInstallReview, val installedIdentity: String)

/** Only installs into Android's real PackageManager. Container synchronization remains the existing path. */
internal class GamePackageInstaller(private val context: Context) {
    private val owner = context.filesDir.toPath().resolve("game-package-update")
    private val staging = owner.resolve("current")
    private val receipt = owner.resolve("session.properties")
    private val installer get() = context.packageManager.packageInstaller

    private fun saved(): Properties? = runCatching { AtomicFiles.readProperties(receipt) }.getOrNull()
    fun hasSession(): Boolean = saved() != null

    /** Interrupted writes cannot be resumed. Sealed sessions can be explicitly resumed or cancelled. */
    fun recover(): Boolean {
        val id = saved()?.getProperty("id")?.toIntOrNull()
        val info = id?.let { installer.getSessionInfo(it) }
        if (info == null || !info.isSealed) {
            if (info != null) installer.abandonSession(info.sessionId)
            Files.deleteIfExists(receipt)
        }
        clearStaging()
        return info?.isSealed == true
    }

    @Suppress("DEPRECATION")
    private fun installedIdentity(): String = try {
        val info = context.packageManager.getPackageInfo(PlayListing.PACKAGE, 0)
        val app = requireNotNull(info.applicationInfo)
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        "$version:${info.lastUpdateTime}:" + (listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()).sorted().joinToString("|")
    } catch (_: android.content.pm.PackageManager.NameNotFoundException) { "missing" }

    fun prepare(uris: List<Uri>, progress: (String) -> Unit): PreparedGameUpdate {
        require(!hasSession()) { "请先完成或取消上一次安装" }
        require(uris.size in 1..GameApkSet.MAX_APKS && uris.distinct().size == uris.size) { "请选择一套完整安装包" }
        val identity = installedIdentity()
        clearStaging()
        Files.createDirectories(staging)
        try {
            var bytes = 0L
            val apks = mutableListOf<Path>()
            uris.forEachIndexed { index, uri ->
                require(uri.scheme == "content") { "请选择本机文件提供器中的安装包" }
                progress("读取安装包 ${index + 1}/${uris.size}")
                val file = staging.resolve("input-$index.apk")
                bytes += requireNotNull(context.contentResolver.openInputStream(uri)).use {
                    GamePackageFiles.copy(it, file, GamePackageFiles.MAX_BYTES - bytes)
                }
                val unpacked = GamePackageFiles.unpack(file, staging)
                if (unpacked != listOf(file)) {
                    require(uris.size == 1) { "压缩包请单独选择，不能与其他 APK 混合" }
                    Files.delete(file)
                }
                apks += unpacked
            }
            val records = apks.mapIndexed { index, path ->
                progress("校验签名与分包 ${index + 1}/${apks.size}")
                GameApkSet.read(path, Build.VERSION.SDK_INT)
            }
            val installed = runCatching { AndroidInstalledGameImporter.installedVersionCode(context.packageManager) }.getOrNull()
            val base = GameApkSet.validate(records, installed, Build.VERSION.SDK_INT)
            progress("检查 BYBUS 兼容性")
            val app = ApplicationInfo().apply {
                packageName = PlayListing.PACKAGE
                sourceDir = base.path.toString()
                publicSourceDir = sourceDir
                splitSourceDirs = records.filter { it != base }.map { it.path.toString() }.toTypedArray()
            }
            @Suppress("DEPRECATION")
            val info = PackageInfo().apply {
                packageName = PlayListing.PACKAGE
                versionName = base.versionName
                if (Build.VERSION.SDK_INT >= 28) setLongVersionCode(base.version) else versionCode = base.version.toInt()
                applicationInfo = app
                // GameApkSet has already cryptographically verified every APK in this set.
                // Supply the same official signer evidence used by installed-game inspection.
                val archiveInfo = requireNotNull(context.packageManager.getPackageArchiveInfo(
                    base.path.toString(), LimbusNativeProfile.SIGNATURE_FLAGS)) { "无法读取已验证的安装包签名" }
                if (Build.VERSION.SDK_INT >= 28) signingInfo = archiveInfo.signingInfo
                else signatures = archiveInfo.signatures
            }
            require(LimbusNativeProfile.findApproved(context, info, app).endsWith("-full")) {
                "此安装包的底层结构未通过自动兼容检查，需要更新 BYBUS。已安装的游戏和资源未改变。"
            }
            require(identity == installedIdentity()) { "校验期间游戏已更新，请重新选择安装包" }
            return PreparedGameUpdate(listOf(base.path) + records.filter { it != base }.map { it.path },
                GameInstallReview(base.versionName, base.version, apks.size, apks.sumOf(Files::size)), identity)
        } catch (error: Exception) {
            clearStaging()
            throw error
        }
    }

    fun commit(prepared: PreparedGameUpdate) {
        require(context.packageManager.canRequestPackageInstalls()) { "请先允许 BYBUS 安装应用" }
        require(!hasSession() && prepared.installedIdentity == installedIdentity()) { "游戏状态已变化，请重新选择安装包" }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(PlayListing.PACKAGE)
            setSize(prepared.review.bytes)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            AtomicFiles.properties(receipt, Properties().apply {
                setProperty("id", id.toString()); setProperty("token", UUID.randomUUID().toString())
            })
            installer.openSession(id).use { session ->
                prepared.apks.forEachIndexed { index, file ->
                    session.openWrite(if (index == 0) "base.apk" else "split-$index.apk", 0, Files.size(file)).use { output ->
                        Files.newInputStream(file).use { it.copyTo(output, 128 * 1024) }
                        session.fsync(output)
                    }
                }
                session.commit(callback(id))
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(id) }
            Files.deleteIfExists(receipt)
            clearStaging()
            throw error
        }
        // Android owns the session files now. A cache cleanup failure must not abandon a committed session.
        runCatching { clearStaging() }.onFailure {
            com.example.limbuszhcn.PersistentDiagnosticLog.warn("LimbusZhCN", "Local APK staging cleanup deferred", it)
        }
    }

    private fun callback(id: Int): android.content.IntentSender {
        val token = requireNotNull(saved()?.getProperty("token"))
        val result = Intent(context, GameInstallReceiver::class.java)
            .setAction(RESULT_ACTION).setData(Uri.parse("bybus-install://session/$id/$token"))
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, id, result, flags).intentSender
    }

    fun accepts(intent: Intent): Boolean {
        val record = saved() ?: return false
        return intent.action == RESULT_ACTION && intent.data?.toString() ==
            "bybus-install://session/${record.getProperty("id")}/${record.getProperty("token")}" &&
            intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1).toString() == record.getProperty("id")
    }

    fun resume() {
        val id = requireNotNull(saved()?.getProperty("id")?.toIntOrNull()) { "没有待完成的安装" }
        require(installer.getSessionInfo(id) != null) { "系统安装已结束，请返回后重新检测版本" }
        installer.openSession(id).use { it.commit(callback(id)) }
    }

    fun finish() { Files.deleteIfExists(receipt); clearStaging() }

    fun cancel() {
        saved()?.getProperty("id")?.toIntOrNull()?.let { id ->
            if (installer.getSessionInfo(id) != null) installer.abandonSession(id)
        }
        finish()
    }

    fun clearStaging() {
        if (Files.exists(staging)) AtomicFiles.deleteOwnedTree(owner, staging)
    }

    companion object { const val RESULT_ACTION = "com.example.limbuszhcn.GAME_INSTALL_RESULT" }
}

/** Explicit, non-exported callback; unmatched or stale sessions cannot change installation state. */
class GameInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        LaunchCoordinator.get(context.applicationContext as android.app.Application).installationResult(intent)
    }
}
