package com.dmer.neoreaderrecords

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

object ReadingDataStore {
    private const val DB_NAME = "readtrace_reading.db"
    private const val DB_VERSION = 4
    private const val TABLE_DAILY_BOOKS = "reading_daily_books"
    private const val TABLE_DAILY_TOTALS = "reading_daily_totals"
    private const val TABLE_PERIOD_BOOKS = "reading_period_books"
    private const val TABLE_WEREAD_BOOK_STATE = "weread_book_state"
    private const val TABLE_WEREAD_PERIOD_STATE = "weread_period_state"
    private const val TABLE_SYNC_META = "reading_sync_meta"
    private const val TABLE_BOOK_NOTES = "reading_book_notes"

    data class DailyBookRecord(
        val date: String,
        val source: String,
        val bookKey: String,
        val title: String,
        val author: String?,
        val coverCachePath: String?,
        val durationMs: Long,
        val progress: String?,
        val status: Int,
        val confidence: String,
        val lastSeenAt: Long
    )

    data class DailyTotalRecord(
        val date: String,
        val source: String,
        val durationMs: Long,
        val confidence: String,
        val updatedAt: Long
    )

    data class PeriodBookRecord(
        val periodStart: String,
        val periodEnd: String,
        val source: String,
        val bookKey: String,
        val title: String,
        val author: String?,
        val coverCachePath: String?,
        val durationMs: Long,
        val confidence: String,
        val lastSeenAt: Long
    )

    data class WeReadBookState(
        val bookKey: String,
        val lastReadAt: Long,
        val recordReadingMs: Long
    )

    data class WeReadSnapshotBook(
        val bookKey: String,
        val title: String,
        val author: String?,
        val coverCachePath: String?,
        val lastReadAt: Long,
        val readDate: String?,
        val monthlyDurationMs: Long?,
        val recordReadingMs: Long?,
        val progress: String?,
        val status: Int
    )

    data class WeReadSnapshotApplyResult(
        val baseline: Boolean,
        val historyImported: Boolean,
        val historyRecordsWritten: Int,
        val changedBooks: Int,
        val dailyRecordsWritten: Int,
        val trackingStartDate: String
    )

    data class BookNoteRecord(
        val bookKey: String,
        val title: String,
        val author: String?,
        val note: String,
        val updatedAt: Long
    )

    data class DailyBookPageRecord(
        val date: String,
        val source: String,
        val bookKey: String,
        val title: String,
        val author: String?,
        val durationMs: Long,
        val progress: String?,
        val status: Int,
        val confidence: String,
        val lastSeenAt: Long,
        val updatedAt: Long
    )

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_DAILY_BOOKS (
                    date TEXT NOT NULL,
                    source TEXT NOT NULL,
                    book_key TEXT NOT NULL,
                    title TEXT NOT NULL,
                    author TEXT,
                    cover_cache_path TEXT,
                    duration_ms INTEGER NOT NULL DEFAULT 0,
                    progress TEXT,
                    status INTEGER NOT NULL DEFAULT 0,
                    confidence TEXT NOT NULL,
                    last_seen_at INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(date, source, book_key)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_daily_books_date ON $TABLE_DAILY_BOOKS(date)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_daily_books_source_date ON $TABLE_DAILY_BOOKS(source, date)")
            createV2Tables(db)
            createV3Tables(db)
            createV4Tables(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) createV2Tables(db)
            if (oldVersion < 3) createV3Tables(db)
            if (oldVersion < 4) createV4Tables(db)
        }

