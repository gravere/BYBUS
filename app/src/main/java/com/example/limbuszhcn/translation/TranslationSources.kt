package com.example.limbuszhcn.translation

import com.example.limbuszhcn.storage.AtomicFiles
import com.example.limbuszhcn.update.PatchManifest
import com.example.limbuszhcn.update.sha256
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.readText
import kotlin.io.path.writeText

internal data class TranslationSource(val id: String, val name: String, val kind: String = "local",
    val revision: String = "", val archive: String = "", val icon: String? = null, val baseId: String? = null,
    val updatedAtMillis: Long = 0, val textFileCount: Int = 0, val officialVersion: Long? = null,
    val independent: Boolean = false, val language: String? = null, val aiEntryCount: Int? = null,
    val baseName: String? = null, val updateLink: SourceUpdateLink? = null, val supplement: OfficialSupplement? = null) {
    val isOfficial get() = id == "zero"
    val label get() = when { isOfficial -> "自动更新"; kind == "ai" -> "AI 译文"; updateLink != null -> "链接更新"; else -> "本地文件" }
    val original get() = supplement?.let { copy(revision = it.originalRevision, archive = it.originalArchive, supplement = null) } ?: this
}

internal data class SourceState(val sources: List<TranslationSource> = listOf(TranslationSources.OFFICIAL),
    val selectedId: String = "zero", val message: String? = null,
    val undo: AiUndoPoint? = null, val officialDownloadTimes: Map<Long, Long> = emptyMap()) {
    val officialDownloaded get() = officialDownloadTimes.isNotEmpty()
    val selected get() = sources.firstOrNull { it.id == selectedId } ?: TranslationSources.OFFICIAL
    fun requireSlot(replacing: TranslationSource?) {
        require(replacing != null || sources.size < 32) { "已保留 31 个自定义汉化源，请先移除一个再继续" }
    }
    /** Returning to the original source must not restart its independent AI pack at batch one. */
    fun independentInput(config: AiConfig): TranslationSource? {
        val active = selected
        if (active.kind == "ai" && active.independent) return active
        val base = active.baseId ?: active.id
        val legacy = "ai-" + AiTaskStore.identityFor(base, config.copy(missingOnly = false))
        return sources.filter { it.kind == "ai" && it.baseId == base && (it.independent || it.id == legacy) }
            .maxByOrNull { it.updatedAtMillis } ?: active.takeIf { it.kind == "ai" }
    }
}

internal data class AiUndoPoint(val sourceId: String, val generatedRevision: String,
    val previous: TranslationSource?, val previousSelectedId: String, val summary: AiChangeSummary? = null)

internal data class PreparedTranslation(val directory: Path, val manifest: PatchManifest)

/** All mutations are owned by LaunchCoordinator's operation lock. No credentials live here. */
internal class TranslationSources(val cache: Path) {
    private val root = cache.resolve("sources")
    private val registry = root.resolve("sources.json")
    private val archives = root.resolve("archives")

