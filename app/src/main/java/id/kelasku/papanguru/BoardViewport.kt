package id.kelasku.papanguru

/** Screen↔board transform: screen = board * scale + (x, y). */
internal class BoardViewport {
    var scale = 1f
        private set
    var x = 0f
        private set
    var y = 0f
        private set
    var viewWidth = 0f
        private set
    var viewHeight = 0f
        private set

    fun resize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewWidth = width.toFloat()
        viewHeight = height.toFloat()
    }

    fun boardX(screenX: Float) = (screenX - x) / scale
    fun boardY(screenY: Float) = (screenY - y) / scale
    fun screenX(boardX: Float) = boardX * scale + x
    fun screenY(boardY: Float) = boardY * scale + y

    fun transform(fromX: Float, fromY: Float, toX: Float, toY: Float, factor: Float = 1f) {
        val nextScale = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
        val ratio = nextScale / scale
        x = toX + (x - fromX) * ratio
        y = toY + (y - fromY) * ratio
        scale = nextScale
    }

    fun zoom(factor: Float) {
        transform(viewWidth / 2, viewHeight / 2, viewWidth / 2, viewHeight / 2, factor)
    }

    fun reset() = set(1f, 0f, 0f)

    fun set(scale: Float, x: Float, y: Float) {
        this.scale = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        this.x = x
        this.y = y
    }

    /** Fits a board-space box into the view with a margin, never zooming in past 100%. */
    fun fit(box: Box, margin: Float) {
        if (viewWidth <= 0f || box.isEmpty) return reset()
        val s = minOf((viewWidth - 2 * margin) / box.width, (viewHeight - 2 * margin) / box.height, 1f)
        set(s, viewWidth / 2 - box.centerX * s, viewHeight / 2 - box.centerY * s)
    }

    /** The board area visible at the initial view (100% zoom at the origin). */
    fun homeFrame() = Box(0f, 0f, viewWidth, viewHeight)

    fun visibleBox() = Box(boardX(0f), boardY(0f), boardX(viewWidth), boardY(viewHeight))

    companion object {
        const val MIN_SCALE = 0.01f
        const val MAX_SCALE = 100f
    }
}
