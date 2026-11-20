package id.kelasku.papanguru

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException
import java.io.OutputStream
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var store: BoardStore
    private lateinit var board: BoardView
    private lateinit var boardFrame: FrameLayout
    private lateinit var doc: BoardDocument
    private lateinit var images: ImageCache
    private val prefs by lazy { getSharedPreferences("papan", Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private val autosave = Runnable { saveNow() }
    private lateinit var notice: TextView
    private val hideNotice = Runnable {
        notice.animate().alpha(0f).setDuration(200).withEndAction { notice.visibility = View.GONE }.start()
    }

    private val toolButtons = linkedMapOf<Tool, ImageButton>()
    private lateinit var colorButton: ImageButton
    private lateinit var widthButton: ImageButton
    private lateinit var undoButton: ImageButton
    private lateinit var redoButton: ImageButton
    private lateinit var prevButton: ImageButton
    private lateinit var nextButton: ImageButton
    private lateinit var pageLabel: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var boardName: TextView
    private lateinit var selectionBar: LinearLayout
    private lateinit var toolboxButton: ImageButton
    private var captureLayer: View? = null
    private var curtain: CurtainView? = null
    private var spotlight: SpotlightView? = null
    private val cards = mutableMapOf<String, FloatingCard>()

    private var penColor = Color.WHITE
    private var highlighterColor = 0xFFFFE066.toInt()
    private var penWidth = 3f
    private var highlighterWidth = 20f
    private var textSizeDp = 32f

    /** Output for the next ACTION_CREATE_DOCUMENT result, written on a worker thread. */
    private var pendingWrite: ((OutputStream) -> Unit)? = null
    private var busy = false
    /** Set while the open board is being deleted, so autosave doesn't recreate it. */
    private var deletedBoard: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui = Ui(this)
        store = BoardStore(this)
        loadSettings()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BACKGROUND)
            setPadding(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(8))
        }
        root.addView(buildHeader(), LinearLayout.LayoutParams(-1, ui.dp(44)))

        board = BoardView(this).apply {
            background = ui.surface(0xFF121312.toInt(), 0xFF252824.toInt(), 12, 0xFF454843.toInt())
            clipToOutline = true
            palmEraser = prefs.getBoolean("palm", true)
            multiWrite = prefs.getBoolean("multiWrite", false)
        }
        boardFrame = FrameLayout(this).apply { addView(board, FrameLayout.LayoutParams(-1, -1)) }
        selectionBar = buildSelectionBar()
        boardFrame.addView(selectionBar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = ui.dp(10)
        })
        root.addView(boardFrame, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(4) })

        root.addView(buildDockScroller(), LinearLayout.LayoutParams(-2, -2).apply {
            topMargin = ui.dp(8)
            gravity = Gravity.CENTER_HORIZONTAL
        })

        board.onChanged = { content ->
            refresh()
            if (content) scheduleSave()
        }
        board.onToolChanged = { applyTool() }
        board.onTextRequest = { existing, x, y -> showTextDialog(existing, x, y) }
        board.onDoubleTap = { x, y -> showWheel(x, y) }
        board.onInstrumentsChanged = { board.invalidate() }

        // Root container so in-app notices (instead of platform toasts) can float above everything.
        val container = FrameLayout(this)
        container.addView(root, FrameLayout.LayoutParams(-1, -1))
        notice = ui.label("", 15f, Ui.TEXT).apply {
            background = ui.panel(14)
            setPadding(ui.dp(18), ui.dp(12), ui.dp(18), ui.dp(12))
            elevation = ui.dp(14).toFloat()
            gravity = Gravity.CENTER
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        container.addView(notice, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = ui.dp(88)
            leftMargin = ui.dp(24)
            rightMargin = ui.dp(24)
        })
        setContentView(container)
        openDocument(startDocument(savedInstanceState))
        applyTool()
        ui.hideSystemBars()
        // Requests from the home screen run once; a recreated activity keeps the open board instead.
        if (savedInstanceState == null) {
            val pdf = intent.data
            when {
                pdf != null && intent.getBooleanExtra(EXTRA_PDF, false) -> board.post { importPdf(pdf) }
                intent.getBooleanExtra(EXTRA_BOARDS, false) -> board.post { showBoardsDialog() }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        saveNow()
        saveSettings()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) ui.hideSystemBars()
    }

    // region Documents and saving

    private fun startDocument(saved: Bundle?): BoardDocument {
        if (saved == null) {
            if (intent.getBooleanExtra(EXTRA_NEW, false)) {
                return store.create(intent.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() } ?: nextBoardName())
            }
            intent.getStringExtra(EXTRA_BOARD)?.let { id -> store.load(id)?.let { return it } }
        }
        return initialDocument()
    }

    /** "Papan N" with the first N that no board uses yet. */
    private fun nextBoardName(): String {
        val names = store.list().map { it.name }.toSet()
        var n = names.size + 1
        while ("Papan $n" in names) n++
        return "Papan $n"
    }

    private fun initialDocument(): BoardDocument {
        prefs.getString("board", null)?.let { id -> store.load(id)?.let { return it } }
        store.list().firstOrNull()?.let { summary -> store.load(summary.id)?.let { return it } }
        return store.create("Papan 1")
    }

    private fun openDocument(next: BoardDocument) {
        doc = next
        images = ImageCache(store.imagesDir(next.id))
        board.setDocument(next, images)
        prefs.edit().putString("board", next.id).apply()
        boardName.text = next.name
        refresh()
    }

    private fun scheduleSave() {
        handler.removeCallbacks(autosave)
        handler.postDelayed(autosave, 1500)
    }

    private fun saveNow() {
        handler.removeCallbacks(autosave)
        if (!::doc.isInitialized || doc.id == deletedBoard) return
        val thumb = try {
            BoardFiles.renderPage(snapshotOf(doc.pages[0]), frame(), images, resources.displayMetrics.density, 320, cachePaths = true)
        } catch (_: OutOfMemoryError) {
            null
        }
        store.save(BoardSnapshot.of(doc), thumb)
    }

    private fun snapshotOf(page: BoardPage) = PageSnapshot(page.items, page.color, page.pattern)

    /** Board area of the initial view; used to lay out PDF pages and exports. */
    private fun frame(): Box {
        val f = board.viewport.homeFrame()
        return if (f.isEmpty) Box(0f, 0f, 1280f, 720f) else f
    }

    private fun loadSettings() {
        penColor = prefs.getInt("penColor", penColor)
        highlighterColor = prefs.getInt("highlighterColor", highlighterColor)
        penWidth = prefs.getFloat("penWidth", penWidth)
        highlighterWidth = prefs.getFloat("highlighterWidth", highlighterWidth)
        textSizeDp = prefs.getFloat("textSize", textSizeDp)
    }

    private fun saveSettings() {
        prefs.edit()
            .putInt("penColor", penColor)
            .putInt("highlighterColor", highlighterColor)
            .putFloat("penWidth", penWidth)
            .putFloat("highlighterWidth", highlighterWidth)
            .putFloat("textSize", textSizeDp)
            .putBoolean("palm", board.palmEraser)
            .putBoolean("multiWrite", board.multiWrite)
            .apply()
    }

    // endregion

    // region Layout

    private fun buildHeader() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(ui.iconButton("Beranda", R.drawable.ic_home).apply {
            setOnClickListener { goHome() }
        }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        addView(ui.iconButton("Papan saya", R.drawable.ic_folder).apply {
            setOnClickListener { showBoardsDialog() }
        }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        boardName = ui.label("", 16f, Ui.TEXT, bold = true).apply {
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            contentDescription = "Nama papan, ketuk untuk mengganti"
            background = ui.buttonStates(transparentIdle = true)
            setOnClickListener { showRenameDialog(doc.id, doc.name) }
        }
        addView(boardName, LinearLayout.LayoutParams(0, -2, 1f))
        addView(ui.iconButton("Perkecil", R.drawable.ic_zoom_out).apply {
            setOnClickListener { board.zoom(0.8f) }
        }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        zoomLabel = ui.label("100%", 13f, Ui.MUTED).apply {
            gravity = Gravity.CENTER
            contentDescription = "Zoom, ketuk untuk kembali ke tampilan awal"
            background = ui.buttonStates(transparentIdle = true)
            setOnClickListener { board.resetView() }
        }
        addView(zoomLabel, LinearLayout.LayoutParams(ui.dp(64), ui.dp(44)))
        addView(ui.iconButton("Perbesar", R.drawable.ic_zoom_in).apply {
            setOnClickListener { board.zoom(1.25f) }
        }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        addView(ui.iconButton("Tampilkan semua isi", R.drawable.ic_fit).apply {
            setOnClickListener { board.fitContent() }
        }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
        addView(ui.iconButton("Kembali ke tampilan awal", R.drawable.ic_reset_view).apply {
            setOnClickListener { board.resetView() }
        }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)))
    }

    /**
     * The dock scrolls sideways on narrow screens. To make that discoverable it gets faded edges,
     * arrow buttons on the sides that can still scroll, and a one-time "peek" animation with a hint.
     * On screens where the whole dock fits, none of this shows.
     */
    private fun buildDockScroller(): View {
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isFillViewport = false
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(ui.dp(44))
            addView(buildDock())
        }
        fun arrow(label: String, icon: Int, direction: Int) = ui.iconButton(label, icon).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xF02C2E33.toInt())
                setStroke(ui.dp(1), 0xFF8C9DB8.toInt())
            }
            elevation = ui.dp(6).toFloat()
            setPadding(ui.dp(9), ui.dp(9), ui.dp(9), ui.dp(9))
            visibility = View.GONE
            setOnClickListener { scroller.smoothScrollBy(direction * (scroller.width * 0.6f).toInt(), 0) }
        }
        val left = arrow("Geser toolbar ke kiri", R.drawable.ic_chevron_left, -1)
        val right = arrow("Geser toolbar ke kanan", R.drawable.ic_chevron_right, 1)
        val update = {
            left.visibility = if (scroller.canScrollHorizontally(-1)) View.VISIBLE else View.GONE
            right.visibility = if (scroller.canScrollHorizontally(1)) View.VISIBLE else View.GONE
        }
        scroller.setOnScrollChangeListener { _, _, _, _, _ -> update() }
        scroller.viewTreeObserver.addOnGlobalLayoutListener { update() }
        if (!prefs.getBoolean("dockHintShown", false)) {
            scroller.postDelayed({
                if (isDestroyed || !scroller.canScrollHorizontally(1)) return@postDelayed
                prefs.edit().putBoolean("dockHintShown", true).apply()
                toast("Geser toolbar ke samping untuk alat lainnya")
                // One animator for out-and-back: two smoothScrollTo calls in a row cancel each other.
                val peek = minOf(ui.dp(160), scroller.getChildAt(0).width - scroller.width)
                android.animation.ValueAnimator.ofInt(0, peek, peek, 0).apply {
                    duration = 1600
                    interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                    addUpdateListener { scroller.scrollTo(it.animatedValue as Int, 0) }
                    start()
                }
            }, 900)
        }
        return FrameLayout(this).apply {
            addView(scroller, FrameLayout.LayoutParams(-2, -2))
            addView(left, FrameLayout.LayoutParams(ui.dp(36), ui.dp(36), Gravity.START or Gravity.CENTER_VERTICAL).apply {
                marginStart = ui.dp(2)
            })
            addView(right, FrameLayout.LayoutParams(ui.dp(36), ui.dp(36), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = ui.dp(2)
            })
        }
    }

    private fun buildDock() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.dp(6), ui.dp(4), ui.dp(6), ui.dp(4))
        background = ui.surface(0xFF4B4C52.toInt(), 0xFF28292E.toInt(), 18, 0xFF66676D.toInt())
        val size = LinearLayout.LayoutParams(ui.dp(48), ui.dp(48))

        listOf(
            Tool.PEN to ("Pena" to R.drawable.ic_pen),
            Tool.HIGHLIGHTER to ("Stabilo" to R.drawable.ic_highlighter),
            Tool.ERASER to ("Penghapus" to R.drawable.ic_eraser),
            Tool.SHAPE to ("Bentuk" to shapeIcon(board.shapeType)),
            Tool.TEXT to ("Teks" to R.drawable.ic_text),
            Tool.SELECT to ("Pilih dan pindahkan" to R.drawable.ic_select),
            Tool.PAN to ("Geser papan" to R.drawable.ic_hand),
        ).forEach { (tool, info) ->
            val button = ui.iconButton(info.first, info.second).apply {
                setOnClickListener { if (board.tool == tool) showToolOptions(tool, this) else board.tool = tool }
            }
            toolButtons[tool] = button
            addView(button, LinearLayout.LayoutParams(size))
        }
        ui.divider(this)

        colorButton = ui.iconButton("Warna", R.drawable.ic_color).apply {
            setOnClickListener { showPalette(this) { pickColor(it) } }
        }
        addView(colorButton, LinearLayout.LayoutParams(size))
        widthButton = ui.iconButton("Ketebalan", R.drawable.ic_line_medium).apply {
            setOnClickListener { showWidths(this) }
        }
        addView(widthButton, LinearLayout.LayoutParams(size))
        ui.divider(this)

        undoButton = ui.iconButton("Urungkan", R.drawable.ic_undo).apply { setOnClickListener { board.undo() } }
        redoButton = ui.iconButton("Ulangi", R.drawable.ic_redo).apply { setOnClickListener { board.redo() } }
        addView(undoButton, LinearLayout.LayoutParams(size))
        addView(redoButton, LinearLayout.LayoutParams(size))
        ui.divider(this)

        prevButton = ui.iconButton("Halaman sebelumnya", R.drawable.ic_chevron_left).apply { setOnClickListener { goToPage(doc.pageIndex - 1) } }
        addView(prevButton, LinearLayout.LayoutParams(size))
        pageLabel = ui.label("1/1", 14f, Ui.TEXT, bold = true).apply {
            gravity = Gravity.CENTER
            background = ui.buttonStates(transparentIdle = true)
            setOnClickListener { showPagesDialog() }
        }
        addView(pageLabel, LinearLayout.LayoutParams(ui.dp(60), ui.dp(48)))
        nextButton = ui.iconButton("Halaman berikutnya", R.drawable.ic_chevron_right).apply { setOnClickListener { goToPage(doc.pageIndex + 1) } }
        addView(nextButton, LinearLayout.LayoutParams(size))
        addView(ui.iconButton("Tambah halaman", R.drawable.ic_add).apply { setOnClickListener { addPage() } }, LinearLayout.LayoutParams(size))
        ui.divider(this)

        addView(ui.iconButton("Sisipkan gambar atau PDF", R.drawable.ic_insert).apply {
            setOnClickListener { showInsertMenu(this) }
        }, LinearLayout.LayoutParams(size))
        toolboxButton = ui.iconButton("Alat kelas", R.drawable.ic_toolbox).apply {
            setOnClickListener { showClassroomMenu(this) }
        }
        addView(toolboxButton, LinearLayout.LayoutParams(size))
        addView(ui.iconButton("Menu lainnya", R.drawable.ic_more).apply {
            setOnClickListener { showMoreMenu(this) }
        }, LinearLayout.LayoutParams(size))
    }

    private fun buildSelectionBar() = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(2))
        background = ui.panel()
        elevation = ui.dp(8).toFloat()
        visibility = View.GONE
        val size = LinearLayout.LayoutParams(ui.dp(46), ui.dp(46))
        addView(ui.iconButton("Ganti warna objek", R.drawable.ic_color).apply {
            setOnClickListener { showPalette(this, below = true) { board.recolorSelection(it) } }
        }, size)
        addView(ui.iconButton("Bawa ke depan", R.drawable.ic_pages).apply { setOnClickListener { board.bringSelectionToFront() } }, LinearLayout.LayoutParams(size))
        addView(ui.iconButton("Duplikat", R.drawable.ic_copy).apply { setOnClickListener { board.duplicateSelection() } }, LinearLayout.LayoutParams(size))
        addView(ui.iconButton("Hapus objek", R.drawable.ic_trash).apply { setOnClickListener { board.deleteSelection() } }, LinearLayout.LayoutParams(size))
        addView(ui.iconButton("Selesai memilih", R.drawable.ic_check).apply { setOnClickListener { board.clearSelection() } }, LinearLayout.LayoutParams(size))
    }

    private fun refresh() {
        if (!::doc.isInitialized) return
        ui.setEnabled(undoButton, board.canUndo)
        ui.setEnabled(redoButton, board.canRedo)
        ui.setEnabled(prevButton, doc.pageIndex > 0)
        ui.setEnabled(nextButton, doc.pageIndex < doc.pages.lastIndex)
        pageLabel.text = "${doc.pageIndex + 1}/${doc.pages.size}"
        pageLabel.contentDescription = "Halaman ${doc.pageIndex + 1} dari ${doc.pages.size}, ketuk untuk daftar halaman"
        val zoom = board.zoomPercent
        zoomLabel.text = if (zoom < 10f) String.format(Locale.US, "%.1f%%", zoom) else "${Math.round(zoom)}%"
        selectionBar.visibility = if (board.hasSelection) View.VISIBLE else View.GONE
        boardName.text = doc.name
    }

    // endregion

    // region Tools

    private fun applyTool() {
        val tool = board.tool
        toolButtons.forEach { (t, b) -> b.isSelected = t == tool }
        toolboxButton.isSelected = tool == Tool.COMPASS
        board.inkColor = if (tool == Tool.HIGHLIGHTER) highlighterColor else penColor
        board.inkWidth = ui.dp(if (tool == Tool.HIGHLIGHTER) highlighterWidth else penWidth).toFloat().coerceAtLeast(1f)
        board.textSize = ui.dp(textSizeDp).toFloat()
        colorButton.setColorFilter(board.inkColor)
        val usesColor = tool != Tool.ERASER && tool != Tool.PAN && tool != Tool.SELECT
        ui.setEnabled(colorButton, usesColor)
        ui.setEnabled(widthButton, usesColor)
        widthButton.setImageResource(if (tool == Tool.TEXT) R.drawable.ic_text else widthIcon())
        toolButtons[Tool.SHAPE]?.setImageResource(shapeIcon(board.shapeType))
        refresh()
    }

    private fun widthIcon(): Int {
        val w = if (board.tool == Tool.HIGHLIGHTER) highlighterWidth / 4 else penWidth
        return when {
            w <= 3f -> R.drawable.ic_line_thin
            w <= 8f -> R.drawable.ic_line_medium
            else -> R.drawable.ic_line_thick
        }
    }

    private fun shapeIcon(type: ShapeType) = when (type) {
        ShapeType.LINE -> R.drawable.ic_shape_line
        ShapeType.ARROW -> R.drawable.ic_shape_arrow
        ShapeType.RECT -> R.drawable.ic_shape_rect
        ShapeType.ELLIPSE -> R.drawable.ic_shape_ellipse
        ShapeType.TRIANGLE -> R.drawable.ic_shape_triangle
    }

    private fun pickColor(color: Int) {
        if (board.tool == Tool.HIGHLIGHTER) highlighterColor = color else penColor = color
        applyTool()
    }

    private fun showToolOptions(tool: Tool, anchor: View) {
        when (tool) {
            Tool.PEN, Tool.HIGHLIGHTER, Tool.TEXT -> showPalette(anchor) { pickColor(it) }
            Tool.SHAPE -> showShapes(anchor)
            Tool.ERASER -> ui.menu(anchor, listOf(
                Ui.MenuEntry("Hapus sebagian goresan", R.drawable.ic_eraser, board.eraserMode == EraserMode.PARTIAL) {
                    board.eraserMode = EraserMode.PARTIAL
                },
                Ui.MenuEntry("Hapus objek utuh", R.drawable.ic_trash, board.eraserMode == EraserMode.WHOLE) {
                    board.eraserMode = EraserMode.WHOLE
                },
                Ui.MenuEntry("Hapus dengan telapak tangan", R.drawable.ic_hand, board.palmEraser) {
                    board.palmEraser = !board.palmEraser
                    saveSettings()
                },
                Ui.MenuEntry("Kosongkan halaman", R.drawable.ic_trash, enabled = board.hasContent) { confirmClear() },
            ))
            Tool.SELECT -> ui.menu(anchor, listOf(
                Ui.MenuEntry("Pilih semua", R.drawable.ic_select, enabled = board.hasContent) { board.selectAll() },
            ))
            Tool.COMPASS -> showPalette(anchor) { pickColor(it) }
            Tool.PAN -> ui.menu(anchor, listOf(
                Ui.MenuEntry("Tampilkan semua isi", R.drawable.ic_fit) { board.fitContent() },
                Ui.MenuEntry("Kembali ke tampilan awal", R.drawable.ic_reset_view) { board.resetView() },
            ))
        }
    }

    private fun showShapes(anchor: View) {
        val row = LinearLayout(this).apply { setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)) }
        val popup = ui.popup(row)
        listOf(
            ShapeType.LINE to "Garis", ShapeType.ARROW to "Panah", ShapeType.RECT to "Persegi",
            ShapeType.ELLIPSE to "Lingkaran", ShapeType.TRIANGLE to "Segitiga",
        ).forEach { (type, label) ->
            row.addView(ui.iconButton(label, shapeIcon(type)).apply {
                isSelected = board.shapeType == type
                setOnClickListener {
                    popup.dismiss()
                    board.shapeType = type
                    board.tool = Tool.SHAPE
                    applyTool()
                }
            }, LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)))
        }
        ui.showAbove(popup, anchor)
    }

    private fun showPalette(anchor: View, below: Boolean = false, onPick: (Int) -> Unit) {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8))
        }
        val popup = ui.popup(grid)
        PALETTE.chunked(5).forEach { rowColors ->
            val row = LinearLayout(this)
            rowColors.forEach { (label, color) ->
                row.addView(ImageButton(this).apply {
                    contentDescription = label
                    if (Build.VERSION.SDK_INT >= 26) tooltipText = label
                    setImageDrawable(GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(color)
                        setStroke(ui.dp(2), if (color == board.inkColor) Ui.ACCENT else 0xFF77797F.toInt())
                        setSize(ui.dp(30), ui.dp(30))
                    })
                    background = ui.buttonStates(transparentIdle = true)
                    isSelected = color == board.inkColor
                    setOnClickListener {
                        popup.dismiss()
                        onPick(color)
                    }
                }, LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)))
            }
            grid.addView(row)
        }
        if (below) {
            popup.showAsDropDown(anchor, 0, ui.dp(6))
        } else {
            ui.showAbove(popup, anchor)
        }
    }

    private fun showWidths(anchor: View) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
        }
        val popup = ui.popup(row)
        val tool = board.tool
        if (tool == Tool.TEXT) {
            TEXT_SIZES.forEach { (label, size) ->
                row.addView(ui.textButton("Aa").apply {
                    textSize = size / 2.2f
                    contentDescription = "Teks $label"
                    isSelected = size == textSizeDp
                    background = ui.buttonStates(transparentIdle = true)
                    setOnClickListener {
                        popup.dismiss()
                        textSizeDp = size
                        applyTool()
                    }
                }, LinearLayout.LayoutParams(-2, ui.dp(56)))
            }
        } else {
            val widths = if (tool == Tool.HIGHLIGHTER) HIGHLIGHTER_WIDTHS else PEN_WIDTHS
            val current = if (tool == Tool.HIGHLIGHTER) highlighterWidth else penWidth
            widths.forEach { w ->
                row.addView(ImageButton(this).apply {
                    contentDescription = "Ketebalan ${w.toInt()}"
                    val dot = ui.dp(w.coerceAtMost(30f)).coerceAtLeast(ui.dp(2))
                    setImageDrawable(GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(board.inkColor)
                        setStroke(ui.dp(1), 0xFF77797F.toInt())
                        setSize(dot, dot)
                    })
                    background = ui.buttonStates(transparentIdle = true)
                    isSelected = w == current
                    setOnClickListener {
                        popup.dismiss()
                        if (tool == Tool.HIGHLIGHTER) highlighterWidth = w else penWidth = w
                        applyTool()
                    }
                }, LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)))
            }
        }
        ui.showAbove(popup, anchor)
    }

    private fun confirmClear() {
        confirm("Kosongkan halaman", "Hapus semua isi halaman ini? Anda masih bisa mengurungkannya.", "Kosongkan") {
            board.clearPage()
        }
    }

    // endregion

    // region Pages

    private fun goToPage(index: Int) {
        if (index !in doc.pages.indices || index == doc.pageIndex) return
        doc.pageIndex = index
        board.pageChanged()
        scheduleSave()
    }

    private fun addPage(after: Int = doc.pageIndex) {
        val current = doc.page
        doc.pages.add(after + 1, BoardPage(color = current.color, pattern = current.pattern))
        doc.pageIndex = after + 1
        board.pageChanged()
        scheduleSave()
    }

    private fun showPagesDialog() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dialog = darkDialog("Halaman", content, widthDp = 760)
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(ScrollView(this).apply { addView(grid) }, LinearLayout.LayoutParams(-1, (window.decorView.height * 0.5f).toInt()))
        val density = resources.displayMetrics.density
        doc.pages.chunked(4).forEachIndexed { rowIndex, pages ->
            val row = LinearLayout(this)
            pages.forEachIndexed { col, page ->
                val index = rowIndex * 4 + col
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
                    background = ui.buttonStates(transparentIdle = true)
                    isSelected = index == doc.pageIndex
                    contentDescription = "Halaman ${index + 1}"
                    setOnClickListener {
                        dialog.dismiss()
                        goToPage(index)
                    }
                }
                val thumb = try {
                    BoardFiles.renderPage(snapshotOf(page), frame(), images, density, ui.dp(160), cachePaths = true)
                } catch (_: OutOfMemoryError) {
                    null
                }
                cell.addView(ImageView(this).apply {
                    setImageBitmap(thumb)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = ui.surface(0xFF111211.toInt(), 0xFF191C18.toInt(), 6, if (index == doc.pageIndex) Ui.ACCENT else 0xFF474A45.toInt())
                }, LinearLayout.LayoutParams(ui.dp(160), ui.dp(96)))
                cell.addView(ui.label("${index + 1}", 13f, Ui.MUTED).apply { setPadding(0, ui.dp(4), 0, 0) })
                row.addView(cell)
            }
            grid.addView(row)
        }
        val actions = LinearLayout(this).apply { gravity = Gravity.END }
        fun action(label: String, primary: Boolean = false, run: () -> Unit) {
            actions.addView(ui.textButton(label, primary).apply {
                setOnClickListener {
                    dialog.dismiss()
                    run()
                }
            }, LinearLayout.LayoutParams(-2, ui.dp(44)).apply { marginStart = ui.dp(8) })
        }
        action("Geser ke kiri") { movePage(-1) }
        action("Geser ke kanan") { movePage(1) }
        action("Duplikat") {
            doc.pages.add(doc.pageIndex + 1, doc.page.copy())
            doc.pageIndex++
            board.pageChanged()
            scheduleSave()
        }
        action("Hapus") { confirmDeletePage() }
        action("Tambah halaman", primary = true) { addPage() }
        content.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        dialog.show()
        fitDialog(dialog, 760)
    }

    private fun movePage(delta: Int) {
        val from = doc.pageIndex
        val to = from + delta
        if (to !in doc.pages.indices) return
        val page = doc.pages.removeAt(from)
        doc.pages.add(to, page)
        doc.pageIndex = to
        board.pageChanged()
        scheduleSave()
    }

    private fun confirmDeletePage() {
        val remove = {
            if (doc.pages.size == 1) {
                doc.pages[0] = BoardPage(color = doc.page.color, pattern = doc.page.pattern)
            } else {
                doc.pages.removeAt(doc.pageIndex)
                doc.pageIndex = doc.pageIndex.coerceAtMost(doc.pages.lastIndex)
            }
            board.pageChanged()
            scheduleSave()
        }
        if (doc.page.items.isEmpty()) return remove()
        confirm("Hapus halaman", "Hapus halaman ${doc.pageIndex + 1} beserta isinya? Ini tidak bisa diurungkan.", "Hapus halaman") {
            remove()
        }
    }

    // endregion

    /** Back to the home screen; it is singleTask in this task, so this board activity finishes. */
    private fun goHome() {
        saveNow()
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    // region Boards

    private fun showBoardsDialog() {
        saveNow()
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dialog = darkDialog("Papan saya", content, widthDp = 640)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, (window.decorView.height * 0.55f).toInt()))
        val dates = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale("id", "ID"))
        // Saves run on the store's worker; list once they've landed so the current board is up to date.
        store.afterPendingWrites {
            val boards = store.list()
            runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                for (summary in boards) {
                    val row = LinearLayout(this).apply {
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(ui.dp(8), ui.dp(6), ui.dp(4), ui.dp(6))
                        background = ui.buttonStates(transparentIdle = true)
                        isSelected = summary.id == doc.id
                        contentDescription = "Buka ${summary.name}"
                        setOnClickListener {
                            dialog.dismiss()
                            if (summary.id != doc.id) store.load(summary.id)?.let(::openDocument)
                        }
                    }
                    row.addView(ImageView(this).apply {
                        setImageBitmap(BitmapFactory.decodeFile(summary.thumbnail.path))
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        background = ui.surface(0xFF111211.toInt(), 0xFF191C18.toInt(), 6, 0xFF474A45.toInt())
                    }, LinearLayout.LayoutParams(ui.dp(120), ui.dp(68)))
                    val text = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(ui.dp(12), 0, ui.dp(8), 0)
                        addView(ui.label(summary.name, 16f, Ui.TEXT, bold = true))
                        addView(ui.label("${summary.pages} halaman · ${dates.format(Date(summary.modified))}", 13f, Ui.MUTED))
                    }
                    row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(ui.iconButton("Ganti nama ${summary.name}", R.drawable.ic_text).apply {
                        setOnClickListener {
                            dialog.dismiss()
                            showRenameDialog(summary.id, summary.name)
                        }
                    }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
                    row.addView(ui.iconButton("Hapus ${summary.name}", R.drawable.ic_trash).apply {
                        setOnClickListener {
                            dialog.dismiss()
                            confirmDeleteBoard(summary)
                        }
                    }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
                    list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(4) })
                }
            }
        }
        val actions = LinearLayout(this).apply { gravity = Gravity.END }
        actions.addView(ui.textButton("Tutup").apply { setOnClickListener { dialog.dismiss() } }, LinearLayout.LayoutParams(-2, ui.dp(44)))
        actions.addView(ui.textButton("Papan baru", primary = true).apply {
            setOnClickListener {
                dialog.dismiss()
                saveNow()
                openDocument(store.create(nextBoardName()))
            }
        }, LinearLayout.LayoutParams(-2, ui.dp(44)).apply { marginStart = ui.dp(8) })
        content.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        dialog.show()
        fitDialog(dialog, 640)
    }

    private fun showRenameDialog(id: String, current: String) {
        val input = ui.input(current, "Nama papan", capWords = true).apply { selectAll() }
        actionDialog("Nama papan", body = input, actions = listOf(
            Action("Batal"),
            Action("Simpan", primary = true) {
                val name = input.text.toString().trim().ifEmpty { current }
                if (id == doc.id) {
                    doc.name = name
                    refresh()
                    saveNow()
                } else {
                    store.rename(id, name)
                }
            },
        ), focus = input)
    }

    private fun confirmDeleteBoard(summary: BoardSummary) {
        confirm("Hapus papan", "Hapus papan \"${summary.name}\" beserta semua halamannya? Ini tidak bisa diurungkan.", "Hapus papan") {
            store.delete(summary.id)
            if (summary.id == doc.id) {
                handler.removeCallbacks(autosave)
                deletedBoard = summary.id
                store.afterPendingWrites {
                    val next = store.list().firstOrNull()?.let { store.load(it.id) } ?: store.create("Papan 1")
                    runOnUiThread { if (!isDestroyed) openDocument(next) }
                }
            }
        }
    }

    // endregion

    // region Text, background, menus

    private fun showTextDialog(existing: TextItem?, x: Float, y: Float) {
        val input = ui.input(existing?.text ?: "", "Ketik teks", lines = 3)
        val actions = mutableListOf<Action>()
        if (existing != null) actions += Action("Hapus", danger = true) { board.placeText("", x, y, existing) }
        actions += Action("Batal")
        actions += Action("Simpan", primary = true) { board.placeText(input.text.toString(), x, y, existing) }
        actionDialog(if (existing == null) "Tambah teks" else "Ubah teks", body = input, actions = actions, widthDp = 560, focus = input)
    }

    private fun showBackgroundDialog() {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dialog = darkDialog("Latar papan", content, widthDp = 520)
        val page = doc.page
        var color = page.color
        var pattern = page.pattern
        val colorRow = LinearLayout(this)
        val patternRow = LinearLayout(this)
        fun rebuild() {
            colorRow.removeAllViews()
            patternRow.removeAllViews()
            listOf(BoardColor.BLACKBOARD to "Hitam", BoardColor.GREEN to "Hijau", BoardColor.WHITE to "Putih").forEach { (c, label) ->
                colorRow.addView(ui.textButton(label).apply {
                    isSelected = c == color
                    setCompoundDrawablesRelativeWithIntrinsicBounds(GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(c.argb)
                        setStroke(ui.dp(1), 0xFF9195A0.toInt())
                        setSize(ui.dp(18), ui.dp(18))
                    }, null, null, null)
                    compoundDrawablePadding = ui.dp(8)
                    setOnClickListener {
                        color = c
                        board.setBackground(color, pattern)
                        rebuild()
                    }
                }, LinearLayout.LayoutParams(-2, ui.dp(44)).apply { marginEnd = ui.dp(8) })
            }
            listOf(BoardPattern.NONE to "Polos", BoardPattern.DOTS to "Titik", BoardPattern.GRID to "Kotak", BoardPattern.LINES to "Garis").forEach { (p, label) ->
                patternRow.addView(ui.textButton(label).apply {
                    isSelected = p == pattern
                    setOnClickListener {
                        pattern = p
                        board.setBackground(color, pattern)
                        rebuild()
                    }
                }, LinearLayout.LayoutParams(-2, ui.dp(44)).apply { marginEnd = ui.dp(8) })
            }
        }
        rebuild()
        content.addView(ui.label("Warna", 13f, Ui.MUTED).apply { setPadding(0, 0, 0, ui.dp(6)) })
        content.addView(colorRow)
        content.addView(ui.label("Pola", 13f, Ui.MUTED).apply { setPadding(0, ui.dp(14), 0, ui.dp(6)) })
        content.addView(patternRow)
        val actions = LinearLayout(this).apply { gravity = Gravity.END }
        actions.addView(ui.textButton("Terapkan ke semua halaman").apply {
            setOnClickListener {
                doc.pages.forEach {
                    it.color = color
                    it.pattern = pattern
                }
                board.setBackground(color, pattern)
                dialog.dismiss()
            }
        }, LinearLayout.LayoutParams(-2, ui.dp(44)))
        actions.addView(ui.textButton("Selesai", primary = true).apply { setOnClickListener { dialog.dismiss() } },
            LinearLayout.LayoutParams(-2, ui.dp(44)).apply { marginStart = ui.dp(8) })
        content.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(18) })
        dialog.show()
        fitDialog(dialog, 520)
    }

    private fun showInsertMenu(anchor: View) {
        ui.menu(anchor, listOf(
            Ui.MenuEntry("Gambar dari perangkat", R.drawable.ic_image) { pick(REQ_IMAGE, "image/*") },
            Ui.MenuEntry("PDF sebagai halaman", R.drawable.ic_pdf) { pick(REQ_PDF, "application/pdf") },
            Ui.MenuEntry("Halaman kosong", R.drawable.ic_add) { addPage() },
        ))
    }

    private fun showMoreMenu(anchor: View) {
        ui.menu(anchor, listOf(
            Ui.MenuEntry("Latar papan", R.drawable.ic_background) { showBackgroundDialog() },
            Ui.MenuEntry("Tangkap area", R.drawable.ic_capture) { selectCapture() },
            Ui.MenuEntry("Ekspor semua halaman ke PDF", R.drawable.ic_pdf) { exportPdf() },
            Ui.MenuEntry("Ekspor halaman ini ke PNG", R.drawable.ic_export) { exportPng() },
            Ui.MenuEntry("Halaman", R.drawable.ic_pages) { showPagesDialog() },
            Ui.MenuEntry("Papan saya", R.drawable.ic_folder) { showBoardsDialog() },
            Ui.MenuEntry("Kosongkan halaman", R.drawable.ic_trash, enabled = board.hasContent) { confirmClear() },
        ))
    }

    /**
     * Radial quick menu opened by a one-finger double tap: four tools and four actions on a ring
     * around the touch point, with the current color in the middle. Tap outside to close.
     */
    private fun showWheel(x: Float, y: Float) {
        val ring = ui.dp(80)
        val button = ui.dp(50)
        val diameter = 2 * ring + button + ui.dp(16)
        val wheel = FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xF0494B55.toInt(), 0xF0242629.toInt())).apply {
                shape = GradientDrawable.OVAL
                setStroke(ui.dp(1), 0xFF9195A0.toInt())
            }
            elevation = ui.dp(12).toFloat()
            contentDescription = "Menu cepat"
        }
        val popup = PopupWindow(wheel, diameter, diameter, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = ui.dp(12).toFloat()
            setOnDismissListener { ui.hideSystemBars() }
        }
        class Entry(val label: String, val icon: Int, val selected: Boolean = false, val enabled: Boolean = true, val action: () -> Unit)
        val tool = board.tool
        // Clockwise from the top: tools on the upper half, actions on the lower half.
        val entries = listOf(
            Entry("Pena", R.drawable.ic_pen, tool == Tool.PEN) { board.tool = Tool.PEN },
            Entry("Stabilo", R.drawable.ic_highlighter, tool == Tool.HIGHLIGHTER) { board.tool = Tool.HIGHLIGHTER },
            Entry("Penghapus", R.drawable.ic_eraser, tool == Tool.ERASER) { board.tool = Tool.ERASER },
            Entry("Ulangi", R.drawable.ic_redo, enabled = board.canRedo) { board.redo() },
            Entry("Tampilkan semua isi", R.drawable.ic_fit) { board.fitContent() },
            Entry("Tangkap area", R.drawable.ic_capture) { selectCapture() },
            Entry("Urungkan", R.drawable.ic_undo, enabled = board.canUndo) { board.undo() },
            Entry("Pilih dan pindahkan", R.drawable.ic_select, tool == Tool.SELECT) { board.tool = Tool.SELECT },
        )
        val center = diameter / 2
        entries.forEachIndexed { i, entry ->
            val angle = -Math.PI / 2 + i * 2 * Math.PI / entries.size
            val cx = center + (ring * Math.cos(angle)).toInt()
            val cy = center + (ring * Math.sin(angle)).toInt()
            wheel.addView(ui.iconButton(entry.label, entry.icon).apply {
                isSelected = entry.selected
                ui.setEnabled(this, entry.enabled)
                setPadding(ui.dp(13), ui.dp(13), ui.dp(13), ui.dp(13))
                setOnClickListener {
                    popup.dismiss()
                    entry.action()
                }
            }, FrameLayout.LayoutParams(button, button, Gravity.TOP or Gravity.START).apply {
                leftMargin = cx - button / 2
                topMargin = cy - button / 2
            })
        }
        // Center: the ink color of the pen (or highlighter); tap to change it without leaving the board.
        val inkColor = if (tool == Tool.HIGHLIGHTER) highlighterColor else penColor
        wheel.addView(ImageButton(this).apply {
            contentDescription = "Ganti warna"
            if (Build.VERSION.SDK_INT >= 26) tooltipText = "Ganti warna"
            setImageDrawable(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(inkColor)
                setStroke(ui.dp(2), 0xFFEAF2FA.toInt())
                setSize(ui.dp(34), ui.dp(34))
            })
            background = ui.buttonStates(transparentIdle = true)
            setOnClickListener { anchor ->
                showPalette(anchor) { color ->
                    popup.dismiss()
                    val usesInk = board.tool == Tool.PEN || board.tool == Tool.HIGHLIGHTER || board.tool == Tool.SHAPE ||
                        board.tool == Tool.TEXT || board.tool == Tool.COMPASS
                    if (!usesInk) board.tool = Tool.PEN
                    pickColor(color)
                }
            }
        }, FrameLayout.LayoutParams(ui.dp(56), ui.dp(56), Gravity.CENTER))

        val location = IntArray(2)
        board.getLocationInWindow(location)
        val left = location[0] + (x.toInt() - diameter / 2).coerceIn(0, (board.width - diameter).coerceAtLeast(0))
        val top = location[1] + (y.toInt() - diameter / 2).coerceIn(0, (board.height - diameter).coerceAtLeast(0))
        // Grow out of the tap point.
        wheel.alpha = 0f
        wheel.scaleX = 0.7f
        wheel.scaleY = 0.7f
        wheel.pivotX = (x - (left - location[0])).coerceIn(0f, diameter.toFloat())
        wheel.pivotY = (y - (top - location[1])).coerceIn(0f, diameter.toFloat())
        popup.showAtLocation(board, Gravity.TOP or Gravity.START, left, top)
        wheel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start()
    }

    /** A dark rounded dialog with a title, matching the toolbar look. */
    private fun darkDialog(title: String, content: LinearLayout, widthDp: Int): Dialog {
        val dialog = Dialog(this).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            setCanceledOnTouchOutside(true)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(18), ui.dp(14), ui.dp(18), ui.dp(14))
            background = ui.surface(0xFF45474F.toInt(), 0xFF222428.toInt(), 20, 0xFF71757E.toInt())
        }
        panel.addView(ui.label(title, 18f, Ui.TEXT, bold = true).apply {
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            setPadding(0, 0, 0, ui.dp(12))
        })
        panel.addView(content, LinearLayout.LayoutParams(-1, -2))
        dialog.setContentView(panel)
        dialog.setOnDismissListener { ui.hideSystemBars() }
        return dialog
    }

    private class Action(val label: String, val primary: Boolean = false, val danger: Boolean = false, val run: () -> Unit = {})

    /**
     * Themed replacement for the platform AlertDialog: title, optional message and body view,
     * and a right-aligned row of buttons that dismiss the dialog before running their action.
     */
    private fun actionDialog(
        title: String, message: String? = null, body: View? = null, actions: List<Action>,
        widthDp: Int = 480, focus: View? = null,
    ): Dialog {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dialog = darkDialog(title, content, widthDp)
        message?.let { content.addView(ui.label(it, 15f, Ui.TEXT).apply { setLineSpacing(0f, 1.2f) }) }
        body?.let {
            content.addView(it, LinearLayout.LayoutParams(-1, -2).apply { if (message != null) topMargin = ui.dp(12) })
        }
        val row = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        for (action in actions) {
            row.addView(ui.textButton(action.label, action.primary, action.danger).apply {
                setOnClickListener {
                    dialog.dismiss()
                    action.run()
                }
            }, LinearLayout.LayoutParams(-2, ui.dp(44)).apply { marginStart = ui.dp(8) })
        }
        content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(18) })
        if (focus != null) {
            dialog.window?.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        }
        dialog.show()
        fitDialog(dialog, widthDp)
        focus?.requestFocus()
        return dialog
    }

    private fun confirm(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit) {
        actionDialog(title, message = message, actions = listOf(Action("Batal"), Action(confirmLabel, danger = true, run = onConfirm)))
    }

    private fun fitDialog(dialog: Dialog, widthDp: Int) {
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.62f)
            setLayout(minOf(ui.dp(widthDp), this@MainActivity.window.decorView.width - ui.dp(32)), -2)
            ui.hideSystemBars(this)
        }
    }

    // endregion

    // region Classroom tools

    private fun showClassroomMenu(anchor: View) {
        fun instrument(label: String, icon: Int, type: InstrumentType) =
            Ui.MenuEntry(label, icon, board.hasInstrument(type)) { board.toggleInstrument(type) }
        ui.menu(anchor, listOf(
            instrument("Penggaris", R.drawable.ic_ruler, InstrumentType.RULER),
            instrument("Segitiga siku", R.drawable.ic_set_square, InstrumentType.SET_SQUARE),
            instrument("Busur derajat", R.drawable.ic_protractor, InstrumentType.PROTRACTOR),
            Ui.MenuEntry("Jangka", R.drawable.ic_compass, board.tool == Tool.COMPASS) {
                board.tool = if (board.tool == Tool.COMPASS) Tool.PEN else Tool.COMPASS
            },
            Ui.MenuEntry("Timer dan stopwatch", R.drawable.ic_timer, "timer" in cards) {
                toggleCard("timer") { TimerCard(this, ui, ::removeCard) }
            },
            Ui.MenuEntry("Pengundi nama", R.drawable.ic_people, "picker" in cards) {
                toggleCard("picker") { NamePickerCard(this, ui, prefs, ::showNamesDialog, ::removeCard) }
            },
            Ui.MenuEntry("Dadu", R.drawable.ic_dice, "dice" in cards) { toggleCard("dice") { DiceCard(this, ui, ::removeCard) } },
            Ui.MenuEntry("Kalkulator", R.drawable.ic_calculator, "calc" in cards) {
                toggleCard("calc") { CalculatorCard(this, ui, ::removeCard) }
            },
            Ui.MenuEntry("Tirai", R.drawable.ic_curtain, curtain != null) { toggleCurtain() },
            Ui.MenuEntry("Lampu sorot", R.drawable.ic_spotlight, spotlight != null) { toggleSpotlight() },
            Ui.MenuEntry("Tulis bersama (banyak jari)", R.drawable.ic_multi, board.multiWrite) {
                board.multiWrite = !board.multiWrite
                saveSettings()
                toast(if (board.multiWrite) "Setiap jari menulis. Zoom dengan alat geser." else "Dua jari kembali untuk zoom dan geser")
            },
        ))
    }

    private fun toggleCard(key: String, create: () -> FloatingCard) {
        cards[key]?.let {
            it.close()
            return
        }
        val card = create()
        card.tag = key
        cards[key] = card
        val offset = ui.dp(24 + 36 * (cards.size - 1))
        boardFrame.addView(card, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = offset
            marginEnd = offset
        })
    }

    private fun removeCard(card: FloatingCard) {
        boardFrame.removeView(card)
        cards.values.remove(card)
    }

    /** Overlays sit right above the board, below cards and the selection bar. */
    private fun addOverlay(view: View) {
        boardFrame.addView(view, 1, FrameLayout.LayoutParams(-1, -1))
    }

    private fun toggleCurtain() {
        curtain?.let {
            boardFrame.removeView(it)
            curtain = null
            return
        }
        curtain = CurtainView(this, ui) { toggleCurtain() }.also(::addOverlay)
    }

    private fun toggleSpotlight() {
        spotlight?.let {
            boardFrame.removeView(it)
            spotlight = null
            return
        }
        spotlight = SpotlightView(this, ui) { toggleSpotlight() }.also(::addOverlay)
    }

    private fun showNamesDialog(current: String, save: (String) -> Unit) {
        val input = ui.input(current, "Satu nama per baris", lines = 6, capWords = true)
        actionDialog("Daftar nama siswa", body = input, actions = listOf(
            Action("Batal"),
            Action("Simpan", primary = true) { save(input.text.toString()) },
        ), widthDp = 520, focus = input)
    }

    // endregion

    // region Import and export

    private fun pick(request: Int, type: String) {
        if (busy) return
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                this.type = type
            }, request)
        } catch (_: android.content.ActivityNotFoundException) {
            toast("Pemilih berkas tidak tersedia")
        }
    }

    private fun createFile(name: String, type: String, write: (OutputStream) -> Unit) {
        if (pendingWrite != null || busy) return
        pendingWrite = write
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                this.type = type
                putExtra(Intent.EXTRA_TITLE, name)
            }, REQ_SAVE)
        } catch (_: android.content.ActivityNotFoundException) {
            pendingWrite = null
            toast("Pemilih lokasi simpan tidak tersedia")
        }
    }

    @Deprecated("Uses the platform document picker result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        ui.hideSystemBars()
        val uri = data?.data
        when (requestCode) {
            REQ_SAVE -> {
                val write = pendingWrite ?: return
                pendingWrite = null
                if (resultCode == RESULT_OK && uri != null) writeTo(uri, write)
            }
            REQ_IMAGE -> if (resultCode == RESULT_OK && uri != null) importImage(uri)
            REQ_PDF -> if (resultCode == RESULT_OK && uri != null) importPdf(uri)
        }
    }

    private fun writeTo(uri: Uri, write: (OutputStream) -> Unit) {
        toast("Menyimpan…")
        Thread {
            val saved = try {
                val out = contentResolver.openOutputStream(uri) ?: throw IOException()
                out.use(write)
                true
            } catch (_: IOException) {
                false
            } catch (_: SecurityException) {
                false
            } catch (_: OutOfMemoryError) {
                false
            }
            runOnUiThread { toast(if (saved) "Berkas tersimpan" else "Berkas gagal disimpan") }
        }.start()
    }

    private fun exportPdf() {
        saveNow()
        val pages = doc.pages.map(::snapshotOf)
        val frame = frame()
        val cache = images
        val density = resources.displayMetrics.density
        createFile("${fileBase()}.pdf", "application/pdf") { out ->
            BoardFiles.exportPdf(pages, frame, cache, density, out)
        }
    }

    private fun exportPng() {
        val page = snapshotOf(doc.page)
        val frame = frame()
        val cache = images
        val density = resources.displayMetrics.density
        createFile("${fileBase()}-hal${doc.pageIndex + 1}.png", "image/png") { out ->
            val bitmap = BoardFiles.renderPage(page, frame, cache, density, 2400)
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException()
            bitmap.recycle()
        }
    }

    private fun fileBase() = "Papan-Guru-" + doc.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(' ', '-').ifEmpty { "papan" }

    private fun importImage(uri: Uri) {
        val target = doc
        busy = true
        toast("Memuat gambar…")
        Thread {
            val stored = try {
                BoardFiles.importImage(this, uri, store.imagesDir(target.id))
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
            runOnUiThread {
                busy = false
                if (isDestroyed) return@runOnUiThread
                when {
                    stored == null -> toast("Gambar tidak bisa dibuka")
                    target !== doc -> Unit
                    else -> {
                        images.put(stored.file, stored.bitmap)
                        board.insertImage(stored.file, stored.width, stored.height)
                    }
                }
            }
        }.start()
    }

    private fun importPdf(uri: Uri) {
        val target = doc
        val frame = frame()
        val progress = ui.label("Membuka PDF…", 15f, Ui.TEXT)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(progress)
        }
        val dialog = darkDialog("Mengimpor PDF", content, widthDp = 420).apply {
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }
        dialog.show()
        fitDialog(dialog, 420)
        busy = true
        Thread {
            val pages = try {
                BoardFiles.importPdf(this, uri, store.imagesDir(target.id), frame, target::newId) { done, total ->
                    runOnUiThread { progress.text = "Halaman ${minOf(done + 1, total)} dari $total" }
                }
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
            runOnUiThread {
                busy = false
                if (isDestroyed) return@runOnUiThread
                dialog.dismiss()
                if (pages.isNullOrEmpty()) {
                    toast("PDF tidak bisa dibuka")
                    return@runOnUiThread
                }
                if (target !== doc) return@runOnUiThread
                // Replace an empty current page instead of leaving a blank page before the PDF.
                val at = if (doc.page.items.isEmpty()) {
                    doc.pages.removeAt(doc.pageIndex)
                    doc.pageIndex
                } else {
                    doc.pageIndex + 1
                }
                doc.pages.addAll(at, pages)
                doc.pageIndex = at
                board.pageChanged()
                saveNow()
                toast(if (pages.size == BoardFiles.MAX_PDF_PAGES) "${pages.size} halaman pertama diimpor" else "${pages.size} halaman diimpor")
            }
        }.start()
    }

    /** In-app notice styled like the rest of the UI (replaces platform toasts). */
    private fun toast(text: String) {
        handler.removeCallbacks(hideNotice)
        notice.animate().cancel()
        notice.text = text
        if (notice.visibility != View.VISIBLE) {
            notice.alpha = 0f
            notice.visibility = View.VISIBLE
        }
        notice.animate().alpha(1f).setDuration(150).start()
        handler.postDelayed(hideNotice, 2600)
    }

    // endregion

    // region Area capture

    private fun selectCapture() {
        if (pendingWrite != null || captureLayer != null) return
        board.clearSelection()
        selectionBar.visibility = View.GONE
        val layer = FrameLayout(this)
        val selector = CaptureSelection(this)
        layer.addView(selector, FrameLayout.LayoutParams(-1, -1))
        val preview = ui.iconButton("Lihat preview", R.drawable.ic_capture).apply {
            ui.setEnabled(this, false)
            setOnClickListener {
                val area = selector.selectedArea() ?: return@setOnClickListener
                val bitmap = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888)
                board.draw(Canvas(bitmap).apply { translate(-area.left.toFloat(), -area.top.toFloat()) })
                closeCapture()
                showCapturePreview(bitmap)
            }
        }
        selector.onSelectionChanged = { ready -> ui.setEnabled(preview, ready) }
        val tools = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = ui.panel()
            addView(TextView(this@MainActivity).apply {
                text = "Seret untuk memilih area"
                setPadding(ui.dp(12), 0, ui.dp(4), 0)
                setTextColor(Color.WHITE)
            })
            addView(preview, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
            addView(ui.iconButton("Batal", R.drawable.ic_close).apply {
                setOnClickListener { closeCapture() }
            }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        }
        layer.addView(tools, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = ui.dp(8)
        })
        boardFrame.addView(layer, FrameLayout.LayoutParams(-1, -1))
        captureLayer = layer
    }

    private fun closeCapture() {
        captureLayer?.let(boardFrame::removeView)
        captureLayer = null
    }

    private fun showCapturePreview(image: Bitmap) {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val dialog = darkDialog("Preview tangkapan", content, widthDp = 600)
        content.addView(ImageView(this).apply {
            setImageBitmap(image)
            contentDescription = "Preview tangkapan papan"
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = ui.surface(0xFF111211.toInt(), 0xFF191C18.toInt(), 12, 0xFF474A45.toInt())
            clipToOutline = true
        }, LinearLayout.LayoutParams(-1, (window.decorView.height * 0.52f).toInt()))
        val actions = LinearLayout(this).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        fun button(label: String, primary: Boolean = false) = ui.textButton(label, primary).also {
            actions.addView(it, LinearLayout.LayoutParams(-2, ui.dp(48)).apply { marginStart = ui.dp(8) })
        }
        button("Batal").setOnClickListener { dialog.dismiss() }
        val share = button("Bagikan")
        button("Simpan PNG", primary = true).setOnClickListener {
            createFile("Papan-Guru-${System.currentTimeMillis()}.png", "image/png") { out ->
                if (!image.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException()
            }
        }
        content.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) })
        share.setOnClickListener {
            share.isEnabled = false
            Thread {
                val uri = try {
                    CaptureProvider.prepareShare(applicationContext, image)
                } catch (_: IOException) {
                    null
                }
                runOnUiThread {
                    if (!dialog.isShowing || isFinishing || isDestroyed) return@runOnUiThread
                    share.isEnabled = true
                    if (uri == null) {
                        toast("PNG gagal disiapkan")
                    } else {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newUri(contentResolver, "Tangkapan papan", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            startActivity(Intent.createChooser(send, "Bagikan tangkapan"))
                        } catch (_: android.content.ActivityNotFoundException) {
                            toast("Aplikasi berbagi tidak tersedia")
                        }
                    }
                }
            }.start()
        }
        dialog.show()
        fitDialog(dialog, 600)
    }

    // endregion

    companion object {
        /** Intent extras used by [HomeActivity]. */
        const val EXTRA_BOARD = "id.kelasku.papanguru.BOARD"
        const val EXTRA_NEW = "id.kelasku.papanguru.NEW"
        const val EXTRA_NAME = "id.kelasku.papanguru.NAME"
        const val EXTRA_PDF = "id.kelasku.papanguru.PDF"
        const val EXTRA_BOARDS = "id.kelasku.papanguru.BOARDS"

        private const val REQ_SAVE = 30
        private const val REQ_IMAGE = 32
        private const val REQ_PDF = 33

        private val PALETTE = listOf(
            "Putih" to Color.WHITE, "Hitam" to 0xFF1B1C1E.toInt(), "Abu-abu" to 0xFF9EA3AB.toInt(),
            "Merah" to 0xFFFF6B6B.toInt(), "Oranye" to 0xFFFFA94D.toInt(),
            "Kuning" to 0xFFFFE066.toInt(), "Hijau" to 0xFF69DB7C.toInt(), "Biru muda" to 0xFF82CFFF.toInt(),
            "Biru" to 0xFF4C6EF5.toInt(), "Ungu" to 0xFFB197FC.toInt(),
        )
        private val PEN_WIDTHS = listOf(2f, 3f, 5f, 8f, 13f, 20f)
        private val HIGHLIGHTER_WIDTHS = listOf(12f, 20f, 32f)
        private val TEXT_SIZES = listOf("kecil" to 20f, "sedang" to 32f, "besar" to 48f, "sangat besar" to 72f)
    }
}

private class CaptureSelection(context: Context) : View(context) {
    var onSelectionChanged: (Boolean) -> Unit = {}
    private val area = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var startX = 0f
    private var startY = 0f

    init {
        contentDescription = "Seret untuk memilih area tangkapan"
        isClickable = true
    }

    fun selectedArea(): Rect? {
        if (area.width() < 2 || area.height() < 2) return null
        return Rect().also { area.roundOut(it) }
    }

    override fun onDraw(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = 0x99000000.toInt()
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, area.top, paint)
        canvas.drawRect(0f, area.bottom, w, h, paint)
        canvas.drawRect(0f, area.top, area.left, area.bottom, paint)
        canvas.drawRect(area.right, area.top, w, area.bottom, paint)
        if (!area.isEmpty) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = resources.displayMetrics.density * 2f
            paint.color = Ui.ACCENT
            canvas.drawRect(area, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = x
                startY = y
                area.setEmpty()
                onSelectionChanged(false)
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                area.set(minOf(startX, x), minOf(startY, y), maxOf(startX, x), maxOf(startY, y))
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    performClick()
                    onSelectionChanged(selectedArea() != null)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                area.setEmpty()
                onSelectionChanged(false)
            }
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