    fun read(): SourceState {
        val times = com.example.limbuszhcn.update.TranslationRepository(cache).downloadTimes()
        if (!Files.exists(registry)) return SourceState(officialDownloadTimes = times)
        require(Files.size(registry) <= 256 * 1024) { "汉化源列表过大" }
        val json = JSONObject(registry.readText())
        val rows = json.getJSONArray("sources")
        require(rows.length() <= 32) { "汉化源数量超出限制" }
        val sources = (0 until rows.length()).map { i -> rows.getJSONObject(i).let {
            val id = it.getString("id"); require(validId(id)) { "汉化源标识无效" }
            TranslationSource(id, cleanName(it.getString("name")), it.getString("kind"),
                it.optString("revision"), it.optString("archive"),
                it.optString("icon").takeIf(String::isNotBlank), it.optString("baseId").takeIf(String::isNotBlank),
                it.optLong("updatedAtMillis").coerceAtLeast(0), it.optInt("textFileCount").coerceAtLeast(0),
                it.optLong("officialVersion").takeIf { version -> version > 0 }, it.optBoolean("independent"),
                it.optString("language").takeIf { code -> code in setOf("jp", "en", "kr") },
                if (it.has("aiEntryCount") && !it.isNull("aiEntryCount")) it.optInt("aiEntryCount").coerceAtLeast(0) else null,
                it.optString("baseName").takeIf(String::isNotBlank),
                it.optJSONObject("updateLink")?.let(SourceUpdateLink::read),
                it.optJSONObject("supplement")?.let(OfficialSupplement::read))
        } }
        require(sources.map { it.id }.distinct().size == sources.size && sources.none { it.isOfficial })
        val taken = (listOf(OFFICIAL.name) + sources.filterNot { it.kind == "ai" }.map { it.name }).toMutableList()
        val normalized = sources.map { source -> if (source.kind != "ai") source else {
            val name = uniqueName(source.name, taken); taken += name; source.copy(name = name)
        } }
        val all = listOf(OFFICIAL.copy(icon = json.optString("officialIcon").takeIf(String::isNotBlank))) + normalized
        val selected = json.optString("selected", "zero")
        require(all.any { it.id == selected }) { "选中的汉化源已不存在，请重新选择" }
        val undo = json.optJSONObject("aiUndo")?.let { point -> runCatching {
            val id = point.getString("sourceId"); val revision = point.getString("generatedRevision")
            require(all.any { it.id == id && it.kind == "ai" && it.revision == revision })
            val previous = point.optJSONObject("previous")?.let { old ->
                require(old.getString("id") == id)
                TranslationSource(id, cleanName(old.getString("name")), old.getString("kind"),
                    old.getString("revision"), old.getString("archive"), old.optString("icon").takeIf(String::isNotBlank),
                    old.optString("baseId").takeIf(String::isNotBlank), old.optLong("updatedAtMillis"), old.optInt("textFileCount"),
                    old.optLong("officialVersion").takeIf { version -> version > 0 }, old.optBoolean("independent"),
                    old.optString("language").takeIf { code -> code in setOf("jp", "en", "kr") },
                    if (old.has("aiEntryCount") && !old.isNull("aiEntryCount")) old.optInt("aiEntryCount").coerceAtLeast(0) else null,
                    old.optString("baseName").takeIf(String::isNotBlank))
            }
            AiUndoPoint(id, revision, previous, point.getString("previousSelectedId"),
                point.optJSONObject("summary")?.let { runCatching { AiChangeSummary.read(it) }.getOrNull() })
        }.getOrNull() }
        return SourceState(all, selected, undo = undo, officialDownloadTimes = times)
    }

    private fun save(state: SourceState) {
        Files.createDirectories(root)
        val json = JSONObject().put("selected", state.selectedId)
            .put("officialIcon", state.sources.first().icon.orEmpty())
            .put("aiUndo", state.undo?.let { point -> JSONObject().put("sourceId", point.sourceId)
                .put("generatedRevision", point.generatedRevision).put("previousSelectedId", point.previousSelectedId)
                .put("summary", point.summary?.json())
                .put("previous", point.previous?.let(::sourceJson)) })
            .put("sources", JSONArray().apply { state.sources.filterNot { it.isOfficial }.forEach { s ->
                put(sourceJson(s))
            } })
        writeAtomic(registry, json.toString())
    }

    private fun sourceJson(s: TranslationSource) = JSONObject().put("id", s.id).put("name", s.name).put("kind", s.kind)
        .put("revision", s.revision).put("archive", s.archive).put("icon", s.icon.orEmpty())
        .put("baseId", s.baseId.orEmpty()).put("updatedAtMillis", s.updatedAtMillis).put("textFileCount", s.textFileCount)
        .put("officialVersion", s.officialVersion).put("independent", s.independent)
        .put("language", s.language).put("aiEntryCount", s.aiEntryCount).put("baseName", s.baseName)
        .put("updateLink", s.updateLink?.json()).put("supplement", s.supplement?.json())

    fun select(id: String): SourceState = read().let { state ->
        require(state.sources.any { it.id == id }) { "汉化源不存在" }
        state.copy(selectedId = id).also(::save)
    }

