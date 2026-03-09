package com.ghpr.app.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ── Neobrutalist Light Palette ──────────────────────────────────────
val neo_light_background = Color(0xFFFFFEF0)       // Warm cream
val neo_light_surface = Color(0xFFFFFEF0)
val neo_light_surfaceContainerLow = Color(0xFFFFFFFF) // White cards
val neo_light_primary = Color(0xFFFF6B35)           // Vivid orange
val neo_light_onPrimary = Color(0xFF000000)
val neo_light_primaryContainer = Color(0xFFFFD166)  // Warm yellow
val neo_light_onPrimaryContainer = Color(0xFF000000)
val neo_light_secondary = Color(0xFF06D6A0)         // Bright mint
val neo_light_onSecondary = Color(0xFF000000)
val neo_light_secondaryContainer = Color(0xFF06D6A0)
val neo_light_onSecondaryContainer = Color(0xFF000000)
val neo_light_tertiary = Color(0xFF118AB2)          // Strong teal
val neo_light_onTertiary = Color(0xFFFFFFFF)
val neo_light_tertiaryContainer = Color(0xFF118AB2)
val neo_light_onTertiaryContainer = Color(0xFFFFFFFF)
val neo_light_error = Color(0xFFEF476F)             // Hot pink-red
val neo_light_onError = Color(0xFFFFFFFF)
val neo_light_errorContainer = Color(0xFFFFDAD6)
val neo_light_onErrorContainer = Color(0xFF410002)
val neo_light_onBackground = Color(0xFF000000)
val neo_light_onSurface = Color(0xFF000000)
val neo_light_surfaceVariant = Color(0xFFF0EDE4)
val neo_light_onSurfaceVariant = Color(0xFF333333)
val neo_light_outline = Color(0xFF000000)           // Thick black borders

// ── Neobrutalist Dark Palette ───────────────────────────────────────
val neo_dark_background = Color(0xFF1A1A2E)         // Deep navy
val neo_dark_surface = Color(0xFF1A1A2E)
val neo_dark_surfaceContainerLow = Color(0xFF22223B)
val neo_dark_primary = Color(0xFFFF8C5A)
val neo_dark_onPrimary = Color(0xFF000000)
val neo_dark_primaryContainer = Color(0xFFFFD166)
val neo_dark_onPrimaryContainer = Color(0xFF000000)
val neo_dark_secondary = Color(0xFF06D6A0)
val neo_dark_onSecondary = Color(0xFF000000)
val neo_dark_secondaryContainer = Color(0xFF06D6A0)
val neo_dark_onSecondaryContainer = Color(0xFF000000)
val neo_dark_tertiary = Color(0xFF4CC9F0)
val neo_dark_onTertiary = Color(0xFF000000)
val neo_dark_tertiaryContainer = Color(0xFF4CC9F0)
val neo_dark_onTertiaryContainer = Color(0xFF000000)
val neo_dark_error = Color(0xFFFF6B8A)
val neo_dark_onError = Color(0xFF000000)
val neo_dark_errorContainer = Color(0xFF93000A)
val neo_dark_onErrorContainer = Color(0xFFFFDAD6)
val neo_dark_onBackground = Color(0xFFF0EDE4)
val neo_dark_onSurface = Color(0xFFF0EDE4)
val neo_dark_surfaceVariant = Color(0xFF2A2A3E)
val neo_dark_onSurfaceVariant = Color(0xFFBBB8B0)
val neo_dark_outline = Color(0xFFF0EDE4)

// ── NeoBrutal design-system colors ──────────────────────────────────
@Immutable
data class NeoBrutalColors(
    val border: Color,
    val shadow: Color,
    val cardBg: Color,
)

val LocalNeoBrutalColors = staticCompositionLocalOf {
    NeoBrutalColors(
        border = Color.Black,
        shadow = Color.Black,
        cardBg = Color.White,
    )
}

// ── Semantic status colors (unchanged) ──────────────────────────────
@Immutable
data class GhprStatusColors(
    val opened: Color = Color(0xFF2DA44E),
    val closed: Color = Color(0xFFCF222E),
    val merged: Color = Color(0xFF8250DF),
    val pending: Color = Color(0xFFBF8700),
    val link: Color = Color(0xFF0969DA),
    val success: Color = Color(0xFF2DA44E),
    val failure: Color = Color(0xFFCF222E),
    val commented: Color = Color(0xFF0969DA),
    val mentioned: Color = Color(0xFFE16F24),
    val assigned: Color = Color(0xFF1B7C83),
    val updated: Color = Color(0xFF57606A),
    val stateChanged: Color = Color(0xFFBF3989),
)

@Immutable
data class GhprStatusColorsDark(
    val opened: Color = Color(0xFF3FB950),
    val closed: Color = Color(0xFFF85149),
    val merged: Color = Color(0xFFA371F7),
    val pending: Color = Color(0xFFD29922),
    val link: Color = Color(0xFF58A6FF),
    val success: Color = Color(0xFF3FB950),
    val failure: Color = Color(0xFFF85149),
    val commented: Color = Color(0xFF58A6FF),
    val mentioned: Color = Color(0xFFF0883E),
    val assigned: Color = Color(0xFF39C5CF),
    val updated: Color = Color(0xFF8B949E),
    val stateChanged: Color = Color(0xFFDB61A2),
)

val LocalGhprStatusColors = staticCompositionLocalOf { GhprStatusColors() }
