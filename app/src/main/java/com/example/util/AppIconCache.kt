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
                } else {
                    diskFile.delete()
                }
            } catch (e: Exception) {
                diskFile.delete()
            }
        }

        return try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)
            val launchComponent = launchIntent?.component

            val appInfo = try {
                pm.getApplicationInfo(packageName, 0)
            } catch (e: Exception) {
                null
            }

            val drawable = (launchComponent?.let {
                try { pm.getActivityIcon(it) } catch (e: Exception) { null }
            }) ?: appInfo?.loadIcon(pm)
                ?: try {
                    pm.getApplicationIcon(packageName)
                } catch (e: Exception) {
                    null
                }
                ?: appInfo?.loadUnbadgedIcon(pm)

            if (drawable != null) {
                memoryCache[packageName] = drawable
                saveIconToDiskAsync(context, packageName, drawable)
            }
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
                } else {
                    diskFile.delete()
                }
            } catch (e: Exception) {
                diskFile.delete()
            }
        }

        return try {
            val pm = context.packageManager
            val launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName)
            val launchComponent = launchIntent?.component

            val drawable = (launchComponent?.let {
                try { pm.getActivityIcon(it) } catch (e: Exception) { null }
            }) ?: try {
                appInfo.loadIcon(pm)
            } catch (e: Exception) {
                try {
                    pm.getApplicationIcon(appInfo)
                } catch (e2: Exception) {
                    pm.getApplicationIcon(appInfo.packageName)
                }
            }

            if (drawable != null) {
                memoryCache[appInfo.packageName] = drawable
                saveIconToDiskAsync(context, appInfo.packageName, drawable)
            }
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
                    } else {
                        file.delete()
                    }
                } catch (e: Exception) {
                    file.delete()
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
                val iconDir = File(context.filesDir, "app_icons")
                if (!iconDir.exists()) iconDir.mkdirs()
                val targetFile = File(iconDir, "$packageName.png")
                if (targetFile.exists() && targetFile.length() > 0) return@launch

                val tempFile = File(iconDir, "$packageName.png.tmp")
                val bitmap = drawableToBitmap(drawable)
                FileOutputStream(tempFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 95, out)
                    out.flush()
                }
                tempFile.renameTo(targetFile)
            } catch (e: Exception) {
                // Ignore background caching failures
            }
        }
    }

    fun drawableToBitmap(drawable: Drawable, targetSize: Int = 144): Bitmap {
        val d = try {
            drawable.constantState?.newDrawable()?.mutate() ?: drawable
        } catch (e: Exception) {
            drawable
        }

        if (d is BitmapDrawable && d.bitmap != null && !d.bitmap.isRecycled) {
            try {
                return d.bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: d.bitmap
            } catch (e: Exception) {
                // fall through to drawing on canvas
            }
        }

        val w = if (d.intrinsicWidth > 0) d.intrinsicWidth else targetSize
        val h = if (d.intrinsicHeight > 0) d.intrinsicHeight else targetSize
        val size = maxOf(72, minOf(maxOf(w, h), 256))
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        d.setBounds(0, 0, size, size)
        d.draw(canvas)
        return bitmap
    }
}
