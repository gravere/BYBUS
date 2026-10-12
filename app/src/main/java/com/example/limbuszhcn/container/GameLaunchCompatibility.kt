package com.example.limbuszhcn.container

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import com.lody.virtual.helper.compat.LimbusNativeProfile
import java.io.File

internal data class GameApkIdentity(val path: String, val size: Long, val modified: Long)
internal data class GameBuildIdentity(val version: Long, val updated: Long, val apks: List<GameApkIdentity>)

/** Host worker only. PackageManager is read again after expensive preparation. */
internal class GameLaunchCompatibility(private val context: Context) {
    private var approved: GameBuildIdentity? = null

    fun requireApproved(): GameBuildIdentity {
        val device = com.example.limbuszhcn.DeviceCompatibility(context).report()
        require(device.isSupported) { requireNotNull(device.blockingReason) }
        val info = installedInfo()
        val identity = identity(info)
        if (identity == approved) return identity
        val profile = LimbusNativeProfile.findApproved(context, info, requireNotNull(info.applicationInfo))
        require(profile.endsWith("-full")) {
            "游戏 ${info.versionName ?: identity.version} 的底层结构未通过自动兼容检查，需要更新 BYBUS。已有登录、资源和汉化文件均保留。"
        }
        return identity.also { approved = it }
    }

    fun requireUnchanged(expected: GameBuildIdentity) {
        requireSameGameBuild(expected, identity(installedInfo()))
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(): PackageInfo = context.packageManager.getPackageInfo(
        LocalContainerRuntime.GAME_PACKAGE, LimbusNativeProfile.SIGNATURE_FLAGS)

    private fun identity(info: PackageInfo): GameBuildIdentity {
        val app = requireNotNull(info.applicationInfo) { "无法读取游戏安装信息，请重试检测" }
        val paths = listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()
        val apks = paths.sorted().map { path ->
            val file = File(path)
            require(file.isFile && file.length() > 0) { "游戏安装文件正在变化，请等待系统更新完成后重试" }
            GameApkIdentity(path, file.length(), file.lastModified())
        }
        @Suppress("DEPRECATION")
        val version = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        return GameBuildIdentity(version, info.lastUpdateTime, apks)
    }
}

internal fun requireSameGameBuild(expected: GameBuildIdentity, actual: GameBuildIdentity) {
    require(expected == actual) { "准备期间游戏已更新，请重新启动以同步最新游戏。已有数据已保留。" }
}
