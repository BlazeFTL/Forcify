package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.OperatingMode
import com.example.data.PureStopPreferences
import com.example.model.AppState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("ForCify", appName)
    }

    @Test
    fun `preferences stores operating mode correctly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PureStopPreferences(context)
        prefs.mode = OperatingMode.ROOT
        assertEquals(OperatingMode.ROOT, prefs.mode)

        prefs.mode = OperatingMode.NON_ROOT
        assertEquals(OperatingMode.NON_ROOT, prefs.mode)
    }

    @Test
    fun `app states have correct labels`() {
        assertEquals("Foreground", AppState.FOREGROUND.label)
        assertEquals("Working State", AppState.WORKING_STATE.label)
        assertEquals("Evading Restrictions", AppState.EVADING_RESTRICTIONS.label)
        assertEquals("Background Free", AppState.BACKGROUND_FREE.label)
    }

    @Test
    fun `classifyManagedAppState matches Greenify rules for evading, accessibility, recents, restricted, and hibernated`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = com.example.detector.AppStatusDetector(context)

        // 1. Foreground service unrestricted -> Evading restrictions (1DM+, LogFox)
        val fgsUnrestricted = detector.classifyManagedAppState(
            rootState = com.example.engine.RootProcessState(isRunning = true, isForegroundService = true, pid = 1001),
            runningProc = null,
            nonRootActivity = null,
            isRootMode = true,
            isOsFlagStopped = false,
            isManuallyStopped = false,
            isAccessibilityActive = false,
            isWorkingIgnored = false,
            isRestrictedForeground = false
        )
        assertEquals(AppState.EVADING_RESTRICTIONS, fgsUnrestricted.first)
        assertEquals("Running as foreground (evading restrictions)", fgsUnrestricted.second)

        // 2. Foreground service restricted -> Will hibernate soon after screen goes off (AyuGram)
        val fgsRestricted = detector.classifyManagedAppState(
            rootState = com.example.engine.RootProcessState(isRunning = true, isForegroundService = true, pid = 1002),
            runningProc = null,
            nonRootActivity = null,
            isRootMode = true,
            isOsFlagStopped = false,
            isManuallyStopped = false,
            isAccessibilityActive = false,
            isWorkingIgnored = false,
            isRestrictedForeground = true
        )
        assertEquals(AppState.BACKGROUND_RUNNING, fgsRestricted.first)
        assertEquals("Restricted running as foreground", fgsRestricted.third)

        // 3. Accessibility active -> Working state (Dev Tools Pro)
        val accessibility = detector.classifyManagedAppState(
            rootState = com.example.engine.RootProcessState(isRunning = true, pid = 1003),
            runningProc = null,
            nonRootActivity = null,
            isRootMode = true,
            isOsFlagStopped = false,
            isManuallyStopped = false,
            isAccessibilityActive = true,
            isWorkingIgnored = false,
            isRestrictedForeground = false
        )
        assertEquals(AppState.WORKING_STATE, accessibility.first)
        assertEquals("Being used by Accessibility", accessibility.second)

        // 4. Running and in recent tasks -> Protected Working State (Claude)
        val claudeInRecents = detector.classifyManagedAppState(
            rootState = com.example.engine.RootProcessState(isRunning = true, isInRecents = true, pid = 1004),
            runningProc = null,
            nonRootActivity = null,
            isRootMode = true,
            isOsFlagStopped = false,
            isManuallyStopped = false,
            isAccessibilityActive = false,
            isWorkingIgnored = false,
            isRestrictedForeground = false
        )
        assertEquals(AppState.WORKING_STATE, claudeInRecents.first)
        assertEquals("In recent tasks (protected)", claudeInRecents.second)

        // 5. Force-stopped in Settings -> Immediately Hibernated (even if dead stub exists elsewhere)
        val forceStopped = detector.classifyManagedAppState(
            rootState = null,
            runningProc = null,
            nonRootActivity = null,
            isRootMode = true,
            isOsFlagStopped = true,
            isManuallyStopped = false,
            isAccessibilityActive = false,
            isWorkingIgnored = false,
            isRestrictedForeground = false
        )
        assertEquals(AppState.BACKGROUND_FREE, forceStopped.first)
        assertEquals("Hibernated", forceStopped.second)
    }
}
