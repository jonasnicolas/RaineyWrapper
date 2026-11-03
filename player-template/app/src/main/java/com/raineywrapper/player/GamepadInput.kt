package com.raineywrapper.player

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.swordfish.libretrodroid.GLRetroView
import kotlin.math.abs

/**
 * Forwards physical gamepad input to the emulator. Everything goes to port 0:
 * handhelds report their built-in controls as a gamepad whose controller number
 * isn't always 1, and these are single-player wrappers.
 */
class GamepadInput(private val retroView: GLRetroView, private val system: String) {

    /** Systems with an analog stick of their own; elsewhere the left stick also drives the d-pad. */
    private val hasAnalog = system in setOf("n64", "psp", "dreamcast", "gamecube")

    companion object {
        private const val STICK_DEADZONE = 0.5f

        val GAMEPAD_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
        )

        /**
         * Android names face buttons by position-agnostic letters with A at the bottom;
         * RetroPad uses the Nintendo layout (A on the right). Swap so the physical
         * right button is A, matching LibretroDroid's own mapping.
         */
        fun toRetroKey(keyCode: Int): Int = when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_A -> KeyEvent.KEYCODE_BUTTON_B
            KeyEvent.KEYCODE_BUTTON_B -> KeyEvent.KEYCODE_BUTTON_A
            KeyEvent.KEYCODE_BUTTON_X -> KeyEvent.KEYCODE_BUTTON_Y
            KeyEvent.KEYCODE_BUTTON_Y -> KeyEvent.KEYCODE_BUTTON_X
            else -> keyCode
        }
    }

    /** Returns true if the event was a gamepad button and has been consumed. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in GAMEPAD_KEYS) return false
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return true
        if (event.repeatCount > 0) return true
        retroView.sendKeyEvent(event.action, systemRemap(toRetroKey(event.keyCode)), 0)
        return true
    }

    /**
     * Flycast and Dolphin read the analog triggers from RetroPad L2/R2, but handhelds' main
     * shoulder buttons are L1/R1, so put the triggers there. On GameCube, R2 becomes Z
     * (RetroPad R1).
     */
    private fun systemRemap(key: Int): Int = when (system) {
        "dreamcast" -> when (key) {
            KeyEvent.KEYCODE_BUTTON_L1 -> KeyEvent.KEYCODE_BUTTON_L2
            KeyEvent.KEYCODE_BUTTON_R1 -> KeyEvent.KEYCODE_BUTTON_R2
            else -> key
        }
        "gamecube" -> when (key) {
            KeyEvent.KEYCODE_BUTTON_L1 -> KeyEvent.KEYCODE_BUTTON_L2
            KeyEvent.KEYCODE_BUTTON_R1 -> KeyEvent.KEYCODE_BUTTON_R2
            KeyEvent.KEYCODE_BUTTON_R2 -> KeyEvent.KEYCODE_BUTTON_R1
            else -> key
        }
        else -> key
    }

    fun onMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK) return false

        // D-pads on many handhelds report as a hat axis. Systems without analog
        // sticks (GBA, NES, ...) also get the left stick folded into the d-pad.
        var x = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        var y = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        if (x == 0f && y == 0f && !hasAnalog) {
            val sx = event.getAxisValue(MotionEvent.AXIS_X)
            val sy = event.getAxisValue(MotionEvent.AXIS_Y)
            x = if (abs(sx) > STICK_DEADZONE) sx else 0f
            y = if (abs(sy) > STICK_DEADZONE) sy else 0f
        }
        retroView.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, x, y, 0)
        retroView.sendMotionEvent(
            GLRetroView.MOTION_SOURCE_ANALOG_LEFT,
            event.getAxisValue(MotionEvent.AXIS_X), event.getAxisValue(MotionEvent.AXIS_Y), 0,
        )
        retroView.sendMotionEvent(
            GLRetroView.MOTION_SOURCE_ANALOG_RIGHT,
            event.getAxisValue(MotionEvent.AXIS_Z), event.getAxisValue(MotionEvent.AXIS_RZ), 0,
        )
        return true
    }
}
