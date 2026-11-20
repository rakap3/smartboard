package id.kelasku.papanguru

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** Shared look for the board UI, built in code (the app has no XML layouts or AndroidX). */
internal class Ui(private val activity: Activity) {
    private val density = activity.resources.displayMetrics.density

    fun dp(value: Number) = (value.toFloat() * density).roundToInt()

    fun surface(top: Int, bottom: Int, radius: Int, edge: Int) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), edge)
        }

    fun panel(radius: Int = 16) = surface(0xFF494B55.toInt(), 0xFF242629.toInt(), radius, 0xFF9195A0.toInt())

    fun iconButton(label: String, icon: Int, tint: Int = TEXT) = ImageButton(activity).apply {
        contentDescription = label
        if (Build.VERSION.SDK_INT >= 26) tooltipText = label
        setImageResource(icon)
        setColorFilter(tint)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = buttonStates(transparentIdle = true)
    }

    fun buttonStates(transparentIdle: Boolean, primary: Boolean = false, danger: Boolean = false) = StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled), surface(Color.TRANSPARENT, Color.TRANSPARENT, 12, Color.TRANSPARENT))
        addState(intArrayOf(android.R.attr.state_pressed), surface(0xFF777982.toInt(), 0xFF4A4C54.toInt(), 12, 0xFFA5A7AF.toInt()))
        addState(intArrayOf(android.R.attr.state_focused), surface(0xFF5D6472.toInt(), 0xFF383F4A.toInt(), 12, 0xFFADD9FF.toInt()))
        addState(intArrayOf(android.R.attr.state_selected), surface(0xFF626978.toInt(), 0xFF393F4C.toInt(), 12, 0xFF8C9DB8.toInt()))
        addState(intArrayOf(), when {
            danger -> surface(0xFF8A2F35.toInt(), 0xFF64212A.toInt(), 12, 0xFFFF8787.toInt())
            primary -> surface(0xFF315D81.toInt(), 0xFF244461.toInt(), 12, 0xFF82CFFF.toInt())
            transparentIdle -> surface(Color.TRANSPARENT, Color.TRANSPARENT, 12, Color.TRANSPARENT)
            else -> surface(0xFF41444C.toInt(), 0xFF2C2E33.toInt(), 12, 0xFF666972.toInt())
        })
    }

    fun textButton(label: String, primary: Boolean = false, danger: Boolean = false) = Button(activity).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTextColor(Color.WHITE)
        setPadding(dp(14), 0, dp(14), 0)
        minimumWidth = 0
        minWidth = 0
        minimumHeight = dp(44)
        minHeight = dp(44)
        background = buttonStates(transparentIdle = false, primary = primary, danger = danger)
    }

    /** Text field in the app's dark style (replaces the platform's light underline field). */
    fun input(text: String, hint: String, lines: Int = 1, capWords: Boolean = false) = EditText(activity).apply {
        setText(text)
        this.hint = hint
        textSize = 16f
        setTextColor(Color.WHITE)
        setHintTextColor(MUTED)
        highlightColor = 0x6682CFFF
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), surface(0xFF1B1D21.toInt(), 0xFF1B1D21.toInt(), 10, ACCENT))
            addState(intArrayOf(), surface(0xFF1B1D21.toInt(), 0xFF1B1D21.toInt(), 10, 0xFF5A5D66.toInt()))
        }
        setPadding(dp(12), dp(10), dp(12), dp(10))
        val caps = if (capWords) InputType.TYPE_TEXT_FLAG_CAP_WORDS else InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        if (lines > 1) {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or caps
            minLines = lines
            maxLines = lines * 2
            gravity = Gravity.TOP or Gravity.START
        } else {
            inputType = InputType.TYPE_CLASS_TEXT or caps
            setSingleLine()
        }
        // In landscape the keyboard otherwise switches to a full-screen (light) editor that hides the dialog.
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        if (Build.VERSION.SDK_INT >= 29) textCursorDrawable = GradientDrawable().apply {
            setColor(ACCENT)
            setSize(dp(2), dp(20))
        }
        setSelection(this.text.length)
    }

    fun label(text: String, size: Float = 14f, color: Int = TEXT, bold: Boolean = false) = TextView(activity).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    fun divider(parent: LinearLayout) {
        parent.addView(View(activity).apply {
            setBackgroundColor(0xFF6B6C72.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(1), dp(22)).apply {
            marginStart = dp(4)
            marginEnd = dp(4)
        })
    }

    fun setEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.38f
    }

    /** A focusable popup with the app's panel style, dismissed by touching outside. */
    fun popup(content: View): PopupWindow {
        content.background = panel()
        content.elevation = dp(10).toFloat()
        return PopupWindow(content, -2, -2, true).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            elevation = dp(10).toFloat()
        }
    }

    /** Shows [popup] above [anchor] (the toolbar sits at the bottom of the screen). */
    fun showAbove(popup: PopupWindow, anchor: View) {
        val content = popup.contentView
        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        // Always attach to the activity window: an anchor inside another popup (the quick menu)
        // has a window token that can't host a popup, which throws BadTokenException.
        val decor = activity.window.decorView
        val anchorAt = IntArray(2)
        val decorAt = IntArray(2)
        anchor.getLocationOnScreen(anchorAt)
        decor.getLocationOnScreen(decorAt)
        val ax = anchorAt[0] - decorAt[0]
        val ay = anchorAt[1] - decorAt[1]
        val x = (ax + anchor.width / 2 - content.measuredWidth / 2).coerceIn(dp(8), (decor.width - content.measuredWidth - dp(8)).coerceAtLeast(dp(8)))
        val y = (ay - content.measuredHeight - dp(8)).coerceAtLeast(dp(8))
        popup.showAtLocation(decor, Gravity.TOP or Gravity.START, x, y)
    }

    class MenuEntry(val label: String, val icon: Int, val checked: Boolean = false, val enabled: Boolean = true, val action: () -> Unit)

    /** Vertical menu popup with icons above [anchor]. */
    fun menu(anchor: View, entries: List<MenuEntry>) {
        val list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val scroll = ScrollView(activity).apply { addView(list) }
        val popup = popup(scroll)
        for (entry in entries) {
            val row = LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), 0, dp(14), 0)
                background = buttonStates(transparentIdle = true)
                isClickable = true
                isFocusable = true
                contentDescription = entry.label
                setOnClickListener {
                    popup.dismiss()
                    entry.action()
                }
            }
            row.addView(ImageView(activity).apply {
                setImageResource(entry.icon)
                setColorFilter(TEXT)
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            row.addView(label(entry.label).apply { setPadding(dp(12), 0, dp(12), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            if (entry.checked) {
                row.addView(ImageView(activity).apply {
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(ACCENT)
                }, LinearLayout.LayoutParams(dp(20), dp(20)))
            }
            setEnabled(row, entry.enabled)
            list.addView(row, LinearLayout.LayoutParams(dp(280), dp(48)))
        }
        val maxHeight = (activity.window.decorView.height * 0.8f).toInt()
        scroll.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        if (scroll.measuredHeight > maxHeight) popup.height = maxHeight
        showAbove(popup, anchor)
    }

    @Suppress("DEPRECATION")
    fun hideSystemBars(target: Window = activity.window) {
        if (Build.VERSION.SDK_INT >= 30) {
            target.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            }
        } else {
            target.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    companion object {
        const val TEXT = 0xFFF1F2F5.toInt()
        const val MUTED = 0xFFB4B7C0.toInt()
        const val ACCENT = 0xFF82CFFF.toInt()
        const val BACKGROUND = 0xFF16171A.toInt()
    }
}
