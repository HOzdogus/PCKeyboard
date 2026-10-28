package com.hozdogus.pckeyboard

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Something a button can press. */
data class KeyChoice(val label: String, val code: Int, val gamepad: Boolean = false)

object Catalogue {
    val keyboard: List<KeyChoice> = buildList {
        ('A'..'Z').forEach { add(KeyChoice(it.toString(), KeyEvent.KEYCODE_A + (it - 'A'))) }
        (0..9).forEach { add(KeyChoice(it.toString(), KeyEvent.KEYCODE_0 + it)) }
        add(KeyChoice("Space", KeyEvent.KEYCODE_SPACE))
        add(KeyChoice("Enter", KeyEvent.KEYCODE_ENTER))
        add(KeyChoice("Esc", KeyEvent.KEYCODE_ESCAPE))
        add(KeyChoice("Tab", KeyEvent.KEYCODE_TAB))
        add(KeyChoice("⌫", KeyEvent.KEYCODE_DEL))
        add(KeyChoice("Del", KeyEvent.KEYCODE_FORWARD_DEL))
        add(KeyChoice("Shift", KeyEvent.KEYCODE_SHIFT_LEFT))
        add(KeyChoice("Ctrl", KeyEvent.KEYCODE_CTRL_LEFT))
        add(KeyChoice("Alt", KeyEvent.KEYCODE_ALT_LEFT))
        add(KeyChoice("Win", KeyEvent.KEYCODE_META_LEFT))
        add(KeyChoice("↑", KeyEvent.KEYCODE_DPAD_UP))
        add(KeyChoice("↓", KeyEvent.KEYCODE_DPAD_DOWN))
        add(KeyChoice("←", KeyEvent.KEYCODE_DPAD_LEFT))
        add(KeyChoice("→", KeyEvent.KEYCODE_DPAD_RIGHT))
        add(KeyChoice("Home", KeyEvent.KEYCODE_MOVE_HOME))
        add(KeyChoice("End", KeyEvent.KEYCODE_MOVE_END))
        add(KeyChoice("PgUp", KeyEvent.KEYCODE_PAGE_UP))
        add(KeyChoice("PgDn", KeyEvent.KEYCODE_PAGE_DOWN))
        (1..12).forEach { add(KeyChoice("F$it", KeyEvent.KEYCODE_F1 + it - 1)) }
        add(KeyChoice("`", KeyEvent.KEYCODE_GRAVE))
        add(KeyChoice("-", KeyEvent.KEYCODE_MINUS))
        add(KeyChoice("=", KeyEvent.KEYCODE_EQUALS))
        add(KeyChoice("[", KeyEvent.KEYCODE_LEFT_BRACKET))
        add(KeyChoice("]", KeyEvent.KEYCODE_RIGHT_BRACKET))
        add(KeyChoice("\\", KeyEvent.KEYCODE_BACKSLASH))
        add(KeyChoice(";", KeyEvent.KEYCODE_SEMICOLON))
        add(KeyChoice("'", KeyEvent.KEYCODE_APOSTROPHE))
        add(KeyChoice(",", KeyEvent.KEYCODE_COMMA))
        add(KeyChoice(".", KeyEvent.KEYCODE_PERIOD))
        add(KeyChoice("/", KeyEvent.KEYCODE_SLASH))
    }

    val gamepad: List<KeyChoice> = listOf(
        KeyChoice("A", KeyEvent.KEYCODE_BUTTON_A, true),
        KeyChoice("B", KeyEvent.KEYCODE_BUTTON_B, true),
        KeyChoice("X", KeyEvent.KEYCODE_BUTTON_X, true),
        KeyChoice("Y", KeyEvent.KEYCODE_BUTTON_Y, true),
        KeyChoice("L1", KeyEvent.KEYCODE_BUTTON_L1, true),
        KeyChoice("R1", KeyEvent.KEYCODE_BUTTON_R1, true),
        KeyChoice("L2", KeyEvent.KEYCODE_BUTTON_L2, true),
        KeyChoice("R2", KeyEvent.KEYCODE_BUTTON_R2, true),
        KeyChoice("L3", KeyEvent.KEYCODE_BUTTON_THUMBL, true),
        KeyChoice("R3", KeyEvent.KEYCODE_BUTTON_THUMBR, true),
        KeyChoice("Start", KeyEvent.KEYCODE_BUTTON_START, true),
        KeyChoice("Select", KeyEvent.KEYCODE_BUTTON_SELECT, true),
        KeyChoice("D↑", KeyEvent.KEYCODE_DPAD_UP, true),
        KeyChoice("D↓", KeyEvent.KEYCODE_DPAD_DOWN, true),
        KeyChoice("D←", KeyEvent.KEYCODE_DPAD_LEFT, true),
        KeyChoice("D→", KeyEvent.KEYCODE_DPAD_RIGHT, true),
    )

