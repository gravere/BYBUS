package com.example.limbuszhcn.translation

import com.example.limbuszhcn.gamefs.GameStorage
import com.example.limbuszhcn.update.TranslationRepository
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path

internal data class SavedCoverage(val fingerprint: String, val report: CoverageReport, val needsCheck: Boolean = false,
    val independentReady: Boolean = true)

private fun packageKey(sourceId: String, revision: String) = AiTranslationJob.digest(revision.ifEmpty { sourceId })

internal class CoverageInputs(source: TranslationSource, revision: String, game: String, independent: TranslationSource?) {
    val sourceId = source.id
    val name = source.name
    val pack = packageKey(source.id, revision)
    val content = AiTranslationJob.digest("package-coverage|$revision|$game")
    val independentKey = AiTranslationJob.digest("$content|${independent?.id.orEmpty()}|${independent?.revision.orEmpty()}")
    // The previous single-report format can be adopted only if all of its inputs match.
    val fingerprint = AiTranslationJob.digest("coverage-v3|${source.id}|$revision|${independent?.id.orEmpty()}|${independent?.revision.orEmpty()}|$game")
}

internal data class CoverageCheck(val sourceId: String, val pack: String, val token: String, val wasPending: Boolean)

/** Separate content-addressed reports, with a last-report pointer and failed-check marker per source. */
internal class CoverageStore(private val cache: Path) {
    private val root = cache.resolve("coverage")
    private val legacy = root.resolve("result.json")
    private fun owner(id: String) = root.resolve("sources").resolve(AiTranslationJob.digest(id))
    private fun directory(pack: String) = root.resolve("packages").resolve(pack)
    private fun marker(id: String, pack: String) = owner(id).resolve("checks").resolve(pack)
    private fun pointer(id: String) = owner(id).resolve("last")
    private fun shortText(path: Path, limit: Long = 128): String? =
        if (Files.isRegularFile(path) && Files.size(path) <= limit) String(Files.readAllBytes(path), Charsets.UTF_8) else null

    fun beginCheck(input: CoverageInputs) = beginCheck(input.sourceId, input.pack)
    fun beginCheck(source: TranslationSource) = beginCheck(source.id, packageKey(source.id, sourceRevision(source)))

    @Synchronized private fun beginCheck(sourceId: String, pack: String): CoverageCheck {
        val path = marker(sourceId, pack)
        val check = CoverageCheck(sourceId, pack, java.util.UUID.randomUUID().toString(), Files.exists(path))
        TranslationSources.writeAtomic(path, check.token)
        return check
    }

    /** The official verifier may fall back to a different archive than its initial metadata suggested. */
    @Synchronized fun verified(check: CoverageCheck, input: CoverageInputs): CoverageCheck {
        check(check.sourceId == input.sourceId && shortText(marker(check.sourceId, check.pack)) == check.token) { "已有新的覆盖检查" }
        if (check.pack == input.pack) return check
        abandon(check)
        return beginCheck(input)
    }

    @Synchronized fun abandon(check: CoverageCheck) {
        val path = marker(check.sourceId, check.pack)
        if (shortText(path) != check.token) return
        if (check.wasPending) TranslationSources.writeAtomic(path, "pending") else Files.deleteIfExists(path)
    }

