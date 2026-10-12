package com.dmer.neoreaderrecords

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AutoRefreshWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        AutoRefreshLog.i(applicationContext, "Worker.doWork start")
        val reason = inputData.getString("reason") ?: "unknown"
        return when (AutoRefreshRunner.run(applicationContext, reason, "Worker")) {
            AutoRefreshRunner.Outcome.SUCCESS -> Result.success()
            AutoRefreshRunner.Outcome.RETRY -> Result.retry()
        }
    }

    companion object {
        fun enqueue(context: Context, reason: String) {
            runCatching {
                val req = OneTimeWorkRequestBuilder<AutoRefreshWorker>()
                    .setInputData(androidx.work.Data.Builder().putString("reason", reason).build())
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                            .build()
                    )
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "neoreader_auto_refresh",
                    ExistingWorkPolicy.REPLACE,
                    req
                )
                AutoRefreshLog.i(context, "Worker enqueue submitted reason=$reason")
            }.onFailure {
                AutoRefreshLog.e(context, "Worker enqueue failed reason=$reason", it)
            }

            if (DevicePlatform.isHanvonDevice()) {
                AutoRefreshDirectExecutor.enqueue(context.applicationContext, reason)
            }
        }
    }
}

object AutoRefreshDirectExecutor {
    private val executor = Executors.newSingleThreadExecutor()
    private val running = AtomicBoolean(false)

    fun enqueue(context: Context, reason: String) {
        AutoRefreshLog.i(context, "DirectExecutor enqueue reason=$reason")
        executor.execute {
            if (!running.compareAndSet(false, true)) {
                AutoRefreshLog.i(context, "DirectExecutor skip: already running reason=$reason")
                return@execute
            }
            try {
                AutoRefreshRunner.run(context.applicationContext, reason, "DirectExecutor")
            } finally {
                running.set(false)
            }
        }
    }
}

object AutoRefreshRunner {
    enum class Outcome {
        SUCCESS,
        RETRY
    }

