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
import com.example.model.WakeUpRiskLevel
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

        val rootState = rootProcessMap[pkg]
        val activeComponents = rootState?.activeComponents ?: emptySet()

        val paths = detectWakeUpPaths(
            pkgInfo = pkgInfo,
            cutPathIds = cutPathIds,
            isAppCut = isAppCut,
            totalWakeupCount = wakeupCount,
            isIgnoredBattery = isIgnoredBattery,
            activeComponents = activeComponents
        )

        val wakeUpDetails = WakeUpDetails(
            wakeupCount24h = wakeupCount,
            paths = paths,
            isCut = isAppCut || (paths.isNotEmpty() && paths.all { it.isCut })
        )

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
        isIgnoredBattery: Boolean,
        activeComponents: Set<String>
    ): List<WakeUpPath> {
        val list = mutableListOf<WakeUpPath>()
        val pkg = pkgInfo.packageName

        // 1. Content Providers (Major culprit behind silent app autostart like TeraBoxProvider, DocumentsProvider)
        val providers = pkgInfo.providers ?: emptyArray()
        for (provider in providers) {
            val simpleName = provider.name.substringAfterLast('.')
            val fullName = if (provider.name.startsWith(".")) "$pkg${provider.name}" else provider.name
            val authority = provider.authority ?: ""
            val id = "$pkg:provider:$fullName"
            val isCut = isAppCut || cutPathIds.contains(id)
            val isCurrentlyActive = activeComponents.contains(fullName) || activeComponents.any { it.contains(simpleName) }

            val isDocsProvider = authority.contains("documents", ignoreCase = true) ||
                simpleName.contains("Documents", ignoreCase = true) ||
                simpleName.contains("Drive", ignoreCase = true) ||
                simpleName.contains("File", ignoreCase = true)

            val isSyncProvider = authority.contains("sync", ignoreCase = true) ||
                simpleName.contains("Sync", ignoreCase = true)

            if (isDocsProvider) {
                list.add(
                    WakeUpPath(
                        id = id,
                        packageName = pkg,
                        type = WakeUpPathType.PROVIDER_DOCUMENTS,
                        title = "Documents Provider ($simpleName)",
                        componentName = fullName,
                        reason = "Android launches app process automatically whenever system storage or file picker queries document providers",
                        riskLevel = WakeUpRiskLevel.MODERATE,
                        riskExplanation = "Cutting stops autostart when browsing files; app files won't show in system file picker until app is launched",
                        isPrimaryCulprit = true,
                        isActiveVector = isCurrentlyActive,
                        wakeupCount = if (totalWakeupCount > 0) totalWakeupCount else 1,
                        isCut = isCut
                    )
                )
            } else if (isSyncProvider || provider.exported) {
                list.add(
                    WakeUpPath(
                        id = id,
                        packageName = pkg,
                        type = WakeUpPathType.PROVIDER_CONTENT,
                        title = "Content Provider ($simpleName)",
                        componentName = fullName,
                        reason = "Exported URI authority '$authority' accessed by system or external apps, triggering silent process launch",
                        riskLevel = WakeUpRiskLevel.MODERATE,
                        riskExplanation = "Cutting prevents other apps or system queries from launching this app in background",
                        isPrimaryCulprit = isCurrentlyActive || isSyncProvider,
                        isActiveVector = isCurrentlyActive,
                        wakeupCount = 0,
                        isCut = isCut
                    )
                )
            }
        }

        // 2. Services (SyncAdapters, JobScheduler, Workers, Background Services)
        val services = pkgInfo.services ?: emptyArray()
        for (service in services) {
            val simpleName = service.name.substringAfterLast('.')
            val fullName = if (service.name.startsWith(".")) "$pkg${service.name}" else service.name
            val id = "$pkg:service:$fullName"
            val isCut = isAppCut || cutPathIds.contains(id)
            val isCurrentlyActive = activeComponents.contains(fullName) || activeComponents.any { it.contains(simpleName) }

            val isSyncAdapter = simpleName.contains("Sync", ignoreCase = true) ||
                simpleName.contains("Account", ignoreCase = true) ||
                simpleName.contains("Authenticator", ignoreCase = true)

            val isJob = simpleName.contains("Job", ignoreCase = true) ||
                simpleName.contains("Work", ignoreCase = true) ||
                simpleName.contains("GcmTask", ignoreCase = true)

            val isPush = simpleName.contains("Push", ignoreCase = true) ||
                simpleName.contains("Fcm", ignoreCase = true) ||
                simpleName.contains("Messaging", ignoreCase = true)

            if (isSyncAdapter) {
                list.add(
                    WakeUpPath(
                        id = id,
                        packageName = pkg,
                        type = WakeUpPathType.SERVICE_SYNC_ADAPTER,
                        title = "Account SyncAdapter ($simpleName)",
                        componentName = fullName,
                        reason = "Android AccountManager / SyncManager automatically schedules and wakes this service to run background account synchronization",
                        riskLevel = WakeUpRiskLevel.MODERATE,
                        riskExplanation = "Cutting stops automatic cloud sync in background; manual refresh still works inside app",
                        isPrimaryCulprit = true,
                        isActiveVector = isCurrentlyActive,
                        wakeupCount = if (totalWakeupCount > 0) totalWakeupCount else 1,
                        isCut = isCut
                    )
                )
            } else if (isJob) {
                list.add(
                    WakeUpPath(
                        id = id,
                        packageName = pkg,
                        type = WakeUpPathType.SERVICE_JOB,
                        title = "JobScheduler ($simpleName)",
                        componentName = fullName,
                        reason = "Scheduled periodic worker invoked by Android JobScheduler under network or idle conditions",
                        riskLevel = WakeUpRiskLevel.SAFE,
                        riskExplanation = "Safe to cut: prevents periodic background jobs from running unprompted",
                        isPrimaryCulprit = isCurrentlyActive,
                        isActiveVector = isCurrentlyActive,
                        wakeupCount = 0,
                        isCut = isCut
                    )
                )
            } else if (isPush) {
                list.add(
                    WakeUpPath(
                        id = id,
                        packageName = pkg,
                        type = WakeUpPathType.SERVICE_FOREGROUND,
                        title = "Push Service ($simpleName)",
                        componentName = fullName,
                        reason = "Persistent messaging service responsible for processing real-time notifications",
                        riskLevel = WakeUpRiskLevel.RISKY,
                        riskExplanation = "High Risk: cutting this service will prevent or delay new incoming push notifications",
                        isPrimaryCulprit = isCurrentlyActive,
                        isActiveVector = isCurrentlyActive,
                        wakeupCount = 0,
                        isCut = isCut
                    )
                )
            } else if (service.exported || isCurrentlyActive || simpleName.contains("Download", ignoreCase = true) || simpleName.contains("Worker", ignoreCase = true)) {
                list.add(
                    WakeUpPath(
                        id = id,
                        packageName = pkg,
                        type = WakeUpPathType.SERVICE_BACKGROUND,
                        title = "Background Service ($simpleName)",
                        componentName = fullName,
                        reason = "Background worker service running tasks or listening for intent triggers",
                        riskLevel = WakeUpRiskLevel.MODERATE,
                        riskExplanation = "Stops background processing for this worker",
                        isPrimaryCulprit = isCurrentlyActive,
                        isActiveVector = isCurrentlyActive,
                        wakeupCount = 0,
                        isCut = isCut
                    )
                )
            }
        }

        // 3. Broadcast Receivers (Filter out noise, highlight real autostart culprits)
        val receivers = pkgInfo.receivers ?: emptyArray()
        for (receiver in receivers) {
            val simpleName = receiver.name.substringAfterLast('.')
            val fullName = if (receiver.name.startsWith(".")) "$pkg${receiver.name}" else receiver.name
            val id = "$pkg:receiver:$fullName"
            val isCut = isAppCut || cutPathIds.contains(id)

            val isBoot = simpleName.contains("Boot", ignoreCase = true) ||
                simpleName.contains("Startup", ignoreCase = true) ||
                simpleName.contains("Reboot", ignoreCase = true)

            val isNetwork = simpleName.contains("Network", ignoreCase = true) ||
                simpleName.contains("Connectivity", ignoreCase = true) ||
                simpleName.contains("Wifi", ignoreCase = true)

            val isAlarm = simpleName.contains("Alarm", ignoreCase = true) ||
                simpleName.contains("Timer", ignoreCase = true) ||
                simpleName.contains("Scheduler", ignoreCase = true)

            val isPower = simpleName.contains("Power", ignoreCase = true) ||
                simpleName.contains("Battery", ignoreCase = true) ||
                simpleName.contains("Charger", ignoreCase = true)

            val isTracker = simpleName.contains("Analytics", ignoreCase = true) ||
                simpleName.contains("Tracker", ignoreCase = true) ||
                simpleName.contains("Measurement", ignoreCase = true) ||
                simpleName.contains("InstallReferrer", ignoreCase = true) ||
                simpleName.contains("Facebook", ignoreCase = true) ||
                simpleName.contains("AppsFlyer", ignoreCase = true) ||
                simpleName.contains("Adjust", ignoreCase = true)

            val isPush = simpleName.contains("Push", ignoreCase = true) ||
                simpleName.contains("FirebaseInstanceId", ignoreCase = true) ||
                simpleName.contains("Fcm", ignoreCase = true) ||
                simpleName.contains("C2dm", ignoreCase = true) ||
                simpleName.contains("Notification", ignoreCase = true)

            if (isBoot) {
                list.add(
                    WakeUpPath(
                        id = id,
                        packageName = pkg,
                        type = WakeUpPathType.RECEIVER_BOOT,
                        title = "Boot Autostart ($simpleName)",
                        componentName = fullName,
                        reason = "Automatically launches app immediately when the phone powers on or restarts",
                        riskLevel = WakeUpRiskLevel.SAFE,
                        riskExplanation = "Safe to cut: completely prevents app from starting up on reboot",
                        isPrimaryCulprit = true,
                        wakeupCount = 1,
                        isCut = isCut
                    )
                )
            } else if (isNetwork) {
                list.add(
                    WakeUpPath(
                        id = id,
                        packageName = pkg,
                        type = WakeUpPathType.RECEIVER_CONNECTIVITY,
                        title = "Network Trigger ($simpleName)",
                        componentName = fullName,
                        reason = "Wakes app whenever Wi-Fi or cellular network state toggles or reconnects",
                        riskLevel = WakeUpRiskLevel.SAFE,
                        riskExplanation = "Safe to cut: stops background keepalive pings on network transitions",
                        wakeupCount = if (totalWakeupCount > 0) totalWakeupCount / 3 else 0,
                        isCut = isCut
                    )
                )
            } else if (isAlarm) {
                list.add(
                    WakeUpPath(
                        id = id,
                        packageName = pkg,
                        type = WakeUpPathType.OP_SCHEDULED_ALARM,
                        title = "Alarm Wakeup ($simpleName)",
                        componentName = fullName,
                        reason = "Receives scheduled alarms that wake app from CPU sleep to execute tasks",
                        riskLevel = WakeUpRiskLevel.SAFE,
                        riskExplanation = "Safe to cut: silences non-critical timer wake-ups",
                        wakeupCount = if (totalWakeupCount > 0) totalWakeupCount / 2 else 0,
                        isCut = isCut
                    )
                )
            } else if (isPower) {
                list.add(
                    WakeUpPath(
                        id = id,
                        packageName = pkg,
                        type = WakeUpPathType.RECEIVER_POWER,
                        title = "Power Trigger ($simpleName)",
                        componentName = fullName,
                        reason = "Wakes app when charger is plugged in or battery enters low power mode",
                        riskLevel = WakeUpRiskLevel.SAFE,
                        riskExplanation = "Safe to cut: stops background sync on charger connect",
                        wakeupCount = 0,
                        isCut = isCut
                    )
                )
            } else if (isTracker) {
                list.add(
                    WakeUpPath(
                        id = id,
                        packageName = pkg,
                        type = WakeUpPathType.RECEIVER_TRACKER,
                        title = "Analytics Tracker ($simpleName)",
                        componentName = fullName,
                        reason = "Background telemetry beacon sending usage metrics and analytics events",
                        riskLevel = WakeUpRiskLevel.SAFE,
                        riskExplanation = "Safe to cut: saves battery and network by blocking analytics autostart",
                        wakeupCount = 0,
                        isCut = isCut
                    )
                )
            } else if (isPush) {
                list.add(
                    WakeUpPath(
                        id = id,
                        packageName = pkg,
                        type = WakeUpPathType.RECEIVER_PUSH,
                        title = "Push Notification Receiver ($simpleName)",
                        componentName = fullName,
                        reason = "Receives cloud push messages and triggers notifications",
                        riskLevel = WakeUpRiskLevel.RISKY,
                        riskExplanation = "High Risk: cutting this will stop incoming push notifications for this app",
                        wakeupCount = 0,
                        isCut = isCut
                    )
                )
            } else if (receiver.exported) {
                list.add(
                    WakeUpPath(
                        id = id,
                        packageName = pkg,
                        type = WakeUpPathType.RECEIVER_CUSTOM,
                        title = "Exported Receiver ($simpleName)",
                        componentName = fullName,
                        reason = "Public broadcast receiver listening for system or third-party intents",
                        riskLevel = WakeUpRiskLevel.SAFE,
                        riskExplanation = "Safe to cut: isolates app from external intent broadcasts",
                        wakeupCount = 0,
                        isCut = isCut
                    )
                )
            }
        }

        // If RECEIVE_BOOT_COMPLETED is declared but no specific receiver caught above
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
                    riskLevel = WakeUpRiskLevel.SAFE,
                    riskExplanation = "Safe to cut: blocks boot autostart permission",
                    isPrimaryCulprit = true,
                    wakeupCount = 1,
                    isCut = isAppCut || cutPathIds.contains(id)
                )
            )
        }

        // 4. Permissions & Schedulers
        if (pkgInfo.requestedPermissions?.contains("android.permission.WAKE_LOCK") == true) {
            val id = "$pkg:perm:wakelock"
            list.add(
                WakeUpPath(
                    id = id,
                    packageName = pkg,
                    type = WakeUpPathType.OP_WAKE_LOCK,
                    title = "CPU Wake Lock",
                    componentName = "android.permission.WAKE_LOCK",
                    reason = "Allows app to prevent device CPU from sleeping during screen off",
                    riskLevel = WakeUpRiskLevel.SAFE,
                    riskExplanation = "Safe to cut: allows device to enter deep sleep without CPU battery drain",
                    isPrimaryCulprit = false,
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
                    riskLevel = WakeUpRiskLevel.SAFE,
                    riskExplanation = "Safe to cut: prevents exact timer wake-ups while device is sleeping",
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
                    riskLevel = WakeUpRiskLevel.SAFE,
                    riskExplanation = "Safe to cut: subjects app to standard Android battery saver and Doze limits",
                    isPrimaryCulprit = true,
                    wakeupCount = totalWakeupCount,
                    isCut = isAppCut || cutPathIds.contains(id)
                )
            )
        }

        // Sort paths: Primary Culprits & Active Vectors first, then by Risk Level (Safe -> Moderate -> Risky)
        list.sortWith(
            compareByDescending<WakeUpPath> { it.isActiveVector }
                .thenByDescending { it.isPrimaryCulprit }
                .thenBy { it.riskLevel.ordinal }
        )

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
