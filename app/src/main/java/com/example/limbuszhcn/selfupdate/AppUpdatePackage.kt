package com.example.limbuszhcn.selfupdate

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkUtils
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.ZipFile

class AppUpdateFileProvider : FileProvider()

internal object AppUpdatePackage {
    @Suppress("DEPRECATION")
    fun installedCode(context: Context): Long = context.packageManager.getPackageInfo(context.packageName, 0).let {
        if (Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong()
    }

    /** Verify the actual archive; JSON and its hash are not substitutes for an APK signature. */
    @Suppress("DEPRECATION")
    fun verify(context: Context, file: File, release: AppRelease) {
        require(release.newerThan(installedCode(context))) { "当前已安装相同或更新的版本" }
        require(release.minSdk <= Build.VERSION.SDK_INT) { "此版本需要更新的 Android 系统" }
        require(file.length() == release.apk.size && AppUpdateRepository.hash(file) == release.sha256) { "安装包已变化，请重新下载" }
        val verification = ApkVerifier.Builder(file).setMinCheckedPlatformVersion(Build.VERSION.SDK_INT)
            .setMaxCheckedPlatformVersion(Build.VERSION.SDK_INT).build().verify()
        require(verification.isVerified) { "安装包签名验证失败" }
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = context.packageManager.getPackageInfo(context.packageName, flags)
        val currentSignatures = if (Build.VERSION.SDK_INT >= 28) requireNotNull(installed.signingInfo).apkContentsSigners else installed.signatures
        fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toList()
        val trusted = requireNotNull(currentSignatures).map { digest(it.toByteArray()) }.toSet()
        require(trusted.isNotEmpty() && verification.signerCertificates.map { digest(it.encoded) }.toSet() == trusted) {
            "更新包签名与当前 BYBUS 不一致，已阻止安装"
        }
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().take(50_001).toList()
            require(entries.size <= 50_000 && entries.map { it.name }.distinct().size == entries.size) { "安装包目录异常" }
            val entry = requireNotNull(zip.getEntry("AndroidManifest.xml")) { "缺少安装信息" }
            require(entry.size in 1..2_097_152) { "安装信息大小异常" }
            val out = ByteArrayOutputStream()
            zip.getInputStream(entry).use { input -> AppUpdateRepository.copy(input, 2_097_152) { bytes, count -> out.write(bytes, 0, count) } }
            val bytes = out.toByteArray()
            require(ApkUtils.getPackageNameFromBinaryAndroidManifest(ByteBuffer.wrap(bytes)) == context.packageName &&
                ApkUtils.getLongVersionCodeFromBinaryAndroidManifest(ByteBuffer.wrap(bytes)) == release.versionCode &&
                ApkUtils.getMinSdkVersionFromBinaryAndroidManifest(ByteBuffer.wrap(bytes)) == release.minSdk) {
                "安装包内容与更新信息不一致"
            }
        }
    }
}
