package id.kelasku.papanguru

import android.annotation.SuppressLint
import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random

/**
 * A movable panel floating over the board (timer, name picker, dice, calculator). Drag it by the
 * title bar; it stays inside its parent.
 */
@SuppressLint("ViewConstructor")
internal open class FloatingCard(protected val activity: Activity, protected val ui: Ui, title: String, private val onClose: (FloatingCard) -> Unit) :
    LinearLayout(activity) {
    protected val body = LinearLayout(activity).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    init {
        orientation = VERTICAL
        background = ui.panel(18)
        elevation = ui.dp(12).toFloat()
        setPadding(ui.dp(10), ui.dp(4), ui.dp(10), ui.dp(12))
        val header = object : LinearLayout(activity) {
            private var lastX = 0f
            private var lastY = 0f

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        lastX = event.rawX
                        lastY = event.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        moveBy(event.rawX - lastX, event.rawY - lastY)
                        lastX = event.rawX
                        lastY = event.rawY
                    }
                }
                return true
            }
        }.apply {
            gravity = Gravity.CENTER_VERTICAL
            contentDescription = "$title, seret untuk memindahkan"
        }
        header.addView(ui.label(title, 15f, Ui.TEXT, bold = true).apply { setPadding(ui.dp(6), 0, 0, 0) }, LayoutParams(0, -2, 1f))
        header.addView(ui.iconButton("Tutup $title", R.drawable.ic_close).apply {
            setOnClickListener { close() }
        }, LayoutParams(ui.dp(44), ui.dp(44)))
        addView(header, LayoutParams(-1, -2))
        addView(body, LayoutParams(-1, -2))
    }

    /** Measure at natural size; the parent would otherwise clip the height and hide buttons. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        fitParent()
    }

    /**
     * Shrinks the card when the board area is smaller than the card (phones, split screens),
     * so every button stays reachable, then keeps it inside the board.
     */
    private fun fitParent() {
        val parent = parent as? View ?: return
        if (width == 0 || height == 0 || parent.height == 0) return
        val margin = ui.dp(8)
        val scale = minOf(1f, (parent.height - margin * 2f) / height, (parent.width - margin * 2f) / width).coerceAtLeast(0.4f)
        pivotX = width.toFloat()
        pivotY = 0f
        scaleX = scale
        scaleY = scale
        moveBy(0f, 0f)
    }

    fun moveBy(dx: Float, dy: Float) {
        val parent = parent as? View ?: return
        // Visual bounds: scaled around the top-right corner.
        val visualLeft = left + width * (1 - scaleX)
        val visualBottom = top + height * scaleY
        translationX = (translationX + dx).coerceIn(-visualLeft, (parent.width - right).toFloat().coerceAtLeast(-visualLeft))
        translationY = (translationY + dy).coerceIn(-top.toFloat(), (parent.height - visualBottom).coerceAtLeast(-top.toFloat()))
    }

    open fun close() {
        onClose(this)
    }

    protected fun row(vararg views: View, spacing: Int = 6): LinearLayout = LinearLayout(activity).apply {
        gravity = Gravity.CENTER
        views.forEachIndexed { i, v ->
            addView(v, LayoutParams(-2, ui.dp(44)).apply { if (i > 0) marginStart = ui.dp(spacing) })
        }
    }

    protected fun bigText(size: Float) = TextView(activity).apply {
        textSize = size
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}

