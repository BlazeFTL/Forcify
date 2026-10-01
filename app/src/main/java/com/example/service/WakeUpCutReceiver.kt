package com.example.service

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.example.data.OperatingMode
import com.example.data.PureStopPreferences
import com.example.engine.RootExecutor
import com.example.model.WakeUpPathType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class WakeUpCutReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_CUT_WAKEUP_PATH) return

        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return
        val component = intent.getStringExtra(EXTRA_COMPONENT) ?: return
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID) ?: ""
        val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: pkg
        val pathTypeStr = intent.getStringExtra(EXTRA_PATH_TYPE) ?: WakeUpPathType.SERVICE_BACKGROUND.name

        val preferences = PureStopPreferences(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val notificationId = 5000 + (pkg.hashCode() and 0x7FFF)
        nm?.cancel(notificationId)

        CoroutineScope(Dispatchers.IO).launch {
            val isRoot = preferences.mode == OperatingMode.ROOT

            val simpleComp = component.substringAfterLast('.')
            val formattedComp = if (component.contains("/")) component else "$pkg/$component"

            val pathType = try {
                WakeUpPathType.valueOf(pathTypeStr)
            } catch (e: Exception) {
                WakeUpPathType.SERVICE_BACKGROUND
            }

            val pathId = when (pathType) {
                WakeUpPathType.PROVIDER_DOCUMENTS, WakeUpPathType.PROVIDER_CONTENT -> "$pkg:provider:$component"
                WakeUpPathType.SERVICE_SYNC_ADAPTER, WakeUpPathType.SERVICE_BACKGROUND, WakeUpPathType.SERVICE_FOREGROUND, WakeUpPathType.SERVICE_JOB -> "$pkg:service:$component"
                WakeUpPathType.RECEIVER_BOOT, WakeUpPathType.RECEIVER_CONNECTIVITY, WakeUpPathType.RECEIVER_POWER, WakeUpPathType.RECEIVER_CUSTOM -> "$pkg:receiver:$component"
                else -> "$pkg:comp:$component"
            }

            // Save cut state in preferences
            preferences.togglePathCut(pkg, pathId, true)
            preferences.markDetectedWakeUpEventCut(eventId)

            if (isRoot) {
                // Permanently disable component via pm disable & force stop app
                RootExecutor.executeCommand("pm disable $formattedComp")
                RootExecutor.executeCommand("am force-stop $pkg")
            } else {
                RootExecutor.executeCommand("am force-stop $pkg")
            }

            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    context,
                    "✓ Cut $simpleComp and force stopped $appName",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    companion object {
        const val ACTION_CUT_WAKEUP_PATH = "com.example.action.CUT_WAKEUP_PATH"
        const val EXTRA_PACKAGE = "extra_package"
        const val EXTRA_COMPONENT = "extra_component"
        const val EXTRA_EVENT_ID = "extra_event_id"
        const val EXTRA_APP_NAME = "extra_app_name"
        const val EXTRA_PATH_TYPE = "extra_path_type"
    }
}
