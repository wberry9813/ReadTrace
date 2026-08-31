package com.dmer.neoreaderrecords

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.delay

class AutoRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AutoRefreshLog.i(applicationContext, "Worker.doWork start")
        if (!AutoRefreshConfig.isEnabled(applicationContext)) return Result.success()
        val reason = inputData.getString("reason") ?: "unknown"
        AutoRefreshLog.i(applicationContext, "Worker reason=$reason")

        if (AutoRefreshReasonPolicy.needsLocalSettleDelay(reason)) {
            AutoRefreshLog.i(applicationContext, "Worker wait for Neo provider settle reason=$reason")
            delay(1_200L)
        }
        
        val prefs = applicationContext.getSharedPreferences(AutoRefreshConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val wallpaperMode = prefs.getString("wallpaper_mode", "STATS") ?: "STATS"
        val sourceMode = prefs.getString("source_mode", "DURATION") ?: "DURATION"
        AutoRefreshLog.i(applicationContext, "Worker config sourceMode=$sourceMode wallpaperMode=$wallpaperMode")
        if (
            wallpaperMode == "CALENDAR" &&
            (sourceMode == "WEREAD" || sourceMode == "MIXED") &&
            !AutoRefreshConfig.isReadingDataStoreEnabled(applicationContext)
        ) {
            AutoRefreshLog.i(
                applicationContext,
                "Worker skip remote calendar: reading data store disabled source=$sourceMode"
            )
            return Result.success()
        }
        if (wallpaperMode != "CALENDAR") {
            val storeSyncOk = AutoWallpaperGenerator.syncRecentNeoReadingStore(
                applicationContext,
                "worker_$reason"
            )
            AutoRefreshLog.i(applicationContext, "Worker reading store sync ok=$storeSyncOk reason=$reason")
        } else {
            AutoRefreshLog.i(applicationContext, "Worker reading store sync delegated to calendar generation")
        }
        val now = System.currentTimeMillis()
        val minIntervalMs = AutoRefreshConfig.minIntervalMinutes(applicationContext) * 60_000L
        val lastMs = prefs.getLong(AutoRefreshConfig.KEY_LAST_TRIGGER_MS, 0L)
        val delta = now - lastMs

        if (AutoRefreshReasonPolicy.isRemoteSource(sourceMode)) {
            if (!AutoRefreshReasonPolicy.isRemoteTrigger(reason)) {
                AutoRefreshLog.i(applicationContext, "Worker skip remote source=$sourceMode on reason=$reason")
                return Result.success()
            }
            val wereadPrewarmMinMs = 5_000L
            val wereadLastMs = prefs.getLong(AutoRefreshConfig.KEY_WEREAD_LAST_PREWARM_MS, 0L)
            val wereadDelta = now - wereadLastMs
            if (wereadDelta < wereadPrewarmMinMs) {
                AutoRefreshLog.i(applicationContext, "Worker skip remote duplicate prewarm: source=$sourceMode reason=$reason delta=${wereadDelta}ms < $wereadPrewarmMinMs ms")
                return Result.success()
            }
            AutoRefreshLog.i(applicationContext, "Worker remote prewarm accepted: source=$sourceMode reason=$reason delta=${wereadDelta}ms")
            prefs.edit()
                .putLong(AutoRefreshConfig.KEY_WEREAD_LAST_PREWARM_MS, now)
                .apply()
            val ok = runRemoteWithNetworkRetries(reason, sourceMode)
            if (ok) {
                prefs.edit()
                    .putLong(AutoRefreshConfig.KEY_LAST_TRIGGER_MS, now)
                    .putString(AutoRefreshConfig.KEY_LAST_REASON, reason)
                    .apply()
                AutoRefreshLog.i(applicationContext, "Worker success saved remote source=$sourceMode")
                return Result.success()
            }
            AutoRefreshLog.i(
                applicationContext,
                "Worker remote failed after internal retries; wait for next trigger source=$sourceMode"
            )
            return Result.failure()
        }

        var shouldGenerate = true
        var latestBookKey = ""

        if (wallpaperMode == "COVER" || wallpaperMode == "AUTO_COVER") {
            // 封面模式按最新书籍防抖；元数据变化仍允许同一本书重新提取封面。
            latestBookKey = getLatestBookIdentifier(applicationContext)
            val lastBookKey = prefs.getString("auto_last_book_key", "") ?: ""
            if (latestBookKey.isNotBlank() && latestBookKey == lastBookKey) {
                shouldGenerate = AutoRefreshReasonPolicy.shouldRefreshUnchangedCover(
                    wallpaperMode = wallpaperMode,
                    reason = reason,
                    minIntervalElapsed = delta >= minIntervalMs
                )
                AutoRefreshLog.i(
                    applicationContext,
                    "Worker cover unchanged ($latestBookKey) mode=$wallpaperMode fallbackStatsDue=$shouldGenerate"
                )
            } else {
                AutoRefreshLog.i(applicationContext, "Worker force generation: book changed from [$lastBookKey] to [$latestBookKey]")
                shouldGenerate = true
            }
        } else {
            // 统计模式：基于配置的时间进行严格防抖
            val contentDriven = reason == "book_content_changed" || reason == "reading_stats_changed"
            if (!contentDriven && delta < minIntervalMs) {
                AutoRefreshLog.i(applicationContext, "Worker skip by debounce: delta=${delta}ms < $minIntervalMs ms")
                shouldGenerate = false
            } else {
                shouldGenerate = true
            }
        }

        if (!shouldGenerate) {
            return Result.success()
        }

        val ok = AutoWallpaperGenerator.generateAndSave(applicationContext, reason)
        if (ok) {
            prefs.edit()
                .putLong(AutoRefreshConfig.KEY_LAST_TRIGGER_MS, now)
                .putString(AutoRefreshConfig.KEY_LAST_REASON, reason)
                .putString("auto_last_book_key", latestBookKey)
                .apply()
            AutoRefreshLog.i(applicationContext, "Worker success saved")
            return Result.success()
        }
        AutoRefreshLog.i(applicationContext, "Worker failed -> retry")
        return Result.retry()
    }

    private suspend fun runRemoteWithNetworkRetries(reason: String, sourceMode: String): Boolean {
        val maxAttempts = 3
        val firstDelayMs = 1_500L
        val retryDelayMs = 2_000L
        for (attempt in 1..maxAttempts) {
            if (isStopped) return false
            val delayMs = if (attempt == 1) firstDelayMs else retryDelayMs
            AutoRefreshLog.i(
                applicationContext,
                "Worker remote wait network settle ${delayMs}ms before request source=$sourceMode attempt=$attempt/$maxAttempts"
            )
            delay(delayMs)
            val taggedReason = "$reason#$attempt"
            val ok = if (sourceMode == "MIXED") {
                AutoWallpaperGenerator.generateAndSaveMixed(applicationContext, taggedReason)
            } else {
                AutoWallpaperGenerator.generateAndSaveWeRead(applicationContext, taggedReason)
            }
            if (ok) {
                AutoRefreshLog.i(applicationContext, "Worker remote attempt success source=$sourceMode attempt=$attempt/$maxAttempts")
                return true
            }
            AutoRefreshLog.i(applicationContext, "Worker remote attempt failed source=$sourceMode attempt=$attempt/$maxAttempts")
        }
        return false
    }

    private fun getLatestBookIdentifier(context: Context): String {
        return runCatching {
            val metadataUri = Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata")
            context.contentResolver.query(metadataUri, null, null, null, "lastAccess DESC")?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex("title")
                    val pathIdx = c.getColumnIndex("nativeAbsolutePath")
                    val title = if (nameIdx >= 0 && !c.isNull(nameIdx)) c.getString(nameIdx) else "unknown"
                    val path = if (pathIdx >= 0 && !c.isNull(pathIdx)) c.getString(pathIdx) else "unknown"
                    "${title}_${path}"
                } else {
                    "empty"
                }
            } ?: "empty"
        }.getOrDefault("error")
    }

    companion object {
        fun enqueue(context: Context, reason: String) {
            val prefs = context.getSharedPreferences(AutoRefreshConfig.PREFS_NAME, Context.MODE_PRIVATE)
            val sourceMode = prefs.getString("source_mode", "DURATION") ?: "DURATION"
            val remoteSource = AutoRefreshReasonPolicy.isRemoteSource(sourceMode)
            if (remoteSource && !AutoRefreshReasonPolicy.isRemoteTrigger(reason)) {
                AutoRefreshLog.i(context, "Worker enqueue skip remote source=$sourceMode reason=$reason")
                return
            }
            val req = OneTimeWorkRequestBuilder<AutoRefreshWorker>()
                .setInputData(androidx.work.Data.Builder().putString("reason", reason).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (remoteSource) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED
                        )
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                if (remoteSource) {
                    AutoRefreshReasonPolicy.REMOTE_WORK_NAME
                } else {
                    AutoRefreshReasonPolicy.LOCAL_WORK_NAME
                },
                if (remoteSource) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }
}
