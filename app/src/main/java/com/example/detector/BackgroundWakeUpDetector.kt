package com.example.detector

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.PureStopPreferences
import com.example.engine.RootExecutor
import com.example.model.DetectedWakeUpEvent
import com.example.model.WakeUpPathType
import com.example.service.WakeUpCutReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object BackgroundWakeUpDetector {

    private const val CHANNEL_ID = "forcify_wakeup_alerts"
    private const val NOTIFICATION_ID_BASE = 5000

    private val _detectedEvents = MutableStateFlow<List<DetectedWakeUpEvent>>(emptyList())
    val detectedEvents = _detectedEvents.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetworkChangeTime: Long = 0L
    private var lastNetworkType: String = ""
    private var monitorJob: Job? = null
    private val notifiedComponentCache = mutableMapOf<String, Long>()

    fun init(context: Context, preferences: PureStopPreferences) {
        _detectedEvents.value = preferences.getDetectedWakeUpEvents()
        createNotificationChannel(context)
    }

    private fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Background Wake-Up Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Alerts when apps secretly wake up in the background with 1-tap option to cut the path"
                enableLights(true)
                enableVibration(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    fun startMonitoring(
        context: Context,
        scope: CoroutineScope,
        preferences: PureStopPreferences
    ) {
        init(context, preferences)
        registerNetworkCallback(context, scope, preferences)

        monitorJob?.cancel()
        monitorJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val monitoredPkgs = preferences.getMonitoredWakeUpPackages()
                    if (monitoredPkgs.isNotEmpty()) {
                        scanForWakeUps(context, monitoredPkgs, preferences)
                    }
                } catch (e: Exception) {
                    // Ignore transient errors
                }
                delay(12_000L) // Scan every 12 seconds
            }
        }
    }

    fun stopMonitoring(context: Context) {
        monitorJob?.cancel()
        monitorJob = null
        unregisterNetworkCallback(context)
    }

    private fun registerNetworkCallback(
        context: Context,
        scope: CoroutineScope,
        preferences: PureStopPreferences
    ) {
        if (networkCallback != null) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return

        try {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val caps = cm.getNetworkCapabilities(network)
                    val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                    lastNetworkType = if (isWifi) "Wi-Fi" else "Mobile Data"
                    lastNetworkChangeTime = System.currentTimeMillis()

                    // Many apps (e.g. TeraBox, cloud storage) wake up 1-5 seconds after network connects
                    scope.launch(Dispatchers.IO) {
                        delay(2000L)
                        val pkgs = preferences.getMonitoredWakeUpPackages()
                        if (pkgs.isNotEmpty()) {
                            scanForWakeUps(context, pkgs, preferences)
                        }
                        delay(4000L)
                        if (pkgs.isNotEmpty()) {
                            scanForWakeUps(context, pkgs, preferences)
                        }
                    }
                }

                override fun onLost(network: Network) {
                    lastNetworkChangeTime = System.currentTimeMillis()
                }
            }
            networkCallback = callback
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            // Permission or system limitation
        }
    }

    private fun unregisterNetworkCallback(context: Context) {
        networkCallback?.let {
            try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(it)
            } catch (e: Exception) {
                // Ignore
            }
            networkCallback = null
        }
    }

    suspend fun scanForWakeUps(
        context: Context,
        managedPkgs: Set<String>,
        preferences: PureStopPreferences
    ): List<DetectedWakeUpEvent> = withContext(Dispatchers.IO) {
        val detected = mutableListOf<DetectedWakeUpEvent>()
        val pm = context.packageManager
        val myPkg = context.packageName

        // 1. Scan Event logcat for am_proc_start:
        // Format: am_proc_start: [userId,pid,uid,processName,startType,component]
        val eventLogCmd = "logcat -b events -d -s am_proc_start -t 50; echo '===SYSTEM==='; logcat -b system -d -s ActivityManager -t 30 | grep -E 'Start proc.*for (content provider|service|broadcast)'; exit 0"
        val logResult = RootExecutor.executeCommand(eventLogCmd)
        val logOutput = logResult.getOrNull() ?: ""

        val isRecentNetwork = (System.currentTimeMillis() - lastNetworkChangeTime) < 30_000L

        if (logOutput.isNotEmpty()) {
            for (line in logOutput.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                // Check event log pattern
                if (trimmed.contains("am_proc_start:")) {
                    // Pattern: am_proc_start: [0,27051,10234,com.dubox.drive,content provider,com.dubox.drive/com.dubox.drive.files.provider.TeraBoxProvider]
                    val content = trimmed.substringAfter("am_proc_start:").trim()
                    if (content.startsWith("[") && content.endsWith("]")) {
                        val parts = content.removeSurrounding("[", "]").split(",")
                        if (parts.size >= 6) {
                            val pkgCandidate = parts[3].trim().substringBefore(":")
                            val startType = parts[4].trim()
                            val componentRaw = parts[5].trim()

                            processLogEntry(
                                pkgCandidate = pkgCandidate,
                                startType = startType,
                                componentRaw = componentRaw,
                                managedPkgs = managedPkgs,
                                myPkg = myPkg,
                                pm = pm,
                                preferences = preferences,
                                isRecentNetwork = isRecentNetwork,
                                detected = detected,
                                context = context
                            )
                        }
                    }
                } else if (trimmed.contains("Start proc")) {
                    // System logcat fallback:
                    // ActivityManager: Start proc 1234:com.dubox.drive/u0a156 for content provider {com.dubox.drive/com.dubox.drive.files.provider.TeraBoxProvider}
                    val match = Regex("Start proc\\s+\\d+:([a-zA-Z0-9._]+).*for\\s+([a-zA-Z0-9 _]+)\\s*\\{([^}]+)\\}").find(trimmed)
                    if (match != null) {
                        val pkgCandidate = match.groupValues[1].substringBefore(":")
                        val startType = match.groupValues[2].trim()
                        val componentRaw = match.groupValues[3].trim()

                        processLogEntry(
                            pkgCandidate = pkgCandidate,
                            startType = startType,
                            componentRaw = componentRaw,
                            managedPkgs = managedPkgs,
                            myPkg = myPkg,
                            pm = pm,
                            preferences = preferences,
                            isRecentNetwork = isRecentNetwork,
                            detected = detected,
                            context = context
                        )
                    }
                }
            }
        }

        // 2. Also check active dumpsys services and providers ONLY for monitored packages (avoids 140-iteration lag)
        val activeMonitoredPkgs = preferences.getMonitoredWakeUpPackages().filter { managedPkgs.contains(it) && it != myPkg }
        for (pkg in activeMonitoredPkgs) {
            try {
                val serviceCheckCmd = "dumpsys activity services $pkg | grep -E 'intent=\\{|ServiceRecord'; dumpsys activity providers $pkg | grep -E 'ProviderRecord\\{'; exit 0"
                val res = RootExecutor.executeCommand(serviceCheckCmd)
                val out = res.getOrNull() ?: ""
                if (out.isNotEmpty()) {
                    for (line in out.lines()) {
                        val l = line.trim()
                        if (l.contains("act=android.content.SyncAdapter") || l.contains("SyncService")) {
                            val compMatch = Regex("cmp=([a-zA-Z0-9._/]+)").find(l)
                            val comp = compMatch?.groupValues?.get(1) ?: "$pkg/.SyncService"
                            processLogEntry(
                                pkgCandidate = pkg,
                                startType = "service",
                                componentRaw = comp,
                                managedPkgs = managedPkgs,
                                myPkg = myPkg,
                                pm = pm,
                                preferences = preferences,
                                isRecentNetwork = isRecentNetwork,
                                detected = detected,
                                context = context
                            )
                        } else if (l.contains("ProviderRecord{") && (l.contains("TeraBoxProvider") || l.contains("documents") || l.contains("Drive"))) {
                            val compMatch = Regex("u0\\s+([a-zA-Z0-9._/]+)").find(l)
                            val comp = compMatch?.groupValues?.get(1) ?: "$pkg/.TeraBoxProvider"
                            processLogEntry(
                                pkgCandidate = pkg,
                                startType = "content provider",
                                componentRaw = comp,
                                managedPkgs = managedPkgs,
                                myPkg = myPkg,
                                pm = pm,
                                preferences = preferences,
                                isRecentNetwork = isRecentNetwork,
                                detected = detected,
                                context = context
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        if (detected.isNotEmpty()) {
            _detectedEvents.value = preferences.getDetectedWakeUpEvents()
        }

        detected
    }

    private fun processLogEntry(
        pkgCandidate: String,
        startType: String,
        componentRaw: String,
        managedPkgs: Set<String>,
        myPkg: String,
        pm: PackageManager,
        preferences: PureStopPreferences,
        isRecentNetwork: Boolean,
        detected: MutableList<DetectedWakeUpEvent>,
        context: Context
    ) {
        if (!managedPkgs.contains(pkgCandidate) || pkgCandidate == myPkg) return

        // Skip user-opened activities (MainActivity / launcher activity)
        if (startType.contains("activity", ignoreCase = true) || startType.contains("top-activity", ignoreCase = true)) {
            return
        }

        // Clean component name
        val cleanComp = if (componentRaw.contains("/")) {
            val afterSlash = componentRaw.substringAfter("/")
            if (afterSlash.startsWith(".")) "$pkgCandidate$afterSlash" else afterSlash
        } else {
            componentRaw
        }

        val simpleCompName = cleanComp.substringAfterLast('.')
        val pathId = when {
            startType.contains("provider", ignoreCase = true) -> "$pkgCandidate:provider:$cleanComp"
            startType.contains("service", ignoreCase = true) -> "$pkgCandidate:service:$cleanComp"
            startType.contains("broadcast", ignoreCase = true) -> "$pkgCandidate:receiver:$cleanComp"
            else -> "$pkgCandidate:comp:$cleanComp"
        }

        // If user already cut this specific path, skip
        if (preferences.isPathCut(pkgCandidate, pathId)) {
            return
        }

        // Deduplicate notifications (don't alert on same component within 5 minutes)
        val cacheKey = "$pkgCandidate:$cleanComp"
        val lastNotified = notifiedComponentCache[cacheKey] ?: 0L
        val now = System.currentTimeMillis()
        if (now - lastNotified < 5 * 60 * 1000L) {
            return
        }
        notifiedComponentCache[cacheKey] = now

        val appName = try {
            val appInfo = pm.getApplicationInfo(pkgCandidate, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            pkgCandidate
        }

        // Classify WakeUpPathType and user-friendly title
        val (pathType, pathTitle) = classifyComponent(startType, cleanComp, simpleCompName)

        val triggerContext = if (isRecentNetwork && lastNetworkType.isNotBlank()) {
            "Woke up when $lastNetworkType was connected (via $simpleCompName)"
        } else {
            "Woke up silently in background (via $startType)"
        }

        val event = DetectedWakeUpEvent(
            id = "$pkgCandidate:$cleanComp:$now",
            packageName = pkgCandidate,
            appName = appName,
            componentName = cleanComp,
            pathType = pathType,
            pathTitle = pathTitle,
            triggerContext = triggerContext,
            rawReason = startType,
            timestamp = now,
            isCut = false
        )

        preferences.saveDetectedWakeUpEvent(event)
        detected.add(event)

        // Post system notification with 1-tap "Cut This Path Only" action
        notifyWakeUpDetected(context, event)
    }

    private fun classifyComponent(
        startType: String,
        cleanComp: String,
        simpleCompName: String
    ): Pair<WakeUpPathType, String> {
        val lower = cleanComp.lowercase()

        return when {
            startType.contains("provider", ignoreCase = true) -> {
                if (lower.contains("documents") || lower.contains("drive") || lower.contains("terabox") || lower.contains("file")) {
                    Pair(WakeUpPathType.PROVIDER_DOCUMENTS, "Documents Provider ($simpleCompName)")
                } else {
                    Pair(WakeUpPathType.PROVIDER_CONTENT, "Content Provider ($simpleCompName)")
                }
            }
            startType.contains("service", ignoreCase = true) -> {
                if (lower.contains("sync")) {
                    Pair(WakeUpPathType.SERVICE_SYNC_ADAPTER, "Account SyncAdapter ($simpleCompName)")
                } else if (lower.contains("job") || lower.contains("work")) {
                    Pair(WakeUpPathType.SERVICE_JOB, "JobScheduler Service ($simpleCompName)")
                } else if (lower.contains("push") || lower.contains("fcm") || lower.contains("messaging")) {
                    Pair(WakeUpPathType.SERVICE_FOREGROUND, "Push Notification Service ($simpleCompName)")
                } else {
                    Pair(WakeUpPathType.SERVICE_BACKGROUND, "Background Service ($simpleCompName)")
                }
            }
            startType.contains("broadcast", ignoreCase = true) -> {
                if (lower.contains("boot")) {
                    Pair(WakeUpPathType.RECEIVER_BOOT, "Boot Receiver ($simpleCompName)")
                } else if (lower.contains("network") || lower.contains("connectivity") || lower.contains("wifi")) {
                    Pair(WakeUpPathType.RECEIVER_CONNECTIVITY, "Network Receiver ($simpleCompName)")
                } else if (lower.contains("alarm") || lower.contains("timer")) {
                    Pair(WakeUpPathType.OP_SCHEDULED_ALARM, "Alarm Receiver ($simpleCompName)")
                } else {
                    Pair(WakeUpPathType.RECEIVER_CUSTOM, "Broadcast Receiver ($simpleCompName)")
                }
            }
            else -> {
                Pair(WakeUpPathType.SERVICE_BACKGROUND, "Background Component ($simpleCompName)")
            }
        }
    }

    private fun notifyWakeUpDetected(context: Context, event: DetectedWakeUpEvent) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // Intent to open MainActivity and show wake-up dialog
            val contentIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("EXTRA_OPEN_WAKEUP_PKG", event.packageName)
                putExtra("EXTRA_DETECTED_COMPONENT", event.componentName)
            }
            val contentPendingIntent = PendingIntent.getActivity(
                context,
                event.packageName.hashCode(),
                contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Direct "Cut This Path Only" action button!
            val cutIntent = Intent(context, WakeUpCutReceiver::class.java).apply {
                action = WakeUpCutReceiver.ACTION_CUT_WAKEUP_PATH
                putExtra(WakeUpCutReceiver.EXTRA_PACKAGE, event.packageName)
                putExtra(WakeUpCutReceiver.EXTRA_COMPONENT, event.componentName)
                putExtra(WakeUpCutReceiver.EXTRA_EVENT_ID, event.id)
                putExtra(WakeUpCutReceiver.EXTRA_APP_NAME, event.appName)
                putExtra(WakeUpCutReceiver.EXTRA_PATH_TYPE, event.pathType.name)
            }
            val cutPendingIntent = PendingIntent.getBroadcast(
                context,
                (event.packageName + event.componentName).hashCode(),
                cutIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notificationId = NOTIFICATION_ID_BASE + (event.packageName.hashCode() and 0x7FFF)

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("⚡ ${event.appName} Started in Background")
                .setContentText("Via ${event.pathTitle} • ${event.triggerContext}")
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(
                            "Detected background wake-up vector:\n" +
                            "• Component: ${event.componentName}\n" +
                            "• Type: ${event.pathTitle}\n" +
                            "• Trigger: ${event.triggerContext}\n\n" +
                            "Tap 'Cut Path Only' to permanently disable this component so it cannot autostart."
                        )
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(contentPendingIntent)
                .setAutoCancel(true)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Cut Path Only",
                    cutPendingIntent
                )

            nm.notify(notificationId, builder.build())
        } catch (e: Exception) {
            // Notifications may be restricted
        }
    }
}