/** Countdown timer with presets, and a stopwatch. Beeps and flashes when the countdown ends. */
@SuppressLint("ViewConstructor")
internal class TimerCard(activity: Activity, ui: Ui, onClose: (FloatingCard) -> Unit) : FloatingCard(activity, ui, "Timer", onClose) {
    private val handler = Handler(Looper.getMainLooper())
    private var stopwatch = false
    private var running = false
    private var durationMs = 5 * 60_000L
    /** Elapsed time before the current run started. */
    private var accumulated = 0L
    private var startedAt = 0L
    private var finished = false
    private val output = bigText(56f).apply { typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
    private val modeButton = ui.textButton("Ke stopwatch")
    private val startButton = ui.textButton("Mulai", primary = true)
    private val presets = LinearLayout(activity)
    private val tick = object : Runnable {
        override fun run() {
            update()
            if (running) handler.postDelayed(this, 100)
        }
    }

    init {
        body.addView(output, LayoutParams(ui.dp(300), -2).apply { topMargin = ui.dp(4) })
        listOf(1, 3, 5, 10, 15).forEach { m ->
            presets.addView(ui.textButton("$m mnt").apply {
                setOnClickListener { setDuration(m * 60_000L) }
            }, LayoutParams(-2, ui.dp(40)).apply { marginEnd = ui.dp(4) })
        }
        presets.addView(ui.textButton("−1").apply { contentDescription = "Kurangi 1 menit"; setOnClickListener { setDuration(durationMs - 60_000L) } },
            LayoutParams(-2, ui.dp(40)).apply { marginEnd = ui.dp(4) })
        presets.addView(ui.textButton("+1").apply { contentDescription = "Tambah 1 menit"; setOnClickListener { setDuration(durationMs + 60_000L) } },
            LayoutParams(-2, ui.dp(40)))
        body.addView(presets, LayoutParams(-2, -2).apply { topMargin = ui.dp(6) })
        startButton.setOnClickListener { if (running) pause() else start() }
        val reset = ui.textButton("Ulang").apply { setOnClickListener { reset() } }
        modeButton.setOnClickListener {
            stopwatch = !stopwatch
            modeButton.text = if (stopwatch) "Ke timer" else "Ke stopwatch"
            presets.visibility = if (stopwatch) View.GONE else View.VISIBLE
            reset()
        }
        body.addView(row(modeButton, reset, startButton, spacing = 8), LayoutParams(-2, -2).apply { topMargin = ui.dp(10) })
        update()
    }

    private fun elapsed() = accumulated + if (running) SystemClock.elapsedRealtime() - startedAt else 0L

    private fun setDuration(ms: Long) {
        durationMs = ms.coerceIn(60_000L, 99 * 60_000L)
        reset()
    }

    private fun start() {
        if (!stopwatch && elapsed() >= durationMs) reset()
        running = true
        finished = false
        startedAt = SystemClock.elapsedRealtime()
        startButton.text = "Jeda"
        handler.post(tick)
    }

    private fun pause() {
        accumulated = elapsed()
        running = false
        startButton.text = "Lanjut"
        update()
    }

    private fun reset() {
        running = false
        finished = false
        accumulated = 0L
        handler.removeCallbacks(tick)
        startButton.text = "Mulai"
        update()
    }

    private fun update() {
        val elapsed = elapsed()
        val shown = if (stopwatch) elapsed else max(0L, durationMs - elapsed)
        if (!stopwatch && running && elapsed >= durationMs) {
            running = false
            accumulated = durationMs
            finished = true
            startButton.text = "Mulai"
            alarm()
        }
        val totalSeconds = if (stopwatch) shown / 1000 else (shown + 999) / 1000
        val text = String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
        output.text = if (stopwatch) text + String.format(Locale.US, ".%d", (shown / 100) % 10) else text
        output.contentDescription = "Sisa waktu $text"
        val warn = !stopwatch && (finished || (running && shown <= 10_000L))
        output.setTextColor(if (warn) 0xFFFF6B6B.toInt() else Color.WHITE)
    }

    private fun alarm() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_ALARM, 90)
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 2500)
            handler.postDelayed({ tone.release() }, 3000)
        } catch (_: RuntimeException) {
            // No audio available; the red output still shows the end.
        }
        var flashes = 0
        handler.post(object : Runnable {
            override fun run() {
                output.alpha = if (flashes % 2 == 0) 0.25f else 1f
                if (++flashes < 10) handler.postDelayed(this, 300) else output.alpha = 1f
            }
        })
    }

    override fun close() {
        handler.removeCallbacksAndMessages(null)
        super.close()
    }
}

