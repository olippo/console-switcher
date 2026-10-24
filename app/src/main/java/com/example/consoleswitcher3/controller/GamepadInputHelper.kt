package com.example.consoleswitcher3.controller

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent

class GamepadInputHelper(private val listener: GamepadListener) {

    interface GamepadListener {
        fun onNavLeft()
        fun onNavRight()
        fun onNavStart()      // Keyboard PageUp/Home or Right Stick Left (move to start of list)
        fun onNavEnd()        // Keyboard PageDown/End or Right Stick Right (move to end of list)
        fun onButtonSelect()  // A / Enter
        fun onButtonBack()    // B / Back
        fun onButtonAction()  // X (Close/Kill)
        fun onVolumeDown()    // L1 Bumper
        fun onVolumeUp()      // R1 Bumper
        fun onBrightnessDown()// L2 Trigger
        fun onBrightnessUp()  // R2 Trigger
    }

    private enum class NavDirection { NONE, LEFT, RIGHT }
    private enum class ContinuousAction {
        NONE, VOLUME_DOWN, VOLUME_UP, BRIGHTNESS_DOWN, BRIGHTNESS_UP
    }

    private val handler = Handler(Looper.getMainLooper())

    private var keyDirection = NavDirection.NONE
    private var stickDirection = NavDirection.NONE
    private var currentActiveDirection = NavDirection.NONE

    private var rightStickInDeadzone = true
    private var l2TriggerActive = false
    private var r2TriggerActive = false
    private var activeContinuousAction = ContinuousAction.NONE

    private val repeatRunnable = object : Runnable {
        override fun run() {
            when (currentActiveDirection) {
                NavDirection.LEFT -> {
                    listener.onNavLeft()
                    handler.postDelayed(this, REPEAT_INTERVAL_MS)
                }
                NavDirection.RIGHT -> {
                    listener.onNavRight()
                    handler.postDelayed(this, REPEAT_INTERVAL_MS)
                }
                NavDirection.NONE -> {}
            }
        }
    }

    private val continuousRepeatRunnable = object : Runnable {
        override fun run() {
            when (activeContinuousAction) {
                ContinuousAction.VOLUME_DOWN -> {
                    listener.onVolumeDown()
                    handler.postDelayed(this, CONTINUOUS_REPEAT_INTERVAL_MS)
                }
                ContinuousAction.VOLUME_UP -> {
                    listener.onVolumeUp()
                    handler.postDelayed(this, CONTINUOUS_REPEAT_INTERVAL_MS)
                }
                ContinuousAction.BRIGHTNESS_DOWN -> {
                    listener.onBrightnessDown()
                    handler.postDelayed(this, CONTINUOUS_REPEAT_INTERVAL_MS)
                }
                ContinuousAction.BRIGHTNESS_UP -> {
                    listener.onBrightnessUp()
                    handler.postDelayed(this, CONTINUOUS_REPEAT_INTERVAL_MS)
                }
                ContinuousAction.NONE -> {}
            }
        }
    }

    private fun startContinuousAction(action: ContinuousAction) {
        if (activeContinuousAction == action) return
        handler.removeCallbacks(continuousRepeatRunnable)
        activeContinuousAction = action
        when (action) {
            ContinuousAction.VOLUME_DOWN -> listener.onVolumeDown()
            ContinuousAction.VOLUME_UP -> listener.onVolumeUp()
            ContinuousAction.BRIGHTNESS_DOWN -> listener.onBrightnessDown()
            ContinuousAction.BRIGHTNESS_UP -> listener.onBrightnessUp()
            ContinuousAction.NONE -> return
        }
        handler.postDelayed(continuousRepeatRunnable, CONTINUOUS_INITIAL_DELAY_MS)
    }

    private fun stopContinuousAction(action: ContinuousAction) {
        if (activeContinuousAction == action) {
            handler.removeCallbacks(continuousRepeatRunnable)
            activeContinuousAction = ContinuousAction.NONE
        }
    }

    private fun updateActiveDirection() {
        val desired = when {
            keyDirection != NavDirection.NONE -> keyDirection
            stickDirection != NavDirection.NONE -> stickDirection
            else -> NavDirection.NONE
        }

        if (desired == currentActiveDirection) return

        handler.removeCallbacks(repeatRunnable)
        currentActiveDirection = desired

        when (desired) {
            NavDirection.LEFT -> {
                listener.onNavLeft()
                handler.postDelayed(repeatRunnable, INITIAL_DELAY_MS)
            }
            NavDirection.RIGHT -> {
                listener.onNavRight()
                handler.postDelayed(repeatRunnable, INITIAL_DELAY_MS)
            }
            NavDirection.NONE -> {
                // Stopped
            }
        }
    }

