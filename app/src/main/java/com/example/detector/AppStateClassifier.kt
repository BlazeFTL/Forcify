package com.example.detector

import android.app.ActivityManager
import android.app.usage.UsageEvents
import com.example.engine.RootProcessState
import com.example.model.AppState

/**
 * Single source of truth for app state. Used by the UI and by the background daemon,
 * so both always agree and the daemon can persist states the UI shows on first frame.
 */
object AppStateClassifier {

    data class Input(
        val rootAuthoritative: Boolean,
        val rootState: RootProcessState?,
        val runningProc: ActivityManager.RunningAppProcessInfo?,
        val nonRoot: NonRootProcessActivity?,
        val isOsFlagStopped: Boolean,
        val isAccessibilityActive: Boolean,
        val isWorkingIgnored: Boolean,
        val isRestrictedForeground: Boolean,
        val manuallyStoppedRecently: Boolean,
        val nowMs: Long = System.currentTimeMillis()
    )

    data class Result(
        val state: AppState,
        val detail: String,
        val secondary: String,
        val isStopped: Boolean,
        val isTop: Boolean,
        val isForegroundService: Boolean,
        val isInRecents: Boolean,
        val isAlive: Boolean
    ) {
        /** True when Greenify-style screen-off hibernation must skip this app. */
        fun isProtected(ignoreWorking: Boolean): Boolean =
            !isStopped && (
                isTop ||
                    isForegroundService ||
                    detail.contains("Accessibility") ||
                    (isInRecents && !ignoreWorking)
                )
    }

    fun classify(i: Input): Result {
        val rs = i.rootState
        val proc = i.runningProc
        val nr = i.nonRoot

        val nrAlive = !i.rootAuthoritative && nr != null && (i.nowMs - nr.lastEventTimestamp) < 180_000L
        val alive = rs?.isRunning == true || proc != null || nrAlive

        val isTop = rs?.isTop == true ||
            proc?.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
            (!i.rootAuthoritative && nr?.lastEventType == UsageEvents.Event.ACTIVITY_RESUMED)

        val isFgs = rs?.isForegroundService == true ||
            proc?.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE ||
            proc?.importance == 125 ||
            (!i.rootAuthoritative && nr?.hasActiveForegroundService == true)

        val hasActiveService = rs?.hasActiveService == true ||
            (proc != null &&
                proc.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE &&
                proc.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE)

        // A recents entry of an app that the OS reports stopped and has no process is stale.
        val osStoppedEffective = i.isOsFlagStopped && !alive
        val rawRecents = rs?.isInRecents == true || (!i.rootAuthoritative && nr?.isInRecents == true)
        val inRecents = rawRecents && !osStoppedEffective

        val acc = i.isAccessibilityActive
        val stopped = !isFgs && !acc && (osStoppedEffective || (i.manuallyStoppedRecently && !isTop))

        val sub = mutableListOf<String>()
        fun result(state: AppState, detail: String, secondary: String) =
            Result(state, detail, secondary, state == AppState.BACKGROUND_FREE, isTop, isFgs, inRecents, alive)

        if (stopped) return result(AppState.BACKGROUND_FREE, "Hibernated", "")

        if (i.isWorkingIgnored) sub.add("Ignored running state")
        if (i.isRestrictedForeground) sub.add("Restricted running as foreground")

        return when {
            acc -> result(AppState.WORKING_STATE, "Being used by Accessibility", sub.joinToString("\n"))
            isTop -> result(AppState.FOREGROUND, "Foreground", sub.joinToString("\n"))
            isFgs -> result(AppState.EVADING_RESTRICTIONS, "Running as foreground (evading restrictions)", sub.joinToString("\n"))
            inRecents && !i.isWorkingIgnored -> {
                val s = mutableListOf("In recent tasks (protected)")
                if (i.isRestrictedForeground) s.add("Restricted running as foreground")
                result(AppState.WORKING_STATE, "Working State", s.joinToString("\n"))
            }
            inRecents -> {
                val s = mutableListOf("In recent tasks")
                s.addAll(sub)
                result(AppState.BACKGROUND_RUNNING, "Running in background", s.joinToString("\n"))
            }
            hasActiveService -> result(AppState.BACKGROUND_RUNNING, "Running in background", sub.joinToString("\n"))
            else -> result(AppState.CACHED, "Pending Hibernation", sub.joinToString("\n"))
        }
    }
}
