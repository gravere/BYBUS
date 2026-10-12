package com.example.limbuszhcn.update

import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkUtils
import com.android.apksig.internal.apk.AndroidBinXmlParser
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipFile

internal data class GameApk(val path: Path, val packageName: String, val version: Long,
    val versionName: String, val split: String, val configFor: String?, val usesSplits: Set<String>,
    val signer: Set<String>, val minSdk: Int, val hasArm64: Boolean, val hasUnityData: Boolean,
    val requiredTypes: Set<String> = emptySet(), val providedTypes: Set<String> = emptySet())

/** Pinned to the certificate verified on the original Play builds 471/475. New signing keys require review. */
internal object GameApkSet {
    const val CERTIFICATE = com.lody.virtual.helper.compat.LimbusNativeProfile.OFFICIAL_CERTIFICATE
    const val MAX_APKS = 32
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    fun read(path: Path, sdk: Int): GameApk {
        ZipFile(path.toFile()).use { zip ->
            val entries = zip.entries().asSequence().take(50_001).toList()
            require(entries.size <= 50_000 && entries.map { it.name }.distinct().size == entries.size) { "安装包目录异常" }
            require(entries.all { it.size in 0..GamePackageFiles.MAX_BYTES } &&
                entries.sumOf { it.size } <= GamePackageFiles.MAX_BYTES) { "安装包展开大小异常" }
            require(zip.getEntry("AndroidManifest.xml")?.size in 1..2_097_152L) { "安装信息大小异常" }
            require(entries.filter { it.name.startsWith("lib/") }.all { it.size <= 512L * 1024 * 1024 }) { "运行库大小异常" }
        }
        val signature = ApkVerifier.Builder(path.toFile()).setMinCheckedPlatformVersion(sdk)
            .setMaxCheckedPlatformVersion(sdk).build().verify()
        require(signature.isVerified) { "安装包签名校验失败，请重新获取原始安装包" }
        val signers = signature.signerCertificates.map { certificate ->
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
        }.toSet()
        require(signers == setOf(CERTIFICATE)) { "此包的签名与已验证的官方游戏不同" }
        return ZipFile(path.toFile()).use { zip ->
            val entry = requireNotNull(zip.getEntry("AndroidManifest.xml")) { "缺少游戏安装信息" }
            require(entry.size in 1..2_097_152) { "安装信息大小异常" }
            val bytes = zip.getInputStream(entry).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= 2_097_152) { "安装信息大小异常" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val pkg = ApkUtils.getPackageNameFromBinaryAndroidManifest(ByteBuffer.wrap(bytes))
            val version = ApkUtils.getLongVersionCodeFromBinaryAndroidManifest(ByteBuffer.wrap(bytes))
            val minSdk = ApkUtils.getMinSdkVersionFromBinaryAndroidManifest(ByteBuffer.wrap(bytes))
            val parser = AndroidBinXmlParser(ByteBuffer.wrap(bytes))
            fun attribute(name: String, namespace: String = ""): String? {
                val index = (0 until parser.attributeCount).firstOrNull {
                    parser.getAttributeName(it) == name && parser.getAttributeNamespace(it).orEmpty() == namespace
                } ?: return null
                return parser.getAttributeStringValue(index)
            }
            var split = ""
            var configFor: String? = null
            var versionName = version.toString()
            var requiredTypes = emptySet<String>()
            var providedTypes = emptySet<String>()
            val dependencies = mutableSetOf<String>()
            while (parser.next() != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
                if (parser.eventType != AndroidBinXmlParser.EVENT_START_ELEMENT) continue
                if (parser.depth == 1 && parser.name == "manifest") {
                    split = attribute("split").orEmpty()
                    configFor = attribute("configForSplit", ANDROID_NS)
                    versionName = attribute("versionName", ANDROID_NS) ?: versionName
                    requiredTypes = attribute("requiredSplitTypes", ANDROID_NS).orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).toSet()
                    providedTypes = attribute("splitTypes", ANDROID_NS).orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).toSet()
                }
                if (parser.depth == 2 && parser.name == "uses-split") {
                    dependencies += requireNotNull(attribute("name", ANDROID_NS))
                }
            }
            val hasArm64 = zip.getEntry("lib/arm64-v8a/libil2cpp.so") != null
            // Verified install-time Unity pack; arbitrary .obb files are not a substitute.
            val hasUnityData = split == "UnityDataAssetPack" && zip.entries().asSequence().any {
                !it.isDirectory && it.name.startsWith("assets/bin/Data/") && it.size > 0
            } || split.isEmpty() && (zip.getEntry("assets/bin/Data/data.unity3d") != null ||
                zip.getEntry("assets/bin/Data/globalgamemanagers") != null && zip.getEntry("assets/bin/Data/sharedassets4.assets") != null)
            GameApk(path, pkg, version, versionName, split, configFor, dependencies, signers, minSdk, hasArm64, hasUnityData, requiredTypes, providedTypes)
        }
    }

    fun validate(apks: List<GameApk>, installed: Long?, sdk: Int): GameApk {
        require(apks.size in 1..MAX_APKS) { "请选择一套完整安装包" }
        require(apks.all { it.packageName == PlayListing.PACKAGE }) { "所选文件含其他应用的安装包" }
        require(apks.map { it.split }.distinct().size == apks.size) { "包含重复分包或多套安装包，请重新选择" }
        val base = requireNotNull(apks.singleOrNull { it.split.isEmpty() }) { "缺少唯一的基础 APK" }
        require(base.version > 0 && apks.all { it.version == base.version }) { "分包版本不一致" }
        require(installed == null || base.version >= installed) { "不能降级覆盖已有游戏" }
        require(apks.all { it.signer == setOf(CERTIFICATE) }) { "游戏签名不匹配" }
        require(apks.all { it.minSdk <= sdk }) { "此版本不支持当前 Android 系统" }
        val names = apks.map { it.split }.toSet()
        val types = apks.flatMap { it.providedTypes }.toSet()
        require(apks.all { it.requiredTypes.all(types::contains) }) { "缺少必需的设备分包类型" }
        require(apks.all { apk -> apk.usesSplits.all { it in names } &&
            (apk.configFor.isNullOrEmpty() || apk.configFor in names) }) { "缺少依赖分包，请选择完整包" }
        require(apks.any { it.hasArm64 }) { "缺少 ARM64 游戏运行库" }
        require(apks.any { it.hasUnityData }) { "缺少 Unity 资源分包；仅 base.apk 无法完整更新" }
        return base
    }
}

