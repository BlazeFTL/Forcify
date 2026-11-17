package com.example.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.example.data.OperatingMode
import com.example.data.PureStopPreferences
import com.example.detector.AppStatusDetector
import com.example.engine.RootExecutor
import com.example.model.AppState
import com.example.util.AppIconCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight background service matching Greenify's background service architecture.
 *
 * Benefits:
 * 1. Keeps process & icon/state caches warm so reopening after swiping from Recents is instant.
 * 2. Listens for ACTION_PACKAGE_RESTARTED so manual force-stops in Android Settings reflect immediately.
 * 3. Protects active/recent/accessibility/media/foreground-service apps cleanly on screen off.
 * 4. Shares the exact same state classification rules as the dashboard UI.
 */
class ForCifyDaemonService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var preferences: PureStopPreferences
    private lateinit var detector: AppStatusDetector
    private var screenReceiver: BroadcastReceiver? = null
    private var packageReceiver: BroadcastReceiver? = null
    private var hibernateJob: Job? = null
    private var stateSyncJob: Job? = null

    private val lastForegroundSeenAt = ConcurrentHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        preferences = PureStopPreferences(applicationContext)
        detector = AppStatusDetector(applicationContext)

        // Preload icons for all managed apps from disk cache immediately
        val savedPkgs = preferences.savedManagedPackages
        if (savedPkgs.isNotEmpty()) {
            AppIconCache.preloadFromDisk(applicationContext, savedPkgs)
        }

        registerScreenReceiver()
        registerPackageReceiver()

        // Start background wake-up detection engine
        com.example.detector.BackgroundWakeUpDetector.startMonitoring(
            context = applicationContext,
            scope = serviceScope,
            preferences = preferences
        )

        // Continuous lightweight background state sync
        startBackgroundStateSync()
    }

    private fun startBackgroundStateSync() {
        if (stateSyncJob?.isActive == true) return
        stateSyncJob = serviceScope.launch {
            while (isActive) {
                try {
                    val pmPower = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                    val isInteractive = pmPower?.isInteractive ?: true
                    if (isInteractive) {
                        syncManagedStatesNow()
                        delay(2500L)
                    } else {
                        delay(6000L)
                    }
                } catch (e: Exception) {
                    delay(4000L)
                }
            }
        }
    }

    private suspend fun syncManagedStatesNow() {
        val managedPkgs = preferences.savedManagedPackages
        if (managedPkgs.isEmpty()) return

        val now = System.currentTimeMillis()
        val isRoot = preferences.mode == OperatingMode.ROOT
        val pm = applicationContext.packageManager
        val installedAppsMap = try {
            pm.getInstalledApplications(0).associateBy { it.packageName }
        } catch (e: Exception) {
            emptyMap()
        }

        val rootProcessMap = if (isRoot) RootExecutor.queryRootProcessStates(managedPkgs) else emptyMap()
        val runningProcs = detector.getRunningProcessesMap()
        val nonRootActivityMap = if (!isRoot) detector.getNonRootActivityMap() else emptyMap()
        val accessibilityPkgs = detector.getEnabledAccessibilityPackages()

        val statesToSave = mutableMapOf<String, Triple<AppState, String, String>>()
        val pendingSet = mutableSetOf<String>()

        for (pkg in managedPkgs) {
            val appInfo = installedAppsMap[pkg]
            val isOsFlagStopped = if (appInfo != null) {
                (appInfo.flags and ApplicationInfo.FLAG_STOPPED) != 0
            } else false

            val rootState = rootProcessMap[pkg]
            val runningProc = runningProcs[pkg]
            val nonRootActivity = nonRootActivityMap[pkg]

            val isTopNow = (rootState?.isTop == true) ||
                (nonRootActivity?.isCurrentlyTop == true) ||
                (runningProc?.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
            if (isTopNow) {
                lastForegroundSeenAt[pkg] = now
            }

            val recentFgAt = lastForegroundSeenAt[pkg] ?: 0L
            val wasRecentlyForeground = recentFgAt > 0L && (now - recentFgAt < RECENT_FOREGROUND_PROTECTION_MS)

            val classified = detector.classifyManagedAppState(
                rootState = rootState,
                runningProc = runningProc,
                nonRootActivity = nonRootActivity,
                isRootMode = isRoot,
                isOsFlagStopped = isOsFlagStopped,
                isManuallyStopped = false,
                isAccessibilityActive = accessibilityPkgs.contains(pkg),
                isWorkingIgnored = preferences.isWorkingStateIgnored(pkg),
                isRestrictedForeground = preferences.isRestrictRunningAsForeground(pkg),
                recentForegroundBeforeScreenOff = wasRecentlyForeground
            )

            statesToSave[pkg] = classified
            if (classified.first != AppState.BACKGROUND_FREE) {
                pendingSet.add(pkg)
            }
        }

        preferences.batchSaveAppStates(statesToSave)
        preferences.savedPendingPackages = pendingSet
        _stateSyncedFlow.tryEmit(now)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val savedPkgs = preferences.savedManagedPackages
        if (savedPkgs.isNotEmpty()) {
            AppIconCache.preloadFromDisk(applicationContext, savedPkgs)
        }
        serviceScope.launch {
            try {
                syncManagedStatesNow()
            } catch (_: Exception) {
            }
        }
        return START_STICKY
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return

        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> onScreenOn()
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenReceiver, filter)
        }
    }

    private fun registerPackageReceiver() {
        if (packageReceiver != null) return

        packageReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_PACKAGE_RESTARTED) {
                    val pkg = intent.data?.schemeSpecificPart ?: return
                    lastForegroundSeenAt.remove(pkg)
                    if (preferences.savedManagedPackages.contains(pkg)) {
                        preferences.setSavedAppState(pkg, AppState.BACKGROUND_FREE, "Hibernated", "")
                        val pending = preferences.savedPendingPackages.toMutableSet()
                        if (pending.remove(pkg)) {
                            preferences.savedPendingPackages = pending
                        }
                    }
                    _packageRestartedFlow.tryEmit(pkg)
                }
            }
        }

        val filter = IntentFilter(Intent.ACTION_PACKAGE_RESTARTED).apply {
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(packageReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(packageReceiver, filter)
        }
    }

    private fun onScreenOff() {
        if (!preferences.autoHibernateAfterScreenOff) return

        hibernateJob?.cancel()
        hibernateJob = serviceScope.launch {
            delay(5000L)

            val pmPower = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pmPower?.isInteractive == true) return@launch

            val managedPkgs = preferences.savedManagedPackages
            val pendingPkgs = preferences.savedPendingPackages
            val targetPkgs = if (pendingPkgs.isNotEmpty()) pendingPkgs else managedPkgs
            if (targetPkgs.isEmpty()) return@launch

            val isRoot = preferences.mode == OperatingMode.ROOT
            if (!isRoot) return@launch

            val now = System.currentTimeMillis()
            val pm = applicationContext.packageManager
            val installedAppsMap = try {
                pm.getInstalledApplications(0).associateBy { it.packageName }
            } catch (e: Exception) {
                emptyMap()
            }
            val topMap = RootExecutor.queryRootProcessStates(targetPkgs)
            val nonRootActivityMap = detector.getNonRootActivityMap()
            val accessibilityPkgs = detector.getEnabledAccessibilityPackages(forceRefresh = true)

            val toStop = mutableListOf<String>()
            val stoppedStates = mutableMapOf<String, Triple<AppState, String, String>>()
            val remainingPending = mutableSetOf<String>()

            for (pkg in targetPkgs) {
                val appInfo = installedAppsMap[pkg]
                val isOsFlagStopped = appInfo != null && (appInfo.flags and ApplicationInfo.FLAG_STOPPED) != 0
                val state = topMap[pkg]
                if (isOsFlagStopped && state?.isRunning != true) {
                    stoppedStates[pkg] = Triple(AppState.BACKGROUND_FREE, "Hibernated", "")
                    continue
                }

                val isWorkingIgnored = preferences.isWorkingStateIgnored(pkg)
                val isRestrictedFg = preferences.isRestrictRunningAsForeground(pkg)

                // Always skip foreground service if not restricted (matches Greenify EVADING_RESTRICTIONS)
                if (state?.isForegroundService == true && !isRestrictedFg) {
                    remainingPending.add(pkg)
                    continue
                }

                if (!isWorkingIgnored) {
                    val isAccessibility = accessibilityPkgs.contains(pkg)
                    val recentFgAt = lastForegroundSeenAt[pkg] ?: 0L
                    val wasRecentlyForeground = (recentFgAt > 0L && (now - recentFgAt < RECENT_FOREGROUND_PROTECTION_MS)) ||
                        (nonRootActivityMap[pkg]?.isCurrentlyTop == true) ||
                        (nonRootActivityMap[pkg]?.isInRecents == true && state?.isRunning == true)

                    val isProtected = state?.isTop == true ||
                        state?.isInRecents == true ||
                        state?.isMediaPlaying == true ||
                        state?.isPerceptible == true ||
                        isAccessibility ||
                        wasRecentlyForeground

                    if (isProtected) {
                        remainingPending.add(pkg)
                        continue
                    }
                }

                toStop.add(pkg)
                stoppedStates[pkg] = Triple(AppState.BACKGROUND_FREE, "Hibernated", "")
            }

            if (toStop.isNotEmpty()) {
                RootExecutor.forceStopAppsBatch(toStop)
            }
            if (stoppedStates.isNotEmpty()) {
                preferences.batchSaveAppStates(stoppedStates)
            }
            preferences.savedPendingPackages = remainingPending
            _stateSyncedFlow.tryEmit(System.currentTimeMillis())
        }
    }

    private fun onScreenOn() {
        hibernateJob?.cancel()
        serviceScope.launch {
            try {
                syncManagedStatesNow()
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        screenReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
            screenReceiver = null
        }
        packageReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
            packageReceiver = null
        }
        com.example.detector.BackgroundWakeUpDetector.stopMonitoring(applicationContext)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val RECENT_FOREGROUND_PROTECTION_MS = 2 * 60 * 1000L

        private val _packageRestartedFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
        val packageRestartedFlow: SharedFlow<String> = _packageRestartedFlow.asSharedFlow()

        private val _stateSyncedFlow = MutableSharedFlow<Long>(replay = 1, extraBufferCapacity = 4)
        val stateSyncedFlow: SharedFlow<Long> = _stateSyncedFlow.asSharedFlow()

        fun start(context: Context) {
            try {
                val intent = Intent(context, ForCifyDaemonService::class.java)
                context.startService(intent)
            } catch (_: Exception) {
                // Restricted in background; will start on next foreground entry
            }
        }
    }
}
