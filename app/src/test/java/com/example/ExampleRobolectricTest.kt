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
        assertEquals("PureStop", appName)
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
}
