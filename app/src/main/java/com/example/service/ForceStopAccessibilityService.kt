package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class ForceStopAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var isAutomating = false
    private var currentTargetPackage: String? = null
    private var onCompleteCallback: (() -> Unit)? = null
    private var stepTimeoutJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.notificationTimeout = 80
        serviceInfo = info
        _serviceStateFlow.tryEmit(true)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        _serviceStateFlow.tryEmit(false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isAutomating || event == null) return

        val rootNode = rootInActiveWindow ?: return
        val packageName = event.packageName?.toString() ?: ""

        // Process Settings or Dialog windows
        if (packageName.contains("settings") || packageName.contains("packageinstaller") || packageName == "android") {
            handleSettingsWindow(rootNode)
        }
    }

    private fun handleSettingsWindow(root: AccessibilityNodeInfo) {
        // Step 1: Look for confirmation dialog "OK" or "Force stop" button
        val confirmButton = findConfirmationButton(root)
        if (confirmButton != null && confirmButton.isEnabled) {
            confirmButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            // Successfully clicked confirm dialog! Finish this app and navigate back
            stepTimeoutJob?.cancel()
            serviceScope.launch {
                delay(200)
                performGlobalAction(GLOBAL_ACTION_BACK)
                delay(250)
                finishCurrentTarget()
            }
            return
        }

        // Step 2: Look for main "Force stop" button on App Info page
        val forceStopButton = findForceStopButton(root)
        if (forceStopButton != null) {
            if (forceStopButton.isEnabled) {
                forceStopButton.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                // Wait briefly for confirmation dialog to appear
            } else {
                // Button is disabled, meaning the app is already force-stopped or not running!
                stepTimeoutJob?.cancel()
                serviceScope.launch {
                    delay(150)
                    performGlobalAction(GLOBAL_ACTION_BACK)
                    delay(200)
                    finishCurrentTarget()
                }
            }
        }
    }

    private fun findForceStopButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val targets = listOf("force stop", "force close", "stop", "forced stop", "arrêter", "forzar detención")
        return findNodeByTexts(root, targets)
    }

    private fun findConfirmationButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        // Look for Dialog positive button: "OK", "Force stop", "Yes"
        val targets = listOf("ok", "force stop", "force close", "oui", "aceptar", "terminer")
        val node = findNodeByTexts(root, targets)
        // Make sure it's inside an alert or dialog
        return node
    }

    private fun findNodeByTexts(root: AccessibilityNodeInfo, targets: List<String>): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = node.text?.toString()?.trim()?.lowercase() ?: ""
            val desc = node.contentDescription?.toString()?.trim()?.lowercase() ?: ""
            val viewId = node.viewIdResourceName?.lowercase() ?: ""

            for (target in targets) {
                if (text == target || desc == target || (text.contains(target) && text.length < 25)) {
                    if (node.isClickable) return node
                    val clickableParent = findClickableParent(node)
                    if (clickableParent != null) return clickableParent
                }
            }

            if (viewId.contains("force_stop") || viewId.contains("button2") || viewId.contains("button1")) {
                if (node.isClickable) return node
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    private fun findClickableParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var parent = node.parent
        while (parent != null) {
            if (parent.isClickable) return parent
            parent = parent.parent
        }
        return null
    }

    private fun finishCurrentTarget() {
        val pkg = currentTargetPackage
        currentTargetPackage = null
        isAutomating = false
        if (pkg != null) {
            _stoppedPackageFlow.tryEmit(pkg)
        }
        onCompleteCallback?.invoke()
        onCompleteCallback = null
    }

    fun automateForceStop(packageName: String, onDone: () -> Unit) {
        currentTargetPackage = packageName
        isAutomating = true
        onCompleteCallback = onDone

        // Launch app settings
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(intent)

        // Set safety timeout in case window doesn't appear or button isn't found
        stepTimeoutJob?.cancel()
        stepTimeoutJob = serviceScope.launch {
            delay(3500)
            if (isAutomating && currentTargetPackage == packageName) {
                performGlobalAction(GLOBAL_ACTION_BACK)
                finishCurrentTarget()
            }
        }
    }

    override fun onInterrupt() {
        isAutomating = false
        currentTargetPackage = null
        stepTimeoutJob?.cancel()
    }

    companion object {
        var instance: ForceStopAccessibilityService? = null
            private set

        private val _serviceStateFlow = MutableSharedFlow<Boolean>(replay = 1)
        val serviceStateFlow: SharedFlow<Boolean> = _serviceStateFlow.asSharedFlow()

        private val _stoppedPackageFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
        val stoppedPackageFlow: SharedFlow<String> = _stoppedPackageFlow.asSharedFlow()

        fun isRunning(): Boolean = instance != null

        fun openAccessibilitySettings(context: Context) {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                // Fallback
            }
        }
    }
}
