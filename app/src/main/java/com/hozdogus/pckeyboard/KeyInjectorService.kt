package com.hozdogus.pckeyboard

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method
import kotlin.system.exitProcess

/**
 * Runs in a separate process started by Shizuku, with ADB ("shell") rights.
 * Android lets that kind of process inject key presses, exactly like a USB keyboard.
 */
class KeyInjectorService : IKeyInjector.Stub() {

    private val target: Any
    private val inject: Method

    init {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) HiddenApiBypass.addHiddenApiExemptions("L")
        } catch (t: Throwable) {
            // Not needed on every phone; carry on.
        }

        // Android 14+ moved this into InputManagerGlobal; older versions use InputManager.
        var found: Pair<Any, Method>? = null
        try {
            val cls = Class.forName("android.hardware.input.InputManagerGlobal")
            val instance = cls.getMethod("getInstance").invoke(null)!!
            val m = cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            found = instance to m
        } catch (t: Throwable) {
            // fall through
        }
        if (found == null) {
            val cls = Class.forName("android.hardware.input.InputManager")
            val instance = cls.getMethod("getInstance").invoke(null)!!
            val m = cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            found = instance to m
        }
        val f = found ?: throw IllegalStateException("No input manager")
        target = f.first
        inject = f.second
    }

    override fun destroy() {
        exitProcess(0)
    }

    override fun injectKey(action: Int, keyCode: Int, metaState: Int, downTime: Long, repeat: Int): Boolean =
        injectKeyFrom(action, keyCode, metaState, downTime, repeat, InputDevice.SOURCE_KEYBOARD)

    override fun injectKeyFrom(
        action: Int, keyCode: Int, metaState: Int, downTime: Long, repeat: Int, source: Int,
    ): Boolean {
        val event = KeyEvent(
            downTime, SystemClock.uptimeMillis(), action, keyCode, repeat, metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source,
        )
        return send(event)
    }

    // Both sticks are sent together, like a real controller.
    private val axes = FloatArray(4) // left x, left y, right x, right y
    private val stickStart = SystemClock.uptimeMillis()

    override fun injectStick(stick: Int, x: Float, y: Float): Boolean {
        val i = if (stick == 1) 2 else 0
        axes[i] = x.coerceIn(-1f, 1f)
        axes[i + 1] = y.coerceIn(-1f, 1f)
        val props = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_UNKNOWN
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_X, axes[0])
            setAxisValue(MotionEvent.AXIS_Y, axes[1])
            setAxisValue(MotionEvent.AXIS_Z, axes[2])
            setAxisValue(MotionEvent.AXIS_RZ, axes[3])
        })
        val event = MotionEvent.obtain(
            stickStart, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, 1, props, coords,
            0, 0, 1f, 1f, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, InputDevice.SOURCE_JOYSTICK, 0,
        )
        val ok = send(event)
        event.recycle()
        return ok
    }

    private fun send(event: InputEvent): Boolean = try {
        inject.invoke(target, event, 0) as? Boolean ?: false // 0 = don't wait
    } catch (t: Throwable) {
        false
    }
}
