package com.example.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    @Query("SELECT * FROM hibernated_apps ORDER BY appName ASC")
    fun getAllManagedApps(): Flow<List<HibernatedAppEntity>>

    @Query("SELECT * FROM hibernated_apps WHERE packageName = :packageName LIMIT 1")
    suspend fun getApp(packageName: String): HibernatedAppEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApp(app: HibernatedAppEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertApps(apps: List<HibernatedAppEntity>)

    @Query("DELETE FROM hibernated_apps WHERE packageName = :packageName")
    suspend fun deleteApp(packageName: String)

    @Update
    suspend fun updateApp(app: HibernatedAppEntity)

    @Query("UPDATE hibernated_apps SET lastFrozenTimestamp = :timestamp, freezeCount = freezeCount + 1 WHERE packageName = :packageName")
    suspend fun recordFreeze(packageName: String, timestamp: Long)

    @Query("UPDATE hibernated_apps SET cutWakeups = :cut WHERE packageName = :packageName")
    suspend fun setCutWakeups(packageName: String, cut: Boolean)
}
