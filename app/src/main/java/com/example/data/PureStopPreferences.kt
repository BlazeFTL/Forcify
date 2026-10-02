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

    var hideSystemAppsInRam: Boolean
        get() = prefs.getBoolean("hide_system_apps_in_ram", true)
        set(value) = prefs.edit().putBoolean("hide_system_apps_in_ram", value).apply()

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

    fun getSavedAppState(packageName: String): com.example.model.AppState? {
        val s = prefs.getString("saved_state_$packageName", null) ?: return null
        return try { com.example.model.AppState.valueOf(s) } catch (e: Exception) { null }
    }

    fun setSavedAppState(packageName: String, state: com.example.model.AppState, detail: String, secondary: String) {
        prefs.edit()
            .putString("saved_state_$packageName", state.name)
            .putString("saved_detail_$packageName", detail)
            .putString("saved_secondary_$packageName", secondary)
            .apply()
    }

    fun batchSaveAppStates(states: Map<String, Triple<com.example.model.AppState, String, String>>) {
        if (states.isEmpty()) return
        val editor = prefs.edit()
        for ((pkg, triple) in states) {
            editor.putString("saved_state_$pkg", triple.first.name)
            editor.putString("saved_detail_$pkg", triple.second)
            editor.putString("saved_secondary_$pkg", triple.third)
        }
        editor.apply()
    }

    fun getSavedStateDetail(packageName: String): String? = prefs.getString("saved_detail_$packageName", null)
    fun getSavedSecondaryDetail(packageName: String): String? = prefs.getString("saved_secondary_$packageName", null)

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

    fun setRestrictedForegroundPackages(pkgs: Set<String>) {
        prefs.edit().putStringSet("restricted_foreground_pkgs", pkgs).apply()
    }

    fun isRestrictRunningAsForeground(packageName: String): Boolean {
        return getRestrictedForegroundPackages().contains(packageName)
    }

    fun getMonitoredWakeUpPackages(): Set<String> {
        return prefs.getStringSet("monitored_wakeup_pkgs", emptySet()) ?: emptySet()
    }

    fun isWakeUpMonitoringEnabled(packageName: String): Boolean {
        return getMonitoredWakeUpPackages().contains(packageName)
    }

    fun setWakeUpMonitoring(packageName: String, enabled: Boolean) {
        val current = getMonitoredWakeUpPackages().toMutableSet()
        if (enabled) current.add(packageName) else current.remove(packageName)
        prefs.edit().putStringSet("monitored_wakeup_pkgs", current).apply()
    }

    fun toggleWakeUpMonitoring(packageName: String): Boolean {
        val current = getMonitoredWakeUpPackages().toMutableSet()
        val newState = if (current.contains(packageName)) {
            current.remove(packageName)
            false
        } else {
            current.add(packageName)
            true
        }
        prefs.edit().putStringSet("monitored_wakeup_pkgs", current).apply()
        return newState
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

    fun getDetectedWakeUpEvents(): List<com.example.model.DetectedWakeUpEvent> {
        val raw = prefs.getString("detected_wakeup_events", null) ?: return emptyList()
        return try {
            val array = org.json.JSONArray(raw)
            val list = mutableListOf<com.example.model.DetectedWakeUpEvent>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val typeStr = obj.optString("pathType", com.example.model.WakeUpPathType.SERVICE_BACKGROUND.name)
                val type = try {
                    com.example.model.WakeUpPathType.valueOf(typeStr)
                } catch (e: Exception) {
                    com.example.model.WakeUpPathType.SERVICE_BACKGROUND
                }
                list.add(
                    com.example.model.DetectedWakeUpEvent(
                        id = obj.getString("id"),
                        packageName = obj.getString("packageName"),
                        appName = obj.optString("appName", obj.getString("packageName")),
                        componentName = obj.getString("componentName"),
                        pathType = type,
                        pathTitle = obj.optString("pathTitle", "Wake-Up Component"),
                        triggerContext = obj.optString("triggerContext", "Background Wake-up"),
                        rawReason = obj.optString("rawReason", ""),
                        timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                        isCut = obj.optBoolean("isCut", false)
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveDetectedWakeUpEvent(event: com.example.model.DetectedWakeUpEvent) {
        val current = getDetectedWakeUpEvents().toMutableList()
        val existingIndex = current.indexOfFirst { it.packageName == event.packageName && it.componentName == event.componentName }
        if (existingIndex >= 0) {
            current[existingIndex] = event
        } else {
            current.add(0, event)
        }
        val trimmed = if (current.size > 50) current.take(50) else current
        persistDetectedEvents(trimmed)
    }

    fun markDetectedWakeUpEventCut(id: String) {
        val current = getDetectedWakeUpEvents().map {
            if (it.id == id || it.componentName == id) it.copy(isCut = true) else it
        }
        persistDetectedEvents(current)
    }

    fun removeDetectedWakeUpEvent(id: String) {
        val current = getDetectedWakeUpEvents().filter { it.id != id && it.componentName != id }
        persistDetectedEvents(current)
    }

    fun clearAllDetectedWakeUpEvents() {
        prefs.edit().remove("detected_wakeup_events").apply()
    }

    private fun persistDetectedEvents(list: List<com.example.model.DetectedWakeUpEvent>) {
        val array = org.json.JSONArray()
        for (item in list) {
            val obj = org.json.JSONObject()
            obj.put("id", item.id)
            obj.put("packageName", item.packageName)
            obj.put("appName", item.appName)
            obj.put("componentName", item.componentName)
            obj.put("pathType", item.pathType.name)
            obj.put("pathTitle", item.pathTitle)
            obj.put("triggerContext", item.triggerContext)
            obj.put("rawReason", item.rawReason)
            obj.put("timestamp", item.timestamp)
            obj.put("isCut", item.isCut)
            array.put(obj)
        }
        prefs.edit().putString("detected_wakeup_events", array.toString()).apply()
    }

    fun resetSetup() {
        prefs.edit()
            .putBoolean("is_setup_completed", false)
            .putString("operating_mode", OperatingMode.UNCONFIGURED.name)
            .apply()
    }
}
