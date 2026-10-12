package com.example.limbuszhcn.container

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File

class AndroidInstalledGameImporter(
    private val packageManager: PackageManager,
    private val importDirectory: File,
    private val packageName: String = LocalContainerRuntime.GAME_PACKAGE
) {
    // The name is retained for callers; snapshot copying is owned by the import transaction.
    fun copyInstalledGame(): InstalledGameImport {
        val appInfo = applicationInfo()
        val packageInfo = packageInfo(packageManager, packageName)
        val sourcePaths = listOfNotNull(appInfo.sourceDir) + appInfo.splitSourceDirs.orEmpty().toList()
        require(sourcePaths.isNotEmpty()) { "未找到已安装游戏的 APK 路径" }

        require(sourcePaths.all { File(it).isFile }) { "无法读取已安装游戏的完整 APK 集合" }
        return InstalledGameImport(
            packageName = packageName,
            versionCode = packageInfo.versionCodeCompat(),
            splitApkPaths = sourcePaths
        )
    }

    private fun applicationInfo(): ApplicationInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getApplicationInfo(packageName, 0)
        }

    companion object {
        fun installedVersionName(packageManager: PackageManager): String? =
            packageInfo(packageManager, LocalContainerRuntime.GAME_PACKAGE).versionName

        fun installedVersionCode(
            packageManager: PackageManager,
            packageName: String = LocalContainerRuntime.GAME_PACKAGE
        ): Long = packageInfo(packageManager, packageName).versionCodeCompat()

        private fun packageInfo(packageManager: PackageManager, packageName: String): PackageInfo =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }

        private fun PackageInfo.versionCodeCompat(): Long =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                longVersionCode
            } else {
                @Suppress("DEPRECATION")
                versionCode.toLong()
            }
    }
}

data class InstalledGameImport(
    val packageName: String,
    val versionCode: Long,
    val splitApkPaths: List<String>
)
