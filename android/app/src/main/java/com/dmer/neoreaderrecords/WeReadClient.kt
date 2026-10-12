package com.dmer.neoreaderrecords

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object WeReadClient {
    private const val PREFS_NAME = "weread_settings"
    private const val KEY_API_KEY = "weread_api_key"
    private const val KEY_STATUS = "weread_status"
    private const val KEY_LAST_TEST_MS = "weread_last_test_ms"
    private const val KEY_ERROR = "weread_error"
    private const val KEY_LAST_COVER_BOOK_ID = "weread_last_cover_book_id"
    private const val KEY_LAST_COVER_TITLE = "weread_last_cover_title"
    private const val KEY_LAST_COVER_AUTHOR = "weread_last_cover_author"
    private const val KEY_LAST_COVER_URL = "weread_last_cover_url"
    private const val KEY_LAST_COVER_PATH = "weread_last_cover_path"
    private const val KEY_LAST_COVER_BYTES = "weread_last_cover_bytes"
    private const val API_GATEWAY = "https://i.weread.qq.com/api/agent/gateway"
    private const val SKILL_VERSION = "1.0.4"

    data class State(
        val maskedKey: String,
        val status: String,
        val lastTestMs: Long,
        val error: String
    )

    data class TestResult(
        val ok: Boolean,
        val status: String,
        val detail: String,
        val bookCount: Int,
        val albumCount: Int,
        val hasMp: Boolean
    )

    data class ReadStatsResult(
        val ok: Boolean,
        val status: String,
        val detail: String,
        val mode: String,
        val totalReadSeconds: Long,
        val dayAverageSeconds: Long,
        val readDays: Int,
        val topBooks: List<String>
    )

    data class CoverCacheResult(
        val ok: Boolean,
        val status: String,
        val detail: String,
        val bookId: String,
        val title: String,
        val author: String,
        val coverUrl: String,
        val cachePath: String,
        val bytes: Long,
        val fromCache: Boolean,
        val readUpdateTimeMs: Long = 0L
    )

    data class WallpaperBook(
        val bookId: String,
        val title: String,
        val author: String,
        val readSeconds: Long
    )

    data class LatestNoteResult(
        val ok: Boolean,
        val bookId: String,
        val text: String,
        val type: String,
        val createTimeMs: Long,
        val detail: String
    )

    data class ShelfBook(
        val bookKey: String,
        val title: String,
        val author: String,
        val coverUrl: String,
        val readUpdateTimeMs: Long,
        val status: Int
    )

    data class ShelfSnapshotResult(
        val ok: Boolean,
        val detail: String,
        val books: List<ShelfBook>
    )

    data class BookProgressResult(
        val ok: Boolean,
        val progressPercent: Int?,
        val recordReadingSeconds: Long,
        val updateTimeMs: Long,
        val detail: String
    )

    data class WallpaperStatsResult(
        val ok: Boolean,
        val status: String,
        val detail: String,
        val mode: String,
        val baseTimeSeconds: Long,
        val totalReadSeconds: Long,
        val readDays: Int,
        val buckets: List<Pair<Long, Long>>,
        val books: List<WallpaperBook>
    )

    fun loadApiKey(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_API_KEY, "")
            .orEmpty()
    }

    fun saveApiKey(context: Context, apiKey: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_API_KEY, apiKey.trim())
            .apply()
    }

    fun cachedState(context: Context): State {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return State(
            maskedKey = maskKey(p.getString(KEY_API_KEY, "").orEmpty()),
            status = p.getString(KEY_STATUS, "尚未测试") ?: "尚未测试",
            lastTestMs = p.getLong(KEY_LAST_TEST_MS, 0L),
            error = p.getString(KEY_ERROR, "") ?: ""
        )
    }

    fun maskKey(apiKey: String): String {
        val key = apiKey.trim()
        if (key.isBlank()) return "未配置"
        if (key.length <= 10) return "${key.take(3)}***"
        return "${key.take(6)}...${key.takeLast(4)}"
    }

    fun testConnection(context: Context, apiKey: String): TestResult {
        val key = apiKey.trim()
        if (key.isBlank()) {
            return saveFailure(context, "未配置 API Key")
        }
        AutoRefreshLog.i(context, "WeRead test start key=${maskKey(key)}")
        return try {
            val body = JSONObject()
                .put("api_name", "/shelf/sync")
                .put("skill_version", SKILL_VERSION)
                .toString()
            val result = postJson(key, body.toString())
            AutoRefreshLog.i(context, "WeRead test http code=${result.code} bytes=${result.body.length}")
            if (result.code !in 200..299) {
                return saveFailure(context, "HTTP ${result.code}: ${result.body.take(120)}")
            }
            val json = JSONObject(result.body)
            val upgradeInfo = json.optJSONObject("upgrade_info")
            if (upgradeInfo != null) {
                return saveFailure(context, "Skill 需要升级：${upgradeInfo.optString("message", "请升级 skill")}")
            }
            val errCode = json.optInt("errcode", 0)
            if (errCode != 0) {
                return saveFailure(context, "接口错误 errcode=$errCode ${json.optString("errmsg", "").take(80)}")
            }
            val books = json.optJSONArray("books")
            val albums = json.optJSONArray("albums")
            val mp = json.opt("mp")
            val bookCount = books?.length() ?: 0
            val albumCount = albums?.length() ?: 0
            val hasMp = mp != null && mp != JSONObject.NULL
            val total = bookCount + albumCount + if (hasMp) 1 else 0
            saveSuccess(
                context,
                TestResult(
                    ok = true,
                    status = "连接成功",
                    detail = "书架可见条目 $total 个：电子书 $bookCount，专辑/有声书 $albumCount，文章收藏 ${if (hasMp) "有" else "无"}",
                    bookCount = bookCount,
                    albumCount = albumCount,
                    hasMp = hasMp
                )
            )
        } catch (e: Exception) {
            AutoRefreshLog.e(context, "WeRead test failed", e)
            saveFailure(context, "${e.javaClass.simpleName}: ${e.message ?: "测试失败"}")
        }
    }

    fun fetchReadStats(context: Context, apiKey: String, mode: String): ReadStatsResult {
        val key = apiKey.trim()
        if (key.isBlank()) {
            return ReadStatsResult(false, "读取失败", "未配置 API Key", mode, 0L, 0L, 0, emptyList())
        }
        AutoRefreshLog.i(context, "WeRead stats start mode=$mode key=${maskKey(key)}")
        return try {
            val body = JSONObject()
                .put("api_name", "/readdata/detail")
                .put("mode", mode)
                .put("skill_version", SKILL_VERSION)
                .toString()
            val result = postJson(key, body.toString())
            AutoRefreshLog.i(context, "WeRead stats http mode=$mode code=${result.code} bytes=${result.body.length}")
            if (result.code !in 200..299) {
                return ReadStatsResult(false, "读取失败", "HTTP ${result.code}: ${result.body.take(120)}", mode, 0L, 0L, 0, emptyList())
            }
            val json = JSONObject(result.body)
            val upgradeInfo = json.optJSONObject("upgrade_info")
            if (upgradeInfo != null) {
                return ReadStatsResult(false, "读取失败", "Skill 需要升级：${upgradeInfo.optString("message", "请升级 skill")}", mode, 0L, 0L, 0, emptyList())
            }
            val errCode = json.optInt("errcode", 0)
            if (errCode != 0) {
                return ReadStatsResult(false, "读取失败", "接口错误 errcode=$errCode ${json.optString("errmsg", "").take(80)}", mode, 0L, 0L, 0, emptyList())
            }
            val total = json.optLong("totalReadTime", 0L)
            val average = json.optLong("dayAverageReadTime", 0L)
            val readDays = json.optInt("readDays", 0)
            val top = mutableListOf<String>()
            val longest = json.optJSONArray("readLongest")
            if (longest != null) {
                for (i in 0 until minOf(longest.length(), 5)) {
                    val item = longest.optJSONObject(i) ?: continue
                    val book = item.optJSONObject("book")
                    val album = item.optJSONObject("albumInfo")
                    val title = book?.optString("title")?.takeIf { it.isNotBlank() }
                        ?: album?.optString("name")?.takeIf { it.isNotBlank() }
                        ?: "未知条目"
                    val seconds = item.optLong("readTime", 0L)
                    top.add("${i + 1}. $title ${formatSeconds(seconds)}")
                }
            }
            val detail = "${modeLabel(mode)}：总时长 ${formatSeconds(total)}，阅读天数 ${readDays} 天，自然日均 ${formatSeconds(average)}" +
                if (top.isEmpty()) "" else "\n排行：${top.joinToString("；")}"
            AutoRefreshLog.i(context, "WeRead stats success mode=$mode total=$total readDays=$readDays top=${top.joinToString("|")}")
            ReadStatsResult(true, "读取成功", detail, mode, total, average, readDays, top)
        } catch (e: Exception) {
            AutoRefreshLog.e(context, "WeRead stats failed mode=$mode", e)
            ReadStatsResult(false, "读取失败", "${e.javaClass.simpleName}: ${e.message ?: "读取失败"}", mode, 0L, 0L, 0, emptyList())
        }
    }

    fun cacheLatestCover(context: Context, apiKey: String): CoverCacheResult {
        val key = apiKey.trim()
        if (key.isBlank()) {
            return CoverCacheResult(false, "缓存失败", "未配置 API Key", "", "", "", "", "", 0L, false)
        }
        AutoRefreshLog.i(context, "WeRead cover cache start key=${maskKey(key)}")
        return try {
            val body = JSONObject()
                .put("api_name", "/shelf/sync")
                .put("skill_version", SKILL_VERSION)
                .toString()
            val result = postJson(key, body)
            AutoRefreshLog.i(context, "WeRead cover shelf http code=${result.code} bytes=${result.body.length}")
            if (result.code !in 200..299) {
                return CoverCacheResult(false, "缓存失败", "HTTP ${result.code}: ${result.body.take(120)}", "", "", "", "", "", 0L, false)
            }
            val json = JSONObject(result.body)
            val upgradeInfo = json.optJSONObject("upgrade_info")
            if (upgradeInfo != null) {
                return CoverCacheResult(false, "缓存失败", "Skill 需要升级：${upgradeInfo.optString("message", "请升级 skill")}", "", "", "", "", "", 0L, false)
            }
            val errCode = json.optInt("errcode", 0)
            if (errCode != 0) {
                return CoverCacheResult(false, "缓存失败", "接口错误 errcode=$errCode ${json.optString("errmsg", "").take(80)}", "", "", "", "", "", 0L, false)
            }
            val books = json.optJSONArray("books")
                ?: return CoverCacheResult(false, "缓存失败", "书架未返回 books 数组", "", "", "", "", "", 0L, false)
            var latest: JSONObject? = null
            for (i in 0 until books.length()) {
                val book = books.optJSONObject(i) ?: continue
                val cover = book.optString("cover", "").trim()
                if (cover.isBlank()) continue
                val prior = latest
                if (prior == null || book.optLong("readUpdateTime", 0L) > prior.optLong("readUpdateTime", 0L)) {
                    latest = book
                }
            }
            val book = latest
                ?: return CoverCacheResult(false, "缓存失败", "书架没有可用封面字段", "", "", "", "", "", 0L, false)
            val bookId = book.optString("bookId", "").trim()
            val title = book.optString("title", "未知书籍").ifBlank { "未知书籍" }
            val author = book.optString("author", "未知作者").ifBlank { "未知作者" }
            val rawCoverUrl = book.optString("cover", "").trim()
            val readUpdateTimeMs = normalizeEpochMs(book.optLong("readUpdateTime", 0L))
            AutoRefreshLog.i(
                context,
                "WeRead cover latest selected title=$title author=$author bookId=$bookId readUpdateTime=${book.optLong("readUpdateTime", 0L)} readUpdateTimeMs=$readUpdateTimeMs"
            )
            val coverUrl = normalizeUrl(rawCoverUrl)
            if (bookId.isBlank() || coverUrl.isBlank()) {
                return CoverCacheResult(false, "缓存失败", "最新书籍缺少 bookId 或 cover", bookId, title, author, coverUrl, "", 0L, false)
            }
            val dir = File(context.cacheDir, "covers/weread")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "${safeFileName(bookId)}.jpg")
            val candidates = coverCandidates(rawCoverUrl, bookId)
            AutoRefreshLog.i(context, "WeRead cover candidates=${candidates.size} title=$title bookId=$bookId first=${candidates.firstOrNull()?.take(120).orEmpty()}")
            if (file.exists() && file.length() > 180_000L) {
                val detail = "缓存命中：$title / $author，${file.length()} bytes"
                AutoRefreshLog.i(context, "WeRead cover cache hit title=$title bookId=$bookId path=${file.absolutePath} bytes=${file.length()}")
                saveLatestCoverState(context, bookId, title, author, coverUrl, file.absolutePath, file.length())
                return CoverCacheResult(true, "缓存命中", detail, bookId, title, author, coverUrl, file.absolutePath, file.length(), true, readUpdateTimeMs)
            }
            for (candidate in candidates) {
                val bytes = runCatching { httpGetBytes(candidate) }
                    .onFailure { AutoRefreshLog.i(context, "WeRead cover candidate failed url=${candidate.take(120)} error=${it.javaClass.simpleName}:${it.message}") }
                    .getOrNull()
                    ?: continue
                FileOutputStream(file).use { it.write(bytes) }
                val detail = "已缓存：$title / $author，${bytes.size} bytes"
                AutoRefreshLog.i(context, "WeRead cover cached title=$title bookId=$bookId path=${file.absolutePath} bytes=${bytes.size} url=${candidate.take(120)}")
                saveLatestCoverState(context, bookId, title, author, candidate, file.absolutePath, bytes.size.toLong())
                return CoverCacheResult(true, "缓存成功", detail, bookId, title, author, candidate, file.absolutePath, bytes.size.toLong(), false, readUpdateTimeMs)
            }
            if (file.exists() && file.length() > 0L) {
                val detail = "高清封面拉取失败，沿用旧缓存：$title / $author，${file.length()} bytes"
                AutoRefreshLog.i(context, "WeRead cover fallback old cache title=$title bookId=$bookId path=${file.absolutePath} bytes=${file.length()}")
                saveLatestCoverState(context, bookId, title, author, coverUrl, file.absolutePath, file.length())
                return CoverCacheResult(true, "缓存命中", detail, bookId, title, author, coverUrl, file.absolutePath, file.length(), true, readUpdateTimeMs)
            }
            CoverCacheResult(false, "缓存失败", "所有封面候选地址都无法下载", bookId, title, author, coverUrl, "", 0L, false)
        } catch (e: Exception) {
            AutoRefreshLog.e(context, "WeRead cover cache failed", e)
            CoverCacheResult(false, "缓存失败", "${e.javaClass.simpleName}: ${e.message ?: "缓存失败"}", "", "", "", "", "", 0L, false)
        }
    }

    fun cachedLatestCover(context: Context): CoverCacheResult? {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val path = p.getString(KEY_LAST_COVER_PATH, "").orEmpty()
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.exists() || file.length() <= 0L) return null
        val bookId = p.getString(KEY_LAST_COVER_BOOK_ID, "").orEmpty()
        val title = p.getString(KEY_LAST_COVER_TITLE, "未知书籍") ?: "未知书籍"
        val author = p.getString(KEY_LAST_COVER_AUTHOR, "未知作者") ?: "未知作者"
        val coverUrl = p.getString(KEY_LAST_COVER_URL, "").orEmpty()
        val bytes = p.getLong(KEY_LAST_COVER_BYTES, file.length()).takeIf { it > 0L } ?: file.length()
        return CoverCacheResult(true, "缓存命中", "使用上次缓存：$title / $author，${file.length()} bytes", bookId, title, author, coverUrl, file.absolutePath, bytes, true)
    }

    fun fetchShelfSnapshot(context: Context, apiKey: String): ShelfSnapshotResult {
        val key = apiKey.trim()
        if (key.isBlank()) return ShelfSnapshotResult(false, "未配置 API Key", emptyList())
        return try {
            val body = JSONObject()
                .put("api_name", "/shelf/sync")
                .put("skill_version", SKILL_VERSION)
                .toString()
            val result = postJson(key, body)
            if (result.code !in 200..299) {
                return ShelfSnapshotResult(false, "HTTP ${result.code}: ${result.body.take(120)}", emptyList())
            }
            val json = JSONObject(result.body)
            val upgradeInfo = json.optJSONObject("upgrade_info")
            if (upgradeInfo != null) {
                return ShelfSnapshotResult(
                    false,
                    "Skill 需要升级：${upgradeInfo.optString("message", "请升级 skill")}",
                    emptyList()
                )
            }
            val errCode = json.optInt("errcode", 0)
            if (errCode != 0) {
                return ShelfSnapshotResult(
                    false,
                    "接口错误 errcode=$errCode ${json.optString("errmsg", "").take(80)}",
                    emptyList()
                )
            }
            val out = mutableListOf<ShelfBook>()
            json.optJSONArray("books")?.let { books ->
                for (i in 0 until books.length()) {
                    val book = books.optJSONObject(i) ?: continue
                    val bookId = book.optString("bookId", "").trim()
                    if (bookId.isBlank()) continue
                    out.add(
                        ShelfBook(
                            bookKey = bookId,
                            title = book.optString("title", "未知书籍").ifBlank { "未知书籍" },
                            author = book.optString("author", "未知作者").ifBlank { "未知作者" },
                            coverUrl = book.optString("cover", "").trim(),
                            readUpdateTimeMs = normalizeEpochMs(book.optLong("readUpdateTime", 0L)),
                            status = if (book.optInt("finishReading", 0) == 1) 2 else 1
                        )
                    )
                }
            }
            json.optJSONArray("albums")?.let { albums ->
                for (i in 0 until albums.length()) {
                    val album = albums.optJSONObject(i) ?: continue
                    val info = album.optJSONObject("albumInfo") ?: continue
                    val extra = album.optJSONObject("albumInfoExtra")
                    val albumId = info.optString("albumId", "").trim()
                    if (albumId.isBlank()) continue
                    out.add(
                        ShelfBook(
                            bookKey = "album:$albumId",
                            title = info.optString("name", "未知专辑").ifBlank { "未知专辑" },
                            author = info.optString("authorName", "未知作者").ifBlank { "未知作者" },
                            coverUrl = info.optString("cover", "").trim(),
                            readUpdateTimeMs = normalizeEpochMs(extra?.optLong("lectureReadUpdateTime", 0L) ?: 0L),
                            status = if (info.optInt("finish", 0) == 1) 2 else 1
                        )
                    )
                }
            }
            AutoRefreshLog.i(
                context,
                "WeRead shelf snapshot success entries=${out.size} readable=${out.count { it.readUpdateTimeMs > 0L }}"
            )
            ShelfSnapshotResult(true, "书架快照 ${out.size} 条", out)
        } catch (e: Exception) {
            AutoRefreshLog.e(context, "WeRead shelf snapshot failed", e)
            ShelfSnapshotResult(false, "${e.javaClass.simpleName}: ${e.message ?: "读取失败"}", emptyList())
        }
    }

    fun cacheShelfBookCover(context: Context, book: ShelfBook): String? {
        if (book.coverUrl.isBlank()) return null
        val dir = File(context.cacheDir, "covers/weread")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "${safeFileName(book.bookKey)}.jpg")
        if (file.exists() && file.length() > 0L) return file.absolutePath
        val rawBookId = book.bookKey.removePrefix("album:")
        for (candidate in coverCandidates(book.coverUrl, rawBookId)) {
            val bytes = runCatching { httpGetBytes(candidate) }.getOrNull() ?: continue
            FileOutputStream(file).use { it.write(bytes) }
            return file.absolutePath
        }
        return null
    }

    fun clearCoverCacheState(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_COVER_BOOK_ID)
            .remove(KEY_LAST_COVER_TITLE)
            .remove(KEY_LAST_COVER_AUTHOR)
            .remove(KEY_LAST_COVER_URL)
            .remove(KEY_LAST_COVER_PATH)
            .remove(KEY_LAST_COVER_BYTES)
            .apply()
    }

    private fun saveLatestCoverState(
        context: Context,
        bookId: String,
        title: String,
        author: String,
        coverUrl: String,
        cachePath: String,
        bytes: Long
    ) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_COVER_BOOK_ID, bookId)
            .putString(KEY_LAST_COVER_TITLE, title)
            .putString(KEY_LAST_COVER_AUTHOR, author)
            .putString(KEY_LAST_COVER_URL, coverUrl)
            .putString(KEY_LAST_COVER_PATH, cachePath)
            .putLong(KEY_LAST_COVER_BYTES, bytes)
            .apply()
    }

    private fun normalizeEpochMs(value: Long): Long {
        return when {
            value <= 0L -> 0L
            value < 10_000_000_000L -> value * 1000L
            else -> value
        }
    }

    fun fetchWallpaperStats(context: Context, apiKey: String, mode: String, baseTimeSeconds: Long? = null): WallpaperStatsResult {
        val key = apiKey.trim()
        if (key.isBlank()) {
            return WallpaperStatsResult(false, "读取失败", "未配置 API Key", mode, 0L, 0L, 0, emptyList(), emptyList())
        }
        AutoRefreshLog.i(context, "WeRead wallpaper stats start mode=$mode baseTime=${baseTimeSeconds ?: 0L} key=${maskKey(key)}")
        return try {
            val body = JSONObject()
                .put("api_name", "/readdata/detail")
                .put("mode", mode)
                .put("skill_version", SKILL_VERSION)
            if (baseTimeSeconds != null && baseTimeSeconds > 0L && mode != "overall") {
                body.put("baseTime", baseTimeSeconds)
            }
            val result = postJson(key, body.toString())
            AutoRefreshLog.i(context, "WeRead wallpaper stats http mode=$mode baseTime=${baseTimeSeconds ?: 0L} code=${result.code} bytes=${result.body.length}")
            if (result.code !in 200..299) {
                return WallpaperStatsResult(false, "读取失败", "HTTP ${result.code}: ${result.body.take(120)}", mode, 0L, 0L, 0, emptyList(), emptyList())
            }
            val json = JSONObject(result.body)
            val upgradeInfo = json.optJSONObject("upgrade_info")
            if (upgradeInfo != null) {
                return WallpaperStatsResult(false, "读取失败", "Skill 需要升级：${upgradeInfo.optString("message", "请升级 skill")}", mode, 0L, 0L, 0, emptyList(), emptyList())
            }
            val errCode = json.optInt("errcode", 0)
            if (errCode != 0) {
                return WallpaperStatsResult(false, "读取失败", "接口错误 errcode=$errCode ${json.optString("errmsg", "").take(80)}", mode, 0L, 0L, 0, emptyList(), emptyList())
            }
            val readTimes = json.optJSONObject("readTimes")
            val buckets = mutableListOf<Pair<Long, Long>>()
            if (readTimes != null) {
                val keys = readTimes.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val ts = k.toLongOrNull() ?: continue
                    val seconds = readTimes.optLong(k, 0L)
                    if (seconds > 0L) buckets.add(ts to seconds)
                }
            }
            buckets.sortBy { it.first }

            val books = mutableListOf<WallpaperBook>()
            val longest = json.optJSONArray("readLongest")
            if (longest != null) {
                for (i in 0 until longest.length()) {
                    val item = longest.optJSONObject(i) ?: continue
                    val book = item.optJSONObject("book")
                    val album = item.optJSONObject("albumInfo")
                    val title = book?.optString("title")?.takeIf { it.isNotBlank() }
                        ?: album?.optString("name")?.takeIf { it.isNotBlank() }
                        ?: "未知条目"
                    val author = book?.optString("author")?.takeIf { it.isNotBlank() }
                        ?: album?.optString("authorName")?.takeIf { it.isNotBlank() }
                        ?: "未知"
                    val bookId = book?.optString("bookId")?.takeIf { it.isNotBlank() }
                        ?: album?.optString("albumId")?.takeIf { it.isNotBlank() }?.let { "album:$it" }
                        .orEmpty()
                    books.add(WallpaperBook(bookId, title, author, item.optLong("readTime", 0L)))
                }
            }
            val total = json.optLong("totalReadTime", 0L)
            val readDays = json.optInt("readDays", 0)
            val detail = "${modeLabel(mode)}：${formatSeconds(total)}，阅读天数 $readDays，排行 ${books.size} 条"
            AutoRefreshLog.i(context, "WeRead wallpaper stats success mode=$mode total=$total buckets=${buckets.size} books=${books.size}")
            WallpaperStatsResult(true, "读取成功", detail, mode, json.optLong("baseTime", 0L), total, readDays, buckets, books)
        } catch (e: Exception) {
            AutoRefreshLog.e(context, "WeRead wallpaper stats failed mode=$mode", e)
            WallpaperStatsResult(false, "读取失败", "${e.javaClass.simpleName}: ${e.message ?: "读取失败"}", mode, 0L, 0L, 0, emptyList(), emptyList())
        }
    }

    fun fetchBookProgress(context: Context, apiKey: String, bookId: String): BookProgressResult {
        val key = apiKey.trim()
        if (key.isBlank()) return BookProgressResult(false, null, 0L, 0L, "未配置 API Key")
        if (bookId.isBlank()) return BookProgressResult(false, null, 0L, 0L, "缺少 bookId")
        return try {
            val body = JSONObject()
                .put("api_name", "/book/getprogress")
                .put("bookId", bookId)
                .put("skill_version", SKILL_VERSION)
            val result = postJson(key, body.toString())
            AutoRefreshLog.i(context, "WeRead book progress http bookId=${bookId.take(10)} code=${result.code} bytes=${result.body.length}")
            if (result.code !in 200..299) {
                return BookProgressResult(false, null, 0L, 0L, "HTTP ${result.code}")
            }
            val json = JSONObject(result.body)
            val upgradeInfo = json.optJSONObject("upgrade_info")
            if (upgradeInfo != null) {
                return BookProgressResult(false, null, 0L, 0L, "Skill 需要升级：${upgradeInfo.optString("message", "请升级 skill")}")
            }
            val errCode = json.optInt("errcode", 0)
            if (errCode != 0) {
                return BookProgressResult(false, null, 0L, 0L, "接口错误 errcode=$errCode ${json.optString("errmsg", "").take(80)}")
            }
            val book = json.optJSONObject("book")
            val progress = book?.optInt("progress", -1)?.takeIf { it >= 0 }
            val recordReadingTime = book?.optLong("recordReadingTime", 0L) ?: 0L
            val updateTimeMs = normalizeEpochMs(book?.optLong("updateTime", 0L) ?: 0L)
            AutoRefreshLog.i(context, "WeRead book progress success bookId=${bookId.take(10)} progress=${progress ?: -1} recordReadingTime=$recordReadingTime updateTimeMs=$updateTimeMs")
            BookProgressResult(true, progress, recordReadingTime, updateTimeMs, "progress=${progress ?: "-"} recordReadingTime=$recordReadingTime")
        } catch (e: Exception) {
            AutoRefreshLog.e(context, "WeRead book progress failed bookId=${bookId.take(10)}", e)
            BookProgressResult(false, null, 0L, 0L, "${e.javaClass.simpleName}: ${e.message ?: "读取失败"}")
        }
    }

    fun fetchLatestNote(context: Context, apiKey: String, bookId: String): LatestNoteResult {
        val key = apiKey.trim()
        if (key.isBlank()) return LatestNoteResult(false, bookId, "", "", 0L, "未配置 API Key")
        if (bookId.isBlank()) return LatestNoteResult(false, bookId, "", "", 0L, "缺少 bookId")
        data class Candidate(val text: String, val type: String, val createTimeMs: Long)
        val candidates = mutableListOf<Candidate>()
        try {
            val bookmarkBody = JSONObject()
                .put("api_name", "/book/bookmarklist")
                .put("bookId", bookId)
                .put("skill_version", SKILL_VERSION)
                .toString()
            val bookmarkResult = postJson(key, bookmarkBody)
            AutoRefreshLog.i(context, "WeRead latest note bookmarks http bookId=${bookId.take(10)} code=${bookmarkResult.code} bytes=${bookmarkResult.body.length}")
            if (bookmarkResult.code in 200..299) {
                val json = JSONObject(bookmarkResult.body)
                val upgradeInfo = json.optJSONObject("upgrade_info")
                if (upgradeInfo != null) {
                    return LatestNoteResult(false, bookId, "", "", 0L, "Skill 需要升级：${upgradeInfo.optString("message", "请升级 skill")}")
                }
                if (json.optInt("errcode", 0) == 0) {
                    val updated = json.optJSONArray("updated")
                    if (updated != null) {
                        for (i in 0 until updated.length()) {
                            val item = updated.optJSONObject(i) ?: continue
                            val text = item.optString("markText", "").trim()
                            val createTime = normalizeEpochMs(item.optLong("createTime", 0L))
                            if (text.isNotBlank()) candidates.add(Candidate(text, "划线", createTime))
                        }
                    }
                }
            }

            val reviewBody = JSONObject()
                .put("api_name", "/review/list/mine")
                .put("bookid", bookId)
                .put("count", 20)
                .put("skill_version", SKILL_VERSION)
                .toString()
            val reviewResult = postJson(key, reviewBody)
            AutoRefreshLog.i(context, "WeRead latest note reviews http bookId=${bookId.take(10)} code=${reviewResult.code} bytes=${reviewResult.body.length}")
            if (reviewResult.code in 200..299) {
                val json = JSONObject(reviewResult.body)
                val upgradeInfo = json.optJSONObject("upgrade_info")
                if (upgradeInfo != null) {
                    return LatestNoteResult(false, bookId, "", "", 0L, "Skill 需要升级：${upgradeInfo.optString("message", "请升级 skill")}")
                }
                if (json.optInt("errcode", 0) == 0) {
                    val reviews = json.optJSONArray("reviews")
                    if (reviews != null) {
                        for (i in 0 until reviews.length()) {
                            val review = reviews.optJSONObject(i)?.optJSONObject("review") ?: continue
                            val text = review.optString("content", "").trim()
                            val createTime = normalizeEpochMs(review.optLong("createTime", 0L))
                            if (text.isNotBlank()) candidates.add(Candidate(text, "想法", createTime))
                        }
                    }
                }
            }
        } catch (e: Exception) {
            AutoRefreshLog.e(context, "WeRead latest note failed bookId=${bookId.take(10)}", e)
            return LatestNoteResult(false, bookId, "", "", 0L, "${e.javaClass.simpleName}: ${e.message ?: "读取失败"}")
        }
        val latest = candidates.maxByOrNull { it.createTimeMs }
            ?: return LatestNoteResult(true, bookId, "", "", 0L, "无可导出摘录")
        return LatestNoteResult(true, bookId, latest.text, latest.type, latest.createTimeMs, latest.type)
    }

    fun fetchPopularBookmark(context: Context, apiKey: String, bookId: String): LatestNoteResult {
        val key = apiKey.trim()
        if (key.isBlank()) return LatestNoteResult(false, bookId, "", "", 0L, "未配置 API Key")
        if (bookId.isBlank()) return LatestNoteResult(false, bookId, "", "", 0L, "缺少 bookId")
        return try {
            val body = JSONObject()
                .put("api_name", "/book/bestbookmarks")
                .put("bookId", bookId)
                .put("chapterUid", 0)
                .put("skill_version", SKILL_VERSION)
                .toString()
            val result = postJson(key, body)
            AutoRefreshLog.i(context, "WeRead popular bookmark http bookId=${bookId.take(10)} code=${result.code} bytes=${result.body.length}")
            if (result.code !in 200..299) return LatestNoteResult(false, bookId, "", "", 0L, "HTTP ${result.code}")
            val json = JSONObject(result.body)
            val upgradeInfo = json.optJSONObject("upgrade_info")
            if (upgradeInfo != null) {
                return LatestNoteResult(false, bookId, "", "", 0L, "Skill 需要升级：${upgradeInfo.optString("message", "请升级 skill")}")
            }
            if (json.optInt("errcode", 0) != 0) {
                return LatestNoteResult(false, bookId, "", "", 0L, "接口错误 errcode=${json.optInt("errcode", 0)}")
            }
            val items = json.optJSONArray("items")
                ?: return LatestNoteResult(true, bookId, "", "", 0L, "无热门划线")
            var best = ""
            for (i in 0 until items.length()) {
                val text = items.optJSONObject(i)?.optString("markText")?.trim().orEmpty()
                if (text.length >= 4 && (best.isBlank() || text.length < best.length)) best = text
            }
            if (best.isBlank()) {
                LatestNoteResult(true, bookId, "", "", 0L, "无热门划线")
            } else {
                LatestNoteResult(true, bookId, best, "热门划线", 0L, "热门划线")
            }
        } catch (e: Exception) {
            AutoRefreshLog.e(context, "WeRead popular bookmark failed bookId=${bookId.take(10)}", e)
            LatestNoteResult(false, bookId, "", "", 0L, "${e.javaClass.simpleName}: ${e.message ?: "读取失败"}")
        }
    }


    private data class HttpResult(val code: Int, val body: String)

    private fun postJson(apiKey: String, body: String): HttpResult {
        val conn = (URL(API_GATEWAY).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ReadTrace-Wallpaper/WeRead")
        }
        return try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            HttpResult(code, stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty())
        } finally {
            conn.disconnect()
        }
    }

    private fun httpGetBytes(url: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/*,*/*;q=0.8")
            setRequestProperty("User-Agent", "ReadTrace-Wallpaper/WeRead")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) error("cover HTTP $code")
            conn.inputStream.use { input ->
                input.readBytes().also {
                    if (it.isEmpty()) error("cover empty")
                    if (it.size > 8 * 1024 * 1024) error("cover too large: ${it.size}")
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun saveSuccess(context: Context, result: TestResult): TestResult {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, result.status)
            .putLong(KEY_LAST_TEST_MS, System.currentTimeMillis())
            .putString(KEY_ERROR, "")
            .apply()
        AutoRefreshLog.i(context, "WeRead test success ${result.detail}")
        return result
    }

    private fun saveFailure(context: Context, error: String): TestResult {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATUS, "连接失败")
            .putLong(KEY_LAST_TEST_MS, System.currentTimeMillis())
            .putString(KEY_ERROR, error)
            .apply()
        return TestResult(false, "连接失败", error, 0, 0, false)
    }

    fun formatSeconds(seconds: Long): String {
        val minutes = (seconds / 60L).coerceAtLeast(0L)
        val hours = minutes / 60L
        val remain = minutes % 60L
        return if (hours > 0L) "${hours}小时${remain}分钟" else "${remain}分钟"
    }

    fun modeLabel(mode: String): String {
        return when (mode) {
            "weekly" -> "本周"
            "monthly" -> "本月"
            "annually" -> "今年"
            "overall" -> "总计"
            else -> mode
        }
    }

    private fun normalizeUrl(raw: String): String {
        val value = raw.trim()
        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http://") || value.startsWith("https://") -> value
            else -> ""
        }
    }

    private fun coverCandidates(raw: String, bookId: String): List<String> {
        val normalizedRaw = normalizeUrl(raw)
        if (normalizedRaw.isBlank()) return emptyList()
        val out = linkedSetOf<String>()
        val normalizedBookId = bookId.ifBlank {
            Regex("""(?:t\d+|o|b|m|s)_(\d+)\.jpg""")
                .find(normalizedRaw)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
        }
        if (normalizedBookId.isNotBlank()) {
            val base = normalizedRaw.replace(Regex("""/[^/]+$"""), "/")
            out += "${base}o_${normalizedBookId}.jpg"
            out += "${base}t9_${normalizedBookId}.jpg"
            out += "${base}t8_${normalizedBookId}.jpg"
        }
        out += normalizedRaw
        return out.toList()
    }

    private fun safeFileName(value: String): String {
        return value.replace(Regex("""[^A-Za-z0-9._-]"""), "_").ifBlank { "unknown" }
    }
}
