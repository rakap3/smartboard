package id.kelasku.papanguru

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Teacher home screen: the app's entry point, and optionally the device's home screen
 * (the disabled-by-default `HomeLauncher` alias adds the HOME category).
 */
class HomeActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var store: BoardStore
    private val handler = Handler(Looper.getMainLooper())
    private val locale = Locale("id", "ID")

    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var greeting: TextView
    private lateinit var resumeTile: LinearLayout
    private lateinit var resumeThumb: ImageView
    private lateinit var resumeTitle: TextView
    private lateinit var resumeSubtitle: TextView
    private lateinit var recentRow: LinearLayout
    private lateinit var notice: TextView
    private lateinit var container: FrameLayout
    private var appsPanel: View? = null
    private var lastBoard: BoardSummary? = null

    private val tick = object : Runnable {
        override fun run() {
            updateClock()
            val now = System.currentTimeMillis()
            handler.postDelayed(this, 60_000 - now % 60_000 + 50)
        }
    }
    private val hideNotice = Runnable { notice.animate().alpha(0f).withEndAction { notice.visibility = View.GONE }.start() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui = Ui(this)
        store = BoardStore(this)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(28), ui.dp(18), ui.dp(28), ui.dp(24))
        }
        page.addView(buildTopBar(), LinearLayout.LayoutParams(-1, -2))
        greeting = ui.label("", 26f, Ui.TEXT, bold = true)
        page.addView(greeting, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(18) })
        page.addView(ui.label("Apa yang ingin diajarkan hari ini?", 15f, Ui.MUTED), LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(2) })
        page.addView(buildMainTiles(), LinearLayout.LayoutParams(-1, ui.dp(168)).apply { topMargin = ui.dp(18) })
        page.addView(sectionHeader("Papan terbaru", "Lihat semua") { openBoard(showList = true) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(26) })
        recentRow = LinearLayout(this)
        page.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(recentRow)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        page.addView(sectionHeader("Aplikasi", null, null), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(26) })
        page.addView(buildAppTiles(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        page.addView(ui.label("Papan Guru ${versionName()}", 12f, 0xFF7D818B.toInt()).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(28) })

        container = FrameLayout(this).apply { background = homeBackground() }
        container.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(page)
        }, FrameLayout.LayoutParams(-1, -1))
        notice = ui.label("", 15f, Ui.TEXT).apply {
            background = ui.panel(14)
            setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12))
            elevation = ui.dp(14).toFloat()
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        container.addView(notice, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ui.dp(32) })
        setContentView(container)
        ui.hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
        refreshBoards()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ui.hideSystemBars()
    }

    @Deprecated("Back handling for the platform Activity")
    override fun onBackPressed() {
        appsPanel?.let {
            closeApps()
            return
        }
        // As the device's home screen there is nowhere to go back to.
        if (isDefaultHome()) return
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    // region Layout

    private fun homeBackground() = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1F2A35.toInt(), 0xFF16171A.toInt(), 0xFF121316.toInt()))

    private fun buildTopBar() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(this@HomeActivity).apply {
            setImageResource(applicationInfo.icon)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)))
        addView(ui.label("Papan Guru", 20f, Ui.TEXT, bold = true).apply { setPadding(ui.dp(12), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        val time = LinearLayout(this@HomeActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        clock = ui.label("", 30f, Ui.TEXT, bold = true)
        date = ui.label("", 14f, Ui.MUTED)
        time.addView(clock)
        time.addView(date)
        addView(time, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(12) })
        val settings = ui.iconButton("Pengaturan beranda", R.drawable.ic_settings)
        settings.setOnClickListener { showSettings(settings) }
        addView(settings, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
    }

    private fun buildMainTiles() = LinearLayout(this).apply {
        addView(tile("Papan tulis baru", "Mulai dari halaman kosong", R.drawable.ic_pen_board, primary = true) {
            openBoard(new = true)
        }, LinearLayout.LayoutParams(0, -1, 1f))
        resumeTile = tile("Lanjutkan", "", R.drawable.ic_folder) {
            lastBoard?.let { openBoard(id = it.id) } ?: openBoard(new = true)
        }
        resumeThumb = resumeTile.getChildAt(0) as ImageView
        resumeTitle = resumeTile.getChildAt(1) as TextView
        resumeSubtitle = resumeTile.getChildAt(2) as TextView
        addView(resumeTile, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = ui.dp(14) })
        addView(tile("Buka PDF", "Jadikan materi sebagai halaman papan", R.drawable.ic_pdf) { pickPdf() },
            LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = ui.dp(14) })
    }

    private fun tileBackground(primary: Boolean): StateListDrawable = StateListDrawable().apply {
        val idle = if (primary) ui.surface(0xFF3B739E.toInt(), 0xFF22405A.toInt(), 20, 0xFF82CFFF.toInt())
        else ui.surface(0xFF3A3D45.toInt(), 0xFF25272C.toInt(), 20, 0xFF5E626C.toInt())
        addState(intArrayOf(android.R.attr.state_pressed), ui.surface(0xFF5C6370.toInt(), 0xFF3B414C.toInt(), 20, 0xFFADD9FF.toInt()))
        addState(intArrayOf(android.R.attr.state_focused), ui.surface(0xFF4F5866.toInt(), 0xFF323843.toInt(), 20, 0xFFFFFFFF.toInt()))
        addState(intArrayOf(), idle)
    }

    /** Big launcher tile; children are icon/thumbnail, title, subtitle (in that order). */
    private fun tile(title: String, subtitle: String, icon: Int, primary: Boolean = false, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(18), ui.dp(20), ui.dp(16))
            background = tileBackground(primary)
            elevation = ui.dp(4).toFloat()
            isClickable = true
            isFocusable = true
            contentDescription = title
            setOnClickListener { onClick() }
            addView(ImageView(this@HomeActivity).apply {
                setImageResource(icon)
                setColorFilter(if (primary) Color.WHITE else Ui.ACCENT)
                scaleType = ImageView.ScaleType.FIT_START
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(ui.label(title, 19f, Ui.TEXT, bold = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
            addView(ui.label(subtitle, 13f, if (primary) 0xFFD6E9F8.toInt() else Ui.MUTED).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(2) })
        }

    private fun sectionHeader(title: String, action: String?, onAction: (() -> Unit)?) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ui.label(title, 17f, Ui.TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        if (action != null && onAction != null) {
            addView(ui.label(action, 14f, Ui.ACCENT).apply {
                setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8))
                background = ui.buttonStates(transparentIdle = true)
                isClickable = true
                isFocusable = true
                setOnClickListener { onAction() }
            })
        }
    }

    private fun buildAppTiles() = LinearLayout(this).apply {
        addView(appTile("Browser", R.drawable.ic_browser) {
            launch(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_BROWSER), "Browser tidak tersedia")
        })
        addView(appTile("Berkas", R.drawable.ic_folder) { openFiles() })
        addView(appTile("Pengaturan", R.drawable.ic_settings) {
            launch(Intent(Settings.ACTION_SETTINGS), "Pengaturan tidak tersedia")
        })
        addView(appTile("Semua aplikasi", R.drawable.ic_apps) { showApps() })
    }

    private fun appTile(label: String, icon: Int? = null, drawable: Drawable? = null, onClick: () -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(ui.dp(8), ui.dp(12), ui.dp(8), ui.dp(10))
            background = tileBackground(false)
            isClickable = true
            isFocusable = true
            contentDescription = label
            setOnClickListener { onClick() }
            addView(ImageView(this@HomeActivity).apply {
                if (drawable != null) setImageDrawable(drawable) else {
                    setImageResource(icon ?: R.drawable.ic_apps)
                    setColorFilter(Ui.TEXT)
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ui.dp(if (drawable != null) 48 else 32), ui.dp(if (drawable != null) 48 else 32)))
            addView(ui.label(label, 13f, Ui.TEXT).apply {
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })
            layoutParams = LinearLayout.LayoutParams(ui.dp(116), ui.dp(108)).apply { marginEnd = ui.dp(12) }
        }

    // endregion

    // region Content

    private fun updateClock() {
        val now = Date()
        clock.text = SimpleDateFormat("HH.mm", locale).format(now)
        date.text = SimpleDateFormat("EEEE, d MMMM yyyy", locale).format(now)
        greeting.text = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 4..10 -> "Selamat pagi"
            in 11..14 -> "Selamat siang"
            in 15..17 -> "Selamat sore"
            else -> "Selamat malam"
        }
    }

    private fun refreshBoards() {
        // Listing runs after any pending board saves, so names and thumbnails are current.
        store.afterPendingWrites {
            val boards = store.list()
            val thumbs = boards.take(RECENT).map { decodeThumb(it) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                showBoards(boards, thumbs)
            }
        }
    }

    private fun decodeThumb(summary: BoardSummary): Bitmap? = try {
        BitmapFactory.decodeFile(summary.thumbnail.path)
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun showBoards(boards: List<BoardSummary>, thumbs: List<Bitmap?>) {
        val last = boards.firstOrNull()
        lastBoard = last
        if (last != null) {
            resumeTitle.text = "Lanjutkan: ${last.name}"
            resumeSubtitle.text = "${last.pages} halaman · ${relativeTime(last.modified)}"
            resumeTile.contentDescription = "Lanjutkan ${last.name}"
            val thumb = thumbs.firstOrNull()
            if (thumb != null) {
                resumeThumb.clearColorFilter()
                resumeThumb.scaleType = ImageView.ScaleType.FIT_START
                resumeThumb.setImageBitmap(thumb)
            }
        } else {
            resumeTitle.text = "Lanjutkan"
            resumeSubtitle.text = "Belum ada papan tersimpan"
        }

        recentRow.removeAllViews()
        if (boards.isEmpty()) {
            recentRow.addView(ui.label("Papan yang dibuat akan muncul di sini.", 14f, Ui.MUTED).apply {
                setPadding(0, ui.dp(8), 0, ui.dp(8))
            })
            return
        }
        boards.take(RECENT).forEachIndexed { i, summary -> recentRow.addView(boardCard(summary, thumbs.getOrNull(i))) }
        if (boards.size > RECENT) {
            recentRow.addView(appTile("Lihat ${boards.size - RECENT} lainnya", R.drawable.ic_folder) { openBoard(showList = true) }.apply {
                layoutParams = LinearLayout.LayoutParams(ui.dp(140), ui.dp(CARD_HEIGHT))
            })
        }
    }

    private fun boardCard(summary: BoardSummary, thumb: Bitmap?) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(10))
        background = tileBackground(false)
        isClickable = true
        isFocusable = true
        contentDescription = "Buka ${summary.name}"
        setOnClickListener { openBoard(id = summary.id) }
        addView(ImageView(this@HomeActivity).apply {
            if (thumb != null) setImageBitmap(thumb)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ui.surface(0xFF111211.toInt(), 0xFF191C18.toInt(), 10, 0xFF474A45.toInt())
            clipToOutline = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        addView(ui.label(summary.name, 14f, Ui.TEXT, bold = true).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(ui.dp(4), ui.dp(8), ui.dp(4), 0)
        })
        addView(ui.label("${summary.pages} hlm · ${relativeTime(summary.modified)}", 12f, Ui.MUTED).apply {
            maxLines = 1
            setPadding(ui.dp(4), ui.dp(2), ui.dp(4), 0)
        })
        layoutParams = LinearLayout.LayoutParams(ui.dp(208), ui.dp(CARD_HEIGHT)).apply { marginEnd = ui.dp(12) }
    }

    private fun relativeTime(time: Long): String {
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = time }
        val days = ((startOfDay(now) - startOfDay(then)) / 86_400_000L).toInt()
        val clock = SimpleDateFormat("HH.mm", locale).format(Date(time))
        return when {
            days <= 0 -> "Hari ini $clock"
            days == 1 -> "Kemarin $clock"
            days < 7 -> "$days hari lalu"
            else -> SimpleDateFormat("d MMM yyyy", locale).format(Date(time))
        }
    }

    private fun startOfDay(c: Calendar): Long = (c.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // endregion

    // region Actions

    private fun openBoard(id: String? = null, new: Boolean = false, showList: Boolean = false, pdf: Uri? = null, name: String? = null) {
        startActivity(Intent(this, MainActivity::class.java).apply {
            id?.let { putExtra(MainActivity.EXTRA_BOARD, it) }
            if (new) putExtra(MainActivity.EXTRA_NEW, true)
            name?.let { putExtra(MainActivity.EXTRA_NAME, it) }
            if (showList) putExtra(MainActivity.EXTRA_BOARDS, true)
            if (pdf != null) {
                data = pdf
                putExtra(MainActivity.EXTRA_PDF, true)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        })
    }

    private fun pickPdf() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/pdf"
            }, REQ_PDF)
        } catch (_: ActivityNotFoundException) {
            toast("Pemilih berkas tidak tersedia")
        }
    }

    @Deprecated("Uses the platform document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        ui.hideSystemBars()
        val uri = data?.data
        if (requestCode == REQ_PDF && resultCode == RESULT_OK && uri != null) {
            openBoard(new = true, pdf = uri, name = pdfName(uri))
        }
    }

    /** The PDF's file name without extension, used as the new board's name. */
    private fun pdfName(uri: Uri): String? = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.removeSuffix(".pdf")?.removeSuffix(".PDF")?.trim()?.take(60) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun openFiles() {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= 29) add(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_FILES))
            // The system Files app (DocumentsUI) on most devices.
            add(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse("content://com.android.externalstorage.documents/root/primary"), "vnd.android.document/root"))
        }
        for (intent in intents) {
            try {
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        toast("Aplikasi berkas tidak tersedia")
    }

    private fun launch(intent: Intent, missing: String) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            toast(missing)
        } catch (_: SecurityException) {
            toast(missing)
        }
    }

    private fun showSettings(anchor: View) {
        ui.menu(anchor, listOf(
            Ui.MenuEntry("Jadikan layar utama perangkat", R.drawable.ic_home, checked = homeAliasEnabled()) { toggleHome() },
            Ui.MenuEntry("Pilih aplikasi layar utama", R.drawable.ic_apps) { openHomeSettings() },
            Ui.MenuEntry("Pengaturan Android", R.drawable.ic_settings) { launch(Intent(Settings.ACTION_SETTINGS), "Pengaturan tidak tersedia") },
        ))
    }

    private val homeAlias get() = ComponentName(packageName, "id.kelasku.papanguru.HomeLauncher")

    private fun homeAliasEnabled() =
        packageManager.getComponentEnabledSetting(homeAlias) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    private fun toggleHome() {
        val enable = !homeAliasEnabled()
        packageManager.setComponentEnabledSetting(
            homeAlias,
            if (enable) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        if (enable) {
            toast("Pilih Papan Guru sebagai aplikasi layar utama")
            handler.postDelayed({ openHomeSettings() }, 900)
        } else {
            toast("Papan Guru tidak lagi menjadi layar utama")
        }
    }

    private fun openHomeSettings() {
        try {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: ActivityNotFoundException) {
            launch(Intent(Settings.ACTION_SETTINGS), "Pengaturan tidak tersedia")
        }
    }

    private fun isDefaultHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: PackageManager.NameNotFoundException) {
        ""
    }

    private fun toast(text: String) {
        handler.removeCallbacks(hideNotice)
        notice.animate().cancel()
        notice.text = text
        notice.alpha = 0f
        notice.visibility = View.VISIBLE
        notice.animate().alpha(1f).setDuration(150).start()
        handler.postDelayed(hideNotice, 2600)
    }

    // endregion

    // region All apps

    private class AppEntry(val label: String, val icon: Drawable, val launch: Intent)

    private fun showApps() {
        if (appsPanel != null) return
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.label("Semua aplikasi", 20f, Ui.TEXT, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(ui.iconButton("Tutup daftar aplikasi", R.drawable.ic_close).apply { setOnClickListener { closeApps() } },
                LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        }
        val loading = ui.label("Memuat aplikasi…", 15f, Ui.MUTED)
        grid.addView(loading)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(28), ui.dp(18), ui.dp(28), ui.dp(18))
            setBackgroundColor(0xF2121316.toInt())
            isClickable = true // keep touches from reaching the home screen below
            addView(header, LinearLayout.LayoutParams(-1, -2))
            addView(ScrollView(this@HomeActivity).apply { addView(grid) }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(12) })
            alpha = 0f
            animate().alpha(1f).setDuration(150).start()
        }
        appsPanel = panel
        container.addView(panel, FrameLayout.LayoutParams(-1, -1))
        Thread {
            val apps = loadApps()
            runOnUiThread {
                if (appsPanel !== panel) return@runOnUiThread
                grid.removeView(loading)
                val columns = ((container.width - ui.dp(56)) / ui.dp(128)).coerceAtLeast(3)
                var row: LinearLayout? = null
                apps.forEachIndexed { i, app ->
                    if (i % columns == 0) row = LinearLayout(this).also {
                        grid.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(12) })
                    }
                    row?.addView(appTile(app.label, drawable = app.icon) {
                        closeApps()
                        launch(app.launch, "Aplikasi tidak bisa dibuka")
                    })
                }
                if (apps.isEmpty()) grid.addView(ui.label("Tidak ada aplikasi lain.", 15f, Ui.MUTED))
            }
        }.start()
    }

    private fun closeApps() {
        val panel = appsPanel ?: return
        appsPanel = null
        (panel.parent as? ViewGroup)?.removeView(panel)
    }

    private fun loadApps(): List<AppEntry> {
        val pm = packageManager
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val found = pm.queryIntentActivities(query, 0)
        return found.asSequence()
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName + "/" + it.activityInfo.name }
            .mapNotNull { info ->
                try {
                    AppEntry(
                        info.loadLabel(pm).toString(),
                        info.loadIcon(pm),
                        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setClassName(info.activityInfo.packageName, info.activityInfo.name),
                    )
                } catch (_: Exception) {
                    null
                }
            }
            .sortedBy { it.label.lowercase(locale) }
            .toList()
    }

    // endregion

    private companion object {
        const val REQ_PDF = 40
        const val RECENT = 8
        const val CARD_HEIGHT = 168
    }
}
