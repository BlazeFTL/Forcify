package com.example.util

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import java.util.concurrent.ConcurrentHashMap

object AppIconCache {
    private val iconCache = ConcurrentHashMap<String, Drawable>()

    fun get(packageName: String): Drawable? = iconCache[packageName]

    fun put(packageName: String, drawable: Drawable) {
        iconCache[packageName] = drawable
    }

    fun getOrLoad(pm: PackageManager, appInfo: ApplicationInfo): Drawable? {
        val cached = iconCache[appInfo.packageName]
        if (cached != null) return cached
        return try {
            val drawable = pm.getApplicationIcon(appInfo)
            iconCache[appInfo.packageName] = drawable
            drawable
        } catch (e: Exception) {
            null
        }
    }

    fun getOrLoad(pm: PackageManager, packageName: String): Drawable? {
        val cached = iconCache[packageName]
        if (cached != null) return cached
        return try {
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val drawable = pm.getApplicationIcon(appInfo)
            iconCache[packageName] = drawable
            drawable
        } catch (e: Exception) {
            null
        }
    }
}
