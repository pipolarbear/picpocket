package com.picpocket.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Ink — one deep, cool palette with an amber accent.

private val InkBlue = Color(0xFF2F5BFF)
private val InkAmber = Color(0xFFF0A21C)
private val InkCyan = Color(0xFF00A6B8)

val InkLight = lightColorScheme(
    primary = InkBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4FF),
    onPrimaryContainer = Color(0xFF001945),
    inversePrimary = Color(0xFF8FB0FF),
    secondary = InkAmber,
    onSecondary = Color(0xFF3A2500),
    secondaryContainer = Color(0xFFFFE3A8),
    onSecondaryContainer = Color(0xFF241800),
    tertiary = InkCyan,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB6F0F5),
    onTertiaryContainer = Color(0xFF002F35),
    background = Color(0xFFF6F7FB),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFEEF1F7),
    onSurfaceVariant = Color(0xFF5B6478),
    surfaceTint = InkBlue,
    surfaceDim = Color(0xFFE3E6EF),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF1F3F9),
    surfaceContainer = Color(0xFFEDF0F7),
    surfaceContainerHigh = Color(0xFFE7EAF3),
    surfaceContainerHighest = Color(0xFFE1E5EF),
    outline = Color(0xFFD5DAE6),
    outlineVariant = Color(0xFFE6E9F1),
    error = Color(0xFFD92D20),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Color(0xFF2A3040),
    inverseOnSurface = Color(0xFFF1F3F9),
    scrim = Color.Black,
)

val InkDark = darkColorScheme(
    primary = Color(0xFF8FB0FF),
    onPrimary = Color(0xFF081A4A),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFDCE4FF),
    inversePrimary = InkBlue,
    secondary = Color(0xFFFFC65C),
    onSecondary = Color(0xFF3A2500),
    secondaryContainer = Color(0xFF6B4A00),
    onSecondaryContainer = Color(0xFFFFE3A8),
    tertiary = Color(0xFF6FD9E6),
    onTertiary = Color(0xFF00363D),
    tertiaryContainer = Color(0xFF00505A),
    onTertiaryContainer = Color(0xFFB6F0F5),
    background = Color(0xFF0B1020),
    onBackground = Color(0xFFE9EDF7),
    surface = Color(0xFF121828),
    onSurface = Color(0xFFE9EDF7),
    surfaceVariant = Color(0xFF1A2235),
    onSurfaceVariant = Color(0xFFA7B0C5),
    surfaceTint = Color(0xFF8FB0FF),
    surfaceDim = Color(0xFF0B1020),
    surfaceBright = Color(0xFF2A3348),
    surfaceContainerLowest = Color(0xFF080C18),
    surfaceContainerLow = Color(0xFF0F1524),
    surfaceContainer = Color(0xFF141B2C),
    surfaceContainerHigh = Color(0xFF1B2336),
    surfaceContainerHighest = Color(0xFF222B41),
    outline = Color(0xFF333D54),
    outlineVariant = Color(0xFF232C40),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0000),
    errorContainer = Color(0xFF6B1B1B),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = Color(0xFFE9EDF7),
    inverseOnSurface = Color(0xFF1A2235),
    scrim = Color.Black,
)

/** Tag accents tuned for the Ink palette (same eight-slot rotation). */
val TagColors = listOf(
    Color(0xFFE4573D), // coral
    Color(0xFFF29D38), // amber
    Color(0xFFD9B72C), // gold
    Color(0xFF3FA66B), // green
    Color(0xFF2BB3C0), // teal
    Color(0xFF3B82F6), // blue
    Color(0xFF8B5CF6), // violet
    Color(0xFFD75A9A), // pink
)