    /** What a stick does when you push it. */
    val stickModes = listOf(
        "wasd" to "W A S D keys",
        "arrows" to "Arrow keys",
        "dpad" to "Gamepad D-pad",
        "left" to "Left analog stick (experimental)",
        "right" to "Right analog stick (experimental)",
    )

    fun stickLabel(mode: String) = when (mode) {
        "wasd" -> "WASD"
        "arrows" -> "↑↓←→"
        "dpad" -> "D-pad"
        "left" -> "L stick"
        "right" -> "R stick"
        else -> mode
    }

    fun gamepadSource() = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_KEYBOARD
}

/** Modifier bits for combos like Ctrl+S. */
object ModBits {
    const val CTRL = 1
    const val ALT = 2
    const val SHIFT = 4
    const val WIN = 8

    val all = listOf(CTRL to "Ctrl", ALT to "Alt", SHIFT to "Shift", WIN to "Win")

    fun prefix(bits: Int) = all.filter { (bits and it.first) != 0 }.joinToString("") { it.second + "+" }
}

/**
 * One button or stick on a custom layout. x/y are its top-left corner as a
 * fraction of the screen, so layouts survive rotating the phone.
 */
data class Control(
    val id: String = UUID.randomUUID().toString(),
    /** "key" or "stick". */
    val kind: String,
    val x: Float,
    val y: Float,
    /** Width/height in dp. */
    val size: Int,
    // Buttons
    val label: String = "",
    val code: Int = 0,
    val gamepad: Boolean = false,
    val mods: Int = 0,
    // Sticks
    val stick: String = "wasd",
) {
    fun displayLabel(): String =
        if (kind == "stick") Catalogue.stickLabel(stick) else ModBits.prefix(mods) + label
}

data class CustomLayout(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val controls: List<Control> = emptyList(),
)

/** Saves layouts on the phone. "" as the active layout means the full keyboard. */
object LayoutStore {
    private const val LAYOUTS = "layouts"
    private const val ACTIVE = "active_layout"

    fun load(context: Context): List<CustomLayout> {
        val text = Prefs.get(context).getString(LAYOUTS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val cs = o.optJSONArray("controls") ?: JSONArray()
                CustomLayout(
                    id = o.getString("id"),
                    name = o.optString("name", "Layout"),
                    controls = (0 until cs.length()).map { j ->
                        val c = cs.getJSONObject(j)
                        Control(
                            id = c.optString("id", UUID.randomUUID().toString()),
                            kind = c.optString("kind", "key"),
                            x = c.optDouble("x", 0.4).toFloat(),
                            y = c.optDouble("y", 0.4).toFloat(),
                            size = c.optInt("size", 64),
                            label = c.optString("label", ""),
                            code = c.optInt("code", 0),
                            gamepad = c.optBoolean("gamepad", false),
                            mods = c.optInt("mods", 0),
                            stick = c.optString("stick", "wasd"),
                        )
                    },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, layouts: List<CustomLayout>) {
        val arr = JSONArray()
        for (l in layouts) {
            val cs = JSONArray()
            for (c in l.controls) {
                cs.put(
                    JSONObject()
                        .put("id", c.id).put("kind", c.kind)
                        .put("x", c.x.toDouble()).put("y", c.y.toDouble()).put("size", c.size)
                        .put("label", c.label).put("code", c.code).put("gamepad", c.gamepad)
                        .put("mods", c.mods).put("stick", c.stick),
                )
            }
            arr.put(JSONObject().put("id", l.id).put("name", l.name).put("controls", cs))
        }
        Prefs.get(context).edit().putString(LAYOUTS, arr.toString()).apply()
    }

    fun find(context: Context, id: String): CustomLayout? = load(context).firstOrNull { it.id == id }

    fun upsert(context: Context, layout: CustomLayout) {
        val all = load(context)
        save(context, if (all.any { it.id == layout.id }) all.map { if (it.id == layout.id) layout else it } else all + layout)
    }

    fun delete(context: Context, id: String) {
        save(context, load(context).filter { it.id != id })
        if (active(context) == id) setActive(context, "")
    }

    fun active(context: Context): String = Prefs.get(context).getString(ACTIVE, "") ?: ""

    fun setActive(context: Context, id: String) {
        Prefs.get(context).edit().putString(ACTIVE, id).apply()
    }
}
