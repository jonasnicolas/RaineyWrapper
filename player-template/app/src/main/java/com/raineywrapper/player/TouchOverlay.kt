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
 * shoulders in the top corners, Select/Start/Menu along the bottom middle. Systems with analog
 * sticks get an on-screen stick in the d-pad's place (the d-pad moves up), and a second stick
 * above the face buttons when the console had one.
 * Multi-touch; each finger can press one control and slide between them; a finger that lands on
 * a stick keeps steering it until lifted.
 *
 * Keys are sent as RetroPad buttons (KEYCODE_BUTTON_A = RetroPad A, the right face button).
 */
class TouchOverlay(
    context: Context,
    private val layout: Layout,
    private val onKey: (action: Int, keyCode: Int) -> Unit,
    /** Analog stick position, -1..1 on each axis; [right] tells which stick. */
    private val onAnalog: (right: Boolean, x: Float, y: Float) -> Unit,
    private val onMenu: () -> Unit,
) : View(context) {

    data class Face(val label: String, val keyCode: Int, /** position on a unit circle, (0,0) = centre */ val x: Float, val y: Float)

    data class Layout(
        val face: List<Face>,
        val shoulders: List<Pair<String, Int>>,
        val selectLabel: String = "SELECT",
        val selectKey: Int = KeyEvent.KEYCODE_BUTTON_SELECT,
        val startLabel: String = "START",
        val startKey: Int = KeyEvent.KEYCODE_BUTTON_START,
        /** On-screen analog sticks, for systems whose games are played with them. */
        val leftStick: Boolean = false,
        val rightStick: Boolean = false,
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

        fun layoutFor(system: String, wiiController: WiiController = WiiController.NUNCHUK): Layout = when (system) {
            "gba" -> Layout(listOf(Face("B", B, -0.45f, 0.25f), Face("A", A, 0.45f, -0.25f)), listOf("L" to L, "R" to R))
            "gb", "sms", "gg" -> Layout(listOf(Face(if (system == "gb") "B" else "1", B, -0.45f, 0.25f), Face(if (system == "gb") "A" else "2", A, 0.45f, -0.25f)), emptyList())
            // Genesis Plus GX: RetroPad Y/B/A = Genesis A/B/C, Select = Mode.
            "genesis" -> Layout(listOf(Face("A", Y, -0.6f, 0.2f), Face("B", B, 0f, 0f), Face("C", A, 0.6f, -0.2f)), emptyList(), selectLabel = "MODE")
            // Mupen64Plus-Next: RetroPad B/Y = N64 A/B, L2 = Z. C-buttons are on the right stick.
            "n64" -> Layout(
                listOf(Face("A", B, 0.1f, 0.35f), Face("B", Y, -0.5f, -0.15f)),
                listOf("L" to L, "R" to R, "Z" to L2),
                leftStick = true, rightStick = true, // right stick = C buttons
            )
            // Flycast: RetroPad B/A/Y/X = Dreamcast A/B/X/Y, L2/R2 = triggers.
            "dreamcast" -> Layout(
                listOf(Face("Y", X, 0f, -0.55f), Face("B", A, 0.55f, 0f), Face("A", B, 0f, 0.55f), Face("X", Y, -0.55f, 0f)),
                listOf("L" to L2, "R" to R2),
                leftStick = true,
            )
            // Dolphin: RetroPad A/B/X/Y = GameCube A/B/X/Y, L2/R2 = triggers, R1 = Z.
            "gamecube" -> Layout(
                listOf(Face("A", A, 0f, 0.1f), Face("B", B, -0.55f, 0.45f), Face("X", X, 0.6f, -0.2f), Face("Y", Y, -0.2f, -0.6f)),
                listOf("L" to L2, "R" to R2, "Z" to R),
                leftStick = true, rightStick = true, // right stick = C stick
            )
            // Dolphin's Wii layouts; see GamepadInput.wiiRemap for the RetroPad assignments.
            "wii" -> when (wiiController) {
                WiiController.NUNCHUK -> Layout(
                    listOf(Face("C", X, 0f, -0.55f), Face("A", A, 0.55f, 0f), Face("B", B, 0f, 0.55f), Face("Z", Y, -0.55f, 0f)),
                    listOf("1" to KeyEvent.KEYCODE_BUTTON_START, "SHAKE" to R2, "2" to KeyEvent.KEYCODE_BUTTON_SELECT),
                    selectLabel = "−", selectKey = L, startLabel = "+", startKey = R,
                    leftStick = true, rightStick = true, // Nunchuk stick; right stick aims the pointer
                )
                WiiController.CLASSIC -> Layout(
                    listOf(Face("X", X, 0f, -0.55f), Face("A", A, 0.55f, 0f), Face("B", B, 0f, 0.55f), Face("Y", Y, -0.55f, 0f)),
                    listOf("L" to L2, "R" to R2, "ZL" to L, "ZR" to R),
                    selectLabel = "−", startLabel = "+",
                    leftStick = true, rightStick = true,
                )
                WiiController.SIDEWAYS -> Layout(
                    listOf(Face("1", B, -0.45f, 0.25f), Face("2", A, 0.45f, -0.25f)),
                    listOf("A" to X, "B" to Y, "SHAKE" to R2),
                    selectLabel = "−", startLabel = "+",
                )
            }
            "psp" -> Layout(
                listOf(Face("△", X, 0f, -0.55f), Face("○", A, 0.55f, 0f), Face("✕", B, 0f, 0.55f), Face("□", Y, -0.55f, 0f)),
                listOf("L" to L, "R" to R),
                leftStick = true,
            )
            "ps2" -> Layout(
                listOf(Face("△", X, 0f, -0.55f), Face("○", A, 0.55f, 0f), Face("✕", B, 0f, 0.55f), Face("□", Y, -0.55f, 0f)),
                listOf("L1" to L, "R1" to R, "L2" to L2, "R2" to R2),
                leftStick = true, rightStick = true,
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

    /** An on-screen analog stick; the knob follows the finger that grabbed it. */
    private class Stick(val right: Boolean) {
        var cx = 0f
        var cy = 0f
        var r = 0f
        var pointerId = -1
        var x = 0f // -1..1
        var y = 0f

        fun contains(px: Float, py: Float) = hypot(px - cx, py - cy) <= r * 1.3f
    }

    private val sticks = listOfNotNull(
        Stick(right = false).takeIf { layout.leftStick },
        Stick(right = true).takeIf { layout.rightStick },
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

        // The bottom-left spot belongs to the left stick when there is one, and the d-pad moves up
        // into the space between it and the shoulder buttons; a right stick goes above the face buttons.
        val mainR = unit * 1.7f
        val mainCx = margin + mainR
        val mainCy = h - margin - mainR - unit * 0.3f
        val upperR = unit * 1.05f
        val upperCy = (margin + 2 * unit * 0.85f + unit * 0.25f + (mainCy - mainR * 1.45f)) / 2
        for (stick in sticks) {
            if (stick.right) {
                stick.r = upperR; stick.cx = w - margin - mainR; stick.cy = upperCy
            } else {
                stick.r = mainR; stick.cx = mainCx; stick.cy = mainCy
            }
        }
        if (layout.leftStick) {
            dpadR = upperR; dpadCx = mainCx; dpadCy = upperCy
        } else {
            dpadR = mainR; dpadCx = mainCx; dpadCy = mainCy
        }

        val faceR = unit * 0.62f
        val faceCx = w - margin - mainR
        val faceCy = mainCy
        for (f in layout.face) {
            val cx = faceCx + f.x * mainR * 1.15f
            val cy = faceCy + f.y * mainR * 1.15f
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
        controls += Control(layout.selectLabel, layout.selectKey, RectF(w / 2f - pw * 1.6f, by, w / 2f - pw * 0.6f, by + ph), false)
        controls += Control(layout.startLabel, layout.startKey, RectF(w / 2f + pw * 0.6f, by, w / 2f + pw * 1.6f, by + ph), false)
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

        for (stick in sticks) {
            canvas.drawCircle(stick.cx, stick.cy, stick.r, fill)
            canvas.drawCircle(stick.cx, stick.cy, stick.r, stroke)
            val kx = stick.cx + stick.x * stick.r * 0.6f
            val ky = stick.cy + stick.y * stick.r * 0.6f
            val knob = if (stick.pointerId >= 0) fillPressed else fill
            canvas.drawCircle(kx, ky, stick.r * 0.42f, knob)
            canvas.drawCircle(kx, ky, stick.r * 0.42f, stroke)
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
                val stick = sticks.firstOrNull { it.pointerId < 0 && it.contains(event.getX(i), event.getY(i)) }
                if (stick != null) {
                    stick.pointerId = event.getPointerId(i)
                    moveStick(stick, event.getX(i), event.getY(i))
                    return true
                }
                val keys = keysAt(event.getX(i), event.getY(i))
                if (MENU in keys) {
                    onMenu()
                    return true
                }
                update(event.getPointerId(i), keys)
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                val id = event.getPointerId(i)
                val stick = sticks.firstOrNull { it.pointerId == id }
                if (stick != null) moveStick(stick, event.getX(i), event.getY(i))
                else if (id in held) update(id, keysAt(event.getX(i), event.getY(i)) - MENU)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = event.getPointerId(event.actionIndex)
                val stick = sticks.firstOrNull { it.pointerId == id }
                if (stick != null) releaseStick(stick) else update(id, emptySet())
            }
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
            if (n == 0) onKey(KeyEvent.ACTION_UP, k)
        }
        for (k in keys - before) {
            val n = (pressedKeys[k] ?: 0) + 1
            pressedKeys[k] = n
            if (n == 1) onKey(KeyEvent.ACTION_DOWN, k)
        }
        if (keys.isEmpty()) held.remove(pointerId) else held[pointerId] = keys
        invalidate()
    }

    /** Stick deflection from the finger's offset; full tilt at 60% of the radius, like the knob's travel. */
    private fun moveStick(stick: Stick, px: Float, py: Float) {
        var x = (px - stick.cx) / (stick.r * 0.6f)
        var y = (py - stick.cy) / (stick.r * 0.6f)
        val len = hypot(x, y)
        if (len > 1f) {
            x /= len
            y /= len
        }
        stick.x = x
        stick.y = y
        onAnalog(stick.right, x, y)
        invalidate()
    }

    private fun releaseStick(stick: Stick) {
        stick.pointerId = -1
        stick.x = 0f
        stick.y = 0f
        onAnalog(stick.right, 0f, 0f)
        invalidate()
    }

    fun releaseAll() {
        for ((k, n) in pressedKeys) if (n > 0) onKey(KeyEvent.ACTION_UP, k)
        pressedKeys.clear()
        held.clear()
        sticks.filter { it.pointerId >= 0 || it.x != 0f || it.y != 0f }.forEach(::releaseStick)
        invalidate()
    }
}
