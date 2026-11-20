package id.kelasku.papanguru

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import java.io.File

/** Decodes board images on demand and keeps recently drawn ones in memory. */
internal class ImageCache(val dir: File) {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun get(file: String): Bitmap? {
        cache.get(file)?.let { return it }
        val bitmap = BitmapFactory.decodeFile(File(dir, file).path) ?: return null
        cache.put(file, bitmap)
        return bitmap
    }

    fun put(file: String, bitmap: Bitmap) {
        cache.put(file, bitmap)
    }
}

/**
 * Draws pages. One instance per thread: Paint objects are reused between calls.
 * [cachePaths] stores built Paths on items; only the UI-thread renderer should enable it.
 */
internal class BoardRenderer(private val density: Float, private val images: ImageCache?, private val cachePaths: Boolean) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val patternPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val rect = RectF()

    /**
     * Set by [drawBackground] from the page color. Pure white ink is drawn near-black on light
     * boards and the palette black is drawn white on dark boards, so content stays visible when
     * the background changes (the stored color is unchanged).
     */
    var lightBoard = false
        private set

    private fun ink(color: Int) = when {
        lightBoard && color == WHITE_INK -> BLACK_INK
        !lightBoard && color == BLACK_INK -> WHITE_INK
        else -> color
    }

    /** Draws the board color and pattern in screen space for a viewport. */
    fun drawBackground(
        canvas: Canvas, color: BoardColor, pattern: BoardPattern,
        originX: Float, originY: Float, scale: Float, width: Float, height: Float,
    ) {
        lightBoard = !color.dark
        canvas.drawColor(color.argb)
        if (pattern == BoardPattern.NONE) return
        patternPaint.color = patternColor(color)
        val minStep = 32f * density
        var step = 56f * scale * density
        while (step < minStep) step *= 2f
        while (step >= minStep * 2f) step /= 2f
        val firstX = (originX % step + step) % step
        val firstY = (originY % step + step) % step
        when (pattern) {
            BoardPattern.DOTS -> {
                var x = firstX
                while (x < width) {
                    var y = firstY
                    while (y < height) {
                        canvas.drawCircle(x, y, density * 0.8f, patternPaint)
                        y += step
                    }
                    x += step
                }
            }
            BoardPattern.GRID, BoardPattern.LINES -> {
                patternPaint.strokeWidth = density * 0.7f
                var y = firstY
                while (y < height) {
                    canvas.drawLine(0f, y, width, y, patternPaint)
                    y += step
                }
                if (pattern == BoardPattern.GRID) {
                    var x = firstX
                    while (x < width) {
                        canvas.drawLine(x, 0f, x, height, patternPaint)
                        x += step
                    }
                }
            }
            BoardPattern.NONE -> Unit
        }
    }

    private fun patternColor(color: BoardColor) = when (color) {
        BoardColor.BLACKBOARD -> 0xFF3C403B.toInt()
        BoardColor.GREEN -> 0xFF3A6150.toInt()
        BoardColor.WHITE -> 0xFFD3D7DD.toInt()
    }

    /** Draws items; the canvas must already map board coordinates. */
    fun drawItems(canvas: Canvas, items: List<BoardItem>) {
        for (item in items) drawItem(canvas, item)
    }

    fun drawItem(canvas: Canvas, item: BoardItem) {
        when (item) {
            is InkItem -> drawInk(canvas, item)
            is ShapeItem -> drawShape(canvas, item)
            is TextItem -> drawText(canvas, item)
            is ImageItem -> drawImage(canvas, item)
        }
    }

    private fun drawInk(canvas: Canvas, ink: InkItem) {
        paint.color = if (ink.highlighter) (ink.color and 0x00FFFFFF) or HIGHLIGHT_ALPHA else ink(ink.color)
        paint.strokeWidth = ink.width
        paint.strokeCap = if (ink.highlighter) Paint.Cap.SQUARE else Paint.Cap.ROUND
        if (ink.pointCount == 1) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(ink.points[0], ink.points[1], ink.width / 2, paint)
        } else {
            paint.style = Paint.Style.STROKE
            canvas.drawPath(pathFor(ink), paint)
        }
        paint.strokeCap = Paint.Cap.ROUND
    }

    private fun pathFor(ink: InkItem): Path {
        if (cachePaths) (ink.renderCache as? Path)?.let { return it }
        val path = smoothPath(ink.points)
        if (cachePaths) ink.renderCache = path
        return path
    }

    private fun drawShape(canvas: Canvas, shape: ShapeItem) {
        paint.color = ink(shape.color)
        paint.strokeWidth = shape.width
        paint.style = Paint.Style.STROKE
        if (shape.type == ShapeType.ELLIPSE) {
            rect.set(minOf(shape.x1, shape.x2), minOf(shape.y1, shape.y2), maxOf(shape.x1, shape.x2), maxOf(shape.y1, shape.y2))
            canvas.drawOval(rect, paint)
            return
        }
        val path = (if (cachePaths) shape.renderCache as? Path else null) ?: Path().also { path ->
            val o = shape.outline()
            var move = true
            for (i in 0 until o.size / 2) {
                val x = o[i * 2]
                val y = o[i * 2 + 1]
                if (x.isNaN()) {
                    move = true
                    continue
                }
                if (move) path.moveTo(x, y) else path.lineTo(x, y)
                move = false
            }
            if (shape.type == ShapeType.RECT || shape.type == ShapeType.TRIANGLE) path.close()
            if (cachePaths) shape.renderCache = path
        }
        canvas.drawPath(path, paint)
    }

    private fun drawText(canvas: Canvas, text: TextItem) {
        textPaint.color = ink(text.color)
        textPaint.textSize = text.size
        val lineHeight = text.size * LINE_HEIGHT
        text.text.split('\n').forEachIndexed { i, line ->
            canvas.drawText(line, text.x, text.y + text.size + i * lineHeight, textPaint)
        }
    }

    private fun drawImage(canvas: Canvas, image: ImageItem) {
        rect.set(image.x, image.y, image.x + image.w, image.y + image.h)
        val bitmap = images?.get(image.file)
        if (bitmap == null) {
            paint.style = Paint.Style.FILL
            paint.color = 0x33888888
            canvas.drawRect(rect, paint)
            return
        }
        canvas.drawBitmap(bitmap, null, rect, bitmapPaint)
    }

    /** Width and height of [text] drawn at [size], in the same units as [size]. */
    fun measureText(text: String, size: Float): Pair<Float, Float> {
        textPaint.textSize = size
        val lines = text.split('\n')
        val width = lines.maxOf { textPaint.measureText(it) }
        return width to (size * LINE_HEIGHT * (lines.size - 1) + size * 1.25f)
    }

    companion object {
        const val LINE_HEIGHT = 1.25f
        const val HIGHLIGHT_ALPHA = 0x66000000
        const val WHITE_INK = 0xFFFFFFFF.toInt()
        /** The palette's black. */
        const val BLACK_INK = 0xFF1B1C1E.toInt()

        /** Builds a Path through flat points, smoothing with quadratic curves between midpoints. */
        fun smoothPath(points: FloatArray): Path {
            val path = Path()
            val n = points.size / 2
            if (n == 0) return path
            path.moveTo(points[0], points[1])
            if (n == 2) {
                path.lineTo(points[2], points[3])
                return path
            }
            for (i in 1 until n - 1) {
                val x = points[i * 2]
                val y = points[i * 2 + 1]
                val mx = (x + points[i * 2 + 2]) / 2
                val my = (y + points[i * 2 + 3]) / 2
                path.quadTo(x, y, mx, my)
            }
            path.lineTo(points[n * 2 - 2], points[n * 2 - 1])
            return path
        }
    }
}
