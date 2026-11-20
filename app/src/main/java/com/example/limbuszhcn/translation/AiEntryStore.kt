package com.example.limbuszhcn.translation

import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import com.example.limbuszhcn.gamefs.GameStorage
import com.example.limbuszhcn.gamefs.GameTextLanguage
import org.json.JSONArray
import org.json.JSONObject
import java.nio.file.Files
import java.nio.file.Path

/** Content-bound, paged entry library. No credentials or network access. */
internal class AiEntryStore(private val cache: Path) {
    private val root = cache.resolve("ai-entries")
    private fun file(key: String): Path {
        require(key.matches(Regex("[a-f0-9]{64}")))
        return root.resolve("$key.db")
    }
    private fun fingerprint(source: TranslationSource?, language: GameTextLanguage, game: String) =
        AiTranslationJob.digest("ai-entries-v1|${source?.id}|${source?.revision}|${language.code}|$game")
    private fun language(source: TranslationSource?, storage: GameStorage) =
        GameTextLanguage.fromCode(source?.language.orEmpty()) ?: requireNotNull(storage.textLanguage()) {
            "请先从 BYBUS 启动一次游戏，以读取当前游戏语言"
        }.language

    fun inspect(sources: TranslationSources, storage: GameStorage, sourceId: String?, force: Boolean = false,
        cancelled: () -> Boolean = { false }): AiEntryReport {
        val source = sourceId?.let { id -> requireNotNull(sources.read().sources.find { it.id == id && it.kind == "ai" }) { "此 AI 汉化包已不存在" } }
        val lang = language(source, storage)
        val game = CoverageStore(cache).resourceFingerprint(storage, lang, force)
        val key = fingerprint(source, lang, game)
        if (!force) readReport(key)?.let {
            if (source != null) sources.recordAiCount(source.id, source.revision, lang.code, it.aiCount)
            return it.copy(name = sources.read().sources.find { s -> s.id == source?.id }?.name ?: "全局 AI 翻译")
        }
        val base = source?.let(sources::prepare)
        val proof = if (source == null) emptyMap() else AiTranslationReuse.collect(cache, source, requireNotNull(base), storage, cancelled, lang)
        Files.createDirectories(root)
        val staged = root.resolve("$key.tmp.db")
        Files.deleteIfExists(staged)
        try {
            val categories = linkedSetOf<String>()
            val (counts, unreadable) = SQLiteDatabase.openDatabase(staged.toString(), null,
                SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                db.disableWriteAheadLogging(); db.beginTransaction()
                try {
                    db.execSQL("CREATE TABLE entries(number INTEGER PRIMARY KEY,id TEXT NOT NULL UNIQUE,kind TEXT NOT NULL,category TEXT NOT NULL,resource TEXT NOT NULL,hash TEXT NOT NULL,original TEXT NOT NULL,translation TEXT,pointer TEXT NOT NULL,game_id TEXT)")
                    val scanned = db.compileStatement("INSERT INTO entries VALUES(?,?,?,?,?,?,?,?,?,?)").use { insert ->
                        AiEntryScan.scan(storage, base, lang, proof, cancelled) { row ->
                            insert.bindLong(1, row.number.toLong())
                            listOf(row.id, row.kind.name, row.category, row.resource, row.hash, row.original, row.translation, row.pointer, row.recordId)
                                .forEachIndexed { i, text -> if (text == null) insert.bindNull(i + 2) else insert.bindString(i + 2, text) }
                            insert.executeInsert(); categories += row.category
                        }
                    }
                    db.execSQL("CREATE INDEX entry_groups ON entries(kind,category,number)"); db.version = 1
                    db.setTransactionSuccessful(); scanned
                } finally { db.endTransaction() }
            }
            val report = AiEntryReport(key, source?.id, source?.revision.orEmpty(), source?.name ?: "全局 AI 翻译", lang, game,
                counts, categories.toList(), unreadable = unreadable)
            validate(report, sources, storage, true)
            com.example.limbuszhcn.storage.AtomicFiles.move(staged, file(key), true)
            if (source != null) sources.recordAiCount(source.id, source.revision, lang.code, report.aiCount)
            val named = report.copy(name = sources.read().sources.find { it.id == source?.id }?.name ?: "全局 AI 翻译")
            writeReport(named)
            // One current index per source/language; other packages retain their own reports.
            prune(sourceId, lang, key)
            return named
        } finally { Files.deleteIfExists(staged) }
    }