    fun reset() {
        handler.removeCallbacks(repeatRunnable)
        handler.removeCallbacks(continuousRepeatRunnable)
        keyDirection = NavDirection.NONE
        stickDirection = NavDirection.NONE
        currentActiveDirection = NavDirection.NONE
        activeContinuousAction = ContinuousAction.NONE
        rightStickInDeadzone = true
        l2TriggerActive = false
        r2TriggerActive = false
    }

    fun handleKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        val isUp = event.action == KeyEvent.ACTION_UP
        if (!isDown && !isUp) return false

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (isDown) {
                    if (event.repeatCount == 0) {
                        keyDirection = NavDirection.LEFT
                        updateActiveDirection()
                    }
                } else {
                    if (keyDirection == NavDirection.LEFT) {
                        keyDirection = NavDirection.NONE
                        updateActiveDirection()
                    }
                }
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (isDown) {
                    if (event.repeatCount == 0) {
                        keyDirection = NavDirection.RIGHT
                        updateActiveDirection()
                    }
                } else {
                    if (keyDirection == NavDirection.RIGHT) {
                        keyDirection = NavDirection.NONE
                        updateActiveDirection()
                    }
                }
                true
            }
            // Swallow UP/DOWN: the overlay has no vertical navigation, and letting
            // these reach the framework would move view-focus onto a card, painting
            // the grey selectableItemBackground focused veil over it.
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                true
            }
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_MOVE_HOME -> {
                if (isDown && event.repeatCount == 0) {
                    listener.onNavStart()
                }
                true
            }
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_MOVE_END -> {
                if (isDown && event.repeatCount == 0) {
                    listener.onNavEnd()
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                if (isDown) {
                    if (event.repeatCount == 0) {
                        startContinuousAction(ContinuousAction.VOLUME_DOWN)
                    }
                } else {
                    stopContinuousAction(ContinuousAction.VOLUME_DOWN)
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (isDown) {
                    if (event.repeatCount == 0) {
                        startContinuousAction(ContinuousAction.VOLUME_UP)
                    }
                } else {
                    stopContinuousAction(ContinuousAction.VOLUME_UP)
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_L2 -> {
                if (isDown) {
                    if (event.repeatCount == 0) {
                        startContinuousAction(ContinuousAction.BRIGHTNESS_DOWN)
                    }
                } else {
                    stopContinuousAction(ContinuousAction.BRIGHTNESS_DOWN)
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_R2 -> {
                if (isDown) {
                    if (event.repeatCount == 0) {
                        startContinuousAction(ContinuousAction.BRIGHTNESS_UP)
                    }
                } else {
                    stopContinuousAction(ContinuousAction.BRIGHTNESS_UP)
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_A,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (isDown && event.repeatCount == 0) {
                    listener.onButtonSelect()
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> {
                if (isDown && event.repeatCount == 0) {
                    listener.onButtonBack()
                }
                true
            }
            KeyEvent.KEYCODE_BUTTON_X,
            KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_DEL -> {
                if (isDown && event.repeatCount == 0) {
                    listener.onButtonAction()
                }
                true
            }
            // Swallow all remaining buttons so they do nothing and don't fall through
            // to the framework focus search (which paints a grey veil on the view).
            KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_MODE,
            KeyEvent.KEYCODE_BUTTON_THUMBL,
            KeyEvent.KEYCODE_BUTTON_THUMBR,
            KeyEvent.KEYCODE_BUTTON_C,
            KeyEvent.KEYCODE_BUTTON_Z -> {
                true
            }
            else -> false
        }
    }

    fun handleMotionEvent(event: MotionEvent): Boolean {
        if (event.source and android.view.InputDevice.SOURCE_CLASS_JOYSTICK == 0) return false

        // 1. Left stick / D-pad Hat (one card at a time, or hold to repeat at standard speed)
        val x = event.getAxisValue(MotionEvent.AXIS_X)
        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val navX = if (Math.abs(x) > Math.abs(hatX)) x else hatX

        val newStickDirection = when {
            navX < -STICK_DEADZONE -> NavDirection.LEFT
            navX > STICK_DEADZONE -> NavDirection.RIGHT
            else -> NavDirection.NONE
        }

        if (newStickDirection != stickDirection) {
            stickDirection = newStickDirection
            updateActiveDirection()
        }

        // 2. Right stick (move to start or end of the list)
        val rz = event.getAxisValue(MotionEvent.AXIS_Z)
        val rx = event.getAxisValue(MotionEvent.AXIS_RX)
        val rightX = if (Math.abs(rz) > Math.abs(rx)) rz else rx

        if (Math.abs(rightX) < RIGHT_STICK_DEADZONE) {
            rightStickInDeadzone = true
        } else if (rightStickInDeadzone) {
            if (rightX < -RIGHT_STICK_DEADZONE) {
                rightStickInDeadzone = false
                listener.onNavStart()
            } else if (rightX > RIGHT_STICK_DEADZONE) {
                rightStickInDeadzone = false
                listener.onNavEnd()
            }
        }

        // 3. Analog triggers (L2 / R2) for brightness control
        val lTrigger = maxOf(
            event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_BRAKE)
        )
        val rTrigger = maxOf(
            event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
            event.getAxisValue(MotionEvent.AXIS_GAS),
            event.getAxisValue(MotionEvent.AXIS_THROTTLE)
        )

        if (lTrigger > TRIGGER_PRESS_THRESHOLD) {
            if (!l2TriggerActive) {
                l2TriggerActive = true
                startContinuousAction(ContinuousAction.BRIGHTNESS_DOWN)
            }
        } else if (lTrigger < TRIGGER_RELEASE_THRESHOLD) {
            if (l2TriggerActive) {
                l2TriggerActive = false
                stopContinuousAction(ContinuousAction.BRIGHTNESS_DOWN)
            }
        }

        if (rTrigger > TRIGGER_PRESS_THRESHOLD) {
            if (!r2TriggerActive) {
                r2TriggerActive = true
                startContinuousAction(ContinuousAction.BRIGHTNESS_UP)
            }
        } else if (rTrigger < TRIGGER_RELEASE_THRESHOLD) {
            if (r2TriggerActive) {
                r2TriggerActive = false
                stopContinuousAction(ContinuousAction.BRIGHTNESS_UP)
            }
        }

        return true
    }

    companion object {
        private const val INITIAL_DELAY_MS = 220L
        private const val REPEAT_INTERVAL_MS = 138L
        private const val CONTINUOUS_INITIAL_DELAY_MS = 350L
        private const val CONTINUOUS_REPEAT_INTERVAL_MS = 150L
        private const val STICK_DEADZONE = 0.45f
        private const val RIGHT_STICK_DEADZONE = 0.5f
        private const val TRIGGER_PRESS_THRESHOLD = 0.5f
        private const val TRIGGER_RELEASE_THRESHOLD = 0.25f

        fun getKeyName(keyCode: Int): String {
            return when (keyCode) {
                KeyEvent.KEYCODE_BUTTON_A -> "Button A"
                KeyEvent.KEYCODE_BUTTON_B -> "Button B"
                KeyEvent.KEYCODE_BUTTON_X -> "Button X"
                KeyEvent.KEYCODE_BUTTON_Y -> "Button Y"
                KeyEvent.KEYCODE_BUTTON_L1 -> "L1 Bumper"
                KeyEvent.KEYCODE_BUTTON_R1 -> "R1 Bumper"
                KeyEvent.KEYCODE_BUTTON_L2 -> "L2 Trigger"
                KeyEvent.KEYCODE_BUTTON_R2 -> "R2 Trigger"
                KeyEvent.KEYCODE_DPAD_LEFT -> "D-Pad Left"
                KeyEvent.KEYCODE_DPAD_RIGHT -> "D-Pad Right"
                KeyEvent.KEYCODE_DPAD_UP -> "D-Pad Up"
                KeyEvent.KEYCODE_DPAD_DOWN -> "D-Pad Down"
                KeyEvent.KEYCODE_DPAD_CENTER -> "D-Pad Center"
                KeyEvent.KEYCODE_BUTTON_START -> "Start"
                KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
                KeyEvent.KEYCODE_BACK -> "Back"
                KeyEvent.KEYCODE_ENTER -> "Enter"
                else -> KeyEvent.keyCodeToString(keyCode)
            }
        }
    }
}
