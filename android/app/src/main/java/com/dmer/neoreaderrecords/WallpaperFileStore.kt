package com.dmer.neoreaderrecords

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Environment
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

internal object WallpaperFileStore {
    fun save(context: Context, bitmap: Bitmap): String {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "NeoReader"
        )
        if (!dir.exists() && !dir.mkdirs()) {
            throw IOException("Unable to create wallpaper directory: ${dir.absolutePath}")
        }

        val target = File(dir, "neoreader_wallpaper.png")
        val temporary = File.createTempFile(".neoreader_wallpaper-", ".png", dir)
        try {
            FileOutputStream(temporary).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("Bitmap compression failed")
                }
                output.fd.sync()
            }
            Os.rename(temporary.absolutePath, target.absolutePath)
        } finally {
            if (temporary.exists()) temporary.delete()
        }

        runCatching {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf("image/png")
            ) { _, uri ->
                AutoRefreshLog.i(context, "MediaScanner scanned updated image: uri=$uri")
            }
        }
        return target.absolutePath
    }
}
