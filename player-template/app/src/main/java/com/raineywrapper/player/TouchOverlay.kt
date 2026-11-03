package com.raineywrapper.player

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Minimal on-screen controller: a d-pad on the left, face buttons on the right,
 * shoulders in the top corners, Select/Start/Menu along the bottom middle.
 * Multi-touch; each finger can press one control and slide between them.
 *
 * Keys are sent as RetroPad buttons (KEYCODE_BUTTON_A = RetroPad A, the right face button).
 */
class TouchOverlay(
    context: Context,
    private val layout: Layout,
    private val onKey: (action: Int, keyCode: Int) -> Unit,
    /** Left analog stick, -1..1; used instead of d-pad keys when [Layout.analogDpad] is set. */
    private val onAnalog: (x: Float, y: Float) -> Unit,
    private val onMenu: () -> Unit,
) : View(context) {

    data class Face(val label: String, val keyCode: Int, /** position on a unit circle, (0,0) = centre */ val x: Float, val y: Float)

    data class Layout(
        val face: List<Face>,
        val shoulders: List<Pair<String, Int>>,
        val selectLabel: String = "SELECT",
        /** For 3D systems the on-screen d-pad drives the analog stick, which those games are played with. */
        val analogDpad: Boolean = false,
    )

    companion object {
        private const val A = KeyEvent.KEYCODE_BUTTON_A
        private const val B = KeyEvent.KEYCODE_BUTTON_B
        private const val X = KeyEvent.KEYCODE_BUTTON_X
        private const val Y = KeyEvent.KEYCODE_BUTTON_Y
        private const val L = KeyEvent.KEYCODE_BUTTON_L1
        private const val R = KeyEvent.KEYCODE_BUTTON_R1
        private const val L2 = KeyEvent.KEYCODE_BUTTON_L2
        private const val R2 = KeyEvent.KEYCODE_BUTTON_R2
        private const val MENU = -1

        fun layoutFor(system: String): Layout = when (system) {
            "gba" -> Layout(listOf(Face("B", B, -0.45f, 0.25f), Face("A", A, 0.45f, -0.25f)), listOf("L" to L, "R" to R))
            "gb", "sms", "gg" -> Layout(listOf(Face(if (system == "gb") "B" else "1", B, -0.45f, 0.25f), Face(if (system == "gb") "A" else "2", A, 0.45f, -0.25f)), emptyList())
            // Genesis Plus GX: RetroPad Y/B/A = Genesis A/B/C, Select = Mode.
            "genesis" -> Layout(listOf(Face("A", Y, -0.6f, 0.2f), Face("B", B, 0f, 0f), Face("C", A, 0.6f, -0.2f)), emptyList(), selectLabel = "MODE")
            // Mupen64Plus-Next: RetroPad B/Y = N64 A/B, L2 = Z. C-buttons are on the right stick.
            "n64" -> Layout(
                listOf(Face("A", B, 0.1f, 0.35f), Face("B", Y, -0.5f, -0.15f)),
                listOf("L" to L, "R" to R, "Z" to L2),
                analogDpad = true,
            )
            // Flycast: RetroPad B/A/Y/X = Dreamcast A/B/X/Y, L2/R2 = triggers.
            "dreamcast" -> Layout(
                listOf(Face("Y", X, 0f, -0.55f), Face("B", A, 0.55f, 0f), Face("A", B, 0f, 0.55f), Face("X", Y, -0.55f, 0f)),
                listOf("L" to L2, "R" to R2),
                analogDpad = true,
            )
            // Dolphin: RetroPad A/B/X/Y = GameCube A/B/X/Y, L2/R2 = triggers, R1 = Z.
            "gamecube" -> Layout(
                listOf(Face("A", A, 0f, 0.1f), Face("B", B, -0.55f, 0.45f), Face("X", X, 0.6f, -0.2f), Face("Y", Y, -0.2f, -0.6f)),
                listOf("L" to L2, "R" to R2, "Z" to R),
                analogDpad = true,
            )
            "psp" -> Layout(
                listOf(Face("△", X, 0f, -0.55f), Face("○", A, 0.55f, 0f), Face("✕", B, 0f, 0.55f), Face("□", Y, -0.55f, 0f)),
                listOf("L" to L, "R" to R),
            )
            "psx" -> Layout(
                listOf(Face("△", X, 0f, -0.55f), Face("○", A, 0.55f, 0f), Face("✕", B, 0f, 0.55f), Face("□", Y, -0.55f, 0f)),
                listOf("L1" to L, "R1" to R, "L2" to L2, "R2" to R2),
            )
            else -> Layout( // SNES and anything else with a 4-button diamond
                listOf(Face("X", X, 0f, -0.55f), Face("A", A, 0.55f, 0f), Face("B", B, 0f, 0.55f), Face("Y", Y, -0.55f, 0f)),
                listOf("L" to L, "R" to R),
            )
        }
    }

    private class Control(val label: String, val keyCode: Int, val rect: RectF, val round: Boolean)

    private val controls = mutableListOf<Control>()
    private var dpadCx = 0f
    private var dpadCy = 0f
    private var dpadR = 0f

    /** pointerId -> keys that pointer is holding (a d-pad diagonal holds two). */
    private val held = mutableMapOf<Int, Set<Int>>()
    private val pressedKeys = mutableMapOf<Int, Int>() // keyCode -> number of pointers holding it
    private var analogX = 0f
    private var analogY = 0f

    private val dpadKeys = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
    )

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) }
    private val fillPressed = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(140, 255, 255, 255) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 255, 255); textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        controls.clear()
        val unit = min(w, h) / 10f
        val margin = unit * 0.4f

        dpadR = unit * 1.7f
        dpadCx = margin + dpadR
        dpadCy = h - margin - dpadR - unit * 0.3f

        val faceR = unit * 0.62f
        val faceCx = w - margin - dpadR
        val faceCy = dpadCy
        for (f in layout.face) {
            val cx = faceCx + f.x * dpadR * 1.15f
            val cy = faceCy + f.y * dpadR * 1.15f
            controls += Control(f.label, f.keyCode, RectF(cx - faceR, cy - faceR, cx + faceR, cy + faceR), true)
        }

        val sw = unit * 1.9f
        val sh = unit * 0.85f
        layout.shoulders.forEachIndexed { i, (label, key) ->
            val left = i % 2 == 0
            val row = i / 2
            val x = if (left) margin else w - margin - sw
            val y = margin + row * (sh + unit * 0.25f)
            controls += Control(label, key, RectF(x, y, x + sw, y + sh), false)
        }

        val pw = unit * 1.5f
        val ph = unit * 0.6f
        val by = h - margin - ph
        controls += Control(layout.selectLabel, KeyEvent.KEYCODE_BUTTON_SELECT, RectF(w / 2f - pw * 1.6f, by, w / 2f - pw * 0.6f, by + ph), false)
        controls += Control("START", KeyEvent.KEYCODE_BUTTON_START, RectF(w / 2f + pw * 0.6f, by, w / 2f + pw * 1.6f, by + ph), false)
        controls += Control("☰", MENU, RectF(w / 2f - ph, margin, w / 2f + ph, margin + ph * 1.4f), false)
    }

    override fun onDraw(canvas: Canvas) {
        // D-pad: ring with four arrows.
        canvas.drawCircle(dpadCx, dpadCy, dpadR, fill)
        canvas.drawCircle(dpadCx, dpadCy, dpadR, stroke)
        text.textSize = dpadR * 0.32f
        val o = dpadR * 0.62f
        val dirs = listOf(
            Triple("▲", 0f, -o) to KeyEvent.KEYCODE_DPAD_UP, Triple("▼", 0f, o) to KeyEvent.KEYCODE_DPAD_DOWN,
            Triple("◀", -o, 0f) to KeyEvent.KEYCODE_DPAD_LEFT, Triple("▶", o, 0f) to KeyEvent.KEYCODE_DPAD_RIGHT,
        )
        for ((t, key) in dirs) {
            val (label, dx, dy) = t
            if (isPressed(key)) canvas.drawCircle(dpadCx + dx, dpadCy + dy, dpadR * 0.3f, fillPressed)
            canvas.drawText(label, dpadCx + dx, dpadCy + dy + text.textSize / 3, text)
        }

        for (c in controls) {
            val paint = if (isPressed(c.keyCode)) fillPressed else fill
            if (c.round) {
                canvas.drawOval(c.rect, paint)
                canvas.drawOval(c.rect, stroke)
            } else {
                val r = c.rect.height() / 2
                canvas.drawRoundRect(c.rect, r, r, paint)
                canvas.drawRoundRect(c.rect, r, r, stroke)
            }
            text.textSize = c.rect.height() * if (c.round) 0.45f else 0.42f
            canvas.drawText(c.label, c.rect.centerX(), c.rect.centerY() + text.textSize / 3, text)
        }
    }

    private fun isPressed(key: Int) = (pressedKeys[key] ?: 0) > 0

    /** Keys under a touch point. The d-pad has a generous radius and supports diagonals. */
    private fun keysAt(x: Float, y: Float): Set<Int> {
        val dx = x - dpadCx
        val dy = y - dpadCy
        val dist = hypot(dx, dy)
        if (dist <= dpadR * 1.25f && dist > dpadR * 0.15f) {
            val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) // 0 = right, 90 = down
            val keys = mutableSetOf<Int>()
            if (angle in -67.5..67.5) keys += KeyEvent.KEYCODE_DPAD_RIGHT
            if (angle >= 112.5 || angle <= -112.5) keys += KeyEvent.KEYCODE_DPAD_LEFT
            if (angle in 22.5..157.5) keys += KeyEvent.KEYCODE_DPAD_DOWN
            if (angle in -157.5..-22.5) keys += KeyEvent.KEYCODE_DPAD_UP
            return keys
        }
        // Slightly enlarged hit boxes make the buttons easier to hit without looking.
        val hit = controls.firstOrNull { c ->
            val pad = c.rect.height() * 0.2f
            x >= c.rect.left - pad && x <= c.rect.right + pad && y >= c.rect.top - pad && y <= c.rect.bottom + pad
        }
        return hit?.let { setOf(it.keyCode) } ?: emptySet()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val keys = keysAt(event.getX(i), event.getY(i))
                if (MENU in keys) {
                    onMenu()
                    return true
                }
                update(event.getPointerId(i), keys)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                val id = event.getPointerId(i)
                if (id in held) update(id, keysAt(event.getX(i), event.getY(i)) - MENU)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> update(event.getPointerId(event.actionIndex), emptySet())
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun update(pointerId: Int, keys: Set<Int>) {
        val before = held[pointerId] ?: emptySet()
        if (before == keys) return
        for (k in before - keys) {
            val n = (pressedKeys[k] ?: 1) - 1
            pressedKeys[k] = n
            if (n == 0 && !sendsAsAnalog(k)) onKey(KeyEvent.ACTION_UP, k)
        }
        for (k in keys - before) {
            val n = (pressedKeys[k] ?: 0) + 1
            pressedKeys[k] = n
            if (n == 1 && !sendsAsAnalog(k)) onKey(KeyEvent.ACTION_DOWN, k)
        }
        if (keys.isEmpty()) held.remove(pointerId) else held[pointerId] = keys
        if (layout.analogDpad) updateAnalog()
        invalidate()
    }

    private fun sendsAsAnalog(key: Int) = layout.analogDpad && key in dpadKeys

    private fun updateAnalog() {
        var x = (if (isPressed(KeyEvent.KEYCODE_DPAD_RIGHT)) 1f else 0f) - (if (isPressed(KeyEvent.KEYCODE_DPAD_LEFT)) 1f else 0f)
        var y = (if (isPressed(KeyEvent.KEYCODE_DPAD_DOWN)) 1f else 0f) - (if (isPressed(KeyEvent.KEYCODE_DPAD_UP)) 1f else 0f)
        if (x != 0f && y != 0f) {
            x *= 0.7071f
            y *= 0.7071f
        }
        if (x != analogX || y != analogY) {
            analogX = x
            analogY = y
            onAnalog(x, y)
        }
    }

    fun releaseAll() {
        for ((k, n) in pressedKeys) if (n > 0 && !sendsAsAnalog(k)) onKey(KeyEvent.ACTION_UP, k)
        pressedKeys.clear()
        held.clear()
        if (analogX != 0f || analogY != 0f) {
            analogX = 0f
            analogY = 0f
            onAnalog(0f, 0f)
        }
        invalidate()
    }
}
