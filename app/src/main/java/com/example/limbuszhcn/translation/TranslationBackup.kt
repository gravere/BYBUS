package com.example.limbuszhcn.translation

import com.example.limbuszhcn.update.sha256
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A portable text-only backup. Never exports the source directory or account/settings files. */
internal object TranslationBackup {
    fun fileName(source: TranslationSource): String =
        source.name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().take(60) + ".zip"

    fun write(prepared: PreparedTranslation, output: OutputStream) {
        val root = prepared.directory.toRealPath()
        val seen = hashSetOf<String>()
        val files = prepared.manifest.entries.map { entry ->
            val name = entry.targetPath
            require(name.startsWith("en/") && name.endsWith(".json") &&
                '\\' !in name && name.split('/').none { it in setOf("", ".", "..") } &&
                seen.add(name.lowercase(java.util.Locale.ROOT))) { "汉化包的资源路径无效" }
            val file = root.resolve(entry.cachePath).toRealPath()
            require(file.startsWith(root) && Files.isRegularFile(file) &&
                Files.size(file) == entry.size && file.sha256() == entry.sha256) { "汉化文件损坏，请先修复或重新导入" }
            name to file
        }
        require(files.isNotEmpty()) { "没有可导出的汉化文本" }
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, file) ->
                zip.putNextEntry(ZipEntry(name))
                Files.newInputStream(file).use { TranslationSources.copyBounded(it, zip, 2L * 1024 * 1024) }
                zip.closeEntry()
            }
        }
    }
}
