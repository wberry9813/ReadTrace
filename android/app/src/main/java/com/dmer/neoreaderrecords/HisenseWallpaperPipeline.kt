package com.dmer.neoreaderrecords

import android.app.WallpaperManager
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

object HisenseWallpaperPipeline {
    data class Result(
        val active: Boolean,
        val ok: Boolean,
        val detail: String
    )

    fun openSetWallpaperUi(context: Context, saved: WallpaperFileStore.SaveResult): Result {
        if (!DevicePlatform.isHisenseDevice()) {
            return Result(active = false, ok = true, detail = "hisenseSetUi=inactive")
        }
        val uri = saved.contentUri?.takeIf { it.isNotBlank() }?.let(Uri::parse)
        if (uri == null) {
            val detail = "hisenseSetUi=active failed noContentUri path=${saved.path.take(160)}"
            AutoRefreshLog.i(context, "Hisense pipeline $detail")
            DebugEventLog.i(context, "Hisense pipeline $detail")
            return Result(active = true, ok = false, detail = detail)
        }

        val intents = listOf(
            Intent(Intent.ACTION_ATTACH_DATA).apply {
                setDataAndType(uri, "image/png")
                clipData = ClipData.newUri(context.contentResolver, "ReadTrace wallpaper", uri)
                putExtra("mimeType", "image/png")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            Intent(Intent.ACTION_SET_WALLPAPER).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            Intent(Settings.ACTION_SETTINGS)
        )

        intents.forEachIndexed { index, intent ->
            runCatching {
                if (context !is android.app.Activity) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }.onSuccess {
                val detail = "hisenseSetUi=active ok intentIndex=$index action=${intent.action} uri=$uri"
                AutoRefreshLog.i(context, "Hisense pipeline $detail")
                DebugEventLog.i(context, "Hisense pipeline $detail")
                return Result(active = true, ok = true, detail = detail)
            }.onFailure {
                AutoRefreshLog.i(context, "Hisense pipeline set ui attempt failed index=$index action=${intent.action} ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}")
            }
        }

        val detail = "hisenseSetUi=active failed allIntents uri=$uri"
        AutoRefreshLog.i(context, "Hisense pipeline $detail")
        DebugEventLog.i(context, "Hisense pipeline $detail")
        return Result(active = true, ok = false, detail = detail)
    }

    fun install(context: Context, bitmap: Bitmap, reason: String): Result {
        if (!DevicePlatform.isHisenseDevice()) {
            return Result(active = false, ok = true, detail = "hisense=inactive")
        }
        if (reason.startsWith("screen_on_prewarm") || reason.startsWith("user_present_prewarm")) {
            val detail = "hisense=active skippedPrewarm reason=$reason"
            AutoRefreshLog.i(context, "Hisense pipeline $detail")
            DebugEventLog.i(context, "Hisense pipeline $detail")
            return Result(active = true, ok = true, detail = detail)
        }

        val manager = runCatching { WallpaperManager.getInstance(context) }.getOrElse {
            val detail = "hisense=active managerFail ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}"
            AutoRefreshLog.e(context, "Hisense pipeline manager failed", it)
            DebugEventLog.i(context, "Hisense pipeline $detail")
            return Result(active = true, ok = false, detail = detail)
        }

        val failures = mutableListOf<String>()
        val successes = mutableListOf<String>()
        val pngBytes = runCatching {
            ByteArrayOutputStream().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) error("bitmap compress failed")
                out.toByteArray()
            }
        }.getOrElse {
            failures += "compress ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}"
            ByteArray(0)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_LOCK)
            }.onSuccess {
                successes += "FLAG_LOCK_BITMAP result=$it"
            }.onFailure {
                failures += "FLAG_LOCK_BITMAP ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}"
            }

            if (pngBytes.isNotEmpty()) {
                runCatching {
                    manager.setStream(ByteArrayInputStream(pngBytes), null, true, WallpaperManager.FLAG_LOCK)
                }.onSuccess {
                    successes += "FLAG_LOCK_STREAM result=$it"
                }.onFailure {
                    failures += "FLAG_LOCK_STREAM ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}"
                }
            }

            val lockOk = successes.any { it.startsWith("FLAG_LOCK") }
            if (!lockOk) {
                runCatching {
                    manager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)
                }.onSuccess {
                    successes += "FLAG_SYSTEM_BITMAP result=$it"
                }.onFailure {
                    failures += "FLAG_SYSTEM_BITMAP ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}"
                }

                if (pngBytes.isNotEmpty()) {
                    runCatching {
                        manager.setStream(ByteArrayInputStream(pngBytes), null, true, WallpaperManager.FLAG_SYSTEM)
                    }.onSuccess {
                        successes += "FLAG_SYSTEM_STREAM result=$it"
                    }.onFailure {
                        failures += "FLAG_SYSTEM_STREAM ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}"
                    }
                }
            }
        }

        if (successes.isEmpty()) {
            runCatching {
                @Suppress("DEPRECATION")
                manager.setBitmap(bitmap)
            }.onSuccess {
                successes += "DEFAULT_BITMAP"
            }.onFailure {
                failures += "DEFAULT_BITMAP ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}"
            }

            if (pngBytes.isNotEmpty()) {
                runCatching {
                    @Suppress("DEPRECATION")
                    manager.setStream(ByteArrayInputStream(pngBytes))
                }.onSuccess {
                    successes += "DEFAULT_STREAM"
                }.onFailure {
                    failures += "DEFAULT_STREAM ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}"
                }
            }
        }

        if (successes.isNotEmpty()) {
            val detail = "hisense=active ok methods=${successes.joinToString("|")} failures=${failures.joinToString("|").ifBlank { "none" }}"
            AutoRefreshLog.i(context, "Hisense pipeline $detail")
            DebugEventLog.i(context, "Hisense pipeline $detail")
            return Result(active = true, ok = true, detail = detail)
        }

        val detail = "hisense=active failed attempts=${failures.joinToString("；").ifBlank { "none" }}"
        AutoRefreshLog.i(context, "Hisense pipeline $detail")
        DebugEventLog.i(context, "Hisense pipeline $detail")
        return Result(active = true, ok = false, detail = detail)
    }
}
