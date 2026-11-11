package com.example.limbuszhcn.container

import com.example.limbuszhcn.storage.AtomicFiles
import com.example.limbuszhcn.update.PatchManifest
import com.example.limbuszhcn.update.sha256
import com.example.limbuszhcn.update.toLowerHex
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.readText

/** A verified release snapshot. Original game resources are neither required nor modified. */
internal class LiveTranslationStore(private val cache: Path) {
    private val root = cache.resolve("translation-index")
    private val descriptor = root.resolve("live-resources.json")
    private val fonts = root.resolve("live-fonts.bin")

    fun prepare(directory: Path, manifest: PatchManifest, resourceKey: (String) -> String): Snapshot {
        // Android can expose the same private directory through /data/user/0 and
        // /data/data. Resolve both operands before creating persistent relative paths.
        val cacheRoot = cache.toRealPath()
        val patchRoot = cacheRoot.resolve("patch-cache").toRealPath()
        require(patchRoot.startsWith(cacheRoot)) { "汉化缓存目录无效" }
        val entries = manifest.entries.filter { it.cachePath.endsWith(".json", true) }.map { entry ->
            val path = directory.resolve(entry.cachePath).toRealPath()
            require(path.startsWith(patchRoot) && Files.size(path) == entry.size) { "汉化资源路径或大小无效" }
            Entry(resourceKey(entry.targetPath), cacheRoot.relativize(path).toString().replace('\\', '/'),
                entry.size, entry.sha256)
        }
        require(entries.isNotEmpty() && entries.size <= 5000 && entries.map { it.key }.toSet().size == entries.size)
        require(entries.all { validKey(it.key) && SHA.matches(it.hash) && it.size in 1..MAX_JSON_BYTES })
        read()?.takeIf { it.version == manifest.requestedVersion && it.archive == manifest.archiveSha256 &&
            it.entries == entries && it.fontHash == fonts.sha256() }?.let { return it }

        val labels = sortedSetOf<String>()
        entries.forEach { entry ->
            val file = cache.resolve(entry.path)
            require(file.sha256() == entry.hash) { "汉化资源校验失败" }
            val json = file.readText(Charsets.UTF_8)
            // Self-pairing applies exactly the same field/identity restrictions as runtime merging.
            TranslationJsonPairer.releasedTexts("Localize/${entry.key}", json).forEach { text ->
                if (text.any { c -> c.code in 0x3400..0x9fff } &&
                    text.none { c -> c.code in 0x3040..0x30ff || c.code in 0xac00..0xd7af }) labels += text
            }
        }
        require(labels.isNotEmpty() && labels.size <= 1_000_000) { "汉化包没有有效中文显示文本" }
        Files.createDirectories(root)
        val pendingFonts = root.resolve(".live-fonts.tmp")
        DataOutputStream(BufferedOutputStream(Files.newOutputStream(pendingFonts))).use { out ->
            out.write(byteArrayOf(76, 90, 70, 67, 49, 0)) // LZFC1
            out.writeInt(labels.size)
            labels.forEach { text -> val bytes = text.toByteArray(Charsets.UTF_8); out.writeInt(bytes.size); out.write(bytes) }
        }
        AtomicFiles.move(pendingFonts, fonts, true)
        val snapshot = Snapshot(manifest.requestedVersion, manifest.archiveSha256, entries, fonts.sha256(), labels.size)
        val json = JSONObject().put("schema", SCHEMA).put("version", snapshot.version).put("archive", snapshot.archive)
            .put("fontHash", snapshot.fontHash).put("labels", labels.size).put("entries", JSONArray().apply {
                entries.forEach { put(JSONObject().put("key", it.key).put("path", it.path).put("size", it.size).put("hash", it.hash)) }
            })
        val pending = root.resolve(".live-resources.tmp")
        Files.write(pending, json.toString().toByteArray(Charsets.UTF_8))
        AtomicFiles.move(pending, descriptor, true)
        return snapshot
    }

