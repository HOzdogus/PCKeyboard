package com.hozdogus.pckeyboard

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val bg = Color.parseColor("#0E1218")
    private val card = Color.parseColor("#1A212B")
    private val muted = Color.parseColor("#9AA4B2")
    private val good = Color.parseColor("#7CE38B")
    private val warn = Color.parseColor("#FFC24B")

    private class Step(val status: TextView, val button: Button)

    private lateinit var stepInstall: Step
    private lateinit var stepRunning: Step
    private lateinit var stepPermission: Step
    private lateinit var stepOverlay: Step
    private lateinit var mainButton: Button

    private val refreshLoop = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }
    private val shizukuListener: () -> Unit = { handler.post { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        root.addView(text("PC Keyboard", 26f, Color.WHITE, bold = true))
        root.addView(text("A full PC keyboard that floats over everything and works in any app, games included.", 13f, muted))
        root.addView(space(16))

        root.addView(text("Setup", 18f, Color.WHITE, bold = true))
        root.addView(space(8))
        stepInstall = addStep(root, "1. Install Shizuku", "Free on the Play Store. It lets PC Keyboard press keys like a real keyboard.", "Get Shizuku") {
            openStore()
        }
        stepRunning = addStep(
            root, "2. Start Shizuku",
            "In Shizuku, use \"Start via Wireless debugging\" and follow its steps. " +
                "After you restart your phone, open Shizuku and tap Start again.",
            "Open Shizuku",
        ) { openShizuku() }
        stepPermission = addStep(root, "3. Allow PC Keyboard in Shizuku", "Tap the button, then Allow.", "Allow") {
            ShizukuLink.start()
            ShizukuLink.requestPermission(1)
        }
        stepOverlay = addStep(root, "4. Allow it to float over apps", "Turn on \"Display over other apps\" for PC Keyboard.", "Allow") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        root.addView(space(12))

        mainButton = Button(this).apply { setOnClickListener { toggleKeyboard() } }
        root.addView(mainButton)
        root.addView(space(20))

        // Appearance
        root.addView(text("Appearance", 18f, Color.WHITE, bold = true))
        root.addView(space(8))
        val look = cardLayout()
        look.addView(slider("Key size", Prefs.KEY_HEIGHT, 40, 28, 60, "dp"))
        look.addView(slider("Opacity", Prefs.OPACITY, 92, 40, 100, "%"))
        root.addView(look)
        root.addView(space(20))

        root.addView(text("How to use it", 18f, Color.WHITE, bold = true))
        root.addView(space(8))
        root.addView(cardLayout().apply {
            addView(
                text(
                    "• Hold a key to keep it pressed, like W to walk in a game.\n" +
                        "• Ctrl, Alt, Shift and Win: hold them while pressing another key, tap once to " +
                        "use for the next key only, or hold on their own for half a second to lock them on.\n" +
                        "• Drag the top bar to move the keyboard. — shrinks it to a bubble you can drag; tap the bubble to bring it back.\n" +
                        "• Keys go to whatever app is on screen. If you need to type into a text box, tap the box first.",
                    14f, Color.WHITE,
                ),
            )
        })

        setContentView(scroll)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        ShizukuLink.addListener(shizukuListener)
        ShizukuLink.start()
    }

    override fun onDestroy() {
        ShizukuLink.removeListener(shizukuListener)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        ShizukuLink.connect()
        handler.post(refreshLoop)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshLoop)
        super.onPause()
    }

    private fun refresh() {
        val installed = ShizukuLink.isInstalled(this)
        val runningNow = ShizukuLink.isRunning()
        val allowed = ShizukuLink.hasPermission()
        val overlay = Settings.canDrawOverlays(this)
        val ready = ShizukuLink.ready()

        setStep(stepInstall, installed, enabled = true)
        setStep(stepRunning, runningNow, enabled = installed)
        setStep(
            stepPermission, allowed, enabled = runningNow,
            doneText = if (allowed && !ready) "Allowed. Connecting…" else null,
        )
        setStep(stepOverlay, overlay, enabled = true)

        mainButton.text = when {
            KeyboardService.running -> "Turn off keyboard"
            !overlay -> "Finish step 4 to show the keyboard"
            else -> "Show keyboard"
        }
        mainButton.isEnabled = KeyboardService.running || overlay
    }

    private fun setStep(step: Step, done: Boolean, enabled: Boolean, doneText: String? = null) {
        step.status.text = if (done) (doneText ?: "✓ Done") else "Not done yet"
        step.status.setTextColor(if (done) good else warn)
        step.button.visibility = if (done) View.GONE else View.VISIBLE
        step.button.isEnabled = enabled
    }

    private fun toggleKeyboard() {
        if (KeyboardService.running) {
            startService(Intent(this, KeyboardService::class.java).setAction(KeyboardService.ACTION_STOP))
        } else {
            startForegroundService(Intent(this, KeyboardService::class.java))
        }
        handler.postDelayed({ refresh() }, 300)
    }

    private fun openStore() {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ShizukuLink.SHIZUKU_PACKAGE}"))
        try {
            startActivity(market)
        } catch (e: Exception) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=${ShizukuLink.SHIZUKU_PACKAGE}"),
                ),
            )
        }
    }

    private fun openShizuku() {
        val launch = packageManager.getLaunchIntentForPackage(ShizukuLink.SHIZUKU_PACKAGE)
        if (launch != null) startActivity(launch) else openStore()
    }

    // ---- UI helpers ----

    private fun addStep(parent: LinearLayout, title: String, hint: String, buttonLabel: String, onClick: () -> Unit): Step {
        val box = cardLayout()
        box.addView(text(title, 16f, Color.WHITE, bold = true))
        box.addView(text(hint, 13f, muted))
        val status = text("", 13f, warn).apply { setPadding(0, dp(6), 0, 0) }
        box.addView(status)
        val button = Button(this).apply {
            text = buttonLabel
            setOnClickListener { onClick() }
        }
        box.addView(button)
        parent.addView(box)
        parent.addView(space(8))
        return Step(status, button)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(s: String, size: Float, colour: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(colour)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun space(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(h))
    }

    private fun cardLayout() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(card)
        }
    }

    private fun slider(label: String, key: String, default: Int, min: Int, max: Int, unit: String): View {
        val prefs = Prefs.get(this)
        val current = prefs.getInt(key, default).coerceIn(min, max)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title = text("$label: $current$unit", 14f, Color.WHITE)
        box.addView(title)
        box.addView(SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = current
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    title.text = "$label: $value$unit"
                    prefs.edit().putInt(key, value).apply()
                }

                override fun onStartTrackingTouch(bar: SeekBar) {}

                override fun onStopTrackingTouch(bar: SeekBar) {
                    // Redraw the keyboard with the new look, if it's open.
                    if (KeyboardService.running) {
                        startService(
                            Intent(this@MainActivity, KeyboardService::class.java)
                                .setAction(KeyboardService.ACTION_REBUILD),
                        )
                    }
                }
            })
        })
        return box
    }
}
