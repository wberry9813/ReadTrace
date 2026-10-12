package com.dmer.neoreaderrecords

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.RandomAccessFile

object SafeLogStore {
    const val DEBUG_LOG_NAME = "neoreader_debug.log"
    const val AUTO_REFRESH_LOG_NAME = "neoreader_auto_refresh.log"

    data class WriteResult(
        val ok: Boolean,
        val path: String,
        val detail: String,
        val fallback: Boolean = false
    )

    fun writeText(context: Context, fileName: String, text: String): WriteResult {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return writeRuntimeBytes(context, fileName, bytes, append = false)
    }

    fun appendText(context: Context, fileName: String, text: String): WriteResult {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return writeRuntimeBytes(context, fileName, bytes, append = true)
    }

    fun exportBytes(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): WriteResult {
        val errors = mutableListOf<String>()
        if (DeviceCompatibilityPolicy.exportPrefersDirectPublicDownload()) {
            runCatching { return writePublicDownload(fileName, bytes, append = false) }
                .onFailure { errors += "BooxPublicDownload ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}" }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { return writeMediaStoreDownload(context, fileName, mimeType, bytes) }
                .onFailure { errors += "MediaStoreDownload ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}" }
        }
        if (!DeviceCompatibilityPolicy.exportPrefersDirectPublicDownload()) {
            runCatching { return writePublicDownload(fileName, bytes, append = false) }
                .onFailure { errors += "PublicDownload ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}" }
        }
        runCatching { return writeAppDownload(context, fileName, bytes, append = false, errors.joinToString("；")) }
            .onFailure { errors += "AppDownload ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}" }
        return WriteResult(false, "", errors.joinToString("；").ifBlank { "未知导出写入错误" }, fallback = true)
    }

    fun candidates(context: Context, fileName: String): List<File> {
        return DeviceCompatibilityPolicy.logCandidates(context, fileName)
    }

    private fun writeRuntimeBytes(
        context: Context,
        fileName: String,
        bytes: ByteArray,
        append: Boolean
    ): WriteResult {
        val errors = mutableListOf<String>()
        if (DeviceCompatibilityPolicy.runtimeLogsPreferPublicDownload()) {
            runCatching { return writePublicDownload(fileName, bytes, append) }
                .onFailure { errors += "BooxPublicDownload ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}" }
        }

        runCatching {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            if (!dir.exists() && !dir.mkdirs()) error("mkdirs failed: ${dir.absolutePath}")
            val file = File(dir, fileName)
            writeFile(file, bytes, append)
            val detail = if (errors.isEmpty()) {
                "saved=app_log"
            } else {
                "saved=app_log；publicSaveFailed=${errors.joinToString("；")}"
            }
            return WriteResult(true, file.absolutePath, detail, fallback = errors.isNotEmpty())
        }.onFailure { errors += "AppLog ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}" }

        runCatching {
            val file = File(context.filesDir, fileName)
            writeFile(file, bytes, append)
            return WriteResult(true, file.absolutePath, "saved=files_log；appLogFailed=${errors.joinToString("；")}", fallback = true)
        }.onFailure { errors += "FilesLog ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}" }

        return WriteResult(false, "", errors.joinToString("；").ifBlank { "未知日志写入错误" }, fallback = true)
    }

    private fun writePublicDownload(fileName: String, bytes: ByteArray, append: Boolean): WriteResult {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) error("mkdirs failed: ${dir.absolutePath}")
        val file = File(dir, fileName)
        writeFile(file, bytes, append)
        return WriteResult(true, file.absolutePath, "saved=public_download")
    }

    private fun writeAppDownload(
        context: Context,
        fileName: String,
        bytes: ByteArray,
        append: Boolean,
        priorErrors: String
    ): WriteResult {
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        if (!dir.exists() && !dir.mkdirs()) error("mkdirs failed: ${dir.absolutePath}")
        val file = File(dir, fileName)
        writeFile(file, bytes, append)
        return WriteResult(true, file.absolutePath, "saved=app_download；publicSaveFailed=$priorErrors", fallback = true)
    }

    private fun writeFile(file: File, bytes: ByteArray, append: Boolean) {
        RandomAccessFile(file, "rw").use { raf ->
            raf.channel.use { ch ->
                ch.lock().use {
                    if (append) raf.seek(raf.length()) else raf.setLength(0)
                    raf.write(bytes)
                }
            }
        }
    }

    private fun writeMediaStoreDownload(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): WriteResult {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/"
        val uri = findExistingDownloadUri(context, collection, fileName, relativePath)
            ?: resolver.insert(collection, ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            })
            ?: error("insert returned null")
        try {
            resolver.openOutputStream(uri, "w")?.use { it.write(bytes) } ?: error("openOutputStream returned null")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        } catch (e: Throwable) {
            runCatching { resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) }
            throw e
        }
        val path = "${Environment.getExternalStorageDirectory().absolutePath}/${Environment.DIRECTORY_DOWNLOADS}/$fileName"
        return WriteResult(true, path, "saved=mediastore_download uri=$uri")
    }

    private fun findExistingDownloadUri(context: Context, collection: Uri, fileName: String, relativePath: String): Uri? {
        val projection = arrayOf(MediaStore.Downloads._ID)
        val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND (${MediaStore.Downloads.RELATIVE_PATH}=? OR ${MediaStore.Downloads.RELATIVE_PATH}=?)"
        val args = arrayOf(fileName, relativePath, relativePath.trimEnd('/'))
        context.contentResolver.query(collection, projection, selection, args, "${MediaStore.Downloads.DATE_MODIFIED} DESC")?.use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        return null
    }
}
