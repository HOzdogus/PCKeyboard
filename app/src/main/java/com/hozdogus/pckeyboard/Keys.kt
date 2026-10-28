package com.hozdogus.pckeyboard

import android.view.KeyEvent

enum class KeyType { CHAR, CODE, MOD, CAPS }

enum class Mod { CTRL, ALT, SHIFT, WIN }

data class Key(
    val label: String,
    val type: KeyType,
    val weight: Float = 1f,
    /** For CHAR keys. */
    val ch: Char = ' ',
    val shiftCh: Char = ' ',
    /** Android key code, used for CODE keys and for CHAR keys pressed with Ctrl/Alt/Win. */
    val code: Int = 0,
    val mod: Mod? = null,
    /** Keeps firing while held. */
    val repeat: Boolean = false,
)

object Layout {
    private fun ch(c: Char, shift: Char, code: Int, w: Float = 1f) =
        Key(c.toString(), KeyType.CHAR, w, c, shift, code)

    private fun letter(c: Char) = ch(c, c.uppercaseChar(), KeyEvent.KEYCODE_A + (c - 'a'))

    private fun code(label: String, code: Int, w: Float = 1f, repeat: Boolean = false) =
        Key(label, KeyType.CODE, w, code = code, repeat = repeat)

    private fun mod(label: String, m: Mod, code: Int, w: Float = 1f) =
        Key(label, KeyType.MOD, w, code = code, mod = m)

    private val digitShift = ")!@#$%^&*("

    private fun digit(d: Int) = ch('0' + d, digitShift[d], KeyEvent.KEYCODE_0 + d)

    val rows: List<List<Key>> = listOf(
        listOf(code("Esc", KeyEvent.KEYCODE_ESCAPE)) +
            (1..12).map { code("F$it", KeyEvent.KEYCODE_F1 + it - 1) } +
            listOf(code("Del", KeyEvent.KEYCODE_FORWARD_DEL, repeat = true)),

        listOf(ch('`', '~', KeyEvent.KEYCODE_GRAVE)) +
            (1..9).map { digit(it) } + listOf(digit(0)) +
            listOf(
                ch('-', '_', KeyEvent.KEYCODE_MINUS),
                ch('=', '+', KeyEvent.KEYCODE_EQUALS),
                code("⌫", KeyEvent.KEYCODE_DEL, 1.6f, repeat = true),
            ),

        listOf(code("Tab", KeyEvent.KEYCODE_TAB, 1.4f)) +
            "qwertyuiop".map { letter(it) } +
            listOf(
                ch('[', '{', KeyEvent.KEYCODE_LEFT_BRACKET),
                ch(']', '}', KeyEvent.KEYCODE_RIGHT_BRACKET),
                ch('\\', '|', KeyEvent.KEYCODE_BACKSLASH, 1.2f),
            ),

        listOf(Key("Caps", KeyType.CAPS, 1.7f, code = KeyEvent.KEYCODE_CAPS_LOCK)) +
            "asdfghjkl".map { letter(it) } +
            listOf(
                ch(';', ':', KeyEvent.KEYCODE_SEMICOLON),
                ch('\'', '"', KeyEvent.KEYCODE_APOSTROPHE),
                code("Enter", KeyEvent.KEYCODE_ENTER, 1.9f),
            ),

        listOf(mod("Shift", Mod.SHIFT, KeyEvent.KEYCODE_SHIFT_LEFT, 1.6f)) +
            "zxcvbnm".map { letter(it) } +
            listOf(
                ch(',', '<', KeyEvent.KEYCODE_COMMA),
                ch('.', '>', KeyEvent.KEYCODE_PERIOD),
                ch('/', '?', KeyEvent.KEYCODE_SLASH),
                code("PgUp", KeyEvent.KEYCODE_PAGE_UP),
                code("↑", KeyEvent.KEYCODE_DPAD_UP, repeat = true),
                code("PgDn", KeyEvent.KEYCODE_PAGE_DOWN),
            ),

        listOf(
            mod("Ctrl", Mod.CTRL, KeyEvent.KEYCODE_CTRL_LEFT, 1.3f),
            mod("Win", Mod.WIN, KeyEvent.KEYCODE_META_LEFT),
            mod("Alt", Mod.ALT, KeyEvent.KEYCODE_ALT_LEFT),
            ch(' ', ' ', KeyEvent.KEYCODE_SPACE, 5.2f).copy(label = "Space", repeat = true),
            code("Home", KeyEvent.KEYCODE_MOVE_HOME),
            code("End", KeyEvent.KEYCODE_MOVE_END),
            code("←", KeyEvent.KEYCODE_DPAD_LEFT, repeat = true),
            code("↓", KeyEvent.KEYCODE_DPAD_DOWN, repeat = true),
            code("→", KeyEvent.KEYCODE_DPAD_RIGHT, repeat = true),
        ),
    )
}
