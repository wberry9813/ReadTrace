package com.dmer.neoreaderrecords

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AutoRefreshLog {
    private val lock = Any()

    fun i(context: Context, msg: String) {
        write(context, "INFO", msg)
    }

    fun e(context: Context, msg: String, t: Throwable? = null) {
        val tail = if (t == null) "" else " | ${t.javaClass.simpleName}: ${t.message}"
        write(context, "ERROR", msg + tail)
    }

    private fun write(context: Context, level: String, msg: String) {
        synchronized(lock) {
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val safeMsg = sanitize(msg)
            val line = "$now [$level] $safeMsg\n"
            SafeLogStore.appendText(context, SafeLogStore.AUTO_REFRESH_LOG_NAME, line)
        }
    }

    private fun sanitize(msg: String): String {
        val sb = StringBuilder(msg.length)
        for (ch in msg) {
            when {
                ch == '\n' || ch == '\r' || ch == '\t' -> sb.append(' ')
                ch.isISOControl() -> sb.append('?')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
