package com.dmer.neoreaderrecords

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfRenderer
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random
import java.util.TimeZone

object AutoWallpaperGenerator {
    private val metadataUri = Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata")
    private val statsUri = Uri.parse("content://com.onyx.kreader.statistics.provider/OnyxStatisticsModel")
    private const val DAY_MS = 86_400_000L
    private const val READING_STORE_SYNC_MIN_INTERVAL_MS = 60_000L
    private const val READING_STORE_SYNC_LOOKBACK_DAYS = 3
    private const val KEY_READING_STORE_LAST_SYNC_ATTEMPT_MS = "reading_store_last_sync_attempt_ms"
    private const val KEY_READING_STORE_LAST_SYNC_SUCCESS_MS = "reading_store_last_sync_success_ms"
    private val readingStoreSyncLock = Any()

    private data class BookItem(
        val bookId: String?,
        val title: String,
        val author: String?,
        val progress: String?,
        val status: Int,
        val progressText: String? = null,
        val durationText: String? = null,
        val latestExcerptText: String? = null,
        val menuPriceText: String? = null,
        val localKeys: List<String> = emptyList()
    )
    private data class MetadataBook(
        val path: String,
        val lastAccessMs: Long,
        val item: BookItem
    )
    private data class CalendarCoverItem(
        val title: String,
        val author: String?,
        val path: String,
        val status: Int,
        val durationMs: Long,
        val lastSeenAt: Long,
        val bitmap: Bitmap?
    )
    private data class CalendarDayCell(
        val dayStartMs: Long,
        val dayOfMonth: Int,
        val inMonth: Boolean,
        val totalMs: Long,
        val eventCount: Int,
        val unmatchedCount: Int,
        val books: List<CalendarCoverItem>,
        val sourceKind: String = "NEO"
    )
    private data class CalendarBuildData(
        val monthStartMs: Long,
        val monthEndMs: Long,
        val weekRows: Int,
        val cells: List<CalendarDayCell>,
        val statsRows: Int,
        val matchedRows: Int,
        val unmatchedRows: Int,
        val footerLabel: String = "Neo 本地月历 · 近似匹配",
        val showDurationOnlyLabel: Boolean = true,
        val showFooterLabel: Boolean = true,
        val showDaySourceLabel: Boolean = false
    )
    private data class CalendarMonthFrame(
        val monthStart: Long,
        val monthEnd: Long,
        val weekRows: Int,
        val gridStart: Long
    )
    private data class CalendarFeaturedBook(
        val item: CalendarCoverItem,
        val durationMs: Long,
        val lastSeenAt: Long,
        val excerpt: String?
    )
    private data class ChartStats(val totalMs: Long, val points: LongArray, val labels: List<String>)
    private data class WallpaperSize(val label: String, val width: Int, val height: Int)
    private data class WeReadBuildData(
        val rangeStart: Long,
        val rangeEnd: Long,
        val chart: ChartStats,
        val books: List<BookItem>,
        val label: String,
        val note: String
    )
    private enum class BucketMode { HOUR, DAY, WEEK, MONTH }

    private data class AutoSettings(
        val includeUnread: Boolean,
        val showChart: Boolean,
        val showProgressStatus: Boolean,
        val showAuthor: Boolean,
        val showBookDuration: Boolean,
        val minDurationMinutes: Int,
        val topN: Int,
        val periodMode: String,
        val weekStart: String,
        val weekEnd: String,
        val readingFilterMode: String,
        val sourceMode: String,
        val wallpaperMode: String,
        val statsTemplate: String,
        val calendarTemplate: String,
        val calendarFeatureBookRule: String,
        val calendarStackOrder: String,
        val coverFitMode: String,
        val progressMode: String,
        val timeUnit: String,
        val receiptTitle: String,
        val receiptTitleSize: Float,
        val receiptBodySize: Float,
        val serialNumberMode: String,
        val serialNumberCustom: String,
        val serialNumberSize: Float,
        val booxDevicePreset: String,
        val customWallpaperWidth: Int,
        val customWallpaperHeight: Int,
        val footerMode: String,
        val barcodeWidthScale: Float,
        val barcodeGapMode: String,
        val noteText: String,
        val bookNoteMode: String,
        val footerNoteSource: String,
        val footerShowBookTitle: Boolean,
        val excerptSourceMode: String,
        val chartStyleMode: String,
        val showPeakLabel: Boolean,
        val yAxisMode: String,
        val yAxisFixedMaxMinutes: Int,
        val titleFont: String,
        val bodyFont: String
    )

    data class PreviewResult(val bitmap: Bitmap, val summary: String)
    private data class FileCoverProbe(val bitmap: Bitmap?, val reason: String)
    private data class CoverProbeTrace(
        var rowsScanned: Int = 0,
        var columnCandidateCount: Int = 0,
        var columnDecodeAttempts: Int = 0,
        var md5CacheAttempts: Int = 0,
        var structuredSpecAttempts: Int = 0,
        var internalCacheAttempts: Int = 0,
        var fileFallbackAttempts: Int = 0,
        var fallbackMissReasons: MutableMap<String, Int> = linkedMapOf()
    )

    fun generateAndSave(context: Context, reason: String): Boolean {
        AutoRefreshLog.i(context, "Generator start reason=$reason")
        return runCatching {
            val built = buildPreviewInternal(context, "A", true) ?: return false
            val path = saveBitmap(context, built.bitmap, reason)
            AutoRefreshLog.i(context, "Generator saved path=$path ${built.summary}")
            true
        }.getOrElse {
            AutoRefreshLog.e(context, "Generator exception", it)
            false
        }
    }

    fun generateAndSaveWeRead(context: Context, reason: String): Boolean {
        AutoRefreshLog.i(context, "WeRead auto generator start reason=$reason")
        return runCatching {
            val s = readSettings(context)
            if (s.wallpaperMode != "STATS" && s.wallpaperMode != "CALENDAR") {
                WeReadReadingSync.syncCurrentMonth(context, "weread_auto_$reason")
            }
            val built = buildWeReadPreviewForWallpaperMode(context, s.wallpaperMode) ?: return false
            if (built.summary.contains("source=fallback_cache")) {
                AutoRefreshLog.i(context, "WeRead auto skip saving fallback cache and request retry ${built.summary}")
                return false
            }
            val path = saveBitmap(context, built.bitmap, reason)
            AutoRefreshLog.i(context, "WeRead auto saved path=$path ${built.summary}")
            true
        }.getOrElse {
            AutoRefreshLog.e(context, "WeRead auto generator exception", it)
            false
        }
    }

    fun generateAndSaveMixed(context: Context, reason: String): Boolean {
        AutoRefreshLog.i(context, "Mixed auto generator start reason=$reason")
        return runCatching {
            val settings = readSettings(context)
            if (settings.wallpaperMode != "STATS" && settings.wallpaperMode != "CALENDAR") {
                WeReadReadingSync.syncCurrentMonth(context, "mixed_auto_$reason")
            }
            val built = buildMixedPreviewFromPrefs(context, "A") ?: return false
            if ((reason.startsWith("screen_on_prewarm") || reason.startsWith("user_present_prewarm")) &&
                built.summary.contains("source=fallback_cache")
            ) {
                AutoRefreshLog.i(context, "Mixed auto skip saving fallback cache and request retry ${built.summary}")
                return false
            }
            val path = saveBitmap(context, built.bitmap, reason)
            AutoRefreshLog.i(context, "Mixed auto saved path=$path ${built.summary}")
            true
        }.getOrElse {
            AutoRefreshLog.e(context, "Mixed auto generator exception", it)
            false
        }
    }

    fun buildPreviewFromPrefs(context: Context, sourceMark: String = "M"): PreviewResult? {
        return runCatching { buildPreviewInternal(context, sourceMark, false) }.getOrNull()
    }

    fun bootstrapReadingStoreIfNeeded(context: Context): Boolean {
        if (!AutoRefreshConfig.isReadingDataStoreEnabled(context)) {
            AutoRefreshLog.i(context, "ReadingDataStore bootstrap skip disabled")
            return true
        }
        return runCatching {
            val bootstrapSettings = readSettings(context).copy(
                sourceMode = "DURATION",
                periodMode = "THIS_MONTH"
            )
            val now = System.currentTimeMillis()
            val monthBases = linkedSetOf(now)
            val recentStart = Calendar.getInstance(TimeZone.getDefault()).apply {
                timeInMillis = now
                add(Calendar.DAY_OF_MONTH, -29)
            }.timeInMillis
            if (!isSameMonth(now, recentStart)) {
                monthBases.add(recentStart)
            }

            var months = 0
            monthBases.forEach { baseMs ->
                val frame = calendarMonthFrame(baseMs)
                val data = buildLiveNeoCalendarData(
                    context = context,
                    s = bootstrapSettings,
                    monthStart = frame.monthStart,
                    monthEnd = frame.monthEnd,
                    weekRows = frame.weekRows,
                    gridStart = frame.gridStart
                )
                if (data != null) months += 1
            }
            AutoRefreshLog.i(
                context,
                "ReadingDataStore bootstrap done months=$months totalDb=${ReadingDataStore.countDailyBooks(context)}"
            )
            true
        }.getOrElse {
            AutoRefreshLog.e(context, "ReadingDataStore bootstrap failed", it)
            false
        }
    }

    fun syncRecentNeoReadingStore(context: Context, reason: String): Boolean {
        if (!AutoRefreshConfig.isReadingDataStoreEnabled(context)) {
            AutoRefreshLog.i(context, "ReadingDataStore incremental skip disabled reason=$reason")
            return true
        }
        return synchronized(readingStoreSyncLock) {
            val prefs = context.getSharedPreferences(AutoRefreshConfig.PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastAttempt = prefs.getLong(KEY_READING_STORE_LAST_SYNC_ATTEMPT_MS, 0L)
            val delta = now - lastAttempt
            if (delta in 0 until READING_STORE_SYNC_MIN_INTERVAL_MS) {
                AutoRefreshLog.i(
                    context,
                    "ReadingDataStore incremental skip reason=$reason delta=${delta}ms"
                )
                return@synchronized true
            }
            prefs.edit().putLong(KEY_READING_STORE_LAST_SYNC_ATTEMPT_MS, now).apply()

            runCatching {
                val end = Calendar.getInstance(TimeZone.getDefault()).apply {
                    timeInMillis = now
                    set(Calendar.HOUR_OF_DAY, 23)
                    set(Calendar.MINUTE, 59)
                    set(Calendar.SECOND, 59)
                    set(Calendar.MILLISECOND, 999)
                }.timeInMillis
                val start = Calendar.getInstance(TimeZone.getDefault()).apply {
                    timeInMillis = now
                    add(Calendar.DAY_OF_MONTH, -(READING_STORE_SYNC_LOOKBACK_DAYS - 1))
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val settings = readSettings(context).copy(
                    sourceMode = "DURATION",
                    periodMode = "CUSTOM",
                    weekStart = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(start)),
                    weekEnd = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(end))
                )
                val data = buildLiveNeoCalendarData(
                    context = context,
                    s = settings,
                    monthStart = start,
                    monthEnd = end,
                    weekRows = 1,
                    gridStart = start
                )
                prefs.edit().putLong(KEY_READING_STORE_LAST_SYNC_SUCCESS_MS, now).apply()
                AutoRefreshLog.i(
                    context,
                    "ReadingDataStore incremental done reason=$reason range=${fmt(start)}~${fmt(end)} rows=${data?.statsRows ?: 0} totalDb=${ReadingDataStore.countDailyBooks(context)}"
                )
                true
            }.getOrElse {
                AutoRefreshLog.e(context, "ReadingDataStore incremental failed reason=$reason", it)
                false
            }
        }
    }

    fun buildWeReadStatsPreviewFromPrefs(context: Context, sourceMark: String = "W"): PreviewResult? {
        return runCatching {
            val s = readSettings(context)
            val data = buildWeReadStatsForSettings(context, s)
            if (data == null) {
                AutoRefreshLog.i(context, "WeRead wallpaper preview failed: no range data")
                return@runCatching null
            }
            val bmp = draw(context, data.rangeStart, data.rangeEnd, data.chart, data.books, s, sourceMark)
            val summary = buildString {
                append("微信读书 ")
                append(data.label)
                append(", 书籍=").append(data.books.size)
                append(", 时长=").append(formatDuration(data.chart.totalMs, s.timeUnit))
                append(", 输出=").append(canvasSizeText(s))
                if (data.note.isNotBlank()) append(", ").append(data.note)
            }
            PreviewResult(bmp, summary)
        }.getOrNull()
    }

    fun buildWeReadCoverPreviewFromPrefs(context: Context, sourceMark: String = "W"): PreviewResult? {
        return runCatching {
            val s = readSettings(context)
            val fetched = WeReadClient.cacheLatestCover(context, WeReadClient.loadApiKey(context))
            var usedFallbackCache = false
            val cover = if (fetched.ok) {
                fetched
            } else {
                val cached = WeReadClient.cachedLatestCover(context)
                if (cached != null) {
                    usedFallbackCache = true
                    AutoRefreshLog.i(context, "WeRead cover wallpaper fallback cached detail=${fetched.detail.take(120)}")
                    cached
                } else {
                    AutoRefreshLog.i(context, "WeRead cover wallpaper failed ${fetched.detail}")
                    return@runCatching null
                }
            }
            val bmp = BitmapFactory.decodeFile(cover.cachePath)
            if (bmp == null) {
                AutoRefreshLog.i(context, "WeRead cover wallpaper decode failed path=${cover.cachePath}")
                return@runCatching null
            }
            val rendered = renderCoverWallpaper(context, bmp, cover.title, sourceMark, s)
            val summary = buildString {
                append("微信读书封面 title=").append(cover.title)
                append(", author=").append(cover.author)
                append(", source=").append(
                    when {
                        usedFallbackCache -> "fallback_cache"
                        cover.fromCache -> "cache"
                        else -> "network"
                    }
                )
                append(", fit=").append(s.coverFitMode)
                append(", 输出=").append(canvasSizeText(s))
            }
            PreviewResult(rendered, summary)
        }.getOrNull()
    }

    fun buildLocalCalendarPreviewFromPrefs(context: Context, sourceMark: String = "M"): PreviewResult? {
        return runCatching {
            val s = readSettings(context)
            buildLocalCalendarPreviewForSettings(context, s, sourceMark)
        }.getOrNull()
    }

    fun buildWeReadCalendarPreviewFromPrefs(context: Context, sourceMark: String = "W"): PreviewResult? {
        return runCatching {
            val syncOk = WeReadReadingSync.syncCurrentMonth(context, "calendar_preview")
            val s = readSettings(context)
            buildWeReadCalendarPreviewForSettings(context, s, sourceMark, syncOk)
        }.getOrNull()
    }

    fun buildMixedPreviewFromPrefs(context: Context, sourceMark: String = "A"): PreviewResult? {
        return runCatching {
            val s = readSettings(context)
            if (s.wallpaperMode == "CALENDAR") {
                return@runCatching buildMixedCalendarPreviewForSettings(context, s, sourceMark)
            }
            val tryCover = s.wallpaperMode == "COVER" || s.wallpaperMode == "AUTO_COVER"
            if (tryCover) {
                buildMixedCoverPreview(context, s, sourceMark)?.let {
                    return@runCatching it
                }
                if (s.wallpaperMode == "COVER") return@runCatching null
            }

            val data = buildMixedStatsForSettings(context, s) ?: return@runCatching null
            val bmp = draw(context, data.rangeStart, data.rangeEnd, data.chart, data.books, s, sourceMark)
            PreviewResult(
                bmp,
                "混合统计 范围=${fmt(data.rangeStart)}~${fmt(data.rangeEnd)}, 书籍=${data.books.size}, 时长=${formatDuration(data.chart.totalMs, s.timeUnit)}, ${data.note}, 输出=${canvasSizeText(s)}"
            )
        }.getOrNull()
    }

    private fun buildMixedCalendarPreviewForSettings(
        context: Context,
        s: AutoSettings,
        sourceMark: String
    ): PreviewResult? {
        val range = resolvePeriodRange(s)
        val frame = calendarFrameForSettings(s, range)
        val syncOk = WeReadReadingSync.syncCurrentMonth(context, "mixed_calendar_preview")
        val localData = buildLocalCalendarData(context, s)
        val weReadData = buildStoredWeReadCalendarData(
            context = context,
            s = s,
            monthStart = frame.monthStart,
            monthEnd = frame.monthEnd,
            weekRows = frame.weekRows,
            gridStart = frame.gridStart
        )
        val data = mergeCalendarData(localData, weReadData, s) ?: return null
        val bmp = drawCalendarWallpaper(context, data, s, sourceMark)
        val localDays = data.cells.count {
            it.inMonth && it.sourceKind == "NEO" && (it.totalMs > 0L || it.books.isNotEmpty())
        }
        val weReadDays = data.cells.count {
            it.inMonth && it.sourceKind == "WEREAD" && (it.totalMs > 0L || it.books.isNotEmpty())
        }
        val mixedDays = data.cells.count {
            it.inMonth && it.sourceKind == "MIXED" && (it.totalMs > 0L || it.books.isNotEmpty())
        }
        val source = when {
            syncOk -> "network+db"
            !AutoRefreshConfig.isReadingDataStoreEnabled(context) && weReadData != null ->
                "cache_store_disabled"
            weReadData != null -> "fallback_cache"
            else -> "local_only"
        }
        return PreviewResult(
            bmp,
            "混合月历 month=${calendarTitleLabel(data, s)} template=${s.calendarTemplate} source=$source stackOrder=${s.calendarStackOrder} localDays=$localDays weReadDays=$weReadDays mixedDays=$mixedDays records=${data.matchedRows} 输出=${canvasSizeText(s)}"
        )
    }

    private fun mergeCalendarData(
        local: CalendarBuildData?,
        weRead: CalendarBuildData?,
        s: AutoSettings
    ): CalendarBuildData? {
        if (local == null) {
            return weRead?.copy(
                footerLabel = "",
                showDurationOnlyLabel = false,
                showFooterLabel = false,
                showDaySourceLabel = true,
                cells = weRead.cells.map { cell ->
                    cell.copy(sourceKind = if (cell.totalMs > 0L || cell.books.isNotEmpty()) "WEREAD" else "NEO")
                }
            )
        }
        if (weRead == null) {
            return local.copy(
                footerLabel = "",
                showFooterLabel = false,
                showDaySourceLabel = true
            )
        }
        val localByDay = local.cells.associateBy { it.dayStartMs }
        val weReadByDay = weRead.cells.associateBy { it.dayStartMs }
        val cells = (localByDay.keys + weReadByDay.keys).sorted().map { day ->
            val neoCell = localByDay[day]
            val weReadCell = weReadByDay[day]
            val neoActive = neoCell != null && (neoCell.totalMs > 0L || neoCell.books.isNotEmpty())
            val weReadActive = weReadCell != null && (weReadCell.totalMs > 0L || weReadCell.books.isNotEmpty())
            CalendarDayCell(
                dayStartMs = day,
                dayOfMonth = neoCell?.dayOfMonth ?: weReadCell?.dayOfMonth ?: 0,
                inMonth = neoCell?.inMonth ?: weReadCell?.inMonth ?: false,
                totalMs = (neoCell?.totalMs ?: 0L) + (weReadCell?.totalMs ?: 0L),
                eventCount = (neoCell?.eventCount ?: 0) + (weReadCell?.eventCount ?: 0),
                unmatchedCount = (neoCell?.unmatchedCount ?: 0) + (weReadCell?.unmatchedCount ?: 0),
                books = mergeCalendarBooks(
                    neoCell?.books.orEmpty() + weReadCell?.books.orEmpty(),
                    s.calendarStackOrder
                ),
                sourceKind = when {
                    neoActive && weReadActive -> "MIXED"
                    weReadActive -> "WEREAD"
                    else -> "NEO"
                }
            )
        }
        return CalendarBuildData(
            monthStartMs = local.monthStartMs,
            monthEndMs = local.monthEndMs,
            weekRows = local.weekRows,
            cells = cells,
            statsRows = local.statsRows + weRead.statsRows,
            matchedRows = local.matchedRows + weRead.matchedRows,
            unmatchedRows = local.unmatchedRows + weRead.unmatchedRows,
            footerLabel = "",
            showDurationOnlyLabel = false,
            showFooterLabel = false,
            showDaySourceLabel = true
        )
    }

