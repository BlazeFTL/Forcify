package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.OperatingMode
import com.example.data.PureStopPreferences
import com.example.detector.AppStateClassifier
import com.example.detector.AppStatusDetector
import com.example.engine.RootExecutor
import com.example.util.AppIconCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lightweight always-on service (Greenify style).
 *
 * - Runs as a low priority foreground service, so it survives removal from recents
 *   and the process stays warm: reopening the app shows fresh states instantly.
 * - Listens for package broadcasts (force stop, install, remove) and tells the UI at once.
 * - Persists classified states with the same classifier the UI uses.
 * - On screen off, hibernates managed apps but never touches apps that are on screen,
 *   in recents, running a foreground service or used by accessibility.
 */
class ForCifyDaemonService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var preferences: PureStopPreferences
    private val detector by lazy { AppStatusDetector(applicationContext) }

    private var screenReceiver: BroadcastReceiver? = null
    private var packageReceiver: BroadcastReceiver? = null
    private var hibernateJob: Job? = null
    private var stateSyncJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        preferences = PureStopPreferences(applicationContext)
        promoteToForeground()

        val savedPkgs = preferences.savedManagedPackages
        if (savedPkgs.isNotEmpty()) {
            AppIconCache.preloadFromDisk(applicationContext, savedPkgs)
        }

        registerScreenReceiver()
        registerPackageReceiver()
        hardenSelf()

        com.example.detector.BackgroundWakeUpDetector.startMonitoring(
            context = applicationContext,
            scope = serviceScope,
            preferences = preferences
        )

        startBackgroundStateSync()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()
        val savedPkgs = preferences.savedManagedPackages
        if (savedPkgs.isNotEmpty()) {
            AppIconCache.preloadFromDisk(applicationContext, savedPkgs)
        }
        startBackgroundStateSync()
        return START_STICKY
    }

    private fun promoteToForeground() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Background monitor",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    setShowBadge(false)
                    description = "Keeps app state detection live"
                }
                nm?.createNotificationChannel(channel)
            }
            val openIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pi = PendingIntent.getActivity(
                this, 0, openIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_forcify)
                .setContentTitle("ForCify")
                .setContentText("Monitoring app states")
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // Not allowed to promote right now; service keeps running as a plain service
        }
    }

    /** Root mode only: keep our own process out of battery restrictions so the daemon is not killed. */
    private fun hardenSelf() {
        if (preferences.mode != OperatingMode.ROOT) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isIgnoringBatteryOptimizations(packageName) == true) return
        serviceScope.launch {
            RootExecutor.executeCommand(
                "dumpsys deviceidle whitelist +$packageName >/dev/null 2>&1; " +
                    "cmd appops set $packageName RUN_ANY_IN_BACKGROUND allow >/dev/null 2>&1"
            )
        }
    }

    // ---------------------------------------------------------------- state sync

    private fun startBackgroundStateSync() {
        if (stateSyncJob?.isActive == true) return
        stateSyncJob = serviceScope.launch {
            while (isActive) {
                try {
                    if (!AppStateHub.screenOn.value) {
                        // Nothing changes for the user while screen is off. Sleep until it wakes.
                        AppStateHub.screenOn.first { it }
                        continue
                    }
                    // While UI is visible it refreshes states itself and persists them.
                    if (!AppStateHub.uiVisible) {
                        syncStatesOnce(maxDeepAgeMs = 60_000L)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Ignore transient errors
                }
                delay(if (AppStateHub.uiVisible) 3_000L else 10_000L)
            }
        }
    }

    private suspend fun computeStates(
        packages: Set<String>,
        maxDeepAgeMs: Long
    ): Map<String, AppStateClassifier.Result> {
        val pm = applicationContext.packageManager
        val installed = try {
            pm.getInstalledApplications(0).associateBy { it.packageName }
        } catch (e: Exception) {
            emptyMap()
        }
        val isRoot = preferences.mode == OperatingMode.ROOT
        val rootMap = if (isRoot) RootExecutor.queryRootProcessStates(packages, maxDeepAgeMs) else emptyMap()
        val authoritative = isRoot && RootExecutor.lastQueryOk
        val nonRootMap = if (!authoritative) detector.getNonRootActivityMap() else emptyMap()
        val runningMap = detector.getRunningProcessesMap()
        val accessibilityPkgs = detector.getEnabledAccessibilityPackages()

        val out = HashMap<String, AppStateClassifier.Result>()
        for (pkg in packages) {
            val info = installed[pkg]
            out[pkg] = AppStateClassifier.classify(
                AppStateClassifier.Input(
                    rootAuthoritative = authoritative,
                    rootState = rootMap[pkg],
                    runningProc = runningMap[pkg],
                    nonRoot = nonRootMap[pkg],
                    isOsFlagStopped = info != null && (info.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0,
                    isAccessibilityActive = accessibilityPkgs.contains(pkg),
                    isWorkingIgnored = preferences.isWorkingStateIgnored(pkg),
                    isRestrictedForeground = preferences.isRestrictRunningAsForeground(pkg),
                    manuallyStoppedRecently = false
                )
            )
        }
        return out
    }

    private suspend fun syncStatesOnce(maxDeepAgeMs: Long) {
        val managed = preferences.savedManagedPackages
        if (managed.isEmpty()) return
        val states = computeStates(managed, maxDeepAgeMs)
        preferences.batchSaveAppStates(states.mapValues { Triple(it.value.state, it.value.detail, it.value.secondary) })
        preferences.savedPendingPackages = states.filterValues { !it.isStopped }.keys
    }

    // ---------------------------------------------------------------- receivers

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val receiver = object : BroadcastReceiver() {
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
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiver = receiver
    }

    private fun registerPackageReceiver() {
        if (packageReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val pkg = intent?.data?.schemeSpecificPart ?: return
                // ACTION_PACKAGE_RESTARTED is sent whenever a package is force stopped (here, Settings or anywhere else)
                RootExecutor.invalidateCache()
                AppStateHub.emitPackage(pkg)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_RESTARTED)
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        packageReceiver = receiver
    }

    private fun onScreenOff() {
        AppStateHub.setScreenOn(false)
        if (!preferences.autoHibernateAfterScreenOff) return

        hibernateJob?.cancel()
        hibernateJob = serviceScope.launch {
            // What was on screen right before the lock. Protected like recents.
            val topBefore: Set<String> =
                if (SystemClock.elapsedRealtime() - RootExecutor.lastKnownTopAt < 60_000L) RootExecutor.lastKnownTop else emptySet()

            delay(HIBERNATE_DELAY_MS)

            if (preferences.mode != OperatingMode.ROOT) return@launch
            val managed = preferences.savedManagedPackages
            if (managed.isEmpty()) return@launch

            RootExecutor.invalidateCache()
            val states = computeStates(managed, maxDeepAgeMs = 0L)
            // Never act blindly: if the root snapshot failed we cannot know what is protected.
            if (!RootExecutor.lastQueryOk) return@launch

            val targets = managed.filter { pkg ->
                val st = states[pkg] ?: return@filter false
                if (st.isStopped) return@filter false
                val ignoreWorking = preferences.isWorkingStateIgnored(pkg)
                val wasOnScreen = !ignoreWorking && topBefore.contains(pkg)
                !st.isProtected(ignoreWorking) && !wasOnScreen
            }
            if (targets.isEmpty()) return@launch
            if (AppStateHub.screenOn.value) return@launch

            RootExecutor.forceStopPackages(targets)
            try {
                syncStatesOnce(maxDeepAgeMs = 0L)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun onScreenOn() {
        AppStateHub.setScreenOn(true)
        hibernateJob?.cancel()
    }

    override fun onDestroy() {
        screenReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) { } }
        packageReceiver?.let { try { unregisterReceiver(it) } catch (e: Exception) { } }
        screenReceiver = null
        packageReceiver = null
        com.example.detector.BackgroundWakeUpDetector.stopMonitoring(applicationContext)
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "forcify_monitor"
        private const val NOTIFICATION_ID = 7001
        private const val HIBERNATE_DELAY_MS = 10_000L

        fun start(context: Context) {
            val intent = Intent(context, ForCifyDaemonService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                try {
                    context.startService(intent)
                } catch (e2: Exception) {
                    // Restricted: will start when UI is in foreground
                }
            }
        }
    }
}
