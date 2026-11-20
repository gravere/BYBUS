package com.example.limbuszhcn.translation

import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal
import com.example.limbuszhcn.container.TranslationSnapshot
import com.example.limbuszhcn.gamefs.GameStorage
import com.example.limbuszhcn.gamefs.GameTextLanguage
import com.example.limbuszhcn.update.sha256
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal enum class AiBaseChangeKind(val label: String) { Added("新增译文"), Replaced("改写译文") }
internal data class AiBaseChange(val id: String, val kind: AiBaseChangeKind, val resource: String, val original: String, val current: String?, val updated: String)
internal data class AiBaseReport(val key: String, val sourceId: String, val revision: String, val latest: String,
    val version: String, val language: GameTextLanguage, val added: Int, val replaced: Int, val historical: Boolean) {
    val total get() = added + replaced
}
internal data class AiBasePage(val rows: List<AiBaseChange>, val total: Int, val page: Int) { val pages get() = ((total+9)/10).coerceAtLeast(1) }
internal data class AiBaseRequest(val report: AiBaseReport, val kind: AiBaseChangeKind? = null, val ids: Set<String>? = null, val count: Int)
internal data class AiBaseState(val sourceId: String? = null, val report: AiBaseReport? = null, val loading: Boolean = false, val error: String? = null, val message: String? = null)

internal class AiBaseUpdate(private val cache: Path) {
    private val root = cache.resolve("ai-base-updates")
    private fun file(key: String): Path { require(key.matches(Regex("[a-f0-9]{64}"))); return root.resolve("$key.db") }
    private fun open(r: AiBaseReport) = SQLiteDatabase.openDatabase(file(r.key).toString(),null,SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)

