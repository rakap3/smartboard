package id.kelasku.papanguru

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.util.WeakHashMap
import kotlin.math.hypot
import kotlin.math.max

internal enum class Tool { PEN, HIGHLIGHTER, ERASER, SHAPE, TEXT, SELECT, PAN, COMPASS }
internal enum class EraserMode { PARTIAL, WHOLE }

/**
 * The board surface. One pointer uses the current [tool]; a second pointer switches to pan/pinch
 * navigation and cancels the unfinished action, except in [multiWrite] mode (every finger draws)
 * or on an instrument (two fingers move and rotate it). Edits are previewed on a working copy of
 * the page's items and committed to the page history when the pointer lifts.
 *
 * [Instrument]s (ruler, set square, protractor) lie on top in screen space; pen strokes that start
 * at an instrument edge are projected onto it.
 */
internal class BoardView(context: Context) : View(context) {
    var tool = Tool.PEN
        set(value) {
            if (field == value) return
            cancelGesture()
            if (value != Tool.SELECT) clearSelection()
            field = value
            resetTap()
            invalidate()
            onToolChanged(value)
        }
    var inkColor = 0xFFFFFFFF.toInt()
    /** Pen width in screen pixels; stored per stroke divided by zoom so it looks the same at any zoom. */
    var inkWidth = 3f
    var shapeType = ShapeType.RECT
    var eraserMode = EraserMode.PARTIAL
    var textSize = 32f
    var palmEraser = true
    /** Every pointer draws its own stroke with the pen tools; zoom only with the hand tool. */
    var multiWrite = false
        set(value) {
            field = value
            cancelGesture()
        }

    /** Called after any visible state change; `content` is true when the page items changed. */
    var onChanged: (content: Boolean) -> Unit = {}
    var onToolChanged: (Tool) -> Unit = {}
    var onDoubleTap: (Float, Float) -> Unit = { _, _ -> }
    /** Text tool tap: (existing text or null, board x, board y). */
    var onTextRequest: (TextItem?, Float, Float) -> Unit = { _, _, _ -> }
    var onInstrumentsChanged: () -> Unit = {}

    private var document: BoardDocument? = null
    private var images: ImageCache? = null
    private val page get() = document?.page

    private val density = resources.displayMetrics.density
    val viewport = BoardViewport()
    private val pageViews = WeakHashMap<BoardPage, FloatArray>()
    private var shownPage: BoardPage? = null
    private var renderer = BoardRenderer(density, null, cachePaths = true)

    private val doubleTap = ViewConfiguration.get(context).let {
        DoubleTap(it.scaledTouchSlop.toFloat(), it.scaledDoubleTapSlop.toFloat(), ViewConfiguration.getDoubleTapTimeout())
    }
    /** Page revision right after a single tap committed something, so a double tap can roll it back. */
    private var tapRevision = -1L

    private enum class Gesture {
        NONE, INK, ERASE, SHAPE, TEXT, LASSO, MOVE, SCALE, PAN, NAVIGATE, COMPASS,
        INSTRUMENT_MOVE, INSTRUMENT_ROTATE, INSTRUMENT_TWO,
    }
    private var gesture = Gesture.NONE
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var downX = 0f
    private var downY = 0f

    /** Live strokes by pointer id (several only in multi-write mode), in board coordinates. */
    private class LiveStroke(val snap: Instrument.Snap?) {
        val points = FloatList()
    }
    private val strokes = LinkedHashMap<Int, LiveStroke>()
    private var highlighting = false
    private val projected = FloatArray(2)

    private val pxPerCm = resources.displayMetrics.xdpi / 2.54f
    private val instruments = mutableListOf<Instrument>()
    private var activeInstrument: Instrument? = null
    private var gripAngle = 0f
    private var twoId = MotionEvent.INVALID_POINTER_ID
    private var twoStartAngle = 0f
    private var twoStartVector = 0f
    private var twoStartMidX = 0f
    private var twoStartMidY = 0f
    private var twoStartCx = 0f
    private var twoStartCy = 0f
    private val instrumentFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val instrumentLine = Paint(Paint.ANTI_ALIAS_FLAG)
    private val instrumentText = Paint(Paint.ANTI_ALIAS_FLAG)

    private var compassX = 0f
    private var compassY = 0f
    private var compassRadius = 0f
    private var compassStart = Float.NaN
    private var compassPrev = 0f
    private var compassSweep = 0f
    private var compassLocked = false
    private var preview: BoardItem? = null
    /** Edited copy of the page items while erasing, moving or scaling. */
    private var working: List<BoardItem>? = null
    private var eraserRadius = 0f
    private var cursorX = Float.NaN
    private var cursorY = Float.NaN

    private var selection: Set<Long> = emptySet()
    private var selectionStart: Box? = null
    private val lasso = FloatList()

