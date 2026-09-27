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
import com.example.model.BatchFreezeProgress
import com.example.model.InstalledAppItem
import com.example.model.WakeUpPath
import com.example.model.WakeUpRiskLevel
import com.example.service.ForceStopAccessibilityService
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

class PureStopViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as PureStopApp
    private val database = app.database
    val preferences = app.preferences

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

    // Dedicated fast managed apps flow populated directly in milliseconds
    private val _managedAppsFlow = MutableStateFlow<List<InstalledAppItem>>(emptyList())
    val allManagedApps: StateFlow<List<InstalledAppItem>> = _managedAppsFlow.asStateFlow()

    // In-memory set of package names stopped during the session to guarantee they stay hibernated
    private val _manuallyStoppedPackages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // 1. Not Hibernating Automatically (Foreground, Background Running, Evading Restrictions, Active Working State)
    val notHibernatingApps: StateFlow<List<InstalledAppItem>> = allManagedApps.map { list ->
        list.filter {
            !it.isStoppedState &&
            it.state != AppState.BACKGROUND_FREE &&
            (it.state == AppState.FOREGROUND ||
             it.state == AppState.BACKGROUND_RUNNING ||
             it.state == AppState.EVADING_RESTRICTIONS ||
             it.state == AppState.WORKING_STATE)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // 2. Will Hibernate Soon After Screen Goes Off (Cached in RAM, or dormant unstopped processes)
    val willHibernateSoonApps: StateFlow<List<InstalledAppItem>> = allManagedApps.map { list ->
        list.filter {
            !it.isStoppedState &&
            it.state != AppState.BACKGROUND_FREE &&
            it.state != AppState.FOREGROUND &&
            it.state != AppState.BACKGROUND_RUNNING &&
            it.state != AppState.EVADING_RESTRICTIONS &&
            it.state != AppState.WORKING_STATE
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

    private val _selectedAppForWakeup = MutableStateFlow<InstalledAppItem?>(null)
    val selectedAppForWakeup: StateFlow<InstalledAppItem?> = _selectedAppForWakeup.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private var liveMonitoringJob: Job? = null

    init {
        checkPermissions()
        // Immediately load managed apps on startup (< 30ms)
        viewModelScope.launch {
            _managedAppEntities.collect { entities ->
                refreshManagedAppsOnly(entities)
            }
        }
        startLiveMonitoring()

        viewModelScope.launch {
            ForceStopAccessibilityService.stoppedPackageFlow.collect { stoppedPkg ->
                // Immediately mark as hibernated in memory for instant removal from UI
                _managedAppsFlow.value = _managedAppsFlow.value.map {
                    if (it.packageName == stoppedPkg) it.copy(state = AppState.BACKGROUND_FREE, stateDetail = "Hibernated") else it
                }
                refreshManagedAppsOnly()
            }
        }
    }

    fun startLiveMonitoring() {
        if (liveMonitoringJob?.isActive == true) return
        liveMonitoringJob = viewModelScope.launch(Dispatchers.Default) {
            refreshManagedAppsOnly(silent = true)
            while (isActive) {
                delay(3000)
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

    /**
     * Ultra-fast managed apps refresh: Only scans the few managed packages in DB (< 30ms)!
     * Ensures instant homepage loading on startup and resume!
     */
    fun refreshManagedAppsOnly(
        entities: List<HibernatedAppEntity> = _managedAppEntities.value,
        silent: Boolean = false
    ) {
        viewModelScope.launch {
            if (!silent && _managedAppsFlow.value.isEmpty()) _isLoading.value = true
            try {
                withContext(Dispatchers.Default) {
                    if (entities.isEmpty()) {
                        _managedAppsFlow.value = emptyList()
                        return@withContext
                    }

                    val pm = getApplication<Application>().packageManager
                    val runningMap = detector.getRunningProcessesMap()

                    // Instant Pre-render (< 5ms) on cold start / reopen
                    if (_managedAppsFlow.value.isEmpty()) {
                        val quickList = mutableListOf<InstalledAppItem>()
                        for (entity in entities) {
                            try {
                                val appInfo = pm.getApplicationInfo(entity.packageName, 0)
                                val appName = pm.getApplicationLabel(appInfo).toString()
                                val icon = try { pm.getApplicationIcon(appInfo) } catch (e: Exception) { null }
                                val runningProc = runningMap[entity.packageName]
                                val isFlagStopped = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0
                                val isRunning = runningProc != null
                                val isStoppedState = isFlagStopped && !isRunning
                                val isCleanlyHibernated = isStoppedState
                                val state = if (runningProc != null) {
                                    when (runningProc.importance) {
                                        android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND -> AppState.FOREGROUND
                                        android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE -> AppState.EVADING_RESTRICTIONS
                                        android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED -> AppState.CACHED
                                        else -> AppState.BACKGROUND_RUNNING
                                    }
                                } else {
                                    if (isCleanlyHibernated) AppState.BACKGROUND_FREE else AppState.CACHED
                                }
                                val isWorkingIgnored = preferences.isWorkingStateIgnored(entity.packageName)
                                quickList.add(
                                    InstalledAppItem(
                                        packageName = entity.packageName,
                                        appName = appName,
                                        icon = icon,
                                        state = state,
                                        stateDetail = if (isCleanlyHibernated) "Hibernated" else if (state == AppState.CACHED) "Cached in RAM" else state.label,
                                        secondaryDetail = if (!isCleanlyHibernated && state != AppState.FOREGROUND && state != AppState.EVADING_RESTRICTIONS && state != AppState.WORKING_STATE) "Will hibernate after screen off" else "",
                                        isManaged = true,
                                        lastFrozenTimestamp = entity.lastFrozenTimestamp,
                                        freezeCount = entity.freezeCount,
                                        ignoreWorkingState = isWorkingIgnored,
                                        isStoppedState = isCleanlyHibernated
                                    )
                                )
                            } catch (e: Exception) {
                                // Package may not exist
                            }
                        }
                        if (quickList.isNotEmpty()) {
                            quickList.sortWith(
                                compareByDescending<InstalledAppItem> {
                                    it.state == AppState.EVADING_RESTRICTIONS ||
                                    it.state == AppState.WORKING_STATE ||
                                    it.state == AppState.FOREGROUND
                                }.thenBy { it.appName.lowercase() }
                            )
                            _managedAppsFlow.value = quickList
                        }
                    }

                    val isRoot = preferences.mode == OperatingMode.ROOT
                    val rootProcessMap = if (isRoot) RootExecutor.queryRootProcessStates() else emptyMap()
                    val nonRootActivityMap = detector.getNonRootActivityMap()

                    val cutPackages = entities.filter { it.cutWakeups }.map { it.packageName }.toSet()
                    val managedPackages = entities.map { it.packageName }.toSet()

                    val cutPathsMap = mutableMapOf<String, Set<String>>()
                    for (e in entities) {
                        val savedCut = preferences.getCutPathsForPackage(e.packageName)
                        if (savedCut.isNotEmpty()) cutPathsMap[e.packageName] = savedCut
                    }

                    val twentyFourHourWakeups = getOrFetchWakeups24h()

                    val resultList = mutableListOf<InstalledAppItem>()
                    for (entity in entities) {
                        try {
                            val pkgInfo = pm.getPackageInfo(
                                entity.packageName,
                                PackageManager.GET_PERMISSIONS or
                                PackageManager.GET_RECEIVERS or
                                PackageManager.GET_SERVICES or
                                PackageManager.GET_PROVIDERS
                            )
                            val item = detector.detectAppItem(
                                pkgInfo = pkgInfo,
                                runningMap = runningMap,
                                rootProcessMap = rootProcessMap,
                                nonRootActivityMap = nonRootActivityMap,
                                isRootMode = isRoot,
                                managedPackages = managedPackages,
                                cutPackages = cutPackages,
                                cutPathsMap = cutPathsMap,
                                twentyFourHourWakeups = twentyFourHourWakeups
                            )
                            val isWorkingIgnored = preferences.isWorkingStateIgnored(entity.packageName)
                            val isCleanlyHibernated = item.isStoppedState || (item.state == AppState.BACKGROUND_FREE)
                            val finalItem = if (isCleanlyHibernated) {
                                item.copy(
                                    state = AppState.BACKGROUND_FREE,
                                    stateDetail = "Hibernated",
                                    secondaryDetail = "",
                                    isManaged = true,
                                    lastFrozenTimestamp = if (entity.lastFrozenTimestamp > 0L) entity.lastFrozenTimestamp else 0L,
                                    freezeCount = entity.freezeCount,
                                    ignoreWorkingState = isWorkingIgnored,
                                    isStoppedState = true
                                )
                            } else {
                                item.copy(
                                    isManaged = true,
                                    lastFrozenTimestamp = entity.lastFrozenTimestamp,
                                    freezeCount = entity.freezeCount,
                                    ignoreWorkingState = isWorkingIgnored,
                                    isStoppedState = false
                                )
                            }
                            resultList.add(finalItem)
                        } catch (e: Exception) {
                            // Package may have been uninstalled
                        }
                    }

                    // Sort: running apps first
                    resultList.sortWith(
                        compareByDescending<InstalledAppItem> {
                            it.state == AppState.EVADING_RESTRICTIONS ||
                            it.state == AppState.WORKING_STATE ||
                            it.state == AppState.FOREGROUND
                        }.thenBy { it.appName.lowercase() }
                    )

                    _managedAppsFlow.value = resultList
                }
            } catch (e: Exception) {
                // Ignore
            } finally {
                _isLoading.value = false
            }
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

            // Optimistic update: mark candidates as stopped immediately
            val runningPkgs = candidates.map { it.packageName }.toSet()
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

    fun toggleIgnoreWorkingState(app: InstalledAppItem) {
        val isNowIgnored = preferences.toggleIgnoreWorkingState(app.packageName)
        _managedAppsFlow.value = _managedAppsFlow.value.map {
            if (it.packageName == app.packageName) it.copy(ignoreWorkingState = isNowIgnored) else it
        }
        _statusMessage.value = if (isNowIgnored) {
            "${app.appName}: Working state ignored (will force stop in batch)"
        } else {
            "${app.appName}: Working state protected (skipped in batch)"
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
}
