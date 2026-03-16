package com.ghpr.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

private val GhprLightColorScheme = lightColorScheme(
    primary = neo_light_primary,
    onPrimary = neo_light_onPrimary,
    primaryContainer = neo_light_primaryContainer,
    onPrimaryContainer = neo_light_onPrimaryContainer,
    secondary = neo_light_secondary,
    onSecondary = neo_light_onSecondary,
    secondaryContainer = neo_light_secondaryContainer,
    onSecondaryContainer = neo_light_onSecondaryContainer,
    tertiary = neo_light_tertiary,
    onTertiary = neo_light_onTertiary,
    tertiaryContainer = neo_light_tertiaryContainer,
    onTertiaryContainer = neo_light_onTertiaryContainer,
    error = neo_light_error,
    onError = neo_light_onError,
    errorContainer = neo_light_errorContainer,
    onErrorContainer = neo_light_onErrorContainer,
    background = neo_light_background,
    onBackground = neo_light_onBackground,
    surface = neo_light_surface,
    onSurface = neo_light_onSurface,
    surfaceVariant = neo_light_surfaceVariant,
    onSurfaceVariant = neo_light_onSurfaceVariant,
    outline = neo_light_outline,
    surfaceContainerLow = neo_light_surfaceContainerLow,
)

private val GhprDarkColorScheme = darkColorScheme(
    primary = neo_dark_primary,
    onPrimary = neo_dark_onPrimary,
    primaryContainer = neo_dark_primaryContainer,
    onPrimaryContainer = neo_dark_onPrimaryContainer,
    secondary = neo_dark_secondary,
    onSecondary = neo_dark_onSecondary,
    secondaryContainer = neo_dark_secondaryContainer,
    onSecondaryContainer = neo_dark_onSecondaryContainer,
    tertiary = neo_dark_tertiary,
    onTertiary = neo_dark_onTertiary,
    tertiaryContainer = neo_dark_tertiaryContainer,
    onTertiaryContainer = neo_dark_onTertiaryContainer,
    error = neo_dark_error,
    onError = neo_dark_onError,
    errorContainer = neo_dark_errorContainer,
    onErrorContainer = neo_dark_onErrorContainer,
    background = neo_dark_background,
    onBackground = neo_dark_onBackground,
    surface = neo_dark_surface,
    onSurface = neo_dark_onSurface,
    surfaceVariant = neo_dark_surfaceVariant,
    onSurfaceVariant = neo_dark_onSurfaceVariant,
    outline = neo_dark_outline,
    surfaceContainerLow = neo_dark_surfaceContainerLow,
)

private val NeoBrutalColorsLight = NeoBrutalColors(
    border = Color.Black,
    shadow = Color.Black,
    cardBg = Color.White,
)

private val NeoBrutalColorsDark = NeoBrutalColors(
    border = Color(0xFF30363D),   // GitHub dark border
    shadow = Color(0xFF010409),   // GitHub very dark
    cardBg = Color(0xFF161B22),   // GitHub dark surface
)

@Composable
fun GhprTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) GhprDarkColorScheme else GhprLightColorScheme

    val statusColors = if (darkTheme) {
        val dark = GhprStatusColorsDark()
        GhprStatusColors(
            opened = dark.opened,
            closed = dark.closed,
            merged = dark.merged,
            pending = dark.pending,
            link = dark.link,
            success = dark.success,
            failure = dark.failure,
            commented = dark.commented,
            mentioned = dark.mentioned,
            assigned = dark.assigned,
            updated = dark.updated,
            stateChanged = dark.stateChanged,
        )
    } else {
        GhprStatusColors()
    }

    val neoBrutalColors = if (darkTheme) NeoBrutalColorsDark else NeoBrutalColorsLight

    CompositionLocalProvider(
        LocalGhprStatusColors provides statusColors,
        LocalNeoBrutalColors provides neoBrutalColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = GhprTypography,
            content = content,
        )
    }
}
