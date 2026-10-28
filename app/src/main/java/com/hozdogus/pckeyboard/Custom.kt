package com.hozdogus.pckeyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

/** Small helpers shared by the overlay windows. */
object Win {
    fun params(width: Int, height: Int, extraFlags: Int = 0) = WindowManager.LayoutParams(
        width, height,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Never take focus, so presses go to the app underneath.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or extraFlags,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    fun screen(wm: WindowManager): Rect = wm.currentWindowMetrics.bounds

    fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    fun chip(ctx: Context, label: String, colour: Int = Color.parseColor("#2A3340"), onClick: () -> Unit) =
        TextView(ctx).apply {
            text = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            gravity = Gravity.CENTER
            val p = dp(ctx, 10f)
            setPadding(p, p * 2 / 3, p, p * 2 / 3)
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 8f).toFloat()
                setColor(colour)
            }
            setOnClickListener { onClick() }
        }

    fun gap(ctx: Context, w: Float, h: Float) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(dp(ctx, w), dp(ctx, h))
    }
}

/** Shows a custom layout: every button and stick floats in its own small window. */
class CustomOverlay(
    private val ctx: Context,
    private val wm: WindowManager,
    private val layout: CustomLayout,
    private val opacity: Float,
    private val onMenu: () -> Unit,
) {
    private val windows = mutableListOf<View>()
    private val held = HashMap<String, Long>()
    /** For sticks that press keys: which keys each stick is holding, and since when. */
    private val stickKeys = HashMap<String, MutableMap<Int, Long>>()

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        val screen = Win.screen(wm)
        for (c in layout.controls) {
            val size = Win.dp(ctx, c.size.toFloat())
            val params = Win.params(size, size).apply {
                x = (c.x * screen.width()).toInt().coerceIn(0, (screen.width() - size).coerceAtLeast(0))
                y = (c.y * screen.height()).toInt().coerceIn(0, (screen.height() - size).coerceAtLeast(0))
            }
            val view: View = if (c.kind == "stick") {
                StickView(ctx, c).apply {
                    onMove = { x, y -> stickMoved(c, x, y) }
                    onRelease = { stickReleased(c) }
                }
            } else {
                ButtonView(ctx, c).apply {
                    setOnTouchListener { v, e ->
                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> {
                                v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                held[c.id] = Sender.press(c)
                                setDown(true)
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                held.remove(c.id)?.let { Sender.release(c, it) }
                                setDown(false)
                            }
                        }
                        true
                    }
                }
            }
            view.alpha = opacity
            wm.addView(view, params)
            windows += view
        }
        addMenuButton()
    }

    private fun addMenuButton() {
        val prefs = Prefs.get(ctx)
        val screen = Win.screen(wm)
        val size = Win.dp(ctx, 36f)
        val params = Win.params(size, size).apply {
            x = prefs.getInt("menu_x", screen.width() / 2 - size / 2).coerceIn(0, screen.width() - size)
            y = prefs.getInt("menu_y", Win.dp(ctx, 8f)).coerceIn(0, screen.height() - size)
        }
        val b = TextView(ctx).apply {
            text = "☰"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(200, 30, 42, 56))
                setStroke(Win.dp(ctx, 1.5f), Look.pressed)
            }
            alpha = opacity
        }
        b.setOnTouchListener(DragOrTap(wm, params, ctx, onTap = onMenu) { x, y ->
            prefs.edit().putInt("menu_x", x).putInt("menu_y", y).apply()
        })
        wm.addView(b, params)
        windows += b
    }

    private fun directions(c: Control): Map<String, Pair<Int, Boolean>> = when (c.stick) {
        "wasd" -> mapOf(
            "up" to (KeyEvent.KEYCODE_W to false), "down" to (KeyEvent.KEYCODE_S to false),
            "left" to (KeyEvent.KEYCODE_A to false), "right" to (KeyEvent.KEYCODE_D to false),
        )
        "arrows" -> mapOf(
            "up" to (KeyEvent.KEYCODE_DPAD_UP to false), "down" to (KeyEvent.KEYCODE_DPAD_DOWN to false),
            "left" to (KeyEvent.KEYCODE_DPAD_LEFT to false), "right" to (KeyEvent.KEYCODE_DPAD_RIGHT to false),
        )
        else -> mapOf( // gamepad D-pad
            "up" to (KeyEvent.KEYCODE_DPAD_UP to true), "down" to (KeyEvent.KEYCODE_DPAD_DOWN to true),
            "left" to (KeyEvent.KEYCODE_DPAD_LEFT to true), "right" to (KeyEvent.KEYCODE_DPAD_RIGHT to true),
        )
    }

    private fun stickMoved(c: Control, x: Float, y: Float) {
        if (c.stick == "left" || c.stick == "right") {
            ShizukuLink.sendStick(if (c.stick == "right") 1 else 0, x, y)
            return
        }
        val dirs = directions(c)
        val dead = 0.4f
        val want = buildSet {
            if (y < -dead) add("up")
            if (y > dead) add("down")
            if (x < -dead) add("left")
            if (x > dead) add("right")
        }.map { dirs.getValue(it) }.toSet()
        val gamepad = dirs.values.first().second
        val now = stickKeys.getOrPut(c.id) { mutableMapOf() }
        // Let go of directions no longer pushed, then press new ones.
        for (code in now.keys.toList()) {
            if (want.none { it.first == code }) {
                Sender.key(false, code, gamepad, now.remove(code)!!)
            }
        }
        for ((code, _) in want) {
            if (code !in now) {
                val t = SystemClock.uptimeMillis()
                now[code] = t
                Sender.key(true, code, gamepad, t)
            }
        }
    }

    private fun stickReleased(c: Control) {
        if (c.stick == "left" || c.stick == "right") {
            ShizukuLink.sendStick(if (c.stick == "right") 1 else 0, 0f, 0f)
            return
        }
        val gamepad = directions(c).values.first().second
        stickKeys.remove(c.id)?.forEach { (code, t) -> Sender.key(false, code, gamepad, t) }
    }

    fun remove() {
        // Let go of anything still held.
        for ((id, t) in held) layout.controls.firstOrNull { it.id == id }?.let { Sender.release(it, t) }
        held.clear()
        for (c in layout.controls) if (c.kind == "stick") stickReleased(c)
        for (v in windows) try { wm.removeView(v) } catch (e: Exception) { }
        windows.clear()
    }
}