    private fun mergeCalendarBooks(
        books: List<CalendarCoverItem>,
        stackOrder: String
    ): List<CalendarCoverItem> {
        val merged = books
            .groupBy { calendarBookIdentity(it.title) }
            .map { (_, sameBook) ->
                val preferred = sameBook.maxWithOrNull(
                    compareBy<CalendarCoverItem> { if (it.bitmap != null) 1 else 0 }
                        .thenBy { it.lastSeenAt }
                ) ?: sameBook.first()
                preferred.copy(
                    durationMs = sameBook.sumOf { it.durationMs },
                    lastSeenAt = sameBook.maxOf { it.lastSeenAt },
                    status = sameBook.maxOf { it.status }
                )
            }
        val selected = when (stackOrder) {
            "SHORTEST_TOP" -> merged.sortedBy { it.durationMs }
            "LATEST_TOP" -> merged.sortedByDescending { it.lastSeenAt }
            else -> merged.sortedByDescending { it.durationMs }
        }.take(4)
        return orderCalendarStack(selected, stackOrder)
    }

    private fun calendarBookIdentity(title: String): String {
        return title.lowercase(Locale.ROOT)
            .replace(Regex("[\\s·•:：,，.。()（）《》【】\\[\\]_-]+"), "")
    }

    private fun buildWeReadPreviewForWallpaperMode(context: Context, wallpaperMode: String): PreviewResult? {
        return when (wallpaperMode) {
            "COVER" -> buildWeReadCoverPreviewFromPrefs(context, "W")
            "AUTO_COVER" -> buildWeReadCoverPreviewFromPrefs(context, "W")
                ?: buildWeReadStatsPreviewFromPrefs(context, "W")
            "CALENDAR" -> buildWeReadCalendarPreviewFromPrefs(context, "W")
            else -> buildWeReadStatsPreviewFromPrefs(context, "W")
        }
    }

    private fun buildMixedCoverPreview(context: Context, s: AutoSettings, sourceMark: String): PreviewResult? {
        val localLatestMs = latestLocalCoverAccessMs(context)
        val fetchedWeRead = WeReadClient.cacheLatestCover(context, WeReadClient.loadApiKey(context))
        val weReadLatestMs = if (fetchedWeRead.ok) fetchedWeRead.readUpdateTimeMs else 0L
        val weReadFirst = weReadLatestMs >= localLatestMs
        AutoRefreshLog.i(
            context,
            "Mixed cover choose weReadFirst=$weReadFirst localLatestMs=$localLatestMs weReadLatestMs=$weReadLatestMs weReadOk=${fetchedWeRead.ok}"
        )

        fun renderWeReadFromFetched(): PreviewResult? {
            if (!fetchedWeRead.ok) return null
            val bmp = BitmapFactory.decodeFile(fetchedWeRead.cachePath) ?: return null
            val rendered = renderCoverWallpaper(context, bmp, fetchedWeRead.title, sourceMark, s)
            val source = if (fetchedWeRead.fromCache) "cache" else "network"
            return PreviewResult(
                rendered,
                "混合封面：微信最新 title=${fetchedWeRead.title}, author=${fetchedWeRead.author}, source=$source, readUpdateTimeMs=$weReadLatestMs, localLatestMs=$localLatestMs, 输出=${canvasSizeText(s)}"
            )
        }

        fun renderLocal(): PreviewResult? {
            return tryBuildCoverWallpaper(context, s.copy(sourceMode = "DURATION"), sourceMark)
                ?.let {
                    PreviewResult(
                        it.bitmap,
                        "混合封面：本地最新 ${it.summary}, localLatestMs=$localLatestMs, weReadLatestMs=$weReadLatestMs"
                    )
                }
        }

        return if (weReadFirst) {
            renderWeReadFromFetched() ?: renderLocal()
        } else {
            renderLocal() ?: renderWeReadFromFetched()
        }
    }

    private fun latestLocalCoverAccessMs(context: Context): Long {
        return runCatching {
            context.contentResolver.query(
                metadataUri,
                arrayOf("lastAccess"),
                null,
                null,
                "lastAccess DESC"
            )?.use { c ->
                if (c.moveToFirst()) normalizeEpochMs(readColString(c, "lastAccess")?.toLongOrNull() ?: 0L) else 0L
            } ?: 0L
        }.getOrDefault(0L)
    }

    private fun normalizeEpochMs(value: Long): Long {
        return when {
            value <= 0L -> 0L
            value < 10_000_000_000L -> value * 1000L
            else -> value
        }
    }

    private fun buildPreviewInternal(context: Context, sourceMark: String, fromAutoWorker: Boolean): PreviewResult? {
        val s = readSettings(context)
        if (s.wallpaperMode == "CALENDAR") {
            return buildLocalCalendarPreviewForSettings(context, s, "M")
        }
        val tryCover = s.wallpaperMode == "COVER" || (s.wallpaperMode == "AUTO_COVER" && fromAutoWorker)
        if (tryCover) {
            tryBuildCoverWallpaper(context, s, sourceMark)?.let { return it }
            AutoRefreshLog.i(context, "cover wallpaper unavailable, fallback stats mode")
            if (s.wallpaperMode == "COVER") return null
        }
        val range = resolvePeriodRange(s) ?: return null
        val books = if (s.sourceMode == "DURATION") {
            enrichExcerptMenuBooks(
                context,
                "",
                queryTopBooksByDuration(context, range.first, range.second, s)
                .take(s.topN)
                    .map { it.first.copy(menuPriceText = menuPriceText(it.second)) to it.second },
                s,
                fetchWeReadExcerpt = false
            )
        } else {
            enrichExcerptMenuBooks(
                context,
                "",
                queryTopBooks(context.contentResolver, range.first, range.second, s.topN, s.includeUnread, s.readingFilterMode)
                    .mapIndexed { idx, item -> item to ((s.topN - idx).coerceAtLeast(1) * 60_000L).toLong() },
                s,
                fetchWeReadExcerpt = false
            )
        }
        val stats = queryStatsByMode(context.contentResolver, range.first, range.second, s)
        AutoRefreshLog.i(
            context,
            "Local stats books source=${s.sourceMode} books=${books.size} withDuration=${books.count { !it.durationText.isNullOrBlank() }}"
        )
        val bmp = draw(context, range.first, range.second, stats, books, s, sourceMark)
        val summary = buildString {
            append("范围=").append(fmt(range.first)).append("~").append(fmt(range.second))
            append(", 周期=").append(s.periodMode)
            append(", 口径=").append(s.sourceMode)
            append(", TopN=").append(s.topN)
            append(", 书籍=").append(books.size)
            append(", 时长=").append(formatDuration(stats.totalMs, s.timeUnit))
            append(", 输出=").append(canvasSizeText(s))
        }
        return PreviewResult(bmp, summary)
    }

    private fun buildLocalCalendarPreviewForSettings(context: Context, s: AutoSettings, sourceMark: String): PreviewResult? {
        val data = buildLocalCalendarData(context, s) ?: return null
        val bmp = drawCalendarWallpaper(context, data, s, sourceMark)
        val monthLabel = calendarTitleLabel(data, s)
        val coveredDays = data.cells.count { it.inMonth && it.books.isNotEmpty() }
        return PreviewResult(
            bmp,
            "月历封面墙 month=$monthLabel template=${s.calendarTemplate} stackOrder=${s.calendarStackOrder} daysWithCover=$coveredDays rows=${data.statsRows} matched=${data.matchedRows} unmatched=${data.unmatchedRows} 输出=${canvasSizeText(s)}"
        )
    }

    private fun buildWeReadCalendarPreviewForSettings(
        context: Context,
        s: AutoSettings,
        sourceMark: String,
        syncOk: Boolean
    ): PreviewResult? {
        val range = resolvePeriodRange(s)
        val frame = calendarFrameForSettings(s, range)
        val data = buildStoredWeReadCalendarData(
            context,
            s,
            frame.monthStart,
            frame.monthEnd,
            frame.weekRows,
            frame.gridStart
        ) ?: return null
        val bmp = drawCalendarWallpaper(context, data, s, sourceMark)
        val activeDays = data.cells.count { it.inMonth && it.totalMs > 0L }
        val coveredDays = data.cells.count { it.inMonth && it.books.isNotEmpty() }
        val source = when {
            syncOk -> "network+db"
            !AutoRefreshConfig.isReadingDataStoreEnabled(context) -> "cache_store_disabled"
            else -> "fallback_cache"
        }
        return PreviewResult(
            bmp,
            "微信读书月历 month=${calendarTitleLabel(data, s)} template=${s.calendarTemplate} source=$source stackOrder=${s.calendarStackOrder} activeDays=$activeDays daysWithCover=$coveredDays records=${data.matchedRows} 输出=${canvasSizeText(s)}"
        )
    }

