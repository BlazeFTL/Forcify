package com.example.model

import android.graphics.drawable.Drawable

enum class AppState(val label: String, val description: String) {
    FOREGROUND("Foreground", "Actively in use on screen or visible"),
    WORKING_STATE("Working State", "Active background services or worker execution"),
    EVADING_RESTRICTIONS("Evading Restrictions", "Running as foreground (evading restrictions)"),
    CACHED("Cached in RAM", "Dormant process waiting in memory"),
    BACKGROUND_FREE("Background Free", "Hibernated cleanly; zero active processes")
}

enum class WakeUpPathType(val category: String, val label: String) {
    RECEIVER_BOOT("Receiver", "Boot Receiver"),
    RECEIVER_CONNECTIVITY("Receiver", "Network State Trigger"),
    RECEIVER_POWER("Receiver", "Power & Charger Trigger"),
    RECEIVER_USER_PRESENT("Receiver", "Screen Unlock Trigger"),
    RECEIVER_PACKAGE("Receiver", "App / Package Update"),
    RECEIVER_CUSTOM("Receiver", "Broadcast Receiver"),
    SERVICE_BACKGROUND("Service", "Background Service"),
    SERVICE_FOREGROUND("Service", "Foreground Service"),
    SERVICE_JOB("Service", "JobScheduler Service"),
    OP_WAKE_LOCK("Permission", "CPU Wake Lock"),
    OP_SCHEDULED_ALARM("Alarm", "Alarm & Timer Wakeup"),
    OP_RUN_IN_BACKGROUND("Permission", "Background Execution"),
    BATTERY_OPTIMIZATION("Exemption", "Battery Optimization Whitelist")
}

data class WakeUpPath(
    val id: String,
    val packageName: String,
    val type: WakeUpPathType,
    val title: String,
    val componentName: String,
    val reason: String,
    val wakeupCount: Int = 0,
    val isCut: Boolean = false
) {
    val name: String get() = title
    val description: String get() = reason
}

// Backward-compatibility alias
typealias WakeUpTrigger = WakeUpPath

data class WakeUpDetails(
    val wakeupCount24h: Int = 0,
    val paths: List<WakeUpPath> = emptyList(),
    val isCut: Boolean = false
) {
    val cutPathsCount: Int get() = paths.count { it.isCut }
    val totalPathsCount: Int get() = paths.size
    val hasWakeLockPermission: Boolean get() = paths.any { it.type == WakeUpPathType.OP_WAKE_LOCK }
    val ignoresBatteryOptimizations: Boolean get() = paths.any { it.type == WakeUpPathType.BATTERY_OPTIMIZATION }
    val triggers: List<WakeUpPath> get() = paths
}

data class InstalledAppItem(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null,
    val state: AppState = AppState.BACKGROUND_FREE,
    val stateDetail: String = "",
    val secondaryDetail: String = "",
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
