package com.dmer.neoreaderrecords

import android.content.Context

object AutoRefreshConfig {
    const val PREFS_NAME = "wallpaper_settings"
    const val KEY_AUTO_ENABLED = "auto_refresh_enabled"
    const val KEY_READING_DATA_STORE_ENABLED = "reading_data_store_enabled"
    const val KEY_AUTO_MODE = "auto_refresh_mode"
    const val KEY_DAILY_TIME = "auto_daily_time"
    const val KEY_SCREEN_OFF_MIN_INTERVAL = "auto_screen_off_min_interval_minutes"
    const val KEY_LAST_TRIGGER_MS = "auto_last_trigger_ms"
    const val KEY_WEREAD_LAST_PREWARM_MS = "weread_last_prewarm_ms"
    const val KEY_LAST_REASON = "auto_last_reason"

    const val MODE_DAILY = "DAILY"
    const val MODE_SCREEN_OFF = "SCREEN_OFF"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_ENABLED, true)
    }

    fun isReadingDataStoreEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_READING_DATA_STORE_ENABLED, false)
    }

    fun mode(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_AUTO_MODE, MODE_DAILY) ?: MODE_DAILY
    }

    fun dailyTime(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DAILY_TIME, "22:30") ?: "22:30"
    }

    fun minIntervalMinutes(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_SCREEN_OFF_MIN_INTERVAL, 3)
            .coerceIn(1, 240)
    }

    fun disableScreenOffModeAfterServiceFailure(context: Context, reason: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_ENABLED, false)
            .putString(KEY_AUTO_MODE, MODE_DAILY)
            .putString(KEY_LAST_REASON, "screen_off_service_failed:$reason")
            .apply()
    }
}
