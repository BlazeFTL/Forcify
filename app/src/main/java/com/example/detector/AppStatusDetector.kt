package com.example.detector

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import com.example.engine.RootExecutor
import com.example.engine.RootProcessState
import com.example.model.AppState
import com.example.model.InstalledAppItem
import com.example.model.WakeUpDetails
import com.example.model.WakeUpTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class NonRootProcessActivity(
    val lastEventType: Int = 0,
    val lastEventTimestamp: Long = 0L,
    val hasActiveForegroundService: Boolean = false
)

class AppStatusDetector(private val context: Context) {
    private val packageManager: PackageManager = context.packageManager
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    suspend fun getRunningProcessesMap(): Map<String, ActivityManager.RunningAppProcessInfo> =
        withContext(Dispatchers.Default) {
            val map = mutableMapOf<String, ActivityManager.RunningAppProcessInfo>()
            try {
                val runningList = activityManager.runningAppProcesses ?: emptyList()
                for (proc in runningList) {
                    for (pkg in proc.pkgList ?: emptyArray()) {
                        val existing = map[pkg]
                        if (existing == null || proc.importance < existing.importance) {
                            map[pkg] = proc
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore security or permission exceptions
            }
            map
        }

    suspend fun getNonRootActivityMap(): Map<String, NonRootProcessActivity> = withContext(Dispatchers.Default) {
        val map = mutableMapOf<String, NonRootProcessActivity>()
        if (usageStatsManager == null || !hasUsageStatsPermission()) return@withContext map

        try {
            val endTime = System.currentTimeMillis()
            val startTime = endTime - (60 * 60 * 1000L) // Scan last 1 hour
            val events = usageStatsManager.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()

            val fgServiceStarts = mutableMapOf<String, Long>()
            val fgServiceStops = mutableMapOf<String, Long>()
            val lastEvents = mutableMapOf<String, Pair<Int, Long>>()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                val type = event.eventType
                val time = event.timeStamp

                lastEvents[pkg] = Pair(type, time)

                if (type == UsageEvents.Event.FOREGROUND_SERVICE_START) {
                    fgServiceStarts[pkg] = time
                } else if (type == UsageEvents.Event.FOREGROUND_SERVICE_STOP) {
                    fgServiceStops[pkg] = time
                }
            }

            for ((pkg, eventPair) in lastEvents) {
                val start = fgServiceStarts[pkg] ?: 0L
                val stop = fgServiceStops[pkg] ?: 0L
                val hasFgService = start > stop && (System.currentTimeMillis() - start < 12 * 60 * 60 * 1000L)

                map[pkg] = NonRootProcessActivity(
                    lastEventType = eventPair.first,
                    lastEventTimestamp = eventPair.second,
                    hasActiveForegroundService = hasFgService
                )
            }
        } catch (e: Exception) {
            // Ignore
        }
        map
    }

    suspend fun detectAppItem(
        pkgInfo: PackageInfo,
        runningMap: Map<String, ActivityManager.RunningAppProcessInfo>,
        rootProcessMap: Map<String, RootProcessState>,
        nonRootActivityMap: Map<String, NonRootProcessActivity>,
        isRootMode: Boolean,
        managedPackages: Set<String>,
        cutPackages: Set<String>,
        twentyFourHourWakeups: Map<String, Int>
    ): InstalledAppItem = withContext(Dispatchers.Default) {
        val pkg = pkgInfo.packageName
        val appInfo = pkgInfo.applicationInfo ?: return@withContext fallbackAppItem(pkg)
        val appName = try {
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            pkg
        }
        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

        val isIgnoredBattery = try {
            powerManager.isIgnoringBatteryOptimizations(pkg)
        } catch (e: Exception) {
            false
        }

        val hasWakeLockPerm = pkgInfo.requestedPermissions?.contains("android.permission.WAKE_LOCK") == true
        val triggers = detectWakeUpTriggers(pkgInfo, cutPackages.contains(pkg))
        val wakeupCount = twentyFourHourWakeups[pkg] ?: triggers.size * 3

        val wakeUpDetails = WakeUpDetails(
            wakeupCount24h = wakeupCount,
            hasBootReceiver = triggers.any { it.action.contains("BOOT_COMPLETED") },
            hasConnectivityReceiver = triggers.any { it.action.contains("CONNECTIVITY") },
            hasWakeLockPermission = hasWakeLockPerm,
            ignoresBatteryOptimizations = isIgnoredBattery,
            isCut = cutPackages.contains(pkg),
            triggers = triggers
        )

        val rootState = rootProcessMap[pkg]
        val nonRootActivity = nonRootActivityMap[pkg]
        val runningProc = runningMap[pkg]

        val state = if (isRootMode && rootProcessMap.isNotEmpty()) {
            determineRootState(rootState, isIgnoredBattery, hasWakeLockPerm, wakeUpDetails)
        } else {
            determineNonRootState(runningProc, nonRootActivity, isIgnoredBattery, hasWakeLockPerm, wakeUpDetails)
        }

        val icon = try {
            packageManager.getApplicationIcon(appInfo)
        } catch (e: Exception) {
            null
        }

        InstalledAppItem(
            packageName = pkg,
            appName = appName,
            icon = icon,
            state = state,
            processImportance = runningProc?.importance ?: 1000,
            pid = rootState?.pid ?: runningProc?.pid,
            wakeUpDetails = wakeUpDetails,
            isSystemApp = isSystem,
            isManaged = managedPackages.contains(pkg)
        )
    }

    private fun determineRootState(
        rootState: RootProcessState?,
        isIgnoredBattery: Boolean,
        hasWakeLockPerm: Boolean,
        wakeUpDetails: WakeUpDetails
    ): AppState {
        if (rootState == null || !rootState.isRunning) {
            return AppState.BACKGROUND_FREE
        }

        if (rootState.isTop) {
            return AppState.FOREGROUND
        }

        // Active foreground service (like IDM+ download, VPN, etc.)
        if (rootState.isForegroundService) {
            return AppState.EVADING_RESTRICTIONS
        }

        // Background running process
        if (isIgnoredBattery || (hasWakeLockPerm && !wakeUpDetails.isCut) || wakeUpDetails.wakeupCount24h > 15) {
            return AppState.EVADING_RESTRICTIONS
        }

        return AppState.WORKING_STATE
    }

    private fun determineNonRootState(
        proc: ActivityManager.RunningAppProcessInfo?,
        nonRootActivity: NonRootProcessActivity?,
        isIgnoredBattery: Boolean,
        hasWakeLockPerm: Boolean,
        wakeUpDetails: WakeUpDetails
    ): AppState {
        // Check Foreground Service
        if (nonRootActivity?.hasActiveForegroundService == true) {
            return AppState.EVADING_RESTRICTIONS
        }

        val now = System.currentTimeMillis()
        if (nonRootActivity != null) {
            val elapsed = now - nonRootActivity.lastEventTimestamp
            if (nonRootActivity.lastEventType == UsageEvents.Event.ACTIVITY_RESUMED && elapsed < 90_000) {
                return AppState.FOREGROUND
            }
        }

        if (proc != null) {
            val importance = proc.importance
            return when {
                importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> {
                    AppState.FOREGROUND
                }
                importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE ||
                importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE ||
                importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE ||
                importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> {
                    if (isIgnoredBattery || (hasWakeLockPerm && !wakeUpDetails.isCut)) {
                        AppState.EVADING_RESTRICTIONS
                    } else {
                        AppState.WORKING_STATE
                    }
                }
                importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> {
                    AppState.CACHED
                }
                else -> {
                    if (isIgnoredBattery || hasWakeLockPerm) AppState.EVADING_RESTRICTIONS else AppState.WORKING_STATE
                }
            }
        }

        // If had recent activity in last 3 minutes
        if (nonRootActivity != null && (now - nonRootActivity.lastEventTimestamp < 180_000)) {
            return if (isIgnoredBattery || hasWakeLockPerm) AppState.EVADING_RESTRICTIONS else AppState.WORKING_STATE
        }

        return AppState.BACKGROUND_FREE
    }

    private fun detectWakeUpTriggers(pkgInfo: PackageInfo, isCut: Boolean): List<WakeUpTrigger> {
        val list = mutableListOf<WakeUpTrigger>()
        val receivers = pkgInfo.receivers ?: emptyArray()

        for (receiver in receivers) {
            val name = receiver.name.substringAfterLast('.')
            if (name.contains("Boot", ignoreCase = true) || name.contains("Startup", ignoreCase = true)) {
                list.add(
                    WakeUpTrigger(
                        action = "android.intent.action.BOOT_COMPLETED",
                        name = "Boot Receiver",
                        description = "Triggers automatic app launch on device reboot",
                        isCut = isCut
                    )
                )
            }
            if (name.contains("Network", ignoreCase = true) || name.contains("Connectivity", ignoreCase = true)) {
                list.add(
                    WakeUpTrigger(
                        action = "android.net.conn.CONNECTIVITY_CHANGE",
                        name = "Connectivity Trigger",
                        description = "Wakes app on Wi-Fi/Mobile network state changes",
                        isCut = isCut
                    )
                )
            }
            if (name.contains("Alarm", ignoreCase = true) || name.contains("Scheduler", ignoreCase = true)) {
                list.add(
                    WakeUpTrigger(
                        action = "android.app.action.SCHEDULE_EXACT_ALARM",
                        name = "Alarm & Timer Wakeup",
                        description = "Fires background alarms to execute tasks",
                        isCut = isCut
                    )
                )
            }
        }

        if (pkgInfo.requestedPermissions?.contains("android.permission.RECEIVE_BOOT_COMPLETED") == true &&
            list.none { it.name.contains("Boot") }
        ) {
            list.add(
                WakeUpTrigger(
                    action = "android.intent.action.BOOT_COMPLETED",
                    name = "Boot Broadcast",
                    description = "Listens for system boot completed intent",
                    isCut = isCut
                )
            )
        }

        if (pkgInfo.requestedPermissions?.contains("android.permission.WAKE_LOCK") == true) {
            list.add(
                WakeUpTrigger(
                    action = "android.permission.WAKE_LOCK",
                    name = "Wake Lock",
                    description = "Prevents CPU from entering deep sleep mode",
                    isCut = isCut
                )
            )
        }

        return list
    }

    suspend fun calculateWakeups24h(): Map<String, Int> = withContext(Dispatchers.Default) {
        val map = mutableMapOf<String, Int>()
        if (usageStatsManager == null || !hasUsageStatsPermission()) {
            return@withContext map
        }

        try {
            val endTime = System.currentTimeMillis()
            val startTime = endTime - (24 * 60 * 60 * 1000L)
            val events = usageStatsManager.queryEvents(startTime, endTime)
            val event = UsageEvents.Event()

            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val pkg = event.packageName ?: continue
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.USER_INTERACTION ||
                    event.eventType == UsageEvents.Event.FOREGROUND_SERVICE_START
                ) {
                    map[pkg] = (map[pkg] ?: 0) + 1
                }
            }
        } catch (e: Exception) {
            // Ignore usage events exceptions
        }
        map
    }

    private fun fallbackAppItem(pkg: String) = InstalledAppItem(
        packageName = pkg,
        appName = pkg,
        state = AppState.BACKGROUND_FREE
    )
}
