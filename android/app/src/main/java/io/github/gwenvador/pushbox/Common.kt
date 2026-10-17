package io.github.gwenvador.pushbox

import android.graphics.Bitmap
import android.text.format.DateUtils
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Api
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun kindIcon(kind: String): ImageVector = when (kind) {
    "phone" -> Icons.Rounded.Smartphone
    "desktop" -> Icons.Rounded.Computer
    "api" -> Icons.Rounded.Api
    else -> Icons.Rounded.Language
}

@Composable
fun DeviceAvatar(kind: String, size: Dp = 36.dp, highlighted: Boolean = false) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            kindIcon(kind), null, Modifier.size(size * 0.55f),
            tint = if (highlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

fun relativeTime(ts: Long): String =
    if (System.currentTimeMillis() - ts < 60_000) "now"
    else DateUtils.getRelativeTimeSpanString(ts, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()

private val thumbCache = LruCache<String, Bitmap>(60)

/** An image push, fetched with the device token and cached in memory. */
@Composable
fun PushImage(id: String, fileUrl: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val bmp by produceState(thumbCache.get(id), id) {
        if (value == null) value = withContext(Dispatchers.IO) { Files.fetchImage(ctx, fileUrl, 900) }?.also { thumbCache.put(id, it) }
    }
    bmp?.let { Image(it.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop) }
}