/** Picks a random student name, optionally without repeating until everyone was picked. */
@SuppressLint("ViewConstructor")
internal class NamePickerCard(activity: Activity, ui: Ui, private val prefs: SharedPreferences, private val edit: (String, (String) -> Unit) -> Unit, onClose: (FloatingCard) -> Unit) :
    FloatingCard(activity, ui, "Pengundi nama", onClose) {
    private val handler = Handler(Looper.getMainLooper())
    private val output = bigText(40f).apply { setPadding(ui.dp(8), ui.dp(16), ui.dp(8), ui.dp(16)) }
    private val status = ui.label("", 13f, Ui.MUTED).apply { gravity = Gravity.CENTER }
    private val noRepeat = ui.textButton("Tanpa ulang")
    private var names = load()
    private val picked = mutableSetOf<String>()
    private var rolling = false

    init {
        body.addView(output, LayoutParams(ui.dp(340), -2))
        body.addView(status, LayoutParams(-1, -2))
        noRepeat.isSelected = prefs.getBoolean("pickerNoRepeat", true)
        noRepeat.setOnClickListener {
            noRepeat.isSelected = !noRepeat.isSelected
            prefs.edit().putBoolean("pickerNoRepeat", noRepeat.isSelected).apply()
            picked.clear()
            showStatus()
        }
        val pick = ui.textButton("Acak nama", primary = true).apply { setOnClickListener { roll() } }
        val list = ui.textButton("Daftar nama").apply {
            setOnClickListener {
                edit(names.joinToString("\n")) { text ->
                    prefs.edit().putString("pickerNames", text).apply()
                    names = load()
                    picked.clear()
                    output.text = if (names.isEmpty()) "Belum ada nama" else "?"
                    showStatus()
                }
            }
        }
        body.addView(row(list, noRepeat, pick, spacing = 8), LayoutParams(-2, -2).apply { topMargin = ui.dp(8) })
        output.text = if (names.isEmpty()) "Belum ada nama" else "?"
        showStatus()
    }

    private fun load() = prefs.getString("pickerNames", "").orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun showStatus() {
        status.text = when {
            names.isEmpty() -> "Ketuk \"Daftar nama\" untuk menambah siswa"
            noRepeat.isSelected -> "${names.size - picked.size} dari ${names.size} belum terpilih"
            else -> "${names.size} nama"
        }
    }

    private fun roll() {
        if (rolling || names.isEmpty()) return
        if (noRepeat.isSelected && picked.size >= names.size) picked.clear()
        val pool = if (noRepeat.isSelected) names.filter { it !in picked } else names
        val winner = pool[Random.nextInt(pool.size)]
        rolling = true
        var step = 0
        handler.post(object : Runnable {
            override fun run() {
                if (step < 18) {
                    output.text = names[Random.nextInt(names.size)]
                    output.setTextColor(Ui.MUTED)
                    step++
                    handler.postDelayed(this, 40L + step * 8L)
                } else {
                    output.text = winner
                    output.setTextColor(Color.WHITE)
                    output.announceForAccessibility(winner)
                    if (noRepeat.isSelected) picked += winner
                    rolling = false
                    showStatus()
                }
            }
        })
    }

    override fun close() {
        handler.removeCallbacksAndMessages(null)
        super.close()
    }
}

/** One to four dice with a short roll animation. */
@SuppressLint("ViewConstructor")
internal class DiceCard(activity: Activity, ui: Ui, onClose: (FloatingCard) -> Unit) : FloatingCard(activity, ui, "Dadu", onClose) {
    private val handler = Handler(Looper.getMainLooper())
    private val dice = DiceView(activity, ui)
    private val total = ui.label("", 15f, Ui.TEXT).apply { gravity = Gravity.CENTER }
    private var rolling = false

    init {
        body.addView(dice, LayoutParams(ui.dp(360), ui.dp(110)).apply { topMargin = ui.dp(4) })
        body.addView(total, LayoutParams(-1, -2))
        val less = ui.textButton("−").apply { contentDescription = "Kurangi dadu"; setOnClickListener { setCount(dice.values.size - 1) } }
        val more = ui.textButton("+").apply { contentDescription = "Tambah dadu"; setOnClickListener { setCount(dice.values.size + 1) } }
        val roll = ui.textButton("Lempar dadu", primary = true).apply { setOnClickListener { roll() } }
        body.addView(row(less, more, roll, spacing = 8), LayoutParams(-2, -2).apply { topMargin = ui.dp(8) })
        setCount(2)
    }

    private fun setCount(n: Int) {
        if (rolling) return
        dice.values = IntArray(n.coerceIn(1, 4)) { 1 }
        total.text = "${dice.values.size} dadu"
    }

    private fun roll() {
        if (rolling) return
        rolling = true
        var step = 0
        handler.post(object : Runnable {
            override fun run() {
                dice.values = IntArray(dice.values.size) { Random.nextInt(1, 7) }
                if (++step < 10) {
                    handler.postDelayed(this, 60L + step * 10L)
                } else {
                    rolling = false
                    total.text = if (dice.values.size > 1) "Jumlah: ${dice.values.sum()}" else "Hasil: ${dice.values[0]}"
                    total.announceForAccessibility(total.text)
                }
            }
        })
    }