    fun inspect(sources: TranslationSources, source: TranslationSource, latest: PreparedTranslation, storage: GameStorage,
        progress: (String, Int, Int) -> Unit = { _, _, _ -> }): AiBaseReport {
        require(source.kind == "ai" && !source.independent && source.baseId != "global") { "从零生成的包没有上游底包" }
        val language = source.language?.let(GameTextLanguage::fromCode) ?: requireNotNull(storage.textLanguage()).language
        val snapshots = AiBaseSnapshots(cache)
        val previous = snapshots.previous(source)
        // A reviewed snapshot remains immutable even if another base version arrives while this screen is open.
        snapshots.store(latest)
        val prepared = sources.prepare(source)
        val proof = AiTranslationReuse.collect(cache, source, prepared, storage, requestedLanguage = language)
        val current = TranslationInspection(storage,prepared,requestedLanguage=language)
        val newer = TranslationInspection(storage,latest,requestedLanguage=language)
        val older = previous?.let { TranslationInspection(storage,it,requestedLanguage=language) }
        val key = AiTranslationJob.digest("base-v1|${source.id}|${source.revision}|${latest.manifest.archiveSha256}|${language.code}|${java.util.UUID.randomUUID()}")
        Files.createDirectories(root)
        val pending = root.resolve("$key.tmp.db"); Files.deleteIfExists(pending)
        var added = 0; var replaced = 0; var totalBytes = 0L
        try {
            SQLiteDatabase.openDatabase(pending.toString(),null,SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { db ->
                db.beginTransaction()
                try {
                    db.execSQL("CREATE TABLE changes(number INTEGER PRIMARY KEY,id TEXT UNIQUE,kind TEXT,resource TEXT,hash TEXT,original TEXT,current TEXT,updated TEXT)")
                    db.compileStatement("INSERT INTO changes VALUES(?,?,?,?,?,?,?,?)").use { insert ->
                        progress("对照原包译文", 0, newer.resources.size)
                        for ((index, key) in newer.resources.withIndex()) {
                            progress("对照原包译文", index, newer.resources.size)
                            val fresh = newer.readable(key)
                            if (fresh == null) { require(key !in newer.entries) { "部分原文无法读取，未更新底包；请先修复游戏资源" }; continue }
                            totalBytes += fresh.bytes; require(totalBytes <= 128L*1024*1024)
                            val before = older?.readable(key)?.fields?.associateBy { it.id }.orEmpty()
                            val active = current.readable(key)?.fields?.associateBy { it.id }.orEmpty()
                            for (field in fresh.fields) {
                                if (!field.canTranslate || !field.covered || field.needsReview) continue
                                val old = before[field.id]?.takeIf { it.covered }?.translated
                                val now = active[field.id]?.takeIf { it.covered }?.translated
                                val next = requireNotNull(field.translated)
                                if (next == now && field.id !in proof || older != null && next == old) continue
                                val kind = if (old == null && (older != null || now == null)) AiBaseChangeKind.Added else AiBaseChangeKind.Replaced
                                if (kind == AiBaseChangeKind.Added) added++ else replaced++
                                require(added+replaced <= MAX_AI_TASK_ENTRIES)
                                insert.bindLong(1,(added+replaced).toLong())
                                listOf(field.id,kind.name,key,AiTranslationJob.digest(fresh.raw),field.text,now,next).forEachIndexed { i,value ->
                                    if (value == null) insert.bindNull(i+2) else insert.bindString(i+2,value)
                                };insert.executeInsert()
                            }
                        }
                        progress("对照原包译文", newer.resources.size, newer.resources.size)
                    }
                    db.execSQL("CREATE INDEX change_kind ON changes(kind,number)");db.execSQL("CREATE INDEX change_resource ON changes(resource)");db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            com.example.limbuszhcn.storage.AtomicFiles.move(pending,file(key),true)
            // Reports contain reconstructible text, not task results. Bound repeated checks.
            runCatching { Files.list(root).use { paths -> paths.filter {
                Files.isRegularFile(it) && it.fileName.toString().matches(Regex("[a-f0-9]{64}\\.db"))
            }.sorted(compareByDescending<Path> { if (it == file(key)) Long.MAX_VALUE else Files.getLastModifiedTime(it).toMillis() })
                .skip(2).forEach { Files.deleteIfExists(it) } } }
        } finally { Files.deleteIfExists(pending) }
        return AiBaseReport(key,source.id,source.revision,latest.manifest.archiveSha256,latest.manifest.requestedVersion,language,added,replaced,older!=null)
    }
    private fun where(kind: AiBaseChangeKind?, ids: Set<String>?): Pair<String,Array<String>> {
        if (ids != null) {
            require(ids.isNotEmpty() && ids.size <= 1000 && ids.all { it.matches(Regex("[a-f0-9]{64}")) })
            return ids.joinToString(","," WHERE id IN (",")") { "'$it'" } to emptyArray()
        }
        return if (kind==null) "" to emptyArray() else " WHERE kind=?" to arrayOf(kind.name)
    }
    fun page(r: AiBaseReport, kind: AiBaseChangeKind?, requested: Int, cancel: CancellationSignal): AiBasePage = open(r).use { db ->
        val (where,args)=where(kind,null)
        val total=db.rawQuery("SELECT count(*) FROM changes$where",args,cancel).use { it.moveToFirst();it.getInt(0) }
        val page=requested.coerceIn(0,((total-1).coerceAtLeast(0))/10)
        val rows=db.rawQuery("SELECT id,kind,resource,original,current,updated FROM changes$where ORDER BY number LIMIT 10 OFFSET ${page*10}",args,cancel).use { c ->
            buildList { while(c.moveToNext()) add(AiBaseChange(c.getString(0),AiBaseChangeKind.valueOf(c.getString(1)),c.getString(2),c.getString(3),c.getString(4),c.getString(5))) }
        };AiBasePage(rows,total,page)
    }
    fun apply(sources: TranslationSources, storage: GameStorage, request: AiBaseRequest, progress: (String, Int, Int) -> Unit = { _, _, _ -> }) {
        val r=request.report
        val source=requireNotNull(sources.read().sources.find { it.id==r.sourceId && it.revision==r.revision && it.kind=="ai" }) { "AI 包已变化，请重新检查" }
        val base=sources.prepare(source); val snapshots=AiBaseSnapshots(cache);val latest=snapshots.read(r.latest)
        val inspection=TranslationInspection(storage,base,requestedLanguage=r.language)
        val entries=base.manifest.entries.associateBy { r.language.resourceKey(it.targetPath) }
        val (where,args)=where(request.kind,request.ids)
        val selected=linkedMapOf<String,MutableSet<String>>();val hashes=mutableMapOf<String,String>()
        open(r).use { db -> db.rawQuery("SELECT resource,hash,id FROM changes$where ORDER BY number",args).use { c ->
            while(c.moveToNext()) { selected.getOrPut(c.getString(0)){linkedSetOf()}.add(c.getString(2));hashes[c.getString(0)]=c.getString(1) }
        } }
        require(selected.values.sumOf { it.size }==request.count && (request.count>0 || r.total==0))
        selected.forEach { (key,_) -> require(AiTranslationJob.digest(inspection.read(key).raw)==hashes[key]) { "游戏文本已变化，请重新检查" } }
        val archive=root.resolve("merged.zip")
        val changes = AiChangeCollector(AiChangeKind.BaseUpdate)
        try {
            open(r).use { db -> ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
                val resources = (entries.keys+selected.keys).toList()
                progress("合并所选译文", 0, resources.size)
                resources.forEachIndexed { index, key ->
                    val ids=selected[key]
                    val values=ids?.let { selectedIds -> db.rawQuery("SELECT id,updated FROM changes WHERE resource=?",arrayOf(key)).use { c ->
                        buildMap { while(c.moveToNext()) if(c.getString(0) in selectedIds) put(c.getString(0),c.getString(1)) }
                    } }
                    val bytes=if(values==null) Files.readAllBytes(base.directory.resolve(entries.getValue(key).cachePath)) else {
                        val resource=inspection.read(key);require(AiTranslationJob.digest(resource.raw)==hashes[key])
                        var changed=0
                        resource.fields.forEach { field ->
                            val value=values[field.id]
                            if(value!=null){changes.record(key, field.text, field.translated, value);field.apply(value);changed++} else if(!field.covered)field.clear()
                        };require(changed==values.size)
                        TranslationSnapshot.encode(resource.raw,resource.document).toByteArray(Charsets.UTF_8)
                    }
                    zip.putNextEntry(ZipEntry(key).apply{time=0});zip.write(bytes);zip.closeEntry()
                    progress("合并所选译文", index + 1, resources.size)
                }
            } }
            val proof=AiTranslationReuse.collect(cache,source,base,storage,requestedLanguage=r.language)
            val chosen=selected.values.flatten().toSet();val hash=archive.sha256()
            AiProvenanceExclusions.write(cache,source.id,hash,AiProvenanceExclusions.read(cache,source)+chosen)
            val all=request.count==r.total
            (if(all) latest else snapshots.previous(source))?.let { snapshots.remember(source.id,hash,it) }
            require(sources.read().sources.any{it.id==r.sourceId && it.revision==r.revision})
            sources.addGenerated(archive,source.baseId?:"zero",source.id.removePrefix("ai-"),source.name,
                if(all && (source.baseId==null || source.baseId=="zero")) r.version.toLongOrNull() else source.officialVersion,
                activate=sources.read().selectedId==source.id,independent=source.independent,language=r.language.code,
                aiEntryCount=proof.keys.count { it !in chosen },baseName=source.baseName,summary=changes.snapshot())
        } finally { Files.deleteIfExists(archive) }
    }
}
