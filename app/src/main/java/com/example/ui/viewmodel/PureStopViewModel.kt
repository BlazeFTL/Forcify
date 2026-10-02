package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.PureStopApp
import com.example.data.HibernatedAppEntity
import com.example.data.OperatingMode
import com.example.data.PureStopPreferences
import com.example.detector.AppStatusDetector
import com.example.engine.ForceStopEngine
import com.example.engine.RootExecutor
import com.example.model.AppState
import com.example.model.AppRamUsageItem
import com.example.model.BatchFreezeProgress
import com.example.model.InstalledAppItem
import com.example.model.SystemRamOverview
import com.example.model.WakeUpPath
import com.example.model.WakeUpRiskLevel
import com.example.service.ForceStopAccessibilityService
import com.example.util.AppIconCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RootCheckStatus {
    IDLE,
    CHECKING,
    GRANTED,
    DENIED
}

enum class DashboardFilter {
    PENDING,
    ALL,
    EVADING,
    HIBERNATED
}

private data class StaticAppMeta(
    val appName: String,
    val icon: android.graphics.drawable.Drawable?,
    val isUnsafe: Boolean,
    val unsafeReason: String
)

class PureStopViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as PureStopApp
    private val database = app.database
    val preferences = app.preferences
    private val staticAppMetaCache = java.util.concurrent.ConcurrentHashMap<String, StaticAppMeta>()

    val detector = AppStatusDetector(application)
    val engine = ForceStopEngine(application, database, preferences)

    // Operating Mode
    private val _operatingMode = MutableStateFlow(preferences.mode)
    val operatingMode: StateFlow<OperatingMode> = _operatingMode.asStateFlow()

    private val _isSetupCompleted = MutableStateFlow(preferences.isSetupCompleted)
    val isSetupCompleted: StateFlow<Boolean> = _isSetupCompleted.asStateFlow()

    // Permissions State for Setup
    private val _hasUsageAccess = MutableStateFlow(detector.hasUsageStatsPermission())
    val hasUsageAccess: StateFlow<Boolean> = _hasUsageAccess.asStateFlow()

    private val _isAccessibilityEnabled = MutableStateFlow(ForceStopAccessibilityService.isRunning())
    val isAccessibilityEnabled: StateFlow<Boolean> = _isAccessibilityEnabled.asStateFlow()

    private val _isIgnoringBattery = MutableStateFlow(isIgnoringBatteryOptimization())
    val isIgnoringBattery: StateFlow<Boolean> = _isIgnoringBattery.asStateFlow()

    private val _rootStatus = MutableStateFlow(RootCheckStatus.IDLE)
    val rootStatus: StateFlow<RootCheckStatus> = _rootStatus.asStateFlow()

    // Add List System Apps Filter
    private val _hideSystemAppsInAddList = MutableStateFlow(preferences.hideSystemAppsInAddList)
    val hideSystemAppsInAddList: StateFlow<Boolean> = _hideSystemAppsInAddList.asStateFlow()

    // Add List Sort Option (Remembered across sessions)
    private val _addAppSortOption = MutableStateFlow(preferences.addAppSortOption)
    val addAppSortOption: StateFlow<com.example.model.AppSortOption> = _addAppSortOption.asStateFlow()

    fun setAddAppSortOption(sortOption: com.example.model.AppSortOption) {
        preferences.addAppSortOption = sortOption
        _addAppSortOption.value = sortOption
    }

    // Fast loading state: Starts false so homepage is instant!
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isLoadingAddApps = MutableStateFlow(false)
    val isLoadingAddApps: StateFlow<Boolean> = _isLoadingAddApps.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _currentFilter = MutableStateFlow(DashboardFilter.PENDING)
    val currentFilter: StateFlow<DashboardFilter> = _currentFilter.asStateFlow()

    private val _allInstalledApps = MutableStateFlow<List<InstalledAppItem>>(emptyList())
    val allInstalledApps: StateFlow<List<InstalledAppItem>> = _allInstalledApps.asStateFlow()

    private val _managedAppEntities = database.appDao().getAllManagedApps()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private fun loadInitialCachedItems(): List<InstalledAppItem> {
        val savedPkgs = preferences.savedManagedPackages
        if (savedPkgs.isEmpty()) return emptyList()

        val app = getApplication<Application>()
        AppIconCache.preloadFromDisk(app, savedPkgs)

        return loadPreloadedManagedApps()
    }

    // Dedicated fast managed apps flow populated directly from cache on line 1 (< 0.2ms)
    private val _managedAppsFlow = MutableStateFlow<List<InstalledAppItem>>(loadInitialCachedItems())
    val allManagedApps: StateFlow<List<InstalledAppItem>> = _managedAppsFlow.asStateFlow()

    // In-memory set of package names stopped during the session to guarantee they stay hibernated
    private val _manuallyStoppedPackages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // 1. Not Hibernating Automatically (Foreground, Evading Restrictions, Being used by Accessibility, or Active Working State)
    val notHibernatingApps: StateFlow<List<InstalledAppItem>> = allManagedApps.map { list ->
        list.filter {
            !it.isStoppedState &&
            it.state != AppState.BACKGROUND_FREE &&
            (it.state == AppState.FOREGROUND ||
             it.state == AppState.EVADING_RESTRICTIONS ||
             it.stateDetail.contains("Accessibility") ||
             (it.state == AppState.WORKING_STATE && !it.ignoreWorkingState))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 2. Will Hibernate Soon After Screen Goes Off (Background running processes, cached in RAM, or dormant unstopped)
    val willHibernateSoonApps: StateFlow<List<InstalledAppItem>> = allManagedApps.map { list ->
        list.filter {
            !it.isStoppedState &&
            it.state != AppState.BACKGROUND_FREE &&
            it.state != AppState.FOREGROUND &&
            it.state != AppState.EVADING_RESTRICTIONS &&
            !it.stateDetail.contains("Accessibility") &&
            (it.ignoreWorkingState || it.state != AppState.WORKING_STATE)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // All unhibernated apps that can be force stopped (Both Not Hibernating and Will Hibernate Soon)
    val pendingApps: StateFlow<List<InstalledAppItem>> = allManagedApps.map { list ->
        list.filter {
            !it.isStoppedState && it.state != AppState.BACKGROUND_FREE
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 3. Cleanly Hibernated Apps (Already stopped in system or frozen by app)
    val hibernatedApps: StateFlow<List<InstalledAppItem>> = allManagedApps.map { list ->
        list.filter {
            it.isStoppedState || it.state == AppState.BACKGROUND_FREE
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val batchProgress: StateFlow<BatchFreezeProgress> = engine.batchProgress

    // Dialogs & Sheets
    private val _showAddAppsSheet = MutableStateFlow(false)
    val showAddAppsSheet: StateFlow<Boolean> = _showAddAppsSheet.asStateFlow()

    private val _showCutBootDialog = MutableStateFlow(false)
    val showCutBootDialog: StateFlow<Boolean> = _showCutBootDialog.asStateFlow()

    private val _selectedAppForWakeup = MutableStateFlow<InstalledAppItem?>(null)
    val selectedAppForWakeup: StateFlow<InstalledAppItem?> = _selectedAppForWakeup.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _isInitialScanCompleted = MutableStateFlow(false)
    val isInitialScanCompleted: StateFlow<Boolean> = _isInitialScanCompleted.asStateFlow()

    private val _bootReceiverApps = MutableStateFlow<List<com.example.detector.BootReceiverItem>>(emptyList())
    val bootReceiverApps: StateFlow<List<com.example.detector.BootReceiverItem>> = _bootReceiverApps.asStateFlow()

    private val _isLoadingBootReceivers = MutableStateFlow(false)
    val isLoadingBootReceivers: StateFlow<Boolean> = _isLoadingBootReceivers.asStateFlow()

    private val _showRamUsageDialog = MutableStateFlow(false)
    val showRamUsageDialog: StateFlow<Boolean> = _showRamUsageDialog.asStateFlow()

    private val _hideSystemAppsInRam = MutableStateFlow(preferences.hideSystemAppsInRam)
    val hideSystemAppsInRam: StateFlow<Boolean> = _hideSystemAppsInRam.asStateFlow()

    fun setHideSystemAppsInRam(hide: Boolean) {
        _hideSystemAppsInRam.value = hide
        preferences.hideSystemAppsInRam = hide
    }

    val hibernatedPackageNames: StateFlow<Set<String>> = allManagedApps.map { list ->
        list.filter { it.isStoppedState || it.state == AppState.BACKGROUND_FREE }.map { it.packageName }.toSet() + _manuallyStoppedPackages
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _systemRamOverview = MutableStateFlow(detector.getSystemRamOverview())
    val systemRamOverview: StateFlow<SystemRamOverview> = _systemRamOverview.asStateFlow()

    private val _appsRamList = MutableStateFlow<List<AppRamUsageItem>>(emptyList())
    val appsRamList: StateFlow<List<AppRamUsageItem>> = _appsRamList.asStateFlow()

    private val _isLoadingRam = MutableStateFlow(false)
    val isLoadingRam: StateFlow<Boolean> = _isLoadingRam.asStateFlow()

    private var liveMonitoringJob: Job? = null

    init {
        checkPermissions()

        // Frame 0 pre-population of managed apps catalog without false initial scan completed flag
        val preloaded = loadPreloadedManagedApps()
        if (preloaded.isNotEmpty()) {
            _managedAppsFlow.value = preloaded
        }

        // Dedicated fast coroutine to pre-warm RAM usage immediately on startup so opening RAM Usage is instant (0ms wait)
        viewModelScope.launch(Dispatchers.IO) {
            val isRoot = preferences.mode == OperatingMode.ROOT
            val list = detector.getRunningAppsRamUsage(isRoot)
            if (list.isNotEmpty()) {
                _appsRamList.value = list
                _systemRamOverview.value = detector.getSystemRamOverview()
            }
        }

        // Secondary coroutine for installed apps catalog in Add Apps dialog
        viewModelScope.launch(Dispatchers.IO) {
            loadInstalledAppsForAddDialog()
        }

        viewModelScope.launch {
            _managedAppEntities.collect { entities ->
                refreshManagedAppsOnly(entities, silent = true)
            }
        }
        startLiveMonitoring()

        viewModelScope.launch {
            ForceStopAccessibilityService.stoppedPackageFlow.collect { stoppedPkg ->
                // Immediately mark as hibernated in memory for instant removal from UI
                _managedAppsFlow.value = _managedAppsFlow.value.map {
                    if (it.packageName == stoppedPkg) it.copy(state = AppState.BACKGROUND_FREE, stateDetail = "Hibernated", isStoppedState = true) else it
                }
                refreshManagedAppsOnly(silent = true)
            }
        }
    }

    fun startLiveMonitoring() {
        if (liveMonitoringJob?.isActive == true) return
        liveMonitoringJob = viewModelScope.launch(Dispatchers.Default) {
            refreshManagedAppsOnly(silent = true)
            while (isActive) {
                delay(600)
                refreshManagedAppsOnly(silent = true)
            }
        }
    }

    fun stopLiveMonitoring() {
        liveMonitoringJob?.cancel()
        liveMonitoringJob = null
    }

    fun setHideSystemAppsInAddList(hide: Boolean) {
        preferences.hideSystemAppsInAddList = hide
        _hideSystemAppsInAddList.value = hide
    }

    fun checkPermissions() {
        _hasUsageAccess.value = detector.hasUsageStatsPermission()
        _isAccessibilityEnabled.value = ForceStopAccessibilityService.isRunning()
        _isIgnoringBattery.value = isIgnoringBatteryOptimization()
    }

    private fun isIgnoringBatteryOptimization(): Boolean {
        val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isIgnoringBatteryOptimizations(getApplication<Application>().packageName) ?: false
    }

    fun testRootAccess() {
        viewModelScope.launch {
            _rootStatus.value = RootCheckStatus.CHECKING
            val hasRoot = RootExecutor.checkRootAccess()
            _rootStatus.value = if (hasRoot) RootCheckStatus.GRANTED else RootCheckStatus.DENIED
        }
    }

    fun selectOperatingMode(mode: OperatingMode) {
        preferences.mode = mode
        _operatingMode.value = mode
        refreshManagedAppsOnly()
    }

    fun completeSetup() {
        preferences.isSetupCompleted = true
        _isSetupCompleted.value = true
        refreshManagedAppsOnly()
    }

    fun resetSetup() {
        preferences.resetSetup()
        _isSetupCompleted.value = false
        _operatingMode.value = OperatingMode.UNCONFIGURED
    }

    fun setFilter(filter: DashboardFilter) {
        _currentFilter.value = filter
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun openAddApps() {
        _showAddAppsSheet.value = true
        if (_allInstalledApps.value.isEmpty()) {
            loadInstalledAppsForAddDialog()
        }
    }

    fun closeAddApps() {
        _showAddAppsSheet.value = false
    }

    fun selectAppForWakeup(app: InstalledAppItem?) {
        _selectedAppForWakeup.value = app
        if (app != null) {
            // Asynchronously deep inspect single selected app to populate full providers & sync components
            viewModelScope.launch(Dispatchers.Default) {
                deepInspectSingleApp(app.packageName)
            }
        }
    }

    private suspend fun deepInspectSingleApp(packageName: String) = withContext(Dispatchers.Default) {
        try {
            val pm = getApplication<Application>().packageManager
            val pkgInfo = pm.getPackageInfo(
                packageName,
                PackageManager.GET_PERMISSIONS or
                PackageManager.GET_RECEIVERS or
                PackageManager.GET_SERVICES or
                PackageManager.GET_PROVIDERS
            )
            val isRoot = preferences.mode == OperatingMode.ROOT
            val rootProcessMap = if (isRoot) RootExecutor.queryRootProcessStates() else emptyMap()
            val nonRootActivityMap = detector.getNonRootActivityMap()
            val runningMap = detector.getRunningProcessesMap()

            val cutPathsMap = mapOf(packageName to preferences.getCutPathsForPackage(packageName))
            val cutPackages = if (_managedAppEntities.value.any { it.packageName == packageName && it.cutWakeups }) setOf(packageName) else emptySet()
            val twentyFourHourWakeups = detector.calculateWakeups24h()

            val detailedItem = detector.detectAppItem(
                pkgInfo = pkgInfo,
                runningMap = runningMap,
                rootProcessMap = rootProcessMap,
                nonRootActivityMap = nonRootActivityMap,
                isRootMode = isRoot,
                managedPackages = setOf(packageName),
                cutPackages = cutPackages,
                cutPathsMap = cutPathsMap,
                twentyFourHourWakeups = twentyFourHourWakeups
            )

            if (_selectedAppForWakeup.value?.packageName == packageName) {
                _selectedAppForWakeup.value = detailedItem
            }

            // Also update in managed apps list
            _managedAppsFlow.value = _managedAppsFlow.value.map {
                if (it.packageName == packageName) detailedItem.copy(isManaged = true) else it
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    private var cachedWakeups24h: Map<String, Int> = emptyMap()
    private var lastWakeupsFetchTime: Long = 0L

    private suspend fun getOrFetchWakeups24h(): Map<String, Int> {
        val now = System.currentTimeMillis()
        if (now - lastWakeupsFetchTime < 120_000L && cachedWakeups24h.isNotEmpty()) {
            return cachedWakeups24h
        }
        val result = detector.calculateWakeups24h()
        cachedWakeups24h = result
        lastWakeupsFetchTime = now
        return result
    }

    private var activeRefreshJob: Job? = null

    private fun loadPreloadedManagedApps(): List<InstalledAppItem> {
        val savedPkgs = preferences.savedManagedPackages
        if (savedPkgs.isEmpty()) return emptyList()
        val pm = getApplication<Application>().packageManager
        val list = mutableListOf<InstalledAppItem>()
        for (pkg in savedPkgs) {
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val appName = preferences.getSavedAppName(pkg) ?: pm.getApplicationLabel(appInfo).toString()
                val icon = AppIconCache.getOrLoad(getApplication(), appInfo)
                val savedState = preferences.getSavedAppState(pkg) ?: AppState.CACHED
                val savedDetail = preferences.getSavedStateDetail(pkg) ?: "Pending Hibernation"
                val savedSecondary = preferences.getSavedSecondaryDetail(pkg) ?: ""
                val isStopped = false
                val isWorkingIgnored = preferences.isWorkingStateIgnored(pkg)
                val isRestricted = preferences.isRestrictRunningAsForeground(pkg)
                val (isUnsafe, unsafeReason) = detector.checkUnsafeToForceStop(pkg, appName)

                list.add(
                    InstalledAppItem(
                        packageName = pkg,
                        appName = appName,
                        icon = icon,
                        state = savedState,
                        stateDetail = savedDetail,
                        secondaryDetail = savedSecondary,
                        isSystemApp = false,
                        isManaged = true,
                        ignoreWorkingState = isWorkingIgnored,
                        isRestrictedForeground = isRestricted,
                        isStoppedState = isStopped,
                        isUnsafeToForceStop = isUnsafe,
                        unsafeReason = unsafeReason
                    )
                )
            } catch (e: Exception) {
                // Ignore package errors
            }
        }
        list.sortWith(
            compareByDescending<InstalledAppItem> {
                it.state == AppState.FOREGROUND || it.state == AppState.EVADING_RESTRICTIONS || it.state == AppState.WORKING_STATE
            }.thenByDescending {
                it.state == AppState.BACKGROUND_RUNNING
            }.thenBy { it.appName.lowercase() }
        )
        return list
    }

    /**
     * Ultra-fast managed apps refresh: Only scans the few managed packages in DB (< 30ms)!
     * Ensures instant homepage loading on startup and resume!
     */
    fun refreshManagedAppsOnly(
        entities: List<HibernatedAppEntity> = _managedAppEntities.value,
        silent: Boolean = false
    ) {
        if (activeRefreshJob?.isActive == true) {
            // Already actively computing live status! Avoid thrashing and let it finish smoothly.
            return
        }
        activeRefreshJob = viewModelScope.launch(Dispatchers.IO) {
            if (!silent && _managedAppsFlow.value.isEmpty()) _isLoading.value = true
            try {
                if (entities.isEmpty()) {
                    val savedPkgs = preferences.savedManagedPackages
                    if (savedPkgs.isEmpty()) {
                        _managedAppsFlow.value = emptyList()
                    }
                    return@launch
                }

                val pm = getApplication<Application>().packageManager
                val runningMap = detector.getRunningProcessesMap()
                val isRoot = preferences.mode == OperatingMode.ROOT
                val targetPkgs = entities.map { it.packageName }.toSet()
                val rootProcessMap = if (isRoot) RootExecutor.queryRootProcessStates(targetPkgs) else emptyMap()
                val nonRootActivityMap: Map<String, com.example.detector.NonRootProcessActivity> =
                    if (!isRoot) detector.getNonRootActivityMap() else emptyMap()
                val enabledAccessibilityPkgs = detector.getEnabledAccessibilityPackages()

                val resultList = mutableListOf<InstalledAppItem>()
                for (entity in entities) {
                    try {
                        val meta = staticAppMetaCache[entity.packageName] ?: run {
                            val appInfo = pm.getApplicationInfo(entity.packageName, 0)
                            val name = pm.getApplicationLabel(appInfo).toString()
                            preferences.setSavedAppName(entity.packageName, name)
                            val ic = AppIconCache.getOrLoad(getApplication(), appInfo)
                            val (unsafe, reason) = detector.checkUnsafeToForceStop(entity.packageName, name)
                            val created = StaticAppMeta(name, ic, unsafe, reason)
                            staticAppMetaCache[entity.packageName] = created
                            created
                        }

                        val appName = meta.appName
                        val icon = meta.icon
                        val isUnsafe = meta.isUnsafe
                        val unsafeReason = meta.unsafeReason

                        val rootState = rootProcessMap[entity.packageName]
                        val runningProc = runningMap[entity.packageName]
                        val nonRootActivity = nonRootActivityMap[entity.packageName]
                        val isInRecents = (rootState?.isInRecents == true) || (nonRootActivity?.isInRecents == true)
                        val isFlagStopped = if (runningProc != null || rootState?.isRunning == true || isInRecents) {
                            false
                        } else {
                            try {
                                (pm.getApplicationInfo(entity.packageName, 0).flags and android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0
                            } catch (e: Exception) { false }
                        }
                        val isRecentlyStopped = _manuallyStoppedPackages.contains(entity.packageName)

                        val isWorkingIgnored = preferences.isWorkingStateIgnored(entity.packageName)
                        val isRestrictedForeground = preferences.isRestrictRunningAsForeground(entity.packageName)
                        val isDownloaderOrMedia = detector.isDownloaderOrMediaApp(entity.packageName)
                        val showRestrictedForeground = isRestrictedForeground

                        val isAccessibilityActive = enabledAccessibilityPkgs.contains(entity.packageName)
                        val isTopForeground = (rootState?.isTop == true) || (runningProc?.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
                        val isFgService = (rootState?.isForegroundService == true) ||
                            (runningProc?.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE) ||
                            (runningProc?.importance == 125)

                        val hasActiveService = (rootState?.hasActiveService == true) ||
                            (runningProc != null && runningProc.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE && runningProc.importance > android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE)

                        if (isTopForeground) {
                            _manuallyStoppedPackages.remove(entity.packageName)
                        }

                        val isStoppedState = (isFlagStopped || isRecentlyStopped) && !isTopForeground && !isFgService && !isAccessibilityActive && !isInRecents

                        val (state, stateDetail, secondaryDetail) = if (isStoppedState) {
                            Triple(AppState.BACKGROUND_FREE, "Hibernated", "")
                        } else if (isAccessibilityActive) {
                            val sub = mutableListOf<String>()
                            if (isWorkingIgnored) sub.add("Ignored running state")
                            Triple(AppState.WORKING_STATE, "Being used by Accessibility", sub.joinToString("\n"))
                        } else if (isTopForeground) {
                            val sub = mutableListOf<String>()
                            if (isWorkingIgnored) sub.add("Ignored running state")
                            if (showRestrictedForeground) sub.add("Restricted running as foreground")
                            Triple(AppState.FOREGROUND, "Foreground", sub.joinToString("\n"))
                        } else if (isFgService) {
                            val sub = mutableListOf<String>()
                            if (isWorkingIgnored) sub.add("Ignored running state")
                            if (showRestrictedForeground) sub.add("Restricted running as foreground")
                            Triple(AppState.EVADING_RESTRICTIONS, "Running as foreground (evading restrictions)", sub.joinToString("\n"))
                        } else if (isInRecents && !isWorkingIgnored) {
                            val sub = mutableListOf<String>()
                            if (showRestrictedForeground) sub.add("Restricted running as foreground")
                            Triple(AppState.WORKING_STATE, "Working State", "In recent tasks (protected)")
                        } else if (isInRecents && isWorkingIgnored) {
                            val sub = mutableListOf("In recent tasks", "Ignored running state")
                            if (showRestrictedForeground) sub.add("Restricted running as foreground")
                            Triple(AppState.BACKGROUND_RUNNING, "Running in background", sub.joinToString("\n"))
                        } else if (hasActiveService) {
                            val sub = mutableListOf<String>()
                            if (isWorkingIgnored) sub.add("Ignored running state")
                            if (showRestrictedForeground) sub.add("Restricted running as foreground")
                            Triple(AppState.BACKGROUND_RUNNING, "Running in background", sub.joinToString("\n"))
                        } else {
                            val sub = mutableListOf<String>()
                            if (isWorkingIgnored) sub.add("Ignored running state")
                            if (showRestrictedForeground) sub.add("Restricted running as foreground")
                            Triple(AppState.CACHED, "Pending Hibernation", sub.joinToString("\n"))
                        }

                        // Persist state for instant zero-lag frame 0 reload on next app open
                        preferences.setSavedAppState(entity.packageName, state, stateDetail, secondaryDetail)

                        resultList.add(
                            InstalledAppItem(
                                packageName = entity.packageName,
                                appName = appName,
                                icon = icon,
                                state = state,
                                stateDetail = stateDetail,
                                secondaryDetail = secondaryDetail,
                                processImportance = runningProc?.importance ?: (if (state != AppState.BACKGROUND_FREE) 200 else 1000),
                                pid = rootState?.pid ?: runningProc?.pid,
                                isSystemApp = entity.isSystemApp,
                                isManaged = true,
                                lastFrozenTimestamp = entity.lastFrozenTimestamp,
                                freezeCount = entity.freezeCount,
                                ignoreWorkingState = isWorkingIgnored,
                                isRestrictedForeground = showRestrictedForeground,
                                isWakeUpMonitoringEnabled = preferences.isWakeUpMonitoringEnabled(entity.packageName),
                                isStoppedState = (state == AppState.BACKGROUND_FREE),
                                isUnsafeToForceStop = isUnsafe,
                                unsafeReason = unsafeReason
                            )
                        )
                    } catch (e: Exception) {
                        // Package may have been uninstalled
                    }
                }

                // Sort: Foreground & Evading & Working first, then Background running, then by name
                resultList.sortWith(
                    compareByDescending<InstalledAppItem> {
                        it.state == AppState.FOREGROUND || it.state == AppState.EVADING_RESTRICTIONS || it.state == AppState.WORKING_STATE
                    }.thenByDescending {
                        it.state == AppState.BACKGROUND_RUNNING
                    }.thenBy { it.appName.lowercase() }
                )

                val current = _managedAppsFlow.value
                val hasChanges = current.size != resultList.size ||
                    resultList.indices.any { i ->
                        val old = current[i]
                        val new = resultList[i]
                        old.packageName != new.packageName ||
                        old.state != new.state ||
                        old.stateDetail != new.stateDetail ||
                        old.secondaryDetail != new.secondaryDetail ||
                        old.isStoppedState != new.isStoppedState ||
                        old.ignoreWorkingState != new.ignoreWorkingState ||
                        old.isRestrictedForeground != new.isRestrictedForeground
                    }

                if (hasChanges || current.isEmpty()) {
                    _managedAppsFlow.value = resultList
                }

                preferences.savedManagedPackages = entities.map { it.packageName }.toSet()

                // Persist pending package set for frame 0 instant restoring on next cold launch
                val pendingPkgs = resultList.filter { !it.isStoppedState && it.state != AppState.BACKGROUND_FREE }
                    .map { it.packageName }.toSet()
                preferences.savedPendingPackages = pendingPkgs

                _isInitialScanCompleted.value = true
            } catch (e: Exception) {
                // Ignore transient errors
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun openCutBootDialog() {
        _showCutBootDialog.value = true
        loadBootReceivers()
    }

    fun closeCutBootDialog() {
        _showCutBootDialog.value = false
    }

    fun loadBootReceivers() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoadingBootReceivers.value = true
            try {
                val pm = getApplication<Application>().packageManager
                val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS or PackageManager.GET_RECEIVERS)
                val list = mutableListOf<com.example.detector.BootReceiverItem>()
                val managedPkgs = preferences.savedManagedPackages

                // Accurately query actual registered boot receivers via Android PackageManager
                val allBootReceiversMap = detector.getAllBootReceiversMap()

                for (pkgInfo in packages) {
                    val appInfo = pkgInfo.applicationInfo ?: continue
                    val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    val hasBootPerm = pkgInfo.requestedPermissions?.contains("android.permission.RECEIVE_BOOT_COMPLETED") == true
                    val bootComponents = allBootReceiversMap[pkgInfo.packageName]
                        ?: detector.getActualBootReceiversForPackage(pkgInfo.packageName)

                    // A package ONLY has boot receivers if it actually contains receiver components
                    // registered for BOOT_COMPLETED in AndroidManifest. If <intent-filter> was removed,
                    // the app will not start on boot and should not be shown as having boot receivers.
                    if (bootComponents.isNotEmpty()) {
                        val appName = try {
                            pm.getApplicationLabel(appInfo).toString()
                        } catch (e: Exception) {
                            pkgInfo.packageName
                        }
                        val icon = try { pm.getApplicationIcon(appInfo) } catch (e: Exception) { null }
                        val isCut = preferences.isBootCutForPackage(pkgInfo.packageName)
                        list.add(
                            com.example.detector.BootReceiverItem(
                                packageName = pkgInfo.packageName,
                                appName = appName,
                                icon = icon,
                                hasBootPermission = hasBootPerm,
                                bootReceiverCount = bootComponents.size,
                                bootReceiverComponents = bootComponents,
                                isCut = isCut,
                                isSystemApp = isSystem
                            )
                        )
                    }
                }

                // Sort: Managed apps first, then non-system user apps, then system apps, then by name
                list.sortWith(
                    compareByDescending<com.example.detector.BootReceiverItem> { managedPkgs.contains(it.packageName) }
                        .thenBy { it.isSystemApp }
                        .thenBy { it.appName.lowercase() }
                )
                _bootReceiverApps.value = list
            } catch (e: Exception) {
                // Ignore
            } finally {
                _isLoadingBootReceivers.value = false
            }
        }
    }

    fun cutBootReceiversForPackages(packages: List<String>, cut: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val isRoot = preferences.mode == OperatingMode.ROOT
            for (pkg in packages) {
                preferences.setBootCutForPackage(pkg, cut)
                if (isRoot) {
                    val item = _bootReceiverApps.value.find { it.packageName == pkg }
                    if (item != null && item.bootReceiverComponents.isNotEmpty()) {
                        for (comp in item.bootReceiverComponents) {
                            val formattedComp = if (comp.contains("/")) comp else "$pkg/$comp"
                            if (cut) {
                                RootExecutor.executeCommand("pm disable $formattedComp")
                            } else {
                                RootExecutor.executeCommand("pm enable $formattedComp")
                            }
                        }
                    }
                    if (cut) {
                        RootExecutor.executeCommand("cmd appops set $pkg BOOT_COMPLETED ignore")
                    } else {
                        RootExecutor.executeCommand("cmd appops set $pkg BOOT_COMPLETED allow")
                    }
                }
            }
            val pkgSet = packages.toSet()
            _bootReceiverApps.value = _bootReceiverApps.value.map {
                if (pkgSet.contains(it.packageName)) it.copy(isCut = cut) else it
            }
            _statusMessage.value = if (cut) {
                "Cut boot receivers for ${packages.size} app(s)"
            } else {
                "Restored boot receivers for ${packages.size} app(s)"
            }
        }
    }

    fun cutAllBootReceivers() {
        val targets = _bootReceiverApps.value.filter { !it.isCut }.map { it.packageName }
        if (targets.isNotEmpty()) {
            cutBootReceiversForPackages(targets, cut = true)
        }
    }

    fun refreshApps() {
        refreshManagedAppsOnly()
        if (_showAddAppsSheet.value) {
            loadInstalledAppsForAddDialog()
        }
    }

    /**
     * Fast enumeration of installed packages for AddAppsDialog (~50ms)
     */
    fun loadInstalledAppsForAddDialog() {
        viewModelScope.launch {
            _isLoadingAddApps.value = true
            try {
                withContext(Dispatchers.Default) {
                    val pm = getApplication<Application>().packageManager
                    // Use fast flags (0) instead of heavy GET_PERMISSIONS or GET_PROVIDERS
                    val packages = pm.getInstalledPackages(0)
                    val runningMap = detector.getRunningProcessesMap()
                    val myPackage = getApplication<Application>().packageName

                    val list = mutableListOf<InstalledAppItem>()
                    for (pkgInfo in packages) {
                        if (pkgInfo.packageName == myPackage) continue
                        val appInfo = pkgInfo.applicationInfo ?: continue
                        val appName = try {
                            pm.getApplicationLabel(appInfo).toString()
                        } catch (e: Exception) {
                            pkgInfo.packageName
                        }
                        val isSystem = detector.isSystemApp(pkgInfo)
                        val icon = try {
                            pm.getApplicationIcon(appInfo)
                        } catch (e: Exception) {
                            null
                        }

                        val runningProc = runningMap[pkgInfo.packageName]
                        val state = if (runningProc != null) {
                            when (runningProc.importance) {
                                android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> AppState.FOREGROUND
                                android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> AppState.EVADING_RESTRICTIONS
                                else -> AppState.WORKING_STATE
                            }
                        } else {
                            AppState.BACKGROUND_FREE
                        }

                        val size = try {
                            java.io.File(appInfo.sourceDir).length()
                        } catch (e: Exception) { 0L }
                        val installTime = pkgInfo.firstInstallTime
                        val (isUnsafe, unsafeReason) = detector.checkUnsafeToForceStop(pkgInfo, appName)

                        list.add(
                            InstalledAppItem(
                                packageName = pkgInfo.packageName,
                                appName = appName,
                                icon = icon,
                                state = state,
                                isSystemApp = isSystem,
                                isManaged = _managedAppEntities.value.any { it.packageName == pkgInfo.packageName },
                                firstInstallTime = installTime,
                                appSize = size,
                                isUnsafeToForceStop = isUnsafe,
                                unsafeReason = unsafeReason
                            )
                        )
                    }

                    list.sortWith(
                        compareByDescending<InstalledAppItem> {
                            it.state == AppState.EVADING_RESTRICTIONS ||
                            it.state == AppState.WORKING_STATE ||
                            it.state == AppState.FOREGROUND
                        }.thenBy { it.appName.lowercase() }
                    )

                    _allInstalledApps.value = list
                }
            } catch (e: Exception) {
                // Ignore
            } finally {
                _isLoadingAddApps.value = false
            }
        }
    }

    fun addAppsToFreezeList(apps: List<InstalledAppItem>) {
        viewModelScope.launch {
            val entities = apps.map {
                HibernatedAppEntity(
                    packageName = it.packageName,
                    appName = it.appName,
                    isAutoFreeze = true,
                    cutWakeups = it.wakeUpDetails.isCut,
                    isSystemApp = it.isSystemApp
                )
            }
            database.appDao().insertApps(entities)
            _showAddAppsSheet.value = false
            _statusMessage.value = "Added ${apps.size} apps to list"
            refreshManagedAppsOnly()
        }
    }

    fun removeAppFromFreezeList(packageName: String) {
        viewModelScope.launch {
            database.appDao().deleteApp(packageName)
            _managedAppsFlow.value = _managedAppsFlow.value.filter { it.packageName != packageName }
            _statusMessage.value = "Removed from list"
        }
    }

    fun forceStopSingle(app: InstalledAppItem) {
        viewModelScope.launch {
            _manuallyStoppedPackages.add(app.packageName)
            // Instant optimistic update so it marks as hibernated cleanly immediately (0ms feedback!)
            _managedAppsFlow.value = _managedAppsFlow.value.map {
                if (it.packageName == app.packageName) it.copy(state = AppState.BACKGROUND_FREE, stateDetail = "Hibernated", isStoppedState = true) else it
            }

            val success = engine.stopSingleApp(app)
            _statusMessage.value = if (success) "Force stopped ${app.appName}" else "Failed to force stop ${app.appName}"
            delay(250)
            refreshManagedAppsOnly(silent = true)
        }
    }

    fun forceStopAllRunning() {
        viewModelScope.launch {
            val candidates = pendingApps.value.filter {
                it.state != AppState.WORKING_STATE || it.ignoreWorkingState
            }

            val skippedWorkingCount = pendingApps.value.count {
                it.state == AppState.WORKING_STATE && !it.ignoreWorkingState
            }

            if (candidates.isEmpty()) {
                if (skippedWorkingCount > 0) {
                    _statusMessage.value = "$skippedWorkingCount app(s) in active Working Mode were protected and skipped"
                } else {
                    _statusMessage.value = "All apps are already stopped and hibernated!"
                }
                return@launch
            }

            val runningPkgs = candidates.map { it.packageName }.toSet()
            _manuallyStoppedPackages.addAll(runningPkgs)
            // Optimistic update: mark candidates as stopped immediately
            _managedAppsFlow.value = _managedAppsFlow.value.map {
                if (runningPkgs.contains(it.packageName)) it.copy(state = AppState.BACKGROUND_FREE, stateDetail = "Hibernated", isStoppedState = true) else it
            }

            engine.stopBatchApps(candidates) {
                viewModelScope.launch {
                    delay(300)
                    refreshManagedAppsOnly(silent = true)
                }
            }

            if (skippedWorkingCount > 0) {
                _statusMessage.value = "Force stopped ${candidates.size} app(s). $skippedWorkingCount working app(s) kept protected."
            } else {
                _statusMessage.value = "Force stopped ${candidates.size} app(s)"
            }
        }
    }

    fun forceStopSelected(apps: List<InstalledAppItem>) {
        viewModelScope.launch {
            if (apps.isEmpty()) return@launch
            val pkgs = apps.map { it.packageName }.toSet()
            _manuallyStoppedPackages.addAll(pkgs)
            _managedAppsFlow.value = _managedAppsFlow.value.map {
                if (pkgs.contains(it.packageName)) it.copy(state = AppState.BACKGROUND_FREE, stateDetail = "Hibernated", isStoppedState = true) else it
            }

            engine.stopBatchApps(apps) {
                viewModelScope.launch {
                    delay(300)
                    refreshManagedAppsOnly(silent = true)
                }
            }
            _statusMessage.value = "Force stopping ${apps.size} selected app(s)..."
        }
    }

    fun openRamUsageDialog() {
        _showRamUsageDialog.value = true
        if (_appsRamList.value.isEmpty()) {
            refreshRamUsage()
        } else {
            // Already cached/pre-warmed! Silent fast update in background (0ms instant display!)
            viewModelScope.launch(Dispatchers.IO) {
                _systemRamOverview.value = detector.getSystemRamOverview()
                val isRoot = preferences.mode == OperatingMode.ROOT
                val list = detector.getRunningAppsRamUsage(isRoot, forceRefresh = true)
                _appsRamList.value = list
            }
        }
    }

    fun closeRamUsageDialog() {
        _showRamUsageDialog.value = false
    }

    fun refreshRamUsage() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_appsRamList.value.isEmpty()) {
                _isLoadingRam.value = true
            }
            _systemRamOverview.value = detector.getSystemRamOverview()
            val isRoot = preferences.mode == OperatingMode.ROOT
            val list = detector.getRunningAppsRamUsage(isRoot, forceRefresh = true)
            _appsRamList.value = list
            _isLoadingRam.value = false
        }
    }

    fun stopAppFromRam(packageName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _manuallyStoppedPackages.add(packageName)
            // Instant optimistic update on managed apps flow so it marks as hibernated immediately
            _managedAppsFlow.value = _managedAppsFlow.value.map {
                if (it.packageName == packageName) it.copy(state = AppState.BACKGROUND_FREE, stateDetail = "Hibernated", isStoppedState = true) else it
            }
            val app = _managedAppsFlow.value.find { it.packageName == packageName }
                ?: _allInstalledApps.value.find { it.packageName == packageName }
                ?: InstalledAppItem(packageName = packageName, appName = packageName)
            engine.stopSingleApp(app)
            delay(300)
            refreshRamUsage()
            refreshManagedAppsOnly(silent = true)
        }
    }

    fun toggleIgnoreWorkingState(app: InstalledAppItem) {
        val isNowIgnored = preferences.toggleIgnoreWorkingState(app.packageName)
        _managedAppsFlow.value = _managedAppsFlow.value.map {
            if (it.packageName == app.packageName) it.copy(ignoreWorkingState = isNowIgnored) else it
        }
        _statusMessage.value = if (isNowIgnored) {
            "${app.appName}: Working state ignored (will hibernate after screen off)"
        } else {
            "${app.appName}: Working state protected"
        }
        refreshManagedAppsOnly(silent = true)
    }

    fun toggleRestrictRunningAsForeground(app: InstalledAppItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val isNowRestricted = preferences.toggleRestrictRunningAsForeground(app.packageName)
            if (preferences.mode == OperatingMode.ROOT) {
                if (isNowRestricted) {
                    RootExecutor.executeCommand("cmd appops set ${app.packageName} START_FOREGROUND ignore; cmd appops set ${app.packageName} RUN_IN_BACKGROUND ignore; cmd appops set ${app.packageName} RUN_ANY_IN_BACKGROUND ignore")
                } else {
                    RootExecutor.executeCommand("cmd appops set ${app.packageName} START_FOREGROUND default; cmd appops set ${app.packageName} RUN_IN_BACKGROUND default; cmd appops set ${app.packageName} RUN_ANY_IN_BACKGROUND default")
                }
            }
            _managedAppsFlow.value = _managedAppsFlow.value.map {
                if (it.packageName == app.packageName) it.copy(isRestrictedForeground = isNowRestricted) else it
            }
            _statusMessage.value = if (isNowRestricted) {
                "${app.appName}: Restricted running as foreground"
            } else {
                "${app.appName}: Allowed running as foreground"
            }
            refreshManagedAppsOnly(silent = true)
        }
    }

    fun launchApp(packageName: String) {
        try {
            val pm = getApplication<Application>().packageManager
            val intent = pm.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                getApplication<Application>().startActivity(intent)
            } else {
                _statusMessage.value = "Cannot launch app: no main activity found"
            }
        } catch (e: Exception) {
            _statusMessage.value = "Failed to launch app: ${e.message}"
        }
    }

    fun toggleSpecificWakeUpPath(app: InstalledAppItem, path: WakeUpPath, cut: Boolean) {
        viewModelScope.launch {
            preferences.togglePathCut(app.packageName, path.id, cut)
            if (preferences.mode == OperatingMode.ROOT) {
                if (cut) {
                    RootExecutor.cutSpecificWakeUpPath(path)
                } else {
                    RootExecutor.restoreSpecificWakeUpPath(path)
                }
            }

            // Update in-memory state of selected app
            val currentSelected = _selectedAppForWakeup.value
            if (currentSelected != null && currentSelected.packageName == app.packageName) {
                val updatedPaths = currentSelected.wakeUpDetails.paths.map {
                    if (it.id == path.id) it.copy(isCut = cut) else it
                }
                val allCut = updatedPaths.all { it.isCut }
                val updatedApp = currentSelected.copy(
                    wakeUpDetails = currentSelected.wakeUpDetails.copy(
                        paths = updatedPaths,
                        isCut = allCut
                    )
                )
                _selectedAppForWakeup.value = updatedApp
            }

            _statusMessage.value = if (cut) "Cut path: ${path.title}" else "Restored path: ${path.title}"
            refreshManagedAppsOnly(silent = true)
        }
    }

    fun cutSafeWakeUpPaths(app: InstalledAppItem) {
        viewModelScope.launch {
            val safePaths = app.wakeUpDetails.paths.filter { it.riskLevel == WakeUpRiskLevel.SAFE }
            val safePathIds = safePaths.map { it.id }.toSet()
            val currentCut = preferences.getCutPathsForPackage(app.packageName).toMutableSet()
            currentCut.addAll(safePathIds)
            preferences.setCutPathsForPackage(app.packageName, currentCut)

            if (preferences.mode == OperatingMode.ROOT) {
                for (p in safePaths) {
                    RootExecutor.cutSpecificWakeUpPath(p)
                }
            }

            val currentSelected = _selectedAppForWakeup.value
            if (currentSelected != null && currentSelected.packageName == app.packageName) {
                val updatedPaths = currentSelected.wakeUpDetails.paths.map {
                    if (it.riskLevel == WakeUpRiskLevel.SAFE) it.copy(isCut = true) else it
                }
                _selectedAppForWakeup.value = currentSelected.copy(
                    wakeUpDetails = currentSelected.wakeUpDetails.copy(
                        paths = updatedPaths,
                        isCut = updatedPaths.all { it.isCut }
                    )
                )
            }

            _statusMessage.value = "Cut ${safePaths.size} safe wake-up paths for ${app.appName}"
            refreshManagedAppsOnly(silent = true)
        }
    }

    fun cutAllWakeUpPaths(app: InstalledAppItem) {
        viewModelScope.launch {
            val allPathIds = app.wakeUpDetails.paths.map { it.id }.toSet()
            preferences.setCutPathsForPackage(app.packageName, allPathIds)

            if (preferences.mode == OperatingMode.ROOT) {
                for (p in app.wakeUpDetails.paths) {
                    RootExecutor.cutSpecificWakeUpPath(p)
                }
                RootExecutor.cutWakeUps(app.packageName)
            } else {
                engine.cutWakeUps(app)
            }

            val currentSelected = _selectedAppForWakeup.value
            if (currentSelected != null && currentSelected.packageName == app.packageName) {
                val updatedPaths = currentSelected.wakeUpDetails.paths.map { it.copy(isCut = true) }
                _selectedAppForWakeup.value = currentSelected.copy(
                    wakeUpDetails = currentSelected.wakeUpDetails.copy(
                        paths = updatedPaths,
                        isCut = true
                    )
                )
            }

            _statusMessage.value = "Cut all ${app.wakeUpDetails.paths.size} wake-up paths for ${app.appName}"
            refreshManagedAppsOnly(silent = true)
        }
    }

    fun restoreAllWakeUpPaths(app: InstalledAppItem) {
        viewModelScope.launch {
            preferences.setCutPathsForPackage(app.packageName, emptySet())

            if (preferences.mode == OperatingMode.ROOT) {
                for (p in app.wakeUpDetails.paths) {
                    RootExecutor.restoreSpecificWakeUpPath(p)
                }
                RootExecutor.restoreWakeUps(app.packageName)
            } else {
                engine.restoreWakeUps(app)
            }

            val currentSelected = _selectedAppForWakeup.value
            if (currentSelected != null && currentSelected.packageName == app.packageName) {
                val updatedPaths = currentSelected.wakeUpDetails.paths.map { it.copy(isCut = false) }
                _selectedAppForWakeup.value = currentSelected.copy(
                    wakeUpDetails = currentSelected.wakeUpDetails.copy(
                        paths = updatedPaths,
                        isCut = false
                    )
                )
            }

            _statusMessage.value = "Restored all wake-up paths for ${app.appName}"
            refreshManagedAppsOnly(silent = true)
        }
    }

    private val _showWakeUpManagerDialog = MutableStateFlow(false)
    val showWakeUpManagerDialog: StateFlow<Boolean> = _showWakeUpManagerDialog.asStateFlow()

    // Background detected wake-up events
    val detectedWakeUpEvents: StateFlow<List<com.example.model.DetectedWakeUpEvent>> =
        com.example.detector.BackgroundWakeUpDetector.detectedEvents

    fun cutDetectedWakeUpEvent(event: com.example.model.DetectedWakeUpEvent) {
        viewModelScope.launch(Dispatchers.IO) {
            val pkg = event.packageName
            val comp = event.componentName
            val formattedComp = if (comp.contains("/")) comp else "$pkg/$comp"

            val pathId = when (event.pathType) {
                com.example.model.WakeUpPathType.PROVIDER_DOCUMENTS, com.example.model.WakeUpPathType.PROVIDER_CONTENT -> "$pkg:provider:$comp"
                com.example.model.WakeUpPathType.SERVICE_SYNC_ADAPTER, com.example.model.WakeUpPathType.SERVICE_BACKGROUND, com.example.model.WakeUpPathType.SERVICE_FOREGROUND, com.example.model.WakeUpPathType.SERVICE_JOB -> "$pkg:service:$comp"
                com.example.model.WakeUpPathType.RECEIVER_BOOT, com.example.model.WakeUpPathType.RECEIVER_CONNECTIVITY, com.example.model.WakeUpPathType.RECEIVER_POWER, com.example.model.WakeUpPathType.RECEIVER_CUSTOM -> "$pkg:receiver:$comp"
                else -> "$pkg:comp:$comp"
            }

            preferences.togglePathCut(pkg, pathId, true)
            preferences.markDetectedWakeUpEventCut(event.id)

            if (preferences.mode == OperatingMode.ROOT) {
                RootExecutor.executeCommand("pm disable $formattedComp")
                RootExecutor.executeCommand("am force-stop $pkg")
            } else {
                RootExecutor.executeCommand("am force-stop $pkg")
            }

            _statusMessage.value = "Cut ${event.componentName.substringAfterLast('.')} and stopped ${event.appName}"
            refreshManagedAppsOnly(silent = true)
        }
    }

    fun dismissDetectedWakeUpEvent(event: com.example.model.DetectedWakeUpEvent) {
        preferences.removeDetectedWakeUpEvent(event.id)
    }

    fun cutAllDetectedWakeUps() {
        viewModelScope.launch(Dispatchers.IO) {
            val events = detectedWakeUpEvents.value.filter { !it.isCut }
            for (ev in events) {
                val pkg = ev.packageName
                val comp = ev.componentName
                val formattedComp = if (comp.contains("/")) comp else "$pkg/$comp"

                val pathId = when (ev.pathType) {
                    com.example.model.WakeUpPathType.PROVIDER_DOCUMENTS, com.example.model.WakeUpPathType.PROVIDER_CONTENT -> "$pkg:provider:$comp"
                    com.example.model.WakeUpPathType.SERVICE_SYNC_ADAPTER, com.example.model.WakeUpPathType.SERVICE_BACKGROUND, com.example.model.WakeUpPathType.SERVICE_FOREGROUND, com.example.model.WakeUpPathType.SERVICE_JOB -> "$pkg:service:$comp"
                    com.example.model.WakeUpPathType.RECEIVER_BOOT, com.example.model.WakeUpPathType.RECEIVER_CONNECTIVITY, com.example.model.WakeUpPathType.RECEIVER_POWER, com.example.model.WakeUpPathType.RECEIVER_CUSTOM -> "$pkg:receiver:$comp"
                    else -> "$pkg:comp:$comp"
                }
                preferences.togglePathCut(pkg, pathId, true)
                preferences.markDetectedWakeUpEventCut(ev.id)
                if (preferences.mode == OperatingMode.ROOT) {
                    RootExecutor.executeCommand("pm disable $formattedComp")
                    RootExecutor.executeCommand("am force-stop $pkg")
                }
            }
            _statusMessage.value = "Cut ${events.size} detected wake-up paths"
            refreshManagedAppsOnly(silent = true)
        }
    }

    fun clearAllDetectedWakeUps() {
        preferences.clearAllDetectedWakeUpEvents()
    }

    fun toggleWakeUpMonitoring(packageName: String): Boolean {
        val newState = preferences.toggleWakeUpMonitoring(packageName)
        _managedAppsFlow.value = _managedAppsFlow.value.map {
            if (it.packageName == packageName) it.copy(isWakeUpMonitoringEnabled = newState) else it
        }
        if (newState) {
            viewModelScope.launch(Dispatchers.IO) {
                com.example.detector.BackgroundWakeUpDetector.scanForWakeUps(
                    getApplication(),
                    setOf(packageName),
                    preferences
                )
            }
        }
        return newState
    }

    fun setWakeUpMonitoring(packageName: String, enabled: Boolean) {
        preferences.setWakeUpMonitoring(packageName, enabled)
        _managedAppsFlow.value = _managedAppsFlow.value.map {
            if (it.packageName == packageName) it.copy(isWakeUpMonitoringEnabled = enabled) else it
        }
        if (enabled) {
            viewModelScope.launch(Dispatchers.IO) {
                com.example.detector.BackgroundWakeUpDetector.scanForWakeUps(
                    getApplication(),
                    setOf(packageName),
                    preferences
                )
            }
        }
    }

    fun openWakeUpForPackage(packageName: String) {
        val app = allManagedApps.value.find { it.packageName == packageName }
        if (app != null) {
            selectAppForWakeup(app)
        }
    }

    fun openWakeUpManager() {
        _showWakeUpManagerDialog.value = true
    }

    fun closeWakeUpManager() {
        _showWakeUpManagerDialog.value = false
    }

    fun cutWakeUpPathsForPackages(packages: List<String>, safeOnly: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val apps = allManagedApps.value.filter { packages.contains(it.packageName) }
            for (app in apps) {
                if (safeOnly) {
                    cutSafeWakeUpPaths(app)
                } else {
                    cutAllWakeUpPaths(app)
                }
            }
            _statusMessage.value = if (safeOnly) {
                "Cut safe wake-up paths for ${apps.size} app(s)"
            } else {
                "Cut all wake-up paths for ${apps.size} app(s)"
            }
            refreshManagedAppsOnly(silent = true)
        }
    }

    fun restoreWakeUpPathsForPackages(packages: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val isRoot = preferences.mode == OperatingMode.ROOT
            for (pkg in packages) {
                preferences.setCutPathsForPackage(pkg, emptySet())
            }

            if (isRoot) {
                // Batch reset AppOps to standard Android default in a single root script (avoids process spawning lag and RAM spike)
                RootExecutor.restoreWakeUpsBatch(packages)
            } else {
                val apps = allManagedApps.value.filter { packages.contains(it.packageName) }
                for (app in apps) {
                    engine.restoreWakeUps(app)
                }
            }

            // Immediately update in-memory state cleanly
            val pkgSet = packages.toSet()
            _managedAppsFlow.value = _managedAppsFlow.value.map { item ->
                if (pkgSet.contains(item.packageName)) {
                    val updatedPaths = item.wakeUpDetails.paths.map { it.copy(isCut = false) }
                    item.copy(
                        wakeUpDetails = item.wakeUpDetails.copy(
                            paths = updatedPaths,
                            isCut = false
                        )
                    )
                } else item
            }

            _statusMessage.value = "Restored system defaults & re-attached wakeups for ${packages.size} app(s)"
            refreshManagedAppsOnly(silent = true)
        }
    }

    fun resetAllAppOpsToSystemDefault() {
        viewModelScope.launch(Dispatchers.IO) {
            val allPkgs = allManagedApps.value.map { it.packageName }
            for (pkg in allPkgs) {
                preferences.setCutPathsForPackage(pkg, emptySet())
            }
            preferences.setRestrictedForegroundPackages(emptySet())
            if (preferences.mode == OperatingMode.ROOT) {
                RootExecutor.restoreWakeUpsBatch(allPkgs)
            }
            _managedAppsFlow.value = _managedAppsFlow.value.map { item ->
                val updatedPaths = item.wakeUpDetails.paths.map { it.copy(isCut = false) }
                item.copy(
                    isRestrictedForeground = false,
                    wakeUpDetails = item.wakeUpDetails.copy(paths = updatedPaths, isCut = false)
                )
            }
            _statusMessage.value = "Reset all system AppOps to default. Apps will remember state normally!"
            refreshManagedAppsOnly(silent = true)
        }
    }

    fun cutAllActiveWakeups() {
        viewModelScope.launch {
            val candidates = allManagedApps.value.filter {
                !it.wakeUpDetails.isCut &&
                (it.state == AppState.EVADING_RESTRICTIONS || it.wakeUpDetails.wakeupCount24h > 0)
            }
            if (candidates.isEmpty()) {
                _statusMessage.value = "All active apps have already been cut!"
                return@launch
            }
            var count = 0
            for (c in candidates) {
                cutAllWakeUpPaths(c)
                count++
            }
            _statusMessage.value = "Cut wakeups for $count apps"
            refreshManagedAppsOnly(silent = true)
        }
    }

    fun openUsageAccessSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback
        }
    }

    fun openAccessibilitySettings(context: Context) {
        ForceStopAccessibilityService.openAccessibilitySettings(context)
    }

    fun requestIgnoreBatteryOptimizations(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {}
        }
    }

    // --- Export / Import App List Feature ---
    private val _showExportDialog = MutableStateFlow(false)
    val showExportDialog: StateFlow<Boolean> = _showExportDialog.asStateFlow()

    private val _showImportDialog = MutableStateFlow(false)
    val showImportDialog: StateFlow<Boolean> = _showImportDialog.asStateFlow()

    fun openExportDialog() { _showExportDialog.value = true }
    fun closeExportDialog() { _showExportDialog.value = false }
    fun openImportDialog() { _showImportDialog.value = true }
    fun closeImportDialog() { _showImportDialog.value = false }

    fun exportPackageNamesList(): List<String> {
        val pkgs = _managedAppEntities.value.map { it.packageName }
        return if (pkgs.isNotEmpty()) pkgs else preferences.savedManagedPackages.toList()
    }

    fun exportPackageNamesText(): String {
        return exportPackageNamesList().joinToString("\n")
    }

    fun exportPackageNamesJson(): String {
        val list = exportPackageNamesList()
        val jsonArray = org.json.JSONArray()
        for (pkg in list) {
            jsonArray.put(pkg)
        }
        return jsonArray.toString(2)
    }

    fun importPackageNamesFromRaw(rawInput: String): Triple<Int, Int, List<String>> {
        val pm = getApplication<Application>().packageManager
        val candidates = mutableSetOf<String>()

        // 1. Try parsing JSON array
        try {
            val trimmed = rawInput.trim()
            if (trimmed.startsWith("[")) {
                val arr = org.json.JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val p = arr.optString(i, "").trim()
                    if (p.isNotBlank()) candidates.add(p)
                }
            }
        } catch (e: Exception) {
            // Not JSON
        }

        // 2. Extract standard Android package names using Regex
        val regex = Regex("([a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+)")
        for (match in regex.findAll(rawInput)) {
            val p = match.value.trim()
            if (!p.startsWith("android.permission") && !p.startsWith("com.android.internal")) {
                candidates.add(p)
            }
        }

        val existing = _managedAppEntities.value.map { it.packageName }.toSet()
        val toAddEntities = mutableListOf<HibernatedAppEntity>()
        val missingInstalled = mutableListOf<String>()
        var alreadyExistingCount = 0

        for (pkg in candidates) {
            if (existing.contains(pkg)) {
                alreadyExistingCount++
                continue
            }
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val appName = pm.getApplicationLabel(appInfo).toString()
                val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                toAddEntities.add(
                    HibernatedAppEntity(
                        packageName = pkg,
                        appName = appName,
                        isAutoFreeze = true,
                        cutWakeups = false,
                        isSystemApp = isSystem
                    )
                )
            } catch (e: Exception) {
                missingInstalled.add(pkg)
            }
        }

        if (toAddEntities.isNotEmpty()) {
            viewModelScope.launch {
                database.appDao().insertApps(toAddEntities)
                preferences.savedManagedPackages = (preferences.savedManagedPackages + toAddEntities.map { it.packageName }).toSet()
                _statusMessage.value = "Imported ${toAddEntities.size} app(s) to ForCify"
                refreshManagedAppsOnly()
            }
        }

        return Triple(toAddEntities.size, alreadyExistingCount, missingInstalled)
    }
}