/** Bounded extraction with generated destination names; archive paths are never used as output paths. */
internal object GamePackageFiles {
    const val MAX_BYTES = 4L * 1024 * 1024 * 1024

    fun copy(input: java.io.InputStream, target: Path, limit: Long): Long {
        var size = 0L
        Files.newOutputStream(target).use { output ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                require(size <= limit) { "安装包过大或解压大小异常" }
                output.write(buffer, 0, count)
            }
        }
        require(size > 0) { "安装包为空" }
        return size
    }

    fun unpack(input: Path, directory: Path): List<Path> = ZipFile(input.toFile()).use { zip ->
        if (zip.getEntry("AndroidManifest.xml") != null) return listOf(input)
        val entries = zip.entries().asSequence().take(513).toList()
        require(entries.size <= 512) { "安装包包含过多文件" }
        require(entries.map { it.name }.distinct().size == entries.size) { "安装包内文件名重复" }
        require(entries.none { it.name.startsWith('/') || it.name.contains('\\') ||
            it.name.split('/').any { part -> part == ".." || part.contains(':') } }) { "安装包路径异常" }
        require(entries.none { it.name.endsWith(".obb", true) }) { "此资源包格式尚未支持，请使用完整 APK 分包" }
        val apks = entries.filter { !it.isDirectory && it.name.endsWith(".apk", true) }
        require(apks.size in 1..GameApkSet.MAX_APKS) { "未找到一套完整 APK，或包含多套设备变体" }
        require(entries.filter { it !in apks && !it.isDirectory }.all { it.size in 0..8_388_608 }) { "安装包含未知大文件" }
        var total = 0L
        apks.mapIndexed { index, entry ->
            require(entry.size in 1..(MAX_BYTES - total) && entry.compressedSize > 0 &&
                entry.size / entry.compressedSize.coerceAtLeast(1) < 200) { "安装包解压大小异常" }
            val target = directory.resolve("part-$index.apk")
            val size = zip.getInputStream(entry).use { copy(it, target, MAX_BYTES - total) }
            require(size == entry.size) { "安装包解压不完整" }
            total += size
            target
        }
    }
}
