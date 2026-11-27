package com.svenuks.imsforpixel.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val StatusGreen = Color(0xFF10B981)
val StatusBlue = Color(0xFF38BDF8)
val StatusAmber = Color(0xFFF59E0B)
val StatusRed = Color(0xFFEF4444)

private val FallbackDarkScheme = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF0A192F),
    primaryContainer = Color(0xFF1E3A5F),
    onPrimaryContainer = Color(0xFFD8E8FF),
    secondary = Color(0xFF34D399),
    onSecondary = Color(0xFF06281E),
    secondaryContainer = Color(0xFF134E4A),
    onSecondaryContainer = Color(0xFFA7F3D0),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFF1F5F9),
    surface = Color(0xFF111A2B),
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = Color(0xFF1B273B),
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A)
)

private val FallbackLightScheme = lightColorScheme(
    primary = Color(0xFF1D4ED8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF1E3A8A),
    secondary = Color(0xFF059669),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD1FAE5),
    onSecondaryContainer = Color(0xFF065F46),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color.White,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFDC2626),
    onError = Color.White
)

@Composable
fun ImsForPixelTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> FallbackDarkScheme
        else -> FallbackLightScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
