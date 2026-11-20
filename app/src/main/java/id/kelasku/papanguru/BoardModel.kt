package id.kelasku.papanguru

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Board content model. Plain Kotlin only (no android.* imports) so it can be unit tested on the JVM.
 *
 * Items are immutable: every edit produces new item instances and a new item list. This keeps
 * undo/redo a matter of swapping list snapshots and lets saving serialize a snapshot off the UI thread.
 * All coordinates are board coordinates (see [BoardViewport]).
 */
internal data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    val isEmpty get() = width <= 0f || height <= 0f

    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
    fun inset(d: Float) = Box(left - d, top - d, right + d, bottom + d)
    fun union(o: Box) = Box(min(left, o.left), min(top, o.top), max(right, o.right), max(bottom, o.bottom))
    fun intersects(o: Box) = left <= o.right && o.left <= right && top <= o.bottom && o.top <= bottom

    companion object {
        fun of(x1: Float, y1: Float, x2: Float, y2: Float) = Box(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))

        fun ofPoints(points: FloatArray): Box {
            var l = Float.MAX_VALUE
            var t = Float.MAX_VALUE
            var r = -Float.MAX_VALUE
            var b = -Float.MAX_VALUE
            for (i in 0 until points.size / 2) {
                val x = points[i * 2]
                val y = points[i * 2 + 1]
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
            return if (points.size < 2) Box(0f, 0f, 0f, 0f) else Box(l, t, r, b)
        }

        fun unionOf(items: Iterable<BoardItem>): Box? = items.fold(null as Box?) { acc, it ->
            acc?.union(it.bounds) ?: it.bounds
        }
    }
}

/** Maps a point with a uniform scale around ([px], [py]) followed by a translation. */
internal data class ItemTransform(val dx: Float = 0f, val dy: Float = 0f, val scale: Float = 1f, val px: Float = 0f, val py: Float = 0f) {
    fun x(x: Float) = px + (x - px) * scale + dx
    fun y(y: Float) = py + (y - py) * scale + dy
    fun points(src: FloatArray) = FloatArray(src.size) { if (it % 2 == 0) x(src[it]) else y(src[it]) }
}

internal enum class ShapeType { LINE, ARROW, RECT, ELLIPSE, TRIANGLE }

internal sealed class BoardItem {
    abstract val id: Long
    abstract val bounds: Box

    /** True if (x, y) is within [radius] of what this item draws. */
    abstract fun hit(x: Float, y: Float, radius: Float): Boolean
    abstract fun transformed(t: ItemTransform): BoardItem
    open fun recolored(color: Int): BoardItem = this

    /** Renderer-owned cache (for example a built Path). Never serialized, never compared. */
    @Transient
    var renderCache: Any? = null
}

internal class InkItem(
    override val id: Long,
    val color: Int,
    val width: Float,
    /** Flat x0,y0,x1,y1,... list. A single point draws a dot. */
    val points: FloatArray,
    val highlighter: Boolean = false,
) : BoardItem() {
    override val bounds by lazy { Box.ofPoints(points).inset(width / 2) }
    val pointCount get() = points.size / 2

    override fun hit(x: Float, y: Float, radius: Float): Boolean {
        val limit = radius + width / 2
        if (!bounds.inset(radius).contains(x, y)) return false
        return Geometry.polylineDistance(points, x, y) <= limit
    }

    override fun transformed(t: ItemTransform) = InkItem(id, color, width * t.scale, t.points(points), highlighter)
    override fun recolored(color: Int) = InkItem(id, color, width, points, highlighter)
}

internal class ShapeItem(
    override val id: Long,
    val type: ShapeType,
    val color: Int,
    val width: Float,
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
) : BoardItem() {
    override val bounds by lazy { Box.ofPoints(outline()).inset(width / 2 + if (type == ShapeType.ARROW) width * 2 else 0f) }

    /** Polyline(s) approximating the drawn shape, used for hit testing and bounds. */
    fun outline(): FloatArray = Geometry.shapeOutline(type, x1, y1, x2, y2, width)

    override fun hit(x: Float, y: Float, radius: Float): Boolean {
        if (!bounds.inset(radius).contains(x, y)) return false
        return Geometry.polylineDistance(outline(), x, y) <= radius + width / 2
    }

    override fun transformed(t: ItemTransform) =
        ShapeItem(id, type, color, width * t.scale, t.x(x1), t.y(y1), t.x(x2), t.y(y2))

    override fun recolored(color: Int) = ShapeItem(id, type, color, width, x1, y1, x2, y2)
}

internal class TextItem(
    override val id: Long,
    val text: String,
    val x: Float,
    /** Top of the first line. */
    val y: Float,
    val size: Float,
    val color: Int,
    /** Measured by the view when the text is created or edited, in board units. */
    val measuredWidth: Float,
    val measuredHeight: Float,
) : BoardItem() {
    override val bounds get() = Box(x, y, x + measuredWidth, y + measuredHeight)
    override fun hit(x: Float, y: Float, radius: Float) = bounds.inset(radius).contains(x, y)
    override fun transformed(t: ItemTransform) = TextItem(
        id, text, t.x(x), t.y(y), size * t.scale, color, measuredWidth * t.scale, measuredHeight * t.scale
    )

    override fun recolored(color: Int) = TextItem(id, text, x, y, size, color, measuredWidth, measuredHeight)
}

