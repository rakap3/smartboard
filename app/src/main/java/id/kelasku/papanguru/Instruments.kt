package id.kelasku.papanguru

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal enum class InstrumentType { RULER, SET_SQUARE, PROTRACTOR }

/**
 * A drawing instrument lying on top of the board, in screen space (it keeps its size when the
 * board zooms). Pen strokes that start near an edge are projected onto that edge, so the
 * teacher can draw straight lines and arcs. Geometry is defined in local coordinates around
 * (cx, cy), rotated by [angle] radians.
 */
internal class Instrument(val type: InstrumentType, private val density: Float, private val pxPerCm: Float, viewWidth: Float) {
    var cx = 0f
    var cy = 0f
    var angle = 0f

    /** Ruler length: up to 30 cm, at most 70% of the board width. */
    private val length = min(pxPerCm * 30f, viewWidth * 0.7f).coerceAtLeast(pxPerCm * 8)
    private val rulerHeight = 84 * density
    private val side = min(viewWidth * 0.32f, pxPerCm * 16).coerceAtLeast(160 * density)
    private val radius = min(viewWidth * 0.2f, pxPerCm * 10).coerceAtLeast(130 * density)

    // region Coordinates

    fun localX(sx: Float, sy: Float): Float {
        val dx = sx - cx
        val dy = sy - cy
        return dx * cos(-angle) - dy * sin(-angle)
    }

    fun localY(sx: Float, sy: Float): Float {
        val dx = sx - cx
        val dy = sy - cy
        return dx * sin(-angle) + dy * cos(-angle)
    }

    fun screenX(lx: Float, ly: Float) = cx + lx * cos(angle) - ly * sin(angle)
    fun screenY(lx: Float, ly: Float) = cy + lx * sin(angle) + ly * cos(angle)

    // endregion

    // region Shape

    /** Straight edges as local segments (x1, y1, x2, y2). */
    private val edges: List<FloatArray> = when (type) {
        InstrumentType.RULER -> listOf(
            floatArrayOf(-length / 2, -rulerHeight / 2, length / 2, -rulerHeight / 2),
            floatArrayOf(-length / 2, rulerHeight / 2, length / 2, rulerHeight / 2),
        )
        InstrumentType.SET_SQUARE -> {
            val h = side / 2
            // Right angle at the bottom-left corner.
            listOf(
                floatArrayOf(-h, h, h, h),
                floatArrayOf(-h, h, -h, -h),
                floatArrayOf(-h, -h, h, h),
            )
        }
        InstrumentType.PROTRACTOR -> listOf(floatArrayOf(-radius, 0f, radius, 0f))
    }

    fun contains(sx: Float, sy: Float): Boolean {
        val x = localX(sx, sy)
        val y = localY(sx, sy)
        return when (type) {
            InstrumentType.RULER -> abs(x) <= length / 2 && abs(y) <= rulerHeight / 2
            InstrumentType.SET_SQUARE -> {
                val h = side / 2
                x >= -h && y <= h && y >= x - 0.0001f
            }
            InstrumentType.PROTRACTOR -> y <= BASE_MARGIN * density && hypot(x, y) <= radius
        }
    }

    /** An edge lock found by [edgeNear]: a straight edge index, or [ARC] for the protractor arc. */
    class Snap(val instrument: Instrument, val edge: Int)

    /** The closest edge within [limit] screen px of the point, if any. */
    fun edgeNear(sx: Float, sy: Float, limit: Float): Snap? {
        val x = localX(sx, sy)
        val y = localY(sx, sy)
        var best = limit
        var bestEdge = -1
        edges.forEachIndexed { i, e ->
            val d = Geometry.segmentDistance(x, y, e[0], e[1], e[2], e[3])
            if (d <= best) {
                best = d
                bestEdge = i
            }
        }
        if (type == InstrumentType.PROTRACTOR && y <= 0f) {
            val d = abs(hypot(x, y) - radius)
            if (d <= best) bestEdge = ARC
        }
        return if (bestEdge == -1) null else Snap(this, bestEdge)
    }

    /** Projects a screen point onto a snapped edge; returns screen (x, y). */
    fun project(edge: Int, sx: Float, sy: Float, out: FloatArray) {
        val x = localX(sx, sy)
        val y = localY(sx, sy)
        val px: Float
        val py: Float
        if (edge == ARC) {
            var a = atan2(y, x)
            if (a > 0f) a = if (x >= 0f) 0f else -PI.toFloat()
            px = radius * cos(a)
            py = radius * sin(a)
        } else {
            val e = edges[edge]
            val dx = e[2] - e[0]
            val dy = e[3] - e[1]
            val len = dx * dx + dy * dy
            val t = if (len == 0f) 0f else (((x - e[0]) * dx + (y - e[1]) * dy) / len).coerceIn(0f, 1f)
            px = e[0] + t * dx
            py = e[1] + t * dy
        }
        out[0] = screenX(px, py)
        out[1] = screenY(px, py)
    }