    private val palmThreshold = resources.displayMetrics.xdpi / 25.4f * 28f
    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 1.5f
        color = 0xFF82CFFF.toInt()
        pathEffect = DashPathEffect(floatArrayOf(density * 6, density * 4), 0f)
    }

    private var layer: Bitmap? = null
    private var layerKey: List<Any?>? = null

    init {
        contentDescription = "Papan tulis, gunakan jari atau pena untuk menulis"
        isFocusable = false
    }

    // region Document and page

    fun setDocument(doc: BoardDocument, cache: ImageCache) {
        cancelGesture()
        clearSelection()
        resetTap()
        document = doc
        images = cache
        renderer = BoardRenderer(density, cache, cachePaths = true)
        pageViews.clear()
        shownPage = null
        pageChanged()
    }

    /** Call after the document's page index or page list changed. */
    fun pageChanged() {
        cancelGesture()
        clearSelection()
        resetTap()
        shownPage?.let { pageViews[it] = floatArrayOf(viewport.scale, viewport.x, viewport.y) }
        val current = page
        val saved = current?.let { pageViews[it] }
        if (saved != null) viewport.set(saved[0], saved[1], saved[2]) else viewport.reset()
        shownPage = current
        changed(content = false)
    }

    val canUndo get() = page?.canUndo == true
    val canRedo get() = page?.canRedo == true
    val hasContent get() = page?.items?.isNotEmpty() == true
    val hasSelection get() = selection.isNotEmpty()
    val zoomPercent get() = viewport.scale * 100

    fun undo() {
        cancelGesture()
        clearSelection()
        resetTap()
        if (page?.undo() == true) changed(content = true)
    }

    fun redo() {
        cancelGesture()
        clearSelection()
        resetTap()
        if (page?.redo() == true) changed(content = true)
    }

    fun clearPage() {
        cancelGesture()
        clearSelection()
        resetTap()
        commit(emptyList())
    }

    fun setBackground(color: BoardColor, pattern: BoardPattern) {
        val p = page ?: return
        p.color = color
        p.pattern = pattern
        changed(content = true)
    }

    // endregion

    // region View controls

    fun zoom(factor: Float) {
        resetTap()
        viewport.zoom(factor)
        changed(content = false)
    }

    fun resetView() {
        resetTap()
        viewport.reset()
        changed(content = false)
    }

    /** Zooms so all content on the page is visible. */
    fun fitContent() {
        resetTap()
        val box = page?.items?.let { Box.unionOf(it) }
        if (box == null) viewport.reset() else viewport.fit(box, density * 32)
        changed(content = false)
    }

    // endregion

    // region Instruments

    fun hasInstrument(type: InstrumentType) = instruments.any { it.type == type }

    /** Shows or hides an instrument, placed in the middle of the board when shown. */
    fun toggleInstrument(type: InstrumentType) {
        val existing = instruments.firstOrNull { it.type == type }
        if (existing != null) {
            instruments.remove(existing)
        } else {
            instruments += Instrument(type, density, pxPerCm, width.toFloat()).apply {
                cx = width / 2f
                cy = height / 2f
            }
        }
        cancelGesture()
        onInstrumentsChanged()
    }

    // endregion

    // region Items

    /** Adds or replaces a text item. An empty string deletes an existing text. */
    fun placeText(text: String, x: Float, y: Float, existing: TextItem?) {
        val doc = document ?: return
        val items = page?.items ?: return
        val trimmed = text.trimEnd()
        val next = if (trimmed.isEmpty()) {
            if (existing == null) return
            items.filter { it.id != existing.id }
        } else {
            val size = existing?.size ?: (textSize / viewport.scale)
            val color = existing?.color ?: inkColor
            val (w, h) = renderer.measureText(trimmed, size)
            val item = TextItem(existing?.id ?: doc.newId(), trimmed, existing?.x ?: x, existing?.y ?: y, size, color, w, h)
            if (existing == null) items + item else items.map { if (it.id == existing.id) item else it }
        }
        commit(next)
    }

    /** Inserts an image centered in the current view and selects it. */
    fun insertImage(file: String, width: Int, height: Int) {
        val doc = document ?: return
        val items = page?.items ?: return
        val view = viewport.visibleBox()
        val fit = minOf(view.width * 0.6f / width, view.height * 0.6f / height)
        val w = width * fit
        val h = height * fit
        val item = ImageItem(doc.newId(), file, view.centerX - w / 2, view.centerY - h / 2, w, h)
        commit(items + item)
        tool = Tool.SELECT
        selection = setOf(item.id)
        changed(content = false)
    }

    fun deleteSelection() {
        val items = page?.items ?: return
        if (selection.isEmpty()) return
        val ids = selection
        clearSelection()
        commit(items.filter { it.id !in ids })
    }

    fun duplicateSelection() {
        val doc = document ?: return
        val items = page?.items ?: return
        val offset = 24 * density / viewport.scale
        val copies = items.filter { it.id in selection }.map { copyWithId(it.transformed(ItemTransform(offset, offset)), doc.newId()) }
        commit(items + copies)
        selection = copies.map { it.id }.toSet()
        changed(content = false)
    }

    fun recolorSelection(color: Int) {
        val items = page?.items ?: return
        commit(items.map { if (it.id in selection) it.recolored(color) else it })
    }

    fun bringSelectionToFront() {
        val items = page?.items ?: return
        val (picked, rest) = items.partition { it.id in selection }
        commit(rest + picked)
    }

    fun selectAll() {
        tool = Tool.SELECT
        selection = page?.items?.filter { it.selectable }?.map { it.id }?.toSet() ?: emptySet()
        changed(content = false)
    }

    fun clearSelection() {
        if (selection.isEmpty()) return
        selection = emptySet()
        changed(content = false)
    }

    /** Text item at a board point, topmost first. */
    private fun textAt(x: Float, y: Float) =
        page?.items?.lastOrNull { it is TextItem && it.hit(x, y, 6 * density / viewport.scale) } as? TextItem

    private val BoardItem.selectable get() = !(this is ImageItem && locked)

    private fun copyWithId(item: BoardItem, id: Long): BoardItem = when (item) {
        is InkItem -> InkItem(id, item.color, item.width, item.points, item.highlighter)
        is ShapeItem -> ShapeItem(id, item.type, item.color, item.width, item.x1, item.y1, item.x2, item.y2)
        is TextItem -> TextItem(id, item.text, item.x, item.y, item.size, item.color, item.measuredWidth, item.measuredHeight)
        is ImageItem -> ImageItem(id, item.file, item.x, item.y, item.w, item.h, item.locked)
    }

    // endregion

    // region Drawing

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        viewport.resize(w, h)
        layer?.recycle()
        layer = null
        layerKey = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val p = page ?: return
        val items = working ?: p.items
        val direct = working != null || gesture == Gesture.NAVIGATE || gesture == Gesture.PAN
        if (direct || !drawLayer(canvas, p)) {
            drawPage(canvas, p, items)
        }
        val save = canvas.save()
        canvas.translate(viewport.x, viewport.y)
        canvas.scale(viewport.scale, viewport.scale)
        if (gesture == Gesture.INK) {
            for (stroke in strokes.values) {
                if (stroke.points.size < 2) continue
                renderer.drawItem(canvas, InkItem(0, inkColor, inkWidth / viewport.scale, stroke.points.toArray(), highlighting))
            }
        }
        preview?.let { renderer.drawItem(canvas, it) }
        canvas.restoreToCount(save)
        drawOverlays(canvas, items)
        val rotating = gesture == Gesture.INSTRUMENT_ROTATE || gesture == Gesture.INSTRUMENT_TWO
        for (instrument in instruments) {
            instrument.draw(canvas, instrumentFill, instrumentLine, instrumentText, rotating && instrument === activeInstrument, !p.color.dark)
        }
    }

    private fun drawPage(canvas: Canvas, p: BoardPage, items: List<BoardItem>) {
        renderer.drawBackground(canvas, p.color, p.pattern, viewport.x, viewport.y, viewport.scale, width.toFloat(), height.toFloat())
        val save = canvas.save()
        canvas.translate(viewport.x, viewport.y)
        canvas.scale(viewport.scale, viewport.scale)
        val visible = viewport.visibleBox()
        for (item in items) if (item.bounds.intersects(visible)) renderer.drawItem(canvas, item)
        canvas.restoreToCount(save)
    }

    /** Draws the committed page from a cached bitmap, re-rendering it when anything it shows changed. */
    private fun drawLayer(canvas: Canvas, p: BoardPage): Boolean {
        if (width <= 0 || height <= 0) return false
        val key = listOf(p, p.revision, p.color, p.pattern, viewport.scale, viewport.x, viewport.y)
        var bitmap = layer
        if (bitmap == null) {
            bitmap = try {
                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            } catch (_: OutOfMemoryError) {
                return false
            }
            layer = bitmap
            layerKey = null
        }
        if (key != layerKey) {
            drawPage(Canvas(bitmap), p, p.items)
            layerKey = key
        }
        canvas.drawBitmap(bitmap, 0f, 0f, null)
        return true
    }

    private fun drawOverlays(canvas: Canvas, items: List<BoardItem>) {
        if (selection.isNotEmpty()) {
            selectionBox(items)?.let { box ->
                val l = viewport.screenX(box.left)
                val t = viewport.screenY(box.top)
                val r = viewport.screenX(box.right)
                val b = viewport.screenY(box.bottom)
                canvas.drawRect(l, t, r, b, dashPaint)
                overlayPaint.style = Paint.Style.FILL
                overlayPaint.color = 0xFF82CFFF.toInt()
                canvas.drawCircle(r, b, HANDLE * density / 2, overlayPaint)
                overlayPaint.color = 0xFF16171A.toInt()
                canvas.drawCircle(r, b, HANDLE * density / 2 - density * 3, overlayPaint)
            }
        }
        if (gesture == Gesture.LASSO && lasso.size >= 4) {
            val pts = lasso.toArray()
            for (i in pts.indices) pts[i] = if (i % 2 == 0) viewport.screenX(pts[i]) else viewport.screenY(pts[i])
            val path = BoardRenderer.smoothPath(pts)
            path.close()
            canvas.drawPath(path, dashPaint)
        }
        if (gesture == Gesture.COMPASS) {
            val cx = viewport.screenX(compassX)
            val cy = viewport.screenY(compassY)
            overlayPaint.style = Paint.Style.FILL
            overlayPaint.color = Ui.ACCENT
            canvas.drawCircle(cx, cy, density * 4, overlayPaint)
            if (!cursorX.isNaN()) {
                canvas.drawLine(cx, cy, cursorX, cursorY, dashPaint)
                instrumentText.textSize = 14 * density
                instrumentText.textAlign = Paint.Align.CENTER
                instrumentText.color = Ui.TEXT
                val cm = compassRadius * viewport.scale / pxPerCm
                canvas.drawText(String.format(java.util.Locale.US, "r = %.1f cm", cm), (cx + cursorX) / 2, (cy + cursorY) / 2 - 10 * density, instrumentText)
            }
        }
        if (gesture == Gesture.ERASE && !cursorX.isNaN()) {
            overlayPaint.style = Paint.Style.STROKE
            overlayPaint.strokeWidth = density * 1.5f
            overlayPaint.color = 0xCCFFFFFF.toInt()
            canvas.drawCircle(cursorX, cursorY, eraserRadius, overlayPaint)
            overlayPaint.color = 0x99000000.toInt()
            canvas.drawCircle(cursorX, cursorY, eraserRadius + density, overlayPaint)
        }
    }

    private fun selectionBox(items: List<BoardItem>) = Box.unionOf(items.filter { it.id in selection })

    // endregion

    // region Touch

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> down(event)
            MotionEvent.ACTION_POINTER_DOWN -> pointerDown(event)
            MotionEvent.ACTION_MOVE -> move(event)
            MotionEvent.ACTION_POINTER_UP -> pointerUp(event)
            MotionEvent.ACTION_UP -> up(event)
            MotionEvent.ACTION_CANCEL -> {
                resetTap()
                cancelGesture()
            }
        }
        invalidate()
        return true
    }

    private fun pointerDown(event: MotionEvent) {
        resetTap()
        val i = event.actionIndex
        when (gesture) {
            Gesture.INK -> if (multiWrite) {
                startStroke(event.getPointerId(i), event.getX(i), event.getY(i))
                return
            }
            Gesture.INSTRUMENT_MOVE, Gesture.INSTRUMENT_ROTATE -> {
                val first = event.findPointerIndex(pointerId)
                if (first >= 0 && activeInstrument != null) {
                    startTwoFinger(event, first, i)
                    return
                }
            }
            Gesture.INSTRUMENT_TWO -> return
            else -> Unit
        }
        cancelGesture()
        gesture = Gesture.NAVIGATE
        updatePinch(event, apply = false)
    }

    private fun pointerUp(event: MotionEvent) {
        val id = event.getPointerId(event.actionIndex)
        when (gesture) {
            Gesture.INK -> if (multiWrite) {
                strokes.remove(id)?.let { stroke ->
                    addPoint(stroke, event.getX(event.actionIndex), event.getY(event.actionIndex))
                    commitStroke(stroke)
                }
                if (id == pointerId) pointerId = strokes.keys.firstOrNull() ?: MotionEvent.INVALID_POINTER_ID
                return
            }
            Gesture.INSTRUMENT_TWO -> {
                if (id != pointerId && id != twoId) return
                // Keep moving with the finger that stays down.
                if (id == pointerId) pointerId = twoId
                twoId = MotionEvent.INVALID_POINTER_ID
                val index = event.findPointerIndex(pointerId)
                if (index >= 0) {
                    lastX = event.getX(index)
                    lastY = event.getY(index)
                }
                gesture = Gesture.INSTRUMENT_MOVE
                return
            }
            else -> Unit
        }
        val remaining = (0 until event.pointerCount).filter { it != event.actionIndex }
        if (remaining.size >= 2) {
            updatePinch(event, apply = false, first = remaining[0], second = remaining[1])
        } else {
            lastSpan = 0f
            lastX = event.getX(remaining.first())
            lastY = event.getY(remaining.first())
        }
    }

    private fun startTwoFinger(event: MotionEvent, first: Int, second: Int) {
        val instrument = activeInstrument ?: return
        twoId = event.getPointerId(second)
        twoStartAngle = instrument.angle
        twoStartVector = kotlin.math.atan2(event.getY(second) - event.getY(first), event.getX(second) - event.getX(first))
        twoStartMidX = (event.getX(first) + event.getX(second)) / 2
        twoStartMidY = (event.getY(first) + event.getY(second)) / 2
        twoStartCx = instrument.cx
        twoStartCy = instrument.cy
        gesture = Gesture.INSTRUMENT_TWO
    }

    private fun down(event: MotionEvent) {
        val x = event.x
        val y = event.y
        val toolType = event.getToolType(0)
        doubleTap.down(x, y, event.eventTime, toolType == MotionEvent.TOOL_TYPE_FINGER)
        pointerId = event.getPointerId(0)
        lastX = x
        lastY = y
        downX = x
        downY = y
        lastSpan = 0f
        val bx = viewport.boardX(x)
        val by = viewport.boardY(y)
        val palm = palmEraser && !multiWrite && toolType == MotionEvent.TOOL_TYPE_FINGER && event.getTouchMajor(0) >= palmThreshold
        val inkTool = tool == Tool.PEN || tool == Tool.HIGHLIGHTER
        instrumentControl(x, y)?.let {
            gesture = it
            return
        }
        gesture = when {
            page == null -> Gesture.NONE
            toolType == MotionEvent.TOOL_TYPE_ERASER || palm -> startErase(x, y, event.getTouchMajor(0), wholeItems = false)
            inkTool && snapAt(x, y) != null -> startInk(pointerId, x, y)
            instrumentBody(x, y) -> Gesture.INSTRUMENT_MOVE
            tool == Tool.PAN -> Gesture.PAN
            inkTool -> startInk(pointerId, x, y)
            tool == Tool.COMPASS -> {
                compassX = bx
                compassY = by
                compassRadius = 0f
                compassStart = Float.NaN
                compassSweep = 0f
                compassLocked = false
                Gesture.COMPASS
            }
            tool == Tool.ERASER -> startErase(x, y, 0f, eraserMode == EraserMode.WHOLE)
            tool == Tool.SHAPE -> Gesture.SHAPE
            tool == Tool.TEXT -> Gesture.TEXT
            tool == Tool.SELECT -> startSelect(x, y, bx, by)
            else -> Gesture.NONE
        }
    }

    private var eraseWhole = false

    private fun startInk(id: Int, x: Float, y: Float): Gesture {
        strokes.clear()
        highlighting = tool == Tool.HIGHLIGHTER
        startStroke(id, x, y)
        return Gesture.INK
    }

    private fun startStroke(id: Int, x: Float, y: Float) {
        val stroke = LiveStroke(snapAt(x, y))
        strokes[id] = stroke
        addPoint(stroke, x, y)
    }

    /** Adds a screen point to a live stroke, projected onto its instrument edge if snapped. */
    private fun addPoint(stroke: LiveStroke, sx: Float, sy: Float) {
        val snap = stroke.snap
        if (snap == null) {
            stroke.points.add(viewport.boardX(sx), viewport.boardY(sy))
        } else {
            snap.instrument.project(snap.edge, sx, sy, projected)
            stroke.points.add(viewport.boardX(projected[0]), viewport.boardY(projected[1]))
        }
    }

    private fun snapAt(x: Float, y: Float): Instrument.Snap? {
        val limit = SNAP * density
        for (instrument in instruments.asReversed()) instrument.edgeNear(x, y, limit)?.let { return it }
        return null
    }

    /** Handles taps on instrument close/rotate controls; returns the gesture, or null if none was hit. */
    private fun instrumentControl(x: Float, y: Float): Gesture? {
        for (instrument in instruments.asReversed()) {
            if (instrument.hitsClose(x, y)) {
                instruments.remove(instrument)
                onInstrumentsChanged()
                return Gesture.NONE
            }
            if (instrument.hitsRotate(x, y)) {
                activeInstrument = instrument
                gripAngle = instrument.handleAngle(x, y) - instrument.angle
                return Gesture.INSTRUMENT_ROTATE
            }
        }
        return null
    }

    private fun instrumentBody(x: Float, y: Float): Boolean {
        activeInstrument = instruments.lastOrNull { it.contains(x, y) }
        return activeInstrument != null
    }

    private fun startErase(x: Float, y: Float, contact: Float, wholeItems: Boolean): Gesture {
        eraseWhole = wholeItems
        eraserRadius = max(density * if (wholeItems) 10 else 18, contact / 2)
        working = page?.items
        cursorX = x
        cursorY = y
        eraseSegment(x, y, x, y)
        return Gesture.ERASE
    }

    private fun startSelect(x: Float, y: Float, bx: Float, by: Float): Gesture {
        val box = page?.items?.let { selectionBox(it) }
        if (box != null) {
            val hx = viewport.screenX(box.right)
            val hy = viewport.screenY(box.bottom)
            selectionStart = box
            if (hypot(x - hx, y - hy) <= HANDLE * density) return Gesture.SCALE
            if (box.inset(8 * density / viewport.scale).contains(bx, by)) return Gesture.MOVE
        }
        selectionStart = null
        lasso.clear()
        lasso.add(bx, by)
        return Gesture.LASSO
    }

    private fun move(event: MotionEvent) {
        if (event.pointerCount == 1) trackTapMovement(event)
        val index = event.findPointerIndex(pointerId)
        when (gesture) {
            Gesture.NAVIGATE -> if (event.pointerCount >= 2) updatePinch(event, apply = true)
            Gesture.PAN -> if (!doubleTap.still) {
                viewport.transform(lastX, lastY, event.x, event.y)
                lastX = event.x
                lastY = event.y
                changed(content = false)
            }
            Gesture.INK -> {
                val single = strokes[pointerId]
                if (!multiWrite && index >= 0 && single != null && single.snap == null && palmEraser &&
                    event.getToolType(index) == MotionEvent.TOOL_TYPE_FINGER &&
                    event.getTouchMajor(index) >= palmThreshold && single.points.size <= 40
                ) {
                    // The contact grew into a palm: treat it as erasing instead of a stroke.
                    strokes.clear()
                    gesture = startErase(event.getX(index), event.getY(index), event.getTouchMajor(index), wholeItems = false)
                } else {
                    for (k in 0 until event.pointerCount) {
                        val stroke = strokes[event.getPointerId(k)] ?: continue
                        for (h in 0 until event.historySize) addPoint(stroke, event.getHistoricalX(k, h), event.getHistoricalY(k, h))
                        addPoint(stroke, event.getX(k), event.getY(k))
                    }
                }
            }
            Gesture.COMPASS -> if (index >= 0) updateCompass(event.getX(index), event.getY(index))
            Gesture.INSTRUMENT_MOVE -> if (index >= 0) {
                activeInstrument?.let {
                    it.cx += event.getX(index) - lastX
                    it.cy += event.getY(index) - lastY
                }
                lastX = event.getX(index)
                lastY = event.getY(index)
            }
            Gesture.INSTRUMENT_ROTATE -> if (index >= 0) {
                activeInstrument?.let { it.setAngleSnapped(it.handleAngle(event.getX(index), event.getY(index)) - gripAngle) }
            }
            Gesture.INSTRUMENT_TWO -> {
                val a = event.findPointerIndex(pointerId)
                val b = event.findPointerIndex(twoId)
                val instrument = activeInstrument
                if (a >= 0 && b >= 0 && instrument != null) {
                    val vector = kotlin.math.atan2(event.getY(b) - event.getY(a), event.getX(b) - event.getX(a))
                    instrument.setAngleSnapped(twoStartAngle + (vector - twoStartVector))
                    instrument.cx = twoStartCx + (event.getX(a) + event.getX(b)) / 2 - twoStartMidX
                    instrument.cy = twoStartCy + (event.getY(a) + event.getY(b)) / 2 - twoStartMidY
                }
            }
            Gesture.ERASE -> if (index >= 0) {
                for (h in 0 until event.historySize) eraseTo(event.getHistoricalX(index, h), event.getHistoricalY(index, h))
                eraseTo(event.getX(index), event.getY(index))
            }
            Gesture.SHAPE -> if (index >= 0) preview = shapeFor(event.getX(index), event.getY(index))
            Gesture.LASSO -> if (index >= 0) lasso.add(viewport.boardX(event.getX(index)), viewport.boardY(event.getY(index)))
            Gesture.MOVE, Gesture.SCALE -> if (index >= 0) transformSelection(event.getX(index), event.getY(index))
            Gesture.TEXT, Gesture.NONE -> Unit
        }
    }

    private fun up(event: MotionEvent) {
        trackTapMovement(event)
        val tap = doubleTap.up(event.eventTime)
        if (tap == DoubleTap.Result.DOUBLE) {
            cancelGesture()
            val p = page
            if (p != null && tapRevision >= 0 && p.revision == tapRevision && p.discardLast()) changed(content = true)
            tapRevision = -1
            onDoubleTap(event.x, event.y)
            return
        }
        val before = page?.revision
        val x = event.x
        val y = event.y
        when (gesture) {
            Gesture.INK -> {
                strokes[event.getPointerId(event.actionIndex)]?.let { addPoint(it, x, y) }
                for (stroke in strokes.values.toList()) commitStroke(stroke)
                strokes.clear()
            }
            Gesture.COMPASS -> finishCompass()
            Gesture.ERASE -> {
                eraseTo(x, y)
                val result = working
                working = null
                cursorX = Float.NaN
                if (result != null) commit(result)
            }
            Gesture.SHAPE -> {
                val shape = shapeFor(x, y)
                preview = null
                val minSize = 4 * density / viewport.scale
                if (shape != null && (shape.bounds.width > minSize + shape.width || shape.bounds.height > minSize + shape.width)) {
                    commit((page?.items ?: emptyList()) + shape)
                }
            }
            Gesture.TEXT -> if (hypot(x - downX, y - downY) < 24 * density) {
                val bx = viewport.boardX(x)
                val by = viewport.boardY(y)
                onTextRequest(textAt(bx, by), bx, by)
            }
            Gesture.LASSO -> finishLasso(x, y)
            Gesture.MOVE, Gesture.SCALE -> {
                transformSelection(x, y)
                val result = working
                working = null
                selectionStart = null
                if (result != null) commit(result)
            }
            Gesture.PAN, Gesture.NAVIGATE, Gesture.NONE,
            Gesture.INSTRUMENT_MOVE, Gesture.INSTRUMENT_ROTATE, Gesture.INSTRUMENT_TWO -> Unit
        }
        activeInstrument = null
        gesture = Gesture.NONE
        pointerId = MotionEvent.INVALID_POINTER_ID
        val after = page?.revision
        tapRevision = if (tap == DoubleTap.Result.SINGLE && after != null && after != before) after else -1
    }

    private fun commitStroke(stroke: LiveStroke) {
        val doc = document ?: return
        val p = page ?: return
        val pts = stroke.points.toArray()
        stroke.points.clear()
        if (pts.size < 2) return
        commit(p.items + InkItem(doc.newId(), inkColor, inkWidth / viewport.scale, simplify(pts), highlighting))
    }

    /**
     * Compass: the touch-down point is the center. Dragging out sets the radius; once the finger
     * has swept more than ~10° around the center the radius locks and further movement draws an arc.
     * Releasing before that draws a full circle.
     */
    private fun updateCompass(sx: Float, sy: Float) {
        cursorX = sx
        cursorY = sy
        val cx = viewport.screenX(compassX)
        val cy = viewport.screenY(compassY)
        val r = hypot(sx - cx, sy - cy)
        val a = kotlin.math.atan2(sy - cy, sx - cx)
        if (!compassLocked) compassRadius = r / viewport.scale
        if (r < COMPASS_MIN * density) {
            preview = null
            return
        }
        if (compassStart.isNaN()) {
            compassStart = a
            compassPrev = a
        } else {
            var delta = a - compassPrev
            if (delta > Math.PI) delta -= (2 * Math.PI).toFloat()
            if (delta < -Math.PI) delta += (2 * Math.PI).toFloat()
            compassSweep = (compassSweep + delta).coerceIn(-FULL_TURN, FULL_TURN)
            compassPrev = a
            if (!compassLocked && kotlin.math.abs(compassSweep) > LOCK_SWEEP) compassLocked = true
        }
        preview = compassItem(0)
    }

    private fun compassItem(id: Long): InkItem {
        val start = if (compassLocked) compassStart else 0f
        val sweep = if (compassLocked) compassSweep else FULL_TURN
        val points = Instrument.arcPoints(compassX, compassY, compassRadius, start, sweep, compassRadius * viewport.scale)
        return InkItem(id, inkColor, inkWidth / viewport.scale, points)
    }

    private fun finishCompass() {
        val doc = document ?: return
        val p = page ?: return
        preview = null
        cursorX = Float.NaN
        if (compassStart.isNaN() || compassRadius * viewport.scale < COMPASS_MIN * density) return
        commit(p.items + compassItem(doc.newId()))
    }

    /** Drops points closer than ~half a screen pixel to the previous kept point. */
    private fun simplify(pts: FloatArray): FloatArray {
        val minDist = 0.6f / viewport.scale
        val out = FloatList()
        for (i in 0 until pts.size / 2) {
            val x = pts[i * 2]
            val y = pts[i * 2 + 1]
            if (out.size >= 2 && i < pts.size / 2 - 1 && hypot(x - out[out.size - 2], y - out[out.size - 1]) < minDist) continue
            out.add(x, y)
        }
        // A tap that never moved is a dot (one point).
        if (out.size == 4 && out[0] == out[2] && out[1] == out[3]) return floatArrayOf(out[0], out[1])
        return out.toArray()
    }

    private fun shapeFor(x: Float, y: Float): ShapeItem? {
        val doc = document ?: return null
        return ShapeItem(
            preview?.id ?: doc.newId(), shapeType, inkColor, inkWidth / viewport.scale,
            viewport.boardX(downX), viewport.boardY(downY), viewport.boardX(x), viewport.boardY(y)
        )
    }

    private fun eraseTo(x: Float, y: Float) {
        eraseSegment(cursorX, cursorY, x, y)
        cursorX = x
        cursorY = y
    }

    private fun eraseSegment(sx1: Float, sy1: Float, sx2: Float, sy2: Float) {
        val doc = document ?: return
        val items = working ?: return
        if (sx1.isNaN()) return
        val r = eraserRadius / viewport.scale
        val ax = viewport.boardX(sx1)
        val ay = viewport.boardY(sy1)
        val bx = viewport.boardX(sx2)
        val by = viewport.boardY(sy2)
        val segment = Box.of(ax, ay, bx, by).inset(r)
        var changed = false
        val next = ArrayList<BoardItem>(items.size)
        for (item in items) {
            if (item is ImageItem || !item.bounds.intersects(segment)) {
                next += item
                continue
            }
            if (item is InkItem && !eraseWhole) {
                val pieces = Geometry.eraseInk(item, ax, ay, bx, by, r, doc::newId)
                if (pieces == null) next += item else {
                    next += pieces
                    changed = true
                }
            } else if (hitsSegment(item, ax, ay, bx, by, r)) {
                changed = true
            } else {
                next += item
            }
        }
        if (changed) working = next
    }

    private fun hitsSegment(item: BoardItem, ax: Float, ay: Float, bx: Float, by: Float, r: Float): Boolean {
        val steps = max(1, (hypot(bx - ax, by - ay) / max(r, 0.01f)).toInt())
        for (i in 0..steps) {
            val f = i.toFloat() / steps
            if (item.hit(ax + (bx - ax) * f, ay + (by - ay) * f, r)) return true
        }
        return false
    }

    private fun finishLasso(x: Float, y: Float) {
        val items = page?.items ?: return
        val bx = viewport.boardX(x)
        val by = viewport.boardY(y)
        val poly = lasso.toArray()
        lasso.clear()
        val isTap = hypot(x - downX, y - downY) < 12 * density
        selection = if (isTap) {
            val r = 8 * density / viewport.scale
            items.lastOrNull { it.selectable && it.hit(bx, by, r) }?.let { setOf(it.id) } ?: emptySet()
        } else {
            items.filter { item ->
                item.selectable && (Geometry.inPolygon(poly, item.bounds.centerX, item.bounds.centerY) ||
                    (item is InkItem && insideShare(item.points, poly) >= 0.5f))
            }.map { it.id }.toSet()
        }
        changed(content = false)
    }

    /** Fraction of a stroke's points inside the lasso (sampled for long strokes). */
    private fun insideShare(points: FloatArray, poly: FloatArray): Float {
        val n = points.size / 2
        val step = max(1, n / 32)
        var inside = 0
        var total = 0
        var i = 0
        while (i < n) {
            if (Geometry.inPolygon(poly, points[i * 2], points[i * 2 + 1])) inside++
            total++
            i += step
        }
        return if (total == 0) 0f else inside.toFloat() / total
    }

    private fun transformSelection(x: Float, y: Float) {
        val items = page?.items ?: return
        val start = selectionStart ?: return
        val t = if (gesture == Gesture.MOVE) {
            ItemTransform((x - downX) / viewport.scale, (y - downY) / viewport.scale)
        } else {
            // Uniform scale around the top-left corner, following the bottom-right handle.
            val from = hypot(start.width, start.height)
            val to = hypot(viewport.boardX(x) - start.left, viewport.boardY(y) - start.top)
            val factor = if (from <= 0f) 1f else (to / from).coerceIn(0.05f, 50f)
            ItemTransform(scale = factor, px = start.left, py = start.top)
        }
        working = items.map { if (it.id in selection) it.transformed(t) else it }
    }

    private fun updatePinch(event: MotionEvent, apply: Boolean, first: Int = 0, second: Int = 1) {
        val x = (event.getX(first) + event.getX(second)) / 2
        val y = (event.getY(first) + event.getY(second)) / 2
        val span = hypot(event.getX(first) - event.getX(second), event.getY(first) - event.getY(second))
        if (apply && lastSpan > 0f && span > 0f) {
            viewport.transform(lastX, lastY, x, y, span / lastSpan)
            changed(content = false)
        }
        lastX = x
        lastY = y
        lastSpan = span
    }

    private fun trackTapMovement(event: MotionEvent) {
        for (sample in 0 until event.historySize) {
            doubleTap.move(event.getHistoricalX(0, sample), event.getHistoricalY(0, sample))
        }
        doubleTap.move(event.x, event.y)
    }

    private fun resetTap() {
        doubleTap.cancel()
        tapRevision = -1
    }

    /** Abandons the unfinished action without committing it. */
    private fun cancelGesture() {
        strokes.clear()
        activeInstrument = null
        twoId = MotionEvent.INVALID_POINTER_ID
        lasso.clear()
        preview = null
        working = null
        selectionStart = null
        cursorX = Float.NaN
        gesture = Gesture.NONE
        pointerId = MotionEvent.INVALID_POINTER_ID
        invalidate()
    }

    // endregion

    private fun commit(items: List<BoardItem>) {
        val p = page ?: return
        val before = p.revision
        p.commit(items)
        // Keep only selected ids that still exist.
        if (selection.isNotEmpty()) {
            val ids = items.mapTo(HashSet()) { it.id }
            selection = selection.filterTo(HashSet()) { it in ids }
        }
        changed(content = p.revision != before)
    }

    private fun changed(content: Boolean) {
        invalidate()
        onChanged(content)
    }

    companion object {
        /** Selection scale handle diameter in dp. */
        private const val HANDLE = 28
        /** Distance in dp from an instrument edge at which a pen stroke snaps to it. */
        private const val SNAP = 26
        private const val COMPASS_MIN = 16
        private const val FULL_TURN = (2 * Math.PI).toFloat()
        private const val LOCK_SWEEP = (10 * Math.PI / 180).toFloat()
    }
}

/** Growable float list without boxing. */
internal class FloatList {
    private var data = FloatArray(256)
    var size = 0
        private set

    operator fun get(i: Int) = data[i]

    fun add(x: Float, y: Float) {
        if (size + 2 > data.size) data = data.copyOf(data.size * 2)
        data[size++] = x
        data[size++] = y
    }

    fun clear() {
        size = 0
    }

    fun toArray(): FloatArray = data.copyOf(size)
}