    fun run(context: Context, reason: String, runner: String): Outcome {
        if (!AutoRefreshConfig.isEnabled(context)) {
            AutoRefreshLog.i(context, "$runner skip: auto disabled")
            return Outcome.SUCCESS
        }
        AutoRefreshLog.i(context, "$runner reason=$reason")

        val prefs = context.getSharedPreferences(AutoRefreshConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val wallpaperMode = prefs.getString("wallpaper_mode", "STATS") ?: "STATS"
        val sourceMode = prefs.getString("source_mode", "DURATION") ?: "DURATION"
        AutoRefreshLog.i(context, "$runner config sourceMode=$sourceMode wallpaperMode=$wallpaperMode")
        if (
            wallpaperMode == "CALENDAR" &&
            (sourceMode == "WEREAD" || sourceMode == "MIXED") &&
            !AutoRefreshConfig.isReadingDataStoreEnabled(context)
        ) {
            AutoRefreshLog.i(
                context,
                "$runner skip remote calendar: reading data store disabled source=$sourceMode"
            )
            return Outcome.SUCCESS
        }
        if (wallpaperMode != "CALENDAR") {
            val storeSyncOk = AutoWallpaperGenerator.syncRecentNeoReadingStore(
                context,
                "worker_$reason"
            )
            AutoRefreshLog.i(context, "$runner reading store sync ok=$storeSyncOk reason=$reason")
        } else {
            AutoRefreshLog.i(context, "$runner reading store sync delegated to calendar generation")
        }
        val now = System.currentTimeMillis()
        val minIntervalMs = AutoRefreshConfig.minIntervalMinutes(context) * 60_000L
        val lastMs = prefs.getLong(AutoRefreshConfig.KEY_LAST_TRIGGER_MS, 0L)
        val delta = now - lastMs

        if (sourceMode == "WEREAD" || sourceMode == "MIXED") {
            val remoteAllowed = reason == "screen_on_prewarm" ||
                reason == "user_present_prewarm" ||
                reason == "daily_alarm"
            if (!remoteAllowed) {
                AutoRefreshLog.i(context, "$runner skip remote source=$sourceMode on reason=$reason")
                return Outcome.SUCCESS
            }
            val wereadPrewarmMinMs = 5_000L
            val wereadLastMs = prefs.getLong(AutoRefreshConfig.KEY_WEREAD_LAST_PREWARM_MS, 0L)
            val wereadDelta = now - wereadLastMs
            if (wereadDelta < wereadPrewarmMinMs) {
                AutoRefreshLog.i(context, "$runner skip remote duplicate prewarm: source=$sourceMode reason=$reason delta=${wereadDelta}ms < $wereadPrewarmMinMs ms")
                return Outcome.SUCCESS
            }
            AutoRefreshLog.i(context, "$runner remote prewarm accepted: source=$sourceMode reason=$reason delta=${wereadDelta}ms")
            prefs.edit()
                .putLong(AutoRefreshConfig.KEY_WEREAD_LAST_PREWARM_MS, now)
                .apply()
            val ok = runRemoteWithNetworkRetries(context, runner, reason, sourceMode)
            if (ok) {
                prefs.edit()
                    .putLong(AutoRefreshConfig.KEY_LAST_TRIGGER_MS, now)
                    .putString(AutoRefreshConfig.KEY_LAST_REASON, reason)
                    .apply()
                AutoRefreshLog.i(context, "$runner success saved remote source=$sourceMode")
                return Outcome.SUCCESS
            }
            AutoRefreshLog.i(context, "$runner remote failed -> retry source=$sourceMode")
            return Outcome.RETRY
        }

        var shouldGenerate = true
        var latestBookKey = ""

        if (wallpaperMode == "COVER") {
            latestBookKey = getLatestBookIdentifier(context)
            val lastBookKey = prefs.getString("auto_last_book_key", "") ?: ""
            if (latestBookKey.isNotBlank() && latestBookKey == lastBookKey) {
                AutoRefreshLog.i(context, "$runner skip: COVER mode book unchanged ($latestBookKey)")
                shouldGenerate = false
            } else {
                AutoRefreshLog.i(context, "$runner force generation: book changed from [$lastBookKey] to [$latestBookKey]")
                shouldGenerate = true
            }
        } else {
            if (delta < minIntervalMs) {
                AutoRefreshLog.i(context, "$runner skip by debounce: delta=${delta}ms < $minIntervalMs ms")
                shouldGenerate = false
            } else {
                shouldGenerate = true
            }
        }

        if (!shouldGenerate) {
            return Outcome.SUCCESS
        }

        val ok = AutoWallpaperGenerator.generateAndSave(context, reason)
        if (ok) {
            prefs.edit()
                .putLong(AutoRefreshConfig.KEY_LAST_TRIGGER_MS, now)
                .putString(AutoRefreshConfig.KEY_LAST_REASON, reason)
                .putString("auto_last_book_key", latestBookKey)
                .apply()
            AutoRefreshLog.i(context, "$runner success saved")
            return Outcome.SUCCESS
        }
        AutoRefreshLog.i(context, "$runner failed -> retry")
        return Outcome.RETRY
    }

    private fun runRemoteWithNetworkRetries(
        context: Context,
        runner: String,
        reason: String,
        sourceMode: String
    ): Boolean {
        val maxAttempts = 5
        val firstDelayMs = 3_500L
        val retryDelayMs = 3_000L
        for (attempt in 1..maxAttempts) {
            val delayMs = if (attempt == 1) firstDelayMs else retryDelayMs
            AutoRefreshLog.i(
                context,
                "$runner remote wait network settle ${delayMs}ms before request source=$sourceMode attempt=$attempt/$maxAttempts"
            )
            Thread.sleep(delayMs)
            val taggedReason = "$reason#$attempt"
            val ok = if (sourceMode == "MIXED") {
                AutoWallpaperGenerator.generateAndSaveMixed(context, taggedReason)
            } else {
                AutoWallpaperGenerator.generateAndSaveWeRead(context, taggedReason)
            }
            if (ok) {
                AutoRefreshLog.i(context, "$runner remote attempt success source=$sourceMode attempt=$attempt/$maxAttempts")
                return true
            }
            AutoRefreshLog.i(context, "$runner remote attempt failed source=$sourceMode attempt=$attempt/$maxAttempts")
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
}
