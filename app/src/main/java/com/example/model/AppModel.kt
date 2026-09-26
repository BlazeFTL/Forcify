package com.example.model

import android.graphics.drawable.Drawable

enum class AppState(val label: String, val description: String) {
    FOREGROUND("Foreground", "Actively in use on screen or visible"),
    WORKING_STATE("Working State", "Active background services or worker execution"),
    EVADING_RESTRICTIONS("Evading Restrictions", "Running in background ignoring battery / power limits"),
    CACHED("Cached in RAM", "Dormant process waiting in memory"),
    BACKGROUND_FREE("Background Free", "Hibernated cleanly; zero active processes")
}

data class WakeUpTrigger(
    val action: String,
    val name: String,
    val description: String,
    val isCut: Boolean = false
)

data class WakeUpDetails(
    val wakeupCount24h: Int = 0,
    val hasBootReceiver: Boolean = false,
    val hasConnectivityReceiver: Boolean = false,
    val hasWakeLockPermission: Boolean = false,
    val ignoresBatteryOptimizations: Boolean = false,
    val isCut: Boolean = false,
    val triggers: List<WakeUpTrigger> = emptyList()
)

data class InstalledAppItem(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null,
    val state: AppState = AppState.BACKGROUND_FREE,
    val processImportance: Int = 1000,
    val pid: Int? = null,
    val wakeUpDetails: WakeUpDetails = WakeUpDetails(),
    val isSystemApp: Boolean = false,
    val isManaged: Boolean = false,
    val lastFrozenTimestamp: Long = 0L,
    val freezeCount: Int = 0
)

data class BatchFreezeProgress(
    val isRunning: Boolean = false,
    val currentPackage: String = "",
    val currentAppName: String = "",
    val completedCount: Int = 0,
    val totalCount: Int = 0,
    val failedCount: Int = 0,
    val summary: String = ""
)
