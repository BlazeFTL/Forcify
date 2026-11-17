package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers

@Composable
fun AppIconImage(
    drawable: Drawable?,
    appName: String,
    size: Dp = 44.dp,
    packageName: String? = null,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var asyncBitmap by remember(drawable, packageName) {
        val preCachedBitmap = packageName?.let { com.example.util.AppIconCache.getBitmap(it) }
        mutableStateOf(
            preCachedBitmap ?: if (drawable is android.graphics.drawable.BitmapDrawable && drawable.bitmap != null && !drawable.bitmap.isRecycled) {
                drawable.bitmap
            } else {
                null
            }
        )
    }

    androidx.compose.runtime.LaunchedEffect(drawable, packageName) {
        if (asyncBitmap == null) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                val activeDrawable = drawable ?: packageName?.let { com.example.util.AppIconCache.getOrLoad(context, it) }
                val bmp = if (packageName != null) {
                    com.example.util.AppIconCache.getBitmap(packageName)
                        ?: activeDrawable?.let { com.example.util.AppIconCache.drawableToBitmap(it) }
                } else {
                    activeDrawable?.let { com.example.util.AppIconCache.drawableToBitmap(it) }
                }
                if (bmp != null) {
                    asyncBitmap = bmp
                }
            }
        }
    }

    val bitmap = asyncBitmap

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "$appName icon",
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            val letter = appName.firstOrNull()?.uppercaseChar()?.toString() ?: ""
            if (letter.isNotBlank()) {
                Text(
                    text = letter,
                    fontWeight = FontWeight.Bold,
                    fontSize = (size.value * 0.4f).sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Apps,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(size * 0.6f)
                )
            }
        }
    }
}
