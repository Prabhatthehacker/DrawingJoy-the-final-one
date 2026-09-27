package com.mom.privatedrawing

import android.content.Context

object ColorStore {
    private const val PREFS = "drawing_prefs"
    private const val KEY_CUSTOM_COLORS = "custom_colors"

    fun loadCustomColors(context: Context): MutableList<Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CUSTOM_COLORS, "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return raw.split(",").mapNotNull { it.toIntOrNull() }.toMutableList()
    }

    fun saveCustomColors(context: Context, colors: List<Int>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_CUSTOM_COLORS, colors.joinToString(",")).apply()
    }
}
