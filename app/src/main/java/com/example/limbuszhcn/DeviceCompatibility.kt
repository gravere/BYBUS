package com.example.limbuszhcn

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import com.lody.virtual.oem.OemPermissionHelper

/**
 * 描述当前手机对汉化容器关键运行条件的支持情况。
 *
 * @property deviceLabel 厂商、品牌与机型组成的可读设备名称。
 * @property androidLabel Android 版本与 API 等级。
 * @property sdkInt 当前 Android API 等级。
 * @property abiLabel 系统提供的 ABI 列表。
 * @property hasRequiredAbi 系统是否提供游戏所需的 arm64 ABI。
 * @property pageSizeBytes 当前内核内存页大小，用于定位新机型的 16 KB 兼容问题。
 * @property is64BitProcess 当前汉化器进程是否以 64 位模式运行。
 * @property isLowRamDevice 系统是否把手机标记为低内存设备。
 * @property isIgnoringBatteryOptimizations 系统是否允许汉化器绕过电池优化。
 * @property warnings 需要用户处理或提交给维护者的兼容性提醒。
 */
internal data class DeviceCompatibilityReport(
    val deviceLabel: String,
    val androidLabel: String,
    val sdkInt: Int,
    val abiLabel: String,
    val hasRequiredAbi: Boolean,
    val pageSizeBytes: Long,
    val is64BitProcess: Boolean,
    val isLowRamDevice: Boolean,
    val isIgnoringBatteryOptimizations: Boolean,
    val warnings: List<String>
) {
    /** Match the shipped native runtime boundary, not merely the APK's minSdk. */
    val isSupported: Boolean
        get() = hasRequiredAbi && is64BitProcess &&
            com.lody.virtual.helper.compat.LimbusNativeStructure.supportsEnvironment(sdkInt, REQUIRED_ABI, pageSizeBytes)

    val blockingReason: String?
        get() = when {
            sdkInt != 36 -> "当前游戏兼容方案仅支持 Android 16（API 36）"
            !hasRequiredAbi || !is64BitProcess -> "当前游戏兼容方案需要 64 位 ARM 运行环境"
            pageSizeBytes != 4096L -> if (pageSizeBytes == 0L) "无法确认系统内存页大小" else "当前游戏兼容方案仅支持 4 KB 内存页"
            else -> null
        }

    /** 面向主界面展示的简短状态。 */
    val statusLabel: String
        get() = when {
            !isSupported -> "设备暂不支持"
            warnings.isNotEmpty() -> "有待确认项"
            else -> "设备条件满足"
        }

    /** 汇总需要用户关注的建议；没有提醒时返回稳定状态说明。 */
    val recommendation: String
        get() = (listOfNotNull(blockingReason) + warnings).joinToString("；").ifBlank {
            "系统与 arm64 基础条件满足；游戏运行仍需实际确认"
        }

    companion object {
        /** 当前游戏 Android 包使用的原生 ABI。 */
        const val REQUIRED_ABI = "arm64-v8a"
    }
}

/**
 * 采集设备兼容信息，并提供常见国产 ROM 的后台运行设置入口。
 *
 * 这里不尝试静默修改系统策略，因为 vivo、iQOO、MIUI、ColorOS 等系统都要求用户
 * 在系统界面确认后台运行权限；应用只负责打开经过 PackageManager 验证的设置页面。
 */
internal class DeviceCompatibility(private val context: Context) {
    /**
     * 读取当前设备的兼容报告。
     *
     * @return 可用于界面展示和 Issue 诊断包的设备兼容信息。
     */
    fun report(): DeviceCompatibilityReport {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val ignoresBatteryOptimizations = runCatching {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        }.getOrDefault(false)
        val hasArm64 = Build.SUPPORTED_64_BIT_ABIS.contains(DeviceCompatibilityReport.REQUIRED_ABI)
        val warnings = buildList {
            if (isAggressiveBackgroundRom() && !ignoresBatteryOptimizations) {
                // 国产 ROM 经常在 Activity 切换到游戏后清理容器 server；这里只提示用户显式放行。
                add("建议在系统设置中允许自启动、后台高耗电和后台运行")
            }
            if (activityManager.isLowRamDevice) {
                add("设备处于低内存模式，启动前建议关闭其他大型应用")
            }
        }
        return DeviceCompatibilityReport(
            deviceLabel = listOf(Build.MANUFACTURER, Build.BRAND, Build.MODEL)
                .filter(String::isNotBlank)
                .distinctBy { it.lowercase() }
                .joinToString(" / "),
            androidLabel = "Android ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）",
            sdkInt = Build.VERSION.SDK_INT,
            abiLabel = Build.SUPPORTED_ABIS.joinToString(","),
            hasRequiredAbi = hasArm64,
            pageSizeBytes = readPageSizeBytes(),
            is64BitProcess = android.os.Process.is64Bit(),
            isLowRamDevice = activityManager.isLowRamDevice,
            isIgnoringBatteryOptimizations = ignoresBatteryOptimizations,
            warnings = warnings
        )
    }

    /**
     * 返回最适合当前 ROM 的后台权限设置页面。
     *
     * @return OEM 页面可用时返回其 Intent，否则回退到系统电池优化或应用详情页面。
     */
    fun settingsIntent(): Intent {
        // 个别 ROM 的属性探测或 PackageManager 实现可能抛出厂商异常，不能因此失去标准设置回退。
        runCatching { OemPermissionHelper.getPermissionActivityIntent(context) }
            .getOrNull()
            ?.let { return it }

        val batterySettings = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (batterySettings.resolveActivity(context.packageManager) != null) {
            return batterySettings
        }

        // 应用详情页是所有 Android 品牌都应提供的最终回退，用户可从这里进入电池与权限设置。
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 判断当前品牌是否通常会额外限制后台进程和自启动。 */
    private fun isAggressiveBackgroundRom(): Boolean {
        val identity = "${Build.MANUFACTURER} ${Build.BRAND}".lowercase()
        return AGGRESSIVE_BACKGROUND_BRANDS.any(identity::contains)
    }

    /** 通过 bionic 的运行时查询读取真实页大小，避免把 4 KB 写死在兼容判断中。 */
    private fun readPageSizeBytes(): Long = runCatching {
        Os.sysconf(OsConstants._SC_PAGESIZE)
    }.getOrDefault(0L)

    companion object {
        private val AGGRESSIVE_BACKGROUND_BRANDS = setOf(
            "vivo",
            "iqoo",
            "bbk",
            "oppo",
            "oneplus",
            "realme",
            "xiaomi",
            "redmi",
            "huawei",
            "honor",
            "meizu"
        )
    }
}
