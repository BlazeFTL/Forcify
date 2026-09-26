package com.example.detector

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import com.example.model.AppState
import com.example.model.InstalledAppItem
import com.example.model.WakeUpDetails
import com.example.model.WakeUpTrigger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                        // Store lowest importance (highest priority/activity)
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

    suspend fun detectAppItem(
        pkgInfo: PackageInfo,
        runningMap: Map<String, ActivityManager.RunningAppProcessInfo>,
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

        val runningProc = runningMap[pkg]
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

        val state = determineAppState(runningProc, isIgnoredBattery, hasWakeLockPerm, wakeUpDetails)

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
            pid = runningProc?.pid,
            wakeUpDetails = wakeUpDetails,
            isSystemApp = isSystem,
            isManaged = managedPackages.contains(pkg)
        )
    }

    private fun determineAppState(
        proc: ActivityManager.RunningAppProcessInfo?,
        isIgnoredBattery: Boolean,
        hasWakeLockPerm: Boolean,
        wakeUpDetails: WakeUpDetails
    ): AppState {
        if (proc == null) {
            return AppState.BACKGROUND_FREE
        }

        val importance = proc.importance
        return when {
            importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> {
                AppState.FOREGROUND
            }
            importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE ||
            importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE ||
            importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE ||
            importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> {
                // If it's running background service while evading battery restrictions or holding wakelocks without user in foreground
                if (isIgnoredBattery || (hasWakeLockPerm && !wakeUpDetails.isCut) || wakeUpDetails.wakeupCount24h > 15) {
                    AppState.EVADING_RESTRICTIONS
                } else {
                    AppState.WORKING_STATE
                }
            }
            importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> {
                AppState.CACHED
            }
            else -> {
                // Standard background process
                if (isIgnoredBattery || hasWakeLockPerm) {
                    AppState.EVADING_RESTRICTIONS
                } else {
                    AppState.WORKING_STATE
                }
            }
        }
    }

    private fun detectWakeUpTriggers(pkgInfo: PackageInfo, isCut: Boolean): List<WakeUpTrigger> {
        val list = mutableListOf<WakeUpTrigger>()
        val receivers = pkgInfo.receivers ?: emptyArray()

        for (receiver in receivers) {
            val name = receiver.name.substringAfterLast('.')
            // Check known triggers
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

        // Add default triggers if app requests wake permissions
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
