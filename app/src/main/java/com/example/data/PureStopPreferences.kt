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

    var autoHibernateAfterScreenOff: Boolean
        get() = prefs.getBoolean("auto_hibernate_screen_off", true)
        set(value) = prefs.edit().putBoolean("auto_hibernate_screen_off", value).apply()

    var aggressiveEvadingDetection: Boolean
        get() = prefs.getBoolean("aggressive_evading_detection", true)
        set(value) = prefs.edit().putBoolean("aggressive_evading_detection", value).apply()

    var showSystemApps: Boolean
        get() = prefs.getBoolean("show_system_apps", false)
        set(value) = prefs.edit().putBoolean("show_system_apps", value).apply()

    var hideSystemAppsInAddList: Boolean
        get() = prefs.getBoolean("hide_system_apps_add_list", true)
        set(value) = prefs.edit().putBoolean("hide_system_apps_add_list", value).apply()

    var savedManagedPackages: Set<String>
        get() = prefs.getStringSet("saved_managed_packages", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("saved_managed_packages", value).apply()

    var savedPendingPackages: Set<String>
        get() = prefs.getStringSet("saved_pending_packages", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("saved_pending_packages", value).apply()

    fun getSavedAppName(packageName: String): String? {
        return prefs.getString("app_name_$packageName", null)
    }

    fun setSavedAppName(packageName: String, name: String) {
        prefs.edit().putString("app_name_$packageName", name).apply()
    }

    fun isBootCutForPackage(packageName: String): Boolean {
        return prefs.getBoolean("boot_cut_$packageName", false)
    }

    fun setBootCutForPackage(packageName: String, cut: Boolean) {
        prefs.edit().putBoolean("boot_cut_$packageName", cut).apply()
    }

    fun getCutPathsForPackage(packageName: String): Set<String> {
        return prefs.getStringSet("cut_paths_$packageName", emptySet()) ?: emptySet()
    }

    fun setCutPathsForPackage(packageName: String, pathIds: Set<String>) {
        prefs.edit().putStringSet("cut_paths_$packageName", pathIds).apply()
    }

    fun isPathCut(packageName: String, pathId: String): Boolean {
        return getCutPathsForPackage(packageName).contains(pathId)
    }

    fun togglePathCut(packageName: String, pathId: String, cut: Boolean) {
        val current = getCutPathsForPackage(packageName).toMutableSet()
        if (cut) current.add(pathId) else current.remove(pathId)
        setCutPathsForPackage(packageName, current)
    }

    var addAppSortOption: com.example.model.AppSortOption
        get() {
            val name = prefs.getString("add_app_sort_option", com.example.model.AppSortOption.NAME_ASC.name)
            return try {
                com.example.model.AppSortOption.valueOf(name ?: com.example.model.AppSortOption.NAME_ASC.name)
            } catch (e: Exception) {
                com.example.model.AppSortOption.NAME_ASC
            }
        }
        set(value) {
            prefs.edit().putString("add_app_sort_option", value.name).apply()
        }

    fun getIgnoredWorkingStatePackages(): Set<String> {
        return prefs.getStringSet("ignored_working_state_pkgs", emptySet()) ?: emptySet()
    }

    fun isWorkingStateIgnored(packageName: String): Boolean {
        return getIgnoredWorkingStatePackages().contains(packageName)
    }

    fun toggleIgnoreWorkingState(packageName: String): Boolean {
        val current = getIgnoredWorkingStatePackages().toMutableSet()
        val newState = if (current.contains(packageName)) {
            current.remove(packageName)
            false
        } else {
            current.add(packageName)
            true
        }
        prefs.edit().putStringSet("ignored_working_state_pkgs", current).apply()
        return newState
    }

    fun getRestrictedForegroundPackages(): Set<String> {
        return prefs.getStringSet("restricted_foreground_pkgs", emptySet()) ?: emptySet()
    }

    fun isRestrictRunningAsForeground(packageName: String): Boolean {
        return getRestrictedForegroundPackages().contains(packageName)
    }

    fun toggleRestrictRunningAsForeground(packageName: String): Boolean {
        val current = getRestrictedForegroundPackages().toMutableSet()
        val newState = if (current.contains(packageName)) {
            current.remove(packageName)
            false
        } else {
            current.add(packageName)
            true
        }
        prefs.edit().putStringSet("restricted_foreground_pkgs", current).apply()
        return newState
    }

    fun resetSetup() {
        prefs.edit()
            .putBoolean("is_setup_completed", false)
            .putString("operating_mode", OperatingMode.UNCONFIGURED.name)
            .apply()
    }
}
