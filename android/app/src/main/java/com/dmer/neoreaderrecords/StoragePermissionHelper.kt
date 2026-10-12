package com.dmer.neoreaderrecords

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

object StoragePermissionHelper {
    fun shouldRequestAllFilesAccess(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()
    }

    fun openAllFilesAccessSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            DebugEventLog.i(activity, "open permission settings skipped sdk=${Build.VERSION.SDK_INT}")
            return
        }
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            intent.data = Uri.parse("package:${activity.packageName}")
            activity.startActivity(intent)
            DebugEventLog.i(activity, "open permission settings appAllFiles")
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            activity.startActivity(intent)
            DebugEventLog.i(activity, "open permission settings allFiles fallback=${e.javaClass.simpleName}:${e.message.orEmpty().take(80)}")
        }
    }

    fun summary(context: Context): String {
        val allFiles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
        val persisted = context.contentResolver.persistedUriPermissions.size
        return "全部文件访问=$allFiles，SAF授权=$persisted，公共目录不可见时请点下方按钮授权后再导出诊断包。"
    }
}
