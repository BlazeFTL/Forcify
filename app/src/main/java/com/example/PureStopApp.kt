package com.example

import android.app.Application
import com.example.data.AppDatabase
import com.example.data.PureStopPreferences

class PureStopApp : Application() {
    lateinit var database: AppDatabase
        private set

    lateinit var preferences: PureStopPreferences
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = AppDatabase.getDatabase(this)
        preferences = PureStopPreferences(this)
        com.example.service.ForCifyDaemonService.start(this)
    }

    companion object {
        lateinit var instance: PureStopApp
            private set
    }
}
