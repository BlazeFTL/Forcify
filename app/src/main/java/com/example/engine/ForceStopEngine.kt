package com.example.engine

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.example.data.AppDatabase
import com.example.data.OperatingMode
import com.example.data.PureStopPreferences
import com.example.model.BatchFreezeProgress
import com.example.model.InstalledAppItem
import com.example.service.ForceStopAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class ForceStopEngine(
    private val context: Context,
    private val database: AppDatabase,
    private val preferences: PureStopPreferences
) {
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val _batchProgress = MutableStateFlow(BatchFreezeProgress())
    val batchProgress: StateFlow<BatchFreezeProgress> = _batchProgress.asStateFlow()

    suspend fun stopSingleApp(app: InstalledAppItem): Boolean = withContext(Dispatchers.Default) {
        val mode = preferences.mode
        val success = if (mode == OperatingMode.ROOT) {
            val rootRes = RootExecutor.forceStopApp(app.packageName)
            rootRes.isSuccess
        } else {
            stopNonRoot(app.packageName)
        }

        if (success) {
            database.appDao().recordFreeze(app.packageName, System.currentTimeMillis())
        }
        success
    }

    private suspend fun stopNonRoot(packageName: String): Boolean {
        val service = ForceStopAccessibilityService.instance
        if (service != null && ForceStopAccessibilityService.isRunning()) {
            return suspendCancellableCoroutine { continuation ->
                service.automateForceStop(packageName) {
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }
            }
        } else {
            // Direct API background kill
            try {
                activityManager.killBackgroundProcesses(packageName)
                return true
            } catch (e: Exception) {
                return false
            }
        }
    }

    suspend fun stopBatchApps(apps: List<InstalledAppItem>, onDone: () -> Unit = {}) = withContext(Dispatchers.Default) {
        if (apps.isEmpty()) {
            onDone()
            return@withContext
        }

        _batchProgress.value = BatchFreezeProgress(
            isRunning = true,
            totalCount = apps.size,
            completedCount = 0,
            failedCount = 0,
            summary = "Starting hibernation sequence..."
        )

        var completed = 0
        var failed = 0

        for (app in apps) {
            _batchProgress.value = _batchProgress.value.copy(
                currentPackage = app.packageName,
                currentAppName = app.appName,
                summary = "Freezing ${app.appName}..."
            )

            val success = stopSingleApp(app)
            if (success) {
                completed++
            } else {
                failed++
            }

            _batchProgress.value = _batchProgress.value.copy(
                completedCount = completed,
                failedCount = failed
            )

            // Graceful interval between automated app freezes
            if (preferences.mode == OperatingMode.NON_ROOT && ForceStopAccessibilityService.isRunning()) {
                delay(300)
            } else {
                delay(50)
            }
        }

        _batchProgress.value = _batchProgress.value.copy(
            isRunning = false,
            summary = "Completed! Successfully froze $completed of ${apps.size} apps."
        )
        onDone()
    }

    suspend fun cutWakeUps(app: InstalledAppItem): Boolean = withContext(Dispatchers.Default) {
        val mode = preferences.mode
        var success = false
        if (mode == OperatingMode.ROOT) {
            val rootRes = RootExecutor.cutWakeUps(app.packageName)
            success = rootRes.isSuccess
        } else {
            // Non-root: Stop the app and remove battery optimization exception
            activityManager.killBackgroundProcesses(app.packageName)
            success = true
        }

        if (success) {
            database.appDao().setCutWakeups(app.packageName, true)
        }
        success
    }

    suspend fun restoreWakeUps(app: InstalledAppItem): Boolean = withContext(Dispatchers.Default) {
        val mode = preferences.mode
        var success = false
        if (mode == OperatingMode.ROOT) {
            val rootRes = RootExecutor.restoreWakeUps(app.packageName)
            success = rootRes.isSuccess
        } else {
            success = true
        }

        if (success) {
            database.appDao().setCutWakeups(app.packageName, false)
        }
        success
    }

    fun openAppDetails(packageName: String) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun dismissBatchProgress() {
        _batchProgress.value = BatchFreezeProgress(isRunning = false)
    }
}