    // endregion

    // region Controls

    private val closeLocal: FloatArray get() = when (type) {
        InstrumentType.RULER -> floatArrayOf(length / 2 - 22 * density, 0f)
        InstrumentType.SET_SQUARE -> floatArrayOf(-side / 2 + 26 * density, side / 2 - 26 * density)
        InstrumentType.PROTRACTOR -> floatArrayOf(radius - 30 * density, -18 * density)
    }

    private val rotateLocal: FloatArray get() = when (type) {
        InstrumentType.RULER -> floatArrayOf(-length / 2 + 22 * density, 0f)
        InstrumentType.SET_SQUARE -> floatArrayOf(side / 2 - 34 * density, side / 2 - 18 * density)
        InstrumentType.PROTRACTOR -> floatArrayOf(-radius + 30 * density, -18 * density)
    }

    fun hitsClose(sx: Float, sy: Float) = near(closeLocal, sx, sy)
    fun hitsRotate(sx: Float, sy: Float) = near(rotateLocal, sx, sy)

    private fun near(local: FloatArray, sx: Float, sy: Float) =
        hypot(screenX(local[0], local[1]) - sx, screenY(local[0], local[1]) - sy) <= CONTROL * density

    /** Angle of the rotate handle around the center, for single-finger rotation. */
    fun handleAngle(sx: Float, sy: Float) = atan2(sy - cy, sx - cx)
    fun rotateHandleBaseAngle(): Float = atan2(rotateLocal[1], rotateLocal[0])

    /** Sets the angle, snapping to multiples of 15° when within 2°. */
    fun setAngleSnapped(radians: Float) {
        val deg = Math.toDegrees(radians.toDouble())
        val nearest = (deg / 15).roundToInt() * 15.0
        angle = Math.toRadians(if (abs(deg - nearest) < 2) nearest else deg).toFloat()
    }

    /** The current angle in whole degrees, 0–359. */
    fun degrees(): Int = ((Math.toDegrees(angle.toDouble()).roundToInt() % 360) + 360) % 360

    // endregion

    // region Drawing

    /** Colors adapt to the board: light instruments on dark boards, dark ones on light boards. */
    private var body = BODY_DARK_BOARD
    private var edge = EDGE_DARK_BOARD

    fun draw(canvas: Canvas, fill: Paint, line: Paint, text: Paint, showAngle: Boolean, lightBoard: Boolean) {
        body = if (lightBoard) BODY_LIGHT_BOARD else BODY_DARK_BOARD
        edge = if (lightBoard) EDGE_LIGHT_BOARD else EDGE_DARK_BOARD
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(Math.toDegrees(angle.toDouble()).toFloat())
        fill.style = Paint.Style.FILL
        fill.color = body
        line.style = Paint.Style.STROKE
        line.color = edge
        line.strokeWidth = density * 1.2f
        text.color = edge
        text.textSize = 11 * density
        text.textAlign = Paint.Align.CENTER
        when (type) {
            InstrumentType.RULER -> drawRuler(canvas, fill, line, text)
            InstrumentType.SET_SQUARE -> drawSetSquare(canvas, fill, line, text)
            InstrumentType.PROTRACTOR -> drawProtractor(canvas, fill, line, text)
        }
        drawControl(canvas, closeLocal, fill, line, isClose = true)
        drawControl(canvas, rotateLocal, fill, line, isClose = false)
        canvas.restore()
        if (showAngle) {
            text.textSize = 15 * density
            text.color = 0xFFFFFFFF.toInt()
            fill.color = 0xCC1B1C1E.toInt()
            val label = "${degrees()}°"
            val w = text.measureText(label) + 16 * density
            canvas.drawRoundRect(RectF(cx - w / 2, cy - 14 * density, cx + w / 2, cy + 14 * density), 8 * density, 8 * density, fill)
            canvas.drawText(label, cx, cy + 5 * density, text)
        }
    }

    private fun drawRuler(canvas: Canvas, fill: Paint, line: Paint, text: Paint) {
        val l = -length / 2
        val t = -rulerHeight / 2
        canvas.drawRoundRect(RectF(l, t, -l, -t), 6 * density, 6 * density, fill)
        canvas.drawRoundRect(RectF(l, t, -l, -t), 6 * density, 6 * density, line)
        val mm = pxPerCm / 10
        val count = (length / mm).toInt()
        val start = l + (length - count * mm) / 2
        for (i in 0..count) {
            val x = start + i * mm
            val tick = when {
                i % 10 == 0 -> 18f
                i % 5 == 0 -> 12f
                else -> 7f
            } * density
            canvas.drawLine(x, t, x, t + tick, line)
            canvas.drawLine(x, -t, x, -t - tick * 0.6f, line)
            if (i % 10 == 0) canvas.drawText("${i / 10}", x, t + tick + 12 * density, text)
        }
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("cm", start + 4 * density, -t - 10 * density, text)
        text.textAlign = Paint.Align.CENTER
    }

