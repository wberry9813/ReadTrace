package com.dmer.neoreaderrecords

import android.content.Context
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object DiagnosticPackageExporter {
    fun export(context: Context): SafeLogStore.WriteResult {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "ReadTrace_logs_$stamp.zip"
        val bytes = runCatching { buildZip(context) }
            .getOrElse { buildFallbackZip(context, it) }
        return runCatching { SafeLogStore.exportBytes(context, name, "application/zip", bytes) }
            .getOrElse {
                SafeLogStore.WriteResult(
                    ok = false,
                    path = "",
                    detail = "DiagnosticPackageExport ${it.javaClass.simpleName}:${it.message.orEmpty().take(220)}",
                    fallback = true
                )
            }
    }

    private fun buildZip(context: Context): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            addText(zip, "device_info.txt", deviceInfo(context))
            addText(zip, "log_locations.txt", logLocations(context))
            addFirstExisting(zip, "neoreader_debug.log", SafeLogStore.candidates(context, SafeLogStore.DEBUG_LOG_NAME))
            addFirstExisting(zip, DebugEventLog.LOG_NAME, SafeLogStore.candidates(context, DebugEventLog.LOG_NAME))
            addFirstExisting(zip, "neoreader_auto_refresh.log", SafeLogStore.candidates(context, SafeLogStore.AUTO_REFRESH_LOG_NAME))
            addFirstExisting(zip, "neoreader_wallpaper.png", wallpaperCandidates(context))
        }
        return out.toByteArray()
    }

    private fun deviceInfo(context: Context): String {
        return buildString {
            append("package=").append(context.packageName).append('\n')
            append("version=").append(GitHubReleaseChecker.currentVersionName(context)).append('\n')
            append("sdk=").append(Build.VERSION.SDK_INT).append('\n')
            append("release=").append(Build.VERSION.RELEASE).append('\n')
            append("manufacturer=").append(Build.MANUFACTURER).append('\n')
            append("brand=").append(Build.BRAND).append('\n')
            append("model=").append(Build.MODEL).append('\n')
            append("device=").append(Build.DEVICE).append('\n')
            append("product=").append(Build.PRODUCT).append('\n')
            append("time=").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        }
    }

    private fun logLocations(context: Context): String {
        return buildString {
            listOf(SafeLogStore.DEBUG_LOG_NAME, DebugEventLog.LOG_NAME, SafeLogStore.AUTO_REFRESH_LOG_NAME).forEach { name ->
                append(name).append('\n')
                SafeLogStore.candidates(context, name).forEach { file ->
                    appendFileSummary(file)
                }
            }
            append("wallpaperCandidates").append('\n')
            wallpaperCandidates(context).forEach { file ->
                appendFileSummary(file)
            }
        }
    }

    private fun StringBuilder.appendFileSummary(file: File) {
        append("  ").append(file.absolutePath)
        runCatching {
            val exists = file.exists()
            append(" exists=").append(exists)
            append(" bytes=").append(if (exists) file.length() else 0L)
        }.onFailure {
            append(" error=").append(it.javaClass.simpleName).append(':').append(it.message.orEmpty().take(160))
        }
        append('\n')
    }

    private fun wallpaperCandidates(context: Context): List<File> {
        return DeviceCompatibilityPolicy.wallpaperCandidates(context)
    }

    private fun addFirstExisting(zip: ZipOutputStream, entryName: String, files: List<File>) {
        val errors = mutableListOf<String>()
        val file = files.firstOrNull { candidate ->
            runCatching { candidate.exists() && candidate.isFile && candidate.length() > 0L }
                .getOrElse {
                    errors += "${candidate.absolutePath} ${it.javaClass.simpleName}:${it.message.orEmpty().take(120)}"
                    false
                }
        }
        if (file == null) {
            addText(
                zip,
                "$entryName.missing.txt",
                "未找到 $entryName\n候选路径：\n${files.joinToString("\n") { it.absolutePath }}\n错误：\n${errors.joinToString("\n").ifBlank { "<none>" }}\n"
            )
            return
        }
        runCatching {
            val bytes = file.inputStream().use { it.readBytes() }
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(bytes)
            zip.closeEntry()
        }.onFailure {
            runCatching { zip.closeEntry() }
            addText(zip, "$entryName.error.txt", "读取失败：${file.absolutePath}\n${it.javaClass.simpleName}:${it.message.orEmpty().take(240)}\n")
        }
    }

    private fun addText(zip: ZipOutputStream, entryName: String, text: String) {
        zip.putNextEntry(ZipEntry(entryName))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun buildFallbackZip(context: Context, error: Throwable): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            addText(zip, "device_info.txt", runCatching { deviceInfo(context) }.getOrElse { "device_info failed: ${it.javaClass.simpleName}:${it.message}\n" })
            addText(zip, "export_error.txt", "${error.javaClass.simpleName}:${error.message.orEmpty().take(500)}\n")
        }
        return out.toByteArray()
    }
}
