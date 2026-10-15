package dev.duardo.neotransfer.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Brand accent. [onDark] is the bright tone used over graphite; [onLight] the deeper tone used over light
 * surfaces. Every other accent token (ink, containers, text on accent) is derived from these two, so an app
 * only swaps this value to rebrand the whole system.
 */
@Immutable
data class NeoAccent(val onLight: Color, val onDark: Color)

object NeoAccents {
    val Mint = NeoAccent(onLight = Color(0xFF1FB36F), onDark = Color(0xFF3DDC97))
}

/** Semantic colors that Material's scheme does not name. */
@Immutable
data class NeoColors(
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    /** Accent as text or small glyphs: AA contrast on [group] and background. */
    val accentInk: Color,
    /** Grouped containers (lists, summaries) sitting on the background. */
    val group: Color,
    /**
     * Rim of raised surfaces (groups, sheets, fields, tiles): in the dark, light catching the top edge, which is what
     * tells layers apart; in the light, a hairline all round, since white on the pale background barely separates.
     */
    val edge: Color,
    val divider: Color,
    val positive: Color,
    val positiveContainer: Color,
    val warning: Color,
    val warningContainer: Color,
    val danger: Color,
    val dangerContainer: Color,
    val neutral: Color,
    val neutralContainer: Color,
    /** Printed text on physical card art. */
    val cardInk: Color,
    val skeleton: Color,
    val isDark: Boolean,
)

private val Ink = Color(0xFF050807)

private object Graphite {
    // Light
    val l0 = Color(0xFFF3F4F6)
    val lGroup = Color(0xFFFFFFFF)
    val l1 = Color(0xFFEBEDF0)
    val l2 = Color(0xFFE3E6E9)
    val l3 = Color(0xFFDADEE2)
    val lText = Color(0xFF121417)
    val lMuted = Color(0xFF5A616A)
    val lOutline = Color(0xFF8B929A)
    val lHairline = Color(0xFFE2E5E8)

    // Dark
    val d0 = Color(0xFF0D0E11)
    val dGroup = Color(0xFF17191D)
    val d1 = Color(0xFF1C1F23)
    val d2 = Color(0xFF24272C)
    val d3 = Color(0xFF2D3137)
    val dText = Color(0xFFF1F3F5)
    val dMuted = Color(0xFF9AA1AA)
    val dOutline = Color(0xFF656C75)
    val dHairline = Color(0xFF262A2F)
}

internal fun neoColors(accent: NeoAccent, dark: Boolean): NeoColors = if (dark) {
    val a = accent.onDark
    NeoColors(
        accent = a,
        onAccent = lerp(a, Ink, 0.84f),
        accentContainer = lerp(a, Graphite.d0, 0.80f),
        onAccentContainer = a,
        accentInk = a,
        group = Graphite.dGroup,
        edge = Color.White.copy(alpha = 0.09f),
        divider = Graphite.dHairline,
        positive = a,
        positiveContainer = lerp(a, Graphite.d0, 0.80f),
        warning = Color(0xFFF0B357),
        warningContainer = Color(0xFF32260F),
        danger = Color(0xFFFF7A80),
        dangerContainer = Color(0xFF3A1518),
        neutral = Graphite.dMuted,
        neutralContainer = Graphite.d2,
        cardInk = Color(0xFF16181A),
        skeleton = Graphite.d2,
        isDark = true,
    )
} else {
    val a = accent.onLight
    NeoColors(
        accent = a,
        onAccent = lerp(a, Ink, 0.84f),
        accentContainer = lerp(a, Color.White, 0.84f),
        onAccentContainer = lerp(a, Ink, 0.62f),
        accentInk = lerp(a, Ink, 0.40f),
        group = Graphite.lGroup,
        edge = Graphite.lHairline,
        divider = Graphite.lHairline,
        positive = lerp(a, Ink, 0.40f),
        positiveContainer = lerp(a, Color.White, 0.84f),
        warning = Color(0xFF8F5300),
        warningContainer = Color(0xFFFCEBD2),
        danger = Color(0xFFBF2F39),
        dangerContainer = Color(0xFFFBE3E4),
        neutral = Graphite.lMuted,
        neutralContainer = Graphite.l1,
        cardInk = Color(0xFF16181A),
        skeleton = Graphite.l2,
        isDark = false,
    )
}

internal fun neoColorScheme(colors: NeoColors): ColorScheme = if (colors.isDark) darkColorScheme(
    primary = colors.accent,
    onPrimary = colors.onAccent,
    primaryContainer = colors.accentContainer,
    onPrimaryContainer = colors.onAccentContainer,
    secondary = colors.accent,
    onSecondary = colors.onAccent,
    secondaryContainer = colors.accentContainer,
    onSecondaryContainer = colors.onAccentContainer,
    tertiary = colors.accent,
    onTertiary = colors.onAccent,
    background = Graphite.d0,
    onBackground = Graphite.dText,
    surface = Graphite.d0,
    onSurface = Graphite.dText,
    surfaceVariant = Graphite.d2,
    onSurfaceVariant = Graphite.dMuted,
    surfaceTint = Color.Transparent,
    surfaceBright = Graphite.d3,
    surfaceDim = Graphite.d0,
    surfaceContainerLowest = Graphite.d0,
    surfaceContainerLow = Graphite.dGroup,
    surfaceContainer = Graphite.d1,
    surfaceContainerHigh = Graphite.d2,
    surfaceContainerHighest = Graphite.d3,
    outline = Graphite.dOutline,
    outlineVariant = Graphite.dHairline,
    inverseSurface = Graphite.dText,
    inverseOnSurface = Graphite.dGroup,
    inversePrimary = lerp(colors.accent, Ink, 0.4f),
    error = colors.danger,
    onError = Color(0xFF3D0A0E),
    errorContainer = colors.dangerContainer,
    onErrorContainer = Color(0xFFFFB4B8),
    scrim = Color.Black,
) else lightColorScheme(
    primary = colors.accent,
    onPrimary = colors.onAccent,
    primaryContainer = colors.accentContainer,
    onPrimaryContainer = colors.onAccentContainer,
    secondary = colors.accentInk,
    onSecondary = Color.White,
    secondaryContainer = colors.accentContainer,
    onSecondaryContainer = colors.onAccentContainer,
    tertiary = colors.accentInk,
    onTertiary = Color.White,
    background = Graphite.l0,
    onBackground = Graphite.lText,
    surface = Graphite.l0,
    onSurface = Graphite.lText,
    surfaceVariant = Graphite.l2,
    onSurfaceVariant = Graphite.lMuted,
    surfaceTint = Color.Transparent,
    surfaceBright = Graphite.lGroup,
    surfaceDim = Graphite.l2,
    surfaceContainerLowest = Graphite.lGroup,
    surfaceContainerLow = Graphite.lGroup,
    surfaceContainer = Graphite.l1,
    surfaceContainerHigh = Graphite.l2,
    surfaceContainerHighest = Graphite.l3,
    outline = Graphite.lOutline,
    outlineVariant = Graphite.lHairline,
    inverseSurface = Color(0xFF1E2125),
    inverseOnSurface = Graphite.dText,
    inversePrimary = colors.accent,
    error = colors.danger,
    onError = Color.White,
    errorContainer = colors.dangerContainer,
    onErrorContainer = Color(0xFF6E1016),
    scrim = Color.Black,
)

val LocalNeoColors = staticCompositionLocalOf<NeoColors> { error("NeoTheme is missing") }