    private fun drawSetSquare(canvas: Canvas, fill: Paint, line: Paint, text: Paint) {
        val h = side / 2
        val path = Path().apply {
            moveTo(-h, h)
            lineTo(h, h)
            lineTo(-h, -h)
            close()
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, line)
        val mm = pxPerCm / 10
        var i = 0
        while (i * mm <= side) {
            val tick = (if (i % 10 == 0) 14f else if (i % 5 == 0) 9f else 5f) * density
            canvas.drawLine(-h + i * mm, h, -h + i * mm, h - tick, line)
            canvas.drawLine(-h, h - i * mm, -h + tick, h - i * mm, line)
            if (i % 10 == 0 && i > 0) canvas.drawText("${i / 10}", -h + i * mm, h - tick - 4 * density, text)
            i++
        }
        // Right-angle mark.
        val m = 16 * density
        canvas.drawLine(-h + m, h, -h + m, h - m, line)
        canvas.drawLine(-h, h - m, -h + m, h - m, line)
        canvas.drawText("90°", -h + 30 * density, h - 30 * density, text)
        canvas.drawText("45°", h - 46 * density, h - 8 * density, text)
    }

    private fun drawProtractor(canvas: Canvas, fill: Paint, line: Paint, text: Paint) {
        val r = radius
        val base = BASE_MARGIN * density
        val body = Path().apply {
            addArc(RectF(-r, -r, r, r), 180f, 180f)
            lineTo(r, base)
            lineTo(-r, base)
            close()
        }
        canvas.drawPath(body, fill)
        canvas.drawPath(body, line)
        for (d in 0..180) {
            val a = (PI + d * PI / 180).toFloat()
            val tick = (if (d % 10 == 0) 16f else if (d % 5 == 0) 10f else 5f) * density
            val ca = cos(a)
            val sa = sin(a)
            canvas.drawLine(r * ca, r * sa, (r - tick) * ca, (r - tick) * sa, line)
            if (d % 10 == 0) {
                val lr = r - 28 * density
                canvas.drawText("$d", lr * ca, lr * sa + 4 * density, text)
                if (d in 10..170) {
                    val ir = r - 46 * density
                    text.textSize = 9 * density
                    canvas.drawText("${180 - d}", ir * ca, ir * sa + 3 * density, text)
                    text.textSize = 11 * density
                }
            }
        }
        canvas.drawLine(0f, 0f, 0f, -12 * density, line)
        canvas.drawCircle(0f, 0f, 3 * density, line)
    }

    private fun drawControl(canvas: Canvas, local: FloatArray, fill: Paint, line: Paint, isClose: Boolean) {
        val r = 13 * density
        fill.color = 0xCC1B1C1E.toInt()
        canvas.drawCircle(local[0], local[1], r, fill)
        line.color = 0xFFFFFFFF.toInt()
        line.strokeWidth = density * 1.8f
        val k = 5 * density
        if (isClose) {
            canvas.drawLine(local[0] - k, local[1] - k, local[0] + k, local[1] + k, line)
            canvas.drawLine(local[0] - k, local[1] + k, local[0] + k, local[1] - k, line)
        } else {
            canvas.drawArc(RectF(local[0] - k, local[1] - k, local[0] + k, local[1] + k), -60f, 280f, false, line)
            canvas.drawLine(local[0] + k * 0.5f, local[1] - k * 0.87f, local[0] + k * 1.2f, local[1] - k * 1.4f, line)
        }
        fill.color = body
        line.color = edge
        line.strokeWidth = density * 1.2f
    }

    // endregion

    companion object {
        const val ARC = 100
        private const val CONTROL = 22
        private const val BASE_MARGIN = 22
        private const val BODY_DARK_BOARD = 0x55D9E6F2
        private const val EDGE_DARK_BOARD = 0xFFEAF2FA.toInt()
        private const val BODY_LIGHT_BOARD = 0x3A3B7BC0
        private const val EDGE_LIGHT_BOARD = 0xFF22324A.toInt()

        /** Circle points for the compass, as a flat list in board coordinates. */
        fun arcPoints(cx: Float, cy: Float, r: Float, start: Float, sweep: Float, screenRadius: Float): FloatArray {
            val steps = max(12, (abs(sweep) * screenRadius / 6f).toInt()).coerceAtMost(720)
            return FloatArray((steps + 1) * 2) {
                val a = start + sweep * (it / 2) / steps
                if (it % 2 == 0) cx + r * cos(a) else cy + r * sin(a)
            }
        }
    }
}
