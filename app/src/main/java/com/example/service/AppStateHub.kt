package com.example.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-process event bus shared by ForCifyDaemonService and the UI.
 * Package events (force stop, install, remove) arrive from system broadcasts and
 * let the UI update immediately instead of waiting for the next poll.
 */
object AppStateHub {
    private val _packageEvents = MutableSharedFlow<String>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val packageEvents: SharedFlow<String> = _packageEvents.asSharedFlow()

    private val _screenOn = MutableStateFlow(true)
    val screenOn: StateFlow<Boolean> = _screenOn.asStateFlow()

    @Volatile
    var uiVisible: Boolean = false

    fun emitPackage(packageName: String) {
        _packageEvents.tryEmit(packageName)
    }

    fun setScreenOn(on: Boolean) {
        _screenOn.value = on
    }
}