    fun validate(report: AiEntryReport, sources: TranslationSources, storage: GameStorage, verifyContents: Boolean) {
        val source = report.sourceId?.let { id -> requireNotNull(sources.read().sources.find { it.id == id && it.kind == "ai" }) { "汉化包已移除，请重新选择" } }
        require(source?.revision.orEmpty() == report.revision) { "汉化包已变化，请重新读取条目" }
        if (source?.language == null) require(storage.textLanguage()?.language == report.language) { "游戏语言已变化，请重新读取条目" }
        val game = CoverageStore(cache).resourceFingerprint(storage, report.language, verifyContents)
        require(report.game == game && report.key == fingerprint(source, report.language, game)) { "游戏原文已变化，请重新读取条目" }
    }
    private fun open(report: AiEntryReport) = SQLiteDatabase.openDatabase(file(report.key).toString(), null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).also { check(it.version == 1) }
    private fun where(filter: AiEntryFilter, ids: Set<String>? = null): Pair<String, Array<String>> {
        val clauses = mutableListOf<String>(); val args = mutableListOf<String>()
        if (ids != null) {
            require(ids.isNotEmpty() && ids.size <= 1000)
            // Bounded literals are SHA-256 identifiers, never user text or SQL fragments.
            require(ids.all { it.matches(Regex("[a-f0-9]{64}")) })
            clauses += ids.joinToString(",", "id IN (", ")") { "'$it'" }
        } else {
            filter.kind?.let { clauses += "kind=?"; args += it.name }
            filter.category?.let { clauses += "category=?"; args += it }
            filter.search.trim().take(128).takeIf(String::isNotEmpty)?.let { term ->
                clauses += "(number=? OR instr(lower(original),lower(?))>0 OR instr(lower(translation),lower(?))>0 OR instr(lower(resource),lower(?))>0 OR instr(lower(game_id),lower(?))>0)"
                args += term.removePrefix("#"); repeat(4) { args += term }
            }
        }
        return (if (clauses.isEmpty()) "" else clauses.joinToString(" AND ", " WHERE ")) to args.toTypedArray()
    }
    fun entries(report: AiEntryReport, filter: AiEntryFilter, requestedPage: Int, cancel: CancellationSignal? = null): AiEntryPage {
        val (where, args) = where(filter)
        return open(report).use { db ->
            val total = db.rawQuery("SELECT count(*) FROM entries$where", args, cancel).use { it.moveToFirst(); it.getInt(0) }
            val page = requestedPage.coerceIn(0, (total - 1).coerceAtLeast(0) / AI_ENTRY_PAGE_SIZE)
            val rows = db.rawQuery("SELECT number,id,kind,category,resource,hash,substr(original,1,6000),substr(translation,1,24000),pointer,game_id FROM entries$where ORDER BY number LIMIT $AI_ENTRY_PAGE_SIZE OFFSET ${page * AI_ENTRY_PAGE_SIZE}", args, cancel).use { c ->
                buildList { while (c.moveToNext()) add(AiEntry(c.getInt(0), c.getString(1), AiEntryKind.valueOf(c.getString(2)), c.getString(3),
                    c.getString(4), c.getString(5), c.getString(6), c.getString(7), c.getString(8), c.getString(9))) }
            }
            AiEntryPage(rows, total, page)
        }
    }
    fun plan(request: AiEntryRequest, limit: Int, deletion: Boolean = false): AiTaskPlan {
        require(limit in 1..MAX_AI_TASK_ENTRIES)
        val (where, args) = where(request.filter, request.ids)
        return open(request.report).use { db ->
            val count = db.rawQuery("SELECT count(*) FROM entries$where", args).use { it.moveToFirst(); it.getInt(0) }
            require(count == request.count && count > 0) { "所选条目已变化，请重新选择" }
            if (deletion) {
                require(count <= limit)
                val wrong = db.rawQuery("SELECT count(*) FROM entries$where${if (where.isEmpty()) " WHERE " else " AND "}kind<>?", args + AiEntryKind.Ai.name)
                    .use { it.moveToFirst(); it.getInt(0) }
                require(wrong == 0) { "只能删除此包实际生成的 AI 译文" }
            }
            val selected = linkedMapOf<String, MutableSet<String>>(); val hashes = linkedMapOf<String, String>()
            db.rawQuery("SELECT resource,hash,id FROM entries$where ORDER BY number LIMIT $limit", args).use { c -> while (c.moveToNext()) {
                selected.getOrPut(c.getString(0)) { linkedSetOf() }.add(c.getString(2)); hashes[c.getString(0)] = c.getString(1)
            } }
            AiTaskPlan(selected, hashes, count)
        }
    }
    private fun writeReport(r: AiEntryReport) {
        val j = JSONObject().put("schema", 1).put("key", r.key).put("sourceId", r.sourceId).put("revision", r.revision)
            .put("name", r.name).put("language", r.language.code).put("game", r.game).put("checkedAt", r.checkedAt)
            .put("unreadable", r.unreadable).put("categories", JSONArray(r.categories))
            .put("counts", JSONObject().apply { r.counts.forEach { (kind, count) -> put(kind.name, count) } })
        TranslationSources.writeAtomic(root.resolve("${r.key}.json"), j.toString())
    }
    private fun readReport(key: String): AiEntryReport? = runCatching {
        val path = root.resolve("$key.json"); require(Files.size(path) <= 64 * 1024 && Files.isRegularFile(file(key)))
        val j = JSONObject(String(Files.readAllBytes(path), Charsets.UTF_8)); require(j.getInt("schema") == 1 && j.getString("key") == key)
        val counts = j.getJSONObject("counts"); val categories = j.getJSONArray("categories")
        AiEntryReport(key, j.optString("sourceId").takeIf { it.isNotEmpty() && it != "null" }, j.getString("revision"), j.getString("name"),
            requireNotNull(GameTextLanguage.fromCode(j.getString("language"))), j.getString("game"),
            AiEntryKind.entries.associateWith { counts.getInt(it.name) }, (0 until categories.length()).map { categories.getString(it) },
            j.getLong("checkedAt"), j.optInt("unreadable"))
    }.getOrNull()
    private fun prune(source: String?, lang: GameTextLanguage, keep: String) {
        Files.newDirectoryStream(root, "*.json").use { paths -> paths.forEach { path ->
            val key = path.fileName.toString().removeSuffix(".json")
            if (key != keep && key.matches(Regex("[a-f0-9]{64}"))) readReport(key)?.takeIf { it.sourceId == source && it.language == lang }?.let {
                Files.deleteIfExists(file(key)); Files.deleteIfExists(path)
            }
        } }
    }
}