    fun read(): Snapshot? = runCatching {
        require(Files.size(descriptor) in 1..4_000_000 && Files.isRegularFile(fonts))
        val json = JSONObject(descriptor.readText(Charsets.UTF_8))
        require(json.getInt("schema") == SCHEMA)
        val array = json.getJSONArray("entries")
        require(array.length() in 1..5000)
        val entries = (0 until array.length()).map { index -> array.getJSONObject(index).let {
            Entry(it.getString("key"), it.getString("path"), it.getLong("size"), it.getString("hash"))
        } }
        require(entries.all { validKey(it.key) && it.path.startsWith("patch-cache/") &&
            it.path.split('/').none { part -> part == ".." || part == "." || part.isEmpty() } &&
            '\\' !in it.path && ':' !in it.path && it.size in 1..MAX_JSON_BYTES && SHA.matches(it.hash) })
        require(entries.map { it.key }.toSet().size == entries.size)
        val result = Snapshot(json.getString("version"), json.getString("archive"), entries,
            json.getString("fontHash"), json.getInt("labels"))
        require(result.version.isNotBlank() && SHA.matches(result.archive) && SHA.matches(result.fontHash) && result.labels > 0)
        result
    }.getOrNull()

    fun open(): LiveResourceTranslator? = read()?.takeIf { it.fontHash == fonts.sha256() }
        ?.let { LiveResourceTranslator(cache, it) }

    fun deactivate() { Files.deleteIfExists(descriptor); Files.deleteIfExists(fonts) }

    data class Entry(val key: String, val path: String, val size: Long, val hash: String)
    data class Snapshot(val version: String, val archive: String, val entries: List<Entry>, val fontHash: String, val labels: Int)

    companion object {
        private const val SCHEMA = 1
        const val MAX_JSON_BYTES = 16L * 1024 * 1024
        private val SHA = Regex("[0-9a-f]{64}")
        private fun validKey(key: String): Boolean = key.startsWith("jp/") && key.length <= 512 &&
            key.substringAfterLast('/').startsWith("JP_") && key.endsWith(".json") &&
            key.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
            key.none { it.code < 32 || it == '\\' || it == ':' }
    }
}

/** Per-process snapshot with a bounded content-addressed cache; no polling or game-file access. */
internal class LiveResourceTranslator(private val cache: Path, snapshot: LiveTranslationStore.Snapshot) {
    private val entries = snapshot.entries.associateBy { it.key }
    private val uniqueNames = snapshot.entries.groupBy { it.key.substringAfterLast('/').removeSuffix(".json") }
        .mapNotNull { (name, rows) -> rows.singleOrNull()?.let { name to it } }.toMap()
    private data class Cached(val sourceHash: String, val merged: String?, val bytes: Int)
    private val results = LinkedHashMap<String, Cached>(16, .75f, true)
    private var bytes = 0

    @Synchronized
    fun translate(resource: String, original: String): String? {
        if (resource.length > 1024 || original.length > LiveTranslationStore.MAX_JSON_BYTES ||
            resource.any { it.code < 32 || it == '\\' } || resource.split('/').any { it == ".." || it == "." }) return null
        val entry = resolve(resource) ?: return null
        if (original.trimStart('\uFEFF', ' ', '\r', '\n', '\t').firstOrNull() !in setOf('{', '[')) return null
        val sourceHash = digest(original.toByteArray(Charsets.UTF_8))
        results[entry.key]?.takeIf { it.sourceHash == sourceHash }?.let { return it.merged }
        val file = cache.resolve(entry.path).toRealPath()
        require(file.startsWith(cache.resolve("patch-cache").toRealPath()) && Files.size(file) == entry.size)
        val data = Files.readAllBytes(file)
        require(digest(data) == entry.hash) { "运行时汉化文件校验失败" }
        val merged = TranslationJsonPairer.merge("Localize/${entry.key}", original, data.toString(Charsets.UTF_8))
        val cost = (merged?.length ?: 0) * 2 + 256
        results.remove(entry.key)?.let { bytes -= it.bytes }
        if (cost <= CACHE_BYTES) {
            results[entry.key] = Cached(sourceHash, merged, cost); bytes += cost
            while (bytes > CACHE_BYTES || results.size > 512) {
                val first = results.entries.iterator(); bytes -= first.next().value.bytes; first.remove()
            }
        }
        return merged
    }

    private fun resolve(resource: String): LiveTranslationStore.Entry? {
        // TextAsset names carry no directory; accept only a unique manifest basename.
        if ('/' !in resource) return uniqueNames[resource.removeSuffix(".json")]
        val relative = when {
            resource.startsWith("jp/") -> resource
            resource.startsWith("Localize/jp/") -> resource.removePrefix("Localize/")
            resource.contains("/Localize/jp/") -> "jp/" + resource.substringAfter("/Localize/jp/")
            else -> return null
        }
        return entries[if (relative.endsWith(".json")) relative else "$relative.json"]
    }

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toLowerHex()
    companion object { private const val CACHE_BYTES = 4 * 1024 * 1024 }
}
