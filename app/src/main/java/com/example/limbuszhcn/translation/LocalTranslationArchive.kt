package com.example.limbuszhcn.translation

import com.example.limbuszhcn.container.TranslationJsonPairer
import com.example.limbuszhcn.storage.AtomicFiles
import com.example.limbuszhcn.update.*
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Data-only ZIP/7z importer. File signatures, names and expanded byte budgets are verified. */
internal class LocalTranslationArchive(private val cache: Path) {
    fun extract(archive: Path, revision: String): PreparedTranslation {
        require(revision.matches(Regex("local-[a-f0-9]{64}")))
        val hash = archive.sha256()
        require(revision == "local-$hash")
        val root = cache.resolve("patch-cache"); Files.createDirectories(root)
        val target = root.resolve(revision)
        verified(target, revision, hash)?.let { return it }
        val staging = root.resolve(".$revision.import")
        if (Files.exists(staging)) AtomicFiles.deleteOwnedTree(root, staging)
        Files.createDirectories(staging)
        val entries = mutableListOf<PatchFileEntry>(); val names = hashSetOf<String>()
        var total = 0L; var count = 0; var hasChinese = false
        fun entry(name: String, input: InputStream) {
            require(++count <= 5000) { "汉化包文件过多" }
            val normalized = safePath(name)
            val jsonName = mapResource(normalized)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(32768); var size = 0L
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                val n = input.read(buffer); if (n < 0) break
                size += n; total += n
                require(total <= 128L * 1024 * 1024 && size <= 16L * 1024 * 1024) { "汉化包解压大小超出限制" }
                if (jsonName != null) {
                    require(size <= 2L * 1024 * 1024) { "单个文本文件过大" }; output.write(buffer, 0, n)
                }
            }
            if (jsonName == null) return
            val bytes = output.toByteArray()
            val text = bytes.toString(Charsets.UTF_8)
            require(!text.contains('\uFFFD')) { "汉化文本不是有效 UTF-8" }
            val texts = TranslationJsonPairer.releasedTexts(text)
            if (texts.isEmpty()) return
            hasChinese = hasChinese || texts.any { value -> value.any { it.code in 0x3400..0x9fff } }
            require(names.add(jsonName.lowercase(java.util.Locale.ROOT))) { "汉化包包含重复文本文件" }
            val file = staging.resolve("files/en/$jsonName")
            Files.createDirectories(file.parent); Files.write(file, bytes)
            entries += PatchFileEntry(normalized, "files/en/$jsonName", "en/$jsonName", size, file.sha256())
        }
        try {
            val header = Files.newInputStream(archive).use { input ->
                val bytes = ByteArray(6); var used = 0
                while (used < 6) { val n = input.read(bytes, used, 6 - used); if (n < 0) break; used += n }
                bytes.copyOf(used)
            }
            when {
                header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4b.toByte() ->
                    ZipInputStream(Files.newInputStream(archive)).use { zip ->
                        while (true) { val e = zip.nextEntry ?: break
                            if (e.isDirectory) { safePath(e.name); require(++count <= 5000) }
                            else entry(e.name, zip)
                            zip.closeEntry()
                        }
                    }
                header.contentEquals(byteArrayOf(0x37, 0x7a, 0xbc.toByte(), 0xaf.toByte(), 0x27, 0x1c)) ->
                    SevenZFile.builder().setPath(archive).setMaxMemoryLimitKiB(128 * 1024).get().use { seven ->
                        while (true) { val e = seven.nextEntry ?: break
                            if (e.isDirectory) { safePath(e.name); require(++count <= 5000) }
                            else entry(e.name, object : InputStream() {
                                override fun read() = seven.read()
                                override fun read(b: ByteArray, off: Int, len: Int) = seven.read(b, off, len)
                            })
                        }
                    }
                else -> error("请选择 ZIP 或 7z 汉化文本包")
            }
            require(entries.isNotEmpty()) { "未找到支持的 JSON 汉化文本；不支持插件或图片替换包" }
            require(hasChinese) { "包内未找到中文显示文本" }
            val manifest = PatchManifest(revision, null, hash, count, entries.size, entries.sortedBy { it.targetPath })
            staging.resolve("manifest.json").writeText(org.json.JSONObject(manifest.toJson())
                .put("bybusImportSchema", IMPORT_SCHEMA).toString())
            AtomicFiles.replaceDirectory(staging, target)
            return PreparedTranslation(target, manifest)
        } finally { if (Files.exists(staging)) AtomicFiles.deleteOwnedTree(root, staging) }
    }

    private fun verified(dir: Path, revision: String, hash: String): PreparedTranslation? = runCatching {
        val path = dir.resolve("manifest.json"); require(Files.size(path) <= 4 * 1024 * 1024)
        val json = path.readText()
        require(org.json.JSONObject(json).optInt("bybusImportSchema") == IMPORT_SCHEMA)
        val m = PatchManifest.fromJson(json)
        require(m.requestedVersion == revision && m.archiveSha256 == hash && m.entries.isNotEmpty() && m.entries.size <= 5000)
        val real = dir.toRealPath(); val seen = hashSetOf<String>()
        m.entries.forEach { e ->
            val mapped = requireNotNull(mapResource(safePath(e.sourcePath)))
            require(e.cachePath == "files/en/$mapped" && e.targetPath == "en/$mapped" && seen.add(mapped.lowercase(java.util.Locale.ROOT)))
            val file = dir.resolve(e.cachePath).toRealPath()
            require(file.startsWith(real) && Files.size(file) == e.size && file.sha256() == e.sha256)
        }
        PreparedTranslation(dir, m)
    }.getOrNull()

    companion object {
        private const val IMPORT_SCHEMA = 2
        private val RESOURCE_FOLDERS = setOf("StoryData", "BattleHint", "RPGSystem", "EGOVoiceDig",
            "PersonalityVoiceDlg", "BattleAnnouncerDlg")
        fun safePath(raw: String): String {
            val path = raw.replace('\\', '/').trimEnd('/')
            require(path.length in 1..512 && !path.startsWith('/') && ':' !in path &&
                path.none { it.code < 32 } && path.split('/').none { it in setOf("", ".", "..") }) { "汉化包包含不安全路径" }
            return path
        }
        fun mapResource(path: String): String? {
            if (!path.endsWith(".json", true)) return null
            val prefixes = listOf("LimbusCompany_Data/Lang/LLC_zh-CN/", "LLC_zh-CN/", "Localize/jp/", "Localize/en/", "Localize/kr/", "jp/", "en/", "kr/", "files/en/")
            val prefix = prefixes.firstOrNull { path.startsWith(it) }
            val relative = prefix?.let { path.removePrefix(it) } ?: path
            if (relative.startsWith("Info/") || relative.substringAfterLast('/') in setOf("manifest.json", "bybus-pack.json", "version.json")) return null
            // Flat resources and the game's known nested resource directories are supported.
            if (prefix == null && '/' in relative && relative.substringBefore('/') !in RESOURCE_FOLDERS) return null
            val folder = relative.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
            val file = relative.substringAfterLast('/').removePrefix("EN_").removePrefix("JP_").removePrefix("KR_")
            return "$folder" + "EN_$file"
        }
    }
}