internal class ImageItem(
    override val id: Long,
    /** File name inside the board's image folder. */
    val file: String,
    val x: Float, val y: Float, val w: Float, val h: Float,
    /** Locked images (imported PDF pages) can't be selected or erased. */
    val locked: Boolean = false,
) : BoardItem() {
    override val bounds get() = Box(x, y, x + w, y + h)
    override fun hit(x: Float, y: Float, radius: Float) = bounds.inset(radius).contains(x, y)
    override fun transformed(t: ItemTransform) = ImageItem(id, file, t.x(x), t.y(y), w * t.scale, h * t.scale, locked)
}

internal enum class BoardColor(val argb: Int, val dark: Boolean) {
    BLACKBOARD(0xFF121312.toInt(), true),
    GREEN(0xFF1D3A2C.toInt(), true),
    WHITE(0xFFF7F7F4.toInt(), false),
}

internal enum class BoardPattern { NONE, DOTS, GRID, LINES }

internal class BoardPage(
    items: List<BoardItem> = emptyList(),
    var color: BoardColor = BoardColor.BLACKBOARD,
    var pattern: BoardPattern = BoardPattern.DOTS,
    /** Stable page id (indices shift when pages move); used by classroom sync. */
    val id: String = newPageId(),
) {
    var items: List<BoardItem> = items
        private set
    private val undoStack = ArrayDeque<List<BoardItem>>()
    private val redoStack = ArrayDeque<List<BoardItem>>()

    /** Increments on every content change; used for render caching and double-tap rollback. */
    var revision = 0L
        private set

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun commit(next: List<BoardItem>) {
        if (next == items) return
        undoStack.addLast(items)
        while (undoStack.size > HISTORY_LIMIT) undoStack.removeFirst()
        redoStack.clear()
        items = next
        revision++
    }

    fun undo(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(items)
        items = previous
        revision++
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(items)
        items = next
        revision++
        return true
    }

    /** Undoes the last commit without making it redoable (used to roll back an accidental tap). */
    fun discardLast(): Boolean {
        val previous = undoStack.removeLastOrNull() ?: return false
        items = previous
        revision++
        return true
    }

    /** A duplicate is a new page, so it gets a new [id]. */
    fun copy() = BoardPage(items, color, pattern)

    companion object {
        const val HISTORY_LIMIT = 200
        val SAFE_PAGE_ID = Regex("p_[A-Za-z0-9]{1,32}")

        fun newPageId(): String = "p_" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
    }
}

internal class BoardDocument(
    val id: String,
    var name: String,
    val pages: MutableList<BoardPage> = mutableListOf(BoardPage()),
    var pageIndex: Int = 0,
    /** Id namespace of whoever edits this document: 0 is the teacher, classroom students get 1 and up. */
    val slot: Int = 0,
) {
    init {
        require(slot in 0..ItemIds.MAX_SLOT) { "slot out of range: $slot" }
    }

    /** Last counter used in this document's [slot]; items from other slots don't move it. */
    private var lastCounter = pages.flatMap { it.items }
        .filter { ItemIds.slotOf(it.id) == slot }
        .maxOfOrNull { ItemIds.counterOf(it.id) } ?: 0L

    val page get() = pages[pageIndex.coerceIn(0, pages.lastIndex)]

    @Synchronized
    fun newId(): Long {
        check(lastCounter < ItemIds.MAX_COUNTER) { "item ids exhausted" }
        return ItemIds.of(slot, ++lastCounter)
    }

    /** Image file names still referenced by any page. */
    fun referencedImages(): Set<String> =
        pages.flatMap { it.items }.filterIsInstance<ImageItem>().map { it.file }.toSet()
}

/**
 * Item ids are `slot × 2^32 + counter`, so the teacher (slot 0) and each classroom student
 * (slot ≥ 1, assigned by the server) never create the same id. Slots stay below 2^21 so ids
 * are exact in JavaScript numbers (< 2^53).
 */
internal object ItemIds {
    const val MAX_SLOT = (1 shl 21) - 1
    const val MAX_COUNTER = 0xFFFFFFFFL

    fun of(slot: Int, counter: Long): Long = (slot.toLong() shl 32) or counter
    fun slotOf(id: Long): Int = (id ushr 32).toInt()
    fun counterOf(id: Long): Long = id and MAX_COUNTER
}