    override fun close() {
        handler.removeCallbacksAndMessages(null)
        super.close()
    }
}

@SuppressLint("ViewConstructor")
private class DiceView(activity: Activity, private val ui: Ui) : View(activity) {
    var values = intArrayOf(1, 1)
        set(value) {
            field = value
            contentDescription = "Dadu: " + value.joinToString(", ")
            invalidate()
        }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val size = minOf(height.toFloat(), (width - ui.dp(12) * (values.size - 1f)) / values.size)
        val totalWidth = size * values.size + ui.dp(12) * (values.size - 1)
        var x = (width - totalWidth) / 2
        val y = (height - size) / 2
        for (v in values) {
            paint.color = 0xFFF7F7F4.toInt()
            canvas.drawRoundRect(RectF(x, y, x + size, y + size), size * 0.16f, size * 0.16f, paint)
            paint.color = if (v == 1) 0xFFE03131.toInt() else 0xFF1B1C1E.toInt()
            val r = size * 0.09f
            for ((px, py) in PIPS[v - 1]) canvas.drawCircle(x + size * px, y + size * py, r, paint)
            x += size + ui.dp(12)
        }
    }

    companion object {
        private const val A = 0.27f
        private const val B = 0.5f
        private const val C = 0.73f
        private val PIPS = listOf(
            listOf(B to B),
            listOf(A to A, C to C),
            listOf(A to A, B to B, C to C),
            listOf(A to A, C to A, A to C, C to C),
            listOf(A to A, C to A, B to B, A to C, C to C),
            listOf(A to A, C to A, A to B, C to B, A to C, C to C),
        )
    }
}

/** A basic four-function calculator using decimal arithmetic (no binary rounding surprises). */
@SuppressLint("ViewConstructor")
internal class CalculatorCard(activity: Activity, ui: Ui, onClose: (FloatingCard) -> Unit) : FloatingCard(activity, ui, "Kalkulator", onClose) {
    private val calc = Calculator()
    private val expression = ui.label("", 14f, Ui.MUTED).apply { gravity = Gravity.END }
    private val output = bigText(36f).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }

    init {
        body.addView(expression, LayoutParams(ui.dp(300), -2).apply { topMargin = ui.dp(2) })
        body.addView(output, LayoutParams(ui.dp(300), -2))
        val keys = listOf(
            listOf("C", "⌫", "%", "÷"),
            listOf("7", "8", "9", "×"),
            listOf("4", "5", "6", "−"),
            listOf("1", "2", "3", "+"),
            listOf("±", "0", ",", "="),
        )
        for (rowKeys in keys) {
            val row = LinearLayout(activity)
            for (key in rowKeys) {
                row.addView(ui.textButton(key, primary = key == "=").apply {
                    textSize = 20f
                    contentDescription = when (key) {
                        "⌫" -> "Hapus satu angka"
                        "C" -> "Hapus semua"
                        "±" -> "Ganti tanda"
                        "÷" -> "Bagi"
                        "×" -> "Kali"
                        "−" -> "Kurang"
                        "+" -> "Tambah"
                        "=" -> "Sama dengan"
                        "," -> "Koma"
                        "%" -> "Persen"
                        else -> key
                    }
                    setOnClickListener {
                        calc.press(key)
                        show()
                    }
                }, LayoutParams(ui.dp(70), ui.dp(52)).apply { setMargins(ui.dp(2), ui.dp(2), ui.dp(2), ui.dp(2)) })
            }
            body.addView(row)
        }
        show()
    }

    private fun show() {
        output.text = calc.display
        expression.text = calc.expression
    }
}

/** Immediate-execution calculator state machine; pure Kotlin so it can be unit tested. */
internal class Calculator {
    var display = "0"
        private set
    var expression = ""
        private set
    private var stored: BigDecimal? = null
    private var operator: String? = null
    private var fresh = true

