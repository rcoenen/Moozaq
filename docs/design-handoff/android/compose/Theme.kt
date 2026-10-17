package com.moozaq.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

// One fixed dark scheme. No dynamic color, so the amber brand is the same on every phone.
private val MoozaqColors = darkColorScheme(
    primary = MzPrimary,
    onPrimary = MzOnPrimary,
    primaryContainer = MzPrimaryContainer,
    onPrimaryContainer = MzOnPrimaryContainer,
    secondaryContainer = MzSecondaryContainer,
    onSecondaryContainer = MzOnSecondaryContainer,
    tertiary = MzTertiary,
    surface = MzSurface,
    surfaceContainerLowest = MzSurfaceContainerLowest,
    surfaceContainerLow = MzSurfaceContainerLow,
    surfaceContainer = MzSurfaceContainer,
    surfaceContainerHigh = MzSurfaceContainerHigh,
    surfaceContainerHighest = MzSurfaceContainerHighest,
    onSurface = MzOnSurface,
    onSurfaceVariant = MzOnSurfaceVariant,
    outline = MzOutline,
    outlineVariant = MzOutlineVariant,
    error = MzError,
    background = MzSurface,
    onBackground = MzOnSurface,
    scrim = androidx.compose.ui.graphics.Color.Black
)

@Composable
fun MoozaqTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MoozaqColors, typography = MoozaqTypography, content = content)
}