    fun rename(id: String, name: String): SourceState {
        val unique = uniqueName(cleanName(name), read().sources.filterNot { it.id == id }.map { it.name })
        return change(id) { it.copy(name = unique) }
    }
    fun recordAiCount(id: String, revision: String, language: String, count: Int) {
        val state = read()
        val source = state.sources.find { it.id == id && it.kind == "ai" && it.revision == revision } ?: return
        if (source.aiEntryCount != count || source.language != language)
            change(id) {
                val base = it.baseName ?: state.sources.find { candidate -> candidate.id == it.baseId }?.name
                val legacy = it.language == null && (it.name.substringBefore(" (") == "AI 汉化" || it.name.substringBefore(" (").endsWith(" · AI 补全"))
                val label = com.example.limbuszhcn.gamefs.GameTextLanguage.fromCode(language)?.displayName.orEmpty()
                val name = if (legacy) uniqueName("${(base ?: "全局 AI 翻译").take(48)} · $label", state.sources.filterNot { s -> s.id == id }.map { s -> s.name }) else it.name
                it.copy(aiEntryCount = count, language = language, name = name, baseName = base ?: it.baseName)
            }
    }
    fun setIcon(id: String, png: ByteArray?): SourceState {
        require(read().sources.any { it.id == id })
        val file = root.resolve("icons/$id.png")
        if (png != null) {
            require(png.size <= 1024 * 1024)
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling(".$id.tmp")
            Files.write(tmp, png); AtomicFiles.move(tmp, file, true)
        }
        val state = change(id) { it.copy(icon = if (png == null) null else file.toString() + "#" + file.sha256().take(12)) }
        if (png == null) Files.deleteIfExists(file)
        return state
    }

    private fun change(id: String, change: (TranslationSource) -> TranslationSource): SourceState = read().let { state ->
        require(state.sources.any { it.id == id })
        state.copy(sources = state.sources.map { if (it.id == id) change(it) else it }).also(::save)
    }

    fun import(input: InputStream, name: String, replaceId: String? = null): TranslationSource {
        return importArchive(input, name, replaceId)
    }

    /** Validation and immutable storage only. Caller commits after all preparation succeeds. */
    fun stage(input: InputStream, name: String, replaceId: String? = null): TranslationSource =
        importArchive(input, name, replaceId, commit = false)

    fun commitLocal(source: TranslationSource, expected: TranslationSource, activate: Boolean = false): TranslationSource {
        require(source.id == expected.id && source.kind == "local")
        val state = read()
        require(state.sources.find { it.id == expected.id } == expected) { "汉化包已变化，请重新操作" }
        prepare(source)
        source.supplement?.let { prepare(source.original) }
        save(state.copy(sources = state.sources.map { if (it.id == source.id) source else it },
            selectedId = if (activate) source.id else state.selectedId))
        return source
    }

    fun updateLink(id: String, link: SourceUpdateLink?): SourceState = change(id) {
        require(it.kind == "local") { "请在原始导入包中设置更新链接" }
        it.copy(updateLink = link)
    }