/** Drag a small window around; a tap without moving runs onTap. */
class DragOrTap(
    private val wm: WindowManager,
    private val params: WindowManager.LayoutParams,
    private val ctx: Context,
    private val onTap: () -> Unit,
    private val onMoved: (Int, Int) -> Unit,
) : View.OnTouchListener {
    private var sx = 0
    private var sy = 0
    private var tx = 0f
    private var ty = 0f
    private var moved = false

    override fun onTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sx = params.x; sy = params.y; tx = e.rawX; ty = e.rawY; moved = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - tx
                val dy = e.rawY - ty
                if (abs(dx) > Win.dp(ctx, 8f) || abs(dy) > Win.dp(ctx, 8f)) moved = true
                if (moved) {
                    val screen = Win.screen(wm)
                    params.x = (sx + dx.toInt()).coerceIn(0, (screen.width() - v.width).coerceAtLeast(0))
                    params.y = (sy + dy.toInt()).coerceIn(0, (screen.height() - v.height).coerceAtLeast(0))
                    wm.updateViewLayout(v, params)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (moved) onMoved(params.x, params.y) else {
                    v.performClick()
                    onTap()
                }
            }
        }
        return true
    }
}

/**
 * Full-screen editor drawn over whatever is on screen, so you can place buttons
 * exactly where they're needed in the game.
 */
