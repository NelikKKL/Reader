package com.example.reader.ui

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import com.example.reader.data.AppSettings
import com.example.reader.data.ColorSource
import com.example.reader.data.ThemeMode

@Composable
fun isDark(settings: AppSettings): Boolean = when (settings.themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
}

@Composable
fun ReaderTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = isDark(settings)
    val context = LocalContext.current
    val seed = rememberWallpaperSeed()

    var scheme = when {
        settings.colorSource == ColorSource.SYSTEM && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        settings.colorSource != ColorSource.DEFAULT && seed != null -> seedScheme(seed, dark)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    if (dark && settings.amoled) {
        scheme = scheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainerLowest = Color.Black,
            surfaceContainerLow = Color(0xFF0B0B0B),
            surfaceContainer = Color(0xFF101010),
            surfaceContainerHigh = Color(0xFF171717),
            surfaceContainerHighest = Color(0xFF1E1E1E)
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

// ------------------------------------------------------------------ wallpaper colors (Android 8.1+)

private fun readSeed(context: Context): Int? = try {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        WallpaperManager.getInstance(context)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()
    } else null
} catch (e: Exception) {
    null
}

/** Primary wallpaper color; updates live when the wallpaper changes. */
@Composable
private fun rememberWallpaperSeed(): Int? {
    val context = LocalContext.current
    var seed by remember { mutableStateOf(readSeed(context)) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        DisposableEffect(Unit) {
            val wm = WallpaperManager.getInstance(context)
            val listener = WallpaperManager.OnColorsChangedListener { _, _ -> seed = readSeed(context) }
            wm.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
            onDispose { wm.removeOnColorsChangedListener(listener) }
        }
    }
    return seed
}

private fun tone(h: Float, s: Float, l: Float): Color = Color(
    ColorUtils.HSLToColor(floatArrayOf(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f)))
)

/** Builds a Material 3 scheme from one seed color (used where system dynamic color is unavailable). */
private fun seedScheme(seed: Int, dark: Boolean): ColorScheme {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(seed, hsl)
    val h = hsl[0]
    val s = hsl[1].coerceIn(0.35f, 0.8f)
    val t = h + 60f
    val sec = s * 0.35f
    val ter = s * 0.5f
    return if (dark) darkColorScheme(
        primary = tone(h, s, 0.78f), onPrimary = tone(h, s, 0.16f),
        primaryContainer = tone(h, s, 0.30f), onPrimaryContainer = tone(h, s, 0.90f),
        secondary = tone(h, sec, 0.78f), onSecondary = tone(h, sec, 0.18f),
        secondaryContainer = tone(h, sec, 0.28f), onSecondaryContainer = tone(h, sec, 0.90f),
        tertiary = tone(t, ter, 0.78f), onTertiary = tone(t, ter, 0.18f),
        tertiaryContainer = tone(t, ter, 0.28f), onTertiaryContainer = tone(t, ter, 0.90f),
        background = tone(h, 0.10f, 0.08f), onBackground = tone(h, 0.08f, 0.90f),
        surface = tone(h, 0.10f, 0.08f), onSurface = tone(h, 0.08f, 0.90f),
        surfaceVariant = tone(h, 0.12f, 0.24f), onSurfaceVariant = tone(h, 0.10f, 0.78f),
        outline = tone(h, 0.08f, 0.56f),
        surfaceContainerLowest = tone(h, 0.10f, 0.05f), surfaceContainerLow = tone(h, 0.10f, 0.10f),
        surfaceContainer = tone(h, 0.10f, 0.12f), surfaceContainerHigh = tone(h, 0.10f, 0.16f),
        surfaceContainerHighest = tone(h, 0.10f, 0.20f)
    ) else lightColorScheme(
        primary = tone(h, s, 0.40f), onPrimary = tone(h, 0f, 1f),
        primaryContainer = tone(h, s, 0.90f), onPrimaryContainer = tone(h, s, 0.15f),
        secondary = tone(h, sec, 0.40f), onSecondary = tone(h, 0f, 1f),
        secondaryContainer = tone(h, sec, 0.90f), onSecondaryContainer = tone(h, sec, 0.15f),
        tertiary = tone(t, ter, 0.40f), onTertiary = tone(t, 0f, 1f),
        tertiaryContainer = tone(t, ter, 0.90f), onTertiaryContainer = tone(t, ter, 0.15f),
        background = tone(h, 0.20f, 0.98f), onBackground = tone(h, 0.08f, 0.10f),
        surface = tone(h, 0.20f, 0.98f), onSurface = tone(h, 0.08f, 0.10f),
        surfaceVariant = tone(h, 0.15f, 0.90f), onSurfaceVariant = tone(h, 0.10f, 0.30f),
        outline = tone(h, 0.08f, 0.50f),
        surfaceContainerLowest = tone(h, 0f, 1f), surfaceContainerLow = tone(h, 0.20f, 0.96f),
        surfaceContainer = tone(h, 0.20f, 0.94f), surfaceContainerHigh = tone(h, 0.18f, 0.92f),
        surfaceContainerHighest = tone(h, 0.16f, 0.90f)
    )
}
