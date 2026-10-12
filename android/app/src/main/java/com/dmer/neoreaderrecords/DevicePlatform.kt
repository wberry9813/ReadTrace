package com.dmer.neoreaderrecords

import android.os.Build
import java.util.Locale

object DevicePlatform {
    private fun identityValues(): List<String> {
        return listOf(
            Build.MANUFACTURER,
            Build.BRAND,
            Build.MODEL,
            Build.DEVICE,
            Build.PRODUCT
        )
    }

    fun identityRawUpper(): String {
        return identityValues().joinToString(" ").uppercase(Locale.ROOT)
    }

    fun isHanvonDevice(): Boolean {
        return identityValues().any { it.orEmpty().contains("HANVON", ignoreCase = true) || it.orEmpty().contains("汉王", ignoreCase = true) }
    }

    fun isHisenseDevice(): Boolean {
        return identityValues().any { value ->
            value.orEmpty().contains("HISENSE", ignoreCase = true) ||
                value.orEmpty().contains("海信", ignoreCase = true)
        }
    }

    fun isBooxDevice(): Boolean {
        return identityValues().any {
            val value = it.orEmpty()
            value.contains("ONYX", ignoreCase = true) || value.contains("BOOX", ignoreCase = true)
        }
    }

    fun identityText(): String {
        return "manufacturer=${Build.MANUFACTURER}, brand=${Build.BRAND}, model=${Build.MODEL}, device=${Build.DEVICE}, product=${Build.PRODUCT}, sdk=${Build.VERSION.SDK_INT}"
    }
}
