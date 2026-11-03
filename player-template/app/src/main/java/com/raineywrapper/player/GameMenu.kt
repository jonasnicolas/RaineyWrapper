package com.raineywrapper.player

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * In-game pause menu. Built from plain views so the d-pad moves focus natively;
 * GameActivity translates gamepad A/B into click/close while it's open.
 */
class GameMenu(context: Context, title: String) : FrameLayout(context) {

    class Item(val label: () -> String, val enabled: () -> Boolean = { true }, val action: () -> Unit)

    private val list = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(24), dp(16), dp(24), dp(16))
        background = GradientDrawable().apply {
            setColor(Color.argb(235, 24, 24, 28))
            cornerRadius = dp(16).toFloat()
        }
    }
    private val buttons = mutableListOf<Pair<Button, Item>>()

    init {
        setBackgroundColor(Color.argb(150, 0, 0, 0))
        isClickable = true // swallow touches meant for the game underneath
        // Clickable views become focusable by default; focus belongs on the buttons,
        // otherwise the whole panel gets the grey focus highlight.
        isFocusable = false
        list.addView(TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        })
        // Scrolls when the menu is taller than the screen (landscape handhelds).
        val scroller = ScrollView(context).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = false
            addView(list)
        }
        addView(scroller, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            topMargin = dp(16)
            bottomMargin = dp(16)
        })
        visibility = GONE
    }

    fun setItems(items: List<Item>) {
        buttons.forEach { list.removeView(it.first) }
        buttons.clear()
        for (item in items) {
            val button = Button(context).apply {
                isAllCaps = false
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                background = buttonBackground()
                minWidth = dp(260)
                setOnClickListener { item.action() }
            }
            list.addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
                topMargin = dp(6)
            })
            buttons += button to item
        }
    }

    val isOpen get() = visibility == VISIBLE

    fun open() {
        refresh()
        visibility = VISIBLE
        buttons.firstOrNull { it.first.isEnabled }?.first?.requestFocus()
    }

    fun close() {
        visibility = GONE
    }

    fun refresh() {
        for ((button, item) in buttons) {
            button.text = item.label()
            button.isEnabled = item.enabled()
            button.alpha = if (button.isEnabled) 1f else 0.4f
        }
    }

    /** Clicks whichever button currently has focus (gamepad A). */
    fun clickFocused() {
        (findFocus() as? View)?.performClick()
    }

    private fun buttonBackground() = StateListDrawable().apply {
        val focused = GradientDrawable().apply { setColor(Color.rgb(70, 110, 220)); cornerRadius = dp(10).toFloat() }
        val normal = GradientDrawable().apply { setColor(Color.rgb(52, 52, 60)); cornerRadius = dp(10).toFloat() }
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(android.R.attr.state_pressed), focused)
        addState(intArrayOf(), normal)
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
}
