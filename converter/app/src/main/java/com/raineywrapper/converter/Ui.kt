package com.raineywrapper.converter

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * Lets a handheld's buttons drive the UI: A presses the focused control, B goes back.
 * The d-pad already moves focus natively.
 */
open class GamepadActivity : ComponentActivity() {
    /**
     * The screen's main button. In touch mode nothing has focus, so the first d-pad
     * press lands here and A presses it, instead of focus starting at the top of the screen.
     */
    protected var defaultAction: View? = null

    /** An invisible view holding focus so Android doesn't auto-focus a text field; treated as "nothing focused". */
    protected var focusHolder: View? = null

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val target = defaultAction
        val nothingFocused = currentFocus == null || currentFocus === focusHolder
        if (nothingFocused && target != null && target.isShown && target.isEnabled) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (event.action == KeyEvent.ACTION_UP) target.performClick()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        target.isFocusableInTouchMode = true
                        target.requestFocus()
                        target.isFocusableInTouchMode = false
                    }
                    return true
                }
            }
        }
        val mapped = when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_DPAD_CENTER
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BACK
            else -> return super.dispatchKeyEvent(event)
        }
        return super.dispatchKeyEvent(KeyEvent(event.downTime, event.eventTime, event.action, mapped, event.repeatCount))
    }
}

object Palette {
    const val TEXT = Color.WHITE
    val SUBTLE = Color.rgb(170, 170, 180)
    val ACCENT = Color.rgb(78, 123, 234)
    val SURFACE = Color.rgb(44, 44, 52)
    val ERROR = Color.rgb(255, 120, 110)
}

fun Context.dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

fun Context.label(text: String, sizeSp: Float = 16f, color: Int = Palette.TEXT) = TextView(this).apply {
    this.text = text
    setTextColor(color)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
}

/** Button with an obvious focused state, since handheld users navigate by d-pad. */
fun Context.button(text: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
    this.text = text
    isAllCaps = false
    setTextColor(Color.WHITE)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
    setPadding(dp(18), 0, dp(18), 0)
    val radius = dp(10).toFloat()
    background = StateListDrawable().apply {
        val focused = GradientDrawable().apply {
            setColor(if (primary) Color.rgb(110, 150, 255) else Color.rgb(80, 80, 96))
            setStroke(dp(3), Color.WHITE); cornerRadius = radius
        }
        val normal = GradientDrawable().apply { setColor(if (primary) Palette.ACCENT else Palette.SURFACE); cornerRadius = radius }
        val disabled = GradientDrawable().apply { setColor(Color.rgb(36, 36, 40)); cornerRadius = radius }
        addState(intArrayOf(-android.R.attr.state_enabled), disabled)
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(android.R.attr.state_pressed), focused)
        addState(intArrayOf(), normal)
    }
    setOnClickListener { onClick() }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.0f MB".format(bytes / (1L shl 20).toDouble())
    else -> "%.0f KB".format(bytes / 1024.0)
}
