package com.example.model

import android.graphics.drawable.Drawable

enum class AppState(val label: String, val description: String) {
    FOREGROUND("Foreground", "Actively in use on screen or visible"),
    WORKING_STATE("Working State", "In recent tasks or carrying out active task"),
    BACKGROUND_RUNNING("Running", "Process running in background"),
    EVADING_RESTRICTIONS("Evading Restrictions", "Running as foreground (evading restrictions)"),
    CACHED("Cached in RAM", "Dormant process waiting in memory"),
    BACKGROUND_FREE("Background Free", "Hibernated cleanly; zero active processes")
}

enum class WakeUpRiskLevel(val label: String, val badge: String) {
    SAFE("Safe to Cut", "Zero Breakage"),
    MODERATE("Caution", "May Stop Sync/Docs"),
    RISKY("High Risk", "May Break Push/Calls")
}

enum class WakeUpPathType(val category: String, val defaultRisk: WakeUpRiskLevel) {
    PROVIDER_DOCUMENTS("Provider", WakeUpRiskLevel.MODERATE),
    PROVIDER_CONTENT("Provider", WakeUpRiskLevel.MODERATE),
    SERVICE_SYNC_ADAPTER("SyncAdapter", WakeUpRiskLevel.MODERATE),
    SERVICE_BACKGROUND("Service", WakeUpRiskLevel.MODERATE),
    SERVICE_FOREGROUND("Service", WakeUpRiskLevel.RISKY),
    SERVICE_JOB("JobScheduler", WakeUpRiskLevel.SAFE),
    RECEIVER_BOOT("Boot Receiver", WakeUpRiskLevel.SAFE),
    RECEIVER_CONNECTIVITY("Network Trigger", WakeUpRiskLevel.SAFE),
    RECEIVER_POWER("Power Trigger", WakeUpRiskLevel.SAFE),
    RECEIVER_USER_PRESENT("Screen Unlock", WakeUpRiskLevel.SAFE),
    RECEIVER_TRACKER("Tracker / Telemetry", WakeUpRiskLevel.SAFE),
    RECEIVER_PUSH("Push Notification", WakeUpRiskLevel.RISKY),
    RECEIVER_PACKAGE("Package Update", WakeUpRiskLevel.SAFE),
    RECEIVER_CUSTOM("Broadcast Receiver", WakeUpRiskLevel.SAFE),
    OP_WAKE_LOCK("Permission", WakeUpRiskLevel.SAFE),
    OP_SCHEDULED_ALARM("Alarm", WakeUpRiskLevel.SAFE),
    OP_RUN_IN_BACKGROUND("Permission", WakeUpRiskLevel.SAFE),
    BATTERY_OPTIMIZATION("Exemption", WakeUpRiskLevel.SAFE)
}

data class WakeUpPath(
    val id: String,
    val packageName: String,
    val type: WakeUpPathType,
    val title: String,
    val componentName: String,
    val reason: String,
    val riskLevel: WakeUpRiskLevel = WakeUpRiskLevel.SAFE,
    val riskExplanation: String = "",
    val isPrimaryCulprit: Boolean = false,
    val isActiveVector: Boolean = false,
    val wakeupCount: Int = 0,
    val isCut: Boolean = false
) {
    val name: String get() = title
    val description: String get() = reason
}

// Backward-compatibility alias
typealias WakeUpTrigger = WakeUpPath

data class DetectedWakeUpEvent(
    val id: String,
    val packageName: String,
    val appName: String,
    val componentName: String,
    val pathType: WakeUpPathType,
    val pathTitle: String,
    val triggerContext: String,
    val rawReason: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val isCut: Boolean = false
) {
    val formattedTime: String
        get() {
            val diff = System.currentTimeMillis() - timestamp
            return when {
                diff < 60_000L -> "Just now"
                diff < 3600_000L -> "${diff / 60_000L}m ago"
                diff < 86400_000L -> "${diff / 3600_000L}h ago"
                else -> "${diff / 86400_000L}d ago"
            }
        }
}

data class WakeUpDetails(
    val wakeupCount24h: Int = 0,
    val paths: List<WakeUpPath> = emptyList(),
    val isCut: Boolean = false
) {
    val cutPathsCount: Int get() = paths.count { it.isCut }
    val totalPathsCount: Int get() = paths.size
    val safePathsCount: Int get() = paths.count { it.riskLevel == WakeUpRiskLevel.SAFE }
    val moderatePathsCount: Int get() = paths.count { it.riskLevel == WakeUpRiskLevel.MODERATE }
    val riskyPathsCount: Int get() = paths.count { it.riskLevel == WakeUpRiskLevel.RISKY }
    val primaryCulpritsCount: Int get() = paths.count { it.isPrimaryCulprit || it.isActiveVector }
    val hasWakeLockPermission: Boolean get() = paths.any { it.type == WakeUpPathType.OP_WAKE_LOCK }
    val ignoresBatteryOptimizations: Boolean get() = paths.any { it.type == WakeUpPathType.BATTERY_OPTIMIZATION }
    val triggers: List<WakeUpPath> get() = paths
}

enum class AppSortOption(val label: String) {
    NAME_ASC("Name (A → Z)"),
    NAME_DESC("Name (Z → A)"),
    INSTALL_TIME_DESC("Install Time (Newest)"),
    INSTALL_TIME_ASC("Install Time (Oldest)"),
    SIZE_DESC("App Size (Largest)"),
    SIZE_ASC("App Size (Smallest)")
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
    val freezeCount: Int = 0,
    val firstInstallTime: Long = 0L,
    val appSize: Long = 0L,
    val ignoreWorkingState: Boolean = false,
    val isRestrictedForeground: Boolean = false,
    val isWakeUpMonitoringEnabled: Boolean = false,
    val isStoppedState: Boolean = false,
    val isUnsafeToForceStop: Boolean = false,
    val unsafeReason: String = ""
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

data class AppRamUsageItem(
    val packageName: String,
    val appName: String,
    val icon: Drawable? = null,
    val pssKb: Long = 0L,
    val pid: Int = 0,
    val isSystemApp: Boolean = false
) {
    val ramMb: Long get() = pssKb / 1024L
    val ramFormatted: String get() {
        val mb = pssKb / 1024.0
        return if (mb >= 1024.0) {
            String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
        } else {
            String.format(java.util.Locale.US, "%.0f MB", mb)
        }
    }
}

data class SystemRamOverview(
    val totalBytes: Long = 0L,
    val availableBytes: Long = 0L,
    val usedBytes: Long = 0L
) {
    val usedPercentage: Int
        get() = if (totalBytes > 0) ((usedBytes.toDouble() / totalBytes.toDouble()) * 100).coerceIn(0.0, 100.0).toInt() else 0

    val totalFormatted: String
        get() = String.format(java.util.Locale.US, "%.1f GB", totalBytes / (1024.0 * 1024.0 * 1024.0))

    val usedFormatted: String
        get() = String.format(java.util.Locale.US, "%.1f GB", usedBytes / (1024.0 * 1024.0 * 1024.0))

    val freeFormatted: String
        get() = String.format(java.util.Locale.US, "%.1f GB", availableBytes / (1024.0 * 1024.0 * 1024.0))
}