    fun resourcesReplaced() {
        Files.deleteIfExists(root.resolve("resources.json"))
    }
    // A report follows the official archive the verifier actually opened, including a fallback.
    // Checking history only reads file metadata while the same candidates remain on disk.
    fun sourceRevision(source: TranslationSource, verifiedOfficial: String? = null): String {
        if (!source.isOfficial) return source.revision
        val repository = TranslationRepository(cache)
        val versions = repository.cachedVersions()
        if (versions.isEmpty()) return "not-downloaded"
        fun stamp(path: Path): String = if (Files.isRegularFile(path))
            "${Files.size(path)}@${Files.getLastModifiedTime(path)}" else "-"
        fun archive(version: String) = cache.resolve("downloads/LimbusLocalize_$version.7z")
        val inputs = AiTranslationJob.digest(versions.joinToString("\n") { version ->
            "$version|${stamp(cache.resolve("patch-cache/$version/manifest.json"))}|${stamp(archive(version))}"
        })
        val receipt = root.resolve("official-source")
        val previous = if (Files.isRegularFile(receipt) && Files.size(receipt) == 129L)
            String(Files.readAllBytes(receipt), Charsets.US_ASCII) else ""
        if (verifiedOfficial != null) {
            require(verifiedOfficial.matches(Regex("[a-f0-9]{64}")))
            val checked = "$inputs|$verifiedOfficial"
            if (previous != checked) TranslationSources.writeAtomic(receipt, checked)
            return verifiedOfficial
        }
        if (previous.startsWith("$inputs|")) return previous.substringAfter('|')
        // A hint for the screen until the next explicit verification. Never authorizes a paid request.
        return versions.firstNotNullOfOrNull { version ->
            if (!Files.isRegularFile(archive(version)) || Files.size(archive(version)) == 0L) null
            else runCatching { repository.cachedManifest(version).archiveSha256 }.getOrNull()?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        } ?: "not-downloaded"
    }

    fun inputs(source: TranslationSource, storage: GameStorage, verifyContents: Boolean = false,
        independentInput: TranslationSource? = null, verifiedOfficial: String? = null) =
        CoverageInputs(source, sourceRevision(source, verifiedOfficial), resourceFingerprint(storage, verifyContents), independentInput)

    fun hasHistory(source: TranslationSource) = Files.isRegularFile(legacy) || Files.isRegularFile(pointer(source.id)) ||
        Files.isRegularFile(directory(packageKey(source.id, sourceRevision(source))).resolve("result.json"))

