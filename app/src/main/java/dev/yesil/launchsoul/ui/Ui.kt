package dev.yesil.launchsoul.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import dev.yesil.launchsoul.app

private val colors = darkColorScheme(
    primary = Color(0xFF8FB8FF),
    onPrimary = Color(0xFF0B1B33),
    background = Color(0xFF101216),
    surface = Color(0xFF101216),
    surfaceContainer = Color(0xFF1C1F25),
    surfaceContainerHigh = Color(0xFF262A32),
    surfaceVariant = Color(0xFF262A32),
    onBackground = Color(0xFFF1F3F7),
    onSurface = Color(0xFFF1F3F7),
    onSurfaceVariant = Color(0xFFA9B0BD),
)

@Composable
fun SoulTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = colors, content = content)

/** The shaped icon of an app; fills in when it has been loaded. */
@Composable
fun AppIcon(key: String, size: Dp) {
    val app = LocalContext.current.app
    var bmp by remember(key) { mutableStateOf<Bitmap?>(app.icons.get(key)) }
    DisposableEffect(key) {
        val l: (String) -> Unit = { if (it == key) bmp = app.icons.get(key) }
        app.icons.listeners += l
        if (bmp == null) bmp = app.icons.get(key)
        onDispose { app.icons.listeners -= l }
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, Modifier.size(size))
    else Box(Modifier.size(size).clip(RoundedCornerShape(size * 0.26f)).background(Color(0x22FFFFFF)))
}