    fun press(key: String) {
        when (key) {
            in "0".."9" -> digit(key)
            "," -> if (fresh) {
                display = "0,"
                fresh = false
            } else if (',' !in display) display += ","
            "C" -> {
                display = "0"
                expression = ""
                stored = null
                operator = null
                fresh = true
            }
            "⌫" -> if (!fresh) {
                display = display.dropLast(1).ifEmpty { "0" }.let { if (it == "-") "0" else it }
                if (display == "0") fresh = true
            }
            "±" -> if (display != "0" && display != ERROR) display = if (display.startsWith("-")) display.drop(1) else "-$display"
            "%" -> value()?.let { show(it.divide(BigDecimal(100))) }
            "+", "−", "×", "÷" -> operator(key)
            "=" -> equals()
        }
    }

    private fun digit(d: String) {
        if (fresh || display == "0" || display == ERROR) {
            display = d
            fresh = d == "0"
        } else if (display.count { it.isDigit() } < 15) {
            display += d
        }
    }

    private fun operator(op: String) {
        val current = value() ?: return
        val left = stored
        val result = if (left != null && operator != null && !fresh) apply(left, operator!!, current) else current
        if (result == null) return error()
        stored = result
        operator = op
        show(result)
        expression = "${format(result)} $op"
        fresh = true
    }

    private fun equals() {
        val left = stored ?: return
        val op = operator ?: return
        val right = value() ?: return
        val result = apply(left, op, right) ?: return error()
        expression = "${format(left)} $op ${format(right)} ="
        stored = null
        operator = null
        show(result)
        fresh = true
    }

    private fun apply(a: BigDecimal, op: String, b: BigDecimal): BigDecimal? = when (op) {
        "+" -> a.add(b)
        "−" -> a.subtract(b)
        "×" -> a.multiply(b)
        "÷" -> if (b.signum() == 0) null else a.divide(b, MathContext(16, RoundingMode.HALF_EVEN))
        else -> b
    }

    private fun value(): BigDecimal? = display.replace(',', '.').toBigDecimalOrNull()

    private fun show(v: BigDecimal) {
        display = format(v)
    }

    private fun error() {
        display = ERROR
        expression = ""
        stored = null
        operator = null
        fresh = true
    }

    private fun format(v: BigDecimal): String {
        val rounded = v.round(MathContext(12, RoundingMode.HALF_EVEN)).stripTrailingZeros()
        val plain = if (rounded.abs() >= BigDecimal("1e12") || (rounded.signum() != 0 && rounded.abs() < BigDecimal("1e-9"))) {
            rounded.toString()
        } else {
            rounded.toPlainString()
        }
        return plain.replace('.', ',')
    }

    companion object {
        const val ERROR = "Tidak bisa dibagi 0"
    }
}

/**
 * Curtain covering part of the board, pulled from the top (or the left) to reveal content step by
 * step. Touches outside the covered part go through to the board.
 */
@SuppressLint("ViewConstructor")
internal class CurtainView(activity: Activity, private val ui: Ui, private val onClose: () -> Unit) : FrameLayout(activity) {
    private var horizontal = false
    /** Covered fraction of the height (or width). */
    private var cover = 0.6f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var last = 0f
    private val controls = LinearLayout(activity).apply {
        background = ui.panel(14)
        addView(ui.iconButton("Ubah arah tirai", R.drawable.ic_curtain).apply {
            setOnClickListener {
                horizontal = !horizontal
                layoutControls()
                invalidate()
            }
        }, LayoutParams(ui.dp(44), ui.dp(44)))
        addView(ui.iconButton("Tutup tirai", R.drawable.ic_close).apply { setOnClickListener { onClose() } }, LayoutParams(ui.dp(44), ui.dp(44)))
    }

    init {
        setWillNotDraw(false)
        contentDescription = "Tirai, seret untuk membuka atau menutup sebagian papan"
        addView(controls, LayoutParams(-2, -2))
    }

