package com.hozdogus.pckeyboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.res.Configuration
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class KeyboardService : Service() {

    companion object {
        const val ACTION_STOP = "com.hozdogus.pckeyboard.STOP"
        const val ACTION_EXPAND = "com.hozdogus.pckeyboard.EXPAND"
        const val ACTION_REBUILD = "com.hozdogus.pckeyboard.REBUILD"
        private const val CHANNEL_ID = "keyboard"
        private const val NOTIF_ID = 1
        private const val REPEAT_DELAY = 400L
        private const val REPEAT_EVERY = 45L
        private const val LOCK_AFTER = 450L

        @Volatile
        var running = false
            private set
    }

    private enum class ModState { OFF, ONCE, LOCKED }

    private class Press(
        val code: Int,
        val meta: Int,
        val downTime: Long,
        /** Sticky modifiers pressed around this key. */
        val wrapped: List<Mod>,
        val repeat: Runnable,
    )

    private val handler = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null

    private var keyboard: LinearLayout? = null
    private var bubble: TextView? = null
    private lateinit var kbParams: WindowManager.LayoutParams
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private var statusView: TextView? = null
    private val keyViews = mutableListOf<Pair<Key, TextView>>()

    private val modState = mutableMapOf(
        Mod.CTRL to ModState.OFF, Mod.ALT to ModState.OFF, Mod.SHIFT to ModState.OFF, Mod.WIN to ModState.OFF,
    )
    private val modHeld = mutableSetOf<Mod>()
    private val modUsed = mutableSetOf<Mod>()
    private val modDownAt = mutableMapOf<Mod, Long>()
    private var caps = false
    private val presses = HashMap<View, Press>()

    private val statusListener: () -> Unit = { handler.post { updateStatus() } }

    // Colours
    private val cBg = Color.parseColor("#0E1218")
    private val cKey = Color.parseColor("#2A3340")
    private val cSpecial = Color.parseColor("#1F2630")
    private val cPressed = Color.parseColor("#4FA3FF")
    private val cOnce = Color.parseColor("#2F6FB3")
    private val cMuted = Color.parseColor("#9AA4B2")
    private val cWarn = Color.parseColor("#FFC24B")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        running = true
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        ShizukuLink.addListener(statusListener)
        ShizukuLink.start()
        if (Prefs.get(this).getBoolean(Prefs.EXPANDED, true)) showKeyboard() else showBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_EXPAND -> if (running) showKeyboard()
            ACTION_REBUILD -> if (running && keyboard != null) {
                removeKeyboard()
                showKeyboard()
            }
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Screen rotated: redraw for the new size and keep everything on screen.
        handler.post {
            if (!running) return@post
            if (keyboard != null) {
                removeKeyboard()
                showKeyboard()
            } else if (bubble != null) {
                removeBubble()
                showBubble()
            }
        }
    }

    override fun onDestroy() {
        running = false
        releaseEverything()
        ShizukuLink.removeListener(statusListener)
        removeKeyboard()
        removeBubble()
        super.onDestroy()
    }

    // ---------------- notification ----------------

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Keyboard", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val show = PendingIntent.getService(
            this, 1, Intent(this, KeyboardService::class.java).setAction(ACTION_EXPAND),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, KeyboardService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle("PC Keyboard is on")
            .setContentText("Tap for settings")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Show keyboard", show).build())
            .addAction(Notification.Action.Builder(null, "Turn off", stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    // ---------------- windows ----------------

    private fun overlayParams(width: Int) = WindowManager.LayoutParams(
        width,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        // Never take focus, so key presses go to the app underneath.
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_SPLIT_TOUCH or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }

    private fun showKeyboard() {
        removeBubble()
        if (keyboard != null) return
        val prefs = Prefs.get(this)
        prefs.edit().putBoolean(Prefs.EXPANDED, true).apply()
        kbParams = overlayParams(WindowManager.LayoutParams.MATCH_PARENT).apply {
            x = 0
            y = prefs.getInt(yKey(), Int.MAX_VALUE) // MAX = not moved yet: sit at the bottom
        }
        val view = buildKeyboard()
        keyboard = view
        wm?.addView(view, kbParams)
        // Once it has a size, make sure it's fully on screen.
        view.post { keepKeyboardOnScreen() }
        updateStatus()
        relabel()
    }

    private fun removeKeyboard() {
        releaseEverything()
        keyboard?.let { wm?.removeView(it) }
        keyboard = null
        statusView = null
        keyViews.clear()
    }

    private fun showBubble() {
        removeKeyboard()
        if (bubble != null) return
        val prefs = Prefs.get(this)
        prefs.edit().putBoolean(Prefs.EXPANDED, false).apply()
        bubbleParams = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = prefs.getInt(Prefs.BUBBLE_X, dp(12))
            y = prefs.getInt(Prefs.BUBBLE_Y, screenHeight() / 3)
            val size = dp(48)
            x = x.coerceIn(0, (screenWidth() - size).coerceAtLeast(0))
            y = y.coerceIn(0, (screenHeight() - size).coerceAtLeast(0))
        }
        val size = dp(48)
        val b = TextView(this).apply {
            text = "⌨"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setWidth(size)
            setHeight(size)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(220, 30, 42, 56))
                setStroke(dp(2), cPressed)
            }
        }
        b.setOnTouchListener(BubbleTouch())
        bubble = b
        wm?.addView(b, bubbleParams)
    }

    private fun removeBubble() {
        bubble?.let { wm?.removeView(it) }
        bubble = null
    }

    // ---------------- building the keyboard ----------------

    private fun buildKeyboard(): LinearLayout {
        val prefs = Prefs.get(this)
        val chosenH = prefs.getInt(Prefs.KEY_HEIGHT, 40).coerceIn(28, 60)
        // In landscape, shrink keys if needed so the keyboard uses at most ~60% of the screen height.
        val screenHdp = screenHeight() / resources.displayMetrics.density
        val fitH = ((screenHdp * 0.6f - 34f) / Layout.rows.size).toInt()
        val keyH = if (isLandscape()) chosenH.coerceAtMost(fitH.coerceAtLeast(24)) else chosenH
        val opacity = prefs.getInt(Prefs.OPACITY, 92).coerceIn(40, 100)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(3), dp(2), dp(3), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(cBg)
            }
            alpha = opacity / 100f
        }

        // Top bar: drag handle + status, minimise, close.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val handle = TextView(this).apply {
            setTextColor(cMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
            layoutParams = LinearLayout.LayoutParams(0, dp(28), 1f)
            gravity = Gravity.CENTER_VERTICAL
        }
        handle.setOnTouchListener(DragKeyboard())
        statusView = handle
        bar.addView(handle)
        bar.addView(barButton("—") { showBubble() })
        bar.addView(barButton("✕") { stopSelf() })
        root.addView(bar)

        val labelSize = (keyH * 0.36f).coerceIn(11f, 20f)
        keyViews.clear()
        for (row in Layout.rows) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(keyH))
            }
            for (key in row) {
                val tv = TextView(this).apply {
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    maxLines = 1
                    val small = key.label.length > 2
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) labelSize * 0.62f else labelSize)
                    if (small) typeface = Typeface.DEFAULT_BOLD
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, key.weight).apply {
                        setMargins(dp(1.5f), dp(1.5f), dp(1.5f), dp(1.5f))
                    }
                }
                tv.setOnTouchListener { v, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> press(key, v)
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release(key, v)
                    }
                    true
                }
                keyViews += key to tv
                line.addView(tv)
            }
            root.addView(line)
        }
        return root
    }

    private fun barButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(28))
        setOnClickListener { onClick() }
    }

    private fun updateStatus() {
        val v = statusView ?: return
        if (ShizukuLink.ready()) {
            v.text = "⠿  PC Keyboard"
            v.setTextColor(cMuted)
        } else {
            v.text = "⠿  Not connected to Shizuku. Open the PC Keyboard app."
            v.setTextColor(cWarn)
        }
    }

    // ---------------- key handling ----------------

    private fun modCode(m: Mod) = when (m) {
        Mod.CTRL -> KeyEvent.KEYCODE_CTRL_LEFT
        Mod.ALT -> KeyEvent.KEYCODE_ALT_LEFT
        Mod.SHIFT -> KeyEvent.KEYCODE_SHIFT_LEFT
        Mod.WIN -> KeyEvent.KEYCODE_META_LEFT
    }

    private fun modMeta(m: Mod) = when (m) {
        Mod.CTRL -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        Mod.ALT -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        Mod.SHIFT -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        Mod.WIN -> KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
    }

    private fun metaOf(mods: Collection<Mod>): Int = mods.fold(0) { acc, m -> acc or modMeta(m) }

    private fun isActive(m: Mod) = m in modHeld || modState[m] != ModState.OFF

    private fun isLetter(k: Key) = k.type == KeyType.CHAR && k.ch in 'a'..'z'

    private fun press(key: Key, v: View) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when (key.type) {
            KeyType.CAPS -> {
                caps = !caps
                relabel()
            }
            KeyType.MOD -> {
                val m = key.mod ?: return
                modHeld += m
                modUsed -= m
                modDownAt[m] = SystemClock.uptimeMillis()
                // Send it straight away so holding Shift/Ctrl works in games.
                ShizukuLink.send(KeyEvent.ACTION_DOWN, key.code, metaOf(modHeld), SystemClock.uptimeMillis())
                relabel()
            }
            KeyType.CHAR, KeyType.CODE -> {
                if (!ShizukuLink.ready()) {
                    flashWarning()
                    return
                }
                val wrapped = Mod.values().filter { modState[it] != ModState.OFF && it !in modHeld }.toMutableList()
                if (isLetter(key) && caps && !isActive(Mod.SHIFT)) wrapped += Mod.SHIFT
                val all = modHeld + wrapped
                val meta = metaOf(all)
                val downTime = SystemClock.uptimeMillis()

                var running = metaOf(modHeld)
                for (m in wrapped) {
                    running = running or modMeta(m)
                    ShizukuLink.send(KeyEvent.ACTION_DOWN, modCode(m), running, downTime)
                }
                ShizukuLink.send(KeyEvent.ACTION_DOWN, key.code, meta, downTime)
                modUsed += modHeld

                // Hold to repeat, like a real keyboard (and keeps game keys held down).
                var count = 0
                val repeat = object : Runnable {
                    override fun run() {
                        count++
                        ShizukuLink.send(KeyEvent.ACTION_DOWN, key.code, meta, downTime, count)
                        handler.postDelayed(this, REPEAT_EVERY)
                    }
                }
                handler.postDelayed(repeat, REPEAT_DELAY)
                presses[v] = Press(key.code, meta, downTime, wrapped, repeat)
                v.background = keyBg(cPressed)
            }
        }
    }

    private fun release(key: Key, v: View) {
        when (key.type) {
            KeyType.CAPS -> Unit
            KeyType.MOD -> {
                val m = key.mod ?: return
                if (m !in modHeld) return
                modHeld -= m
                ShizukuLink.send(KeyEvent.ACTION_UP, key.code, metaOf(modHeld), SystemClock.uptimeMillis())
                if (m !in modUsed) {
                    val held = SystemClock.uptimeMillis() - (modDownAt[m] ?: 0L)
                    modState[m] = when {
                        modState[m] != ModState.OFF -> ModState.OFF
                        held >= LOCK_AFTER -> ModState.LOCKED
                        else -> ModState.ONCE
                    }
                }
                relabel()
            }
            KeyType.CHAR, KeyType.CODE -> {
                val p = presses.remove(v) ?: return
                handler.removeCallbacks(p.repeat)
                ShizukuLink.send(KeyEvent.ACTION_UP, p.code, p.meta, p.downTime)
                var running = p.meta
                for (m in p.wrapped.reversed()) {
                    running = running and modMeta(m).inv()
                    ShizukuLink.send(KeyEvent.ACTION_UP, modCode(m), running, SystemClock.uptimeMillis())
                }
                // One-shot modifiers are used up.
                for (m in p.wrapped) if (modState[m] == ModState.ONCE) modState[m] = ModState.OFF
                relabel()
            }
        }
    }

    /** Lets go of anything still held, e.g. when the keyboard is closed mid-press. */
    private fun releaseEverything() {
        for ((_, p) in presses) {
            handler.removeCallbacks(p.repeat)
            ShizukuLink.send(KeyEvent.ACTION_UP, p.code, p.meta, p.downTime)
            for (m in p.wrapped.reversed()) {
                ShizukuLink.send(KeyEvent.ACTION_UP, modCode(m), 0, SystemClock.uptimeMillis())
            }
        }
        presses.clear()
        for (m in modHeld) ShizukuLink.send(KeyEvent.ACTION_UP, modCode(m), 0, SystemClock.uptimeMillis())
        modHeld.clear()
    }

    private fun flashWarning() {
        val v = statusView ?: return
        v.text = "⠿  Not connected to Shizuku. Open the PC Keyboard app."
        v.setTextColor(cWarn)
        ShizukuLink.connect()
    }

    private fun keyBg(colour: Int) = GradientDrawable().apply {
        cornerRadius = dp(5).toFloat()
        setColor(colour)
    }

    /** Updates labels and colours for Shift, Caps and the modifier states. */
    private fun relabel() {
        val shiftOn = isActive(Mod.SHIFT)
        for ((key, tv) in keyViews) {
            if (presses.containsKey(tv)) continue
            when (key.type) {
                KeyType.CHAR -> {
                    tv.text = when {
                        key.label == "Space" -> "Space"
                        isLetter(key) -> if (shiftOn != caps) key.ch.uppercase() else key.ch.toString()
                        shiftOn -> key.shiftCh.toString()
                        else -> key.ch.toString()
                    }
                    tv.background = keyBg(cKey)
                }
                KeyType.CODE -> {
                    tv.text = key.label
                    tv.background = keyBg(cSpecial)
                }
                KeyType.CAPS -> {
                    tv.text = key.label
                    tv.background = keyBg(if (caps) cPressed else cSpecial)
                }
                KeyType.MOD -> {
                    val m = key.mod!!
                    val state = modState[m]
                    tv.text = if (state == ModState.LOCKED) "${key.label} 🔒" else key.label
                    tv.background = keyBg(
                        when {
                            m in modHeld || state == ModState.LOCKED -> cPressed
                            state == ModState.ONCE -> cOnce
                            else -> cSpecial
                        },
                    )
                }
            }
        }
    }

    // ---------------- dragging ----------------

    private inner class DragKeyboard : View.OnTouchListener {
        private var startY = 0
        private var touchY = 0f

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val root = keyboard ?: return true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = kbParams.y
                    touchY = e.rawY
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxY = (screenHeight() - root.height).coerceAtLeast(0)
                    kbParams.y = (startY + (e.rawY - touchY).toInt()).coerceIn(0, maxY)
                    wm?.updateViewLayout(root, kbParams)
                }
                MotionEvent.ACTION_UP -> {
                    Prefs.get(this@KeyboardService).edit().putInt(yKey(), kbParams.y).apply()
                    v.performClick()
                }
            }
            return true
        }
    }

    private inner class BubbleTouch : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (abs(dx) > dp(8) || abs(dy) > dp(8)) moved = true
                    if (moved) {
                        bubbleParams.x = startX + dx.toInt()
                        bubbleParams.y = startY + dy.toInt()
                        wm?.updateViewLayout(v, bubbleParams)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) {
                        Prefs.get(this@KeyboardService).edit()
                            .putInt(Prefs.BUBBLE_X, bubbleParams.x)
                            .putInt(Prefs.BUBBLE_Y, bubbleParams.y)
                            .apply()
                    } else {
                        v.performClick()
                        handler.post { showKeyboard() }
                    }
                }
            }
            return true
        }
    }

    private fun isLandscape() = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    /** Portrait and landscape each remember their own keyboard position. */
    private fun yKey() = if (isLandscape()) Prefs.KEYBOARD_Y + "_landscape" else Prefs.KEYBOARD_Y

    private fun screenHeight(): Int = wm?.currentWindowMetrics?.bounds?.height() ?: resources.displayMetrics.heightPixels

    private fun screenWidth(): Int = wm?.currentWindowMetrics?.bounds?.width() ?: resources.displayMetrics.widthPixels

    private fun keepKeyboardOnScreen() {
        val root = keyboard ?: return
        val maxY = (screenHeight() - root.height).coerceAtLeast(0)
        val y = kbParams.y.coerceIn(0, maxY)
        if (y != kbParams.y) {
            kbParams.y = y
            wm?.updateViewLayout(root, kbParams)
        }
    }

    private fun dp(v: Int): Int = dp(v.toFloat())

    private fun dp(v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
}