    private fun importArchive(input: InputStream, name: String, replaceId: String? = null,
                              generatedId: String? = null, baseId: String? = null, officialVersion: Long? = null,
                              activate: Boolean = true, independent: Boolean = false, language: String? = null,
                              aiEntryCount: Int? = null, baseName: String? = null, summary: AiChangeSummary? = null,
                              commit: Boolean = true): TranslationSource {
        require(generatedId == null || validId(generatedId))
        val state = read()
        val previous = replaceId?.let { id -> requireNotNull(state.sources.find { it.id == id && !it.isOfficial }) }
        state.requireSlot(previous)
        Files.createDirectories(archives)
        val staged = archives.resolve(".import.tmp")
        try {
            Files.newOutputStream(staged).use { output -> copyBounded(input, output, 256L * 1024 * 1024) }
            val hash = staged.sha256()
            val target = archives.resolve("$hash.pack")
            val prepared = LocalTranslationArchive(cache).extract(staged, "local-$hash")
            require(prepared.manifest.entries.isNotEmpty()) { "没有可用的汉化文本" }
            AtomicFiles.move(staged, target, true)
            val source = TranslationSource(generatedId ?: previous?.id ?: "local-${UUID.randomUUID()}",
                uniqueName(previous?.name ?: cleanName((if (generatedId != null) name else name.substringBeforeLast('.'))
                    .ifBlank { "本地汉化" }.take(64)), state.sources.filterNot { it.id == previous?.id }.map { it.name }),
                kind = if (generatedId != null) "ai" else "local", baseId = baseId,
                revision = hash, archive = target.fileName.toString(), icon = previous?.icon,
                updatedAtMillis = System.currentTimeMillis(), textFileCount = prepared.manifest.entries.size,
                officialVersion = officialVersion?.takeIf { it > 0 }, independent = independent, language = language,
                aiEntryCount = aiEntryCount, baseName = baseName,
                updateLink = if (generatedId == null) previous?.updateLink?.let { SourceUpdateLink(it.url, it.automatic) } else null)
            val sources = if (previous == null) state.sources + source
                else state.sources.map { if (it.id == source.id) source else it }
            val undo = if (generatedId != null) AiUndoPoint(source.id, source.revision, previous, state.selectedId,
                summary?.copy(atMillis = source.updatedAtMillis)?.validated())
                else state.undo?.takeUnless { it.sourceId == source.id }
            if (commit) {
                save(state.copy(sources = sources, selectedId = if (activate) source.id else state.selectedId, undo = undo))
                if (generatedId == null && previous?.kind == "ai") deleteAiCache(previous.id)
            }
            return source
        } finally { Files.deleteIfExists(staged) }
    }

    fun prepare(source: TranslationSource): PreparedTranslation {
        require(!source.isOfficial && source.archive.matches(Regex("[a-f0-9]{64}\\.pack"))) { "汉化归档无效" }
        val archive = archives.resolve(source.archive)
        require(Files.isRegularFile(archive) && archive.sha256() == source.revision) { "本地汉化包损坏，请重新导入" }
        return LocalTranslationArchive(cache).extract(archive, "local-${source.revision}")
    }

    @JvmOverloads fun addGenerated(archive: Path, baseId: String, identity: String, name: String, officialVersion: Long? = null,
        activate: Boolean = true, independent: Boolean = false, language: String? = null,
        aiEntryCount: Int? = null, baseName: String? = null, summary: AiChangeSummary? = null): TranslationSource {
        val state = read()
        val id = "ai-$identity"
        val previous = state.sources.find { it.id == id }
        require(previous == null || previous.kind == "ai") { "目标已替换为本地汉化文件，已保留原文件" }
        return Files.newInputStream(archive).use { importArchive(it, name, previous?.id, id, baseId, officialVersion, activate,
            independent, language, aiEntryCount, baseName, summary) }
    }

    fun undoGenerated(expected: AiUndoPoint? = null): SourceState {
        val state = read(); val point = requireNotNull(state.undo) { "没有可撤回的 AI 生成" }
        require(expected == null || expected == point) { "可撤回的改动已变化，请重新查看后确认" }
        val current = state.sources.first { it.id == point.sourceId }
        val restored = point.previous?.let { old ->
            prepare(old) // Validate the retained archive before changing the live registry.
            old.copy(name = current.name, icon = current.icon)
        }
        val sources = state.sources.mapNotNull { if (it.id == point.sourceId) restored else it }
        val selected = point.previousSelectedId.takeIf { id -> sources.any { it.id == id } }
            ?: current.baseId?.takeIf { id -> sources.any { it.id == id } } ?: "zero"
        val next = state.copy(sources = sources, selectedId = selected, undo = null)
        save(next)
        if (restored == null) {
            Files.deleteIfExists(root.resolve("icons/${point.sourceId}.png"))
            deleteAiCache(point.sourceId)
        }
        return next
    }

