package com.hozdogus.pckeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.min

object Look {
    val key = Color.parseColor("#2A3340")
    val pressed = Color.parseColor("#4FA3FF")
    val ring = Color.parseColor("#FFC24B")
    val gamepad = Color.parseColor("#3B2F4F")
    val stickBase = Color.argb(150, 42, 51, 64)
    val stickKnob = Color.parseColor("#4FA3FF")
}

/** A round on-screen button. */
class ButtonView(context: Context, private val control: Control) : TextView(context) {
    private var down = false
    var chosen = false
        set(v) {
            field = v
            refresh()
        }

    init {
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        val label = control.displayLabel()
        val base = control.size * 0.32f
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, if (label.length > 3) base * 3.2f / label.length.coerceAtMost(8) else base)
        maxLines = 1
        text = label
        refresh()
    }

    fun setDown(d: Boolean) {
        down = d
        refresh()
    }

    private fun refresh() {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (down) Look.pressed else if (control.gamepad) Look.gamepad else Look.key)
            setStroke(
                (resources.displayMetrics.density * (if (chosen) 3f else 1.5f)).toInt(),
                if (chosen) Look.ring else Color.argb(160, 255, 255, 255),
            )
        }
    }
}

/** An on-screen thumbstick. Reports x/y from -1 to 1 while you hold it. */
class StickView(context: Context, private val control: Control) : View(context) {
    var onMove: ((Float, Float) -> Unit)? = null
    var onRelease: (() -> Unit)? = null
    /** False in the editor: then it can be dragged instead. */
    var interactive = true
    var chosen = false
        set(v) {
            field = v
            invalidate()
        }

    private var kx = 0f
    private var ky = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 12f * resources.displayMetrics.density
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(cx, cy)
        paint.style = Paint.Style.FILL
        paint.color = Look.stickBase
        canvas.drawCircle(cx, cy, r - 2, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = resources.displayMetrics.density * (if (chosen) 3f else 1.5f)
        paint.color = if (chosen) Look.ring else Color.argb(160, 255, 255, 255)
        canvas.drawCircle(cx, cy, r - 2, paint)
        paint.style = Paint.Style.FILL
        paint.color = Look.stickKnob
        val knobR = r * 0.38f
        canvas.drawCircle(cx + kx * (r - knobR), cy + ky * (r - knobR), knobR, paint)
        canvas.drawText(control.displayLabel(), cx, cy + r * 0.82f, textPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!interactive) return super.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                }
                val r = min(width, height) / 2f
                var dx = (e.x - width / 2f) / r
                var dy = (e.y - height / 2f) / r
                val len = hypot(dx, dy)
                if (len > 1f) {
                    dx /= len
                    dy /= len
                }
                kx = dx
                ky = dy
                invalidate()
                onMove?.invoke(dx, dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                kx = 0f
                ky = 0f
                invalidate()
                onRelease?.invoke()
            }
        }
        return true
    }
}

/** Sends button presses, including combos and gamepad buttons. */
object Sender {
    private fun modCode(bit: Int) = when (bit) {
        ModBits.CTRL -> android.view.KeyEvent.KEYCODE_CTRL_LEFT
        ModBits.ALT -> android.view.KeyEvent.KEYCODE_ALT_LEFT
        ModBits.SHIFT -> android.view.KeyEvent.KEYCODE_SHIFT_LEFT
        else -> android.view.KeyEvent.KEYCODE_META_LEFT
    }

    private fun metaFor(bit: Int) = when (bit) {
        ModBits.CTRL -> android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON
        ModBits.ALT -> android.view.KeyEvent.META_ALT_ON or android.view.KeyEvent.META_ALT_LEFT_ON
        ModBits.SHIFT -> android.view.KeyEvent.META_SHIFT_ON or android.view.KeyEvent.META_SHIFT_LEFT_ON
        else -> android.view.KeyEvent.META_META_ON or android.view.KeyEvent.META_META_LEFT_ON
    }

    /** If the key itself is a modifier, it carries its own meta flag, like a real keyboard. */
    private fun ownMeta(code: Int) = when (code) {
        android.view.KeyEvent.KEYCODE_CTRL_LEFT -> metaFor(ModBits.CTRL)
        android.view.KeyEvent.KEYCODE_ALT_LEFT -> metaFor(ModBits.ALT)
        android.view.KeyEvent.KEYCODE_SHIFT_LEFT -> metaFor(ModBits.SHIFT)
        android.view.KeyEvent.KEYCODE_META_LEFT -> metaFor(ModBits.WIN)
        else -> 0
    }

    private fun bits(mods: Int) = ModBits.all.map { it.first }.filter { (mods and it) != 0 }

    fun press(c: Control): Long {
        val t = SystemClock.uptimeMillis()
        var meta = 0
        for (b in bits(c.mods)) {
            meta = meta or metaFor(b)
            ShizukuLink.send(android.view.KeyEvent.ACTION_DOWN, modCode(b), meta, t)
        }
        meta = meta or ownMeta(c.code)
        if (c.gamepad) {
            ShizukuLink.sendFrom(android.view.KeyEvent.ACTION_DOWN, c.code, meta, t, Catalogue.gamepadSource())
        } else {
            ShizukuLink.send(android.view.KeyEvent.ACTION_DOWN, c.code, meta, t)
        }
        return t
    }

    fun release(c: Control, downTime: Long) {
        var meta = bits(c.mods).fold(0) { acc, b -> acc or metaFor(b) }
        if (c.gamepad) {
            ShizukuLink.sendFrom(android.view.KeyEvent.ACTION_UP, c.code, meta, downTime, Catalogue.gamepadSource())
        } else {
            ShizukuLink.send(android.view.KeyEvent.ACTION_UP, c.code, meta, downTime)
        }
        for (b in bits(c.mods).reversed()) {
            meta = meta and metaFor(b).inv()
            ShizukuLink.send(android.view.KeyEvent.ACTION_UP, modCode(b), meta, SystemClock.uptimeMillis())
        }
    }

    /** Presses or releases a single key with no modifiers (used by sticks). */
    fun key(down: Boolean, code: Int, gamepad: Boolean, downTime: Long) {
        val action = if (down) android.view.KeyEvent.ACTION_DOWN else android.view.KeyEvent.ACTION_UP
        if (gamepad) ShizukuLink.sendFrom(action, code, 0, downTime, Catalogue.gamepadSource())
        else ShizukuLink.send(action, code, 0, downTime)
    }
}
