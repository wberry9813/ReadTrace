package com.dmer.neoreaderrecords

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugEventLog {
    const val LOG_NAME = "neoreader_debug_history.log"
    private val lock = Any()

    fun i(context: Context, message: String) {
        synchronized(lock) {
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            SafeLogStore.appendText(context, LOG_NAME, "$now ${sanitize(message)}\n")
        }
    }

    private fun sanitize(message: String): String {
        val sb = StringBuilder(message.length)
        for (ch in message) {
            when {
                ch == '\n' || ch == '\r' || ch == '\t' -> sb.append(' ')
                ch.isISOControl() -> sb.append('?')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