    /** Run only after a successful activation, while the operation lock excludes game launch. */
    fun pruneUnused() {
        if (Files.exists(cache.resolve(".source-switch/ready"))) return
        val state = read()
        val retained = state.sources.map { it.revision }.toMutableSet()
        state.sources.mapNotNull { it.supplement?.originalRevision }.forEach(retained::add)
        state.undo?.previous?.revision?.let(retained::add)
        com.example.limbuszhcn.container.LiveTranslationStore(cache).read()?.archive?.let(retained::add)
        val indexes = com.example.limbuszhcn.container.TranslationIndexStore(cache.resolve("translation-index"))
        listOfNotNull(indexes.activeManifest(), indexes.bootstrapManifest()).forEach {
            it.getProperty("version")?.removePrefix("local-")?.let(retained::add)
        }
        if (Files.isDirectory(archives)) Files.list(archives).use { files -> files.forEach { file ->
            val hash = Regex("([a-f0-9]{64})\\.pack").matchEntire(file.fileName.toString())?.groupValues?.get(1)
            if (hash != null && hash !in retained) Files.deleteIfExists(file)
        } }
        val patches = cache.resolve("patch-cache")
        if (Files.isDirectory(patches)) Files.list(patches).use { files -> files.forEach { file ->
            val name = file.fileName.toString()
            val hash = (Regex("local-([a-f0-9]{64})").matchEntire(name)
                ?: Regex("\\.local-([a-f0-9]{64})\\.(?:previous|import)").matchEntire(name))?.groupValues?.get(1)
            if (hash != null && hash !in retained) AtomicFiles.deleteOwnedTree(patches, file)
        } }
    }

    fun remove(id: String): SourceState {
        require(id != "zero") { "默认汉化源不能删除" }
        val state = read(); require(state.sources.any { it.id == id })
        val remaining = state.sources.filterNot { it.id == id }
        val next = state.copy(sources = remaining, selectedId = if (state.selectedId == id) "zero" else state.selectedId,
            undo = state.undo?.takeUnless { it.sourceId == id })
        save(next) // Detach before deleting disposable resources.
        Files.deleteIfExists(root.resolve("icons/$id.png"))
        val removed = state.sources.first { it.id == id }
        deleteAiCache(id)
        if (removed.archive.isNotEmpty() && remaining.none { it.archive == removed.archive || it.supplement?.originalArchive == removed.archive } && next.undo?.previous?.archive != removed.archive &&
            removed.archive.matches(Regex("[a-f0-9]{64}\\.pack"))) Files.deleteIfExists(archives.resolve(removed.archive))
        return next
    }

    private fun deleteAiCache(id: String) {
        if (!id.matches(Regex("ai-[a-f0-9]{32}"))) return
        val jobs = cache.resolve("ai-jobs")
        val job = jobs.resolve(id.removePrefix("ai-"))
        if (Files.exists(job)) AtomicFiles.deleteOwnedTree(jobs, job)
    }

    companion object {
        val OFFICIAL = TranslationSource("zero", "都市零协会", "official")
        fun uniqueName(preferred: String, names: Collection<String>): String {
            val base = cleanName(preferred)
            if (names.none { it.equals(base, ignoreCase = true) }) return base
            return generateSequence(2) { it + 1 }.map { number ->
                val suffix = " ($number)"; base.take(64 - suffix.length) + suffix
            }.first { candidate -> names.none { it.equals(candidate, ignoreCase = true) } }
        }
        fun availableAiIdentity(preferred: String, state: SourceState): String =
            if (state.sources.any { it.id == "ai-$preferred" && it.kind != "ai" }) UUID.randomUUID().toString().replace("-", "") else preferred
        fun validId(id: String) = id.matches(Regex("(?:local|ai)-[a-f0-9-]{16,80}"))
        fun cleanName(name: String) = name.trim().also {
            require(it.length in 1..64 && it.none { c -> c.code < 32 }) { "名称需为 1–64 个字符" }
        }
        fun writeAtomic(path: Path, text: String) {
            Files.createDirectories(path.parent)
            val tmp = path.resolveSibling(".${path.fileName}.tmp")
            try { tmp.writeText(text); AtomicFiles.move(tmp, path, true) }
            finally { Files.deleteIfExists(tmp) }
        }
        fun copyBounded(input: InputStream, output: java.io.OutputStream, max: Long) {
            val buffer = ByteArray(32 * 1024); var total = 0L
            while (true) {
                if (Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException()
                val count = input.read(buffer); if (count < 0) break
                total += count; require(total <= max) { "文件超出大小限制" }; output.write(buffer, 0, count)
            }
        }
    }
}
