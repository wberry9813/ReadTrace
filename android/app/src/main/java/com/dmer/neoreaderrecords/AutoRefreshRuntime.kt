package com.dmer.neoreaderrecords

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object AutoRefreshRuntime {
    fun sync(context: Context) {
        val enabled = AutoRefreshConfig.isEnabled(context)
        val screenOffMode = AutoRefreshConfig.mode(context) == AutoRefreshConfig.MODE_SCREEN_OFF
        if (enabled && screenOffMode) {
            AutoRefreshLog.i(context, "runtime sync: start screen-off monitor service")
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ScreenOffMonitorService::class.java)
                )
            }.onFailure { AutoRefreshLog.e(context, "startForegroundService failed", it) }
        } else {
            AutoRefreshLog.i(context, "runtime sync: stop screen-off monitor service")
            runCatching {
                context.stopService(Intent(context, ScreenOffMonitorService::class.java))
            }.onFailure { AutoRefreshLog.e(context, "stopService failed", it) }
        }
    }
}
