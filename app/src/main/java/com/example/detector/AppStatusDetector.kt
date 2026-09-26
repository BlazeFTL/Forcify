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
import com.example.model.WakeUpPath
import com.example.model.WakeUpPathType
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

    fun isSystemApp(pkgInfo: PackageInfo): Boolean {
        val appInfo = pkgInfo.applicationInfo ?: return false
        val pkg = pkgInfo.packageName

        // Check system flags
        val isSystemFlag = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val isUpdatedSystemFlag = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

        // Check system directories
        val sourceDir = appInfo.sourceDir ?: ""
        val isSystemDir = sourceDir.startsWith("/system") ||
            sourceDir.startsWith("/product") ||
            sourceDir.startsWith("/apex") ||
            sourceDir.startsWith("/vendor") ||
            sourceDir.startsWith("/system_ext")

        // Check package prefixes
        val isSystemPrefix = pkg.startsWith("android") ||
            pkg.startsWith("com.android.") ||
            pkg.startsWith("com.google.android.webview") ||
            pkg.startsWith("com.google.android.ext.") ||
            pkg.startsWith("com.google.android.overlay.") ||
            pkg.startsWith("com.google.android.packageinstaller") ||
            pkg == "com.android.settings"

        return isSystemFlag || isUpdatedSystemFlag || isSystemDir || isSystemPrefix
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
                // Ignore
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
        cutPathsMap: Map<String, Set<String>>,
        twentyFourHourWakeups: Map<String, Int>
    ): InstalledAppItem = withContext(Dispatchers.Default) {
        val pkg = pkgInfo.packageName
        val appInfo = pkgInfo.applicationInfo ?: return@withContext fallbackAppItem(pkg)
        val appName = try {
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            pkg
        }
        val isSystem = isSystemApp(pkgInfo)

        val isIgnoredBattery = try {
            powerManager.isIgnoringBatteryOptimizations(pkg)
        } catch (e: Exception) {
            false
        }

        val hasWakeLockPerm = pkgInfo.requestedPermissions?.contains("android.permission.WAKE_LOCK") == true
        val wakeupCount = twentyFourHourWakeups[pkg] ?: 0
        val isAppCut = cutPackages.contains(pkg)
        val cutPathIds = cutPathsMap[pkg] ?: emptySet()

        val paths = detectWakeUpPaths(pkgInfo, cutPathIds, isAppCut, wakeupCount, isIgnoredBattery)

        val wakeUpDetails = WakeUpDetails(
            wakeupCount24h = wakeupCount,
            paths = paths,
            isCut = isAppCut || (paths.isNotEmpty() && paths.all { it.isCut })
        )

        val rootState = rootProcessMap[pkg]
        val nonRootActivity = nonRootActivityMap[pkg]
        val runningProc = runningMap[pkg]

        val (state, stateDetail, secondaryDetail) = if (isRootMode && rootProcessMap.isNotEmpty()) {
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
            stateDetail = stateDetail,
            secondaryDetail = secondaryDetail,
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
    ): Triple<AppState, String, String> {
        if (rootState == null || !rootState.isRunning) {
            return Triple(AppState.BACKGROUND_FREE, "Hibernated", "")
        }

        if (rootState.isTop) {
            return Triple(AppState.FOREGROUND, "Foreground", "Ignored running state")
        }

        // Active foreground service (like IDM+ download, VPN, etc.)
        if (rootState.isForegroundService) {
            return Triple(AppState.EVADING_RESTRICTIONS, "Running as foreground (evading restrictions)", "")
        }

        // Background running process
        if (isIgnoredBattery || (hasWakeLockPerm && !wakeUpDetails.isCut) || wakeUpDetails.wakeupCount24h > 15) {
            return Triple(AppState.EVADING_RESTRICTIONS, "Running as foreground (evading restrictions)", "")
        }

        return Triple(AppState.WORKING_STATE, "Background service active", "")
    }

    private fun determineNonRootState(
        proc: ActivityManager.RunningAppProcessInfo?,
        nonRootActivity: NonRootProcessActivity?,
        isIgnoredBattery: Boolean,
        hasWakeLockPerm: Boolean,
        wakeUpDetails: WakeUpDetails
    ): Triple<AppState, String, String> {
        // Check Foreground Service
        if (nonRootActivity?.hasActiveForegroundService == true) {
            return Triple(AppState.EVADING_RESTRICTIONS, "Running as foreground (evading restrictions)", "")
        }

        val now = System.currentTimeMillis()
        if (nonRootActivity != null) {
            val elapsed = now - nonRootActivity.lastEventTimestamp
            if (nonRootActivity.lastEventType == UsageEvents.Event.ACTIVITY_RESUMED && elapsed < 90_000) {
                return Triple(AppState.FOREGROUND, "Foreground", "Ignored running state")
            }
        }

        if (proc != null) {
            val importance = proc.importance
            return when {
                importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> {
                    Triple(AppState.FOREGROUND, "Foreground", "Ignored running state")
                }
                importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE ||
                importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE ||
                importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE ||
                importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE -> {
                    if (isIgnoredBattery || (hasWakeLockPerm && !wakeUpDetails.isCut)) {
                        Triple(AppState.EVADING_RESTRICTIONS, "Running as foreground (evading restrictions)", "")
                    } else {
                        Triple(AppState.WORKING_STATE, "Background service active", "")
                    }
                }
                importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> {
                    Triple(AppState.CACHED, "Cached in RAM", "")
                }
                else -> {
                    if (isIgnoredBattery || hasWakeLockPerm)
                        Triple(AppState.EVADING_RESTRICTIONS, "Running as foreground (evading restrictions)", "")
                    else
                        Triple(AppState.WORKING_STATE, "Background service active", "")
                }
            }
        }

        // If had recent activity in last 3 minutes
        if (nonRootActivity != null && (now - nonRootActivity.lastEventTimestamp < 180_000)) {
            return if (isIgnoredBattery || hasWakeLockPerm)
                Triple(AppState.EVADING_RESTRICTIONS, "Running as foreground (evading restrictions)", "")
            else
                Triple(AppState.WORKING_STATE, "Background service active", "")
        }

        return Triple(AppState.BACKGROUND_FREE, "Hibernated", "")
    }

    private fun detectWakeUpPaths(
        pkgInfo: PackageInfo,
        cutPathIds: Set<String>,
        isAppCut: Boolean,
        totalWakeupCount: Int,
        isIgnoredBattery: Boolean
    ): List<WakeUpPath> {
        val list = mutableListOf<WakeUpPath>()
        val pkg = pkgInfo.packageName

        // 1. Receivers
        val receivers = pkgInfo.receivers ?: emptyArray()
        for (receiver in receivers) {
            val simpleName = receiver.name.substringAfterLast('.')
            val fullName = if (receiver.name.startsWith(".")) "$pkg${receiver.name}" else receiver.name
            val id = "$pkg:receiver:$fullName"
            val isCut = isAppCut || cutPathIds.contains(id)

            when {
                simpleName.contains("Boot", ignoreCase = true) || simpleName.contains("Startup", ignoreCase = true) -> {
                    list.add(
                        WakeUpPath(
                            id = id,
                            packageName = pkg,
                            type = WakeUpPathType.RECEIVER_BOOT,
                            title = "Boot Receiver ($simpleName)",
                            componentName = fullName,
                            reason = "Automatically launches app in background when phone is turned on or restarted",
                            wakeupCount = if (totalWakeupCount > 0) 1 else 0,
                            isCut = isCut
                        )
                    )
                }
                simpleName.contains("Network", ignoreCase = true) || simpleName.contains("Connectivity", ignoreCase = true) -> {
                    list.add(
                        WakeUpPath(
                            id = id,
                            packageName = pkg,
                            type = WakeUpPathType.RECEIVER_CONNECTIVITY,
                            title = "Connectivity Trigger ($simpleName)",
                            componentName = fullName,
                            reason = "Wakes app whenever Wi-Fi or mobile data state toggles or connects",
                            wakeupCount = if (totalWakeupCount > 2) totalWakeupCount / 3 else 0,
                            isCut = isCut
                        )
                    )
                }
                simpleName.contains("Alarm", ignoreCase = true) || simpleName.contains("Timer", ignoreCase = true) -> {
                    list.add(
                        WakeUpPath(
                            id = id,
                            packageName = pkg,
                            type = WakeUpPathType.OP_SCHEDULED_ALARM,
                            title = "Alarm Receiver ($simpleName)",
                            componentName = fullName,
                            reason = "Fires scheduled alarms waking the app from sleep to execute tasks",
                            wakeupCount = if (totalWakeupCount > 0) totalWakeupCount / 2 else 0,
                            isCut = isCut
                        )
                    )
                }
                simpleName.contains("Power", ignoreCase = true) || simpleName.contains("Battery", ignoreCase = true) -> {
                    list.add(
                        WakeUpPath(
                            id = id,
                            packageName = pkg,
                            type = WakeUpPathType.RECEIVER_POWER,
                            title = "Power Trigger ($simpleName)",
                            componentName = fullName,
                            reason = "Wakes app when charger is connected or battery level changes",
                            wakeupCount = 0,
                            isCut = isCut
                        )
                    )
                }
                simpleName.contains("Package", ignoreCase = true) || simpleName.contains("Install", ignoreCase = true) -> {
                    list.add(
                        WakeUpPath(
                            id = id,
                            packageName = pkg,
                            type = WakeUpPathType.RECEIVER_PACKAGE,
                            title = "Package Trigger ($simpleName)",
                            componentName = fullName,
                            reason = "Wakes app whenever other apps or this app are updated or installed",
                            wakeupCount = 0,
                            isCut = isCut
                        )
                    )
                }
                else -> {
                    list.add(
                        WakeUpPath(
                            id = id,
                            packageName = pkg,
                            type = WakeUpPathType.RECEIVER_CUSTOM,
                            title = "Broadcast Receiver ($simpleName)",
                            componentName = fullName,
                            reason = "Listens for broadcast intents to launch app components",
                            wakeupCount = 0,
                            isCut = isCut
                        )
                    )
                }
            }
        }

        // If RECEIVE_BOOT_COMPLETED is requested but not in receivers list
        if (pkgInfo.requestedPermissions?.contains("android.permission.RECEIVE_BOOT_COMPLETED") == true &&
            list.none { it.type == WakeUpPathType.RECEIVER_BOOT }
        ) {
            val id = "$pkg:perm:boot"
            list.add(
                WakeUpPath(
                    id = id,
                    packageName = pkg,
                    type = WakeUpPathType.RECEIVER_BOOT,
                    title = "Boot Permission",
                    componentName = "android.permission.RECEIVE_BOOT_COMPLETED",
                    reason = "Allows app to start automatically upon device boot",
                    wakeupCount = 1,
                    isCut = isAppCut || cutPathIds.contains(id)
                )
            )
        }

        // 2. Services
        val services = pkgInfo.services ?: emptyArray()
        for (service in services) {
            val simpleName = service.name.substringAfterLast('.')
            val fullName = if (service.name.startsWith(".")) "$pkg${service.name}" else service.name
            val id = "$pkg:service:$fullName"
            val isCut = isAppCut || cutPathIds.contains(id)

            when {
                simpleName.contains("Job", ignoreCase = true) || simpleName.contains("Work", ignoreCase = true) -> {
                    list.add(
                        WakeUpPath(
                            id = id,
                            packageName = pkg,
                            type = WakeUpPathType.SERVICE_JOB,
                            title = "JobService ($simpleName)",
                            componentName = fullName,
                            reason = "Executed by Android JobScheduler when network or idle conditions are met",
                            wakeupCount = if (totalWakeupCount > 4) totalWakeupCount / 4 else 0,
                            isCut = isCut
                        )
                    )
                }
                simpleName.contains("Download", ignoreCase = true) ||
                simpleName.contains("Sync", ignoreCase = true) ||
                simpleName.contains("Transfer", ignoreCase = true) ||
                simpleName.contains("Push", ignoreCase = true) -> {
                    list.add(
                        WakeUpPath(
                            id = id,
                            packageName = pkg,
                            type = WakeUpPathType.SERVICE_BACKGROUND,
                            title = "Background Worker ($simpleName)",
                            componentName = fullName,
                            reason = "Runs continuous background tasks, sync routines, or download transfers",
                            wakeupCount = if (totalWakeupCount > 0) totalWakeupCount else 0,
                            isCut = isCut
                        )
                    )
                }
                else -> {
                    list.add(
                        WakeUpPath(
                            id = id,
                            packageName = pkg,
                            type = WakeUpPathType.SERVICE_BACKGROUND,
                            title = "Background Service ($simpleName)",
                            componentName = fullName,
                            reason = "Declared service component capable of running in background",
                            wakeupCount = 0,
                            isCut = isCut
                        )
                    )
                }
            }
        }

        // 3. Permissions & Schedulers
        if (pkgInfo.requestedPermissions?.contains("android.permission.WAKE_LOCK") == true) {
            val id = "$pkg:perm:wakelock"
            list.add(
                WakeUpPath(
                    id = id,
                    packageName = pkg,
                    type = WakeUpPathType.OP_WAKE_LOCK,
                    title = "CPU Wake Lock",
                    componentName = "android.permission.WAKE_LOCK",
                    reason = "Allows app to prevent CPU from sleeping, keeping processes active during screen off",
                    wakeupCount = totalWakeupCount,
                    isCut = isAppCut || cutPathIds.contains(id)
                )
            )
        }

        if (pkgInfo.requestedPermissions?.contains("android.permission.SCHEDULE_EXACT_ALARM") == true ||
            pkgInfo.requestedPermissions?.contains("android.permission.USE_EXACT_ALARM") == true
        ) {
            val id = "$pkg:perm:alarm"
            list.add(
                WakeUpPath(
                    id = id,
                    packageName = pkg,
                    type = WakeUpPathType.OP_SCHEDULED_ALARM,
                    title = "Exact Alarm Scheduler",
                    componentName = "android.permission.SCHEDULE_EXACT_ALARM",
                    reason = "Triggers high-priority alarms at precise millisecond times waking the device",
                    wakeupCount = if (totalWakeupCount > 0) totalWakeupCount / 2 else 0,
                    isCut = isAppCut || cutPathIds.contains(id)
                )
            )
        }

        if (isIgnoredBattery) {
            val id = "$pkg:opt:battery"
            list.add(
                WakeUpPath(
                    id = id,
                    packageName = pkg,
                    type = WakeUpPathType.BATTERY_OPTIMIZATION,
                    title = "Battery Optimization Whitelist",
                    componentName = "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
                    reason = "App has bypassed Android Doze mode and App Standby, allowing unlimited background execution",
                    wakeupCount = totalWakeupCount,
                    isCut = isAppCut || cutPathIds.contains(id)
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
            // Ignore
        }
        map
    }

    private fun fallbackAppItem(pkg: String) = InstalledAppItem(
        packageName = pkg,
        appName = pkg,
        state = AppState.BACKGROUND_FREE,
        stateDetail = "Hibernated"
    )
}
