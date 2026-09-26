package com.example.data

import android.content.Context
import android.content.SharedPreferences

enum class OperatingMode {
    UNCONFIGURED,
    ROOT,
    NON_ROOT
}

class PureStopPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("purestop_prefs", Context.MODE_PRIVATE)

    var mode: OperatingMode
        get() {
            val name = prefs.getString("operating_mode", OperatingMode.UNCONFIGURED.name)
            return try {
                OperatingMode.valueOf(name ?: OperatingMode.UNCONFIGURED.name)
            } catch (e: Exception) {
                OperatingMode.UNCONFIGURED
            }
        }
        set(value) {
            prefs.edit().putString("operating_mode", value.name).apply()
        }

    var isSetupCompleted: Boolean
        get() = prefs.getBoolean("is_setup_completed", false)
        set(value) = prefs.edit().putBoolean("is_setup_completed", value).apply()

    var autoCutWakeups: Boolean
        get() = prefs.getBoolean("auto_cut_wakeups", true)
        set(value) = prefs.edit().putBoolean("auto_cut_wakeups", value).apply()

    var aggressiveEvadingDetection: Boolean
        get() = prefs.getBoolean("aggressive_evading_detection", true)
        set(value) = prefs.edit().putBoolean("aggressive_evading_detection", value).apply()

    var showSystemApps: Boolean
        get() = prefs.getBoolean("show_system_apps", false)
        set(value) = prefs.edit().putBoolean("show_system_apps", value).apply()

    fun resetSetup() {
        prefs.edit()
            .putBoolean("is_setup_completed", false)
            .putString("operating_mode", OperatingMode.UNCONFIGURED.name)
            .apply()
    }
}
