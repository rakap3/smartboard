package id.kelasku.papanguru

internal class DoubleTap(private val touchSlop: Float, private val doubleTapSlop: Float, private val timeout: Int) {
    private var startX = 0f
    private var startY = 0f
    private var downTime = 0L
    private var previous: Tap? = null
    var still = false
        private set

    fun down(x: Float, y: Float, time: Long, finger: Boolean) {
        startX = x
        startY = y
        downTime = time
        still = finger
    }

    fun move(x: Float, y: Float) {
        if (!near(startX, startY, x, y, touchSlop)) still = false
    }

    fun up(time: Long): Result {
        if (!still || time - downTime > 220) {
            cancel()
            return Result.NONE
        }
        val first = previous
        if (first != null && downTime - first.time in 0L..timeout.toLong() &&
            near(first.x, first.y, startX, startY, doubleTapSlop)
        ) {
            cancel()
            return Result.DOUBLE
        }
        previous = Tap(startX, startY, time)
        return Result.SINGLE
    }

    fun cancel() {
        previous = null
        still = false
    }

    private fun near(x1: Float, y1: Float, x2: Float, y2: Float, limit: Float): Boolean {
        val dx = x1 - x2
        val dy = y1 - y2
        return dx * dx + dy * dy <= limit * limit
    }

    private data class Tap(val x: Float, val y: Float, val time: Long)
    enum class Result { NONE, SINGLE, DOUBLE }
}
