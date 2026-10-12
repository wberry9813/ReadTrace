package com.dmer.neoreaderrecords

import android.content.Context
import android.os.Environment
import java.io.File

object DeviceCompatibilityPolicy {
    fun runtimeLogsPreferPublicDownload(): Boolean {
        return DevicePlatform.isBooxDevice()
    }

    fun exportPrefersDirectPublicDownload(): Boolean {
        return DevicePlatform.isBooxDevice()
    }

    fun isCompactPhoneUi(context: Context): Boolean {
        val widthDp = context.resources.configuration.screenWidthDp
        return DevicePlatform.isHisenseDevice() || (widthDp in 1..420)
    }

    fun logCandidates(context: Context, fileName: String): List<File> {
        val appCandidates = listOfNotNull(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.let { File(it, fileName) },
            File(context.filesDir, fileName)
        )
        val publicCandidates = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), fileName)
        )
        return preferPublicForDiagnostics(publicCandidates, appCandidates)
    }

    fun wallpaperCandidates(context: Context): List<File> {
        val appCandidates = listOfNotNull(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let { File(it, "NeoReader/neoreader_wallpaper.png") },
            File(context.filesDir, "NeoReader/neoreader_wallpaper.png")
        )
        val publicCandidates = listOf(
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "NeoReader/neoreader_wallpaper.png")
        )
        val ordered = if (DevicePlatform.isBooxDevice()) {
            publicCandidates + appCandidates
        } else {
            appCandidates
        }
        return ordered.distinctBy { it.absolutePath }
    }

    private fun preferPublicForDiagnostics(publicCandidates: List<File>, appCandidates: List<File>): List<File> {
        val ordered = if (DevicePlatform.isBooxDevice()) {
            publicCandidates + appCandidates
        } else {
            appCandidates + publicCandidates
        }
        return ordered.distinctBy { it.absolutePath }
    }
}
