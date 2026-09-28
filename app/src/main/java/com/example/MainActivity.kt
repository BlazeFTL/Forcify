package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.SetupScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.PureStopViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: PureStopViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.example.service.ForCifyDaemonService.start(this)
        setContent {
            MyApplicationTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val isSetupCompleted by viewModel.isSetupCompleted.collectAsState()

                    Crossfade(targetState = isSetupCompleted, label = "screen_transition") { completed ->
                        if (completed) {
                            DashboardScreen(viewModel = viewModel)
                        } else {
                            SetupScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkPermissions()
        viewModel.refreshManagedAppsOnly(silent = true)
        viewModel.startLiveMonitoring()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopLiveMonitoring()
    }
}
