package com.ftpflix.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val FtpDarkScheme = darkColorScheme(
    background = FtpColors.Background,
    surface = FtpColors.Surface,
    surfaceContainer = FtpColors.SurfaceElevated,
    surfaceContainerHigh = FtpColors.SurfaceElevated,
    surfaceContainerHighest = FtpColors.SurfaceHighest,
    outline = FtpColors.Outline,
    outlineVariant = FtpColors.Outline,
    onBackground = FtpColors.OnSurface,
    onSurface = FtpColors.OnSurface,
    onSurfaceVariant = FtpColors.OnSurfaceVariant,
    primary = FtpColors.Primary,
    onPrimary = FtpColors.OnPrimary,
    primaryContainer = FtpColors.PrimaryContainer,
    onPrimaryContainer = FtpColors.Primary,
    secondary = FtpColors.Highlight,
    onSecondary = FtpColors.OnPrimary,
    error = FtpColors.Error,
    onError = FtpColors.OnPrimary,
)

/** Android 12+ follows the wallpaper via [dynamicDarkColorScheme]; the old artwork-accent lerp was dropped because it overrode the user's palette. */
@Composable
fun FtpFlixTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current

    // Dynamic color extraction decodes the wallpaper bitmap on the calling thread.
    // Running it off the main thread keeps the first frame fast (it was causing
    // multi-second main-thread stalls on cold start); the dark palette is shown
    // immediately and replaced when the dynamic scheme is ready.
    var scheme by remember { mutableStateOf(FtpDarkScheme) }
    LaunchedEffect(context) {
        scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            withContext(Dispatchers.Default) { dynamicDarkColorScheme(context) }
        } else {
            FtpDarkScheme
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = FtpTypography,
        shapes = FtpShapes,
        content = content
    )
}