    private fun buildStoredWeReadCalendarData(
        context: Context,
        s: AutoSettings,
        monthStart: Long,
        monthEnd: Long,
        weekRows: Int,
        gridStart: Long
    ): CalendarBuildData? {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val startDate = dateFmt.format(Date(monthStart))
        val endDate = dateFmt.format(Date(monthEnd))
        val totals = ReadingDataStore.queryDailyTotals(context, "WEREAD", startDate, endDate)
        val records = ReadingDataStore.queryDailyBooks(context, "WEREAD", startDate, endDate)
            .filter { record ->
                (s.includeUnread || record.status != 0) &&
                    (s.readingFilterMode != "READING_ONLY" || record.status == 1) &&
                    (s.readingFilterMode != "FINISHED_ONLY" || record.status == 2)
            }
        if (totals.isEmpty() && records.isEmpty()) {
            AutoRefreshLog.i(context, "WeRead calendar database empty range=$startDate~$endDate")
            return null
        }
        val totalsByDate = totals.associateBy { it.date }
        val recordsByDate = records.groupBy { it.date }
        val cells = (0 until weekRows * 7).map { index ->
            val dayMs = gridStart + index * DAY_MS
            val dc = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = dayMs }
            val dayKey = dateFmt.format(Date(dayMs))
            val dayRecords = recordsByDate[dayKey].orEmpty()
            val selectedRecords = when (s.calendarStackOrder) {
                "SHORTEST_TOP" -> dayRecords.sortedBy { it.durationMs }
                "LATEST_TOP" -> dayRecords.sortedByDescending { it.lastSeenAt }
                else -> dayRecords.sortedByDescending { it.durationMs }
            }.take(4)
            val books = orderCalendarStack(
                selectedRecords.map { record ->
                    CalendarCoverItem(
                        title = record.title,
                        author = record.author,
                        path = record.bookKey,
                        status = record.status,
                        durationMs = record.durationMs,
                        lastSeenAt = record.lastSeenAt,
                        bitmap = record.coverCachePath
                            ?.takeIf { it.isNotBlank() }
                            ?.let { BitmapFactory.decodeFile(it) }
                    )
                },
                s.calendarStackOrder
            )
            val totalMs = totalsByDate[dayKey]?.durationMs ?: dayRecords.sumOf { it.durationMs }
            CalendarDayCell(
                dayStartMs = dayMs,
                dayOfMonth = dc.get(Calendar.DAY_OF_MONTH),
                inMonth = dayMs in monthStart..monthEnd,
                totalMs = totalMs,
                eventCount = if (totalMs > 0L) 1 else 0,
                unmatchedCount = if (totalMs > 0L && books.isEmpty()) 1 else 0,
                books = books
            )
        }
        AutoRefreshLog.i(
            context,
            "WeRead calendar data source=db range=$startDate~$endDate totals=${totals.size} dailyBooks=${records.size} activeDays=${cells.count { it.inMonth && it.totalMs > 0L }} daysWithBooks=${cells.count { it.inMonth && it.books.isNotEmpty() }}"
        )
        return CalendarBuildData(
            monthStartMs = monthStart,
            monthEndMs = monthEnd,
            weekRows = weekRows,
            cells = cells,
            statsRows = totals.size,
            matchedRows = records.size,
            unmatchedRows = cells.count { it.inMonth && it.totalMs > 0L && it.books.isEmpty() },
            footerLabel = "",
            showDurationOnlyLabel = false,
            showFooterLabel = false
        )
    }

    private fun buildLocalCalendarData(context: Context, s: AutoSettings): CalendarBuildData? {
        val baseRange = resolvePeriodRange(s)
        val frame = calendarFrameForSettings(s, baseRange)
        val monthStart = frame.monthStart
        val monthEnd = frame.monthEnd
        val weekRows = frame.weekRows
        val gridStart = frame.gridStart

        val liveData = buildLiveNeoCalendarData(context, s, monthStart, monthEnd, weekRows, gridStart)
        if (!AutoRefreshConfig.isReadingDataStoreEnabled(context)) {
            AutoRefreshLog.i(context, "calendar wallpaper data store disabled, use live Neo data")
            return liveData
        }
        val storedData = buildStoredNeoCalendarData(context, s, monthStart, monthEnd, weekRows, gridStart)
        if (storedData != null) {
            AutoRefreshLog.i(
                context,
                "calendar wallpaper use data store month=${fmt(monthStart)} rows=${storedData.statsRows} daysWithBooks=${storedData.cells.count { it.inMonth && it.books.isNotEmpty() }}"
            )
            return storedData
        }
        AutoRefreshLog.i(context, "calendar wallpaper data store empty, fallback live month=${fmt(monthStart)}")
        return liveData
    }

    private fun calendarFrameForSettings(s: AutoSettings, range: Pair<Long, Long>?): CalendarMonthFrame {
        val safeRange = range ?: resolvePeriodRange(s)
        return if (s.periodMode == "LAST_30_DAYS" && safeRange != null) {
            rollingLast30CalendarFrame(safeRange.first, safeRange.second)
        } else {
            calendarMonthFrame(safeRange?.second ?: System.currentTimeMillis())
        }
    }

    private fun rollingLast30CalendarFrame(rangeStart: Long, rangeEnd: Long): CalendarMonthFrame {
        val endWeek = Calendar.getInstance(TimeZone.getDefault()).apply {
            timeInMillis = rangeEnd
            firstDayOfWeek = Calendar.MONDAY
            set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        endWeek.add(Calendar.DAY_OF_MONTH, -28)
        val gridStart = startOfDayMs(endWeek.timeInMillis)
        return CalendarMonthFrame(rangeStart, rangeEnd, 5, gridStart)
    }

    private fun calendarMonthFrame(baseMs: Long): CalendarMonthFrame {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = baseMs
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val monthStart = cal.timeInMillis
        cal.add(Calendar.MONTH, 1)
        cal.add(Calendar.MILLISECOND, -1)
        val monthEnd = cal.timeInMillis

        val gridCal = Calendar.getInstance(TimeZone.getDefault())
        gridCal.timeInMillis = monthStart
        val mondayIndex = (gridCal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val daysInMonth = cal.get(Calendar.DAY_OF_MONTH)
        val weekRows = ((mondayIndex + daysInMonth + 6) / 7).coerceIn(5, 6)
        gridCal.add(Calendar.DAY_OF_MONTH, -mondayIndex)
        val gridStart = startOfDayMs(gridCal.timeInMillis)
        return CalendarMonthFrame(monthStart, monthEnd, weekRows, gridStart)
    }

    private fun isSameMonth(aMs: Long, bMs: Long): Boolean {
        val a = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = aMs }
        val b = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = bMs }
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.MONTH) == b.get(Calendar.MONTH)
    }

    private fun buildLiveNeoCalendarData(
        context: Context,
        s: AutoSettings,
        monthStart: Long,
        monthEnd: Long,
        weekRows: Int,
        gridStart: Long
    ): CalendarBuildData? {
        val metadata = loadCalendarMetadata(context, s)
        val metadataByPath = metadata.associateBy { it.path }
        val candidates = metadata.filter { it.lastAccessMs > 0L }.ifEmpty { metadata }
        val durationByDayPath = linkedMapOf<Long, LinkedHashMap<String, Long>>()
        val latestEventByDayPath = linkedMapOf<Long, LinkedHashMap<String, Long>>()
        val eventsByDay = linkedMapOf<Long, Int>()
        val unmatchedByDay = linkedMapOf<Long, Int>()
        val minMs = s.minDurationMinutes * 60_000L
        var rows = 0
        var matchedRows = 0
        var unmatchedRows = 0
        var querySucceeded = false

        context.contentResolver.query(
            statsUri,
            arrayOf("path", "eventTime", "durationTime"),
            "eventTime >= ? AND eventTime <= ? AND durationTime IS NOT NULL AND durationTime != '' AND durationTime != '0'",
            arrayOf(monthStart.toString(), monthEnd.toString()),
            null
        )?.use { c ->
            querySucceeded = true
            while (c.moveToNext()) {
                rows += 1
                val rawEvent = readColString(c, "eventTime")?.toLongOrNull() ?: continue
                val eventMs = normalizeEpochMs(rawEvent)
                val durationMs = readColString(c, "durationTime")?.toLongOrNull() ?: 0L
                if (durationMs < minMs) continue
                val day = startOfDayMs(eventMs)
                eventsByDay[day] = (eventsByDay[day] ?: 0) + 1
                val rawPath = readColString(c, "path").orEmpty()
                val matchedPath = when {
                    rawPath.isNotBlank() && metadataByPath.containsKey(rawPath) -> rawPath
                    rawPath.isNotBlank() -> rawPath
                    else -> nearestMetadataPath(eventMs, candidates, monthStart, monthEnd)
                }
                if (matchedPath.isNullOrBlank()) {
                    unmatchedRows += 1
                    unmatchedByDay[day] = (unmatchedByDay[day] ?: 0) + 1
                    continue
                }
                matchedRows += 1
                val dayMap = durationByDayPath.getOrPut(day) { linkedMapOf() }
                dayMap[matchedPath] = (dayMap[matchedPath] ?: 0L) + durationMs
                val latestMap = latestEventByDayPath.getOrPut(day) { linkedMapOf() }
                latestMap[matchedPath] = maxOf(latestMap[matchedPath] ?: 0L, eventMs)
            }
        }

        val cells = (0 until weekRows * 7).map { index ->
            val dayMs = gridStart + index * DAY_MS
            val dc = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = dayMs }
            val inMonth = dayMs in monthStart..monthEnd
            val dayMap = durationByDayPath[dayMs].orEmpty()
            val latestMap = latestEventByDayPath[dayMs].orEmpty()
            val selectedEntries = when (s.calendarStackOrder) {
                "SHORTEST_TOP" -> dayMap.entries.sortedBy { it.value }
                "LATEST_TOP" -> dayMap.entries.sortedByDescending { latestMap[it.key] ?: 0L }
                else -> dayMap.entries.sortedByDescending { it.value }
            }
                .take(4)
            val candidatesForDay = selectedEntries
                .map { (path, ms) ->
                    val meta = metadataByPath[path]
                    CalendarCoverItem(
                        title = meta?.item?.title ?: File(path).nameWithoutExtension.ifBlank { "未知书名" },
                        author = meta?.item?.author,
                        path = path,
                        status = meta?.item?.status ?: 1,
                        durationMs = ms,
                        lastSeenAt = latestMap[path] ?: dayMs,
                        bitmap = loadCalendarCoverBitmap(context, path)
                    )
                }
            val books = orderCalendarStack(candidatesForDay, s.calendarStackOrder)
            CalendarDayCell(
                dayStartMs = dayMs,
                dayOfMonth = dc.get(Calendar.DAY_OF_MONTH),
                inMonth = inMonth,
                totalMs = dayMap.values.sum(),
                eventCount = eventsByDay[dayMs] ?: 0,
                unmatchedCount = unmatchedByDay[dayMs] ?: 0,
                books = books
            )
        }
        AutoRefreshLog.i(
            context,
            "calendar wallpaper data month=${fmt(monthStart)} stackOrder=${s.calendarStackOrder} rows=$rows metadata=${metadata.size} matched=$matchedRows unmatched=$unmatchedRows daysWithBooks=${cells.count { it.inMonth && it.books.isNotEmpty() }} querySucceeded=$querySucceeded"
        )
        if (querySucceeded && AutoRefreshConfig.isReadingDataStoreEnabled(context)) {
            persistNeoCalendarEstimates(context, cells)
        } else if (querySucceeded) {
            AutoRefreshLog.i(context, "calendar persisted neo estimates skip: data store disabled")
        } else {
            AutoRefreshLog.i(
                context,
                "calendar persisted neo estimates skip: statistics provider returned null range=${fmt(monthStart)}~${fmt(monthEnd)}"
            )
        }
        return CalendarBuildData(monthStart, monthEnd, weekRows, cells, rows, matchedRows, unmatchedRows)
    }

    private fun buildStoredNeoCalendarData(
        context: Context,
        s: AutoSettings,
        monthStart: Long,
        monthEnd: Long,
        weekRows: Int,
        gridStart: Long
    ): CalendarBuildData? {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val startDate = dateFmt.format(Date(monthStart))
        val endDate = dateFmt.format(Date(monthEnd))
        val records = ReadingDataStore.queryDailyBooks(context, "NEO", startDate, endDate)
            .filter { record ->
                (s.includeUnread || record.status != 0) &&
                    (s.readingFilterMode != "READING_ONLY" || record.status == 1) &&
                    (s.readingFilterMode != "FINISHED_ONLY" || record.status == 2)
            }
        if (records.isEmpty()) return null
        val grouped = records.groupBy { it.date }
        val cells = (0 until weekRows * 7).map { index ->
            val dayMs = gridStart + index * DAY_MS
            val dc = Calendar.getInstance(TimeZone.getDefault()).apply { timeInMillis = dayMs }
            val inMonth = dayMs in monthStart..monthEnd
            val dayKey = dateFmt.format(Date(dayMs))
            val dayRecords = grouped[dayKey].orEmpty()
            val selectedRecords = when (s.calendarStackOrder) {
                "SHORTEST_TOP" -> dayRecords.sortedBy { it.durationMs }
                "LATEST_TOP" -> dayRecords.sortedByDescending { it.lastSeenAt }
                else -> dayRecords.sortedByDescending { it.durationMs }
            }
                .take(4)
            val candidatesForDay = selectedRecords
                .map { record ->
                    CalendarCoverItem(
                        title = record.title,
                        author = record.author,
                        path = record.bookKey,
                        status = record.status,
                        durationMs = record.durationMs,
                        lastSeenAt = record.lastSeenAt,
                        bitmap = loadCalendarStoredCoverBitmap(context, record)
                    )
                }
            val books = orderCalendarStack(candidatesForDay, s.calendarStackOrder)
            CalendarDayCell(
                dayStartMs = dayMs,
                dayOfMonth = dc.get(Calendar.DAY_OF_MONTH),
                inMonth = inMonth,
                totalMs = dayRecords.sumOf { it.durationMs },
                eventCount = dayRecords.size,
                unmatchedCount = 0,
                books = books
            )
        }
        AutoRefreshLog.i(
            context,
            "calendar wallpaper data source=db month=${fmt(monthStart)} stackOrder=${s.calendarStackOrder} records=${records.size} daysWithBooks=${cells.count { it.inMonth && it.books.isNotEmpty() }}"
        )
        return CalendarBuildData(monthStart, monthEnd, weekRows, cells, records.size, records.size, 0)
    }

    private fun loadCalendarStoredCoverBitmap(context: Context, record: ReadingDataStore.DailyBookRecord): Bitmap? {
        val fromCache = record.coverCachePath
            ?.takeIf { it.isNotBlank() }
            ?.let { BitmapFactory.decodeFile(it) }
        if (fromCache != null) return fromCache
        return if (record.bookKey.startsWith("/")) loadCalendarCoverBitmap(context, record.bookKey) else null
    }

    private fun persistNeoCalendarEstimates(context: Context, cells: List<CalendarDayCell>) {
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val rangeCells = cells.filter { it.inMonth }
        if (rangeCells.isEmpty()) {
            AutoRefreshLog.i(context, "calendar persisted neo estimates skip: empty range cells")
            return
        }
        val records = rangeCells
            .filter { it.books.isNotEmpty() }
            .flatMap { cell ->
                cell.books.map { book ->
                    ReadingDataStore.DailyBookRecord(
                        date = dateFmt.format(Date(cell.dayStartMs)),
                        source = "NEO",
                        bookKey = book.path.ifBlank { book.title },
                        title = book.title,
                        author = book.author,
                        coverCachePath = calendarCoverCachePath(context, book.path),
                        durationMs = book.durationMs,
                        progress = null,
                        status = book.status,
                        confidence = "ESTIMATED",
                        lastSeenAt = book.lastSeenAt
                    )
                }
            }
        val startDate = dateFmt.format(Date(rangeCells.minOf { it.dayStartMs }))
        val endDate = dateFmt.format(Date(rangeCells.maxOf { it.dayStartMs }))
        val written = ReadingDataStore.replaceDailyBooksForRange(
            context = context,
            source = "NEO",
            startDate = startDate,
            endDate = endDate,
            records = records,
            reason = "neo_calendar_estimates"
        )
        AutoRefreshLog.i(
            context,
            "calendar persisted neo estimates range=$startDate~$endDate records=${records.size} written=$written totalDb=${ReadingDataStore.countDailyBooks(context)}"
        )
    }

    private fun orderCalendarStack(
        books: List<CalendarCoverItem>,
        mode: String
    ): List<CalendarCoverItem> {
        // Canvas 后绘制的封面位于最上层，因此目标书必须排在列表末尾。
        return when (mode) {
            "SHORTEST_TOP" -> books.sortedByDescending { it.durationMs }
            "LATEST_TOP" -> books.sortedBy { it.lastSeenAt }
            else -> books.sortedBy { it.durationMs }
        }
    }

    private fun loadCalendarMetadata(context: Context, s: AutoSettings): List<MetadataBook> {
        val out = mutableListOf<MetadataBook>()
        context.contentResolver.query(metadataUri, null, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val path = readColString(c, "nativeAbsolutePath").orEmpty()
                if (path.isBlank()) continue
                val status = readColString(c, "readingStatus")?.toIntOrNull() ?: 0
                if (!s.includeUnread && status == 0) continue
                if (s.readingFilterMode == "READING_ONLY" && status != 1) continue
                if (s.readingFilterMode == "FINISHED_ONLY" && status != 2) continue
                out.add(
                    MetadataBook(
                        path = path,
                        lastAccessMs = normalizeEpochMs(readColString(c, "lastAccess")?.toLongOrNull() ?: 0L),
                        item = BookItem(
                            bookId = null,
                            title = readColString(c, "title") ?: File(path).nameWithoutExtension.ifBlank { "未知书名" },
                            author = readColString(c, "authors"),
                            progress = readColString(c, "progress"),
                            status = status,
                            localKeys = localBookKeys(c)
                        )
                    )
                )
            }
        }
        return out
    }

    private fun nearestMetadataPath(eventMs: Long, candidates: List<MetadataBook>, monthStart: Long, monthEnd: Long): String? {
        if (candidates.isEmpty()) return null
        val maxDelta = when {
            monthEnd - monthStart <= 8L * DAY_MS -> 3L * DAY_MS
            else -> 10L * DAY_MS
        }
        val best = candidates.minByOrNull { kotlin.math.abs(it.lastAccessMs - eventMs) } ?: return null
        if (best.lastAccessMs <= 0L) return null
        val delta = kotlin.math.abs(best.lastAccessMs - eventMs)
        return if (delta <= maxDelta) best.path else null
    }

    private fun loadCalendarCoverBitmap(context: Context, path: String): Bitmap? {
        if (path.isBlank()) return null
        val file = File(path)
        val cacheFile = File(calendarCoverCachePath(context, path).orEmpty())
        if (cacheFile.exists() && cacheFile.length() > 0L) {
            BitmapFactory.decodeFile(cacheFile.absolutePath)?.let { return it }
        }
        val bmp = decodeCoverFromBookFileWithReason(path).bitmap ?: return null
        runCatching {
            FileOutputStream(cacheFile).use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
        }
        return bmp
    }

    private fun calendarCoverCachePath(context: Context, path: String): String? {
        if (path.isBlank()) return null
        val file = File(path)
        val cacheDir = File(context.cacheDir, "extracted_covers").apply { if (!exists()) mkdirs() }
        return File(cacheDir, "${path.hashCode()}_${file.lastModified()}.jpg").absolutePath
    }

    private fun canvasSizeText(s: AutoSettings): String {
        val size = resolveWallpaperSize(s)
        return "${size.label} ${size.width}x${size.height}"
    }

    private fun resolveWallpaperSize(s: AutoSettings): WallpaperSize {
        return if (s.booxDevicePreset == BooxDevicePresets.CUSTOM_KEY) {
            WallpaperSize("自定义", s.customWallpaperWidth.coerceIn(300, 4000), s.customWallpaperHeight.coerceIn(300, 4000))
        } else {
            val preset = BooxDevicePresets.byKey(s.booxDevicePreset)
            WallpaperSize(preset.label, preset.widthPx, preset.heightPx)
        }
    }

    private fun buildWeReadStatsForSettings(context: Context, s: AutoSettings): WeReadBuildData? {
        val range = resolvePeriodRange(s) ?: return null
        val monthStarts = monthStartsBetween(range.first, range.second)
        if (monthStarts.isEmpty()) return null
        if (monthStarts.size > 24) {
            AutoRefreshLog.i(context, "WeRead range too large months=${monthStarts.size} period=${s.periodMode}")
            return null
        }

        val key = WeReadClient.loadApiKey(context)
        val bucketMap = linkedMapOf<Long, Long>()
        val bookMap = linkedMapOf<String, WeReadClient.WallpaperBook>()
        monthStarts.forEach { monthStart ->
            val stats = WeReadClient.fetchWallpaperStats(context, key, "monthly", monthStart / 1000L)
            if (!stats.ok) {
                AutoRefreshLog.i(context, "WeRead range fetch failed period=${s.periodMode} month=${fmt(monthStart)} detail=${stats.detail}")
                return null
            }
            persistWeReadMonthlyStats(context, monthStart, stats)
            stats.buckets.forEach { (bucketSec, seconds) ->
                val bucketStart = startOfDayMs(bucketSec * 1000L)
                if (bucketStart in range.first..range.second) {
                    bucketMap[bucketStart] = (bucketMap[bucketStart] ?: 0L) + seconds
                }
            }
            stats.books.forEach { book ->
                val id = "${book.title.trim()}|${book.author.trim()}"
                val old = bookMap[id]
                bookMap[id] = if (old == null) {
                    book
                } else {
                    old.copy(readSeconds = old.readSeconds + book.readSeconds)
                }
            }
        }

        val sortedBuckets = bucketMap.toSortedMap()
        val values = sortedBuckets.values.map { it * 1000L }.toLongArray()
        val labels = sortedBuckets.keys.map { SimpleDateFormat("MM-dd", Locale.US).format(Date(it)) }
        val totalMs = values.sum()
        val chart = ChartStats(
            totalMs = totalMs,
            points = if (values.isNotEmpty()) values else longArrayOf(0L),
            labels = if (labels.isNotEmpty()) labels else listOf(weReadPeriodLabel(s.periodMode))
        )
        val books = enrichExcerptMenuBooks(
            context,
            key,
            bookMap.values
                .sortedByDescending { it.readSeconds }
                .map { toWeReadBookItem(context, key, it) to it.readSeconds * 1000L }
                .filter { matchesReadingFilter(it.first, s) }
                .take(s.topN),
            s,
            fetchWeReadExcerpt = true
        )
        val note = if (monthStarts.size > 1 || s.periodMode != "LAST_30_DAYS") {
            "时长按日分桶精确过滤，书单按覆盖月份排行合并"
        } else {
            "时长按日分桶过滤"
        }
        AutoRefreshLog.i(context, "WeRead range stats period=${s.periodMode} range=${fmt(range.first)}~${fmt(range.second)} months=${monthStarts.size} buckets=${sortedBuckets.size} totalSec=${totalMs / 1000L} books=${books.size}")
        return WeReadBuildData(range.first, range.second, chart, books, weReadPeriodLabel(s.periodMode), note)
    }

    private fun buildMixedStatsForSettings(context: Context, s: AutoSettings): WeReadBuildData? {
        val range = resolvePeriodRange(s) ?: return null
        val localEvents = collectDurationEvents(context.contentResolver, range.first, range.second, s.minDurationMinutes)
        val weReadEvents = mutableListOf<Pair<Long, Long>>()
        val weReadBookScores = linkedMapOf<String, Pair<WeReadClient.WallpaperBook, Long>>()
        val monthStarts = monthStartsBetween(range.first, range.second)
        val key = WeReadClient.loadApiKey(context)
        val weReadFailures = mutableListOf<String>()
        monthStarts.forEach { monthStart ->
            val stats = WeReadClient.fetchWallpaperStats(context, key, "monthly", monthStart / 1000L)
            if (!stats.ok) {
                AutoRefreshLog.i(context, "Mixed WeRead fetch failed month=${fmt(monthStart)} detail=${stats.detail}")
                weReadFailures.add("${fmt(monthStart)} ${stats.detail.take(80)}")
                return@forEach
            }
            persistWeReadMonthlyStats(context, monthStart, stats)
            stats.buckets.forEach { (bucketSec, seconds) ->
                val bucketStart = startOfDayMs(bucketSec * 1000L)
                if (bucketStart in range.first..range.second) {
                    weReadEvents.add(bucketStart to seconds * 1000L)
                }
            }
            stats.books.forEach { book ->
                val id = "${book.title.trim()}|${book.author.trim()}"
                val old = weReadBookScores[id]
                val newMs = book.readSeconds * 1000L
                weReadBookScores[id] = if (old == null) {
                    book to newMs
                } else {
                    old.first.copy(readSeconds = old.first.readSeconds + book.readSeconds) to (old.second + newMs)
                }
            }
        }

        val chart = bucketize(localEvents + weReadEvents, range.first, range.second, chooseBucketMode(s, range.first, range.second))
        val localBooks = queryTopBooksByDuration(context, range.first, range.second, s)
            .map { it.first to it.second }
        val weReadBooks = weReadBookScores.values
            .map { (book, scoreMs) ->
                toWeReadBookItem(context, key, book).copy(durationText = formatDuration(scoreMs, s.timeUnit), menuPriceText = menuPriceText(scoreMs)) to scoreMs
            }
            .filter { matchesReadingFilter(it.first, s) }
        val mergedBooks = enrichExcerptMenuBooks(context, key, mergeScoredBooksWithScores(localBooks + weReadBooks, s.topN, s.timeUnit), s, fetchWeReadExcerpt = true)
        val note = buildString {
            append("本地+微信，图表按时间相加，书单按阅读时长合并排序")
            if (weReadFailures.isNotEmpty()) {
                append("；微信读书读取失败，已使用本地数据")
                if (weReadEvents.isNotEmpty() || weReadBooks.isNotEmpty()) append("和已读取到的微信数据")
            }
        }
        AutoRefreshLog.i(
            context,
            "Mixed stats range=${fmt(range.first)}~${fmt(range.second)} localEvents=${localEvents.size} weReadEvents=${weReadEvents.size} localBooks=${localBooks.size} weReadBooks=${weReadBooks.size} mergedBooks=${mergedBooks.size} weReadFailures=${weReadFailures.size} totalMs=${chart.totalMs}"
        )
        return WeReadBuildData(range.first, range.second, chart, mergedBooks, "混合", note)
    }

    internal fun persistWeReadMonthlyStats(
        context: Context,
        monthStartMs: Long,
        stats: WeReadClient.WallpaperStatsResult,
        captureSnapshot: Boolean = true
    ) {
        if (!stats.ok) return
        if (!AutoRefreshConfig.isReadingDataStoreEnabled(context)) {
            AutoRefreshLog.i(context, "WeRead data store persist skip disabled")
            return
        }
        val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val monthStart = Calendar.getInstance(TimeZone.getDefault()).apply {
            timeInMillis = monthStartMs
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val periodStart = dateFmt.format(Date(monthStart.timeInMillis))
        monthStart.add(Calendar.MONTH, 1)
        monthStart.add(Calendar.DAY_OF_MONTH, -1)
        val periodEnd = dateFmt.format(Date(monthStart.timeInMillis))
        val now = System.currentTimeMillis()

        val totals = stats.buckets.mapNotNull { (bucketSeconds, durationSeconds) ->
            val dayMs = startOfDayMs(bucketSeconds * 1000L)
            val date = dateFmt.format(Date(dayMs))
            if (date !in periodStart..periodEnd || durationSeconds <= 0L) {
                null
            } else {
                ReadingDataStore.DailyTotalRecord(
                    date = date,
                    source = "WEREAD",
                    durationMs = durationSeconds * 1000L,
                    confidence = "EXACT_API",
                    updatedAt = now
                )
            }
        }
        val totalsWritten = ReadingDataStore.replaceDailyTotalsForRange(
            context = context,
            source = "WEREAD",
            startDate = periodStart,
            endDate = periodEnd,
            records = totals,
            reason = "weread_monthly_stats"
        )

        val latestCover = WeReadClient.cachedLatestCover(context)
        val periodBooks = stats.books.map { book ->
            val cachedPath = latestCover
                ?.takeIf { it.bookId == book.bookId && it.cachePath.isNotBlank() }
                ?.cachePath
            ReadingDataStore.PeriodBookRecord(
                periodStart = periodStart,
                periodEnd = periodEnd,
                source = "WEREAD",
                bookKey = book.bookId.ifBlank { "${book.title.trim()}|${book.author.trim()}" },
                title = book.title,
                author = book.author,
                coverCachePath = cachedPath,
                durationMs = book.readSeconds * 1000L,
                confidence = "MONTHLY_RANKING",
                lastSeenAt = latestCover
                    ?.takeIf { it.bookId == book.bookId }
                    ?.readUpdateTimeMs
                    ?: 0L
            )
        }
        val booksWritten = ReadingDataStore.replacePeriodBooks(
            context = context,
            source = "WEREAD",
            periodStart = periodStart,
            periodEnd = periodEnd,
            records = periodBooks,
            reason = "weread_monthly_ranking"
        )
        AutoRefreshLog.i(
            context,
            "WeRead data store persisted period=$periodStart~$periodEnd daily=${totals.size}/$totalsWritten candidates=${periodBooks.size}/$booksWritten totalDaily=${ReadingDataStore.countDailyTotals(context, "WEREAD")} totalCandidates=${ReadingDataStore.countPeriodBooks(context, "WEREAD")}"
        )
        if (captureSnapshot) {
            WeReadReadingSync.captureCurrentMonth(context, monthStartMs, stats)
        }
    }

    private fun queryTopBooksByDuration(
        context: Context,
        start: Long,
        end: Long,
        s: AutoSettings
    ): List<Pair<BookItem, Long>> {
        val resolver = context.contentResolver
        val durationByPath = linkedMapOf<String, Long>()
        val orphanEvents = mutableListOf<Pair<Long, Long>>()
        val minMs = s.minDurationMinutes * 60_000L
        var statsRows = 0
        var statsRowsWithPath = 0
        resolver.query(
            statsUri,
            arrayOf("path", "eventTime", "durationTime"),
            "eventTime >= ? AND eventTime <= ? AND durationTime IS NOT NULL AND durationTime != '' AND durationTime != '0'",
            arrayOf(start.toString(), end.toString()),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                statsRows += 1
                val path = c.getString(c.getColumnIndexOrThrow("path")).orEmpty()
                val event = c.getString(c.getColumnIndexOrThrow("eventTime"))?.toLongOrNull() ?: 0L
                val dur = c.getString(c.getColumnIndexOrThrow("durationTime"))?.toLongOrNull() ?: 0L
                if (dur < minMs) continue
                if (path.isBlank()) {
                    if (event > 0L) orphanEvents.add(normalizeEpochMs(event) to dur)
                } else {
                    statsRowsWithPath += 1
                    durationByPath[path] = (durationByPath[path] ?: 0L) + dur
                }
            }
        }

        val metadata = linkedMapOf<String, MetadataBook>()
        resolver.query(
            metadataUri,
            arrayOf("nativeAbsolutePath", "title", "authors", "progress", "readingStatus", "lastAccess", "idString", "digest", "hashTag", "uuid", "guid", "id"),
            null,
            null,
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val path = c.getString(c.getColumnIndexOrThrow("nativeAbsolutePath")).orEmpty()
                if (path.isBlank()) continue
                val status = c.getString(c.getColumnIndexOrThrow("readingStatus"))?.toIntOrNull() ?: 0
                if (!s.includeUnread && status == 0) continue
                if (s.readingFilterMode == "READING_ONLY" && status != 1) continue
                if (s.readingFilterMode == "FINISHED_ONLY" && status != 2) continue
                metadata[path] = MetadataBook(
                    path = path,
                    lastAccessMs = normalizeEpochMs(c.getString(c.getColumnIndexOrThrow("lastAccess"))?.toLongOrNull() ?: 0L),
                    item = BookItem(
                        null,
                        c.getString(c.getColumnIndexOrThrow("title")) ?: File(path).nameWithoutExtension,
                        c.getString(c.getColumnIndexOrThrow("authors")),
                        c.getString(c.getColumnIndexOrThrow("progress")),
                        status,
                        localKeys = localBookKeys(c)
                    )
                )
            }
        }

        var timeMatched = 0
        var timeUnmatched = 0
        if (orphanEvents.isNotEmpty() && metadata.isNotEmpty()) {
            val candidates = metadata.values
                .filter { it.lastAccessMs > 0L }
                .ifEmpty { metadata.values.toList() }
            val maxDelta = when {
                end - start <= DAY_MS -> 12L * 60L * 60L * 1000L
                end - start <= 8L * DAY_MS -> 3L * DAY_MS
                else -> 10L * DAY_MS
            }
            orphanEvents.forEach { (eventMs, dur) ->
                val best = candidates.minByOrNull {
                    val access = if (it.lastAccessMs > 0L) it.lastAccessMs else start
                    kotlin.math.abs(access - eventMs)
                }
                val bestAccess = best?.lastAccessMs ?: 0L
                val delta = if (best != null && bestAccess > 0L) kotlin.math.abs(bestAccess - eventMs) else Long.MAX_VALUE
                if (best != null && delta <= maxDelta) {
                    durationByPath[best.path] = (durationByPath[best.path] ?: 0L) + dur
                    timeMatched += 1
                } else {
                    timeUnmatched += 1
                }
            }
        }

        AutoRefreshLog.i(context, "Local duration book match rows=$statsRows rowsWithPath=$statsRowsWithPath orphan=${orphanEvents.size} timeMatched=$timeMatched timeUnmatched=$timeUnmatched metadata=${metadata.size} durationBooks=${durationByPath.size}")

        if (durationByPath.isEmpty()) return queryTopBooks(
            resolver,
            start,
            end,
            s.topN,
            s.includeUnread,
            s.readingFilterMode
        ).mapIndexed { idx, item -> item to ((s.topN - idx).coerceAtLeast(1) * 60_000L).toLong() }

        return durationByPath.mapNotNull { (path, ms) ->
            val item = metadata[path]?.item ?: BookItem(null, File(path).nameWithoutExtension, null, null, 1)
            item.copy(durationText = formatDuration(ms, s.timeUnit)) to ms
        }.sortedByDescending { it.second }
    }

    private fun toWeReadBookItem(context: Context, apiKey: String, book: WeReadClient.WallpaperBook): BookItem {
        val progress = if (book.bookId.isNotBlank()) {
            WeReadClient.fetchBookProgress(context, apiKey, book.bookId)
        } else {
            null
        }
        val progressValue = progress?.progressPercent
        val progressText = progressValue?.let { "${it.coerceIn(0, 100)}%" }
        val status = when {
            progressValue != null && progressValue >= 100 -> 2
            progressValue != null && progressValue > 0 -> 1
            book.readSeconds > 0L -> 1
            else -> 0
        }
        val durationSeconds = progress?.recordReadingSeconds?.takeIf { it > 0L } ?: book.readSeconds
        return BookItem(
            bookId = book.bookId,
            title = book.title,
            author = book.author,
            progress = null,
            status = status,
            progressText = progressText,
            durationText = WeReadClient.formatSeconds(durationSeconds)
        )
    }

    private fun matchesReadingFilter(book: BookItem, s: AutoSettings): Boolean {
        if (!s.includeUnread && book.status == 0) return false
        return when (s.readingFilterMode) {
            "READING_ONLY" -> book.status == 1
            "FINISHED_ONLY" -> book.status == 2
            else -> true
        }
    }

    private fun mergeScoredBooks(items: List<Pair<BookItem, Long>>, limit: Int, unit: String): List<BookItem> {
        return mergeScoredBooksWithScores(items, limit, unit).map { it.first }
    }

    private fun mergeScoredBooksWithScores(items: List<Pair<BookItem, Long>>, limit: Int, unit: String): List<Pair<BookItem, Long>> {
        val merged = linkedMapOf<String, Pair<BookItem, Long>>()
        items.forEach { (book, score) ->
            val key = "${book.title.trim()}|${book.author.orEmpty().trim()}"
            val old = merged[key]
            merged[key] = if (old == null) {
                book to score
            } else {
                val total = old.second + score
                val progressBase = if (old.first.progressText.isNullOrBlank() && !book.progressText.isNullOrBlank()) book else old.first
                val base = when {
                    progressBase.bookId.isNullOrBlank() && !book.bookId.isNullOrBlank() -> book
                    else -> progressBase
                }
                base.copy(durationText = formatDuration(total, unit), menuPriceText = menuPriceText(total)) to total
            }
        }
        return merged.values
            .sortedByDescending { it.second }
            .take(limit)
            .map { (book, score) -> book.copy(menuPriceText = menuPriceText(score)) to score }
    }

    private fun enrichExcerptMenuBooks(
        context: Context,
        apiKey: String,
        items: List<Pair<BookItem, Long>>,
        s: AutoSettings,
        fetchWeReadExcerpt: Boolean
    ): List<BookItem> {
        val noteTemplate = s.statsTemplate == "EXCERPT_MENU" || s.statsTemplate == "READING_MENU"
        return items.mapIndexed { idx, (book, scoreMs) ->
            val priced = book.copy(menuPriceText = menuPriceText(scoreMs))
            if (!noteTemplate) {
                priced
            } else if (s.bookNoteMode == "MANUAL") {
                priced.copy(latestExcerptText = loadManualBookNote(context, priced, idx))
            } else if (s.bookNoteMode == "AUTO_THEN_MANUAL") {
                val auto = loadAutoBookNote(context, apiKey, priced, fetchWeReadExcerpt, s)
                priced.copy(latestExcerptText = auto.ifBlank { loadManualBookNote(context, priced, idx) })
            } else {
                priced.copy(latestExcerptText = loadAutoBookNote(context, apiKey, priced, fetchWeReadExcerpt, s))
            }
        }
    }

    private fun stableBookKeys(book: BookItem): List<String> {
        val keys = mutableListOf<String>()
        if (!book.bookId.isNullOrBlank()) keys += "WEREAD:${book.bookId}"
        keys += book.localKeys
        val title = book.title.trim()
        if (title.isNotBlank()) keys += "TITLE:${title.lowercase(Locale.ROOT).hashCode()}"
        return keys.distinct()
    }

    private fun cacheBookOrder(context: Context, books: List<BookItem>) {
        val p = context.getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE)
        val e = p.edit()
        books.take(5).forEachIndexed { idx, book ->
            e.putString("last_book_order_$idx", book.title)
            e.putString("last_book_author_$idx", book.author.orEmpty())
            e.putString("last_book_keys_$idx", stableBookKeys(book).joinToString("|"))
        }
        e.apply()
    }

    private fun loadManualBookNote(context: Context, book: BookItem, idx: Int): String {
        val keys = stableBookKeys(book)
        ReadingDataStore.queryBookNote(context, keys)?.note?.trim()?.let {
            if (it.isNotBlank()) return it
        }
        val p = context.getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE)
        keys.forEach { key ->
            val note = p.getString("book_note_key_${key.hashCode()}", "")?.trim().orEmpty()
            if (note.isNotBlank()) return note
        }
        return p.getString("book_note_$idx", "")?.trim().orEmpty()
    }

    private fun loadAutoBookNote(
        context: Context,
        apiKey: String,
        book: BookItem,
        fetchWeReadExcerpt: Boolean,
        s: AutoSettings
    ): String {
        if (fetchWeReadExcerpt && !book.bookId.isNullOrBlank()) {
            val personalFirst = s.excerptSourceMode != "POPULAR_ONLY"
            val popularAllowed = s.excerptSourceMode != "PERSONAL_ONLY"
            if (personalFirst) {
                val personal = WeReadClient.fetchLatestNote(context, apiKey, book.bookId)
                if (personal.ok && personal.text.isNotBlank()) return personal.text
            }
            if (popularAllowed) {
                val popular = WeReadClient.fetchPopularBookmark(context, apiKey, book.bookId)
                if (popular.ok && popular.text.isNotBlank()) return popular.text
            }
        }
        if (book.localKeys.isNotEmpty()) {
            val note = fetchLatestLocalAnnotation(context, book.localKeys)
            if (note != null && note.text.isNotBlank()) return note.text
        }
        return ""
    }

    private fun resolveFooterNote(context: Context, apiKey: String, books: List<BookItem>, s: AutoSettings): String {
        if (s.footerNoteSource != "DATA_SOURCE") return s.noteText.trim()
        val effectiveApiKey = apiKey.ifBlank { WeReadClient.loadApiKey(context) }
        for (book in books) {
            val note = loadAutoBookNote(
                context = context,
                apiKey = effectiveApiKey,
                book = book,
                fetchWeReadExcerpt = !book.bookId.isNullOrBlank(),
                s = s
            ).trim()
            if (note.isNotBlank()) {
                return if (s.footerShowBookTitle) "$note ——《${book.title}》" else note
            }
        }
        return ""
    }

    private data class LocalAnnotationResult(
        val text: String,
        val updatedAt: Long
    )

    private fun localBookKeys(c: android.database.Cursor): List<String> {
        return listOf(
            "idString",
            "digest",
            "hashTag",
            "uuid",
            "guid",
            "id",
            "nativeAbsolutePath"
        ).mapNotNull { name ->
            readColString(c, name)?.trim()?.takeIf { it.isNotBlank() }
        }.distinct()
    }

    private fun fetchLatestLocalAnnotation(context: Context, localKeys: List<String>): LocalAnnotationResult? {
        val keys = localKeys
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        if (keys.isEmpty()) return null
        val uri = Uri.parse("content://com.onyx.content.database.ContentProvider/Annotation")
        fun readFromCursor(c: android.database.Cursor): LocalAnnotationResult? {
            var best: LocalAnnotationResult? = null
            fun col(name: String): String {
                val i = c.getColumnIndex(name)
                if (i < 0 || c.isNull(i)) return ""
                return runCatching { c.getString(i) ?: "" }.getOrDefault("")
            }
            while (c.moveToNext()) {
                val rowKeys = listOf(
                    col("idString"),
                    col("objId")
                ).map { it.trim() }.filter { it.isNotBlank() }
                if (rowKeys.none { it in keys }) continue
                val note = col("note").ifBlank { col("linkNote") }.trim()
                val quote = col("quote").trim()
                val text = note.ifBlank { quote }
                if (text.isBlank()) continue
                val updatedAt = normalizeEpochMs(
                    col("updatedAt").toLongOrNull()
                        ?: col("createdAt").toLongOrNull()
                        ?: 0L
                )
                val current = LocalAnnotationResult(text, updatedAt)
                if (best == null || current.updatedAt > best!!.updatedAt) best = current
            }
            return best
        }
        return runCatching {
            val queryKeys = keys.take(8)
            val placeholders = queryKeys.joinToString(",") { "?" }
            context.contentResolver.query(
                uri,
                null,
                "idString IN ($placeholders) OR objId IN ($placeholders)",
                (queryKeys + queryKeys).toTypedArray(),
                "updatedAt DESC"
            )?.use { c -> readFromCursor(c) }
        }.getOrNull() ?: runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c -> readFromCursor(c) }
        }.getOrNull()
    }

    private fun menuPriceText(durationMs: Long): String {
        val minutes = ((durationMs + 59_999L) / 60_000L).coerceAtLeast(1L)
        val price = ((minutes + 9L) / 10L).coerceIn(1L, 999L)
        return "¥$price"
    }

    private fun menuTotalPriceText(books: List<BookItem>): String {
        val total = books.sumOf { book ->
            book.menuPriceText
                ?.filter { it.isDigit() }
                ?.toIntOrNull()
                ?: 1
        }
        return "¥$total"
    }

    private fun weReadPeriodLabel(periodMode: String): String {
        return when (periodMode) {
            "TODAY" -> "当天"
            "YESTERDAY" -> "昨天"
            "THIS_WEEK" -> "本周"
            "LAST_WEEK" -> "上周"
            "THIS_MONTH" -> "本月"
            "LAST_7_DAYS" -> "最近7天"
            "LAST_30_DAYS" -> "最近30天"
            "CUSTOM" -> "自定义周期"
            else -> periodMode
        }
    }

    private fun monthStartsBetween(startMs: Long, endMs: Long): List<Long> {
        val out = mutableListOf<Long>()
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = startMs
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        while (cal.timeInMillis <= endMs) {
            out.add(cal.timeInMillis)
            cal.add(Calendar.MONTH, 1)
        }
        return out
    }

    private fun startOfDayMs(ms: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = ms
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun weReadRange(stats: WeReadClient.WallpaperStatsResult): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val baseMs = stats.baseTimeSeconds * 1000L
        if (stats.mode == "overall") {
            val first = stats.buckets.firstOrNull()?.first?.times(1000L) ?: now
            return first to now
        }
        if (baseMs <= 0L) return now to now
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = baseMs
        val start = cal.timeInMillis
        when (stats.mode) {
            "weekly" -> cal.add(Calendar.DAY_OF_MONTH, 6)
            "monthly" -> {
                cal.add(Calendar.MONTH, 1)
                cal.add(Calendar.DAY_OF_MONTH, -1)
            }
            "annually" -> {
                cal.add(Calendar.YEAR, 1)
                cal.add(Calendar.DAY_OF_MONTH, -1)
            }
            else -> cal.add(Calendar.DAY_OF_MONTH, 0)
        }
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        return start to minOf(cal.timeInMillis, now)
    }

    private fun weReadChartStats(stats: WeReadClient.WallpaperStatsResult, mode: String): ChartStats {
        if (stats.buckets.isEmpty()) {
            return ChartStats(stats.totalReadSeconds * 1000L, longArrayOf(stats.totalReadSeconds * 1000L), listOf(WeReadClient.modeLabel(mode)))
        }
        val values = stats.buckets.map { it.second * 1000L }.toLongArray()
        val labels = stats.buckets.map { (seconds, _) ->
            val d = Date(seconds * 1000L)
            when (mode) {
                "annually" -> SimpleDateFormat("MM月", Locale.US).format(d)
                "overall" -> SimpleDateFormat("yyyy", Locale.US).format(d)
                else -> SimpleDateFormat("MM-dd", Locale.US).format(d)
            }
        }
        val total = if (stats.totalReadSeconds > 0L) stats.totalReadSeconds * 1000L else values.sum()
        return ChartStats(total, values, labels)
    }

    private fun tryBuildCoverWallpaper(context: Context, s: AutoSettings, sourceMark: String): PreviewResult? {
        val startedAt = System.currentTimeMillis()
        val trace = CoverProbeTrace()
        val cursor = context.contentResolver.query(metadataUri, null, null, null, "lastAccess DESC") ?: return null
        cursor.use { c ->
            if (!c.moveToFirst()) {
                AutoRefreshLog.i(context, "cover mode: metadata empty")
                logCoverProbeSummary(context, startedAt, "miss:metadata_empty", null, trace)
                return null
            }
            AutoRefreshLog.i(context, "cover mode: metadata columns=${c.columnNames.joinToString(",")} (local-only)")
            val coverColumns = listOf(
                "coverPath", "cover", "coverUri", "thumbnail", "thumbnailPath",
                "bookCoverPath", "frontCoverPath", "coverUrl", "cover_url"
            )
            var row = 0
            do {
                row++
                trace.rowsScanned = row
                val title = readColString(c, "title") ?: "未知书名"
                val readingStatus = readColString(c, "readingStatus") ?: "?"
                val nativePath = readColString(c, "nativeAbsolutePath") ?: ""
                AutoRefreshLog.i(
                    context,
                    "cover mode row=$row title=${title.take(48)} status=$readingStatus ext=${File(nativePath).extension.lowercase(Locale.ROOT)}"
                )
                for (col in coverColumns) {
                    readColString(c, col)?.let { v ->
                        trace.columnCandidateCount++
                        if (v.startsWith("http://") || v.startsWith("https://")) {
                            AutoRefreshLog.i(context, "cover mode row=$row candidate $col ignored network url (local-only)")
                        }
                        AutoRefreshLog.i(context, "cover mode row=$row candidate $col=${v.take(120)}")
                    }
                    trace.columnDecodeAttempts++
                    val bmp = readBitmapFromColumn(context, c, col)
                    if (bmp != null) {
                        AutoRefreshLog.i(context, "cover mode hit row=$row column=$col title=$title w=${bmp.width} h=${bmp.height}")
                        logCoverProbeSummary(context, startedAt, "hit:column:$col", title, trace)
                        return PreviewResult(renderCoverWallpaper(context, bmp, title, sourceMark, s), "封面壁纸 title=$title col=$col fit=${s.coverFitMode} size=${canvasSizeText(s)}")
                    }
                }
                
                val md5 = readColString(c, "md5")
                if (!md5.isNullOrBlank()) {
                    val root = Environment.getExternalStorageDirectory().absolutePath
                    val cachePaths = listOf(
                        "$root/.kreader/cover/$md5.jpg",
                        "$root/.kreader/cover/$md5.png",
                        "$root/.Onyx/cloud/cache/reader/$md5.jpg"
                    )
                    for (cp in cachePaths) {
                        trace.md5CacheAttempts++
                        val cbmp = BitmapFactory.decodeFile(cp)
                        if (cbmp != null) {
                            AutoRefreshLog.i(context, "cover mode hit row=$row md5 cache=$cp w=${cbmp.width} h=${cbmp.height}")
                            logCoverProbeSummary(context, startedAt, "hit:md5_cache", title, trace)
                            return PreviewResult(renderCoverWallpaper(context, cbmp, title, sourceMark, s), "封面壁纸 title=$title md5 cache fit=${s.coverFitMode} size=${canvasSizeText(s)}")
                        }
                    }
                }

                val structuredSpecs = extractStructuredCoverSpecs(
                    readColString(c, "coverUrl"),
                    readColString(c, "extraInfo"),
                    readColString(c, "downloadInfo")
                )
                if (structuredSpecs.isNotEmpty()) {
                    AutoRefreshLog.i(context, "cover mode row=$row structured specs=${structuredSpecs.joinToString(" | ").take(300)}")
                }
                for (spec in structuredSpecs) {
                    trace.structuredSpecAttempts++
                    val bmp = decodeBitmapByPathOrUri(context, spec)
                    if (bmp != null) {
                        AutoRefreshLog.i(context, "cover mode hit row=$row structured spec=$spec w=${bmp.width} h=${bmp.height}")
                        logCoverProbeSummary(context, startedAt, "hit:structured_spec", title, trace)
                        return PreviewResult(renderCoverWallpaper(context, bmp, title, sourceMark, s), "封面壁纸 title=$title structured fit=${s.coverFitMode} size=${canvasSizeText(s)}")
                    }
                }
                if (nativePath.isNotBlank()) {
                    trace.fileFallbackAttempts++
                    val f = java.io.File(nativePath)
                    val cacheKey = "${nativePath.hashCode()}_${f.lastModified()}.jpg"
                    val myCacheDir = java.io.File(context.cacheDir, "extracted_covers").apply { if (!exists()) mkdirs() }
                    val cacheFile = java.io.File(myCacheDir, cacheKey)
                    
                    if (cacheFile.exists() && cacheFile.length() > 0) {
                        trace.internalCacheAttempts++
                        val cbmp = android.graphics.BitmapFactory.decodeFile(cacheFile.absolutePath)
                        if (cbmp != null) {
                            AutoRefreshLog.i(context, "cover mode hit by internal cache row=$row title=$title w=${cbmp.width} h=${cbmp.height}")
                            logCoverProbeSummary(context, startedAt, "hit:internal_cache", title, trace)
                            return PreviewResult(renderCoverWallpaper(context, cbmp, title, sourceMark, s), "封面壁纸 title=$title source=internal_cache fit=${s.coverFitMode} size=${canvasSizeText(s)}")
                        }
                    }

                    val probe = decodeCoverFromBookFileWithReason(nativePath)
                    val fallback = probe.bitmap
                    if (fallback != null) {
                        runCatching {
                            java.io.FileOutputStream(cacheFile).use { out ->
                                fallback.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
                            }
                        }
                        AutoRefreshLog.i(context, "cover mode hit by file fallback row=$row title=$title path=$nativePath w=${fallback.width} h=${fallback.height}")
                        logCoverProbeSummary(context, startedAt, "hit:file_fallback", title, trace)
                        return PreviewResult(renderCoverWallpaper(context, fallback, title, sourceMark, s), "封面壁纸 title=$title source=file fit=${s.coverFitMode} size=${canvasSizeText(s)}")
                    } else {
                        trace.fallbackMissReasons[probe.reason] = (trace.fallbackMissReasons[probe.reason] ?: 0) + 1
                        AutoRefreshLog.i(context, "cover mode row=$row file fallback miss reason=${probe.reason} path=$nativePath")
                    }
                }
                val hints = listOf("nativeAbsolutePath", "filePath", "path").mapNotNull { k -> readColString(c, k)?.let { "$k=$it" } }
                AutoRefreshLog.i(context, "cover mode row=$row no cover decoded title=$title pathHints=${hints.joinToString(";")}")
            } while (row < 30 && c.moveToNext())
            AutoRefreshLog.i(context, "cover mode exhausted rows=$row no valid cover")
            logCoverProbeSummary(context, startedAt, "miss:exhausted", null, trace)
            return null
        }
    }

    private fun logCoverProbeSummary(
        context: Context,
        startedAtMs: Long,
        outcome: String,
        title: String?,
        trace: CoverProbeTrace
    ) {
        val cost = System.currentTimeMillis() - startedAtMs
        val missReasons = if (trace.fallbackMissReasons.isEmpty()) {
            "-"
        } else {
            trace.fallbackMissReasons.entries.joinToString("|") { "${it.key}:${it.value}" }
        }
        AutoRefreshLog.i(
            context,
            "cover probe summary outcome=$outcome localOnly=true costMs=$cost rows=${trace.rowsScanned} title=${title?.take(48) ?: "-"} " +
                "colCand=${trace.columnCandidateCount} colTry=${trace.columnDecodeAttempts} md5Try=${trace.md5CacheAttempts} " +
                "specTry=${trace.structuredSpecAttempts} internalTry=${trace.internalCacheAttempts} fileTry=${trace.fileFallbackAttempts} missReasons=$missReasons"
        )
    }

    private fun readColString(c: android.database.Cursor, name: String): String? {
        val idx = c.getColumnIndex(name)
        if (idx < 0 || c.isNull(idx)) return null
        return runCatching { c.getString(idx) }.getOrNull()
    }

    private fun readBitmapFromColumn(context: Context, c: android.database.Cursor, name: String): Bitmap? {
        val idx = c.getColumnIndex(name)
        if (idx < 0 || c.isNull(idx)) return null
        return when (c.getType(idx)) {
            android.database.Cursor.FIELD_TYPE_BLOB -> {
                val blob = c.getBlob(idx) ?: return null
                BitmapFactory.decodeByteArray(blob, 0, blob.size)
            }
            android.database.Cursor.FIELD_TYPE_STRING -> {
                val v = c.getString(idx) ?: return null
                decodeBitmapByPathOrUri(context, v)
            }
            else -> null
        }
    }

    private fun decodeBitmapByPathOrUri(context: Context, value: String): Bitmap? {
        if (value.startsWith("http://") || value.startsWith("https://")) {
            AutoRefreshLog.i(context, "cover mode ignored network url decode request (local-only)")
            return null
        }
        return runCatching {
            when {
                value.startsWith("content://") -> context.contentResolver.openInputStream(Uri.parse(value))?.use { BitmapFactory.decodeStream(it) }
                value.startsWith("/") -> BitmapFactory.decodeFile(value)
                else -> null
            }
        }.getOrNull()
    }

    private fun extractStructuredCoverSpecs(vararg texts: String?): List<String> {
        val out = linkedSetOf<String>()
        val imagePathRegex = Regex("""(/[^"'\\s]+?\.(?:jpg|jpeg|png|webp|bmp))""", RegexOption.IGNORE_CASE)
        val contentRegex = Regex("""(content://[^"'\\s]+)""", RegexOption.IGNORE_CASE)
        val keyRegex = Regex(""""(?:cover|coverUrl|cover_uri|thumbnail|thumb|image|img)"\s*:\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
        for (text in texts) {
            if (text.isNullOrBlank()) continue
            contentRegex.findAll(text).forEach { out += it.groupValues[1] }
            imagePathRegex.findAll(text).forEach { out += it.groupValues[1] }
            keyRegex.findAll(text).forEach { m ->
                val v = m.groupValues[1].trim()
                if (v.startsWith("content://") || v.startsWith("/")) {
                    out += v
                }
            }
        }
        return out.toList()
    }

    private fun decodeCoverFromBookFile(path: String): Bitmap? {
        return decodeCoverFromBookFileWithReason(path).bitmap
    }

    private fun decodeCoverFromBookFileWithReason(path: String): FileCoverProbe {
        val file = File(path)
        if (!file.exists()) return FileCoverProbe(null, "file_not_exists")
        if (!file.canRead()) return FileCoverProbe(null, "file_not_readable")
        val ext = file.extension.lowercase(Locale.ROOT)
        if (ext in setOf("mobi", "azw3", "azw", "jeb", "txt")) {
            return FileCoverProbe(null, "format_not_implemented:$ext")
        }
        return runCatching {
            when (ext) {
                "epub" -> FileCoverProbe(decodeEpubCover(file), "epub_decode")
                "cbz", "zip" -> FileCoverProbe(decodeCbzCover(file), "cbz_decode")
                "pdf" -> FileCoverProbe(decodePdfFirstPage(file), "pdf_decode")
                else -> FileCoverProbe(null, "unsupported_ext:$ext")
            }
        }.getOrElse { FileCoverProbe(null, "exception:${it.javaClass.simpleName}:${it.message}") }
    }

    private fun decodeEpubCover(file: File): Bitmap? {
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val imageEntries = entries.filter { isImageEntryName(it.name) }
            if (imageEntries.isEmpty()) return null
            val preferred = imageEntries
                .filter { it.name.lowercase(Locale.ROOT).contains("cover") }
                .sortedBy { it.name.length }
                .firstOrNull()
                ?: imageEntries.sortedBy { it.name.length }.firstOrNull()
            return preferred?.let { entry ->
                zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
            }
        }
    }

    private fun decodeCbzCover(file: File): Bitmap? {
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { !it.isDirectory && isImageEntryName(it.name) }
                .sortedBy { it.name.lowercase(Locale.ROOT) }
                .toList()
            val first = entries.firstOrNull() ?: return null
            return zip.getInputStream(first).use { BitmapFactory.decodeStream(it) }
        }
    }

    private fun decodePdfFirstPage(file: File): Bitmap? {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount <= 0) return null
                renderer.openPage(0).use { page ->
                    val w = (page.width * 2).coerceAtLeast(1200)
                    val h = (page.height * 2).coerceAtLeast(1600)
                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bitmap
                }
            }
        }
    }

    private fun isImageEntryName(name: String): Boolean {
        val l = name.lowercase(Locale.ROOT)
        return l.endsWith(".jpg") || l.endsWith(".jpeg") || l.endsWith(".png") || l.endsWith(".webp")
    }

    private fun renderCoverWallpaper(context: Context, cover: Bitmap, title: String, sourceMark: String, s: AutoSettings): Bitmap {
        val size = resolveWallpaperSize(s)
        val w = size.width
        val h = size.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        val scale = if (s.coverFitMode == "CROP") maxOf(w / cover.width.toFloat(), h / cover.height.toFloat())
        else minOf(w / cover.width.toFloat(), h / cover.height.toFloat())
        val dw = cover.width * scale
        val dh = cover.height * scale
        val dst = RectF((w - dw) / 2f, (h - dh) / 2f, (w + dw) / 2f, (h + dh) / 2f)
        canvas.drawBitmap(cover, null, dst, null)
        // Keep cover mode visually clean: no bottom-left title overlay.
        drawSourceCornerMark(canvas, w, h, sourceMark, 1f)
        return out
    }

    private fun drawCalendarWallpaper(context: Context, data: CalendarBuildData, s: AutoSettings, sourceMark: String): Bitmap {
        return if (s.calendarTemplate == "ROUNDED_CARD") {
            drawRoundedCardCalendarWallpaper(context, data, s, sourceMark)
        } else {
            drawClassicCalendarWallpaper(context, data, s, sourceMark)
        }
    }

    private fun drawClassicCalendarWallpaper(context: Context, data: CalendarBuildData, s: AutoSettings, sourceMark: String): Bitmap {
        val size = resolveWallpaperSize(s)
        val w = size.width
        val h = size.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bg = Color.rgb(247, 242, 234)
        val paper = Color.rgb(252, 249, 243)
        val ink = Color.rgb(35, 22, 20)
        val muted = Color.rgb(128, 106, 101)
        val accent = Color.rgb(170, 62, 52)
        canvas.drawColor(bg)

        val titleFace = resolveTypeface(context, s.titleFont, true)
        val bodyFace = resolveTypeface(context, s.bodyFont, false)
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textSize = (w * 0.072f).coerceIn(58f, 116f)
            typeface = Typeface.create(titleFace, Typeface.BOLD)
        }
        val summaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textSize = (w * 0.024f).coerceIn(22f, 38f)
            typeface = Typeface.create(bodyFace, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val weekPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textSize = (w * 0.03f).coerceIn(26f, 48f)
            typeface = Typeface.create(bodyFace, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = (w * 0.036f).coerceIn(32f, 56f)
            typeface = Typeface.create(bodyFace, Typeface.NORMAL)
        }
        val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textSize = (w * 0.017f).coerceIn(16f, 28f)
            typeface = Typeface.create(bodyFace, Typeface.NORMAL)
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(105, 80, 62, 58)
            strokeWidth = (w * 0.0015f).coerceIn(1.5f, 3f)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = paper
            style = Paint.Style.FILL
        }
        val gridStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 80, 62, 58)
            style = Paint.Style.STROKE
            strokeWidth = (w * 0.001f).coerceIn(1f, 2f)
        }

        val marginX = w * 0.055f
        val top = h * 0.034f
        val monthText = calendarTitleLabel(data, s)
        canvas.drawText(monthText, marginX, top + title.textSize, title)
        val coveredDays = data.cells.count { it.inMonth && it.books.isNotEmpty() }
        val totalDuration = data.cells.filter { it.inMonth }.sumOf { it.totalMs }
        val uniqueBooks = data.cells
            .filter { it.inMonth }
            .flatMap { it.books }
            .distinctBy { calendarBookIdentity(it.title) }
        val activeDays = data.cells.count { it.inMonth && it.totalMs > 0L }
        val averagePerActiveDay = if (activeDays > 0) totalDuration / activeDays else 0L
        canvas.drawText("总时长 ${compactDuration(totalDuration)}", w - marginX, top + title.textSize * 0.46f, summaryPaint)
        summaryPaint.typeface = Typeface.create(bodyFace, Typeface.NORMAL)
        canvas.drawText("日均 ${compactDuration(averagePerActiveDay)} · 读过${uniqueBooks.size}本", w - marginX, top + title.textSize * 0.82f, summaryPaint)
        canvas.drawText("读完${uniqueBooks.count { it.status == 2 }}本 · ${data.matchedRows}次记录", w - marginX, top + title.textSize * 1.18f, summaryPaint)

        val gridTop = top + title.textSize + h * 0.035f
        val gridLeft = marginX
        val gridRight = w - marginX
        val gridWidth = gridRight - gridLeft
        val weekH = h * 0.058f
        val gridBottom = h - h * 0.055f
        val rowCount = data.weekRows
        val rowH = ((gridBottom - gridTop - weekH) / rowCount.toFloat()).coerceAtLeast(100f)
        val colW = gridWidth / 7f
        val gridRect = RectF(gridLeft, gridTop, gridRight, gridTop + weekH + rowH * rowCount)
        canvas.drawRoundRect(gridRect, w * 0.018f, w * 0.018f, fill)
        canvas.drawRoundRect(gridRect, w * 0.018f, w * 0.018f, gridStroke)

        val weekdays = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        weekdays.forEachIndexed { i, label ->
            weekPaint.color = if (i == 6) accent else muted
            canvas.drawText(label, gridLeft + colW * (i + 0.5f), gridTop + weekH * 0.63f, weekPaint)
        }
        canvas.drawLine(gridLeft, gridTop + weekH, gridRight, gridTop + weekH, line)

        for (r in 0 until rowCount) {
            val y0 = gridTop + weekH + rowH * r
            if (r > 0) canvas.drawLine(gridLeft, y0, gridRight, y0, line)
            for (col in 0 until 7) {
                val cell = data.cells[r * 7 + col]
                val x0 = gridLeft + colW * col
                if (col > 0) canvas.drawLine(x0, y0, x0, y0 + rowH, gridStroke)
                val alpha = if (cell.inMonth) 255 else 70
                dayPaint.color = if (col == 6) Color.argb(alpha, 200, 56, 48) else Color.argb(alpha, 48, 20, 20)
                canvas.drawText(cell.dayOfMonth.toString(), x0 + colW * 0.08f, y0 + rowH * 0.22f, dayPaint)
                if (
                    data.showDaySourceLabel &&
                    cell.inMonth &&
                    (cell.totalMs > 0L || cell.books.isNotEmpty())
                ) {
                    smallPaint.color = when (cell.sourceKind) {
                        "WEREAD" -> Color.rgb(47, 105, 76)
                        "MIXED" -> accent
                        else -> muted
                    }
                    smallPaint.textAlign = Paint.Align.RIGHT
                    val sourceLabel = when (cell.sourceKind) {
                        "WEREAD" -> "W"
                        "MIXED" -> "M"
                        else -> "N"
                    }
                    canvas.drawText(sourceLabel, x0 + colW * 0.91f, y0 + rowH * 0.18f, smallPaint)
                }

                if (cell.inMonth && cell.books.isNotEmpty()) {
                    val coverArea = RectF(
                        x0 + colW * 0.05f,
                        y0 + rowH * 0.31f,
                        x0 + colW * 0.95f,
                        y0 + rowH * 0.91f
                    )
                    drawCalendarBookStack(canvas, coverArea, cell.books, bodyFace)
                    if (cell.totalMs > 0L) {
                        drawCalendarOutlineDuration(
                            canvas,
                            compactDuration(cell.totalMs),
                            x0 + colW * 0.92f,
                            y0 + rowH * 0.88f,
                            bodyFace
                        )
                    }
                } else if (cell.inMonth && cell.eventCount > 0) {
                    if (data.showDurationOnlyLabel) {
                        smallPaint.color = muted
                        smallPaint.textAlign = Paint.Align.LEFT
                        canvas.drawText("仅时长", x0 + colW * 0.08f, y0 + rowH * 0.52f, smallPaint)
                    }
                    if (cell.totalMs > 0L) {
                        drawCalendarOutlineDuration(
                            canvas,
                            compactDuration(cell.totalMs),
                            x0 + colW * 0.92f,
                            y0 + rowH * 0.88f,
                            bodyFace
                        )
                    }
                }
            }
        }

        if (data.showFooterLabel) {
            val note = "${data.footerLabel} · ${coveredDays}天有封面"
            smallPaint.color = muted
            smallPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(note, marginX, h - h * 0.024f, smallPaint)
        }
        drawSourceCornerMark(canvas, w, h, sourceMark, 1f)
        return out
    }

    private fun drawRoundedCardCalendarWallpaper(context: Context, data: CalendarBuildData, s: AutoSettings, sourceMark: String): Bitmap {
        val size = resolveWallpaperSize(s)
        val w = size.width
        val h = size.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val bg = Color.rgb(247, 242, 234)
        val cardFill = Color.rgb(253, 251, 246)
        val ink = Color.rgb(34, 22, 20)
        val muted = Color.rgb(126, 105, 100)
        val accent = Color.rgb(185, 66, 58)
        canvas.drawColor(bg)

        val titleFace = resolveTypeface(context, s.titleFont, true)
        val bodyFace = resolveTypeface(context, s.bodyFont, false)
        val aspect = w / h.toFloat()
        val narrow = aspect < 0.68f
        val wide = aspect > 0.82f
        val marginX = w * if (narrow) 0.045f else 0.055f
        val top = h * if (narrow) 0.028f else 0.034f

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textSize = (w * if (narrow) 0.078f else 0.072f).coerceIn(48f, 116f)
            typeface = Typeface.create(titleFace, Typeface.BOLD)
        }
        val summaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textSize = (w * if (narrow) 0.027f else 0.024f).coerceIn(20f, 38f)
            typeface = Typeface.create(bodyFace, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
        }
        val weekPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textSize = (w * if (narrow) 0.028f else 0.026f).coerceIn(20f, 40f)
            typeface = Typeface.create(bodyFace, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textSize = (w * if (narrow) 0.046f else 0.04f).coerceIn(28f, 58f)
            typeface = Typeface.create(bodyFace, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val tinyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = muted
            textSize = (w * if (narrow) 0.019f else 0.016f).coerceIn(13f, 24f)
            typeface = Typeface.create(bodyFace, Typeface.NORMAL)
            textAlign = Paint.Align.RIGHT
        }
        val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = cardFill
            style = Paint.Style.FILL
        }
        val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(80, 84, 66, 62)
            style = Paint.Style.STROKE
            strokeWidth = (w * 0.0012f).coerceIn(1f, 2.2f)
        }
        val noDataStroke = Paint(cardStroke).apply {
            color = Color.argb(42, 84, 66, 62)
        }

        val monthText = calendarTitleLabel(data, s)
        canvas.drawText(monthText, marginX, top + titlePaint.textSize, titlePaint)

        val inMonthCells = data.cells.filter { it.inMonth }
        val totalDuration = inMonthCells.sumOf { it.totalMs }
        val activeDays = inMonthCells.count { it.totalMs > 0L }
        val coveredDays = inMonthCells.count { it.books.isNotEmpty() }
        val uniqueBooks = inMonthCells.flatMap { it.books }.distinctBy { calendarBookIdentity(it.title) }
        val avg = if (activeDays > 0) totalDuration / activeDays else 0L
        val summaryRight = w - marginX
        canvas.drawText("总时长 ${compactDuration(totalDuration)}", summaryRight, top + titlePaint.textSize * 0.50f, summaryPaint)
        if (!narrow) {
            summaryPaint.typeface = Typeface.create(bodyFace, Typeface.NORMAL)
            canvas.drawText("日均 ${compactDuration(avg)} · ${activeDays}天记录", summaryRight, top + titlePaint.textSize * 0.84f, summaryPaint)
            canvas.drawText("读过${uniqueBooks.size}本 · ${coveredDays}天封面", summaryRight, top + titlePaint.textSize * 1.18f, summaryPaint)
        } else {
            summaryPaint.typeface = Typeface.create(bodyFace, Typeface.NORMAL)
            canvas.drawText("${activeDays}天 · ${uniqueBooks.size}本", summaryRight, top + titlePaint.textSize * 0.88f, summaryPaint)
        }

        val gridLeft = marginX
        val gridRight = w - marginX
        val gridWidth = gridRight - gridLeft
        val gridTop = top + titlePaint.textSize + h * if (narrow) 0.045f else 0.052f
        val weekH = (h * if (narrow) 0.042f else 0.048f).coerceIn(34f, 70f)
        val rowCount = data.weekRows
        val colW = gridWidth / 7f
        val cellGap = colW * if (narrow) 0.08f else 0.10f
        val maxCardByWidth = colW - cellGap
        val cardScale = when {
            narrow -> 0.98f
            wide -> 0.92f
            else -> 0.95f
        }
        val cardSide = maxCardByWidth * cardScale
        val cardRadius = cardSide * 0.18f
        val rowGap = when {
            narrow -> cardSide * 0.22f
            wide -> cardSide * 0.34f
            else -> cardSide * 0.28f
        }
        val rowStep = cardSide + rowGap
        val contentH = weekH + cardSide + rowStep * (rowCount - 1)
        val availableBelowHeader = h - gridTop - h * 0.055f
        val compressedRowStep = if (contentH > availableBelowHeader && rowCount > 1) {
            ((availableBelowHeader - weekH - cardSide) / (rowCount - 1)).coerceAtLeast(cardSide * 1.06f)
        } else {
            rowStep
        }
        val firstRowTop = gridTop + weekH

        val weekdays = if (narrow) {
            listOf("M", "T", "W", "T", "F", "S", "S")
        } else {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        }
        weekdays.forEachIndexed { i, label ->
            weekPaint.color = if (i == 6) accent else muted
            canvas.drawText(label, gridLeft + colW * (i + 0.5f), gridTop + weekH * 0.62f, weekPaint)
        }

        for (r in 0 until rowCount) {
            val y0 = firstRowTop + compressedRowStep * r
            for (col in 0 until 7) {
                val idx = r * 7 + col
                if (idx !in data.cells.indices) continue
                val cell = data.cells[idx]
                val x0 = gridLeft + colW * col
                val cardLeft = x0 + (colW - cardSide) / 2f
                val cardTop = y0
                val card = RectF(cardLeft, cardTop, cardLeft + cardSide, cardTop + cardSide)
                val alpha = if (cell.inMonth) 255 else 62
                val book = if (cell.inMonth) cell.books.firstOrNull() else null
                if (book?.bitmap != null) {
                    drawRoundedFittedBitmap(canvas, book.bitmap, card, cardRadius, drawBorder = false)
                    val shade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(46, 0, 0, 0)
                        style = Paint.Style.FILL
                    }
                    canvas.drawRoundRect(card, cardRadius, cardRadius, shade)
                } else if (book != null) {
                    drawRoundedCalendarPlaceholder(canvas, card, book.title, bodyFace, cardRadius)
                } else if (cell.inMonth) {
                    cardPaint.color = Color.argb(if (cell.totalMs > 0L) 170 else 92, 253, 251, 246)
                    canvas.drawRoundRect(card, cardRadius, cardRadius, cardPaint)
                    canvas.drawRoundRect(card, cardRadius, cardRadius, if (cell.totalMs > 0L) cardStroke else noDataStroke)
                }

                if (data.showDaySourceLabel && cell.inMonth && (cell.totalMs > 0L || cell.books.isNotEmpty())) {
                    tinyPaint.color = when (cell.sourceKind) {
                        "WEREAD" -> Color.rgb(47, 105, 76)
                        "MIXED" -> accent
                        else -> muted
                    }
                    val sourceLabel = when (cell.sourceKind) {
                        "WEREAD" -> "W"
                        "MIXED" -> "M"
                        else -> "N"
                    }
                    canvas.drawText(sourceLabel, card.right - cardSide * 0.11f, card.top + cardSide * 0.19f, tinyPaint)
                }

                val dayText = cell.dayOfMonth.toString()
                dayPaint.color = when {
                    book != null -> Color.WHITE
                    col == 6 -> Color.argb(alpha, 200, 56, 48)
                    else -> Color.argb(alpha, 42, 24, 22)
                }
                val oldSize = dayPaint.textSize
                val oldAlign = dayPaint.textAlign
                if (book != null) {
                    dayPaint.textSize = oldSize * 1.16f
                    dayPaint.setShadowLayer(cardSide * 0.035f, 0f, cardSide * 0.012f, Color.argb(120, 0, 0, 0))
                } else {
                    dayPaint.clearShadowLayer()
                }
                val dayBase = card.centerY() - (dayPaint.descent() + dayPaint.ascent()) / 2f
                canvas.drawText(dayText, card.centerX(), dayBase, dayPaint)
                dayPaint.textSize = oldSize
                dayPaint.textAlign = oldAlign
                dayPaint.clearShadowLayer()
            }
        }

        val calendarBottom = firstRowTop + compressedRowStep * (rowCount - 1) + cardSide
        val featured = selectCalendarFeaturedBook(context, data, s)
        if (featured != null) {
            val remainingTop = calendarBottom + h * if (narrow) 0.018f else 0.024f
            val remainingBottom = h - h * 0.055f
            val remainingH = remainingBottom - remainingTop
            if (remainingH >= h * 0.095f) {
                drawCalendarFeaturedBookCard(
                    canvas = canvas,
                    area = RectF(marginX, remainingTop, w - marginX, remainingBottom),
                    featured = featured,
                    bodyFace = bodyFace,
                    compact = remainingH < h * 0.15f,
                    allowExcerpt = remainingH >= h * 0.19f
                )
            }
        }

        drawSourceCornerMark(canvas, w, h, sourceMark, 1f)
        return out
    }

    private fun selectCalendarFeaturedBook(context: Context, data: CalendarBuildData, s: AutoSettings): CalendarFeaturedBook? {
        val grouped = linkedMapOf<String, MutableList<CalendarCoverItem>>()
        data.cells
            .filter { it.inMonth }
            .flatMap { it.books }
            .forEach { book ->
                val key = calendarBookIdentity(book.title).ifBlank { book.path }
                grouped.getOrPut(key) { mutableListOf() } += book
            }
        if (grouped.isEmpty()) return null
        val selected = grouped.values
            .mapNotNull { books ->
                val representative = books.maxWithOrNull(
                    compareBy<CalendarCoverItem> { it.bitmap != null }
                        .thenBy { it.lastSeenAt }
                ) ?: return@mapNotNull null
                Triple(representative, books.sumOf { it.durationMs }, books.maxOf { it.lastSeenAt })
            }
            .let { candidates ->
                if (s.calendarFeatureBookRule == "LATEST") {
                    candidates.maxWithOrNull(compareBy<Triple<CalendarCoverItem, Long, Long>> { it.third }.thenBy { it.second })
                } else {
                    candidates.maxWithOrNull(compareBy<Triple<CalendarCoverItem, Long, Long>> { it.second }.thenBy { it.third })
                }
            }
            ?: return null
        val excerpt = runCatching {
            val keys = listOf(selected.first.path, selected.first.title).filter { it.isNotBlank() }
            val book = BookItem(
                bookId = selected.first.path.takeIf { it.isNotBlank() && !it.startsWith("/") },
                title = selected.first.title,
                author = selected.first.author,
                progress = null,
                status = selected.first.status,
                localKeys = keys
            )
            loadAutoBookNote(
                context = context,
                apiKey = WeReadClient.loadApiKey(context),
                book = book,
                fetchWeReadExcerpt = s.sourceMode == "WEREAD" || s.sourceMode == "MIXED",
                s = s
            ).trim().takeIf { it.isNotBlank() }
        }.getOrNull()
        return CalendarFeaturedBook(selected.first, selected.second, selected.third, excerpt)
    }

    private fun drawCalendarFeaturedBookCard(
        canvas: Canvas,
        area: RectF,
        featured: CalendarFeaturedBook,
        bodyFace: Typeface,
        compact: Boolean,
        allowExcerpt: Boolean
    ) {
        val h = area.height()
        val w = area.width()
        val radius = (h * 0.16f).coerceIn(18f, 42f)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 253, 251, 246)
            style = Paint.Style.FILL
        }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(58, 84, 66, 62)
            style = Paint.Style.STROKE
            strokeWidth = (w * 0.0012f).coerceIn(1f, 2.2f)
        }
        canvas.drawRoundRect(area, radius, radius, bg)
        canvas.drawRoundRect(area, radius, radius, stroke)

        val pad = (h * 0.13f).coerceIn(12f, 30f)
        val maxCoverH = (h - pad * 2f).coerceAtLeast(1f)
        val coverRatio = 0.68f
        val maxCoverW = (w * if (compact) 0.23f else 0.27f).coerceAtLeast(72f)
        var coverW = maxCoverW
        var coverH = coverW / coverRatio
        if (coverH > maxCoverH) {
            coverH = maxCoverH
            coverW = coverH * coverRatio
        }
        val heightCap = h * if (compact) 0.72f else 0.68f
        if (coverH > heightCap) {
            coverH = heightCap
            coverW = coverH * coverRatio
        }
        coverW = coverW.coerceAtMost(w * 0.30f)
        coverH = coverW / coverRatio
        val coverTop = area.top + pad
        val cover = RectF(area.left + pad, coverTop, area.left + pad + coverW, coverTop + coverH)
        if (featured.item.bitmap != null) {
            drawRoundedFittedBitmap(canvas, featured.item.bitmap, cover, cover.width() * 0.12f)
        } else {
            drawRoundedCalendarPlaceholder(canvas, cover, featured.item.title, bodyFace, cover.width() * 0.12f)
        }

        val textLeft = cover.right + pad
        val textRight = area.right - pad
        val textW = (textRight - textLeft).coerceAtLeast(80f)
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(42, 24, 22)
            textSize = (h * if (compact) 0.23f else 0.21f).coerceIn(22f, 46f)
            typeface = Typeface.create(bodyFace, Typeface.BOLD)
            textAlign = Paint.Align.LEFT
        }
        val metaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(116, 94, 88)
            textSize = (h * if (compact) 0.15f else 0.14f).coerceIn(15f, 28f)
            typeface = Typeface.create(bodyFace, Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
        }
        val excerptPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(86, 68, 63)
            textSize = (h * 0.13f).coerceIn(14f, 25f)
            typeface = Typeface.create(bodyFace, Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
        }

        var y = area.top + pad + titlePaint.textSize
        drawFittedText(canvas, featured.item.title, textLeft, y, titlePaint, textW, Paint.Align.LEFT, 0.68f)
        y += titlePaint.textSize * 0.92f
        val status = when (featured.item.status) {
            2 -> "读完"
            0 -> "未读"
            else -> "在读"
        }
        val author = featured.item.author?.takeIf { it.isNotBlank() } ?: "未知作者"
        val meta = "$author · ${compactDuration(featured.durationMs)} · $status"
        drawFittedText(canvas, meta, textLeft, y, metaPaint, textW, Paint.Align.LEFT, 0.76f)
        y += metaPaint.textSize * 1.48f

        val excerpt = featured.excerpt?.trim().orEmpty()
        if (allowExcerpt && excerpt.isNotBlank()) {
            val availableTextH = area.bottom - pad * 0.55f - y
            val lineH = excerptPaint.textSize * 1.34f
            val maxLines = when {
                availableTextH >= lineH * 3.1f -> 3
                availableTextH >= lineH * 2.05f -> 2
                availableTextH >= lineH * 1.05f -> 1
                else -> 0
            }
            if (maxLines > 0) {
                drawMultiLineText(canvas, "摘：$excerpt", textLeft, y, excerptPaint, textW, lineH, maxLines)
            }
        }
    }

    private fun calendarTitleLabel(data: CalendarBuildData, s: AutoSettings): String {
        return if (s.periodMode == "LAST_30_DAYS") {
            val fmt = SimpleDateFormat("M/d", Locale.US)
            "${fmt.format(Date(data.monthStartMs))}-${fmt.format(Date(data.monthEndMs))}"
        } else {
            SimpleDateFormat("M/yyyy", Locale.US).format(Date(data.monthStartMs))
        }
    }

    private fun drawCalendarBookStack(canvas: Canvas, area: RectF, books: List<CalendarCoverItem>, bodyFace: Typeface) {
        val count = books.size.coerceIn(1, 4)
        val gap = area.width() * 0.025f
        val maxCoverH = area.height()
        val singleW = (maxCoverH * 0.66f).coerceAtMost(area.width() * 0.86f)
        val coverW = when (count) {
            1 -> singleW
            2 -> (area.width() * 0.57f).coerceAtMost(maxCoverH * 0.66f)
            else -> (area.width() * 0.47f).coerceAtMost(maxCoverH * 0.66f)
        }
        val coverH = (coverW / 0.66f).coerceAtMost(maxCoverH)
        val startX = when (count) {
            1 -> area.left + (area.width() - coverW) / 2f
            2 -> area.left + area.width() * 0.07f
            else -> area.left + area.width() * 0.02f
        }
        books.take(count).forEachIndexed { i, book ->
            val offset = i * (coverW * 0.3f + gap)
            val topOffset = if (i % 2 == 0) 0f else area.height() * 0.045f
            val left = (startX + offset).coerceAtMost(area.right - coverW)
            val rect = RectF(left, area.top + topOffset, left + coverW, area.top + topOffset + coverH)
            if (book.bitmap != null) {
                drawFittedBitmap(canvas, book.bitmap, rect)
            } else {
                drawCalendarPlaceholder(canvas, rect, book.title, bodyFace)
            }
            val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(115, 40, 28, 26)
                style = Paint.Style.STROKE
                strokeWidth = 1.4f
            }
            canvas.drawRect(rect, border)
        }
    }

    private fun drawCalendarOutlineDuration(canvas: Canvas, label: String, right: Float, baseline: Float, bodyFace: Typeface) {
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 34f
            typeface = Typeface.create(bodyFace, Typeface.BOLD)
            textAlign = Paint.Align.RIGHT
            style = Paint.Style.STROKE
            strokeWidth = 5.2f
        }
        val fill = Paint(stroke).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            strokeWidth = 0f
        }
        canvas.drawText(label, right, baseline, stroke)
        canvas.drawText(label, right, baseline, fill)
    }

    private fun drawFittedBitmap(canvas: Canvas, bitmap: Bitmap, dst: RectF) {
        val srcRatio = bitmap.width / bitmap.height.toFloat()
        val dstRatio = dst.width() / dst.height()
        val src = if (srcRatio > dstRatio) {
            val newW = (bitmap.height * dstRatio).toInt()
            val left = (bitmap.width - newW) / 2
            android.graphics.Rect(left, 0, left + newW, bitmap.height)
        } else {
            val newH = (bitmap.width / dstRatio).toInt()
            val top = (bitmap.height - newH) / 2
            android.graphics.Rect(0, top, bitmap.width, top + newH)
        }
        canvas.drawBitmap(bitmap, src, dst, null)
    }

    private fun drawRoundedFittedBitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        dst: RectF,
        radius: Float,
        drawBorder: Boolean = true
    ) {
        val path = Path().apply { addRoundRect(dst, radius, radius, Path.Direction.CW) }
        val save = canvas.save()
        canvas.clipPath(path)
        drawFittedBitmap(canvas, bitmap, dst)
        canvas.restoreToCount(save)
        if (!drawBorder) return
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(90, 44, 34, 32)
            style = Paint.Style.STROKE
            strokeWidth = (dst.width() * 0.018f).coerceIn(1f, 2.4f)
        }
        canvas.drawRoundRect(dst, radius, radius, border)
    }

    private fun drawRoundedCalendarPlaceholder(canvas: Canvas, rect: RectF, title: String, bodyFace: Typeface, radius: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(246, 243, 238)
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(rect, radius, radius, fill)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(76, 60, 56)
            textSize = (rect.width() * 0.18f).coerceIn(11f, 22f)
            typeface = Typeface.create(bodyFace, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val short = shortTitle(title, 4)
        val base = rect.centerY() - (text.descent() + text.ascent()) / 2f
        canvas.drawText(short, rect.centerX(), base, text)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 44, 34, 32)
            style = Paint.Style.STROKE
            strokeWidth = 1.2f
        }
        canvas.drawRoundRect(rect, radius, radius, border)
    }

    private fun drawCalendarPlaceholder(canvas: Canvas, rect: RectF, title: String, bodyFace: Typeface) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(45, 32, 30)
            textSize = (rect.width() * 0.17f).coerceIn(12f, 26f)
            typeface = Typeface.create(bodyFace, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawRect(rect, fill)
        val short = shortTitle(title, 4)
        val base = rect.centerY() - (text.descent() + text.ascent()) / 2f
        canvas.drawText(short, rect.centerX(), base, text)
    }

    private fun compactDuration(ms: Long): String {
        val minutes = (ms / 60_000L).coerceAtLeast(0L)
        return if (minutes >= 60L) {
            val h = minutes / 60L
            val m = minutes % 60L
            if (m == 0L) "${h}h" else "${h}h${m}m"
        } else {
            "${minutes}m"
        }
    }

    private fun readSettings(context: Context): AutoSettings {
        val p = context.getSharedPreferences(AutoRefreshConfig.PREFS_NAME, Context.MODE_PRIVATE)
        return AutoSettings(
            includeUnread = p.getBoolean("include_unread", false),
            showChart = p.getBoolean("show_chart", true),
            showProgressStatus = p.getBoolean("show_progress_status", true),
            showAuthor = p.getBoolean("show_author", true),
            showBookDuration = p.getBoolean("show_book_duration", true),
            minDurationMinutes = p.getInt("min_duration_minutes", 1).coerceAtLeast(0),
            topN = p.getInt("top_n", 5).coerceIn(1, 5),
            periodMode = p.getString("period_mode", "THIS_WEEK") ?: "THIS_WEEK",
            weekStart = p.getString("week_start", currentWeekStartYmd()) ?: currentWeekStartYmd(),
            weekEnd = p.getString("week_end", currentWeekEndYmd()) ?: currentWeekEndYmd(),
            readingFilterMode = p.getString("reading_filter_mode", "ALL") ?: "ALL",
            sourceMode = p.getString("source_mode", "DURATION") ?: "DURATION",
            wallpaperMode = p.getString("wallpaper_mode", "STATS") ?: "STATS",
            statsTemplate = p.getString("stats_template", "RECEIPT") ?: "RECEIPT",
            calendarTemplate = p.getString("calendar_template", "CLASSIC") ?: "CLASSIC",
            calendarFeatureBookRule = p.getString("calendar_feature_book_rule", "LONGEST") ?: "LONGEST",
            calendarStackOrder = p.getString("calendar_stack_order", "LONGEST_TOP") ?: "LONGEST_TOP",
            coverFitMode = p.getString("cover_fit_mode", "FIT") ?: "FIT",
            progressMode = p.getString("progress_mode", "PAGES") ?: "PAGES",
            timeUnit = p.getString("time_unit", "HOUR") ?: "HOUR",
            receiptTitle = p.getString("receipt_title", "阅读账单") ?: "阅读账单",
            receiptTitleSize = p.getFloat("receipt_title_size", 74f).coerceIn(24f, 120f),
            receiptBodySize = p.getFloat("receipt_body_size", 34f).coerceIn(18f, 60f),
            serialNumberMode = p.getString("serial_number_mode", "DATE") ?: "DATE",
            serialNumberCustom = (p.getString("serial_number_custom", "") ?: "").filter { it.isDigit() }.take(12),
            serialNumberSize = p.getFloat("serial_number_size", 46f).coerceIn(24f, 140f),
            booxDevicePreset = p.getString("boox_device_preset", BooxDevicePresets.DEFAULT_KEY) ?: BooxDevicePresets.DEFAULT_KEY,
            customWallpaperWidth = p.getInt("custom_wallpaper_width", BooxDevicePresets.byKey(BooxDevicePresets.DEFAULT_KEY).widthPx).coerceIn(300, 4000),
            customWallpaperHeight = p.getInt("custom_wallpaper_height", BooxDevicePresets.byKey(BooxDevicePresets.DEFAULT_KEY).heightPx).coerceIn(300, 4000),
            footerMode = p.getString("footer_mode", "NONE") ?: "NONE",
            barcodeWidthScale = p.getFloat("barcode_width_scale", 1.0f).coerceIn(0.6f, 1.6f),
            barcodeGapMode = p.getString("barcode_gap_mode", "STANDARD") ?: "STANDARD",
            noteText = p.getString("note_text", "") ?: "",
            bookNoteMode = p.getString("book_note_mode", "AUTO") ?: "AUTO",
            footerNoteSource = p.getString("footer_note_source", "CUSTOM") ?: "CUSTOM",
            footerShowBookTitle = p.getBoolean("footer_show_book_title", true),
            excerptSourceMode = p.getString("excerpt_source_mode", "PERSONAL_ONLY") ?: "PERSONAL_ONLY",
            chartStyleMode = p.getString("chart_style_mode", "LINE") ?: "LINE",
            showPeakLabel = p.getBoolean("show_peak_label", true),
            yAxisMode = p.getString("y_axis_mode", "AUTO") ?: "AUTO",
            yAxisFixedMaxMinutes = p.getInt("y_axis_fixed_max_minutes", 300).coerceIn(1, 2000),
            titleFont = p.getString("title_font", "SERIF_BOLD") ?: "SERIF_BOLD",
            bodyFont = p.getString("body_font", "MONO") ?: "MONO"
        )
    }

    private fun draw(
        context: Context,
        rangeStart: Long,
        rangeEnd: Long,
        stats: ChartStats,
        books: List<BookItem>,
        s0: AutoSettings,
        sourceMark: String
    ): Bitmap {
        val wallpaperSize = resolveWallpaperSize(s0)
        val w = wallpaperSize.width
        val h = wallpaperSize.height
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)

        val excerptMenu = s0.statsTemplate == "EXCERPT_MENU"
        val readingMenu = s0.statsTemplate == "READING_MENU"
        val noteTemplate = excerptMenu || readingMenu
        val footerNoteText = resolveFooterNote(context, "", books, s0)
        val barcodeSeed = footerNoteText.ifBlank {
            "ReadTrace|${fmt(rangeStart)}|${fmt(rangeEnd)}|${stats.totalMs}|${books.joinToString("|") { it.title }}"
        }
        val maxExcerptLines = when {
            !noteTemplate -> 0
            readingMenu && books.size <= 5 -> 2
            books.size <= 3 -> 4
            books.size <= 5 -> 3
            else -> 2
        }
        val bookLines = if (noteTemplate) {
            books.sumOf { (80f + if (!it.latestExcerptText.isNullOrBlank()) (54f + 42f * (maxExcerptLines - 1)) else 0f).toDouble() }.toFloat()
        } else {
            books.size * (80f + (if (s0.showAuthor) 42f else 0f) + (if (s0.showProgressStatus) 50f else 0f))
        }
        val headerBlock = 110f + 30f + 250f + 48f + 28f
        val hasFooterNote = footerNoteText.isNotBlank() && s0.footerMode != "BARCODE_ONLY"
        val hasFooterBarcode = s0.footerMode == "BARCODE" || s0.footerMode == "BARCODE_ONLY"
        val hasFooter = s0.footerMode != "NONE" && (hasFooterNote || hasFooterBarcode)
        val hasExcerptMenuBarcodeFooter = excerptMenu && s0.footerMode == "BARCODE" && hasFooter
        val summaryBlock = if (hasExcerptMenuBarcodeFooter) 30f + 52f + 54f + 30f else 30f + 60f + 50f
        val drawChart = s0.showChart && !excerptMenu
        val chartBlock = if (drawChart) 260f else 0f
        val footerBlock = if (!hasFooter) 0f else if (hasFooterBarcode) {
            if (excerptMenu) 130f else 280f
        } else 130f
        val requiredH = headerBlock + bookLines + summaryBlock + chartBlock + footerBlock + 120f
        val fitScale = (h.toFloat() - 40f) / requiredH
        val gs = fitScale.coerceIn(0.52f, 1f)
        fun s(v: Float): Float = v * gs

        val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val titleFace = resolveTypeface(context, s0.titleFont, true)
        val bodyFace = resolveTypeface(context, s0.bodyFont, false)
        val titlePaint = Paint(black).apply { textSize = s(s0.receiptTitleSize); typeface = titleFace }
        val h1 = Paint(black).apply { textSize = s((s0.receiptBodySize * 1.35f).coerceIn(24f, 90f)); typeface = Typeface.create(bodyFace, Typeface.BOLD) }
        val serialNumberPaint = Paint(black).apply { textSize = s(s0.serialNumberSize); typeface = Typeface.create(bodyFace, Typeface.BOLD) }
        val text = Paint(black).apply { textSize = s(s0.receiptBodySize); typeface = bodyFace }
        val mono = Paint(black).apply { textSize = s((s0.receiptBodySize * 0.88f).coerceIn(16f, 56f)); typeface = bodyFace }
        val excerptPaint = Paint(black).apply { textSize = s((s0.receiptBodySize * 0.98f).coerceIn(18f, 64f)); typeface = bodyFace }
        val line = Paint(black).apply { strokeWidth = s(3f) }

        val leftMargin = s(60f)
        val rightMargin = s(60f)
        val rightEdge = w - rightMargin
        val summaryWidth = maxOf(s(380f), w * 0.46f)
            .coerceAtMost((w - leftMargin - rightMargin - s(280f)).coerceAtLeast(w * 0.34f))
        val summaryLeft = rightEdge - summaryWidth
        val leftInfoMaxWidth = (summaryLeft - leftMargin - s(24f)).coerceAtLeast(w * 0.35f)
        val noX = leftMargin
        val titleX = s(260f)
        val qtyX = w - s(260f)
        val unitX = w - s(140f)
        val chefX = w - s(245f)
        val priceX = w - s(85f)
        val titleColumnMaxWidth = if (noteTemplate) {
            (chefX - titleX - s(52f)).coerceAtLeast(s(160f))
        } else {
            (qtyX - titleX - s(28f)).coerceAtLeast(s(160f))
        }
        val excerptColumnMaxWidth = if (noteTemplate) {
            (chefX - titleX - s(52f)).coerceAtLeast(s(160f))
        } else {
            (rightEdge - titleX).coerceAtLeast(s(180f))
        }

        var y = s(110f)
        drawFittedText(c, s0.receiptTitle, rightEdge, y, titlePaint, summaryWidth, Paint.Align.RIGHT, 0.62f)
        y += s(30f)
        val serialBaseY = y + s(40f)
        val serialPrefix = "单号: "
        val serialValue = resolveSerialNumber(s0)
        c.drawText(serialPrefix, leftMargin, serialBaseY, h1)
        val prefixWidth = h1.measureText(serialPrefix)
        drawFittedText(c, serialValue, leftMargin + prefixWidth, serialBaseY, serialNumberPaint, (summaryLeft - leftMargin - prefixWidth - s(24f)).coerceAtLeast(s(180f)), Paint.Align.LEFT, 0.7f)
        drawFittedText(c, "操作编号: ${System.currentTimeMillis().toString().takeLast(6)}", leftMargin, y + s(95f), text, leftInfoMaxWidth, Paint.Align.LEFT, 0.78f)
        drawFittedText(c, "时间: ${fmt(rangeStart)} - ${fmt(rangeEnd)}", leftMargin, y + s(145f), text, leftInfoMaxWidth, Paint.Align.LEFT, 0.78f)
        drawFittedText(c, "设备: ${wallpaperSize.label}", leftMargin, y + s(195f), text, leftInfoMaxWidth, Paint.Align.LEFT, 0.78f)
        drawFittedText(c, "时长: ${formatDuration(stats.totalMs, s0.timeUnit)}", rightEdge, y + s(145f), h1, summaryWidth, Paint.Align.RIGHT, 0.76f)
        drawFittedText(c, "书籍: ${books.size}", rightEdge, y + s(195f), text, summaryWidth, Paint.Align.RIGHT, 0.78f)

        y += s(250f)
        c.drawLine(s(40f), y, w - s(40f), y, line)
        y += s(48f)
        if (readingMenu) drawFittedText(c, "品类", titleX, y, text, titleColumnMaxWidth, Paint.Align.LEFT, 0.8f) else c.drawText("品类", noX, y, text)
        if (noteTemplate) {
            drawFittedText(c, "主厨", chefX, y, text, s(190f), Paint.Align.CENTER, 0.8f)
            if (excerptMenu) drawFittedText(c, "价格", priceX, y, text, s(90f), Paint.Align.CENTER, 0.8f)
        } else {
            drawFittedText(c, "数量", qtyX, y, text, s(84f), Paint.Align.CENTER, 0.8f)
            drawFittedText(c, "单位", unitX, y, text, s(84f), Paint.Align.CENTER, 0.8f)
        }
        y += s(28f)
        c.drawLine(s(40f), y, w - s(40f), y, line)
        cacheBookOrder(context, books)

        books.forEachIndexed { idx, b ->
            y += s(80f)
            c.drawText("NO.${(idx + 1).toString().padStart(2, '0')}", noX, y, h1)
            drawFittedText(c, b.title, titleX, y, h1, titleColumnMaxWidth, Paint.Align.LEFT, 0.68f)
            if (noteTemplate) {
                drawFittedText(c, b.author ?: "未知", chefX, y, h1, s(215f), Paint.Align.CENTER, 0.62f)
                if (excerptMenu) drawFittedText(c, b.menuPriceText ?: "¥1", priceX, y, h1, s(90f), Paint.Align.CENTER, 0.72f)
                val excerpt = b.latestExcerptText?.trim().orEmpty()
                if (excerpt.isNotBlank()) {
                    y += s(58f)
                    val prefix = if (readingMenu && s0.bookNoteMode == "MANUAL") "" else "摘："
                    y = drawMultiLineText(c, "$prefix$excerpt", titleX, y, excerptPaint, excerptColumnMaxWidth, s(42f), maxExcerptLines)
                }
            } else {
                drawFittedText(c, "1", qtyX, y, h1, s(84f), Paint.Align.CENTER, 0.8f)
                drawFittedText(c, "本", unitX, y, h1, s(84f), Paint.Align.CENTER, 0.8f)
                if (s0.showAuthor) {
                    y += s(42f)
                    drawFittedText(c, "作者:${b.author ?: "未知"}", titleX, y, mono, (rightEdge - titleX).coerceAtLeast(s(180f)), Paint.Align.LEFT, 0.78f)
                }
                if (s0.showProgressStatus) {
                    y += s(50f)
                    val st = when (b.status) { 2 -> "已读完"; 1 -> "阅读中"; else -> "未读" }
                    val value = b.progressText ?: formatProgress(b.progress, s0.progressMode)
                    val duration = if (s0.showBookDuration && !b.durationText.isNullOrBlank()) "  时长:${b.durationText}" else ""
                    drawFittedText(c, "进度:$value  状态:$st$duration", titleX, y, mono, (rightEdge - titleX).coerceAtLeast(s(180f)), Paint.Align.LEFT, 0.78f)
                }
            }
        }

        y += s(30f)
        c.drawLine(s(40f), y, w - s(40f), y, line)
        if (hasExcerptMenuBarcodeFooter) {
            y += s(52f)
            if (hasFooterNote) drawFittedText(c, "整单备注：$footerNoteText", leftMargin, y, mono, (rightEdge - leftMargin), Paint.Align.LEFT, 0.72f)
            if (excerptMenu) {
                y += s(54f)
                drawFittedText(c, "账单合计: ${menuTotalPriceText(books)}", rightEdge, y, h1, (w * 0.56f), Paint.Align.RIGHT, 0.66f)
            }
        } else if (excerptMenu) {
            y += s(60f)
            drawFittedText(c, "账单合计: ${menuTotalPriceText(books)}", rightEdge, y, h1, (w * 0.54f), Paint.Align.RIGHT, 0.72f)
        } else {
            y += s(60f)
            val avgDiv = stats.points.size.coerceAtLeast(1)
            drawFittedText(c, "日均: ${String.format(Locale.US, "%.0f分钟", stats.totalMs / avgDiv.toDouble() / 60000.0)}", leftMargin, y, h1, (w * 0.38f), Paint.Align.LEFT, 0.72f)
            drawFittedText(c, "本期合计: ${formatDuration(stats.totalMs, s0.timeUnit)}", rightEdge, y, h1, (w * 0.54f), Paint.Align.RIGHT, 0.72f)
        }

        y += if (hasExcerptMenuBarcodeFooter) s(26f) else s(50f)
        val footerReserved = if (!hasFooter) 0f else if (hasFooterBarcode) {
            if (excerptMenu) s(118f) else s(260f)
        } else s(120f)
        val bottomSafe = (h - s(56f)).toFloat()
        val availableChartH = ((bottomSafe - footerReserved - s(24f)) - y).coerceAtLeast(s(70f))
        val maxChartBottom = y + availableChartH
        var chartBottomUsed = y

        if (drawChart) {
            val chartLeft = s(80f)
            val chartRight = (w - s(80f)).toFloat()
            val chartTop = y
            val desiredChartH = s(220f)
            val chartBottom = (chartTop + desiredChartH).coerceAtMost(maxChartBottom)
            chartBottomUsed = chartBottom

            val autoMax = (stats.points.maxOrNull() ?: 1L).toFloat().coerceAtLeast(1f)
            val max = if (s0.yAxisMode == "FIXED") (s0.yAxisFixedMaxMinutes * 60000f).coerceAtLeast(1f) else autoMax
            val peakIdx = stats.points.indices.maxByOrNull { stats.points[it] } ?: 0

            c.drawLine(chartLeft, chartBottom, chartRight, chartBottom, line)
            c.drawLine(chartLeft, chartTop, chartLeft, chartBottom, line)
            var prevX = 0f
            var prevY = 0f
            val n = stats.points.size.coerceAtLeast(1)

            for (i in 0 until n) {
                val x = if (n == 1) (chartLeft + chartRight) / 2f else chartLeft + i * (chartRight - chartLeft) / (n - 1).toFloat()
                val yv = chartBottom - ((stats.points[i] / max) * (chartBottom - chartTop))
                if (s0.chartStyleMode == "BAR") {
                    val bw = (s(24f)).coerceAtMost((chartRight - chartLeft) / n * 0.7f)
                    c.drawRect(x - bw / 2f, yv, x + bw / 2f, chartBottom, black)
                } else {
                    c.drawCircle(x, yv, s(5f), black)
                    if (i > 0) c.drawLine(prevX, prevY, x, yv, line)
                }
                val label = stats.labels.getOrNull(i) ?: ""
                if (shouldShowLabel(i, n)) {
                    c.drawText(label, x - s((label.length * 8).toFloat()), chartBottom + s(42f), mono)
                }
                if (s0.showPeakLabel && i == peakIdx) {
                    c.drawText(String.format(Locale.US, "%.0f分", stats.points[i] / 60000.0), x - s(28f), yv - s(14f), mono)
                }
                prevX = x
                prevY = yv
            }
            y = chartBottom + s(56f)
        }

        if (hasFooter) {
            val baseY = if (drawChart) (chartBottomUsed + s(64f)) else if (hasExcerptMenuBarcodeFooter) y else (y + s(16f))
            c.drawLine(s(40f), baseY, w - s(40f), baseY, line)
            if (s0.footerMode == "NOTE") {
                if (hasFooterNote) drawFittedText(c, "备注: $footerNoteText", leftMargin, baseY + s(58f), text, (rightEdge - leftMargin), Paint.Align.LEFT, 0.78f)
            } else if (hasFooterBarcode) {
                val bottomSafeForFooter = (h - s(56f)).toFloat()
                val footerAreaTop = baseY + if (excerptMenu) s(28f) else s(18f)
                val footerAreaH = (bottomSafeForFooter - footerAreaTop - s(8f)).coerceAtLeast(s(56f))
                val qrSize = if (excerptMenu) {
                    val minQr = maxOf(72f, s(64f))
                    val maxQr = minOf(184f, w * 0.145f, footerAreaH * 0.9f).coerceAtLeast(minQr)
                    minOf(maxQr, footerAreaH * 0.82f).coerceAtLeast(minQr).toInt()
                } else {
                    s(168f).toInt().coerceAtLeast(120)
                }
                val qr = buildQrBitmap(barcodeSeed, qrSize)
                if (qr != null) {
                    if (excerptMenu) {
                        val gap = maxOf(s(24f), w * 0.018f)
                        val maxGroupW = (rightEdge - leftMargin).coerceAtLeast(s(260f))
                        val availableDecorW = (maxGroupW - qr.width - gap).coerceAtLeast(w * 0.32f)
                        val minDecorW = minOf(availableDecorW, maxOf(s(180f), w * 0.42f))
                        val decorW = minOf(availableDecorW, w * 0.62f).coerceAtLeast(minDecorW)
                        val groupW = qr.width + gap + decorW
                        val groupX = ((w - groupW) / 2f).coerceAtLeast(leftMargin)
                        val decorH = minOf(footerAreaH * 0.78f, qr.height * 1.02f, 152f)
                            .coerceAtLeast(maxOf(s(46f), minOf(footerAreaH * 0.52f, 68f)))
                        val decorX = groupX + qr.width + gap
                        val blockH = maxOf(qr.height.toFloat(), decorH)
                        val blockTop = footerAreaTop
                        val qrY = blockTop + ((blockH - qr.height) / 2f).coerceAtLeast(0f)
                        val decorY = blockTop + ((blockH - decorH) / 2f).coerceAtLeast(0f)
                        c.drawBitmap(qr, groupX, qrY, null)
                        drawBarcodeDecor(c, decorX, decorY, decorW, decorH, barcodeSeed, s0.barcodeWidthScale, s0.barcodeGapMode, black)
                    } else {
                        val qrX = s(60f)
                        val qrY = baseY + s(18f)
                        c.drawBitmap(qr, qrX, qrY, null)
                        val decorX = qrX + qr.width + s(24f)
                        val decorY = qrY + s(10f)
                        val decorW = (w - decorX - s(60f)).coerceAtLeast(s(220f))
                        val decorH = (qr.height - s(20f)).toFloat().coerceAtLeast(s(60f))
                        drawBarcodeDecor(c, decorX, decorY, decorW, decorH, barcodeSeed, s0.barcodeWidthScale, s0.barcodeGapMode, black)
                        val textY = qrY + qr.height + s(34f)
                        if (hasFooterNote) drawFittedText(c, footerNoteText, qrX, textY, mono, (rightEdge - qrX), Paint.Align.LEFT, 0.78f)
                    }
                } else {
                    if (hasFooterNote) drawFittedText(c, "二维码生成失败，备注: $footerNoteText", leftMargin, baseY + s(58f), text, (rightEdge - leftMargin), Paint.Align.LEFT, 0.78f)
                }
            }
        }

        drawSourceCornerMark(c, w, h, sourceMark, gs)
        return bmp
    }

    private fun drawFittedText(
        canvas: Canvas,
        raw: String,
        x: Float,
        y: Float,
        paint: Paint,
        maxWidth: Float,
        align: Paint.Align = Paint.Align.LEFT,
        minScale: Float = 0.72f
    ) {
        if (raw.isBlank() || maxWidth <= 0f) return
        val originalTextSize = paint.textSize
        val originalAlign = paint.textAlign
        val safeMinScale = minScale.coerceIn(0.45f, 1f)
        val minTextSize = originalTextSize * safeMinScale

        var fitted = raw
        if (paint.measureText(fitted) > maxWidth) {
            val ratio = (maxWidth / paint.measureText(fitted)).coerceIn(safeMinScale, 1f)
            paint.textSize = originalTextSize * ratio
            if (paint.textSize < minTextSize) paint.textSize = minTextSize
        }
        fitted = ellipsizeToWidth(fitted, paint, maxWidth)
        paint.textAlign = align
        canvas.drawText(fitted, x, y, paint)
        paint.textSize = originalTextSize
        paint.textAlign = originalAlign
    }

    private fun drawMultiLineText(
        canvas: Canvas,
        raw: String,
        x: Float,
        y: Float,
        paint: Paint,
        maxWidth: Float,
        lineHeight: Float,
        maxLines: Int
    ): Float {
        if (raw.isBlank() || maxWidth <= 0f || maxLines <= 0) return y
        val originalAlign = paint.textAlign
        paint.textAlign = Paint.Align.LEFT
        var rest = raw.trim()
        var lastY = y
        for (lineIndex in 0 until maxLines) {
            if (rest.isBlank()) break
            val isLastLine = lineIndex == maxLines - 1
            val line = if (isLastLine) {
                ellipsizeToWidth(rest, paint, maxWidth)
            } else {
                val split = takePrefixWithinWidth(rest, paint, maxWidth)
                rest = split.rest.trimStart()
                split.line
            }
            canvas.drawText(line, x, lastY, paint)
            if (rest.isNotBlank() && !isLastLine) lastY += lineHeight
        }
        paint.textAlign = originalAlign
        return lastY
    }

    private data class TextSplit(val line: String, val rest: String)

    private fun takePrefixWithinWidth(raw: String, paint: Paint, maxWidth: Float): TextSplit {
        if (raw.isEmpty() || paint.measureText(raw) <= maxWidth) return TextSplit(raw, "")
        var lo = 1
        var hi = raw.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (paint.measureText(raw.take(mid)) <= maxWidth) {
                lo = mid
            } else {
                hi = mid - 1
            }
        }
        val splitEnd = raw.take(lo).lastIndexOfAny(charArrayOf(' ', '，', '。', '、', '；', '：', ',', '.', ';', ':'))
            .takeIf { it >= 4 }
            ?.let { it + 1 }
            ?: lo
        return TextSplit(raw.take(splitEnd).trimEnd(), raw.drop(splitEnd).trimStart())
    }

    private fun ellipsizeToWidth(raw: String, paint: Paint, maxWidth: Float): String {
        if (raw.isEmpty() || paint.measureText(raw) <= maxWidth) return raw
        val ellipsis = "…"
        val ellipsisWidth = paint.measureText(ellipsis)
        if (ellipsisWidth >= maxWidth) return ellipsis
        var lo = 0
        var hi = raw.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            val candidate = raw.take(mid) + ellipsis
            if (paint.measureText(candidate) <= maxWidth) {
                lo = mid
            } else {
                hi = mid - 1
            }
        }
        return raw.take(lo).trimEnd() + ellipsis
    }

    private fun resolveSerialNumber(s: AutoSettings): String {
        return when (s.serialNumberMode) {
            "RANDOM" -> String.format(Locale.US, "%06d", Random.nextInt(0, 1_000_000))
            "CUSTOM" -> s.serialNumberCustom.ifBlank { SimpleDateFormat("MMdd", Locale.US).format(Date()) }
            else -> SimpleDateFormat("MMdd", Locale.US).format(Date())
        }
    }

    private fun queryStatsByMode(resolver: ContentResolver, start: Long, end: Long, s: AutoSettings): ChartStats {
        val events: List<Pair<Long, Long>> = when (s.sourceMode) {
            "PATH_SESSION" -> collectPathEvents(resolver, start, end)
            "METADATA_ACCESS" -> collectMetadataEvents(resolver, start, end)
            else -> collectDurationEvents(resolver, start, end, s.minDurationMinutes)
        }
        return bucketize(events, start, end, chooseBucketMode(s, start, end))
    }

    private fun collectDurationEvents(resolver: ContentResolver, start: Long, end: Long, minDurationMinutes: Int): List<Pair<Long, Long>> {
        val events = mutableListOf<Pair<Long, Long>>()
        val minMs = minDurationMinutes * 60_000L
        resolver.query(
            statsUri,
            arrayOf("eventTime", "durationTime"),
            "eventTime >= ? AND eventTime <= ? AND durationTime IS NOT NULL AND durationTime != '' AND durationTime != '0'",
            arrayOf(start.toString(), end.toString()),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val event = c.getString(c.getColumnIndexOrThrow("eventTime"))?.toLongOrNull() ?: continue
                val dur = c.getString(c.getColumnIndexOrThrow("durationTime"))?.toLongOrNull() ?: 0L
                if (dur < minMs) continue
                events.add(event to dur)
            }
        }
        return events
    }

    private fun collectPathEvents(resolver: ContentResolver, start: Long, end: Long): List<Pair<Long, Long>> {
        val events = mutableListOf<Pair<Long, Long>>()
        resolver.query(
            statsUri,
            arrayOf("eventTime"),
            "eventTime >= ? AND eventTime <= ? AND path IS NOT NULL AND path != ''",
            arrayOf(start.toString(), end.toString()),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val event = c.getString(c.getColumnIndexOrThrow("eventTime"))?.toLongOrNull() ?: continue
                events.add(event to 60_000L)
            }
        }
        return events
    }

    private fun collectMetadataEvents(resolver: ContentResolver, start: Long, end: Long): List<Pair<Long, Long>> {
        val events = mutableListOf<Pair<Long, Long>>()
        resolver.query(
            metadataUri,
            arrayOf("lastAccess"),
            "lastAccess >= ? AND lastAccess <= ?",
            arrayOf(start.toString(), end.toString()),
            null
        )?.use { c ->
            while (c.moveToNext()) {
                val event = c.getString(c.getColumnIndexOrThrow("lastAccess"))?.toLongOrNull() ?: continue
                events.add(event to 60_000L)
            }
        }
        return events
    }

    private fun chooseBucketMode(s: AutoSettings, start: Long, end: Long): BucketMode {
        val days = (((end - start) / DAY_MS) + 1L).toInt().coerceAtLeast(1)
        return when (s.periodMode) {
            "TODAY", "YESTERDAY" -> BucketMode.HOUR
            "THIS_WEEK", "LAST_WEEK", "LAST_7_DAYS" -> BucketMode.DAY
            "THIS_MONTH", "LAST_30_DAYS" -> BucketMode.DAY
            "CUSTOM" -> when {
                days <= 14 -> BucketMode.DAY
                days <= 90 -> BucketMode.WEEK
                else -> BucketMode.MONTH
            }
            else -> if (days <= 14) BucketMode.DAY else if (days <= 90) BucketMode.WEEK else BucketMode.MONTH
        }
    }

    private fun bucketize(events: List<Pair<Long, Long>>, start: Long, end: Long, mode: BucketMode): ChartStats {
        val values: LongArray
        val labels: List<String>

        when (mode) {
            BucketMode.HOUR -> {
                values = LongArray(24)
                labels = (0..23).map { "${it}时" }
                events.forEach { (ts, v) ->
                    if (ts in start..end) {
                        val c = Calendar.getInstance(TimeZone.getDefault())
                        c.timeInMillis = ts
                        values[c.get(Calendar.HOUR_OF_DAY)] += v
                    }
                }
            }
            BucketMode.DAY -> {
                val days = (((end - start) / DAY_MS) + 1L).toInt().coerceAtLeast(1)
                values = LongArray(days)
                labels = (0 until days).map {
                    SimpleDateFormat("MM-dd", Locale.US).format(Date(start + it * DAY_MS))
                }
                events.forEach { (ts, v) ->
                    if (ts in start..end) {
                        val idx = ((ts - start) / DAY_MS).toInt().coerceIn(0, days - 1)
                        values[idx] += v
                    }
                }
            }
            BucketMode.WEEK -> {
                val days = (((end - start) / DAY_MS) + 1L).toInt().coerceAtLeast(1)
                val n = ((days + 6) / 7).coerceAtLeast(1)
                values = LongArray(n)
                labels = (0 until n).map { i ->
                    val d = Date(start + i * 7L * DAY_MS)
                    "W${i + 1} ${SimpleDateFormat("MM-dd", Locale.US).format(d)}"
                }
                events.forEach { (ts, v) ->
                    if (ts in start..end) {
                        val idx = (((ts - start) / DAY_MS) / 7L).toInt().coerceIn(0, n - 1)
                        values[idx] += v
                    }
                }
            }
            BucketMode.MONTH -> {
                val sc = Calendar.getInstance(TimeZone.getDefault()); sc.timeInMillis = start
                val ec = Calendar.getInstance(TimeZone.getDefault()); ec.timeInMillis = end
                val sm = sc.get(Calendar.YEAR) * 12 + sc.get(Calendar.MONTH)
                val em = ec.get(Calendar.YEAR) * 12 + ec.get(Calendar.MONTH)
                val n = (em - sm + 1).coerceAtLeast(1)
                values = LongArray(n)
                labels = (0 until n).map { i ->
                    val c = Calendar.getInstance(TimeZone.getDefault())
                    c.timeInMillis = start
                    c.set(Calendar.DAY_OF_MONTH, 1)
                    c.add(Calendar.MONTH, i)
                    SimpleDateFormat("yyyy-MM", Locale.US).format(Date(c.timeInMillis))
                }
                events.forEach { (ts, v) ->
                    if (ts in start..end) {
                        val c = Calendar.getInstance(TimeZone.getDefault()); c.timeInMillis = ts
                        val cm = c.get(Calendar.YEAR) * 12 + c.get(Calendar.MONTH)
                        val idx = (cm - sm).coerceIn(0, n - 1)
                        values[idx] += v
                    }
                }
            }
        }

        return ChartStats(values.sum(), values, labels)
    }

    private fun shouldShowLabel(i: Int, n: Int): Boolean {
        val step = when {
            n <= 8 -> 1
            n <= 16 -> 2
            n <= 24 -> 3
            n <= 40 -> 5
            else -> 8
        }
        return i % step == 0 || i == n - 1
    }

    private fun queryTopBooks(
        resolver: ContentResolver,
        start: Long,
        end: Long,
        limit: Int,
        includeUnread: Boolean,
        filter: String
    ): List<BookItem> {
        val list = mutableListOf<BookItem>()
        val selection = buildString {
            append("lastAccess >= ? AND lastAccess <= ?")
            if (!includeUnread) append(" AND (readingStatus = 1 OR readingStatus = 2)")
            when (filter) {
                "READING_ONLY" -> append(" AND readingStatus = 1")
                "FINISHED_ONLY" -> append(" AND readingStatus = 2")
            }
        }
        resolver.query(
            metadataUri,
            arrayOf("title", "authors", "progress", "readingStatus", "idString", "digest", "hashTag", "uuid", "guid", "id", "nativeAbsolutePath"),
            selection,
            arrayOf(start.toString(), end.toString()),
            "readingStatus DESC, lastAccess DESC"
        )?.use { c ->
            while (c.moveToNext() && list.size < limit) {
                list.add(
                    BookItem(
                        null,
                        c.getString(c.getColumnIndexOrThrow("title")) ?: "未知书名",
                        c.getString(c.getColumnIndexOrThrow("authors")),
                        c.getString(c.getColumnIndexOrThrow("progress")),
                        c.getString(c.getColumnIndexOrThrow("readingStatus"))?.toIntOrNull() ?: 0,
                        localKeys = localBookKeys(c)
                    )
                )
            }
        }
        return list
    }

    private fun drawSourceCornerMark(canvas: Canvas, w: Int, h: Int, sourceMark: String, gs: Float) {
        val upper = sourceMark.uppercase(Locale.US)
        val label = when {
            upper.startsWith("A") -> "A"
            upper.startsWith("W") -> "W"
            else -> "M"
        }
        val radius = (11f * gs).coerceAtLeast(9f)
        val cx = w - (26f * gs)
        val cy = h - (24f * gs)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(105, 0, 0, 0)
            style = Paint.Style.STROKE
            strokeWidth = (1.4f * gs).coerceAtLeast(1f)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(12, 0, 0, 0)
            style = Paint.Style.FILL
        }
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 0, 0, 0)
            textSize = (12f * gs).coerceAtLeast(9f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawCircle(cx, cy, radius, fill)
        canvas.drawCircle(cx, cy, radius, ring)
        val baseline = cy - ((t.descent() + t.ascent()) / 2f)
        canvas.drawText(label, cx, baseline, t)
    }

    private fun buildQrBitmap(content: String, size: Int): Bitmap? {
        return runCatching {
            val hints = hashMapOf<EncodeHintType, Any>(EncodeHintType.CHARACTER_SET to "UTF-8")
            val compact = if (content.length > 120) content.take(120) else content
            val matrix: BitMatrix = MultiFormatWriter().encode(compact, BarcodeFormat.QR_CODE, size, size, hints)
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        }.getOrNull()
    }

    private fun drawBarcodeDecor(
        canvas: Canvas,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        seedText: String,
        widthScale: Float,
        gapMode: String,
        paint: Paint
    ) {
        val seed = seedText.hashCode().toLong()
        var state = if (seed == 0L) 1L else kotlin.math.abs(seed)
        var cursor = x
        val end = x + width
        val gapScale = when (gapMode) {
            "TIGHT" -> 0.75f
            "LOOSE" -> 1.35f
            else -> 1.0f
        }
        while (cursor < end) {
            state = (state * 1103515245 + 12345) and 0x7fffffff
            state = (state * 1103515245 + 12345) and 0x7fffffff
            val barW = ((1 + (state % 5)).toFloat() * widthScale).coerceAtLeast(0.8f)
            state = (state * 1103515245 + 12345) and 0x7fffffff
            val gapW = ((1 + (state % 4)).toFloat() * gapScale).coerceAtLeast(0.6f)
            canvas.drawRect(cursor, y, (cursor + barW).coerceAtMost(end), y + height, paint)
            cursor += barW + gapW
        }
    }

    private fun resolveTypeface(context: Context, spec: String, boldDefault: Boolean): Typeface {
        return when (spec) {
            "SERIF_BOLD" -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
            "SANS" -> Typeface.create(Typeface.SANS_SERIF, if (boldDefault) Typeface.BOLD else Typeface.NORMAL)
            "MONO" -> Typeface.create(Typeface.MONOSPACE, if (boldDefault) Typeface.BOLD else Typeface.NORMAL)
            else -> {
                try {
                    if (spec.startsWith("content://")) {
                        context.contentResolver.openFileDescriptor(Uri.parse(spec), "r")?.use { pfd ->
                            Typeface.Builder(pfd.fileDescriptor).build()
                        } ?: Typeface.create(Typeface.SANS_SERIF, if (boldDefault) Typeface.BOLD else Typeface.NORMAL)
                    } else {
                        Typeface.createFromFile(spec)
                    }
                } catch (_: Exception) {
                    Typeface.create(Typeface.SANS_SERIF, if (boldDefault) Typeface.BOLD else Typeface.NORMAL)
                }
            }
        }
    }

    private fun resolvePeriodRange(settings: AutoSettings): Pair<Long, Long>? {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        return when (settings.periodMode) {
            "TODAY" -> {
                val c = Calendar.getInstance(TimeZone.getDefault())
                c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                val start = c.timeInMillis
                c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59); c.set(Calendar.SECOND, 59); c.set(Calendar.MILLISECOND, 999)
                start to c.timeInMillis
            }
            "YESTERDAY" -> {
                val c = Calendar.getInstance(TimeZone.getDefault())
                c.add(Calendar.DAY_OF_MONTH, -1)
                c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
                val start = c.timeInMillis
                c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59); c.set(Calendar.SECOND, 59); c.set(Calendar.MILLISECOND, 999)
                start to c.timeInMillis
            }
            "THIS_WEEK" -> parseWeek(currentWeekStartYmd())
            "LAST_WEEK" -> {
                val c = Calendar.getInstance(TimeZone.getDefault())
                parseYmd(currentWeekStartYmd())?.let { c.timeInMillis = it } ?: return null
                c.add(Calendar.DAY_OF_MONTH, -7)
                parseWeek(SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(c.timeInMillis)))
            }
            "LAST_7_DAYS" -> {
                cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
                val end = cal.timeInMillis
                cal.add(Calendar.DAY_OF_MONTH, -6)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to end
            }
            "THIS_MONTH" -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                val start = cal.timeInMillis
                cal.add(Calendar.MONTH, 1)
                cal.add(Calendar.MILLISECOND, -1)
                start to cal.timeInMillis
            }
            "LAST_30_DAYS" -> {
                cal.set(Calendar.HOUR_OF_DAY, 23); cal.set(Calendar.MINUTE, 59); cal.set(Calendar.SECOND, 59); cal.set(Calendar.MILLISECOND, 999)
                val end = cal.timeInMillis
                cal.add(Calendar.DAY_OF_MONTH, -29)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to end
            }
            "CUSTOM" -> {
                val s = parseYmd(settings.weekStart) ?: return null
                val e = parseYmd(settings.weekEnd) ?: return null
                val sc = Calendar.getInstance(TimeZone.getDefault()); sc.timeInMillis = s
                sc.set(Calendar.HOUR_OF_DAY, 0); sc.set(Calendar.MINUTE, 0); sc.set(Calendar.SECOND, 0); sc.set(Calendar.MILLISECOND, 0)
                val ec = Calendar.getInstance(TimeZone.getDefault()); ec.timeInMillis = e
                ec.set(Calendar.HOUR_OF_DAY, 23); ec.set(Calendar.MINUTE, 59); ec.set(Calendar.SECOND, 59); ec.set(Calendar.MILLISECOND, 999)
                if (sc.timeInMillis > ec.timeInMillis) null else (sc.timeInMillis to ec.timeInMillis)
            }
            else -> parseWeek(currentWeekStartYmd())
        }
    }

    private fun parseWeek(startYmd: String): Pair<Long, Long>? {
        val s = parseYmd(startYmd) ?: return null
        val c = Calendar.getInstance(TimeZone.getDefault())
        c.timeInMillis = s
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val start = c.timeInMillis
        c.add(Calendar.DAY_OF_MONTH, 6)
        c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 59); c.set(Calendar.SECOND, 59); c.set(Calendar.MILLISECOND, 999)
        return start to c.timeInMillis
    }

    private fun parseYmd(ymd: String): Long? {
        return runCatching {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getDefault()
            sdf.parse(ymd)?.time
        }.getOrNull()
    }

    private fun currentWeekStartYmd(): String {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.firstDayOfWeek = Calendar.SUNDAY
        cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(cal.timeInMillis))
    }

    private fun currentWeekEndYmd(): String {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.firstDayOfWeek = Calendar.SUNDAY
        cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        cal.add(Calendar.DAY_OF_MONTH, 6)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(cal.timeInMillis))
    }

    private fun fmt(ts: Long): String = SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date(ts))

    private fun shortTitle(s: String, max: Int): String = if (s.length <= max) s else s.take(max - 1) + "…"

    private fun formatDuration(ms: Long, unit: String): String {
        if (unit == "MINUTE") return String.format(Locale.US, "%.0f分钟", ms / 60000.0)
        val totalMinutes = (ms / 60000L).coerceAtLeast(0L)
        val days = totalMinutes / (24L * 60L)
        val hours = (totalMinutes % (24L * 60L)) / 60L
        val minutes = totalMinutes % 60L
        return when {
            days > 0L -> "${days}天${hours}小时${minutes}分钟"
            hours > 0L -> "${hours}小时${minutes}分钟"
            else -> "${minutes}分钟"
        }
    }

    private fun formatProgress(raw: String?, mode: String): String {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return "-"
        if (mode != "PERCENT") return value
        val parts = value.split("/")
        if (parts.size != 2) return value
        val cur = parts[0].trim().toDoubleOrNull() ?: return value
        val total = parts[1].trim().toDoubleOrNull() ?: return value
        if (total <= 0.0) return value
        return String.format(Locale.US, "%.1f%%", (cur / total) * 100.0)
    }

    private fun saveBitmap(context: android.content.Context, bitmap: Bitmap, reason: String): String {
        val result = WallpaperFileStore.save(context, bitmap, reason)
        AutoRefreshLog.i(context, "Wallpaper save result ok=${result.ok} fallback=${result.fallback} path=${result.path} detail=${result.detail.take(180)}")
        if (!result.ok) error(result.detail)
        return result.path
    }
}
