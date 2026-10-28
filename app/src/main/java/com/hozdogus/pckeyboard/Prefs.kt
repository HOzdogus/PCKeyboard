package com.hozdogus.pckeyboard

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    const val HIDE_PHONE_KEYBOARD = "hide_phone_keyboard"
    const val KEY_HEIGHT = "key_height"   // dp, 28..60
    const val OPACITY = "opacity"         // %, 40..100
    const val KEYBOARD_Y = "keyboard_y"
    const val BUBBLE_X = "bubble_x"
    const val BUBBLE_Y = "bubble_y"
    const val EXPANDED = "expanded"

    fun get(context: Context): SharedPreferences =
        context.getSharedPreferences("pckeyboard", Context.MODE_PRIVATE)
}
