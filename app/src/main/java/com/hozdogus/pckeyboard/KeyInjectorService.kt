package com.hozdogus.pckeyboard

import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
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

    override fun injectKey(action: Int, keyCode: Int, metaState: Int, downTime: Long, repeat: Int): Boolean {
        val event = KeyEvent(
            downTime, SystemClock.uptimeMillis(), action, keyCode, repeat, metaState,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
        )
        return try {
            inject.invoke(target, event, 0) as? Boolean ?: false // 0 = don't wait
        } catch (t: Throwable) {
            false
        }
    }
}
