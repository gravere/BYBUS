package com.example.limbuszhcn.translation

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteStatement
import android.os.CancellationSignal
import java.nio.file.Files
import java.nio.file.Path

internal data class CoverageIndexRef(val pack: String, val generation: String) {
    init {
        require(pack.matches(Regex("[a-f0-9]{64}")) && generation.matches(Regex("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")))
    }
    fun file(cache: Path): Path = cache.resolve("coverage/packages").resolve(pack).resolve("entries-$generation.db")
}

internal typealias CoveragePageReader = (CoverageIndexRef, CoverageFilter, Int, CancellationSignal) -> CoveragePage

/** Each completed check has an immutable index. Home and source switching read only the small JSON summary. */
internal object CoverageEntryIndex {
    fun write(file: Path, scan: ((CoverageEntry) -> Unit) -> CoverageReport): CoverageReport {
        Files.createDirectories(file.parent)
        Files.createFile(file)
        val flags = SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        return SQLiteDatabase.openDatabase(file.toString(), null, flags).use { db ->
            db.disableWriteAheadLogging()
            db.beginTransaction()
            try {
                db.execSQL("CREATE TABLE files (id INTEGER PRIMARY KEY, category TEXT NOT NULL, resource TEXT NOT NULL UNIQUE)")
                db.execSQL("""CREATE TABLE issues (number INTEGER PRIMARY KEY, file INTEGER NOT NULL, review INTEGER NOT NULL, section INTEGER NOT NULL,
                    original TEXT NOT NULL, translation TEXT, game_id TEXT, pointer TEXT NOT NULL)""")
                val report = db.compileStatement("INSERT INTO files(category, resource) VALUES(?,?)").use { files ->
                    db.compileStatement("INSERT INTO issues VALUES(?,?,?,?,?,?,?,?)").use { entries ->
                        var lastFile = ""; var fileId = 0L
                        val sections = mutableMapOf<String, Long>()
                        var missing = 0; var review = 0
                        val result = scan { entry ->
                            if (entry.resource != lastFile) {
                                files.bindString(1, entry.category); files.bindString(2, entry.resource)
                                fileId = files.executeInsert(); lastFile = entry.resource
                            }
                            if (entry.kind == CoverageKind.Review) review++ else missing++
                            check(entry.number == missing + review)
                            entries.bindLong(1, entry.number.toLong()); entries.bindLong(2, fileId)
                            entries.bindLong(3, if (entry.kind == CoverageKind.Review) 1 else 0)
                            entries.bindLong(4, sections.getOrPut(entry.category) { sections.size.toLong() })
                            entries.bindString(5, entry.text); entries.textOrNull(6, entry.translation)
                            entries.textOrNull(7, entry.recordId); entries.bindString(8, entry.pointer)
                            entries.executeInsert()
                        }
                        check(missing == result.missing && review == result.review) { "明细条数与检查结果不一致" }
                        result
                    }
                }
                db.execSQL("CREATE INDEX issue_file_status ON issues(file, review, number)")
                db.execSQL("CREATE INDEX issue_groups ON issues(review, section, file, number)")
                db.execSQL("CREATE INDEX file_category ON files(category, resource)")
                db.version = 1
                db.setTransactionSuccessful()
                report
            } finally { db.endTransaction() }
        }
    }

    private fun SQLiteStatement.textOrNull(index: Int, text: String?) {
        if (text == null) bindNull(index) else bindString(index, text)
    }

    fun read(file: Path, filter: CoverageFilter, requestedPage: Int, cancel: CancellationSignal? = null): CoveragePage {
        val args = ArrayList<String>()
        val conditions = ArrayList<String>()
        filter.kind?.let { conditions += "i.review = ?"; args += if (it == CoverageKind.Review) "1" else "0" }
        filter.category?.let { conditions += "f.category = ?"; args += it }
        val number = filter.exactNumber
        if (number != null) {
            conditions += "i.number = ?"; args += number.toString()
        } else if (filter.term.isNotEmpty()) {
            val columns = listOf("i.original", "i.translation", "i.game_id", "f.resource")
            conditions += columns.joinToString(" OR ", "(i.number = ? OR ", ")") { "instr(lower($it), lower(?)) > 0" }
            args += filter.term
            repeat(columns.size) { args += filter.term }
        }
        val from = " FROM issues i JOIN files f ON f.id = i.file" +
            if (conditions.isEmpty()) "" else conditions.joinToString(" AND ", " WHERE ")
        val flags = SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        return SQLiteDatabase.openDatabase(file.toString(), null, flags).use { db ->
            check(db.version == 1) { "无法读取此版本的明细" }
            val total = db.rawQuery("SELECT count(*)" + from, args.toTypedArray(), cancel).use { it.moveToFirst(); it.getInt(0) }
            val page = requestedPage.coerceIn(0, (total - 1).coerceAtLeast(0) / COVERAGE_PAGE_SIZE)
            // Bound displayed strings for Android's CursorWindow. Searches above use the full stored text.
            val select = """SELECT i.number,i.review,f.category,f.resource,substr(i.original,1,12001),
                substr(i.translation,1,12001),substr(i.game_id,1,256),substr(i.pointer,1,4096)"""
            val order = " ORDER BY i.review, i.section, i.file, i.number LIMIT ? OFFSET ?"
            val rows = db.rawQuery(select + from + order, (args + listOf(COVERAGE_PAGE_SIZE.toString(),
                (page * COVERAGE_PAGE_SIZE).toString())).toTypedArray(), cancel).use { c ->
                buildList { while (c.moveToNext()) add(CoverageEntry(c.getInt(0),
                    if (c.getInt(1) == 1) CoverageKind.Review else CoverageKind.Missing, c.getString(2), c.getString(3),
                    c.getString(4), c.getString(5), c.getString(6), c.getString(7))) }
            }
            CoveragePage(rows, total, page)
        }
    }
}