        private fun createV2Tables(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_DAILY_TOTALS (
                    date TEXT NOT NULL,
                    source TEXT NOT NULL,
                    duration_ms INTEGER NOT NULL DEFAULT 0,
                    confidence TEXT NOT NULL,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(date, source)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_PERIOD_BOOKS (
                    period_start TEXT NOT NULL,
                    period_end TEXT NOT NULL,
                    source TEXT NOT NULL,
                    book_key TEXT NOT NULL,
                    title TEXT NOT NULL,
                    author TEXT,
                    cover_cache_path TEXT,
                    duration_ms INTEGER NOT NULL DEFAULT 0,
                    confidence TEXT NOT NULL,
                    last_seen_at INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(period_start, period_end, source, book_key)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_daily_totals_source_date ON $TABLE_DAILY_TOTALS(source, date)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_period_books_source_period ON $TABLE_PERIOD_BOOKS(source, period_start, period_end)")
        }

        private fun createV3Tables(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_WEREAD_BOOK_STATE (
                    book_key TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    author TEXT,
                    cover_cache_path TEXT,
                    last_read_at INTEGER NOT NULL DEFAULT 0,
                    record_reading_ms INTEGER NOT NULL DEFAULT 0,
                    progress TEXT,
                    status INTEGER NOT NULL DEFAULT 1,
                    updated_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_WEREAD_PERIOD_STATE (
                    period_start TEXT NOT NULL,
                    period_end TEXT NOT NULL,
                    book_key TEXT NOT NULL,
                    duration_ms INTEGER NOT NULL DEFAULT 0,
                    updated_at INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(period_start, period_end, book_key)
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_SYNC_META (
                    key TEXT NOT NULL PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """.trimIndent()
            )
        }

        private fun createV4Tables(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_BOOK_NOTES (
                    book_key TEXT NOT NULL PRIMARY KEY,
                    title TEXT NOT NULL,
                    author TEXT,
                    note TEXT NOT NULL,
                    updated_at INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_book_notes_title ON $TABLE_BOOK_NOTES(title)")
        }
    }

    fun upsertBookNote(context: Context, bookKey: String, title: String, author: String?, note: String): Boolean {
        if (!AutoRefreshConfig.isReadingDataStoreEnabled(context)) return false
        val cleanKey = bookKey.trim()
        if (cleanKey.isBlank()) return false
        return runCatching {
            Helper(context.applicationContext).writableDatabase.use { db ->
                db.insertWithOnConflict(TABLE_BOOK_NOTES, null, ContentValues().apply {
                    put("book_key", cleanKey)
                    put("title", title.ifBlank { cleanKey })
                    put("author", author)
                    put("note", note)
                    put("updated_at", System.currentTimeMillis())
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            true
        }.getOrElse {
            AutoRefreshLog.e(context, "ReadingDataStore book note upsert failed key=${cleanKey.take(80)}", it)
            false
        }
    }

    fun queryBookNote(context: Context, bookKeys: List<String>): BookNoteRecord? {
        if (!AutoRefreshConfig.isReadingDataStoreEnabled(context)) return null
        val keys = bookKeys.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (keys.isEmpty()) return null
        return runCatching {
            Helper(context.applicationContext).readableDatabase.use { db ->
                keys.forEach { key ->
                    db.query(
                        TABLE_BOOK_NOTES,
                        arrayOf("book_key", "title", "author", "note", "updated_at"),
                        "book_key=?",
                        arrayOf(key),
                        null,
                        null,
                        null,
                        "1"
                    ).use { c ->
                        if (c.moveToFirst()) {
                            return@runCatching BookNoteRecord(
                                bookKey = c.getString(0),
                                title = c.getString(1),
                                author = c.getString(2),
                                note = c.getString(3),
                                updatedAt = c.getLong(4)
                            )
                        }
                    }
                }
                null
            }
        }.getOrElse {
            AutoRefreshLog.e(context, "ReadingDataStore book note query failed keys=${keys.joinToString("|").take(160)}", it)
            null
        }
    }

    fun upsertDailyBooks(context: Context, records: List<DailyBookRecord>, reason: String): Int {
        if (!AutoRefreshConfig.isReadingDataStoreEnabled(context)) {
            AutoRefreshLog.i(context, "ReadingDataStore upsert skip disabled reason=$reason")
            return 0
        }
        if (records.isEmpty()) {
            AutoRefreshLog.i(context, "ReadingDataStore upsert skip reason=$reason records=0")
            return 0
        }
        return runCatching {
            val now = System.currentTimeMillis()
            var written = 0
            Helper(context.applicationContext).writableDatabase.use { db ->
                db.beginTransaction()
                try {
                    records.forEach { record ->
                        val values = ContentValues().apply {
                            put("date", record.date)
                            put("source", record.source)
                            put("book_key", record.bookKey)
                            put("title", record.title)
                            put("author", record.author)
                            put("cover_cache_path", record.coverCachePath)
                            put("duration_ms", record.durationMs)
                            put("progress", record.progress)
                            put("status", record.status)
                            put("confidence", record.confidence)
                            put("last_seen_at", record.lastSeenAt)
                            put("updated_at", now)
                        }
                        db.insertWithOnConflict(TABLE_DAILY_BOOKS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                        written += 1
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            AutoRefreshLog.i(context, "ReadingDataStore upsert reason=$reason records=${records.size} written=$written")
            written
        }.getOrElse {
            AutoRefreshLog.e(context, "ReadingDataStore upsert failed reason=$reason records=${records.size}", it)
            0
        }
    }

    fun replaceDailyBooksForRange(
        context: Context,
        source: String,
        startDate: String,
        endDate: String,
        records: List<DailyBookRecord>,
        reason: String
    ): Int {
        if (!AutoRefreshConfig.isReadingDataStoreEnabled(context)) {
            AutoRefreshLog.i(context, "ReadingDataStore replace skip disabled reason=$reason")
            return 0
        }
        require(startDate <= endDate) { "Invalid date range: $startDate > $endDate" }
        val validRecords = records.filter {
            it.source == source && it.date >= startDate && it.date <= endDate
        }
        return runCatching {
            val now = System.currentTimeMillis()
            var written = 0
            var deleted = 0
            Helper(context.applicationContext).writableDatabase.use { db ->
                db.beginTransaction()
                try {
                    deleted = db.delete(
                        TABLE_DAILY_BOOKS,
                        "source = ? AND date >= ? AND date <= ?",
                        arrayOf(source, startDate, endDate)
                    )
                    validRecords.forEach { record ->
                        db.insertWithOnConflict(
                            TABLE_DAILY_BOOKS,
                            null,
                            record.toContentValues(now),
                            SQLiteDatabase.CONFLICT_REPLACE
                        )
                        written += 1
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            AutoRefreshLog.i(
                context,
                "ReadingDataStore replace reason=$reason source=$source range=$startDate~$endDate deleted=$deleted records=${records.size} valid=${validRecords.size} written=$written"
            )
            written
        }.getOrElse {
            AutoRefreshLog.e(
                context,
                "ReadingDataStore replace failed reason=$reason source=$source range=$startDate~$endDate records=${records.size}",
                it
            )
            0
        }
    }

    fun countDailyBooks(context: Context): Int {
        return runCatching {
            Helper(context.applicationContext).readableDatabase.use { db ->
                db.rawQuery("SELECT COUNT(*) FROM $TABLE_DAILY_BOOKS", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
            }
        }.getOrDefault(0)
    }

    fun countDailyBooks(context: Context, source: String?): Int {
        val cleanSource = source?.trim()?.takeIf { it.isNotBlank() }
        return if (cleanSource == null) {
            countRows(context, TABLE_DAILY_BOOKS, "1=1", emptyArray<String>())
        } else {
            countRows(context, TABLE_DAILY_BOOKS, "source = ?", arrayOf(cleanSource))
        }
    }

    fun queryDailyBooksPage(
        context: Context,
        source: String?,
        limit: Int,
        offset: Int
    ): List<DailyBookPageRecord> {
        val cleanSource = source?.trim()?.takeIf { it.isNotBlank() }
        return runCatching {
            val out = mutableListOf<DailyBookPageRecord>()
            Helper(context.applicationContext).readableDatabase.use { db ->
                db.query(
                    TABLE_DAILY_BOOKS,
                    arrayOf(
                        "date", "source", "book_key", "title", "author",
                        "duration_ms", "progress", "status", "confidence", "last_seen_at", "updated_at"
                    ),
                    if (cleanSource == null) null else "source = ?",
                    if (cleanSource == null) null else arrayOf(cleanSource),
                    null,
                    null,
                    "date DESC, updated_at DESC, duration_ms DESC",
                    "${limit.coerceIn(1, 100)} OFFSET ${offset.coerceAtLeast(0)}"
                ).use { c ->
                    while (c.moveToNext()) {
                        out.add(
                            DailyBookPageRecord(
                                date = c.getString(0),
                                source = c.getString(1),
                                bookKey = c.getString(2),
                                title = c.getString(3),
                                author = if (c.isNull(4)) null else c.getString(4),
                                durationMs = c.getLong(5),
                                progress = if (c.isNull(6)) null else c.getString(6),
                                status = c.getInt(7),
                                confidence = c.getString(8),
                                lastSeenAt = c.getLong(9),
                                updatedAt = c.getLong(10)
                            )
                        )
                    }
                }
            }
            out
        }.getOrElse {
            AutoRefreshLog.e(context, "ReadingDataStore page query failed source=${cleanSource ?: "ALL"}", it)
            emptyList()
        }
    }

    fun deleteDailyBook(context: Context, date: String, source: String, bookKey: String): Boolean {
        val cleanDate = date.trim()
        val cleanSource = source.trim()
        val cleanKey = bookKey.trim()
        if (cleanDate.isBlank() || cleanSource.isBlank() || cleanKey.isBlank()) return false
        return runCatching {
            val deleted = Helper(context.applicationContext).writableDatabase.use { db ->
                db.delete(
                    TABLE_DAILY_BOOKS,
                    "date = ? AND source = ? AND book_key = ?",
                    arrayOf(cleanDate, cleanSource, cleanKey)
                )
            }
            AutoRefreshLog.i(context, "ReadingDataStore delete daily book date=$cleanDate source=$cleanSource key=${cleanKey.take(80)} deleted=$deleted")
            deleted > 0
        }.getOrElse {
            AutoRefreshLog.e(context, "ReadingDataStore delete daily book failed date=$cleanDate source=$cleanSource key=${cleanKey.take(80)}", it)
            false
        }
    }

    fun replaceDailyTotalsForRange(
        context: Context,
        source: String,
        startDate: String,
        endDate: String,
        records: List<DailyTotalRecord>,
        reason: String
    ): Int {
        if (!AutoRefreshConfig.isReadingDataStoreEnabled(context)) {
            AutoRefreshLog.i(context, "ReadingDataStore daily totals skip disabled reason=$reason")
            return 0
        }
        require(startDate <= endDate) { "Invalid date range: $startDate > $endDate" }
        val valid = records.filter { it.source == source && it.date in startDate..endDate }
        return runCatching {
            var written = 0
            var deleted = 0
            Helper(context.applicationContext).writableDatabase.use { db ->
                db.beginTransaction()
                try {
                    deleted = db.delete(
                        TABLE_DAILY_TOTALS,
                        "source = ? AND date >= ? AND date <= ?",
                        arrayOf(source, startDate, endDate)
                    )
                    valid.forEach { record ->
                        val values = ContentValues().apply {
                            put("date", record.date)
                            put("source", record.source)
                            put("duration_ms", record.durationMs)
                            put("confidence", record.confidence)
                            put("updated_at", record.updatedAt)
                        }
                        db.insertWithOnConflict(TABLE_DAILY_TOTALS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                        written += 1
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            AutoRefreshLog.i(
                context,
                "ReadingDataStore daily totals replace reason=$reason source=$source range=$startDate~$endDate deleted=$deleted records=${records.size} valid=${valid.size} written=$written"
            )
            written
        }.getOrElse {
            AutoRefreshLog.e(context, "ReadingDataStore daily totals replace failed reason=$reason source=$source", it)
            0
        }
    }

    fun replacePeriodBooks(
        context: Context,
        source: String,
        periodStart: String,
        periodEnd: String,
        records: List<PeriodBookRecord>,
        reason: String
    ): Int {
        if (!AutoRefreshConfig.isReadingDataStoreEnabled(context)) {
            AutoRefreshLog.i(context, "ReadingDataStore period books skip disabled reason=$reason")
            return 0
        }
        val valid = records.filter {
            it.source == source && it.periodStart == periodStart && it.periodEnd == periodEnd
        }
        return runCatching {
            var written = 0
            var deleted = 0
            val now = System.currentTimeMillis()
            Helper(context.applicationContext).writableDatabase.use { db ->
                db.beginTransaction()
                try {
                    deleted = db.delete(
                        TABLE_PERIOD_BOOKS,
                        "source = ? AND period_start = ? AND period_end = ?",
                        arrayOf(source, periodStart, periodEnd)
                    )
                    valid.forEach { record ->
                        val values = ContentValues().apply {
                            put("period_start", record.periodStart)
                            put("period_end", record.periodEnd)
                            put("source", record.source)
                            put("book_key", record.bookKey)
                            put("title", record.title)
                            put("author", record.author)
                            put("cover_cache_path", record.coverCachePath)
                            put("duration_ms", record.durationMs)
                            put("confidence", record.confidence)
                            put("last_seen_at", record.lastSeenAt)
                            put("updated_at", now)
                        }
                        db.insertWithOnConflict(TABLE_PERIOD_BOOKS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                        written += 1
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            AutoRefreshLog.i(
                context,
                "ReadingDataStore period books replace reason=$reason source=$source period=$periodStart~$periodEnd deleted=$deleted records=${records.size} valid=${valid.size} written=$written"
            )
            written
        }.getOrElse {
            AutoRefreshLog.e(context, "ReadingDataStore period books replace failed reason=$reason source=$source", it)
            0
        }
    }

    fun countDailyTotals(context: Context, source: String): Int {
        return countRows(context, TABLE_DAILY_TOTALS, "source = ?", arrayOf(source))
    }

    fun countPeriodBooks(context: Context, source: String): Int {
        return countRows(context, TABLE_PERIOD_BOOKS, "source = ?", arrayOf(source))
    }

    fun queryDailyTotals(
        context: Context,
        source: String,
        startDate: String,
        endDate: String
    ): List<DailyTotalRecord> {
        return runCatching {
            val out = mutableListOf<DailyTotalRecord>()
            Helper(context.applicationContext).readableDatabase.use { db ->
                db.query(
                    TABLE_DAILY_TOTALS,
                    arrayOf("date", "source", "duration_ms", "confidence", "updated_at"),
                    "source = ? AND date >= ? AND date <= ?",
                    arrayOf(source, startDate, endDate),
                    null,
                    null,
                    "date ASC"
                ).use { c ->
                    while (c.moveToNext()) {
                        out.add(
                            DailyTotalRecord(
                                date = c.getString(0),
                                source = c.getString(1),
                                durationMs = c.getLong(2),
                                confidence = c.getString(3),
                                updatedAt = c.getLong(4)
                            )
                        )
                    }
                }
            }
            out
        }.getOrElse {
            AutoRefreshLog.e(
                context,
                "ReadingDataStore daily totals query failed source=$source range=$startDate~$endDate",
                it
            )
            emptyList()
        }
    }

    fun queryWeReadBookStates(context: Context): Map<String, WeReadBookState> {
        return runCatching {
            val out = linkedMapOf<String, WeReadBookState>()
            Helper(context.applicationContext).readableDatabase.use { db ->
                db.query(
                    TABLE_WEREAD_BOOK_STATE,
                    arrayOf("book_key", "last_read_at", "record_reading_ms"),
                    null,
                    null,
                    null,
                    null,
                    null
                ).use { c ->
                    while (c.moveToNext()) {
                        val state = WeReadBookState(c.getString(0), c.getLong(1), c.getLong(2))
                        out[state.bookKey] = state
                    }
                }
            }
            out
        }.getOrElse {
            AutoRefreshLog.e(context, "ReadingDataStore WeRead state query failed", it)
            emptyMap()
        }
    }

    fun isWeReadShelfHistoryImported(context: Context): Boolean {
        return runCatching {
            Helper(context.applicationContext).readableDatabase.use { db ->
                db.query(
                    TABLE_SYNC_META,
                    arrayOf("value"),
                    "key = ?",
                    arrayOf(KEY_WEREAD_SHELF_HISTORY_IMPORTED),
                    null,
                    null,
                    null
                ).use { c -> c.moveToFirst() && c.getString(0) == "1" }
            }
        }.getOrElse {
            AutoRefreshLog.e(context, "ReadingDataStore WeRead history meta query failed", it)
            false
        }
    }

    fun applyWeReadSnapshot(
        context: Context,
        periodStart: String,
        periodEnd: String,
        trackingDate: String,
        books: List<WeReadSnapshotBook>
    ): WeReadSnapshotApplyResult? {
        if (!AutoRefreshConfig.isReadingDataStoreEnabled(context)) {
            AutoRefreshLog.i(context, "ReadingDataStore WeRead snapshot skip disabled")
            return null
        }
        return runCatching {
            val now = System.currentTimeMillis()
            var baseline = false
            var historyImported = false
            var historyWritten = 0
            var changedBooks = 0
            var dailyWritten = 0
            var trackingStartDate = trackingDate
            Helper(context.applicationContext).writableDatabase.use { db ->
                db.beginTransaction()
                try {
                    val priorBookTimes = linkedMapOf<String, Long>()
                    val priorRecordReading = linkedMapOf<String, Long>()
                    db.query(
                        TABLE_WEREAD_BOOK_STATE,
                        arrayOf("book_key", "last_read_at", "record_reading_ms"),
                        null,
                        null,
                        null,
                        null,
                        null
                    ).use { c ->
                        while (c.moveToNext()) {
                            priorBookTimes[c.getString(0)] = c.getLong(1)
                            priorRecordReading[c.getString(0)] = c.getLong(2)
                        }
                    }
                    baseline = priorBookTimes.isEmpty()
                    if (baseline) {
                        val deletedLegacy = db.delete(
                            TABLE_DAILY_BOOKS,
                            "source = ?",
                            arrayOf("WEREAD")
                        )
                        AutoRefreshLog.i(
                            context,
                            "ReadingDataStore WeRead baseline cleared legacy daily records=$deletedLegacy"
                        )
                    }
                    trackingStartDate = db.query(
                        TABLE_SYNC_META,
                        arrayOf("value"),
                        "key = ?",
                        arrayOf("weread_tracking_start_date"),
                        null,
                        null,
                        null
                    ).use { c ->
                        if (c.moveToFirst()) c.getString(0) else trackingDate
                    }
                    db.insertWithOnConflict(
                        TABLE_SYNC_META,
                        null,
                        ContentValues().apply {
                            put("key", "weread_tracking_start_date")
                            put("value", trackingStartDate)
                        },
                        SQLiteDatabase.CONFLICT_IGNORE
                    )
                    historyImported = db.query(
                        TABLE_SYNC_META,
                        arrayOf("value"),
                        "key = ?",
                        arrayOf(KEY_WEREAD_SHELF_HISTORY_IMPORTED),
                        null,
                        null,
                        null
                    ).use { c -> !c.moveToFirst() || c.getString(0) != "1" }

                    val priorPeriodDurations = linkedMapOf<String, Long>()
                    db.query(
                        TABLE_WEREAD_PERIOD_STATE,
                        arrayOf("book_key", "duration_ms"),
                        "period_start = ? AND period_end = ?",
                        arrayOf(periodStart, periodEnd),
                        null,
                        null,
                        null
                    ).use { c ->
                        while (c.moveToNext()) priorPeriodDurations[c.getString(0)] = c.getLong(1)
                    }

                    books.forEach { book ->
                        val priorReadAt = priorBookTimes[book.bookKey] ?: 0L
                        val changed = !baseline &&
                            book.lastReadAt > priorReadAt &&
                            !book.readDate.isNullOrBlank() &&
                            book.readDate >= trackingStartDate &&
                            book.readDate <= trackingDate
                        val historyCandidate = historyImported &&
                            !changed &&
                            !book.readDate.isNullOrBlank() &&
                            book.readDate >= periodStart &&
                            book.readDate <= trackingDate
                        if (historyCandidate) {
                            val readDate = requireNotNull(book.readDate)
                            val alreadyRecorded = db.query(
                                TABLE_DAILY_BOOKS,
                                arrayOf("confidence"),
                                "date = ? AND source = ? AND book_key = ?",
                                arrayOf(readDate, "WEREAD", book.bookKey),
                                null,
                                null,
                                null
                            ).use { c -> c.moveToFirst() }
                            if (!alreadyRecorded) {
                                db.insertWithOnConflict(
                                    TABLE_DAILY_BOOKS,
                                    null,
                                    ContentValues().apply {
                                        put("date", readDate)
                                        put("source", "WEREAD")
                                        put("book_key", book.bookKey)
                                        put("title", book.title)
                                        put("author", book.author)
                                        put("cover_cache_path", book.coverCachePath)
                                        put("duration_ms", 0L)
                                        put("progress", book.progress)
                                        put("status", book.status)
                                        put("confidence", "SHELF_HISTORY")
                                        put("last_seen_at", book.lastReadAt)
                                        put("updated_at", now)
                                    },
                                    SQLiteDatabase.CONFLICT_IGNORE
                                )
                                historyWritten += 1
                                dailyWritten += 1
                            }
                        }
                        if (changed) {
                            val readDate = requireNotNull(book.readDate)
                            changedBooks += 1
                            val currentDuration = book.monthlyDurationMs
                            val previousDuration = priorPeriodDurations[book.bookKey]
                            val monthlyDelta = if (
                                currentDuration != null &&
                                previousDuration != null &&
                                currentDuration >= previousDuration
                            ) {
                                currentDuration - previousDuration
                            } else {
                                0L
                            }
                            val currentRecordReading = book.recordReadingMs
                            val previousRecordReading = priorRecordReading[book.bookKey]
                            val progressDelta = if (
                                currentRecordReading != null &&
                                previousRecordReading != null &&
                                currentRecordReading >= previousRecordReading
                            ) {
                                currentRecordReading - previousRecordReading
                            } else {
                                0L
                            }
                            val delta = monthlyDelta.takeIf { it > 0L } ?: progressDelta
                            val existingDuration = db.query(
                                TABLE_DAILY_BOOKS,
                                arrayOf("duration_ms"),
                                "date = ? AND source = ? AND book_key = ?",
                                arrayOf(readDate, "WEREAD", book.bookKey),
                                null,
                                null,
                                null
                            ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
                            db.insertWithOnConflict(
                                TABLE_DAILY_BOOKS,
                                null,
                                ContentValues().apply {
                                    put("date", readDate)
                                    put("source", "WEREAD")
                                    put("book_key", book.bookKey)
                                    put("title", book.title)
                                    put("author", book.author)
                                    put("cover_cache_path", book.coverCachePath)
                                    put("duration_ms", existingDuration + delta)
                                    put("progress", book.progress)
                                    put("status", book.status)
                                    put("confidence", if (delta > 0L) "SNAPSHOT_DELTA" else "SHELF_EVENT")
                                    put("last_seen_at", book.lastReadAt)
                                    put("updated_at", now)
                                },
                                SQLiteDatabase.CONFLICT_REPLACE
                            )
                            dailyWritten += 1
                        }

                        db.insertWithOnConflict(
                            TABLE_WEREAD_BOOK_STATE,
                            null,
                            ContentValues().apply {
                                put("book_key", book.bookKey)
                                put("title", book.title)
                                put("author", book.author)
                                put("cover_cache_path", book.coverCachePath)
                                put("last_read_at", book.lastReadAt)
                                put(
                                    "record_reading_ms",
                                    book.recordReadingMs ?: priorRecordReading[book.bookKey] ?: 0L
                                )
                                put("progress", book.progress)
                                put("status", book.status)
                                put("updated_at", now)
                            },
                            SQLiteDatabase.CONFLICT_REPLACE
                        )
                        book.monthlyDurationMs?.let { duration ->
                            db.insertWithOnConflict(
                                TABLE_WEREAD_PERIOD_STATE,
                                null,
                                ContentValues().apply {
                                    put("period_start", periodStart)
                                    put("period_end", periodEnd)
                                    put("book_key", book.bookKey)
                                    put("duration_ms", duration)
                                    put("updated_at", now)
                                },
                                SQLiteDatabase.CONFLICT_REPLACE
                            )
                        }
                    }
                    if (historyImported) {
                        db.insertWithOnConflict(
                            TABLE_SYNC_META,
                            null,
                            ContentValues().apply {
                                put("key", KEY_WEREAD_SHELF_HISTORY_IMPORTED)
                                put("value", "1")
                            },
                            SQLiteDatabase.CONFLICT_REPLACE
                        )
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
            WeReadSnapshotApplyResult(
                baseline = baseline,
                historyImported = historyImported,
                historyRecordsWritten = historyWritten,
                changedBooks = changedBooks,
                dailyRecordsWritten = dailyWritten,
                trackingStartDate = trackingStartDate
            )
        }.getOrElse {
            AutoRefreshLog.e(context, "ReadingDataStore WeRead snapshot apply failed", it)
            null
        }
    }

    fun queryDailyBooks(
        context: Context,
        source: String,
        startDate: String,
        endDate: String
    ): List<DailyBookRecord> {
        return runCatching {
            val out = mutableListOf<DailyBookRecord>()
            Helper(context.applicationContext).readableDatabase.use { db ->
                db.query(
                    TABLE_DAILY_BOOKS,
                    arrayOf(
                        "date", "source", "book_key", "title", "author", "cover_cache_path",
                        "duration_ms", "progress", "status", "confidence", "last_seen_at"
                    ),
                    "source = ? AND date >= ? AND date <= ?",
                    arrayOf(source, startDate, endDate),
                    null,
                    null,
                    "date ASC, duration_ms DESC"
                ).use { c ->
                    while (c.moveToNext()) {
                        out.add(
                            DailyBookRecord(
                                date = c.getString(0),
                                source = c.getString(1),
                                bookKey = c.getString(2),
                                title = c.getString(3),
                                author = if (c.isNull(4)) null else c.getString(4),
                                coverCachePath = if (c.isNull(5)) null else c.getString(5),
                                durationMs = c.getLong(6),
                                progress = if (c.isNull(7)) null else c.getString(7),
                                status = c.getInt(8),
                                confidence = c.getString(9),
                                lastSeenAt = c.getLong(10)
                            )
                        )
                    }
                }
            }
            AutoRefreshLog.i(context, "ReadingDataStore query source=$source range=$startDate~$endDate records=${out.size}")
            out
        }.getOrElse {
            AutoRefreshLog.e(context, "ReadingDataStore query failed source=$source range=$startDate~$endDate", it)
            emptyList()
        }
    }

    private fun DailyBookRecord.toContentValues(updatedAt: Long): ContentValues {
        return ContentValues().apply {
            put("date", date)
            put("source", source)
            put("book_key", bookKey)
            put("title", title)
            put("author", author)
            put("cover_cache_path", coverCachePath)
            put("duration_ms", durationMs)
            put("progress", progress)
            put("status", status)
            put("confidence", confidence)
            put("last_seen_at", lastSeenAt)
            put("updated_at", updatedAt)
        }
    }

    private fun countRows(
        context: Context,
        table: String,
        selection: String,
        args: Array<String>
    ): Int {
        return runCatching {
            Helper(context.applicationContext).readableDatabase.use { db ->
                db.query(table, arrayOf("COUNT(*)"), selection, args, null, null, null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
            }
        }.getOrDefault(0)
    }

    private const val KEY_WEREAD_SHELF_HISTORY_IMPORTED = "weread_shelf_history_imported_v1"
}
