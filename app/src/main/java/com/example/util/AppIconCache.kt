package com.example.util

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

object AppIconCache {
    private val memoryCache = ConcurrentHashMap<String, Drawable>()
    private val ioScope = CoroutineScope(Dispatchers.IO)

    fun get(packageName: String): Drawable? = memoryCache[packageName]

    fun put(packageName: String, drawable: Drawable) {
        memoryCache[packageName] = drawable
    }

    /**
     * Fast retrieval:
     * 1. Check memory cache (0.001ms)
     * 2. Check local disk PNG cache (0.2ms)
     * 3. Fallback to PackageManager Binder IPC and cache to disk
     */
    fun getOrLoad(context: Context, packageName: String): Drawable? {
        val inMem = memoryCache[packageName]
        if (inMem != null) return inMem

        val diskFile = getDiskCacheFile(context, packageName)
        if (diskFile.exists() && diskFile.length() > 0) {
            try {
                val bitmap = BitmapFactory.decodeFile(diskFile.absolutePath)
                if (bitmap != null) {
                    val drawable = BitmapDrawable(context.resources, bitmap)
                    memoryCache[packageName] = drawable
                    return drawable
                }
            } catch (e: Exception) {
                // If disk file is corrupted, fall through to PackageManager
            }
        }

        return try {
            val pm = context.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val drawable = pm.getApplicationIcon(appInfo)
            memoryCache[packageName] = drawable
            saveIconToDiskAsync(context, packageName, drawable)
            drawable
        } catch (e: Exception) {
            null
        }
    }

    fun getOrLoad(context: Context, appInfo: ApplicationInfo): Drawable? {
        val inMem = memoryCache[appInfo.packageName]
        if (inMem != null) return inMem

        val diskFile = getDiskCacheFile(context, appInfo.packageName)
        if (diskFile.exists() && diskFile.length() > 0) {
            try {
                val bitmap = BitmapFactory.decodeFile(diskFile.absolutePath)
                if (bitmap != null) {
                    val drawable = BitmapDrawable(context.resources, bitmap)
                    memoryCache[appInfo.packageName] = drawable
                    return drawable
                }
            } catch (e: Exception) {
                // Fallback
            }
        }

        return try {
            val pm = context.packageManager
            val drawable = pm.getApplicationIcon(appInfo)
            memoryCache[appInfo.packageName] = drawable
            saveIconToDiskAsync(context, appInfo.packageName, drawable)
            drawable
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Preloads icons from local disk files into memory synchronously in < 15ms
     */
    fun preloadFromDisk(context: Context, packageNames: Collection<String>) {
        val resources = context.resources
        val iconDir = File(context.filesDir, "app_icons")
        if (!iconDir.exists()) return

        for (pkg in packageNames) {
            if (memoryCache.containsKey(pkg)) continue
            val file = File(iconDir, "$pkg.png")
            if (file.exists() && file.length() > 0) {
                try {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        memoryCache[pkg] = BitmapDrawable(resources, bitmap)
                    }
                } catch (e: Exception) {
                    // Ignore corrupted cache entry
                }
            }
        }
    }

    private fun getDiskCacheFile(context: Context, packageName: String): File {
        val iconDir = File(context.filesDir, "app_icons")
        if (!iconDir.exists()) {
            iconDir.mkdirs()
        }
        return File(iconDir, "$packageName.png")
    }

    private fun saveIconToDiskAsync(context: Context, packageName: String, drawable: Drawable) {
        ioScope.launch {
            try {
                val file = getDiskCacheFile(context, packageName)
                if (file.exists() && file.length() > 0) return@launch

                val bitmap = drawableToBitmap(drawable)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
                    out.flush()
                }
            } catch (e: Exception) {
                // Ignore background caching failures
            }
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth.coerceAtMost(144) else 96
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight.coerceAtMost(144) else 96
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