class Editor(
    private val ctx: Context,
    private val wm: WindowManager,
    start: CustomLayout,
    private val onDone: (CustomLayout?) -> Unit,
) {
    private var layout = start
    private val controls = start.controls.toMutableList()
    private var selectedId: String? = null
    private val root = FrameLayout(ctx)
    private val canvas = FrameLayout(ctx)
    private val panel = LinearLayout(ctx)
    private var picker: View? = null

    fun current(): CustomLayout = layout.copy(controls = controls.toList())

    fun show() {
        root.setBackgroundColor(Color.argb(90, 0, 0, 0))
        root.addView(canvas, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        canvas.setOnClickListener { select(null) }

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(Win.dp(ctx, 6f), Win.dp(ctx, 6f), Win.dp(ctx, 6f), Win.dp(ctx, 6f))
            background = GradientDrawable().apply {
                cornerRadius = Win.dp(ctx, 12f).toFloat()
                setColor(Color.argb(230, 14, 18, 24))
            }
        }
        bar.addView(Win.chip(ctx, "+ Button") { addButton() })
        bar.addView(Win.gap(ctx, 6f, 1f))
        bar.addView(Win.chip(ctx, "+ Stick") { addStick() })
        bar.addView(Win.gap(ctx, 6f, 1f))
        bar.addView(Win.chip(ctx, "Save", Look.pressed) { onDone(current()) })
        bar.addView(Win.gap(ctx, 6f, 1f))
        bar.addView(Win.chip(ctx, "Cancel") { onDone(null) })
        root.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL,
        ).apply { topMargin = Win.dp(ctx, 8f) })

        panel.orientation = LinearLayout.HORIZONTAL
        panel.setPadding(Win.dp(ctx, 6f), Win.dp(ctx, 6f), Win.dp(ctx, 6f), Win.dp(ctx, 6f))
        panel.background = GradientDrawable().apply {
            cornerRadius = Win.dp(ctx, 12f).toFloat()
            setColor(Color.argb(230, 14, 18, 24))
        }
        panel.visibility = View.GONE
        root.addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = Win.dp(ctx, 12f) })

        val params = Win.params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        wm.addView(root, params)
        root.post { drawControls() }
    }

    fun remove() {
        try { wm.removeView(root) } catch (e: Exception) { }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun drawControls() {
        canvas.removeAllViews()
        val w = canvas.width.coerceAtLeast(1)
        val h = canvas.height.coerceAtLeast(1)
        for (c in controls) {
            val size = Win.dp(ctx, c.size.toFloat())
            val v: View = if (c.kind == "stick") {
                StickView(ctx, c).apply { interactive = false; chosen = c.id == selectedId }
            } else {
                ButtonView(ctx, c).apply { chosen = c.id == selectedId }
            }
            val lp = FrameLayout.LayoutParams(size, size).apply {
                leftMargin = (c.x * w).toInt().coerceIn(0, (w - size).coerceAtLeast(0))
                topMargin = (c.y * h).toInt().coerceIn(0, (h - size).coerceAtLeast(0))
            }
            v.setOnTouchListener(EditDrag(c.id, lp, size))
            canvas.addView(v, lp)
        }
    }

    private inner class EditDrag(
        private val id: String,
        private val lp: FrameLayout.LayoutParams,
        private val size: Int,
    ) : View.OnTouchListener {
        private var sx = 0
        private var sy = 0
        private var tx = 0f
        private var ty = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = lp.leftMargin; sy = lp.topMargin; tx = e.rawX; ty = e.rawY; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - tx
                    val dy = e.rawY - ty
                    if (abs(dx) > Win.dp(ctx, 6f) || abs(dy) > Win.dp(ctx, 6f)) moved = true
                    if (moved) {
                        lp.leftMargin = (sx + dx.toInt()).coerceIn(0, (canvas.width - size).coerceAtLeast(0))
                        lp.topMargin = (sy + dy.toInt()).coerceIn(0, (canvas.height - size).coerceAtLeast(0))
                        v.layoutParams = lp
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        val i = controls.indexOfFirst { it.id == id }
                        if (i >= 0) {
                            controls[i] = controls[i].copy(
                                x = lp.leftMargin / canvas.width.coerceAtLeast(1).toFloat(),
                                y = lp.topMargin / canvas.height.coerceAtLeast(1).toFloat(),
                            )
                        }
                    } else {
                        v.performClick()
                        select(id)
                    }
                }
            }
            return true
        }
    }

    private fun selected(): Control? = controls.firstOrNull { it.id == selectedId }

    private fun replace(c: Control) {
        val i = controls.indexOfFirst { it.id == c.id }
        if (i >= 0) controls[i] = c
        drawControls()
        select(c.id)
    }

    private fun select(id: String?) {
        selectedId = id
        drawControls()
        panel.removeAllViews()
        val c = selected()
        if (c == null) {
            panel.visibility = View.GONE
            return
        }
        panel.visibility = View.VISIBLE
        panel.addView(Win.chip(ctx, if (c.kind == "stick") "Type: ${c.displayLabel()}" else "Key: ${c.displayLabel()}") {
            openPicker(c)
        })
        panel.addView(Win.gap(ctx, 6f, 1f))
        panel.addView(Win.chip(ctx, "Smaller") { replace(c.copy(size = (c.size - 8).coerceAtLeast(36))) })
        panel.addView(Win.gap(ctx, 6f, 1f))
        panel.addView(Win.chip(ctx, "Bigger") { replace(c.copy(size = (c.size + 8).coerceAtMost(220))) })
        panel.addView(Win.gap(ctx, 6f, 1f))
        panel.addView(Win.chip(ctx, "Delete", Color.parseColor("#7A2E2E")) {
            controls.removeAll { it.id == c.id }
            select(null)
        })
    }

    private fun addButton() {
        val c = Control(kind = "key", x = 0.45f, y = 0.4f, size = 64, label = "Space", code = KeyEvent.KEYCODE_SPACE)
        controls += c
        select(c.id)
        openPicker(c)
    }

    private fun addStick() {
        val c = Control(kind = "stick", x = 0.06f, y = 0.5f, size = 140, stick = "wasd")
        controls += c
        select(c.id)
        openPicker(c)
    }

    private fun closePicker() {
        picker?.let { root.removeView(it) }
        picker = null
    }

    /** Lets you choose what a button presses (with optional Ctrl/Alt/Shift/Win), or what a stick does. */
    private fun openPicker(c: Control) {
        closePicker()
        var mods = c.mods
        val list = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Win.dp(ctx, 12f), Win.dp(ctx, 12f), Win.dp(ctx, 12f), Win.dp(ctx, 12f))
        }
        fun title(t: String) = TextView(ctx).apply {
            text = t
            setTextColor(Color.parseColor("#9AA4B2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, Win.dp(ctx, 10f), 0, Win.dp(ctx, 4f))
        }
        fun grid(items: List<View>, perRow: Int) {
            for (chunk in items.chunked(perRow)) {
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                for (v in chunk) {
                    row.addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(Win.dp(ctx, 2f), Win.dp(ctx, 2f), Win.dp(ctx, 2f), Win.dp(ctx, 2f))
                    })
                }
                repeat(perRow - chunk.size) {
                    row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
                }
                list.addView(row)
            }
        }

        if (c.kind == "stick") {
            list.addView(title("What should this stick do?"))
            for ((mode, label) in Catalogue.stickModes) {
                list.addView(Win.chip(ctx, label, if (mode == c.stick) Look.pressed else Color.parseColor("#2A3340")) {
                    closePicker()
                    replace(c.copy(stick = mode))
                }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = Win.dp(ctx, 4f) })
                list.addView(Win.gap(ctx, 1f, 4f))
            }
        } else {
            list.addView(title("Hold with (for combos like Ctrl+S)"))
            val modChips = mutableListOf<TextView>()
            fun paintMods() {
                ModBits.all.forEachIndexed { i, (bit, _) ->
                    (modChips[i].background as GradientDrawable)
                        .setColor(if ((mods and bit) != 0) Look.pressed else Color.parseColor("#2A3340"))
                }
            }
            for ((bit, name) in ModBits.all) {
                modChips += Win.chip(ctx, name) {
                    mods = mods xor bit
                    paintMods()
                }
            }
            grid(modChips, 4)
            paintMods()

            fun choose(k: KeyChoice) {
                closePicker()
                replace(c.copy(label = k.label, code = k.code, gamepad = k.gamepad, mods = if (k.gamepad) 0 else mods))
            }
            list.addView(title("Keyboard"))
            grid(Catalogue.keyboard.map { k -> Win.chip(ctx, k.label) { choose(k) } }, 8)
            list.addView(title("Gamepad"))
            grid(Catalogue.gamepad.map { k -> Win.chip(ctx, k.label, Look.gamepad) { choose(k) } }, 6)
        }
        list.addView(Win.gap(ctx, 1f, 10f))
        list.addView(Win.chip(ctx, "Close") { closePicker() })

        val scroll = ScrollView(ctx).apply {
            addView(list)
            background = GradientDrawable().apply {
                cornerRadius = Win.dp(ctx, 14f).toFloat()
                setColor(Color.argb(245, 14, 18, 24))
            }
            isClickable = true // don't let taps fall through to the editor behind
        }
        val screen = Win.screen(wm)
        root.addView(scroll, FrameLayout.LayoutParams(
            (screen.width() * 0.9f).toInt().coerceAtMost(Win.dp(ctx, 640f)),
            (screen.height() * 0.8f).toInt(),
            Gravity.CENTER,
        ))
        picker = scroll
    }
}
