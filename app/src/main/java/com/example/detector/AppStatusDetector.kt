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
    val hasActiveForegroundService: Boolean = false,
    val isInRecents: Boolean = false
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

    fun getEnabledAccessibilityPackages(): Set<String> {
        val result = mutableSetOf<String>()
        try {
            val enabledServices = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (!enabledServices.isNullOrBlank()) {
                val colonSplitter = android.text.TextUtils.SimpleStringSplitter(':')
                colonSplitter.setString(enabledServices)
                while (colonSplitter.hasNext()) {
                    val componentNameString = colonSplitter.next()
                    val componentName = android.content.ComponentName.unflattenFromString(componentNameString)
                    if (componentName != null) {
                        result.add(componentName.packageName)
                    }
                }
            }
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
            am?.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)?.forEach {
                result.add(it.resolveInfo.serviceInfo.packageName)
            }
        } catch (e: Exception) {
            // Ignore
        }
        return result
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

            val now = System.currentTimeMillis()
            for ((pkg, eventPair) in lastEvents) {
                val start = fgServiceStarts[pkg] ?: 0L
                val stop = fgServiceStops[pkg] ?: 0L
                val hasFgService = start > stop && (now - start < 12 * 60 * 60 * 1000L)
                val isRecent = (now - eventPair.second < 10 * 60 * 1000L) &&
                    (eventPair.first == UsageEvents.Event.ACTIVITY_RESUMED ||
                     eventPair.first == UsageEvents.Event.ACTIVITY_PAUSED)

                map[pkg] = NonRootProcessActivity(
                    lastEventType = eventPair.first,
                    lastEventTimestamp = eventPair.second,
                    hasActiveForegroundService = hasFgService,
                    isInRecents = isRecent
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
        val isFlagStopped = (appInfo.flags and ApplicationInfo.FLAG_STOPPED) != 0
        val isRunning = (rootState?.isRunning == true) || (runningProc != null)
        val isStoppedState = isFlagStopped && !isRunning
        val (isUnsafe, unsafeReason) = checkUnsafeToForceStop(pkgInfo, appName)

        val (state, stateDetail, secondaryDetail) = if (isRootMode && rootProcessMap.isNotEmpty()) {
            determineRootState(rootState, isIgnoredBattery, hasWakeLockPerm, wakeUpDetails, isFlagStopped, pkg)
        } else {
            determineNonRootState(runningProc, nonRootActivity, isIgnoredBattery, hasWakeLockPerm, wakeUpDetails, isFlagStopped, pkg)
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
            isManaged = managedPackages.contains(pkg),
            isStoppedState = isStoppedState,
            isUnsafeToForceStop = isUnsafe,
            unsafeReason = unsafeReason
        )
    }

    fun checkUnsafeToForceStop(pkgInfo: PackageInfo, appName: String): Pair<Boolean, String> {
        return checkUnsafeToForceStop(pkgInfo.packageName, appName)
    }

    fun checkUnsafeToForceStop(packageName: String, appName: String): Pair<Boolean, String> {
        val pkg = packageName.lowercase()
        val name = appName.lowercase()

        // 1. Keyboards / Input Methods
        val isInputMethod = pkg.contains("inputmethod") ||
            pkg.contains("keyboard") ||
            pkg.contains("gboard") ||
            pkg.contains("ime") ||
            name.contains("keyboard") ||
            name.contains("gboard") ||
            name.contains("ime") ||
            pkg == "com.google.android.inputmethod.latin" ||
            pkg == "com.touchtype.swiftkey" ||
            pkg == "com.syntellia.fleksy.keyboard"

        if (isInputMethod) {
            return Pair(true, "Keyboard / Input Method (typing will stop working)")
        }

        // 2. Volume Boosters / Sound Enhancers / Equalizers
        val isVolumeOrSound = pkg.contains("volume") ||
            pkg.contains("booster") ||
            pkg.contains("equalizer") ||
            pkg.contains("sound") ||
            name.contains("volume") ||
            name.contains("booster") ||
            name.contains("equalizer") ||
            name.contains("sound booster") ||
            pkg == "com.goodev.volume.booster" ||
            pkg == "com.pmml.callvolumebooster" ||
            name.contains("volume-screenshot-qs")

        if (isVolumeOrSound) {
            return Pair(true, "Volume / Audio Enhancer (media/sound issues)")
        }

        // 3. Instant Messaging & Calling (Notifications / calls not appearing)
        val isMessengerOrCalling = pkg == "com.whatsapp" ||
            pkg == "com.facebook.orca" ||
            pkg == "com.facebook.lite" ||
            pkg == "com.instagram.android" ||
            pkg == "org.telegram.messenger" ||
            pkg == "org.thoughtcrime.securesms" ||
            pkg == "com.discord" ||
            pkg == "com.skype.raider" ||
            pkg == "com.viber.voip" ||
            pkg == "com.tencent.mm" ||
            pkg == "jp.naver.line.android" ||
            name.contains("whatsapp") ||
            name.contains("messenger") ||
            name.contains("telegram") ||
            name.contains("signal") ||
            name.contains("discord") ||
            name.contains("viber") ||
            name.contains("wechat")

        if (isMessengerOrCalling) {
            return Pair(true, "Instant Messaging & Calls (notifications/calls will be missed)")
        }

        // 4. Emergency Alerts & Alarms
        val isEmergencyOrAlarm = pkg.contains("earthquake") ||
            pkg.contains("emergency") ||
            pkg.contains("alert") ||
            pkg.contains("alarm") ||
            pkg.contains("clock") ||
            pkg.contains("sos") ||
            name.contains("earthquake") ||
            name.contains("alert") ||
            name.contains("emergency") ||
            name.contains("alarm") ||
            pkg == "com.jrustonapps.myearthquakealerts"

        if (isEmergencyOrAlarm) {
            return Pair(true, "Alerts & Alarms (emergency or timed notifications won't sound)")
        }

        // 5. System Utilities, Root Tools, Find Device, Accessibility
        val isSystemUtil = pkg == "com.coderstory.toolkit" ||
            pkg == "org.frknkrc44.hma_oss" ||
            pkg == "com.oasis.greenify" ||
            pkg == "com.oasisfeng.greenify" ||
            pkg == "com.google.android.apps.adm" ||
            pkg == "com.topjohnwu.magisk" ||
            pkg == "io.github.vvb2060.magisk" ||
            pkg == "me.weishu.kernelsu" ||
            name.contains("core patch") ||
            name.contains("hma-oss") ||
            name.contains("find hub") ||
            name.contains("greenify")

        if (isSystemUtil) {
            return Pair(true, "System / Security Utility (may disrupt background system features)")
        }

        // 6. Home Launchers (Screenshots showed Spark Launcher, Nova, etc.)
        val isLauncher = pkg.contains("launcher") ||
            name.contains("launcher") ||
            pkg.contains("spark") ||
            name.contains("spark") ||
            name.contains("lawnchair") ||
            name.contains("nova") ||
            pkg == "com.teslacoilsw.launcher"

        if (isLauncher) {
            return Pair(true, "Home Launcher (closing will reset home screen and recents)")
        }

        // 7. VPN / Network Filters
        val isVpn = pkg.contains("vpn") ||
            name.contains("vpn") ||
            pkg.contains("wireguard") ||
            pkg.contains("adguard") ||
            name.contains("adguard") ||
            name.contains("clash") ||
            pkg.contains("clash")

        if (isVpn) {
            return Pair(true, "VPN / Network Service (disconnects active filtering/proxy)")
        }

        return Pair(false, "")
    }

    fun isDownloaderOrMediaApp(packageName: String): Boolean {
        val pkg = packageName.lowercase()
        return pkg.contains("idm") ||
            pkg.contains("download") ||
            pkg.contains("adm") ||
            pkg.contains("torrent") ||
            pkg.contains("videoplayer") ||
            pkg.contains("mxtech") ||
            pkg.contains("vlc") ||
            pkg.contains("spotify") ||
            pkg.contains("music") ||
            pkg.contains("podcast")
    }

    private fun determineRootState(
        rootState: RootProcessState?,
        isIgnoredBattery: Boolean,
        hasWakeLockPerm: Boolean,
        wakeUpDetails: WakeUpDetails,
        isFlagStopped: Boolean,
        packageName: String
    ): Triple<AppState, String, String> {
        if (rootState == null || !rootState.isRunning) {
            return if (isFlagStopped) {
                Triple(AppState.BACKGROUND_FREE, "Hibernated", "")
            } else {
                Triple(AppState.CACHED, "Pending Hibernation", "Will hibernate after screen off")
            }
        }

        if (rootState.isTop) {
            return Triple(AppState.FOREGROUND, "Foreground", "Active on screen")
        }

        // 1. RUNNING AS FOREGROUND SERVICE (EVADING RESTRICTIONS)
        if (rootState.isForegroundService) {
            return Triple(AppState.EVADING_RESTRICTIONS, "Running as foreground (evading restrictions)", "")
        }

        // 2. IN USER'S RECENTS -> Running in background (not protected working mode, stoppable)
        if (rootState.isInRecents) {
            return Triple(AppState.BACKGROUND_RUNNING, "Running in background", "In recent tasks")
        }

        // 3. REGULAR BACKGROUND RUNNING PROCESS (e.g. MovieBox, AyuGram, Claude, MT Manager)
        val secondary = if (isIgnoredBattery) "Restricted running as foreground" else ""
        return Triple(AppState.BACKGROUND_RUNNING, "Running in background", secondary)
    }

    private fun determineNonRootState(
        proc: ActivityManager.RunningAppProcessInfo?,
        nonRootActivity: NonRootProcessActivity?,
        isIgnoredBattery: Boolean,
        hasWakeLockPerm: Boolean,
        wakeUpDetails: WakeUpDetails,
        isFlagStopped: Boolean,
        packageName: String
    ): Triple<AppState, String, String> {
        val now = System.currentTimeMillis()
        val isRunning = proc != null || (nonRootActivity != null && now - nonRootActivity.lastEventTimestamp < 180_000)

        if (!isRunning) {
            return if (isFlagStopped) {
                Triple(AppState.BACKGROUND_FREE, "Hibernated", "")
            } else {
                Triple(AppState.CACHED, "Pending Hibernation", "Will hibernate after screen off")
            }
        }

        if (nonRootActivity != null) {
            val elapsed = now - nonRootActivity.lastEventTimestamp
            if (nonRootActivity.lastEventType == UsageEvents.Event.ACTIVITY_RESUMED && elapsed < 60_000) {
                return Triple(AppState.FOREGROUND, "Foreground", "Active on screen")
            }
        }

        if (proc != null) {
            val importance = proc.importance
            if (importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                return Triple(AppState.FOREGROUND, "Foreground", "Active on screen")
            }

            if (nonRootActivity?.hasActiveForegroundService == true ||
                importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) {
                return Triple(AppState.EVADING_RESTRICTIONS, "Running as foreground (evading restrictions)", "")
            }

            if (importance >= ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED) {
                return Triple(AppState.CACHED, "Cached in RAM", "Will hibernate after screen off")
            }

            val recentHint = if (nonRootActivity?.isInRecents == true && (now - nonRootActivity.lastEventTimestamp < 10 * 60 * 1000L)) {
                "In recent tasks"
            } else if (isIgnoredBattery) {
                "Restricted running as foreground"
            } else ""

            return Triple(AppState.BACKGROUND_RUNNING, "Running in background", recentHint)
        }

        return Triple(AppState.BACKGROUND_RUNNING, "Running in background", "")
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

    fun getBootReceiversForPackage(pkgInfo: PackageInfo): List<String> {
        val bootComponents = mutableListOf<String>()
        val receivers = pkgInfo.receivers ?: return bootComponents
        for (receiver in receivers) {
            val simpleName = receiver.name.substringAfterLast('.')
            val fullName = receiver.name
            if (simpleName.contains("Boot", ignoreCase = true) ||
                simpleName.contains("Startup", ignoreCase = true) ||
                simpleName.contains("Reboot", ignoreCase = true) ||
                fullName.contains("boot", ignoreCase = true) ||
                fullName.contains("startup", ignoreCase = true)
            ) {
                bootComponents.add(fullName)
            }
        }
        return bootComponents
    }

    private fun fallbackAppItem(pkg: String) = InstalledAppItem(
        packageName = pkg,
        appName = pkg,
        state = AppState.BACKGROUND_FREE,
        stateDetail = "Hibernated"
    )

    fun getSystemRamOverview(): com.example.model.SystemRamOverview {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val total = memInfo.totalMem
            val avail = memInfo.availMem
            val used = (total - avail).coerceAtLeast(0L)
            com.example.model.SystemRamOverview(
                totalBytes = total,
                availableBytes = avail,
                usedBytes = used
            )
        } catch (e: Exception) {
            com.example.model.SystemRamOverview()
        }
    }

    private var cachedRamList: List<com.example.model.AppRamUsageItem> = emptyList()
    private var lastRamFetchTime: Long = 0L

    suspend fun getRunningAppsRamUsage(isRoot: Boolean, forceRefresh: Boolean = false): List<com.example.model.AppRamUsageItem> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedRamList.isNotEmpty() && (now - lastRamFetchTime < 30_000L)) {
            return@withContext cachedRamList
        }

        val result = mutableMapOf<String, com.example.model.AppRamUsageItem>()
        val pm = context.packageManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

        // 1. Try dumpsys meminfo in Root mode
        if (isRoot) {
            try {
                val res = com.example.engine.RootExecutor.executeCommand("dumpsys meminfo -s")
                val output = res.getOrNull() ?: ""
                var inProcessSection = false
                val regex = Regex("""^\s*([0-9,]+)K:\s+([a-zA-Z0-9._]+)(?:\s+\(pid\s+(\d+)\))?""")

                for (line in output.lines()) {
                    if (line.contains("Total PSS by process:")) {
                        inProcessSection = true
                        continue
                    }
                    if (inProcessSection && (line.contains("Total PSS by category:") || line.contains("Total PSS by OOM adjustment:"))) {
                        break
                    }
                    if (inProcessSection) {
                        val match = regex.find(line)
                        if (match != null) {
                            val kbStr = match.groupValues[1].replace(",", "")
                            val pssKb = kbStr.toLongOrNull() ?: 0L
                            val procName = match.groupValues[2]
                            val pid = match.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
                            val pkg = procName.substringBefore(':')

                            if (pkg in setOf("system", "zygote", "zygote64", "surfaceflinger", "audioserver", "cameraserver", "mediaserver", "adbd", "init", "logd", "vold", "netd")) {
                                continue
                            }

                            try {
                                val appInfo = pm.getApplicationInfo(pkg, 0)
                                val appName = pm.getApplicationLabel(appInfo).toString()
                                val icon = com.example.util.AppIconCache.getOrLoad(context, appInfo)
                                val isSys = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                                    pkg.startsWith("com.android.") ||
                                    pkg.startsWith("com.google.android.gms") ||
                                    pkg.startsWith("com.google.android.gsf") ||
                                    pkg == "android"

                                val existing = result[pkg]
                                val combinedPss = (existing?.pssKb ?: 0L) + pssKb
                                result[pkg] = com.example.model.AppRamUsageItem(
                                    packageName = pkg,
                                    appName = appName,
                                    icon = icon,
                                    pssKb = combinedPss,
                                    pid = if (pid != 0) pid else existing?.pid ?: 0,
                                    isSystemApp = isSys
                                )
                            } catch (e: Exception) {
                                // Non-app process
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Root command fallback
            }
        }

        // 2. ActivityManager RunningAppProcessInfo for non-root or if root yielded no results
        if (result.isEmpty()) {
            try {
                val procs = am.runningAppProcesses ?: emptyList()
                val targetProcs = procs.filter { proc ->
                    proc.pkgList?.any { pkg ->
                        pkg != "system" && pkg != "android" && !result.containsKey(pkg)
                    } == true
                }.take(60)

                if (targetProcs.isNotEmpty()) {
                    val pidsArray = targetProcs.map { it.pid }.toIntArray()
                    val memInfos = am.getProcessMemoryInfo(pidsArray)
                    for (i in targetProcs.indices) {
                        val proc = targetProcs[i]
                        val pssKb = memInfos.getOrNull(i)?.totalPss?.toLong() ?: 0L
                        val pkgs = proc.pkgList ?: continue
                        for (pkg in pkgs) {
                            if (pkg in setOf("system", "android")) continue
                            if (!result.containsKey(pkg)) {
                                try {
                                    val appInfo = pm.getApplicationInfo(pkg, 0)
                                    val appName = pm.getApplicationLabel(appInfo).toString()
                                    val icon = com.example.util.AppIconCache.getOrLoad(context, appInfo)
                                    val isSys = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 ||
                                        pkg.startsWith("com.android.") ||
                                        pkg.startsWith("com.google.android.gms") ||
                                        pkg.startsWith("com.google.android.gsf") ||
                                        pkg == "android"
                                    result[pkg] = com.example.model.AppRamUsageItem(
                                        packageName = pkg,
                                        appName = appName,
                                        icon = icon,
                                        pssKb = pssKb,
                                        pid = proc.pid,
                                        isSystemApp = isSys
                                    )
                                } catch (e: Exception) {
                                    // Package not found
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        val finalSorted = result.values.toList().sortedByDescending { it.pssKb }
        cachedRamList = finalSorted
        lastRamFetchTime = now
        finalSorted
    }
}

data class BootReceiverItem(
    val packageName: String,
    val appName: String,
    val icon: android.graphics.drawable.Drawable?,
    val hasBootPermission: Boolean,
    val bootReceiverCount: Int,
    val bootReceiverComponents: List<String>,
    val isCut: Boolean,
    val isSystemApp: Boolean
)
