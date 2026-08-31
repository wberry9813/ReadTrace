package com.dmer.neoreaderrecords

import androidx.core.content.ContextCompat

internal object AutoRefreshReasonPolicy {
    const val LOCAL_WORK_NAME = "neoreader_local_auto_refresh"
    const val REMOTE_WORK_NAME = "neoreader_remote_auto_refresh"

    fun isRemoteSource(sourceMode: String): Boolean {
        return sourceMode == "WEREAD" || sourceMode == "MIXED"
    }

    fun isRemoteTrigger(reason: String): Boolean {
        return reason == "screen_on_prewarm" ||
            reason == "user_present_prewarm" ||
            reason == "daily_alarm"
    }

    fun needsLocalSettleDelay(reason: String): Boolean {
        return reason == "book_content_changed" || reason == "reading_stats_changed"
    }

    fun screenStateReceiverFlags(): Int = ContextCompat.RECEIVER_EXPORTED

    fun shouldRefreshUnchangedCover(
        wallpaperMode: String,
        reason: String,
        minIntervalElapsed: Boolean
    ): Boolean {
        return reason == "book_content_changed" ||
            (wallpaperMode == "AUTO_COVER" && minIntervalElapsed)
    }
}
