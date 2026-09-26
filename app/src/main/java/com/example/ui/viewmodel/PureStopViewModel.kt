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

    // Dashboard State
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _currentFilter = MutableStateFlow(DashboardFilter.PENDING)
    val currentFilter: StateFlow<DashboardFilter> = _currentFilter.asStateFlow()

    private val _allInstalledApps = MutableStateFlow<List<InstalledAppItem>>(emptyList())
    val allInstalledApps: StateFlow<List<InstalledAppItem>> = _allInstalledApps.asStateFlow()

    private val _managedAppEntities = database.appDao().getAllManagedApps()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // All managed apps joined with live status
    val allManagedApps: StateFlow<List<InstalledAppItem>> = combine(
        _managedAppEntities,
        _allInstalledApps
    ) { entities, installed ->
        val entityMap = entities.associateBy { it.packageName }
        installed.filter { entityMap.containsKey(it.packageName) }.map { item ->
            val entity = entityMap[item.packageName]
            item.copy(
                isManaged = true,
                lastFrozenTimestamp = entity?.lastFrozenTimestamp ?: 0L,
                freezeCount = entity?.freezeCount ?: 0,
                wakeUpDetails = item.wakeUpDetails.copy(isCut = entity?.cutWakeups == true || item.wakeUpDetails.isCut)
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Running managed apps (Pending / Working / Evading / Foreground) - exactly what should show on Home Page
    val pendingApps: StateFlow<List<InstalledAppItem>> = allManagedApps.map { list ->
        list.filter {
            it.state == AppState.FOREGROUND ||
            it.state == AppState.WORKING_STATE ||
            it.state == AppState.EVADING_RESTRICTIONS
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val hibernatedApps: StateFlow<List<InstalledAppItem>> = allManagedApps.map { list ->
        list.filter {
            it.state == AppState.BACKGROUND_FREE || it.state == AppState.CACHED
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
        refreshApps()
        startLiveMonitoring()

        viewModelScope.launch {
            ForceStopAccessibilityService.stoppedPackageFlow.collect { stoppedPkg ->
                // Immediately mark as hibernated in memory for instant feedback
                _allInstalledApps.value = _allInstalledApps.value.map {
                    if (it.packageName == stoppedPkg) it.copy(state = AppState.BACKGROUND_FREE, stateDetail = "Hibernated") else it
                }
                refreshApps(silent = true)
            }
        }
    }

    fun startLiveMonitoring() {
        if (liveMonitoringJob?.isActive == true) return
        liveMonitoringJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                delay(3000)
                refreshApps(silent = true)
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
        refreshApps()
    }

    fun completeSetup() {
        preferences.isSetupCompleted = true
        _isSetupCompleted.value = true
        refreshApps()
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
    }

    fun closeAddApps() {
        _showAddAppsSheet.value = false
    }

    fun selectAppForWakeup(app: InstalledAppItem?) {
        _selectedAppForWakeup.value = app
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }

    fun refreshApps(silent: Boolean = false) {
        viewModelScope.launch {
            if (!silent) _isLoading.value = true
            try {
                withContext(Dispatchers.Default) {
                    val pm = getApplication<Application>().packageManager
                    val packages = pm.getInstalledPackages(
                        PackageManager.GET_PERMISSIONS or
                        PackageManager.GET_RECEIVERS or
                        PackageManager.GET_SERVICES
                    )

                    val isRoot = preferences.mode == OperatingMode.ROOT
                    val rootProcessMap = if (isRoot) RootExecutor.queryRootProcessStates() else emptyMap()
                    val nonRootActivityMap = detector.getNonRootActivityMap()
                    val runningMap = detector.getRunningProcessesMap()

                    val cutPackages = mutableSetOf<String>()
                    val managedPackages = mutableSetOf<String>()
                    val dbEntities = _managedAppEntities.value
                    for (e in dbEntities) {
                        managedPackages.add(e.packageName)
                        if (e.cutWakeups) cutPackages.add(e.packageName)
                    }

                    val cutPathsMap = mutableMapOf<String, Set<String>>()
                    for (pkgInfo in packages) {
                        val savedCut = preferences.getCutPathsForPackage(pkgInfo.packageName)
                        if (savedCut.isNotEmpty()) {
                            cutPathsMap[pkgInfo.packageName] = savedCut
                        }
                    }

                    val twentyFourHourWakeups = detector.calculateWakeups24h()
                    val myPackage = getApplication<Application>().packageName

                    val resultList = mutableListOf<InstalledAppItem>()
                    for (pkgInfo in packages) {
                        if (pkgInfo.packageName == myPackage) continue
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
                        resultList.add(item)
                    }

                    // Sort: Evading / Working first, then alphabetical
                    resultList.sortWith(
                        compareByDescending<InstalledAppItem> {
                            it.state == AppState.EVADING_RESTRICTIONS ||
                            it.state == AppState.WORKING_STATE ||
                            it.state == AppState.FOREGROUND
                        }.thenBy { it.appName.lowercase() }
                    )

                    _allInstalledApps.value = resultList

                    // Keep selected dialog app in sync if open
                    val currentSelected = _selectedAppForWakeup.value
                    if (currentSelected != null) {
                        val updated = resultList.find { it.packageName == currentSelected.packageName }
                        if (updated != null) {
                            _selectedAppForWakeup.value = updated
                        }
                    }
                }
            } catch (e: Exception) {
                _statusMessage.value = "Failed to scan installed apps: ${e.localizedMessage}"
            } finally {
                if (!silent) _isLoading.value = false
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
            refreshApps(silent = true)
        }
    }

    fun removeAppFromFreezeList(packageName: String) {
        viewModelScope.launch {
            database.appDao().deleteApp(packageName)
            _statusMessage.value = "Removed from list"
            refreshApps(silent = true)
        }
    }

    fun forceStopSingle(app: InstalledAppItem) {
        viewModelScope.launch {
            // Instant optimistic update so it disappears immediately from running list
            _allInstalledApps.value = _allInstalledApps.value.map {
                if (it.packageName == app.packageName) it.copy(state = AppState.BACKGROUND_FREE, stateDetail = "Hibernated") else it
            }

            val success = engine.stopSingleApp(app)
            _statusMessage.value = if (success) "Force stopped ${app.appName}" else "Failed to force stop ${app.appName}"
            delay(250)
            refreshApps(silent = true)
        }
    }

    fun forceStopAllRunning() {
        viewModelScope.launch {
            val runningManaged = allManagedApps.value.filter {
                it.state == AppState.FOREGROUND ||
                it.state == AppState.WORKING_STATE ||
                it.state == AppState.EVADING_RESTRICTIONS
            }

            if (runningManaged.isEmpty()) {
                _statusMessage.value = "All apps are already stopped!"
                return@launch
            }

            engine.stopBatchApps(runningManaged) {
                viewModelScope.launch {
                    delay(300)
                    refreshApps(silent = true)
                }
            }
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
            refreshApps(silent = true)
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
            refreshApps(silent = true)
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
            refreshApps(silent = true)
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
            refreshApps(silent = true)
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
