package com.dmer.neoreaderrecords

data class BooxDevicePreset(
    val key: String,
    val label: String,
    val inchText: String,
    val widthPx: Int,
    val heightPx: Int
) {
    fun displayText(): String = "$label $inchText ${heightPx}x$widthPx"
}

object BooxDevicePresets {
    const val DEFAULT_KEY = "LEAF5"
    const val CUSTOM_KEY = "CUSTOM"

    val boox: List<BooxDevicePreset> = listOf(
        BooxDevicePreset("POKE6S", "Poke6S", "6英寸", 758, 1024),
        BooxDevicePreset("POKE6", "Poke6", "6英寸", 1072, 1448),
        BooxDevicePreset("POKE7", "Poke7", "6英寸", 1072, 1448),
        BooxDevicePreset("POKE7_PRO", "Poke7 Pro", "6英寸", 1072, 1448),
        BooxDevicePreset("P6", "P6 / P6+", "6.13英寸", 824, 1648),
        BooxDevicePreset("P6_PRO", "P6 Pro / P6 Pro C", "6.13英寸", 824, 1648),
        BooxDevicePreset("PALMA", "Palma", "6.13英寸", 824, 1648),
        BooxDevicePreset("LEAF5", "Leaf5", "7英寸", 1264, 1680),
        BooxDevicePreset("LEAF5C", "Leaf5C", "7英寸", 1264, 1680),
        BooxDevicePreset("LEAF5_PLUS", "Leaf5+", "7英寸", 1264, 1680),
        BooxDevicePreset("PAGE", "Page", "7英寸", 1264, 1680),
        BooxDevicePreset("NOTE_X5_MINI", "Note X5 mini", "7.8英寸", 1404, 1872),
        BooxDevicePreset("NOTE_AIR3", "Note Air3", "10.3英寸", 1404, 1872),
        BooxDevicePreset("NOTE_X5S", "Note X5S", "10.3英寸", 1404, 1872),
        BooxDevicePreset("NOTE_X5", "Note X5", "10.3英寸", 1860, 2480),
        BooxDevicePreset("NOTEX6", "NoteX6", "10.3英寸", 1860, 2480),
        BooxDevicePreset("T10C", "T10 C / T10C+", "10.3英寸", 1860, 2480),
        BooxDevicePreset("TAB10C_PRO", "Tab 10C Pro", "10.3英寸", 1860, 2480),
        BooxDevicePreset("NOTE_AIR3C", "Note Air3 C", "10.3英寸", 1860, 2480),
        BooxDevicePreset("T13C", "T13 C", "13.3英寸", 2400, 3200)
    )

    val hanvon: List<BooxDevicePreset> = listOf(
        BooxDevicePreset("HANVON_CLEAR6", "汉王 Clear 6 / Clear 6 Pro", "6英寸", 1072, 1448),
        BooxDevicePreset("HANVON_CLEAR7", "汉王 Clear 7 / Clear 7 Turbo", "7英寸", 1264, 1680),
        BooxDevicePreset("HANVON_N10_MINI", "汉王 N10 mini", "7.8英寸", 1404, 1872),
        BooxDevicePreset("HANVON_N10", "汉王 N10 / N10 Touch / N10 2024", "10.3英寸", 1404, 1872),
        BooxDevicePreset("HANVON_N10_PRO", "汉王 N10 Pro / N10 Pro 2024", "10.3英寸", 1860, 2480),
        BooxDevicePreset("HANVON_N10_MAX", "汉王 N10 Max", "13.3英寸", 1650, 2200)
    )

    val hisense: List<BooxDevicePreset> = listOf(
        BooxDevicePreset("HISENSE_A5", "海信 A5", "5.84英寸", 720, 1440),
        BooxDevicePreset("HISENSE_A5_PRO", "海信 A5 Pro", "5.84英寸", 720, 1440),
        BooxDevicePreset("HISENSE_A5_PRO_CC", "海信 A5 Pro CC", "5.84英寸", 720, 1440),
        BooxDevicePreset("HISENSE_A7", "海信 A7", "6.7英寸", 720, 1680),
        BooxDevicePreset("HISENSE_A7_CC", "海信 A7 CC", "6.7英寸", 720, 1680),
        BooxDevicePreset("HISENSE_A9", "海信 A9", "6.1英寸", 1080, 1440),
        BooxDevicePreset("HISENSE_A9_PRO", "海信 A9 Pro", "6.1英寸", 1080, 1440)
    )

    val all: List<BooxDevicePreset> = boox + hanvon + hisense

    fun visibleForCurrentDevice(): List<BooxDevicePreset> {
        return when {
            DevicePlatform.isHanvonDevice() -> hanvon
            DevicePlatform.isHisenseDevice() -> hisense
            else -> boox
        }
    }

    fun byKey(key: String?): BooxDevicePreset {
        return all.firstOrNull { it.key == key } ?: all.first { it.key == DEFAULT_KEY }
    }
}