    private fun edge() = if (horizontal) width * cover else height * cover

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        layoutControls()
    }

    private fun layoutControls() {
        val e = edge()
        val w = controls.width.toFloat()
        val h = controls.height.toFloat()
        if (horizontal) {
            controls.x = (e - w - ui.dp(14)).coerceAtLeast(ui.dp(6).toFloat())
            controls.y = ui.dp(10).toFloat()
        } else {
            controls.x = (width - w) / 2
            controls.y = (e - h - ui.dp(18)).coerceAtLeast(ui.dp(6).toFloat())
        }
    }

    override fun onDraw(canvas: Canvas) {
        val e = edge()
        paint.color = 0xFF2B2D33.toInt()
        if (horizontal) canvas.drawRect(0f, 0f, e, height.toFloat(), paint) else canvas.drawRect(0f, 0f, width.toFloat(), e, paint)
        // Fabric folds.
        paint.color = 0xFF33363D.toInt()
        val step = ui.dp(28).toFloat()
        var p = step / 2
        while (p < (if (horizontal) height else width)) {
            if (horizontal) canvas.drawRect(0f, p, e, p + step / 3, paint) else canvas.drawRect(p, 0f, p + step / 3, e, paint)
            p += step
        }
        // Pull handle along the edge.
        paint.color = Ui.ACCENT
        val g = ui.dp(4).toFloat()
        val len = ui.dp(64).toFloat()
        if (horizontal) {
            canvas.drawRect(e - g, 0f, e, height.toFloat(), paint)
            canvas.drawRoundRect(RectF(e - ui.dp(10), height / 2 - len / 2, e + ui.dp(10), height / 2 + len / 2), g * 2, g * 2, paint)
        } else {
            canvas.drawRect(0f, e - g, width.toFloat(), e, paint)
            canvas.drawRoundRect(RectF(width / 2 - len / 2, e - ui.dp(10), width / 2 + len / 2, e + ui.dp(10)), g * 2, g * 2, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val pos = if (horizontal) event.x else event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Only the covered part (plus the handle) catches touches.
                if (pos > edge() + ui.dp(14)) return false
                last = pos
            }
            MotionEvent.ACTION_MOVE -> {
                val size = (if (horizontal) width else height).toFloat()
                cover = (cover + (pos - last) / size).coerceIn(0.04f, 1f)
                last = pos
                layoutControls()
                invalidate()
            }
        }
        return true
    }
}

/**
 * Spotlight: darkens the board except a movable circle. Touches inside the circle reach the board
 * (the teacher can keep writing); dragging the dark area moves the light, the ring knob resizes it.
 */
@SuppressLint("ViewConstructor")
internal class SpotlightView(activity: Activity, private val ui: Ui, onClose: () -> Unit) : FrameLayout(activity) {
    private var cx = Float.NaN
    private var cy = 0f
    private var radius = ui.dp(150).toFloat()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private enum class Drag { NONE, MOVE, RESIZE }
    private var drag = Drag.NONE
    private var lastX = 0f
    private var lastY = 0f

    init {
        setWillNotDraw(false)
        contentDescription = "Lampu sorot, seret area gelap untuk memindahkan"
        addView(ui.iconButton("Matikan lampu sorot", R.drawable.ic_close).apply {
            background = ui.panel(14)
            setOnClickListener { onClose() }
        }, LayoutParams(ui.dp(44), ui.dp(44), Gravity.TOP or Gravity.END).apply { setMargins(0, ui.dp(10), ui.dp(10), 0) })
    }

    private fun knobX() = cx + radius * 0.7071f
    private fun knobY() = cy + radius * 0.7071f

    override fun onDraw(canvas: Canvas) {
        if (cx.isNaN()) {
            cx = width / 2f
            cy = height / 2f
        }
        path.reset()
        path.fillType = Path.FillType.EVEN_ODD
        path.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        path.addCircle(cx, cy, radius, Path.Direction.CW)
        paint.style = Paint.Style.FILL
        paint.color = 0xE6000000.toInt()
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = ui.dp(2).toFloat()
        paint.color = 0x88FFFFFF.toInt()
        canvas.drawCircle(cx, cy, radius, paint)
        paint.style = Paint.Style.FILL
        paint.color = Ui.ACCENT
        canvas.drawCircle(knobX(), knobY(), ui.dp(12).toFloat(), paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = when {
                    hypot(x - knobX(), y - knobY()) <= ui.dp(26) -> Drag.RESIZE
                    hypot(x - cx, y - cy) < radius -> return false
                    else -> Drag.MOVE
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag == Drag.MOVE) {
                    cx = (cx + x - lastX).coerceIn(0f, width.toFloat())
                    cy = (cy + y - lastY).coerceIn(0f, height.toFloat())
                } else if (drag == Drag.RESIZE) {
                    radius = (hypot(x - cx, y - cy) / 1f).coerceIn(ui.dp(50).toFloat(), max(width, height).toFloat())
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = Drag.NONE
        }
        lastX = x
        lastY = y
        return true
    }
}