    @Synchronized private fun resourceFingerprint(storage: GameStorage, verifyContents: Boolean): String {
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
            val sameStamp = saved?.optString("stamp") == stamp
            val hash = if (!verifyContents && sameStamp && oldHash.matches(Regex("[a-f0-9]{64}"))) oldHash else {
                val bytes = requireNotNull(storage.read(storage.rootPath().trimEnd('/') + "/$key"))
                readBytes += bytes.size; require(bytes.size <= 8 * 1024 * 1024 && readBytes <= 128L * 1024 * 1024)
                val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                digest.joinToString("") { "%02x".format(it.toInt() and 255) }
            }
            if (!sameStamp || hash != oldHash) changed = true
            hashes[key] = hash
            current.put(key, JSONObject().put("stamp", stamp).put("hash", hash))
        }
        if (changed || verifyContents)
            check(storage.japaneseResourceMetadata() == metadata) { "游戏文本正在变化，请在资源下载结束后检查" }
        if (changed) {
            TranslationSources.writeAtomic(index, current.toString())
        }
        // A timestamp change with identical text does not invalidate the report.
        return AiTranslationJob.digest(hashes.entries.joinToString("\n") { "${it.key}|${it.value}" })
    }

    @Synchronized fun save(input: CoverageInputs, r: CoverageReport, ticket: CoverageCheck): SavedCoverage {
        val pending = marker(input.sourceId, input.pack)
        check(ticket.sourceId == input.sourceId && ticket.pack == input.pack && shortText(pending) == ticket.token) { "已有新的覆盖检查，请查看最新结果" }
        val json = JSONObject().put("schema", 2).put("fingerprint", input.fingerprint)
            .put("content", input.content).put("independentKey", input.independentKey)
            .put("sourceName", r.sourceName).put("checkedAt", r.checkedAt).put("files", r.files).put("total", r.total)
            .put("covered", r.covered).put("missing", r.missing).put("review", r.review).put("aiEligible", r.aiEligible)
            .put("unreadable", r.unreadable).put("excluded", r.excluded).put("shared", r.shared).put("reviewEligible", r.reviewEligible)
            .put("independentEligible", r.independentEligible)
            .put("details", JSONArray().apply { r.details.forEach { detail -> put(JSONObject()
                .put("resource", detail.resource).put("category", detail.category).put("missing", detail.missing).put("review", detail.review)
                .put("examples", JSONArray().apply { detail.examples.forEach { sample -> put(JSONObject().put("text", sample.text)
                    .put("review", sample.review).put("translation", sample.translation).put("reason", sample.reason)) } })) } })
        val text = json.toString(); require(text.toByteArray(Charsets.UTF_8).size <= 8 * 1024 * 1024)
        TranslationSources.writeAtomic(directory(input.pack).resolve("result.json"), text)
        remember(input)
        Files.deleteIfExists(pending)
        return SavedCoverage(input.fingerprint, r)
    }

    private fun remember(input: CoverageInputs) {
        val path = pointer(input.sourceId)
        if (shortText(path) != input.pack) TranslationSources.writeAtomic(path, input.pack)
    }

    private fun document(path: Path): JSONObject? {
        if (!Files.isRegularFile(path)) return null
        require(Files.size(path) <= 8 * 1024 * 1024)
        return JSONObject(String(Files.readAllBytes(path), Charsets.UTF_8))
    }

    @Synchronized fun read(input: CoverageInputs, countIndependent: (() -> Int)? = null): SavedCoverage? {
        val folder = directory(input.pack)
        var json = document(folder.resolve("result.json"))
        if (json == null) {
            val old = runCatching { document(legacy) }.getOrNull()
            if (old != null && old.optString("fingerprint") == input.fingerprint && old.optInt("schema") == 1) {
                val pending = Files.exists(root.resolve("needs-check")) || !old.has("independentEligible")
                old.put("schema", 2).put("content", input.content).put("independentKey", input.independentKey)
                val path = marker(input.sourceId, input.pack)
                if (pending && !Files.exists(path)) TranslationSources.writeAtomic(path, "legacy")
                TranslationSources.writeAtomic(folder.resolve("result.json"), old.toString())
                remember(input)
                Files.deleteIfExists(legacy)
                Files.deleteIfExists(root.resolve("needs-check"))
                json = old
            }
        }
        val currentPack = json != null
        if (json == null) {
            val last = shortText(pointer(input.sourceId))?.takeIf { it.matches(Regex("[a-f0-9]{64}")) } ?: return null
            json = document(directory(last).resolve("result.json")) ?: return null
        }
        require(json.getInt("schema") == 2)
        val report = decode(json).copy(sourceName = input.name)
        if (!currentPack || json.getString("content") != input.content)
            return SavedCoverage(json.getString("fingerprint"), report, needsCheck = true)
        remember(input)
        val needsCheck = Files.exists(marker(input.sourceId, input.pack)) || !json.has("independentEligible")
        var count = report.independentEligible.takeIf { json.getString("independentKey") == input.independentKey }
        if (count == null) {
            val path = folder.resolve("remaining-" + AiTranslationJob.digest(input.sourceId))
            count = shortText(path)?.takeIf { it.startsWith(input.independentKey + "|") }?.substringAfter('|')?.toIntOrNull()
            if (count == null && !needsCheck && countIndependent != null) {
                count = countIndependent()
                if (count >= 0) TranslationSources.writeAtomic(path, "${input.independentKey}|$count")
            }
        }
        return SavedCoverage(input.fingerprint, report.copy(independentEligible = count?.coerceAtLeast(0) ?: 0),
            needsCheck, independentReady = count != null && count >= 0)
    }

    private fun decode(j: JSONObject): CoverageReport {
        val rows = j.getJSONArray("details"); require(rows.length() <= 5000)
        val details = (0 until rows.length()).map { i -> val row = rows.getJSONObject(i)
            val examples = row.getJSONArray("examples"); require(examples.length() <= 4)
            CoverageFile(row.getString("resource"), row.getString("category"), row.getInt("missing"), row.getInt("review"),
                (0 until examples.length()).map { n -> val sample = examples.getJSONObject(n)
                    CoverageSample(sample.getString("text"), sample.getBoolean("review"),
                        if (sample.isNull("translation")) null else sample.getString("translation"), sample.getString("reason")) })
        }
        return CoverageReport(j.getString("sourceName"), j.getLong("checkedAt"),
            j.getInt("files"), j.getInt("total"), j.getInt("covered"), j.getInt("missing"), j.getInt("review"),
            j.getInt("aiEligible"), j.getInt("unreadable"), details, j.getInt("excluded"), j.getInt("shared"), j.getInt("reviewEligible"),
            j.optInt("independentEligible"))
    }
}
