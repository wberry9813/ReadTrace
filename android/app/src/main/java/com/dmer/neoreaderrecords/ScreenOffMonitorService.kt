package com.dmer.neoreaderrecords

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class ScreenOffMonitorService : Service() {
    private var receiver: BroadcastReceiver? = null
    private var contentObserver: ContentObserver? = null

    override fun onCreate() {
        super.onCreate()
        AutoRefreshLog.i(this, "ScreenOffMonitorService.onCreate")
        val foregroundOk = runCatching {
            ensureChannel()
            startForeground(17731, buildNotification())
        }.onFailure {
            AutoRefreshLog.e(this, "ScreenOffMonitorService startForeground failed", it)
        }.isSuccess
        if (!foregroundOk) {
            AutoRefreshConfig.disableScreenOffModeAfterServiceFailure(
                this,
                "startForeground_failed"
            )
            AutoRefreshLog.i(this, "ScreenOffMonitorService disabled screen-off auto refresh after foreground failure")
            stopSelf()
            return
        }
        
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
        runCatching {
            registerReceiver(r, filter)
            receiver = r
        }.onFailure {
            AutoRefreshLog.e(this, "ScreenOffMonitorService registerReceiver failed", it)
        }

        // 2. Register NeoReader database observer
        if (!DevicePlatform.isBooxDevice()) {
            AutoRefreshLog.i(this, "ScreenOffMonitorService skip Onyx observer device=${DevicePlatform.identityText()}")
            return
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                super.onChange(selfChange, uri)
                AutoRefreshLog.i(this@ScreenOffMonitorService, "ScreenOffMonitorService content_changed uri=$uri")
                AutoRefreshWorker.enqueue(this@ScreenOffMonitorService, "book_content_changed")
            }
        }
        runCatching {
            contentResolver.registerContentObserver(
                Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata"),
                true, // notifyForDescendants
                observer
            )
            contentObserver = observer
        }.onFailure {
            AutoRefreshLog.e(this, "ScreenOffMonitorService registerContentObserver failed", it)
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
            runCatching { contentResolver.unregisterContentObserver(it) }
                .onFailure { e -> AutoRefreshLog.e(this, "ScreenOffMonitorService unregisterContentObserver failed", e) }
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
