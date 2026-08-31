package com.dmer.neoreaderrecords

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.io.File
import java.io.FileWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    companion object {
        private const val FONT_ENTRY_SEP = "@@"
        private const val SPONSOR_QR_URL = "https://dmer.work:15060/images/2026/07/01/IMG_7329_neutral.JPG.png"
        private const val XHS_PROFILE_URL = "https://xhslink.com/m/1QDye14ktkf"
        private const val PREVIEW_DEBOUNCE_MS = 800L
    }

    private class SimpleItemSelectedListener(val onChange: () -> Unit) : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onChange()
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    private val metadataUri = Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata")
    private val statsUri = Uri.parse("content://com.onyx.kreader.statistics.provider/OnyxStatisticsModel")

    private lateinit var includeUnreadCheck: CheckBox
    private lateinit var showChartCheck: CheckBox
    private lateinit var showProgressStatusCheck: CheckBox
    private lateinit var showAuthorCheck: CheckBox
    private lateinit var showBookDurationCheck: CheckBox
    private lateinit var minDurationInput: EditText
    private lateinit var topNInput: EditText
    private lateinit var titleInput: EditText
    private lateinit var titleSizeInput: EditText
    private lateinit var bodySizeInput: EditText
    private lateinit var serialNumberSizeInput: EditText
    private lateinit var serialCustomInput: EditText
    private lateinit var customWallpaperWidthInput: EditText
    private lateinit var customWallpaperHeightInput: EditText
    private lateinit var noteInput: EditText
    private lateinit var weekStartText: TextView
    private lateinit var weekEndText: TextView
    private lateinit var sourceGroup: RadioGroup
    private lateinit var periodGroup: RadioGroup
    private lateinit var progressModeGroup: RadioGroup
    private lateinit var readingFilterGroup: RadioGroup
    private lateinit var timeUnitGroup: RadioGroup
    private lateinit var wallpaperModeGroup: RadioGroup
    private lateinit var statsTemplateGroup: RadioGroup
    private lateinit var calendarStackOrderGroup: RadioGroup
    private lateinit var booxDevicePresetGroup: RadioGroup
    private lateinit var coverFitModeGroup: RadioGroup
    private lateinit var serialModeGroup: RadioGroup
    private lateinit var footerModeGroup: RadioGroup
    private lateinit var barcodeWidthGroup: RadioGroup
    private lateinit var barcodeGapGroup: RadioGroup
    private lateinit var chartStyleGroup: RadioGroup
    private lateinit var yAxisModeGroup: RadioGroup
    private lateinit var showPeakLabelCheck: CheckBox
    private lateinit var yAxisMaxInput: EditText
    private lateinit var readingDataStoreCheck: CheckBox
    private lateinit var autoRefreshCheck: CheckBox
    private lateinit var autoModeGroup: RadioGroup
    private lateinit var autoDailyTimeInput: EditText
    private lateinit var autoMinIntervalInput: EditText
    private lateinit var autoModeHintText: TextView
    private lateinit var autoStateText: TextView
    private lateinit var autoExactAlarmButton: Button
    private lateinit var updateStatusText: TextView
    private lateinit var wereadApiKeyInput: EditText
    private lateinit var wereadStatsModeGroup: RadioGroup
    private lateinit var wereadStatusText: TextView
    private lateinit var pickFontDirBtn: Button
    private lateinit var titleFontSpinner: Spinner
    private lateinit var bodyFontSpinner: Spinner
    private lateinit var fontScanText: TextView
    private lateinit var statusText: TextView
    private lateinit var changeStateText: TextView

    private lateinit var settingsPage: View
    private lateinit var previewPage: View
    private lateinit var previewImage: ImageView
    private lateinit var previewText: TextView

    private var currentPageKey: String = "settings"
    private var updateTopNavState: (() -> Unit)? = null
    private var lastSavedPath: String? = null
    private var previewBitmap: Bitmap? = null
    private var previewPresetText: String = ""
    private var isInitializingUi: Boolean = false
    private var selectedWeekStartYmd: String = ""
    private var selectedWeekEndYmd: String = ""
    private val systemFonts: MutableList<String> = mutableListOf()
    private var fontScanReport: String = ""
    private var barcodeDebugReport: String = ""
    private var fontPermissionDebug: String = ""
    private var metadataDebugReport: String = ""
    private var metadataRowsDebugReport: String = ""
    private var localCalendarProbeReport: String = ""
    private var uiDebugReport: String = ""
    private var isCheckingUpdates: Boolean = false
    private var isTestingWeRead: Boolean = false
    private var lastWeReadStatsDebug: String = ""
    private var lastWeReadCoverDebug: String = ""
    private var lastWeReadWallpaperDebug: String = ""
    private val debugLogName = "neoreader_debug_log.txt"
    private var selectedFontDirUri: String? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private val previewExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "wallpaper-preview").apply { isDaemon = true }
    }
    private val debugExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "wallpaper-debug").apply { isDaemon = true }
    }
    private var previewDebounceRunnable: Runnable? = null
    @Volatile private var previewRequestId: Int = 0
    private var isApplyingSettings: Boolean = false
    private var autoRuntimeInitialized: Boolean = false

    private val pickFontTreeLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                // OpenDocumentTree already grants a temporary read permission. Persist it explicitly.
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                fontPermissionDebug = "takePersistableUriPermission=ok uri=$uri"
            } catch (e: Exception) {
                fontPermissionDebug = "takePersistableUriPermission=fail ${e.javaClass.simpleName}:${e.message}"
            }
            selectedFontDirUri = uri.toString()
            getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE).edit().putString("font_tree_uri", selectedFontDirUri).apply()
            reloadFontsFromSources()
            writeDebugLog("font_tree_selected")
            applySettingsPreview()
        }
    }

    data class BookItem(val title: String, val author: String?, val progress: String?, val status: Int, val path: String?)
    private data class CalendarMetaBook(
        val path: String,
        val title: String,
        val author: String,
        val hasCoverHint: Boolean
    )
    private data class CalendarDayStat(
        var events: Int = 0,
        var withPath: Int = 0,
        var orphan: Int = 0,
        var matched: Int = 0,
        var unmatched: Int = 0,
        var durationMs: Long = 0L,
        val books: LinkedHashMap<String, Long> = linkedMapOf(),
        val coverBooks: LinkedHashSet<String> = linkedSetOf()
    )

    enum class DataSourceMode { DURATION, PATH_SESSION, METADATA_ACCESS, WEREAD, MIXED }
    enum class PeriodMode { TODAY, YESTERDAY, THIS_WEEK, LAST_WEEK, THIS_MONTH, LAST_7_DAYS, LAST_30_DAYS, CUSTOM }
    enum class ReadingFilterMode { ALL, READING_ONLY, FINISHED_ONLY }
    enum class ChartStyleMode { LINE, BAR }
    enum class YAxisMode { AUTO, FIXED }

    data class Settings(
        val includeUnread: Boolean,
        val showChart: Boolean,
        val showProgressStatus: Boolean,
        val showAuthor: Boolean,
        val showBookDuration: Boolean,
        val minDurationMinutes: Int,
        val topN: Int,
        val weekStartYmd: String,
        val weekEndYmd: String,
        val periodMode: PeriodMode,
        val readingFilterMode: ReadingFilterMode,
        val sourceMode: DataSourceMode,
        val wallpaperMode: String,
        val statsTemplate: String,
        val calendarStackOrder: String,
        val coverFitMode: String,
        val progressMode: String,
        val timeUnit: String,
        val receiptTitle: String,
        val receiptTitleSize: Float,
        val receiptBodySize: Float,
        val serialNumberMode: String,
        val serialNumberCustom: String,
        val serialNumberSize: Float,
        val booxDevicePreset: String,
        val customWallpaperWidth: Int,
        val customWallpaperHeight: Int,
        val footerMode: String,
        val barcodeWidthScale: Float,
        val barcodeGapMode: String,
        val noteText: String,
        val chartStyleMode: ChartStyleMode,
        val showPeakLabel: Boolean,
        val yAxisMode: YAxisMode,
        val yAxisFixedMaxMinutes: Int,
        val titleFont: String,
        val bodyFont: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAllFilesAccessPermission()
        setupUi()
    }

    override fun onDestroy() {
        previewRequestId += 1
        previewDebounceRunnable?.let(uiHandler::removeCallbacks)
        previewDebounceRunnable = null
        previewExecutor.shutdownNow()
        debugExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density + 0.5f).toInt()
    }

    private fun checkAllFilesAccessPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Return from permission/settings pages: refresh font scan and preview context.
        if (!isInitializingUi) {
            validateFontTreePermission()
            reloadFontsFromSources()
            if (
                AutoRefreshConfig.isEnabled(this) &&
                AutoRefreshConfig.mode(this) == AutoRefreshConfig.MODE_DAILY
            ) {
                AutoRefreshScheduler.reschedule(this)
            }
            updateAutoRuntimeState()
            updateReleaseStatusFromCache()
            checkForUpdatesIfNeeded(force = false)
            writeDebugLog("onResume_rescan")
        }
    }

    private fun validateFontTreePermission() {
        val tree = selectedFontDirUri ?: return
        val persisted = contentResolver.persistedUriPermissions
        val ok = persisted.any { it.uri.toString() == tree && it.isReadPermission }
        fontPermissionDebug = "persistedCheck uri=$tree readGranted=$ok persistedCount=${persisted.size}"
        if (!ok) {
            // Permission is gone after reboot/app restart, force re-select to avoid silent empty list.
            selectedFontDirUri = null
            getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE).edit().remove("font_tree_uri").apply()
        }
    }

    private fun reloadFontsFromSources() {
        val oldTitle = titleFontSpinner.selectedItem?.toString()
        val oldBody = bodyFontSpinner.selectedItem?.toString()
        val latestFonts = loadSystemFonts()
        systemFonts.clear()
        systemFonts.addAll(latestFonts)
        (titleFontSpinner.adapter as? ArrayAdapter<String>)?.apply {
            clear()
            addAll(latestFonts)
            notifyDataSetChanged()
        }
        (bodyFontSpinner.adapter as? ArrayAdapter<String>)?.apply {
            clear()
            addAll(latestFonts)
            notifyDataSetChanged()
        }
        oldTitle?.let { v -> systemFonts.indexOf(v).takeIf { it >= 0 }?.let { titleFontSpinner.setSelection(it) } }
        oldBody?.let { v -> systemFonts.indexOf(v).takeIf { it >= 0 }?.let { bodyFontSpinner.setSelection(it) } }
        fontScanText.text = fontScanReport
    }

    private fun fontLabel(entry: String): String {
        val idx = entry.indexOf(FONT_ENTRY_SEP)
        return if (idx > 0) entry.substring(0, idx) else entry
    }

    private fun fontSpec(entry: String): String {
        val idx = entry.indexOf(FONT_ENTRY_SEP)
        return if (idx > 0) entry.substring(idx + FONT_ENTRY_SEP.length) else entry
    }

    private fun findSpinnerIndexBySpec(spec: String): Int {
        val idx = systemFonts.indexOfFirst { fontSpec(it) == spec }
        return idx.coerceAtLeast(0)
    }

    private fun buildFontAdapter(items: List<String>): ArrayAdapter<String> {
        return object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as? TextView)?.text = fontLabel(getItem(position) ?: "")
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent)
                (v as? TextView)?.text = fontLabel(getItem(position) ?: "")
                return v
            }
        }
    }

    private fun setupUi() {
        isInitializingUi = true
        val prefs = getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE)
        selectedFontDirUri = prefs.getString("font_tree_uri", null)
        systemFonts.clear()
        systemFonts.addAll(loadSystemFonts())
        selectedWeekStartYmd = prefs.getString("week_start", currentWeekStartYmd()) ?: currentWeekStartYmd()
        selectedWeekEndYmd = prefs.getString("week_end", currentWeekEndYmd()) ?: currentWeekEndYmd()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.WHITE)
        }
        fun inkBorder(stroke: Int = dp(2), fill: Int = Color.WHITE): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(fill)
                setStroke(stroke, Color.BLACK)
            }
        }
        fun makeNavItem(textValue: String, key: String, onTap: () -> Unit): TextView {
            return TextView(this).apply {
                text = textValue
                textSize = 18f
                gravity = Gravity.CENTER
                setTypeface(Typeface.DEFAULT_BOLD)
                minimumHeight = dp(56)
                setPadding(dp(6), dp(8), dp(6), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener {
                    currentPageKey = key
                    onTap()
                }
            }
        }
        fun makeActionItem(textValue: String, primary: Boolean, onTap: () -> Unit): TextView {
            return TextView(this).apply {
                text = textValue
                textSize = 18f
                gravity = Gravity.CENTER
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(if (primary) Color.WHITE else Color.BLACK)
                background = inkBorder(dp(2), if (primary) Color.BLACK else Color.WHITE)
                minimumHeight = dp(56)
                setPadding(dp(6), dp(8), dp(6), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(0, 0, dp(6), 0)
                }
                setOnClickListener { onTap() }
            }
        }
        fun dividerVertical(): View = View(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT)
        }

        val navGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = inkBorder()
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 0, 0, dp(8))
            }
        }
        val navSettings = makeNavItem("设置", "settings") { showSettingsPage() }
        val navPreview = makeNavItem("预览", "preview") { showPreviewPage() }
        val refreshAction = makeActionItem("刷新预览", false) { refreshPreviewData() }
        val generateAction = makeActionItem("生成壁纸", true) { generateAndSaveFromCurrentSettings() }
        refreshAction.background = null
        refreshAction.setTextColor(Color.BLACK)
        refreshAction.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        generateAction.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        navGroup.addView(navSettings)
        navGroup.addView(dividerVertical())
        navGroup.addView(navPreview)
        navGroup.addView(dividerVertical())
        navGroup.addView(refreshAction)
        navGroup.addView(dividerVertical())
        navGroup.addView(generateAction)

        changeStateText = TextView(this).apply {
            text = "状态：初始化"
            textSize = 13f
            setPadding(dp(2), 0, dp(2), dp(9))
            setTextColor(Color.DKGRAY)
        }
        updateTopNavState = {
            val items = listOf(Triple("settings", "设置", navSettings), Triple("preview", "预览", navPreview))
            items.forEach { (key, label, item) ->
                val selected = currentPageKey == key
                item.text = "${if (selected) "●" else "○"} $label"
                item.setTextColor(Color.BLACK)
                item.setBackgroundColor(Color.WHITE)
                item.setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
                item.contentDescription = "$label，${if (selected) "当前页面" else "点击打开"}"
            }
        }

        settingsPage = buildSettingsPage(prefs)
        previewPage = buildPreviewPage()
        appendUiDebug("setupUi pages built settings=${settingsPage.javaClass.simpleName} preview=${previewPage.javaClass.simpleName}")

        root.addView(navGroup)
        root.addView(changeStateText)
        root.addView(settingsPage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(previewPage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)
        showSettingsPage()
        isInitializingUi = false
        applySettingsPreview()
        checkForUpdatesIfNeeded(force = false)
        writeDebugLog("setupUi_done")
        startReadingStoreBootstrapIfNeeded()
    }

    private fun startReadingStoreBootstrapIfNeeded() {
        val prefs = getSharedPreferences(AutoRefreshConfig.PREFS_NAME, Context.MODE_PRIVATE)
        if (!AutoRefreshConfig.isReadingDataStoreEnabled(this)) {
            appendUiDebug("readingStoreMaintenance skip disabled")
            return
        }
        val key = "reading_store_bootstrap_neo_month_v1_done"
        Thread {
            val bootstrapDone = prefs.getBoolean(key, false)
            val bootstrapOk = if (bootstrapDone) {
                appendUiDebug("readingStoreBootstrap skip alreadyDone")
                true
            } else {
                AutoWallpaperGenerator.bootstrapReadingStoreIfNeeded(applicationContext).also { ok ->
                    if (ok) prefs.edit().putBoolean(key, true).apply()
                }
            }
            val wallpaperMode = prefs.getString("wallpaper_mode", "STATS") ?: "STATS"
            val incrementalOk = wallpaperMode == "CALENDAR" ||
                AutoWallpaperGenerator.syncRecentNeoReadingStore(applicationContext, "app_start")
            val sourceMode = prefs.getString("source_mode", "DURATION") ?: "DURATION"
            val weReadOk = if (sourceMode == "WEREAD" || sourceMode == "MIXED") {
                WeReadReadingSync.syncCurrentMonth(applicationContext, "app_start")
            } else {
                true
            }
            appendUiDebug(
                "readingStoreMaintenance finished bootstrapOk=$bootstrapOk incrementalOk=$incrementalOk weReadOk=$weReadOk sourceMode=$sourceMode wallpaperMode=$wallpaperMode"
            )
        }.apply {
            name = "reading-store-bootstrap"
            isDaemon = true
            start()
        }
    }

    private fun appendUiDebug(message: String) {
        val now = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        uiDebugReport += "[$now] $message\n"
    }

    private fun deviceIdentityText(): String {
        return listOf(
            "manufacturer=${android.os.Build.MANUFACTURER}",
            "brand=${android.os.Build.BRAND}",
            "model=${android.os.Build.MODEL}",
            "device=${android.os.Build.DEVICE}",
            "product=${android.os.Build.PRODUCT}"
        ).joinToString(", ")
    }

    private fun detectBooxDevicePresetOrNull(): String? {
        val raw = listOf(
            android.os.Build.MANUFACTURER,
            android.os.Build.BRAND,
            android.os.Build.MODEL,
            android.os.Build.DEVICE,
            android.os.Build.PRODUCT
        ).joinToString(" ").uppercase(Locale.ROOT)

        return when {
            raw.contains("PALMA") -> "PALMA"
            raw.contains("POKE") && raw.contains("7") && raw.contains("PRO") -> "POKE7_PRO"
            raw.contains("POKE") && raw.contains("7") -> "POKE7"
            raw.contains("POKE") && raw.contains("6") && raw.contains("S") -> "POKE6S"
            raw.contains("POKE") && raw.contains("6") -> "POKE6"
            raw.contains("P6") && raw.contains("PRO") -> "P6_PRO"
            raw.contains("P6") -> "P6"
            raw.contains("LEAF") && raw.contains("5") && raw.contains("C") -> "LEAF5C"
            raw.contains("LEAF") && raw.contains("5") && raw.contains("+") -> "LEAF5_PLUS"
            raw.contains("LEAF") && raw.contains("5") -> "LEAF5"
            raw.contains("NOTE") && raw.contains("X5") && raw.contains("MINI") -> "NOTE_X5_MINI"
            raw.contains("NOTE") && raw.contains("X5S") -> "NOTE_X5S"
            raw.contains("NOTE") && raw.contains("X5") -> "NOTE_X5"
            raw.contains("NOTEX6") || (raw.contains("NOTE") && raw.contains("X6")) -> "NOTEX6"
            raw.contains("TAB") && raw.contains("10C") && raw.contains("PRO") -> "TAB10C_PRO"
            raw.contains("T10") && raw.contains("C") -> "T10C"
            raw.contains("T13") && raw.contains("C") -> "T13C"
            raw.contains("NOTE") && raw.contains("AIR") && raw.contains("3") && raw.contains("C") -> "NOTE_AIR3C"
            raw.contains("NOTE") && raw.contains("AIR") && raw.contains("3") -> "NOTE_AIR3"
            raw.contains("PAGE") -> "PAGE"
            else -> null
        }
    }

    private fun detectBooxDevicePreset(): String {
        return detectBooxDevicePresetOrNull() ?: BooxDevicePresets.DEFAULT_KEY
    }

    private fun booxPresetKeyByRadioId(id: Int): String {
        if (id == 1301) return BooxDevicePresets.CUSTOM_KEY
        return BooxDevicePresets.all.getOrNull(id - 1302)?.key ?: BooxDevicePresets.DEFAULT_KEY
    }

    private fun wallpaperSizeDisplayText(settings: Settings): String {
        return if (settings.booxDevicePreset == BooxDevicePresets.CUSTOM_KEY) {
            "自定义 ${settings.customWallpaperWidth}x${settings.customWallpaperHeight}"
        } else {
            BooxDevicePresets.byKey(settings.booxDevicePreset).displayText()
        }
    }

    private fun buildSettingsPage(prefs: android.content.SharedPreferences): View {
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.WHITE) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(48))
        }
        val hiddenHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }

        fun inkBorder(stroke: Int = dp(2)): GradientDrawable {
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.WHITE)
                setStroke(stroke, Color.BLACK)
            }
        }

        fun createDivider(thickness: Int = dp(2), topMargin: Int = 0, bottomMargin: Int = dp(16)) = View(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, thickness).apply {
                setMargins(0, topMargin, 0, bottomMargin)
            }
        }

        fun addSectionTitle(text: String, hint: String? = null) {
            root.addView(TextView(this).apply {
                this.text = text
                textSize = 24f
                setTextColor(Color.BLACK)
                setTypeface(Typeface.DEFAULT_BOLD)
                setPadding(0, dp(28), 0, if (hint == null) dp(8) else dp(4))
            })
            if (hint != null) {
                root.addView(TextView(this).apply {
                    this.text = hint
                    textSize = 14f
                    setTextColor(Color.DKGRAY)
                    setPadding(0, 0, 0, dp(12))
                })
            }
            root.addView(createDivider(dp(2), 0, dp(18)))
        }

        fun addHint(hint: String): TextView {
            return TextView(this).apply {
                text = hint
                textSize = 13f
                setTextColor(Color.DKGRAY)
                setPadding(0, 0, 0, dp(10))
                root.addView(this)
            }
        }

        fun makeCheck(checked: Boolean): CheckBox {
            return CheckBox(this).apply {
                isChecked = checked
                hiddenHost.addView(this)
            }
        }

        fun makeInput(text: String): EditText {
            return EditText(this).apply {
                setText(text)
                hiddenHost.addView(this)
            }
        }

        fun makeRadioGroup(options: List<Pair<Int, String>>, selectedId: Int, orientation: Int = RadioGroup.VERTICAL): RadioGroup {
            return RadioGroup(this).apply {
                this.orientation = orientation
                options.forEach { (id, label) ->
                    addView(RadioButton(context).apply {
                        this.id = id
                        text = label
                    })
                }
                check(selectedId)
                hiddenHost.addView(this)
            }
        }

        fun selectedId(saved: String, fallback: Int, pairs: List<Pair<Int, String>>, names: List<String>): Int {
            val index = names.indexOf(saved)
            return if (index >= 0) pairs.getOrNull(index)?.first ?: fallback else fallback
        }

        fun bindToggle(label: String, check: CheckBox): LinearLayout {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(0, dp(6), 0, dp(10))
                isClickable = true
                isFocusable = true
                contentDescription = label
            }
            row.addView(TextView(this).apply {
                text = label
                textSize = 20f
                setTextColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val box = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
                setPadding(dp(10), dp(10), dp(10), dp(10))
                background = inkBorder()
            }
            val inner = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            fun render() { inner.setBackgroundColor(if (check.isChecked) Color.BLACK else Color.TRANSPARENT) }
            render()
            box.addView(inner)
            row.addView(box)
            row.setOnClickListener {
                check.isChecked = !check.isChecked
                render()
            }
            root.addView(row)
            return row
        }

        fun bindSegmented(
            label: String,
            group: RadioGroup,
            options: List<Pair<Int, String>>,
            isVertical: Boolean = false
        ): LinearLayout {
            val wrap = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 0)
            }
            wrap.addView(TextView(this).apply {
                text = label
                textSize = 20f
                setTextColor(Color.BLACK)
                setPadding(0, 16, 0, 16)
            })
            val allViews = mutableListOf<Pair<Int, TextView>>()
            fun render() {
                allViews.forEach { (id, tv) ->
                    val selected = group.checkedRadioButtonId == id
                    val original = tv.tag as String
                    tv.text = "${if (selected) "●" else "○"} $original"
                    tv.setBackgroundColor(Color.WHITE)
                    tv.setTextColor(Color.BLACK)
                    tv.setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
                    tv.contentDescription = "$original，${if (selected) "已选择" else "未选择"}"
                }
            }
            if (isVertical) {
                val segmented = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = inkBorder()
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 0, dp(18))
                    }
                }
                options.forEachIndexed { index, (id, text) ->
                    val tv = TextView(this).apply {
                        this.text = text
                        tag = text
                        textSize = if (text.contains("\n")) 16f else 18f
                        minimumHeight = dp(60)
                        gravity = Gravity.CENTER_VERTICAL
                        setLineSpacing(dp(2).toFloat(), 1.0f)
                        setPadding(dp(18), dp(12), dp(18), dp(12))
                        setOnClickListener {
                            group.check(id)
                            render()
                        }
                    }
                    allViews.add(id to tv)
                    segmented.addView(tv)
                    if (index < options.size - 1) {
                        segmented.addView(View(this).apply {
                            setBackgroundColor(Color.BLACK)
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
                        })
                    }
                }
                wrap.addView(segmented)
            } else {
                val segmented = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    background = inkBorder()
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins(0, 0, 0, dp(18))
                    }
                }
                options.chunked(3).forEachIndexed { rowIndex, rowOptions ->
                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    }
                    rowOptions.forEachIndexed { colIndex, (id, text) ->
                        val tv = TextView(this).apply {
                            this.text = text
                            tag = text
                            textSize = if (text.contains("\n")) 13f else 16f
                            gravity = Gravity.CENTER
                            minimumHeight = dp(60)
                            setLineSpacing(dp(2).toFloat(), 1.0f)
                            setPadding(dp(6), dp(10), dp(6), dp(10))
                            layoutParams = LinearLayout.LayoutParams(0, dp(60), 1f)
                            setOnClickListener {
                                group.check(id)
                                render()
                            }
                        }
                        allViews.add(id to tv)
                        row.addView(tv)
                        if (colIndex < rowOptions.size - 1) {
                            row.addView(View(this).apply {
                                setBackgroundColor(Color.BLACK)
                                layoutParams = LinearLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT)
                            })
                        }
                    }
                    while (rowOptions.size < 3 && row.childCount < 5) {
                        row.addView(View(this).apply {
                            setBackgroundColor(Color.BLACK)
                            layoutParams = LinearLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT)
                        })
                        row.addView(View(this).apply {
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        })
                    }
                    segmented.addView(row)
                    if (rowIndex < options.chunked(3).size - 1) {
                        segmented.addView(View(this).apply {
                            setBackgroundColor(Color.BLACK)
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2))
                        })
                    }
                }
                wrap.addView(segmented)
            }
            render()
            root.addView(wrap)
            return wrap
        }

        fun bindNumberControl(
            label: String,
            target: EditText,
            min: Int,
            max: Int,
            step: Int = 1,
            presets: List<Int> = emptyList()
        ): LinearLayout {
            val wrap = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(8), 0, dp(18))
            }
            wrap.addView(TextView(this).apply {
                text = label
                textSize = 20f
                setTextColor(Color.BLACK)
                setPadding(0, 0, 0, dp(8))
            })

            val controlRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                background = inkBorder()
            }
            val valueText = TextView(this).apply {
                textSize = 24f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                minimumHeight = dp(64)
                layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f)
                isClickable = true
                isFocusable = true
                contentDescription = "$label，点击精确输入"
            }

            val presetViews = mutableListOf<Pair<Int, TextView>>()
            fun currentValue(): Int {
                return target.text.toString().trim().toIntOrNull()?.coerceIn(min, max) ?: min
            }
            fun renderValue(v: Int) {
                val next = v.coerceIn(min, max)
                valueText.text = next.toString()
                valueText.contentDescription = "$label，当前值 $next，点击精确输入"
                presetViews.forEach { (preset, view) ->
                    val selected = preset == next
                    view.text = "${if (selected) "●" else "○"} $preset"
                    view.setTypeface(Typeface.DEFAULT, if (selected) Typeface.BOLD else Typeface.NORMAL)
                    view.contentDescription = "$label $preset，${if (selected) "当前值" else "点击选择"}"
                }
            }
            fun setValue(v: Int) {
                val next = v.coerceIn(min, max)
                renderValue(next)
                if (target.text.toString() != next.toString()) {
                    target.setText(next.toString())
                    target.setSelection(target.text.length)
                }
            }
            fun makeStepButton(symbol: String, delta: Int): TextView {
                return TextView(this).apply {
                    text = symbol
                    textSize = 32f
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(Color.BLACK)
                    gravity = Gravity.CENTER
                    minimumHeight = dp(64)
                    layoutParams = LinearLayout.LayoutParams(dp(72), dp(64))
                    isClickable = true
                    isFocusable = true
                    contentDescription = if (delta < 0) "$label 减少 $step" else "$label 增加 $step"
                    setOnClickListener { setValue(currentValue() + delta) }
                }
            }

            controlRow.addView(makeStepButton("−", -step))
            controlRow.addView(View(this).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT)
            })
            controlRow.addView(valueText)
            controlRow.addView(View(this).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT)
            })
            controlRow.addView(makeStepButton("+", step))
            wrap.addView(controlRow)

            valueText.setOnClickListener {
                val edit = EditText(this).apply {
                    setText(currentValue().toString())
                    selectAll()
                    textSize = 22f
                    inputType = InputType.TYPE_CLASS_NUMBER
                    minimumHeight = dp(64)
                    setPadding(dp(16), dp(8), dp(16), dp(8))
                }
                AlertDialog.Builder(this)
                    .setTitle("$label ($min-$max)")
                    .setView(edit)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("确定") { _, _ ->
                        setValue(edit.text.toString().trim().toIntOrNull() ?: currentValue())
                    }
                    .show()
            }

            val validPresets = presets.distinct().filter { it in min..max }
            validPresets.chunked(3).forEach { rowPresets ->
                val presetRow = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(8), 0, 0)
                }
                rowPresets.forEach { preset ->
                    val presetView = TextView(this).apply {
                        textSize = 17f
                        setTextColor(Color.BLACK)
                        gravity = Gravity.CENTER
                        minimumHeight = dp(52)
                        background = inkBorder(dp(1))
                        layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                            setMargins(dp(3), 0, dp(3), 0)
                        }
                        setOnClickListener { setValue(preset) }
                    }
                    presetViews.add(preset to presetView)
                    presetRow.addView(presetView)
                }
                repeat(3 - rowPresets.size) {
                    presetRow.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                            setMargins(dp(3), 0, dp(3), 0)
                        }
                    })
                }
                wrap.addView(presetRow)
            }

            renderValue(currentValue())
            target.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val v = s?.toString()?.trim()?.toIntOrNull()?.coerceIn(min, max) ?: min
                    renderValue(v)
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            root.addView(wrap)
            return wrap
        }

        fun openTextEditDialog(title: String, target: EditText, numericOnly: Boolean = false, maxDigits: Int? = null) {
            val edit = EditText(this).apply {
                setText(target.text.toString())
                setSelection(text.length)
                textSize = 20f
                minimumHeight = dp(64)
                setPadding(dp(16), dp(8), dp(16), dp(8))
                if (numericOnly) inputType = InputType.TYPE_CLASS_NUMBER
            }
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(edit)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定") { _, _ ->
                    val raw = edit.text.toString()
                    val next = if (numericOnly) raw.filter { it.isDigit() }.let { v -> maxDigits?.let { v.take(it) } ?: v } else raw
                    target.setText(next)
                    target.setSelection(target.text.length)
                }
                .show()
        }

        fun bindInputRow(label: String, valueProvider: () -> String, onClick: (() -> Unit)? = null): Pair<LinearLayout, TextView> {
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(0, dp(8), 0, dp(18))
                }
                background = inkBorder()
                isClickable = onClick != null
                isFocusable = onClick != null
                contentDescription = label
                setOnClickListener { onClick?.invoke() }
            }
            box.addView(TextView(this).apply {
                text = label
                textSize = 20f
                setTextColor(Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            val value = TextView(this).apply {
                text = valueProvider()
                textSize = 20f
                setTypeface(Typeface.DEFAULT_BOLD)
                setTextColor(Color.BLACK)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            box.addView(value)
            root.addView(box)
            return box to value
        }

        fun bindEditRow(label: String, target: EditText, numericOnly: Boolean = false, maxDigits: Int? = null): LinearLayout {
            val (row, value) = bindInputRow(label, { target.text.toString().ifBlank { "点击设置" } }) {
                openTextEditDialog(label, target, numericOnly, maxDigits)
            }
            target.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    value.text = target.text.toString().ifBlank { "点击设置" }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            return row
        }

        fun bindSecretEditRow(label: String, target: EditText): LinearLayout {
            fun masked(): String = WeReadClient.maskKey(target.text.toString()) + " ▼"
            val (row, value) = bindInputRow(label, { masked() }) {
                openTextEditDialog(label, target)
            }
            target.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    value.text = masked()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
            return row
        }

        fun bindSpinnerRow(label: String, spinner: Spinner): LinearLayout {
            lateinit var value: TextView
            val (row, valueView) = bindInputRow(label, {
                fontLabel(spinner.selectedItem?.toString() ?: "") + " ▼"
            }) {
                val labels = systemFonts.map { fontLabel(it) }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle(label)
                    .setItems(labels) { _, which ->
                        spinner.setSelection(which)
                        value.text = "${labels[which]} ▼"
                    }
                    .show()
            }
            value = valueView
            return row
        }

        fun bindRadioChoiceRow(label: String, group: RadioGroup, options: List<Pair<Int, String>>): LinearLayout {
            fun optionText(id: Int): String {
                return (options.firstOrNull { it.first == id }?.second ?: options.first().second)
                    .replace('\n', ' ')
            }

            lateinit var value: TextView
            val (row, valueView) = bindInputRow(label, { optionText(group.checkedRadioButtonId) + " ▼" }) {
                val labels = options.map { it.second.replace('\n', ' ') }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle(label)
                    .setItems(labels) { _, which ->
                        group.check(options[which].first)
                        value.text = "${labels[which]} ▼"
                        if (!isInitializingUi) applySettingsPreview()
                    }
                    .show()
            }
            value = valueView
            return row
        }

        fun buildFontSpinner(savedKey: String, fallback: String): Spinner {
            return Spinner(this).apply {
                adapter = buildFontAdapter(systemFonts)
                val saved = prefs.getString(savedKey, fallback) ?: fallback
                setSelection(findSpinnerIndexBySpec(saved))
                hiddenHost.addView(this)
            }
        }

        root.addView(TextView(this).apply {
            text = "阅读壁纸设置"
            textSize = 28f
            setTextColor(Color.BLACK)
            setTypeface(Typeface.DEFAULT_BOLD)
            setPadding(0, 0, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "提示：少数情况下，锁屏时系统可能还没读取到刚生成的壁纸，通常下一次锁屏会显示最新结果。"
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 40)
        })

        val periodOptions = listOf(4000 to "当天\n只看今天", 4006 to "昨天\n只看昨日", 4001 to "本周\n周视图", 4002 to "上周\n回看上周", 4007 to "本月\n自然月历", 4003 to "最近7天\n滚动7天", 4004 to "最近30天\n月度概览", 4005 to "自定义起止\n手动选日期")
        val periodNames = listOf(PeriodMode.TODAY.name, PeriodMode.YESTERDAY.name, PeriodMode.THIS_WEEK.name, PeriodMode.LAST_WEEK.name, PeriodMode.THIS_MONTH.name, PeriodMode.LAST_7_DAYS.name, PeriodMode.LAST_30_DAYS.name, PeriodMode.CUSTOM.name)
        val savedPeriod = prefs.getString("period_mode", PeriodMode.THIS_WEEK.name) ?: PeriodMode.THIS_WEEK.name
        periodGroup = makeRadioGroup(periodOptions, selectedId(savedPeriod, 4001, periodOptions, periodNames))

        val sourceOptions = listOf(
            1001 to "Neo 阅读器\n读取文石本地阅读记录",
            1004 to "微信读书\n联网读取微信统计与封面",
            1005 to "混合来源\n合并本地和微信数据"
        )
        val sourceNames = listOf(DataSourceMode.DURATION.name, DataSourceMode.WEREAD.name, DataSourceMode.MIXED.name)
        sourceGroup = makeRadioGroup(sourceOptions, selectedId(prefs.getString("source_mode", DataSourceMode.DURATION.name) ?: DataSourceMode.DURATION.name, 1001, sourceOptions, sourceNames))

        val wallpaperOptions = listOf(
            1201 to "统计壁纸\n生成阅读账单图片",
            1202 to "当前阅读封面\n使用当前来源的最近封面",
            1203 to "自动封面优先\n有封面用封面，否则用账单",
            1204 to "月历封面墙\n按天展示所选来源数据"
        )
        val wallpaperNames = listOf("STATS", "COVER", "AUTO_COVER", "CALENDAR")
        wallpaperModeGroup = makeRadioGroup(wallpaperOptions, selectedId(prefs.getString("wallpaper_mode", "STATS") ?: "STATS", 1201, wallpaperOptions, wallpaperNames))

        val statsTemplateOptions = listOf(
            1241 to "阅读账单\n显示时长、进度和图表",
            1242 to "摘录菜单\n显示最新划线/想法和阅读价格"
        )
        val statsTemplateNames = listOf("RECEIPT", "EXCERPT_MENU")
        statsTemplateGroup = makeRadioGroup(statsTemplateOptions, selectedId(prefs.getString("stats_template", "RECEIPT") ?: "RECEIPT", 1241, statsTemplateOptions, statsTemplateNames), RadioGroup.VERTICAL)

        val calendarStackOrderOptions = listOf(
            1221 to "阅读最长在最上\n突出当天主要阅读",
            1222 to "阅读最短在最上\n突出短时翻阅书籍",
            1223 to "最近打开在最上\n突出当天最后阅读"
        )
        val calendarStackOrderNames = listOf("LONGEST_TOP", "SHORTEST_TOP", "LATEST_TOP")
        calendarStackOrderGroup = makeRadioGroup(
            calendarStackOrderOptions,
            selectedId(
                prefs.getString("calendar_stack_order", "LONGEST_TOP") ?: "LONGEST_TOP",
                1221,
                calendarStackOrderOptions,
                calendarStackOrderNames
            )
        )

        val matchedBooxPreset = detectBooxDevicePresetOrNull()
        val detectedBooxPreset = matchedBooxPreset ?: BooxDevicePresets.DEFAULT_KEY
        val booxDevicePresetOptions = listOf(1301 to "自定义分辨率\n手动输入宽度和高度") + BooxDevicePresets.all.mapIndexed { index, preset ->
            val matchMark = if (matchedBooxPreset != null && preset.key == matchedBooxPreset) " [本机匹配]" else ""
            (1302 + index) to "${preset.label}$matchMark\n${preset.inchText} ${preset.heightPx}x${preset.widthPx}"
        }
        val booxDevicePresetNames = listOf(BooxDevicePresets.CUSTOM_KEY) + BooxDevicePresets.all.map { it.key }
        val hasManualBooxPreset = prefs.getBoolean("boox_device_preset_user_set", false)
        val defaultBooxDevicePreset = if (hasManualBooxPreset && prefs.contains("boox_device_preset")) {
            prefs.getString("boox_device_preset", BooxDevicePresets.DEFAULT_KEY) ?: BooxDevicePresets.DEFAULT_KEY
        } else {
            detectedBooxPreset
        }
        appendUiDebug("booxDevicePreset default=$defaultBooxDevicePreset matched=${matchedBooxPreset ?: "none"} hasSaved=${prefs.contains("boox_device_preset")} userSet=$hasManualBooxPreset device=${deviceIdentityText()}")
        booxDevicePresetGroup = makeRadioGroup(
            booxDevicePresetOptions,
            selectedId(
                defaultBooxDevicePreset,
                1301,
                booxDevicePresetOptions,
                booxDevicePresetNames
            ),
            RadioGroup.VERTICAL
        )

        val coverFitOptions = listOf(1211 to "完整显示\n不裁掉封面", 1212 to "铺满裁切\n铺满屏幕边缘")
        val coverFitNames = listOf("FIT", "CROP")
        coverFitModeGroup = makeRadioGroup(coverFitOptions, selectedId(prefs.getString("cover_fit_mode", "FIT") ?: "FIT", 1211, coverFitOptions, coverFitNames), RadioGroup.HORIZONTAL)

        val timeUnitOptions = listOf(2001 to "小时\n自动显示x小时y分钟", 2002 to "分钟\n全部换算成分钟")
        val timeUnitNames = listOf("HOUR", "MINUTE")
        timeUnitGroup = makeRadioGroup(timeUnitOptions, selectedId(prefs.getString("time_unit", "HOUR") ?: "HOUR", 2001, timeUnitOptions, timeUnitNames), RadioGroup.HORIZONTAL)

        val readingFilterOptions = listOf(6001 to "全部\n不按状态过滤", 6002 to "仅在读\n只显示没读完", 6003 to "仅已读完\n只显示已完成")
        val readingFilterNames = listOf(ReadingFilterMode.ALL.name, ReadingFilterMode.READING_ONLY.name, ReadingFilterMode.FINISHED_ONLY.name)
        readingFilterGroup = makeRadioGroup(readingFilterOptions, selectedId(prefs.getString("reading_filter_mode", ReadingFilterMode.ALL.name) ?: ReadingFilterMode.ALL.name, 6001, readingFilterOptions, readingFilterNames), RadioGroup.HORIZONTAL)

        val progressOptions = listOf(6101 to "页数\n例如32/198", 6102 to "百分比\n例如16%")
        val progressNames = listOf("PAGES", "PERCENT")
        progressModeGroup = makeRadioGroup(progressOptions, selectedId(prefs.getString("progress_mode", "PAGES") ?: "PAGES", 6101, progressOptions, progressNames), RadioGroup.HORIZONTAL)

        val serialOptions = listOf(2011 to "月日\n自动用当前日期", 2012 to "随机\n每次生成变化", 2013 to "自定义\n手动固定数字")
        val serialNames = listOf("DATE", "RANDOM", "CUSTOM")
        serialModeGroup = makeRadioGroup(serialOptions, selectedId(prefs.getString("serial_number_mode", "DATE") ?: "DATE", 2011, serialOptions, serialNames), RadioGroup.HORIZONTAL)

        val footerOptions = listOf(3001 to "不显示\n底部留白更干净", 3002 to "只显示备注\n显示一句自定义文字", 3003 to "条码 + 备注\n增加票据装饰感")
        val footerNames = listOf("NONE", "NOTE", "BARCODE")
        footerModeGroup = makeRadioGroup(footerOptions, selectedId(prefs.getString("footer_mode", "NONE") ?: "NONE", 3001, footerOptions, footerNames))

        val barcodeWidthOptions = listOf(3101 to "细(0.8x)\n更轻", 3102 to "标准(1.0x)\n默认", 3103 to "粗(1.2x)\n更醒目")
        val savedBarcodeWidth = when (prefs.getFloat("barcode_width_scale", 1.0f)) {
            0.8f -> 3101
            1.2f -> 3103
            else -> 3102
        }
        barcodeWidthGroup = makeRadioGroup(barcodeWidthOptions, savedBarcodeWidth, RadioGroup.HORIZONTAL)

        val barcodeGapOptions = listOf(3111 to "紧凑\n线条更密", 3112 to "标准\n推荐", 3113 to "疏松\n留白更多")
        val barcodeGapNames = listOf("TIGHT", "STANDARD", "LOOSE")
        barcodeGapGroup = makeRadioGroup(barcodeGapOptions, selectedId(prefs.getString("barcode_gap_mode", "STANDARD") ?: "STANDARD", 3112, barcodeGapOptions, barcodeGapNames))

        val chartStyleOptions = listOf(7001 to "折线\n看趋势", 7002 to "柱状\n看每天差异")
        val chartStyleNames = listOf(ChartStyleMode.LINE.name, ChartStyleMode.BAR.name)
        chartStyleGroup = makeRadioGroup(chartStyleOptions, selectedId(prefs.getString("chart_style_mode", ChartStyleMode.LINE.name) ?: ChartStyleMode.LINE.name, 7001, chartStyleOptions, chartStyleNames), RadioGroup.HORIZONTAL)

        val yAxisOptions = listOf(7101 to "自动\n按数据自己缩放", 7102 to "固定\n不同周期更好对比")
        val yAxisNames = listOf(YAxisMode.AUTO.name, YAxisMode.FIXED.name)
        yAxisModeGroup = makeRadioGroup(yAxisOptions, selectedId(prefs.getString("y_axis_mode", YAxisMode.AUTO.name) ?: YAxisMode.AUTO.name, 7101, yAxisOptions, yAxisNames), RadioGroup.HORIZONTAL)

        val autoOptions = listOf(8001 to "每日定时一次（推荐）\n省电，适合稳定更新", 8002 to "熄屏触发\n更及时，但更耗电")
        val autoNames = listOf(AutoRefreshConfig.MODE_DAILY, AutoRefreshConfig.MODE_SCREEN_OFF)
        autoModeGroup = makeRadioGroup(autoOptions, selectedId(prefs.getString(AutoRefreshConfig.KEY_AUTO_MODE, AutoRefreshConfig.MODE_DAILY) ?: AutoRefreshConfig.MODE_DAILY, 8001, autoOptions, autoNames))

        includeUnreadCheck = makeCheck(prefs.getBoolean("include_unread", false))
        showProgressStatusCheck = makeCheck(prefs.getBoolean("show_progress_status", true))
        showAuthorCheck = makeCheck(prefs.getBoolean("show_author", true))
        showBookDurationCheck = makeCheck(prefs.getBoolean("show_book_duration", true))
        showChartCheck = makeCheck(prefs.getBoolean("show_chart", true))
        showPeakLabelCheck = makeCheck(prefs.getBoolean("show_peak_label", true))
        readingDataStoreCheck = makeCheck(
            prefs.getBoolean(AutoRefreshConfig.KEY_READING_DATA_STORE_ENABLED, false)
        )
        autoRefreshCheck = makeCheck(prefs.getBoolean(AutoRefreshConfig.KEY_AUTO_ENABLED, true))

        minDurationInput = makeInput(prefs.getInt("min_duration_minutes", 1).toString())
        topNInput = makeInput(prefs.getInt("top_n", 5).coerceIn(1, 5).toString())
        titleInput = makeInput(prefs.getString("receipt_title", "阅读账单") ?: "阅读账单")
        titleSizeInput = makeInput((prefs.getFloat("receipt_title_size", 74f)).toInt().toString())
        bodySizeInput = makeInput((prefs.getFloat("receipt_body_size", 34f)).toInt().toString())
        serialCustomInput = makeInput(prefs.getString("serial_number_custom", "") ?: "")
        serialNumberSizeInput = makeInput((prefs.getFloat("serial_number_size", 46f)).toInt().toString())
        customWallpaperWidthInput = makeInput(prefs.getInt("custom_wallpaper_width", BooxDevicePresets.byKey(defaultBooxDevicePreset).widthPx).coerceIn(300, 4000).toString())
        customWallpaperHeightInput = makeInput(prefs.getInt("custom_wallpaper_height", BooxDevicePresets.byKey(defaultBooxDevicePreset).heightPx).coerceIn(300, 4000).toString())
        noteInput = makeInput(prefs.getString("note_text", "") ?: "")
        yAxisMaxInput = makeInput(prefs.getInt("y_axis_fixed_max_minutes", 300).toString())
        autoDailyTimeInput = makeInput(prefs.getString(AutoRefreshConfig.KEY_DAILY_TIME, "22:30") ?: "22:30")
        autoMinIntervalInput = makeInput(prefs.getInt(AutoRefreshConfig.KEY_SCREEN_OFF_MIN_INTERVAL, 3).toString())
        titleFontSpinner = buildFontSpinner("title_font", "SERIF_BOLD")
        bodyFontSpinner = buildFontSpinner("body_font", "MONO")

        root.addView(hiddenHost)

        addSectionTitle("数据与统计", "周期、数据来源、设备尺寸与时长单位")
        bindToggle("启用阅读数据落库", readingDataStoreCheck)
        addHint("说明：默认关闭以降低低性能墨水屏设备的历史扫描、封面缓存和数据库写入开销。开启后会在启动、解锁或定时任务中维护 Neo 与微信读书日级记录，供微信/混合月历持续积累历史；关闭不会删除已有数据，Neo 月历仍可实时读取，微信和混合月历只能手动查看已有缓存，自动任务会保留现有壁纸。")
        val booxDevicePresetRow = bindRadioChoiceRow("阅读器尺寸预设", booxDevicePresetGroup, booxDevicePresetOptions)
        appendUiDebug("buildSettingsPage added booxDevicePresetRow rootChildCount=${root.childCount} rowChildren=${booxDevicePresetRow.childCount}")
        addHint("说明：首次会根据本机型号自动匹配；匹配不到时默认 Leaf5。这个选项会影响预览和生成壁纸的图片分辨率。")
        val customWidthRow = bindEditRow("自定义宽度(px)", customWallpaperWidthInput, numericOnly = true, maxDigits = 4)
        val customHeightRow = bindEditRow("自定义高度(px)", customWallpaperHeightInput, numericOnly = true, maxDigits = 4)
        addHint("说明：只有选择“自定义分辨率”时生效。请填写最终壁纸图片的宽和高，例如 P6 Pro 竖屏常用 824 x 1648。")
        val periodSegment = bindSegmented("统计周期", periodGroup, periodOptions, isVertical = false)
        addHint("说明：选择账单统计哪一段时间；自定义模式会显示起止日期选择。月历封面墙复用这个周期：选择“本月”时显示当前自然月；选择“最近30天”或其它周期时，会显示该周期结束日期所在月份的月历。")
        val sourceSegment = bindSegmented("数据来源", sourceGroup, sourceOptions, isVertical = true)
        addHint("说明：Neo 阅读器读取文石本地数据库，适合离线使用；微信读书需要联网读取 API；混合来源会把本地和微信的统计时长相加，书单按阅读时长合并排序，封面按最近阅读来源选择，失败时回退另一来源。混合来源包含联网数据，因此自动模式下不会在熄屏瞬间请求网络，而是在解锁后刷新，通常下一次锁屏看到新图；如果微信读书读取失败，会继续使用本地数据。")
        val wallpaperModeSegment = bindSegmented("壁纸类型", wallpaperModeGroup, wallpaperOptions, isVertical = true)
        val wallpaperModeHint = addHint("说明：统计壁纸生成阅读账单；当前阅读封面会按所选数据来源取最近书籍封面，Neo 阅读器只读本地封面，微信读书会联网获取并缓存封面；自动封面优先会先尝试封面，失败时回退到账单；月历封面墙按所选来源读取数据，Neo 使用本地阅读事件，微信读书使用精确每日总时长和快照差分确认的日级书籍。提示：Neo 封面依赖 NeoReader 自身写入本地元数据，通常退出当前书籍后再锁屏更容易刷新；微信数据在解锁后生成，通常下一次锁屏显示最新结果。")
        val statsTemplateSegment = bindSegmented("统计壁纸模板", statsTemplateGroup, statsTemplateOptions, isVertical = true)
        val statsTemplateHint = addHint("说明：摘录菜单会保留票据顶部和书单编号，右侧按阅读时长换算价格；价格=向上取整(阅读分钟÷10)，最低¥1，封顶¥999。微信读书书籍会尝试显示最新划线或想法，没有摘录则不显示。")
        val calendarStackOrderSegment = bindSegmented(
            "月历封面堆叠顺序",
            calendarStackOrderGroup,
            calendarStackOrderOptions,
            isVertical = true
        )
        val calendarStackOrderHint = addHint("说明：控制每日封面堆叠中最上方显示哪本书；最多仍显示4本，不改变每日阅读时长统计。")
        val coverFitSegment = bindSegmented("封面显示方式", coverFitModeGroup, coverFitOptions, isVertical = false)
        val timeUnitSegment = bindSegmented("时长显示单位", timeUnitGroup, timeUnitOptions, isVertical = false)
        addHint("说明：小时模式更适合壁纸阅读，分钟模式更适合精确核对。")
        val weekStartRow = bindInputRow("选择起始日期", { selectedWeekStartYmd.ifBlank { currentWeekStartYmd() } }) { openWeekStartDatePicker() }.first
        weekStartText = weekStartRow.getChildAt(1) as TextView
        val weekEndRow = bindInputRow("选择结束日期", { selectedWeekEndYmd.ifBlank { currentWeekEndYmd() } }) { openWeekEndDatePicker() }.first
        weekEndText = weekEndRow.getChildAt(1) as TextView

        addSectionTitle("书单筛选", "控制展示书目与统计阈值")
        val includeUnreadRow = bindToggle("最近阅读包含未读（readingStatus=0）", includeUnreadCheck)
        addHint("说明：关闭后会尽量排除只进过书库但没真正开始读的书。")
        val readingFilterSegment = bindSegmented("书单筛选（状态）", readingFilterGroup, readingFilterOptions, isVertical = false)
        bindNumberControl(
            "Top N（最多显示书籍数量）",
            topNInput,
            1,
            5,
            presets = listOf(1, 2, 3, 4, 5)
        )
        addHint("说明：默认最多5本；如果底部还要显示图表和条码，3本会更宽松。")
        bindNumberControl(
            "最小时长阈值（分钟，作用于“按阅读时长事件”）",
            minDurationInput,
            0,
            240,
            presets = listOf(0, 1, 3, 5, 10)
        )
        addHint("说明：小于这个时长的阅读事件会被忽略，可过滤误打开。")

        addSectionTitle("排版与字体", "标题、字号、进度与字体")
        val titleRow = bindEditRow("账单标题", titleInput)
        addHint("说明：会显示在壁纸右上角，例如“阅读账单”或“留台单”。")
        bindNumberControl(
            "标题字号",
            titleSizeInput,
            24,
            120,
            step = 2,
            presets = listOf(48, 64, 74, 96)
        )
        bindNumberControl(
            "正文字号基准",
            bodySizeInput,
            18,
            60,
            step = 2,
            presets = listOf(24, 30, 34, 42)
        )
        addHint("说明：字号会影响整张壁纸能放下多少内容；书多时建议调小。")
        val serialSegment = bindSegmented("单号数字模式", serialModeGroup, serialOptions, isVertical = false)
        val serialCustomRow = bindEditRow("自定义数字", serialCustomInput, numericOnly = true, maxDigits = 12)
        bindNumberControl(
            "单号数字字号",
            serialNumberSizeInput,
            24,
            140,
            step = 2,
            presets = listOf(36, 46, 60, 80)
        )
        addHint("说明：数字变大时会向上扩展，避免挤压下面的操作编号。")
        val progressStatusRow = bindToggle("显示进度和状态行", showProgressStatusCheck)
        addHint("说明：关闭后书单更简洁，但看不到读到哪里。")
        val progressSegment = bindSegmented("进度显示方式", progressModeGroup, progressOptions, isVertical = false)
        val authorRow = bindToggle("显示作者行（在进度行上方）", showAuthorCheck)
        addHint("说明：如果文石元数据里没有作者，会显示未知；关闭后可节省空间。")
        val bookDurationRow = bindToggle("显示每本书籍阅读时长", showBookDurationCheck)
        addHint("说明：打开后会在每本书的状态后追加“时长”；Neo 阅读器按本地阅读事件统计，微信读书按接口返回的阅读时长显示。")
        val titleFontRow = bindSpinnerRow("标题字体（系统字体）", titleFontSpinner)
        val bodyFontRow = bindSpinnerRow("正文字体（系统字体）", bodyFontSpinner)
        pickFontDirBtn = Button(this).apply {
            visibility = View.GONE
            setOnClickListener { pickFontTreeLauncher.launch(null) }
            hiddenHost.addView(this)
        }
        val fontDirRow = bindInputRow("选择字体目录（SAF）", { selectedFontDirUri?.let { "已选择 ▼" } ?: "未选择 ▼" }) {
            pickFontTreeLauncher.launch(null)
        }.first
        addHint("说明：选择存储/Fonts 后，可在标题字体和正文字体里使用里面的 ttf/otf 字体。")
        fontScanText = TextView(this).apply {
            text = fontScanReport
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 16)
        }
        root.addView(fontScanText)

        addSectionTitle("图表", "图形样式与坐标设置")
        val chartToggleRow = bindToggle("显示下方周曲线图", showChartCheck)
        addHint("说明：打开后能看到时间分布；如果书单或备注太多，可关闭节省空间。")
        val chartStyleSegment = bindSegmented("图表样式", chartStyleGroup, chartStyleOptions, isVertical = false)
        val chartRuleHint = addHint("图表横轴规则：当天/昨天=按小时；本周/上周/最近7天=按天；最近30天=按天；自定义<=14天按天，15-90天按周，>90天按月。")
        val peakLabelRow = bindToggle("显示峰值标签", showPeakLabelCheck)
        val yAxisSegment = bindSegmented("Y轴最大值", yAxisModeGroup, yAxisOptions, isVertical = false)
        val yAxisFixedControl = bindNumberControl(
            "Y轴固定最大值(分钟)",
            yAxisMaxInput,
            1,
            2000,
            step = 10,
            presets = listOf(60, 120, 300, 600)
        )
        addHint("说明：固定值越大，柱子/曲线越矮；用于不同周期之间保持同一比例。")

        addSectionTitle("底部备注与条码", "备注文本与装饰条码参数")
        val footerSegment = bindSegmented("底部备注/条码", footerModeGroup, footerOptions, isVertical = true)
        val noteRow = bindEditRow("备注文本 / 条码内容", noteInput)
        addHint("说明：备注会显示在底部；条码只是装饰风格，不保证所有扫码软件都能识别。")
        val barcodeWidthSegment = bindSegmented("条码粗细强度", barcodeWidthGroup, barcodeWidthOptions, isVertical = false)
        val barcodeGapSegment = bindSegmented("条码留白密度", barcodeGapGroup, barcodeGapOptions, isVertical = false)

        addSectionTitle("自动刷新", "默认自动模式，可切换定时或熄屏触发")
        val autoToggleRow = bindToggle("启用自动刷新与自动覆盖保存", autoRefreshCheck)
        addHint("说明：开启后 App 会按自动模式覆盖保存同一张壁纸图片。")
        val autoModeSegment = bindSegmented("自动刷新模式", autoModeGroup, autoOptions, isVertical = true)
        val autoDailyRow = bindInputRow("每日执行时间", { normalizeDailyTime(autoDailyTimeInput.text.toString()) }) { openDailyTimePicker() }.first
        val autoDailyValue = autoDailyRow.getChildAt(1) as TextView
        autoDailyTimeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { autoDailyValue.text = normalizeDailyTime(autoDailyTimeInput.text.toString()) }
            override fun afterTextChanged(s: Editable?) {}
        })
        val autoMinIntervalControl = bindNumberControl(
            "熄屏触发最小间隔(分钟)",
            autoMinIntervalInput,
            1,
            240,
            presets = listOf(1, 3, 5, 10, 30, 60)
        )
        addHint("说明：间隔越短越及时，也越容易增加耗电；3分钟是折中值。")
        autoModeHintText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 16)
            root.addView(this)
        }
        autoStateText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 16)
            root.addView(this)
        }
        autoExactAlarmButton = Button(this).apply {
            text = "允许精确定时"
            isAllCaps = false
            setOnClickListener { openExactAlarmSettings() }
            root.addView(this)
        }
        val autoWarningText = addHint("提示：熄屏触发会增加唤醒次数与耗电；NeoReader 常在退出当前书籍/会话落库后才更新元数据，所以可能出现“本次锁屏仍是旧封面、下次锁屏生效”的现象。")

        addSectionTitle("微信读书", "配置 API Key，支持手动生成与解锁预热刷新")
        wereadApiKeyInput = makeInput(WeReadClient.loadApiKey(this))
        val wereadKeyRow = bindSecretEditRow("API Key", wereadApiKeyInput)
        val wereadStatsModeOptions = listOf(9001 to "本周\nweekly", 9002 to "本月\nmonthly", 9003 to "今年\nannually", 9004 to "总计\noverall")
        val wereadStatsModeNames = listOf("weekly", "monthly", "annually", "overall")
        wereadStatsModeGroup = makeRadioGroup(
            wereadStatsModeOptions,
            selectedId(
                getSharedPreferences("weread_settings", Context.MODE_PRIVATE).getString("weread_stats_mode", "monthly") ?: "monthly",
                9002,
                wereadStatsModeOptions,
                wereadStatsModeNames
            ),
            RadioGroup.HORIZONTAL
        )
        val wereadStatsModeSegment = bindSegmented("统计测试周期", wereadStatsModeGroup, wereadStatsModeOptions, isVertical = false)
        wereadStatusText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 16)
            root.addView(this)
        }
        val wereadButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 24)
        }
        wereadButtons.addView(Button(this).apply {
            text = "保存 Key"
            setOnClickListener {
                saveWeReadApiKeyFromUi()
                renderWeReadState(WeReadClient.cachedState(this@MainActivity))
                writeDebugLog("weread_key_saved")
            }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 12, 0) })
        wereadButtons.addView(Button(this).apply {
            text = "测试连接"
            setOnClickListener { testWeReadConnection() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(wereadButtons)
        root.addView(Button(this).apply {
            text = "读取统计测试"
            setOnClickListener { testWeReadStats() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 24) })
        root.addView(Button(this).apply {
            text = "缓存最近封面测试"
            setOnClickListener { testWeReadCoverCache() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 24) })
        root.addView(Button(this).apply {
            text = "清理所有封面缓存"
            setOnClickListener { clearAllCoverCaches() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 24) })
        root.addView(Button(this).apply {
            text = "预览微信账单测试"
            setOnClickListener { previewWeReadWallpaper() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 24) })
        root.addView(Button(this).apply {
            text = "生成微信账单测试"
            setOnClickListener { generateWeReadWallpaper() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 0, 0, 24) })
        addHint("当前书识别：微信读书封面按 /shelf/sync 中 readUpdateTime 最新的书籍判断，不是直接读取微信读书正在显示的前台页面。刚切换书籍时微信同步可能有延迟，通常下一次解锁或手动刷新后会更新。")
        addHint("说明：连接和最近封面会调用 /shelf/sync；统计壁纸会调用 /readdata/detail；封面会缓存到 App 私有目录。选择“数据来源=微信读书”后，手动预览/生成会立即联网；自动模式下不在熄屏时联网，而是在解锁后预热刷新，网络未恢复会短间隔重试，成功后覆盖保存到 Pictures/NeoReader/neoreader_wallpaper.png。Key 只保存在本机 App 配置中，日志只记录脱敏后的 Key。")
        renderWeReadState(WeReadClient.cachedState(this))

        addSectionTitle("版本与更新", "GitHub Release 分发与更新检查")
        updateStatusText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 16)
            root.addView(this)
        }
        val updateButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 24)
        }
        updateButtons.addView(Button(this).apply {
            text = "检查更新"
            setOnClickListener { checkForUpdatesIfNeeded(force = true) }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 12, 0) })
        updateButtons.addView(Button(this).apply {
            text = "打开 Release 页面"
            setOnClickListener { openReleasePage() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(updateButtons)
        addHint("说明：App 只检查并跳转 GitHub Release 页面，不会自动下载或安装 APK。")
        updateReleaseStatusFromCache()

        addSectionTitle("联系开发者", "反馈问题、关注更新、请喝奶茶")
        val contactButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }
        contactButtons.addView(Button(this).apply {
            text = "关注小红书 (๑•̀ㅂ•́)و✧"
            setOnClickListener { openXhsProfile() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(0, 0, 12, 0) })
        contactButtons.addView(Button(this).apply {
            text = "请喝奶茶 🧋 (｡･ω･｡)ﾉ♡"
            setOnClickListener { showSponsorQrDialog() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(contactButtons)

        statusText = TextView(this).apply {
            text = "设置后点击按钮生成。"
            textSize = 16f
            setTextColor(Color.BLACK)
            setPadding(0, 16, 0, 0)
            root.addView(this)
        }

        fun updateConditionalVisibility() {
            val customPeriod = periodGroup.checkedRadioButtonId == 4005
            weekStartRow.visibility = if (customPeriod) View.VISIBLE else View.GONE
            weekEndRow.visibility = if (customPeriod) View.VISIBLE else View.GONE
            val customSize = booxPresetKeyByRadioId(booxDevicePresetGroup.checkedRadioButtonId) == BooxDevicePresets.CUSTOM_KEY
            customWidthRow.visibility = if (customSize) View.VISIBLE else View.GONE
            customHeightRow.visibility = if (customSize) View.VISIBLE else View.GONE

            val coverOptsVisible = wallpaperModeGroup.checkedRadioButtonId == 1202 || wallpaperModeGroup.checkedRadioButtonId == 1203
            coverFitSegment.visibility = if (coverOptsVisible) View.VISIBLE else View.GONE
            val statsVisible = wallpaperModeGroup.checkedRadioButtonId == 1201
            statsTemplateSegment.visibility = if (statsVisible) View.VISIBLE else View.GONE
            statsTemplateHint.visibility = if (statsVisible) View.VISIBLE else View.GONE
            val calendarVisible = wallpaperModeGroup.checkedRadioButtonId == 1204
            calendarStackOrderSegment.visibility = if (calendarVisible) View.VISIBLE else View.GONE
            calendarStackOrderHint.visibility = if (calendarVisible) View.VISIBLE else View.GONE

            serialCustomRow.visibility = if (serialModeGroup.checkedRadioButtonId == 2013) View.VISIBLE else View.GONE

            val showChart = showChartCheck.isChecked
            chartStyleSegment.visibility = if (showChart) View.VISIBLE else View.GONE
            chartRuleHint.visibility = if (showChart) View.VISIBLE else View.GONE
            peakLabelRow.visibility = if (showChart) View.VISIBLE else View.GONE
            yAxisSegment.visibility = if (showChart) View.VISIBLE else View.GONE
            yAxisFixedControl.visibility = if (showChart && yAxisModeGroup.checkedRadioButtonId == 7102) View.VISIBLE else View.GONE

            val footerMode = footerModeGroup.checkedRadioButtonId
            noteRow.visibility = if (footerMode != 3001) View.VISIBLE else View.GONE
            barcodeWidthSegment.visibility = if (footerMode == 3003) View.VISIBLE else View.GONE
            barcodeGapSegment.visibility = if (footerMode == 3003) View.VISIBLE else View.GONE

            val autoEnabled = autoRefreshCheck.isChecked
            autoModeSegment.visibility = if (autoEnabled) View.VISIBLE else View.GONE
            autoDailyRow.visibility = if (autoEnabled && autoModeGroup.checkedRadioButtonId == 8001) View.VISIBLE else View.GONE
            autoMinIntervalControl.visibility = if (autoEnabled && autoModeGroup.checkedRadioButtonId == 8002) View.VISIBLE else View.GONE
            autoModeHintText.visibility = if (autoEnabled) View.VISIBLE else View.GONE
            autoStateText.visibility = if (autoEnabled) View.VISIBLE else View.GONE
            autoExactAlarmButton.visibility = if (
                autoEnabled &&
                autoModeGroup.checkedRadioButtonId == 8001 &&
                !AutoRefreshScheduler.canScheduleExact(this)
            ) View.VISIBLE else View.GONE
            autoWarningText.visibility = if (autoEnabled) View.VISIBLE else View.GONE
        }

        showChartCheck.setOnCheckedChangeListener { _, _ -> updateConditionalVisibility(); if (!isInitializingUi) applySettingsPreview() }
        periodGroup.setOnCheckedChangeListener { _, _ -> updateConditionalVisibility(); if (!isInitializingUi) applySettingsPreview() }
        yAxisModeGroup.setOnCheckedChangeListener { _, _ -> updateConditionalVisibility(); if (!isInitializingUi) applySettingsPreview() }
        footerModeGroup.setOnCheckedChangeListener { _, _ -> updateConditionalVisibility(); if (!isInitializingUi) applySettingsPreview() }
        autoRefreshCheck.setOnCheckedChangeListener { _, _ -> updateConditionalVisibility(); if (!isInitializingUi) applySettingsPreview() }
        readingDataStoreCheck.setOnCheckedChangeListener { _, enabled ->
            if (!isInitializingUi) {
                getSharedPreferences(AutoRefreshConfig.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(AutoRefreshConfig.KEY_READING_DATA_STORE_ENABLED, enabled)
                    .apply()
                applySettingsPreview()
                if (enabled) startReadingStoreBootstrapIfNeeded()
            }
        }
        autoModeGroup.setOnCheckedChangeListener { _, _ -> updateConditionalVisibility(); if (!isInitializingUi) applySettingsPreview() }
        serialModeGroup.setOnCheckedChangeListener { _, _ -> updateConditionalVisibility(); if (!isInitializingUi) applySettingsPreview() }
        wallpaperModeGroup.setOnCheckedChangeListener { _, _ -> updateConditionalVisibility(); if (!isInitializingUi) applySettingsPreview() }
        statsTemplateGroup.setOnCheckedChangeListener { _, _ -> if (!isInitializingUi) applySettingsPreview() }
        calendarStackOrderGroup.setOnCheckedChangeListener { _, _ -> if (!isInitializingUi) applySettingsPreview() }
        booxDevicePresetGroup.setOnCheckedChangeListener { _, _ ->
            updateConditionalVisibility()
            if (!isInitializingUi) {
                getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("boox_device_preset_user_set", true)
                    .apply()
                applySettingsPreview()
                if (wallpaperModeGroup.checkedRadioButtonId == 1201) {
                    changeStateText.text = "状态: 尺寸已切换，统计壁纸预览已重新生成｜尺寸: $previewPresetText"
                }
            }
        }
        updateConditionalVisibility()

        updateAutoRefreshHint()
        updateAutoRuntimeState()
        attachAutoRefreshListeners()

        scroll.addView(root)
        return scroll
    }

    private fun attachAutoRefreshListeners() {
        includeUnreadCheck.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        showProgressStatusCheck.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        showAuthorCheck.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        showBookDurationCheck.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        showPeakLabelCheck.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        sourceGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        coverFitModeGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        readingFilterGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        progressModeGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        timeUnitGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        chartStyleGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        barcodeWidthGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        barcodeGapGroup.setOnCheckedChangeListener { _, _ ->
            if (!isInitializingUi) applySettingsPreview()
        }
        titleFontSpinner.setOnItemSelectedListener(SimpleItemSelectedListener { if (!isInitializingUi) applySettingsPreview() })
        bodyFontSpinner.setOnItemSelectedListener(SimpleItemSelectedListener { if (!isInitializingUi) applySettingsPreview() })
        minDurationInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        topNInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        titleInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        noteInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        titleSizeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        bodySizeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        serialNumberSizeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        serialCustomInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        customWallpaperWidthInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        customWallpaperHeightInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        yAxisMaxInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        autoDailyTimeInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        autoMinIntervalInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!isInitializingUi) applySettingsPreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }

    private fun beginPreviewRequest(): Int {
        previewDebounceRunnable?.let(uiHandler::removeCallbacks)
        previewDebounceRunnable = null
        previewRequestId += 1
        return previewRequestId
    }

    private fun applySettingsPreview() {
        if (isInitializingUi || isApplyingSettings || isDestroyed) return
        val settings = readSettingsFromUi()
        saveSettings(settings)
        saveAndApplyAutoRefreshSettings()
        val requestId = beginPreviewRequest()
        previewPresetText = wallpaperSizeDisplayText(settings)

        if (settings.sourceMode == DataSourceMode.WEREAD || settings.sourceMode == DataSourceMode.MIXED) {
            val label = if (settings.sourceMode == DataSourceMode.MIXED) "混合来源" else "微信读书来源"
            statusText.text = "$label 参数已保存\n点击“刷新预览”或“生成壁纸”时才会联网。"
            changeStateText.text = "状态: $label 参数已保存（未联网）｜尺寸: $previewPresetText"
            refreshPreview()
            return
        }

        statusText.text = "参数已保存\n停止操作后将自动更新预览。"
        changeStateText.text = "状态: 等待更新预览｜尺寸: $previewPresetText"
        val task = Runnable {
            previewDebounceRunnable = null
            if (requestId == previewRequestId && !isDestroyed) {
                renderLocalPreviewAsync(settings, requestId, showPreviewAfter = false)
            }
        }
        previewDebounceRunnable = task
        uiHandler.postDelayed(task, PREVIEW_DEBOUNCE_MS)
    }

    private fun renderLocalPreviewAsync(settings: Settings, requestId: Int, showPreviewAfter: Boolean) {
        if (requestId != previewRequestId || isDestroyed) return
        changeStateText.text = "状态: 正在后台生成预览｜尺寸: ${wallpaperSizeDisplayText(settings)}"
        previewExecutor.execute {
            val rendered = runCatching { renderWallpaperPreview(settings) }
            uiHandler.post {
                if (requestId != previewRequestId || isDestroyed || isFinishing) return@post
                rendered.onSuccess { (bmp, result) ->
                    previewBitmap = bmp
                    previewPresetText = wallpaperSizeDisplayText(settings)
                    statusText.text = "预览已更新（未写入文件）\n$result"
                    changeStateText.text = "状态: 预览已更新｜尺寸: $previewPresetText"
                    refreshPreview()
                    if (showPreviewAfter) showPreviewPage()
                }.onFailure { error ->
                    statusText.text = "预览生成失败\n${error.javaClass.simpleName}: ${error.message ?: "未知错误"}"
                    changeStateText.text = "状态: 预览生成失败"
                    AutoRefreshLog.e(this, "preview generation failed", error)
                }
            }
        }
    }

    private fun openWeekStartDatePicker() {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        parseWeek(selectedWeekStartYmd)?.let { cal.timeInMillis = it.first }
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val picked = Calendar.getInstance(TimeZone.getDefault())
                picked.set(year, month, dayOfMonth, 0, 0, 0)
                picked.set(Calendar.MILLISECOND, 0)
                selectedWeekStartYmd = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(picked.timeInMillis))
                weekStartText.text = selectedWeekStartYmd
                if (!isInitializingUi) applySettingsPreview()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun openWeekEndDatePicker() {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        parseYmd(selectedWeekEndYmd)?.let { cal.timeInMillis = it }
        DatePickerDialog(
            this,
            { _, year, month, dayOfMonth ->
                val picked = Calendar.getInstance(TimeZone.getDefault())
                picked.set(year, month, dayOfMonth, 0, 0, 0)
                picked.set(Calendar.MILLISECOND, 0)
                selectedWeekEndYmd = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(picked.timeInMillis))
                weekEndText.text = selectedWeekEndYmd
                if (!isInitializingUi) applySettingsPreview()
            },
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH),
            cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun openDailyTimePicker() {
        val raw = autoDailyTimeInput.text.toString().trim()
        val parts = raw.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 22
        val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 30
        TimePickerDialog(
            this,
            { _, h, m ->
                autoDailyTimeInput.setText(String.format(Locale.US, "%02d:%02d", h, m))
                if (!isInitializingUi) applySettingsPreview()
            },
            hour,
            minute,
            true
        ).show()
    }

    private fun generateAndSaveFromCurrentSettings() {
        val settings = readSettingsFromUi()
        if (settings.sourceMode == DataSourceMode.WEREAD || settings.sourceMode == DataSourceMode.MIXED) {
            generateWeReadWallpaper()
            return
        }
        saveSettings(settings)
        saveAndApplyAutoRefreshSettings()
        val requestId = beginPreviewRequest()
        previewPresetText = wallpaperSizeDisplayText(settings)
        statusText.text = "正在后台生成并保存壁纸..."
        changeStateText.text = "状态: 正在生成壁纸｜尺寸: $previewPresetText"
        previewExecutor.execute {
            val generated = runCatching {
                val (bmp, result) = renderWallpaperPreview(settings)
                val saved = if (requestId == previewRequestId) saveBitmapToPictures(bmp) else null
                Triple(bmp, result, saved)
            }
            uiHandler.post {
                if (requestId != previewRequestId || isDestroyed || isFinishing) return@post
                generated.onSuccess { (bmp, result, saved) ->
                    if (saved == null) return@onSuccess
                    previewBitmap = bmp
                    lastSavedPath = saved
                    statusText.text = "已生成并覆盖文件\n$result\n路径: $saved"
                    changeStateText.text = "状态: 已生成并保存｜尺寸: $previewPresetText"
                    refreshPreview()
                    showPreviewPage()
                    collectMetadataDebugSampleAsync("generated_saved")
                }.onFailure { error ->
                    statusText.text = "壁纸生成或保存失败\n${error.javaClass.simpleName}: ${error.message ?: "未知错误"}"
                    changeStateText.text = "状态: 壁纸生成失败"
                    AutoRefreshLog.e(this, "wallpaper generation failed", error)
                }
            }
        }
    }

    private fun saveAndApplyAutoRefreshSettings() {
        val isEnabled = autoRefreshCheck.isChecked
        val mode = if (autoModeGroup.checkedRadioButtonId == 8002) AutoRefreshConfig.MODE_SCREEN_OFF else AutoRefreshConfig.MODE_DAILY
        val dailyTime = normalizeDailyTime(autoDailyTimeInput.text.toString())
        val minInterval = autoMinIntervalInput.text.toString().trim().toIntOrNull()?.coerceIn(1, 240) ?: 3
        val prefs = getSharedPreferences(AutoRefreshConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val changed = prefs.getBoolean(AutoRefreshConfig.KEY_AUTO_ENABLED, true) != isEnabled ||
            prefs.getString(AutoRefreshConfig.KEY_AUTO_MODE, AutoRefreshConfig.MODE_DAILY) != mode ||
            prefs.getString(AutoRefreshConfig.KEY_DAILY_TIME, "22:30") != dailyTime ||
            prefs.getInt(AutoRefreshConfig.KEY_SCREEN_OFF_MIN_INTERVAL, 3) != minInterval
        if (changed) {
            prefs.edit()
                .putBoolean(AutoRefreshConfig.KEY_AUTO_ENABLED, isEnabled)
                .putString(AutoRefreshConfig.KEY_AUTO_MODE, mode)
                .putString(AutoRefreshConfig.KEY_DAILY_TIME, dailyTime)
                .putInt(AutoRefreshConfig.KEY_SCREEN_OFF_MIN_INTERVAL, minInterval)
                .apply()
        }
        if (autoDailyTimeInput.text.toString() != dailyTime) {
            isApplyingSettings = true
            try {
                autoDailyTimeInput.setText(dailyTime)
                autoDailyTimeInput.setSelection(dailyTime.length)
            } finally {
                isApplyingSettings = false
            }
        }
        updateAutoRefreshHint()
        if (changed || !autoRuntimeInitialized) {
            updateAutoRuntimeState()
            AutoRefreshScheduler.reschedule(this)
            AutoRefreshRuntime.sync(this)
            autoRuntimeInitialized = true
            AutoRefreshLog.i(this, "auto settings updated: enabled=$isEnabled mode=$mode dailyTime=$dailyTime minInterval=$minInterval changed=$changed")
        }
    }

    private fun updateAutoRefreshHint() {
        val mode = if (::autoModeGroup.isInitialized && autoModeGroup.checkedRadioButtonId == 8002) "熄屏触发" else "每日定时"
        autoModeHintText.text = if (::autoRefreshCheck.isInitialized && autoRefreshCheck.isChecked) {
            "当前自动模式：$mode，熄屏最小间隔=${autoMinIntervalInput.text}"
        } else {
            "当前自动模式：已关闭"
        }
    }

    private fun updateAutoRuntimeState() {
        if (!::autoStateText.isInitialized) return
        val p = getSharedPreferences(AutoRefreshConfig.PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = p.getBoolean(AutoRefreshConfig.KEY_AUTO_ENABLED, true)
        val mode = p.getString(AutoRefreshConfig.KEY_AUTO_MODE, AutoRefreshConfig.MODE_DAILY) ?: AutoRefreshConfig.MODE_DAILY
        val dailyTime = p.getString(AutoRefreshConfig.KEY_DAILY_TIME, "22:30") ?: "22:30"
        val minInterval = p.getInt(AutoRefreshConfig.KEY_SCREEN_OFF_MIN_INTERVAL, 3).coerceIn(1, 240)
        val lastMs = p.getLong(AutoRefreshConfig.KEY_LAST_TRIGGER_MS, 0L)
        val lastReasonRaw = p.getString(AutoRefreshConfig.KEY_LAST_REASON, "") ?: ""
        val lastReason = when (lastReasonRaw) {
            "screen_off" -> "熄屏触发"
            "screen_on_prewarm" -> "亮屏预热"
            "user_present_prewarm" -> "解锁预热"
            "book_content_changed" -> "内容变化"
            "reading_stats_changed" -> "阅读统计变化"
            "daily_alarm" -> "每日定时"
            "" -> "暂无"
            else -> lastReasonRaw
        }
        val lastTime = if (lastMs > 0L) {
            SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(lastMs))
        } else {
            "暂无"
        }
        val exactAllowed = AutoRefreshScheduler.canScheduleExact(this)
        val runtimeHint = if (!enabled) {
            "自动已关闭"
        } else if (mode == AutoRefreshConfig.MODE_SCREEN_OFF) {
            "熄屏监听应运行（前台服务）"
        } else if (exactAllowed) {
            "按每日精确定时运行（$dailyTime）"
        } else {
            "按每日非精确定时运行（$dailyTime，系统可能延后）"
        }
        if (::autoExactAlarmButton.isInitialized) {
            autoExactAlarmButton.visibility = if (
                enabled && mode == AutoRefreshConfig.MODE_DAILY && !exactAllowed
            ) View.VISIBLE else View.GONE
        }
        autoStateText.text = "自动状态：$runtimeHint\n最近触发：$lastTime（$lastReason）\n当前参数：模式=$mode，定时=$dailyTime，熄屏间隔=${minInterval}分钟"
    }

    private fun openExactAlarmSettings() {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        val packageUri = Uri.parse("package:$packageName")
        runCatching {
            startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri)
            )
        }.onFailure {
            startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)
            )
        }
    }

    private fun updateReleaseStatusFromCache() {
        if (!::updateStatusText.isInitialized) return
        renderReleaseState(GitHubReleaseChecker.cachedState(this))
    }

    private fun saveWeReadApiKeyFromUi() {
        if (!::wereadApiKeyInput.isInitialized) return
        WeReadClient.saveApiKey(this, wereadApiKeyInput.text.toString())
        appendUiDebug("weread api key saved key=${WeReadClient.maskKey(wereadApiKeyInput.text.toString())}")
    }

    private fun clearAllCoverCaches() {
        val targets = listOf(
            File(cacheDir, "extracted_covers"),
            File(cacheDir, "covers")
        )
        var fileCount = 0
        var byteCount = 0L
        targets.forEach { dir ->
            if (dir.exists()) {
                dir.walkTopDown()
                    .filter { it.isFile }
                    .forEach {
                        fileCount += 1
                        byteCount += it.length()
                    }
                dir.deleteRecursively()
            }
        }
        WeReadClient.clearCoverCacheState(this)
        val message = "已清理封面缓存：${fileCount}个文件，${formatBytes(byteCount)}"
        AutoRefreshLog.i(this, "cover cache cleared files=$fileCount bytes=$byteCount")
        appendUiDebug("cover cache cleared files=$fileCount bytes=$byteCount")
        if (::statusText.isInitialized) statusText.text = message
        if (::wereadStatusText.isInitialized) {
            wereadStatusText.text = "${wereadStatusText.text}\n$message"
        }
        writeDebugLog("cover_cache_cleared")
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024f / 1024f)
            bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024f)
            else -> "${bytes} B"
        }
    }

    private fun testWeReadConnection() {
        if (isTestingWeRead) return
        saveWeReadApiKeyFromUi()
        isTestingWeRead = true
        if (::wereadStatusText.isInitialized) {
            wereadStatusText.text = "微信读书：正在测试连接..."
        }
        Thread {
            val result = WeReadClient.testConnection(applicationContext, WeReadClient.loadApiKey(applicationContext))
            runOnUiThread {
                isTestingWeRead = false
                renderWeReadState(WeReadClient.cachedState(this))
                if (::wereadStatusText.isInitialized) {
                    wereadStatusText.text = "${wereadStatusText.text}\n本次结果：${result.detail.take(160)}"
                }
                appendUiDebug("weread test ok=${result.ok} detail=${result.detail.take(120)}")
                writeDebugLog("weread_test")
            }
        }.start()
    }

    private fun selectedWeReadStatsMode(): String {
        return when (if (::wereadStatsModeGroup.isInitialized) wereadStatsModeGroup.checkedRadioButtonId else 9002) {
            9001 -> "weekly"
            9003 -> "annually"
            9004 -> "overall"
            else -> "monthly"
        }
    }

    private fun saveWeReadStatsModeFromUi(): String {
        val mode = selectedWeReadStatsMode()
        getSharedPreferences("weread_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("weread_stats_mode", mode)
            .apply()
        return mode
    }

    private fun weReadPeriodLabel(periodMode: PeriodMode): String {
        return when (periodMode) {
            PeriodMode.TODAY -> "当天"
            PeriodMode.YESTERDAY -> "昨天"
            PeriodMode.THIS_WEEK -> "本周"
            PeriodMode.LAST_WEEK -> "上周"
            PeriodMode.THIS_MONTH -> "本月"
            PeriodMode.LAST_7_DAYS -> "最近7天"
            PeriodMode.LAST_30_DAYS -> "最近30天"
            PeriodMode.CUSTOM -> "自定义周期"
        }
    }

    private fun testWeReadStats() {
        if (isTestingWeRead) return
        saveWeReadApiKeyFromUi()
        val mode = saveWeReadStatsModeFromUi()
        isTestingWeRead = true
        if (::wereadStatusText.isInitialized) {
            wereadStatusText.text = "微信读书：正在读取${WeReadClient.modeLabel(mode)}统计..."
        }
        Thread {
            val result = WeReadClient.fetchReadStats(applicationContext, WeReadClient.loadApiKey(applicationContext), mode)
            runOnUiThread {
                isTestingWeRead = false
                lastWeReadStatsDebug = "ok=${result.ok}, mode=${result.mode}, totalSeconds=${result.totalReadSeconds}, dayAverageSeconds=${result.dayAverageSeconds}, readDays=${result.readDays}, top=${result.topBooks.joinToString("|")}, detail=${result.detail}"
                renderWeReadState(WeReadClient.cachedState(this))
                if (::wereadStatusText.isInitialized) {
                    wereadStatusText.text = "${wereadStatusText.text}\n统计结果：${result.detail.take(260)}"
                }
                appendUiDebug("weread stats $lastWeReadStatsDebug")
                writeDebugLog("weread_stats_test")
            }
        }.start()
    }

    private fun testWeReadCoverCache() {
        if (isTestingWeRead) return
        saveWeReadApiKeyFromUi()
        isTestingWeRead = true
        if (::wereadStatusText.isInitialized) {
            wereadStatusText.text = "微信读书：正在缓存最近阅读封面..."
        }
        Thread {
            val result = WeReadClient.cacheLatestCover(applicationContext, WeReadClient.loadApiKey(applicationContext))
            runOnUiThread {
                isTestingWeRead = false
                lastWeReadCoverDebug = "ok=${result.ok}, status=${result.status}, title=${result.title}, author=${result.author}, bookId=${result.bookId}, bytes=${result.bytes}, fromCache=${result.fromCache}, path=${result.cachePath}, detail=${result.detail}"
                renderWeReadState(WeReadClient.cachedState(this))
                if (::wereadStatusText.isInitialized) {
                    wereadStatusText.text = "${wereadStatusText.text}\n封面结果：${result.detail.take(220)}"
                }
                appendUiDebug("weread cover $lastWeReadCoverDebug")
                writeDebugLog("weread_cover_cache_test")
            }
        }.start()
    }

    private fun previewWeReadWallpaper() {
        if (isTestingWeRead) return
        saveWeReadApiKeyFromUi()
        val settings = readSettingsFromUi()
        saveSettings(settings)
        saveAndApplyAutoRefreshSettings()
        val requestId = beginPreviewRequest()
        val periodLabel = weReadPeriodLabel(settings.periodMode)
        val sourceLabel = if (settings.sourceMode == DataSourceMode.MIXED) "混合来源" else "微信读书"
        isTestingWeRead = true
        changeStateText.text = "状态: 正在生成${sourceLabel}预览..."
        if (::wereadStatusText.isInitialized) {
            wereadStatusText.text = "$sourceLabel：正在生成${periodLabel}预览..."
        }
        previewExecutor.execute {
            val rendered = runCatching { buildSourcePreviewForWallpaperMode(settings) }
            uiHandler.post {
                isTestingWeRead = false
                if (requestId != previewRequestId || isDestroyed || isFinishing) return@post
                val preview = rendered.getOrNull()
                if (preview != null) {
                    previewBitmap = preview.bitmap
                    previewPresetText = wallpaperSizeDisplayText(settings)
                    statusText.text = "${sourceLabel}预览已更新（未写入文件）\n${preview.summary}"
                    changeStateText.text = "状态: ${sourceLabel}预览已更新｜尺寸: $previewPresetText"
                    lastWeReadWallpaperDebug = "ok=true, period=$periodLabel, summary=${preview.summary}"
                    refreshPreview()
                    showPreviewPage()
                } else {
                    changeStateText.text = "状态: ${sourceLabel}预览失败"
                    lastWeReadWallpaperDebug = "ok=false, period=$periodLabel, error=${rendered.exceptionOrNull()?.message ?: "empty preview"}"
                }
                if (::wereadStatusText.isInitialized) {
                    wereadStatusText.text = "${wereadStatusText.text}\n账单预览：${lastWeReadWallpaperDebug.take(180)}"
                }
                appendUiDebug("weread wallpaper $lastWeReadWallpaperDebug")
                collectMetadataDebugSampleAsync("weread_wallpaper_preview")
            }
        }
    }

    private fun generateWeReadWallpaper() {
        if (isTestingWeRead) return
        saveWeReadApiKeyFromUi()
        val settings = readSettingsFromUi()
        saveSettings(settings)
        saveAndApplyAutoRefreshSettings()
        val requestId = beginPreviewRequest()
        val periodLabel = weReadPeriodLabel(settings.periodMode)
        val sourceLabel = if (settings.sourceMode == DataSourceMode.MIXED) "混合来源" else "微信读书"
        isTestingWeRead = true
        changeStateText.text = "状态: 正在生成${sourceLabel}壁纸..."
        if (::wereadStatusText.isInitialized) {
            wereadStatusText.text = "$sourceLabel：正在生成并保存${periodLabel}壁纸..."
        }
        previewExecutor.execute {
            val generated = runCatching {
                val preview = buildSourcePreviewForWallpaperMode(settings)
                val saved = if (preview != null && requestId == previewRequestId) {
                    saveBitmapToPictures(preview.bitmap)
                } else {
                    null
                }
                preview to saved
            }
            uiHandler.post {
                isTestingWeRead = false
                if (requestId != previewRequestId || isDestroyed || isFinishing) return@post
                val (preview, saved) = generated.getOrNull() ?: (null to null)
                if (preview != null && saved != null) {
                    previewBitmap = preview.bitmap
                    lastSavedPath = saved
                    previewPresetText = wallpaperSizeDisplayText(settings)
                    statusText.text = "${sourceLabel}壁纸已生成并覆盖文件\n${preview.summary}\n路径: $saved"
                    changeStateText.text = "状态: ${sourceLabel}壁纸已生成并保存｜尺寸: $previewPresetText"
                    lastWeReadWallpaperDebug = "ok=true, period=$periodLabel, saved=$saved, summary=${preview.summary}"
                    refreshPreview()
                    showPreviewPage()
                } else {
                    changeStateText.text = "状态: ${sourceLabel}生成失败"
                    lastWeReadWallpaperDebug = "ok=false, period=$periodLabel, saved=<none>, error=${generated.exceptionOrNull()?.message ?: "empty preview"}"
                }
                if (::wereadStatusText.isInitialized) {
                    wereadStatusText.text = "${wereadStatusText.text}\n账单生成：${lastWeReadWallpaperDebug.take(180)}"
                }
                appendUiDebug("weread wallpaper generated $lastWeReadWallpaperDebug")
                collectMetadataDebugSampleAsync("weread_wallpaper_generated")
            }
        }
    }

    private fun buildWeReadPreviewForWallpaperMode(wallpaperMode: String): AutoWallpaperGenerator.PreviewResult? {
        return when (wallpaperMode) {
            "COVER" -> AutoWallpaperGenerator.buildWeReadCoverPreviewFromPrefs(applicationContext, "W")
            "AUTO_COVER" -> AutoWallpaperGenerator.buildWeReadCoverPreviewFromPrefs(applicationContext, "W")
                ?: AutoWallpaperGenerator.buildWeReadStatsPreviewFromPrefs(applicationContext, "W")
            "CALENDAR" -> AutoWallpaperGenerator.buildWeReadCalendarPreviewFromPrefs(applicationContext, "W")
            else -> AutoWallpaperGenerator.buildWeReadStatsPreviewFromPrefs(applicationContext, "W")
        }
    }

    private fun buildSourcePreviewForWallpaperMode(settings: Settings): AutoWallpaperGenerator.PreviewResult? {
        return if (settings.sourceMode == DataSourceMode.MIXED) {
            AutoWallpaperGenerator.buildMixedPreviewFromPrefs(applicationContext, "A")
        } else {
            buildWeReadPreviewForWallpaperMode(settings.wallpaperMode)
        }
    }

    private fun renderWeReadState(state: WeReadClient.State) {
        if (!::wereadStatusText.isInitialized) return
        val checkedAt = if (state.lastTestMs > 0L) {
            SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(state.lastTestMs))
        } else {
            "尚未测试"
        }
        val error = if (state.error.isBlank()) "" else "\n失败原因：${state.error.take(120)}"
        wereadStatusText.text = "微信读书 Key：${state.maskedKey}\n连接状态：${state.status}\n最近测试：$checkedAt$error"
    }

    private fun checkForUpdatesIfNeeded(force: Boolean) {
        if (!force && !GitHubReleaseChecker.shouldAutoCheck(this)) {
            updateReleaseStatusFromCache()
            return
        }
        if (isCheckingUpdates) return
        isCheckingUpdates = true
        if (::updateStatusText.isInitialized) {
            updateStatusText.text = "当前版本：${GitHubReleaseChecker.currentVersionName(this)}\n更新状态：正在检查 GitHub Release..."
        }
        Thread {
            val state = GitHubReleaseChecker.check(applicationContext)
            runOnUiThread {
                isCheckingUpdates = false
                renderReleaseState(state)
            }
        }.start()
    }

    private fun renderReleaseState(state: GitHubReleaseChecker.State) {
        if (!::updateStatusText.isInitialized) return
        val checkedAt = if (state.lastCheckMs > 0L) {
            SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(state.lastCheckMs))
        } else {
            "尚未检查"
        }
        val latest = state.latestTag.ifBlank { "未知" }
        val error = if (state.error.isBlank()) "" else "\n失败原因：${state.error.take(80)}"
        updateStatusText.text = "当前版本：${GitHubReleaseChecker.currentVersionName(this)}\n最新版本：$latest\n更新状态：${state.status}\n最近检查：$checkedAt$error"
    }

    private fun openReleasePage() {
        val url = GitHubReleaseChecker.cachedState(this).latestUrl.ifBlank { GitHubReleaseChecker.RELEASES_URL }
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure {
            if (::updateStatusText.isInitialized) {
                updateStatusText.text = "${updateStatusText.text}\n无法打开链接：${it.javaClass.simpleName}"
            }
        }
    }

    private fun showSponsorQrDialog() {
        val density = resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density + 0.5f).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        TextView(this).apply {
            text = "如果阅迹壁纸刚好帮到了你，也欢迎请我喝杯奶茶 🧋\n(づ｡◕‿‿◕｡)づ"
            textSize = 15f
            setTextColor(Color.BLACK)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
            content.addView(this)
        }
        val qrImage = ImageView(this).apply {
            visibility = View.GONE
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            content.addView(
                this,
                LinearLayout.LayoutParams(dp(240), dp(240)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            )
        }
        val state = TextView(this).apply {
            text = "收款码加载中..."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, 0)
            content.addView(this)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("请喝奶茶 (｡･ω･｡)ﾉ♡")
            .setView(content)
            .setNegativeButton("打开链接") { _, _ -> openSponsorQrLink() }
            .setPositiveButton("关闭", null)
            .show()
        AutoRefreshLog.i(this, "sponsor qr dialog opened")
        Thread {
            val result = runCatching { loadSponsorQrBitmap() }
            runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                result.onSuccess { bmp ->
                    qrImage.setImageBitmap(bmp)
                    qrImage.visibility = View.VISIBLE
                    state.text = "支付宝扫一扫支持一下 ( •̀ ω •́ )✧"
                }.onFailure {
                    qrImage.visibility = View.GONE
                    state.text = "收款码加载失败，可点击“打开链接”。\n$SPONSOR_QR_URL"
                    AutoRefreshLog.e(this, "sponsor qr load failed", it)
                }
            }
        }.start()
    }

    private fun loadSponsorQrBitmap(): Bitmap {
        val conn = (URL(SPONSOR_QR_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 12000
            instanceFollowRedirects = true
            requestMethod = "GET"
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            conn.inputStream.use { input ->
                BitmapFactory.decodeStream(input) ?: error("decode failed")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun openSponsorQrLink() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SPONSOR_QR_URL)))
        }.onFailure {
            if (::statusText.isInitialized) {
                statusText.text = "无法打开收款码链接：${it.javaClass.simpleName}"
            }
        }
    }

    private fun openXhsProfile() {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(XHS_PROFILE_URL)))
        }.onSuccess {
            AutoRefreshLog.i(this, "xhs profile opened")
        }.onFailure {
            if (::statusText.isInitialized) {
                statusText.text = "无法打开小红书主页：${it.javaClass.simpleName}\n$XHS_PROFILE_URL"
            }
            AutoRefreshLog.e(this, "open xhs profile failed", it)
        }
    }

    private fun normalizeDailyTime(raw: String): String {
        val m = Regex("""^\s*(\d{1,2}):(\d{1,2})\s*$""").find(raw)
        val h = m?.groupValues?.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 23) ?: 22
        val mm = m?.groupValues?.getOrNull(2)?.toIntOrNull()?.coerceIn(0, 59) ?: 30
        return String.format(Locale.US, "%02d:%02d", h, mm)
    }

    private fun buildPreviewPage(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 24, 12, 24)
        }
        previewText = TextView(this).apply {
            visibility = View.GONE
        }
        previewImage = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        container.addView(previewImage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return container
    }

    private fun showSettingsPage() {
        currentPageKey = "settings"
        settingsPage.visibility = View.VISIBLE
        previewPage.visibility = View.GONE
        updateTopNavState?.invoke()
        appendUiDebug("showSettingsPage settingsVisible=${settingsPage.visibility} previewVisible=${previewPage.visibility}")
        if (!isInitializingUi) writeDebugLog("showSettingsPage")
    }

    private fun showPreviewPage() {
        currentPageKey = "preview"
        settingsPage.visibility = View.GONE
        previewPage.visibility = View.VISIBLE
        updateTopNavState?.invoke()
        refreshPreview()
    }

    private fun refreshPreviewData() {
        val settings = readSettingsFromUi()
        if (settings.sourceMode == DataSourceMode.WEREAD || settings.sourceMode == DataSourceMode.MIXED) {
            previewWeReadWallpaper()
            return
        }
        saveSettings(settings)
        saveAndApplyAutoRefreshSettings()
        val requestId = beginPreviewRequest()
        statusText.text = "正在后台刷新预览..."
        renderLocalPreviewAsync(settings, requestId, showPreviewAfter = true)
        collectMetadataDebugSampleAsync("manual_refresh_preview")
        showPreviewPage()
    }

    private fun collectMetadataDebugSampleAsync(logEvent: String) {
        debugExecutor.execute {
            collectMetadataDebugSample()
            uiHandler.post {
                if (!isDestroyed && !isFinishing) writeDebugLog(logEvent)
            }
        }
    }

    private fun collectMetadataDebugSample() {
        runCatching {
            val maxRows = 30
            val rows = StringBuilder()
            contentResolver.query(metadataUri, null, null, null, "lastAccess DESC")?.use { c ->
                metadataDebugReport = "columns=${c.columnNames.joinToString(",")} count=${c.count}"
                var row = 0
                while (row < maxRows && c.moveToNext()) {
                    row++
                    fun col(name: String): String {
                        val i = c.getColumnIndex(name)
                        if (i < 0 || c.isNull(i)) return ""
                        return runCatching { c.getString(i) ?: "" }.getOrDefault("")
                    }
                    val title = col("title")
                    val path = col("nativeAbsolutePath")
                    val status = col("readingStatus")
                    val author = col("authors").ifBlank { col("author") }
                    val coverVals = listOf("coverPath", "cover", "coverUri", "thumbnail", "thumbnailPath", "bookCoverPath", "frontCoverPath", "coverUrl", "cover_url")
                        .mapNotNull { k ->
                            val v = col(k)
                            if (v.isBlank()) null else "$k=${v.take(120)}"
                        }
                    rows.append("row=").append(row)
                        .append(" title=").append(title.take(80))
                        .append(" status=").append(status.ifBlank { "?" })
                        .append(" author=").append(author.take(40))
                        .append(" ext=").append(File(path).extension.lowercase(Locale.ROOT))
                        .append(" path=").append(path.take(160))
                        .append('\n')
                    if (coverVals.isEmpty()) {
                        rows.append("  coverCandidates=<empty>\n")
                    } else {
                        rows.append("  coverCandidates=").append(coverVals.joinToString(" | ")).append('\n')
                    }
                }
            } ?: run {
                metadataDebugReport = "query=null"
                rows.append("<query returned null>")
            }
            metadataRowsDebugReport = rows.toString().ifBlank { "<empty>" }
        }.onFailure {
            metadataDebugReport = "error=${it.javaClass.simpleName}:${it.message}"
            metadataRowsDebugReport = "<error>"
        }
        collectLocalCalendarDebugProbe()
    }

    private fun collectLocalCalendarDebugProbe() {
        runCatching {
            val now = System.currentTimeMillis()
            val end = endOfDayMs(now)
            val start = startOfDayMs(now - 29L * 24L * 60L * 60L * 1000L)
            val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val metaByPath = linkedMapOf<String, CalendarMetaBook>()
            contentResolver.query(
                metadataUri,
                arrayOf("nativeAbsolutePath", "title", "authors", "coverUrl", "extraInfo", "downloadInfo"),
                null,
                null,
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    fun col(name: String): String {
                        val i = c.getColumnIndex(name)
                        if (i < 0 || c.isNull(i)) return ""
                        return runCatching { c.getString(i) ?: "" }.getOrDefault("")
                    }
                    val path = col("nativeAbsolutePath")
                    if (path.isBlank()) continue
                    val title = col("title").ifBlank { File(path).nameWithoutExtension }
                    val author = col("authors")
                    val coverHint = listOf("coverUrl", "extraInfo", "downloadInfo").any { col(it).isNotBlank() } || hasExtractedCoverCache(path)
                    val book = CalendarMetaBook(path, title, author, coverHint)
                    metaByPath[path] = book
                }
            }

            val days = linkedMapOf<Long, CalendarDayStat>()
            for (i in 0 until 30) {
                days[start + i * 24L * 60L * 60L * 1000L] = CalendarDayStat()
            }
            var statsRows = 0
            var statsRowsWithPath = 0
            var exactMatches = 0
            var nameMatches = 0
            val rawEvents = mutableListOf<ReadingEventAttribution.Event>()
            contentResolver.query(
                statsUri,
                arrayOf("path", "eventTime", "durationTime"),
                "eventTime >= ? AND eventTime <= ? AND durationTime IS NOT NULL AND durationTime != '' AND durationTime != '0'",
                arrayOf(start.toString(), end.toString()),
                null
            )?.use { c ->
                while (c.moveToNext()) {
                    fun col(name: String): String {
                        val i = c.getColumnIndex(name)
                        if (i < 0 || c.isNull(i)) return ""
                        return runCatching { c.getString(i) ?: "" }.getOrDefault("")
                    }
                    statsRows += 1
                    val path = col("path")
                    val eventMs = normalizeEpochMs(col("eventTime").toLongOrNull() ?: 0L)
                    val dur = col("durationTime").toLongOrNull() ?: 0L
                    if (eventMs <= 0L || dur <= 0L) continue
                    val dayStart = startOfDayMs(eventMs)
                    val day = days.getOrPut(dayStart) { CalendarDayStat() }
                    day.events += 1
                    if (path.isNotBlank()) {
                        statsRowsWithPath += 1
                        day.withPath += 1
                    } else {
                        day.orphan += 1
                    }
                    rawEvents += ReadingEventAttribution.Event(path, eventMs, dur)
                }
            }

            val attribution = ReadingEventAttribution.attribute(
                rawEvents,
                metaByPath.values.map { ReadingEventAttribution.Book(it.path, true) }
            )
            attribution.matches.forEach { match ->
                val day = days.getOrPut(startOfDayMs(match.event.timestampMs)) { CalendarDayStat() }
                val matchedBook = metaByPath[match.book.path] ?: return@forEach
                day.matched += 1
                day.durationMs += match.event.durationMs
                day.books[matchedBook.title] =
                    (day.books[matchedBook.title] ?: 0L) + match.event.durationMs
                if (matchedBook.hasCoverHint) day.coverBooks.add(matchedBook.title)
                when (match.confidence) {
                    ReadingEventAttribution.Confidence.EXACT_PATH -> exactMatches += 1
                    ReadingEventAttribution.Confidence.UNIQUE_FILE_NAME -> nameMatches += 1
                }
            }
            attribution.unmatched.forEach { event ->
                val day = days.getOrPut(startOfDayMs(event.event.timestampMs)) { CalendarDayStat() }
                day.unmatched += 1
            }
            val unmatchedReasons = attribution.unmatched
                .groupingBy { it.reason }
                .eachCount()
                .entries
                .joinToString(",") { "${it.key}=${it.value}" }
                .ifBlank { "none" }

            val out = StringBuilder()
            out.append("range=").append(dateFmt.format(Date(start))).append("~").append(dateFmt.format(Date(end))).append('\n')
            out.append("statsRows=").append(statsRows)
                .append(", rowsWithPath=").append(statsRowsWithPath)
                .append(", metadata=").append(metaByPath.size)
                .append(", exactMatches=").append(exactMatches)
                .append(", nameMatches=").append(nameMatches)
                .append(", unmatched=").append(attribution.unmatched.size)
                .append(", unmatchedReasons=").append(unmatchedReasons)
                .append('\n')
            days.entries.forEach { (dayStart, stat) ->
                if (stat.events == 0 && stat.books.isEmpty()) return@forEach
                val top = stat.books.entries
                    .sortedByDescending { it.value }
                    .take(4)
                    .joinToString(" | ") { "${it.key.take(24)}:${formatMinutesForDebug(it.value)}" }
                    .ifBlank { "<no-book-match>" }
                out.append(dateFmt.format(Date(dayStart)))
                    .append(" events=").append(stat.events)
                    .append(", withPath=").append(stat.withPath)
                    .append(", orphan=").append(stat.orphan)
                    .append(", matched=").append(stat.matched)
                    .append(", unmatched=").append(stat.unmatched)
                    .append(", total=").append(formatMinutesForDebug(stat.durationMs))
                    .append(", coverBooks=").append(stat.coverBooks.size)
                    .append(", top=").append(top)
                    .append('\n')
            }
            localCalendarProbeReport = out.toString().ifBlank { "<empty>" }
        }.onFailure {
            localCalendarProbeReport = "error=${it.javaClass.simpleName}:${it.message}"
        }
    }

    private fun hasExtractedCoverCache(path: String): Boolean {
        if (path.isBlank()) return false
        val f = File(path)
        val cacheFile = File(File(cacheDir, "extracted_covers"), "${path.hashCode()}_${f.lastModified()}.jpg")
        return cacheFile.exists() && cacheFile.length() > 0L
    }

    private fun normalizeEpochMs(value: Long): Long {
        return when {
            value <= 0L -> 0L
            value < 10_000_000_000L -> value * 1000L
            else -> value
        }
    }

    private fun startOfDayMs(ms: Long): Long {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.timeInMillis = ms
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun endOfDayMs(ms: Long): Long {
        return startOfDayMs(ms) + 24L * 60L * 60L * 1000L - 1L
    }

    private fun formatMinutesForDebug(ms: Long): String {
        val minutes = (ms / 60_000L).coerceAtLeast(0L)
        val hours = minutes / 60L
        val remain = minutes % 60L
        return if (hours > 0L) "${hours}h${remain}m" else "${minutes}m"
    }

    private fun refreshPreview() {
        val bmp = previewBitmap
        if (bmp != null) {
            previewImage.setImageBitmap(bmp)
            previewText.text = ""
            return
        }
        previewText.text = ""
        previewImage.setImageDrawable(null)
    }

    private fun readSettingsFromUi(): Settings {
        val includeUnread = includeUnreadCheck.isChecked
        val showChart = showChartCheck.isChecked
        val showProgressStatus = showProgressStatusCheck.isChecked
        val showAuthor = showAuthorCheck.isChecked
        val showBookDuration = showBookDurationCheck.isChecked
        val minDurationMinutes = minDurationInput.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: 1
        val topN = topNInput.text.toString().trim().toIntOrNull()?.coerceIn(1, 5) ?: 5
        val weekStart = selectedWeekStartYmd.ifBlank { currentWeekStartYmd() }
        val weekEnd = selectedWeekEndYmd.ifBlank { currentWeekEndYmd() }
        val periodMode = when (periodGroup.checkedRadioButtonId) {
            4000 -> PeriodMode.TODAY
            4006 -> PeriodMode.YESTERDAY
            4002 -> PeriodMode.LAST_WEEK
            4007 -> PeriodMode.THIS_MONTH
            4003 -> PeriodMode.LAST_7_DAYS
            4004 -> PeriodMode.LAST_30_DAYS
            4005 -> PeriodMode.CUSTOM
            else -> PeriodMode.THIS_WEEK
        }
        val readingFilterMode = when (readingFilterGroup.checkedRadioButtonId) {
            6002 -> ReadingFilterMode.READING_ONLY
            6003 -> ReadingFilterMode.FINISHED_ONLY
            else -> ReadingFilterMode.ALL
        }
        val sourceMode = when (sourceGroup.checkedRadioButtonId) {
            1002 -> DataSourceMode.PATH_SESSION
            1003 -> DataSourceMode.METADATA_ACCESS
            1004 -> DataSourceMode.WEREAD
            1005 -> DataSourceMode.MIXED
            else -> DataSourceMode.DURATION
        }
        val wallpaperMode = when (wallpaperModeGroup.checkedRadioButtonId) {
            1202 -> "COVER"
            1203 -> "AUTO_COVER"
            1204 -> "CALENDAR"
            else -> "STATS"
        }
        val statsTemplate = when (statsTemplateGroup.checkedRadioButtonId) {
            1242 -> "EXCERPT_MENU"
            else -> "RECEIPT"
        }
        val calendarStackOrder = when (calendarStackOrderGroup.checkedRadioButtonId) {
            1222 -> "SHORTEST_TOP"
            1223 -> "LATEST_TOP"
            else -> "LONGEST_TOP"
        }
        val coverFitMode = when (coverFitModeGroup.checkedRadioButtonId) {
            1212 -> "CROP"
            else -> "FIT"
        }
        val progressMode = when (progressModeGroup.checkedRadioButtonId) {
            6102 -> "PERCENT"
            else -> "PAGES"
        }
        val timeUnit = when (timeUnitGroup.checkedRadioButtonId) {
            2002 -> "MINUTE"
            else -> "HOUR"
        }
        val serialNumberMode = when (serialModeGroup.checkedRadioButtonId) {
            2012 -> "RANDOM"
            2013 -> "CUSTOM"
            else -> "DATE"
        }
        val serialNumberCustomRaw = serialCustomInput.text.toString().trim()
        val serialNumberCustom = serialNumberCustomRaw.filter { it.isDigit() }.take(12)
        val serialNumberSize = serialNumberSizeInput.text.toString().trim().toFloatOrNull()?.coerceIn(24f, 140f) ?: 46f
        val booxDevicePreset = booxPresetKeyByRadioId(booxDevicePresetGroup.checkedRadioButtonId)
        val fallbackPreset = BooxDevicePresets.byKey(if (booxDevicePreset == BooxDevicePresets.CUSTOM_KEY) BooxDevicePresets.DEFAULT_KEY else booxDevicePreset)
        val customWallpaperWidth = customWallpaperWidthInput.text.toString().trim().toIntOrNull()?.coerceIn(300, 4000) ?: fallbackPreset.widthPx
        val customWallpaperHeight = customWallpaperHeightInput.text.toString().trim().toIntOrNull()?.coerceIn(300, 4000) ?: fallbackPreset.heightPx
        val receiptTitle = titleInput.text.toString().ifBlank { "阅读账单" }
        val receiptTitleSize = titleSizeInput.text.toString().trim().toFloatOrNull()?.coerceIn(24f, 120f) ?: 74f
        val receiptBodySize = bodySizeInput.text.toString().trim().toFloatOrNull()?.coerceIn(18f, 60f) ?: 34f
        val chartStyleMode = when (chartStyleGroup.checkedRadioButtonId) {
            7002 -> ChartStyleMode.BAR
            else -> ChartStyleMode.LINE
        }
        val showPeakLabel = showPeakLabelCheck.isChecked
        val yAxisMode = when (yAxisModeGroup.checkedRadioButtonId) {
            7102 -> YAxisMode.FIXED
            else -> YAxisMode.AUTO
        }
        val yAxisFixedMaxMinutes = yAxisMaxInput.text.toString().trim().toIntOrNull()?.coerceIn(1, 2000) ?: 300
        val footerMode = when (footerModeGroup.checkedRadioButtonId) {
            3002 -> "NOTE"
            3003 -> "BARCODE"
            else -> "NONE"
        }
        val noteText = noteInput.text.toString().trim()
        val barcodeWidthScale = when (barcodeWidthGroup.checkedRadioButtonId) {
            3101 -> 0.8f
            3103 -> 1.2f
            else -> 1.0f
        }
        val barcodeGapMode = when (barcodeGapGroup.checkedRadioButtonId) {
            3111 -> "TIGHT"
            3113 -> "LOOSE"
            else -> "STANDARD"
        }
        val titleFont = fontSpec(titleFontSpinner.selectedItem?.toString() ?: "SERIF_BOLD")
        val bodyFont = fontSpec(bodyFontSpinner.selectedItem?.toString() ?: "MONO")
        return Settings(includeUnread, showChart, showProgressStatus, showAuthor, showBookDuration, minDurationMinutes, topN, weekStart, weekEnd, periodMode, readingFilterMode, sourceMode, wallpaperMode, statsTemplate, calendarStackOrder, coverFitMode, progressMode, timeUnit, receiptTitle, receiptTitleSize, receiptBodySize, serialNumberMode, serialNumberCustom, serialNumberSize, booxDevicePreset, customWallpaperWidth, customWallpaperHeight, footerMode, barcodeWidthScale, barcodeGapMode, noteText, chartStyleMode, showPeakLabel, yAxisMode, yAxisFixedMaxMinutes, titleFont, bodyFont)
    }

    private fun saveSettings(settings: Settings) {
        getSharedPreferences("wallpaper_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("include_unread", settings.includeUnread)
            .putBoolean("show_chart", settings.showChart)
            .putBoolean("show_progress_status", settings.showProgressStatus)
            .putBoolean("show_author", settings.showAuthor)
            .putBoolean("show_book_duration", settings.showBookDuration)
            .putInt("min_duration_minutes", settings.minDurationMinutes)
            .putInt("top_n", settings.topN)
            .putString("week_start", settings.weekStartYmd)
            .putString("week_end", settings.weekEndYmd)
            .putString("period_mode", settings.periodMode.name)
            .putString("reading_filter_mode", settings.readingFilterMode.name)
            .putString("source_mode", settings.sourceMode.name)
            .putString("wallpaper_mode", settings.wallpaperMode)
            .putString("stats_template", settings.statsTemplate)
            .putString("calendar_stack_order", settings.calendarStackOrder)
            .putString("cover_fit_mode", settings.coverFitMode)
            .putString("progress_mode", settings.progressMode)
            .putString("time_unit", settings.timeUnit)
            .putString("receipt_title", settings.receiptTitle)
            .putFloat("receipt_title_size", settings.receiptTitleSize)
            .putFloat("receipt_body_size", settings.receiptBodySize)
            .putString("serial_number_mode", settings.serialNumberMode)
            .putString("serial_number_custom", settings.serialNumberCustom)
            .putFloat("serial_number_size", settings.serialNumberSize)
            .putString("boox_device_preset", settings.booxDevicePreset)
            .putInt("custom_wallpaper_width", settings.customWallpaperWidth)
            .putInt("custom_wallpaper_height", settings.customWallpaperHeight)
            .putString("footer_mode", settings.footerMode)
            .putFloat("barcode_width_scale", settings.barcodeWidthScale)
            .putString("barcode_gap_mode", settings.barcodeGapMode)
            .putString("note_text", settings.noteText)
            .putString("chart_style_mode", settings.chartStyleMode.name)
            .putBoolean("show_peak_label", settings.showPeakLabel)
            .putBoolean(
                AutoRefreshConfig.KEY_READING_DATA_STORE_ENABLED,
                readingDataStoreCheck.isChecked
            )
            .putString("y_axis_mode", settings.yAxisMode.name)
            .putInt("y_axis_fixed_max_minutes", settings.yAxisFixedMaxMinutes)
            .putString("title_font", settings.titleFont)
            .putString("body_font", settings.bodyFont)
            .apply()
    }

    private fun renderWallpaperPreview(settings: Settings): Pair<Bitmap, String> {
        val preview = AutoWallpaperGenerator.buildPreviewFromPrefs(this)
            ?: return "日期格式错误，请用 yyyy-MM-dd".let { Pair(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888), it) }
        return Pair(preview.bitmap, "已生成\n${preview.summary}")
    }

    private fun parseWeek(startYmd: String): Pair<Long, Long>? {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getDefault()
            val d = sdf.parse(startYmd) ?: return null
            val cal = Calendar.getInstance(TimeZone.getDefault())
            cal.time = d
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            val start = cal.timeInMillis
            cal.add(Calendar.DAY_OF_MONTH, 6)
            cal.set(Calendar.HOUR_OF_DAY, 23)
            cal.set(Calendar.MINUTE, 59)
            cal.set(Calendar.SECOND, 59)
            cal.set(Calendar.MILLISECOND, 999)
            start to cal.timeInMillis
        } catch (_: Exception) {
            null
        }
    }

    private fun parseYmd(ymd: String): Long? {
        return try {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            sdf.timeZone = TimeZone.getDefault()
            sdf.parse(ymd)?.time
        } catch (_: Exception) {
            null
        }
    }

    private fun currentWeekStartYmd(): String {
        val cal = Calendar.getInstance(TimeZone.getDefault())
        cal.firstDayOfWeek = Calendar.SUNDAY
        cal.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(cal.timeInMillis))
    }

    private fun currentWeekEndYmd(): String {
        val c = Calendar.getInstance(TimeZone.getDefault())
        c.firstDayOfWeek = Calendar.SUNDAY
        c.set(Calendar.DAY_OF_WEEK, Calendar.SUNDAY)
        c.add(Calendar.DAY_OF_MONTH, 6)
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(c.timeInMillis))
    }

    private fun loadSystemFonts(): List<String> {
        val result = mutableListOf("SERIF_BOLD", "SANS", "MONO")
        val report = StringBuilder("字体目录扫描:\n")
        report.append("fontTreeUri=").append(selectedFontDirUri ?: "<null>").append("\n")
        report.append("fontPermissionDebug=").append(fontPermissionDebug.ifBlank { "<empty>" }).append("\n")

        if (!selectedFontDirUri.isNullOrBlank()) {
            try {
                val treeUri = Uri.parse(selectedFontDirUri!!)
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                var treeCount = 0
                contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE
                    ),
                    null,
                    null,
                    null
                )?.use { c ->
                    val idIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (c.moveToNext()) {
                        val display = if (nameIdx >= 0) c.getString(nameIdx) ?: "" else ""
                        val mime = if (mimeIdx >= 0) c.getString(mimeIdx) ?: "" else ""
                        if (mime != DocumentsContract.Document.MIME_TYPE_DIR &&
                            (display.endsWith(".ttf", true) || display.endsWith(".otf", true) || display.endsWith(".ttc", true))
                        ) {
                            val childId = if (idIdx >= 0) c.getString(idIdx) else null
                            if (!childId.isNullOrBlank()) {
                                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                                result.add("${display}${FONT_ENTRY_SEP}${childUri}")
                                treeCount++
                            }
                        }
                    }
                }
                report.append("fontTreeCount=").append(treeCount).append("\n")
            } catch (e: Exception) {
                report.append("fontTreeError=").append(e.message ?: "unknown").append("\n")
            }
        }

        fontScanReport = report.toString()
        return result.distinct()
    }

    private fun saveBitmapToPictures(bitmap: Bitmap): String {
        return WallpaperFileStore.save(this, bitmap)
    }

    private fun dumpTextTree(view: View, maxItems: Int = 80): String {
        val out = mutableListOf<String>()
        fun redactSecrets(text: String): String {
            return Regex("""wrk-[A-Za-z0-9_=-]{8,}""").replace(text) { match ->
                WeReadClient.maskKey(match.value)
            }
        }
        fun walk(v: View, depth: Int) {
            if (out.size >= maxItems) return
            if (v is TextView) {
                val text = redactSecrets(v.text?.toString().orEmpty())
                    .replace('\n', '|')
                    .take(120)
                if (text.isNotBlank()) {
                    out += "${"  ".repeat(depth)}${v.javaClass.simpleName}:$text visibility=${v.visibility}"
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
        }
        walk(view, 0)
        return out.joinToString("\n").ifBlank { "<empty>" }
    }

    private fun writeDebugLog(event: String) {
        try {
            if (localCalendarProbeReport.isBlank()) collectLocalCalendarDebugProbe()
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, debugLogName)
            val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val s = readSettingsFromUi()
            FileWriter(f, false).use { w ->
                w.append("event=").append(event).append('\n')
                w.append("time=").append(now).append('\n')
                w.append("deviceIdentity=").append(deviceIdentityText()).append('\n')
                w.append("detectedBooxDevicePreset=").append(detectBooxDevicePreset()).append('\n')
                val wereadState = WeReadClient.cachedState(this)
                w.append("weread=").append("key=").append(wereadState.maskedKey)
                    .append(", status=").append(wereadState.status)
                    .append(", lastTestMs=").append(wereadState.lastTestMs.toString())
                    .append(", error=").append(wereadState.error)
                    .append('\n')
                w.append("lastWeReadStats=").append(lastWeReadStatsDebug.ifBlank { "<empty>" }).append('\n')
                w.append("lastWeReadCover=").append(lastWeReadCoverDebug.ifBlank { "<empty>" }).append('\n')
                w.append("lastWeReadWallpaper=").append(lastWeReadWallpaperDebug.ifBlank { "<empty>" }).append('\n')
                w.append("currentPageKey=").append(currentPageKey).append('\n')
                if (::settingsPage.isInitialized) {
                    w.append("settingsPageVisibility=").append(settingsPage.visibility.toString()).append('\n')
                    w.append("previewPageVisibility=").append(previewPage.visibility.toString()).append('\n')
                }
                w.append("selectedWeekStart=").append(selectedWeekStartYmd).append('\n')
                w.append("settings=").append("includeUnread=").append(s.includeUnread.toString())
                    .append(", showChart=").append(s.showChart.toString())
                    .append(", showProgressStatus=").append(s.showProgressStatus.toString())
                    .append(", showAuthor=").append(s.showAuthor.toString())
                    .append(", showBookDuration=").append(s.showBookDuration.toString())
                    .append(", minDuration=").append(s.minDurationMinutes.toString())
                    .append(", topN=").append(s.topN.toString())
                    .append(", readingDataStoreEnabled=").append(readingDataStoreCheck.isChecked.toString())
                    .append(", sourceMode=").append(s.sourceMode.name)
                    .append(", wallpaperMode=").append(s.wallpaperMode)
                    .append(", statsTemplate=").append(s.statsTemplate)
                    .append(", coverFitMode=").append(s.coverFitMode)
                    .append(", progressMode=").append(s.progressMode)
                    .append(", timeUnit=").append(s.timeUnit)
                    .append(", receiptTitle=").append(s.receiptTitle)
                    .append(", receiptTitleSize=").append(s.receiptTitleSize.toString())
                    .append(", receiptBodySize=").append(s.receiptBodySize.toString())
                    .append(", serialNumberMode=").append(s.serialNumberMode)
                    .append(", serialNumberCustom=").append(s.serialNumberCustom)
                    .append(", serialNumberSize=").append(s.serialNumberSize.toString())
                    .append(", booxDevicePreset=").append(s.booxDevicePreset)
                    .append(", customWallpaperWidth=").append(s.customWallpaperWidth.toString())
                    .append(", customWallpaperHeight=").append(s.customWallpaperHeight.toString())
                    .append(", footerMode=").append(s.footerMode)
                    .append(", noteText=").append(s.noteText)
                    .append(", titleFont=").append(s.titleFont)
                    .append(", bodyFont=").append(s.bodyFont)
                    .append('\n')
                w.append("lastSavedPath=").append(lastSavedPath ?: "<null>").append('\n')
                w.append("fontCount=").append(systemFonts.size.toString()).append('\n')
                w.append('\n')
                w.append("uiDebugReport=").append('\n').append(uiDebugReport.ifBlank { "<empty>" }).append('\n')
                if (::settingsPage.isInitialized) {
                    w.append("settingsPageTextDump=").append('\n').append(dumpTextTree(settingsPage)).append('\n')
                }
                w.append('\n')
                w.append(fontScanReport)
                w.append('\n')
                w.append("barcodeDebug=").append(barcodeDebugReport.ifBlank { "<empty>" }).append('\n')
                w.append("metadataDebug=").append(metadataDebugReport.ifBlank { "<empty>" }).append('\n')
                w.append("metadataRowsDebug=").append('\n').append(metadataRowsDebugReport.ifBlank { "<empty>" }).append('\n')
                w.append("localCalendarProbe=").append('\n').append(localCalendarProbeReport.ifBlank { "<empty>" }).append('\n')
                val persisted = contentResolver.persistedUriPermissions
                w.append("persistedUriPermissions=").append(persisted.size.toString()).append('\n')
                persisted.forEachIndexed { i, p ->
                    w.append("persisted[").append(i.toString()).append("]=")
                        .append(p.uri.toString())
                        .append(" read=").append(p.isReadPermission.toString())
                        .append(" write=").append(p.isWritePermission.toString())
                        .append('\n')
                }
            }
        } catch (_: Exception) {
        }
    }
}
