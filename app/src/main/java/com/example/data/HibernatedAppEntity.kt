package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "hibernated_apps")
data class HibernatedAppEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val isAutoFreeze: Boolean = true,
    val cutWakeups: Boolean = true,
    val addedTimestamp: Long = System.currentTimeMillis(),
    val lastFrozenTimestamp: Long = 0L,
    val freezeCount: Int = 0,
    val isSystemApp: Boolean = false
)
