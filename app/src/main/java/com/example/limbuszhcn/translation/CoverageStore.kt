package com.example.limbuszhcn.translation

import com.example.limbuszhcn.gamefs.GameStorage
import com.example.limbuszhcn.update.PatchManifest
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path

internal data class SavedCoverage(val fingerprint: String, val report: CoverageReport)

/** Result history is independent of the selected source, so changed resources retain a readable last report. */
internal class CoverageStore(private val cache: Path) {
    private val file get() = cache.resolve("coverage/result.json")
    fun fingerprint(source: TranslationSource, storage: GameStorage): String {
        // A display freshness hint only: activation still uses the full archive validator.
        val revision = if (!source.isOfficial) source.revision else {
            val patches = cache.resolve("patch-cache")
            val version = if (!Files.isDirectory(patches)) null else Files.list(patches).use { paths ->
                paths.filter { Files.isDirectory(it) && it.fileName.toString().matches(Regex("[0-9]{1,18}")) }
                    .iterator().asSequence().maxByOrNull { it.fileName.toString().toLong() }
            }
            version?.resolve("manifest.json")?.takeIf { Files.isRegularFile(it) && Files.size(it) <= 4 * 1024 * 1024 }?.let {
                PatchManifest.fromJson(String(Files.readAllBytes(it), Charsets.UTF_8)).archiveSha256
            } ?: "not-downloaded"
        }
        return AiTranslationJob.digest("coverage-v2|${source.id}|$revision|${resourceFingerprint(storage)}")
    }

    @Synchronized private fun resourceFingerprint(storage: GameStorage): String {
        val metadata = storage.japaneseResourceMetadata() ?: return storage.japaneseResourceStamp()
        val index = cache.resolve("coverage/resources.json")
        val previous = runCatching {
            require(Files.size(index) <= 8 * 1024 * 1024)
            JSONObject(String(Files.readAllBytes(index), Charsets.UTF_8))
        }.getOrDefault(JSONObject())
        val current = JSONObject(); val hashes = linkedMapOf<String, String>(); var readBytes = 0L
        var changed = previous.length() != metadata.size
        metadata.toSortedMap().forEach { (key, stamp) ->
            val saved = previous.optJSONObject(key)
            val oldHash = saved?.optString("hash").orEmpty()
            val hash = if (saved?.optString("stamp") == stamp && oldHash.matches(Regex("[a-f0-9]{64}"))) oldHash else {
                changed = true
                val bytes = requireNotNull(storage.read(storage.rootPath().trimEnd('/') + "/$key"))
                readBytes += bytes.size; require(bytes.size <= 8 * 1024 * 1024 && readBytes <= 128L * 1024 * 1024)
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                digest.joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            hashes[key] = hash
            current.put(key, JSONObject().put("stamp", stamp).put("hash", hash))
        }
        if (changed) {
            check(storage.japaneseResourceMetadata() == metadata) { "游戏文本正在变化，请在资源下载结束后检查" }
            TranslationSources.writeAtomic(index, current.toString())
        }
        // A timestamp change with identical text does not invalidate the report.
        return AiTranslationJob.digest(hashes.entries.joinToString("\n") { "${it.key}|${it.value}" })
    }

    fun save(value: SavedCoverage) {
        val r = value.report
        val json = JSONObject().put("schema", 1).put("fingerprint", value.fingerprint)
            .put("sourceName", r.sourceName).put("checkedAt", r.checkedAt).put("files", r.files).put("total", r.total)
            .put("covered", r.covered).put("missing", r.missing).put("review", r.review).put("aiEligible", r.aiEligible)
            .put("unreadable", r.unreadable).put("excluded", r.excluded).put("shared", r.shared).put("reviewEligible", r.reviewEligible)
            .put("details", JSONArray().apply { r.details.forEach { detail -> put(JSONObject()
                .put("resource", detail.resource).put("category", detail.category).put("missing", detail.missing).put("review", detail.review)
                .put("examples", JSONArray().apply { detail.examples.forEach { sample -> put(JSONObject().put("text", sample.text)
                    .put("review", sample.review).put("translation", sample.translation).put("reason", sample.reason)) } })) } })
        val text = json.toString(); require(text.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024)
        TranslationSources.writeAtomic(file, text)
    }

    fun read(): SavedCoverage? {
        if (!Files.isRegularFile(file)) return null
        require(Files.size(file) <= 8 * 1024 * 1024)
        val j = JSONObject(String(Files.readAllBytes(file), Charsets.UTF_8)); require(j.getInt("schema") == 1)
        val rows = j.getJSONArray("details"); require(rows.length() <= 5000)
        val details = (0 until rows.length()).map { i -> val row = rows.getJSONObject(i)
            val examples = row.getJSONArray("examples"); require(examples.length() <= 4)
            CoverageFile(row.getString("resource"), row.getString("category"), row.getInt("missing"), row.getInt("review"),
                (0 until examples.length()).map { n -> val sample = examples.getJSONObject(n)
                    CoverageSample(sample.getString("text"), sample.getBoolean("review"),
                        if (sample.isNull("translation")) null else sample.getString("translation"), sample.getString("reason")) })
        }
        return SavedCoverage(j.getString("fingerprint"), CoverageReport(j.getString("sourceName"), j.getLong("checkedAt"),
            j.getInt("files"), j.getInt("total"), j.getInt("covered"), j.getInt("missing"), j.getInt("review"),
            j.getInt("aiEligible"), j.getInt("unreadable"), details, j.getInt("excluded"), j.getInt("shared"), j.getInt("reviewEligible")))
    }
}
