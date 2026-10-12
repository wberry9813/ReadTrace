package com.dmer.neoreaderrecords

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Environment
import java.io.File
import java.io.FileOutputStream

object HanvonWallpaperPipeline {
    private const val HWSYS_DIR = "hwsys"
    private const val LOCKSCREEN_DIR = "lockscreenImage"
    private const val HANVON_SETTINGS_PACKAGE = "hanvon.aebr.hvsettings"
    private const val HANVON_SETTINGS_RECEIVER = "hanvon.aebr.hvsettings.ScreencapBroadCastRecevier"
    private const val ACTION_SET_LOGO_PIC = "hanvon.intent.action.get.logopic"
    private const val EXTRA_PIC_TYPE = "hanvon_pictype"
    private const val EXTRA_PIC_PATH = "hanvon_picpath"
    private const val PIC_TYPE_LOCKSCREEN = 0

    data class Result(
        val active: Boolean,
        val ok: Boolean,
        val paths: List<String>,
        val detail: String
    )

    fun install(context: Context, bitmap: Bitmap, primaryPath: String, reason: String): Result {
        if (!DevicePlatform.isHanvonDevice()) {
            return Result(active = false, ok = true, paths = emptyList(), detail = "hanvon=inactive")
        }

        val root = Environment.getExternalStorageDirectory()
        val dir = File(File(root, HWSYS_DIR), LOCKSCREEN_DIR)
        val errors = mutableListOf<String>()
        if (!dir.isDirectory) {
            runCatching { dir.mkdirs() }
                .onFailure { errors += "mkdir ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}" }
        }
        if (!dir.isDirectory) {
            val detail = "hanvon=active lockscreenDirMissing path=${dir.absolutePath} errors=${errors.joinToString("；")}"
            AutoRefreshLog.i(context, "Hanvon pipeline unavailable $detail device=${DevicePlatform.identityText()}")
            return Result(active = true, ok = false, paths = emptyList(), detail = detail)
        }

        val targets = dir.listFiles()
            ?.filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(File(dir, "readtrace_${System.currentTimeMillis()}.jpg"))

        val saved = mutableListOf<String>()
        targets.forEach { file ->
            runCatching {
                FileOutputStream(file).use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) error("bitmap compress failed")
                }
                saved += file.absolutePath
            }.onFailure {
                errors += "write ${file.name} ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}"
                AutoRefreshLog.e(context, "Hanvon pipeline mirror failed path=${file.absolutePath}", it)
            }
        }

        if (saved.isNotEmpty()) {
            runCatching {
                android.media.MediaScannerConnection.scanFile(
                    context,
                    saved.toTypedArray(),
                    Array(saved.size) { "image/jpeg" }
                ) { _, _ -> }
            }
            val shouldRefresh = shouldRequestSystemRefresh(reason)
            if (shouldRefresh) {
                requestSystemRefresh(context, saved.firstOrNull() ?: primaryPath)
            } else {
                AutoRefreshLog.i(context, "Hanvon pipeline refresh skipped reason=$reason path=${saved.firstOrNull().orEmpty()}")
            }
            val detail = "hanvon=active ok paths=${saved.joinToString("|")} refresh=$shouldRefresh"
            AutoRefreshLog.i(context, "Hanvon pipeline installed $detail")
            return Result(active = true, ok = true, paths = saved, detail = detail)
        }

        val detail = "hanvon=active mirrorFailed path=${dir.absolutePath} errors=${errors.joinToString("；").ifBlank { "unknown" }}"
        AutoRefreshLog.i(context, "Hanvon pipeline failed $detail")
        return Result(active = true, ok = false, paths = emptyList(), detail = detail)
    }

    fun shouldRequestSystemRefresh(reason: String): Boolean {
        return !(reason.startsWith("screen_on_prewarm") || reason.startsWith("user_present_prewarm"))
    }

    private fun requestSystemRefresh(context: Context, imagePath: String) {
        runCatching {
            val file = File(imagePath)
            if (!file.isFile) return@runCatching
            val intent = Intent(ACTION_SET_LOGO_PIC).apply {
                component = ComponentName(HANVON_SETTINGS_PACKAGE, HANVON_SETTINGS_RECEIVER)
                putExtra(EXTRA_PIC_TYPE, PIC_TYPE_LOCKSCREEN)
                putExtra(EXTRA_PIC_PATH, file.absolutePath)
            }
            context.sendBroadcast(intent)
            AutoRefreshLog.i(context, "Hanvon pipeline refresh requested path=${file.absolutePath}")
        }.onFailure {
            AutoRefreshLog.e(context, "Hanvon pipeline refresh request failed path=$imagePath", it)
        }
    }
}
