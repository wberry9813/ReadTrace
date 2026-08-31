package com.dmer.neoreaderrecords

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class ScreenOffMonitorService : Service() {
    private var receiver: BroadcastReceiver? = null
    private var contentObserver: ContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        AutoRefreshLog.i(this, "ScreenOffMonitorService.onCreate")
        ensureChannel()
        ServiceCompat.startForeground(
            this,
            17731,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
        )
        
        // 1. Register display state receiver
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                AutoRefreshLog.i(this@ScreenOffMonitorService, "ScreenOffMonitorService receiver action=${intent?.action}")
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    AutoRefreshWorker.enqueue(this@ScreenOffMonitorService, "screen_off")
                } else if (intent?.action == Intent.ACTION_SCREEN_ON) {
                    AutoRefreshWorker.enqueue(this@ScreenOffMonitorService, "screen_on_prewarm")
                } else if (intent?.action == Intent.ACTION_USER_PRESENT) {
                    AutoRefreshWorker.enqueue(this@ScreenOffMonitorService, "user_present_prewarm")
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this,
            r,
            filter,
            AutoRefreshReasonPolicy.screenStateReceiverFlags()
        )
        receiver = r

        // 2. Register NeoReader database observer
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                AutoRefreshLog.i(this@ScreenOffMonitorService, "ScreenOffMonitorService content_changed uri=$uri")
                val reason = if (uri?.authority == "com.onyx.kreader.statistics.provider") {
                    "reading_stats_changed"
                } else {
                    "book_content_changed"
                }
                AutoRefreshWorker.enqueue(this@ScreenOffMonitorService, reason)
            }
        }
        contentObserver = observer
        listOf(
            Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata"),
            Uri.parse("content://com.onyx.kreader.statistics.provider/OnyxStatisticsModel")
        ).forEach { uri ->
            runCatching {
                contentResolver.registerContentObserver(uri, true, observer)
            }.onSuccess {
                AutoRefreshLog.i(this, "ScreenOffMonitorService observer registered uri=$uri")
            }.onFailure {
                AutoRefreshLog.e(this, "ScreenOffMonitorService observer registration failed uri=$uri", it)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        AutoRefreshLog.i(this, "ScreenOffMonitorService.onStartCommand")
        return START_STICKY
    }

    override fun onDestroy() {
        AutoRefreshLog.i(this, "ScreenOffMonitorService.onDestroy")
        receiver?.let {
            runCatching { unregisterReceiver(it) }
        }
        receiver = null
        contentObserver?.let {
            contentResolver.unregisterContentObserver(it)
        }
        contentObserver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val c = NotificationChannel(
            "neoreader_auto_refresh",
            "NeoReader 自动刷新",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "用于熄屏自动刷新监听（较耗电）"
            setShowBadge(false)
        }
        nm.createNotificationChannel(c)
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, "neoreader_auto_refresh")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("NeoReader 熄屏监听中")
            .setContentText("已启用熄屏自动刷新（较耗电）")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
