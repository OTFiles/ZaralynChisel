package com.zaralynchisel.ui.components

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.zaralynchisel.ZaralynChiselApp
import com.zaralynchisel.utils.ThemeMode

// ZaralynChisel brand colors
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006B5E),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF6FF7E2),
    onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF4A635C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCE9DF),
    onSecondaryContainer = Color(0xFF06201A),
    tertiary = Color(0xFF426277),
    onTertiary = Color.White,
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    background = Color(0xFFFBFDF9),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFFBFDF9),
    onSurface = Color(0xFF191C1A)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF4EDBC6),
    onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF005046),
    onPrimaryContainer = Color(0xFF6FF7E2),
    secondary = Color(0xFFB1CDC3),
    onSecondary = Color(0xFF1B352E),
    secondaryContainer = Color(0xFF324C44),
    onSecondaryContainer = Color(0xFFCCE9DF),
    tertiary = Color(0xFFB6CBE2),
    onTertiary = Color(0xFF213347),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    background = Color(0xFF191C1A),
    onBackground = Color(0xFFE1E3DF),
    surface = Color(0xFF191C1A),
    onSurface = Color(0xFFE1E3DF)
)

@Composable
fun ZaralynChiselTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as ZaralynChiselApp
    val prefs = app.preferenceManager
    val themeMode = prefs.themeMode

    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.AUTO -> isSystemInDarkTheme()
    }

    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}