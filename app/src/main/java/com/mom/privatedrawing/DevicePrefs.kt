package com.mom.privatedrawing

import android.content.Context

enum class DeviceType { PHONE, TABLET }

object DevicePrefs {
    private const val PREFS = "drawing_prefs"
    private const val KEY_DEVICE_TYPE = "device_type"

    fun getSavedDeviceType(context: Context): DeviceType? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return when (prefs.getString(KEY_DEVICE_TYPE, null)) {
            "PHONE" -> DeviceType.PHONE
            "TABLET" -> DeviceType.TABLET
            else -> null
        }
    }

    fun saveDeviceType(context: Context, type: DeviceType) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_DEVICE_TYPE, type.name).apply()
    }
}