internal object Geometry {
    fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len = dx * dx + dy * dy
        val t = if (len == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len).coerceIn(0f, 1f)
        val cx = ax + t * dx - px
        val cy = ay + t * dy - py
        return kotlin.math.sqrt(cx * cx + cy * cy)
    }

    /** Distance from a point to a flat polyline. NaN pairs split the polyline into separate runs. */
    fun polylineDistance(points: FloatArray, x: Float, y: Float): Float {
        val n = points.size / 2
        if (n == 0) return Float.MAX_VALUE
        var best = Float.MAX_VALUE
        if (n == 1) return segmentDistance(x, y, points[0], points[1], points[0], points[1])
        for (i in 0 until n - 1) {
            val ax = points[i * 2]
            val bx = points[i * 2 + 2]
            if (ax.isNaN() || bx.isNaN()) continue
            val d = segmentDistance(x, y, ax, points[i * 2 + 1], bx, points[i * 2 + 3])
            if (d < best) best = d
        }
        return best
    }

    /** Ray-casting point-in-polygon test for a flat closed polygon. */
    fun inPolygon(poly: FloatArray, x: Float, y: Float): Boolean {
        val n = poly.size / 2
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = poly[i * 2]
            val yi = poly[i * 2 + 1]
            val xj = poly[j * 2]
            val yj = poly[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** Arrow head wing length for a stroke width. */
    fun arrowHead(width: Float) = max(14f, width * 4f)

    fun shapeOutline(type: ShapeType, x1: Float, y1: Float, x2: Float, y2: Float, width: Float): FloatArray {
        val l = min(x1, x2)
        val r = max(x1, x2)
        val t = min(y1, y2)
        val b = max(y1, y2)
        return when (type) {
            ShapeType.LINE -> floatArrayOf(x1, y1, x2, y2)
            ShapeType.ARROW -> {
                val head = arrowHead(width)
                val angle = kotlin.math.atan2(y2 - y1, x2 - x1)
                val a1 = angle + Math.PI.toFloat() * 5 / 6
                val a2 = angle - Math.PI.toFloat() * 5 / 6
                floatArrayOf(
                    x1, y1, x2, y2,
                    x2 + head * cos(a1), y2 + head * sin(a1),
                    Float.NaN, Float.NaN,
                    x2, y2, x2 + head * cos(a2), y2 + head * sin(a2),
                )
            }
            ShapeType.RECT -> floatArrayOf(l, t, r, t, r, b, l, b, l, t)
            ShapeType.TRIANGLE -> floatArrayOf((l + r) / 2, t, r, b, l, b, (l + r) / 2, t)
            ShapeType.ELLIPSE -> {
                val steps = 48
                val cx = (l + r) / 2
                val cy = (t + b) / 2
                val rx = (r - l) / 2
                val ry = (b - t) / 2
                FloatArray((steps + 1) * 2) {
                    val a = (it / 2) * 2 * Math.PI / steps
                    if (it % 2 == 0) cx + rx * cos(a).toFloat() else cy + ry * sin(a).toFloat()
                }
            }
        }
    }

    /**
     * Removes the parts of an ink stroke within [radius] of the eraser path segment (ax,ay)-(bx,by).
     * Returns null if nothing was erased, otherwise the remaining pieces (possibly empty).
     */
    fun eraseInk(ink: InkItem, ax: Float, ay: Float, bx: Float, by: Float, radius: Float, newId: () -> Long): List<InkItem>? {
        val limit = radius + ink.width / 2
        val eraserBox = Box.of(ax, ay, bx, by).inset(limit)
        if (!eraserBox.intersects(ink.bounds)) return null
        // Densify long segments so a fast stroke still gets cut where the eraser crosses it.
        val dense = densify(ink.points, max(1f, limit / 2))
        val n = dense.size / 2
        val keep = BooleanArray(n) { segmentDistance(dense[it * 2], dense[it * 2 + 1], ax, ay, bx, by) > limit }
        if (keep.all { it }) return null
        val pieces = mutableListOf<InkItem>()
        var start = -1
        for (i in 0..n) {
            val kept = i < n && keep[i]
            if (kept && start < 0) start = i
            if (!kept && start >= 0) {
                // Drop one-point leftovers of a line; they would render as stray dots.
                if (i - start >= 2 || n == 1) {
                    val piece = dense.copyOfRange(start * 2, i * 2)
                    pieces += InkItem(if (pieces.isEmpty()) ink.id else newId(), ink.color, ink.width, piece, ink.highlighter)
                }
                start = -1
            }
        }
        return pieces
    }

    fun densify(points: FloatArray, step: Float): FloatArray {
        val n = points.size / 2
        if (n < 2) return points
        val out = ArrayList<Float>(points.size)
        out += points[0]; out += points[1]
        for (i in 1 until n) {
            val ax = points[i * 2 - 2]
            val ay = points[i * 2 - 1]
            val bx = points[i * 2]
            val by = points[i * 2 + 1]
            val len = kotlin.math.hypot(bx - ax, by - ay)
            val parts = (len / step).toInt()
            for (k in 1..parts) {
                val f = k.toFloat() / (parts + 1)
                out += ax + (bx - ax) * f
                out += ay + (by - ay) * f
            }
            out += bx; out += by
        }
        return out.toFloatArray()
    }
}
