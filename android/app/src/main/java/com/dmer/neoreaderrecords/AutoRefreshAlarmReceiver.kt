package com.dmer.neoreaderrecords

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class AutoRefreshAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        AutoRefreshLog.i(context, "AutoRefreshAlarmReceiver.onReceive action=${intent?.action}")
        AutoRefreshScheduler.scheduleNext(context)
        AutoRefreshWorker.enqueue(context, "daily_alarm")
    }
}
