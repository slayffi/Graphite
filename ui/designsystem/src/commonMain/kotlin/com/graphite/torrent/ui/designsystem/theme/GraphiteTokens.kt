package com.graphite.torrent.ui.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Colour tokens. Hierarchy comes from shades of grey; the only accent is near-white and the only
 * colour is a muted red for errors. There are no shadows: depth is a lighter surface plus a 1dp border.
 */
@Immutable
data class GraphiteColors(
    val bg: Color,
    val surface1: Color,
    val surface2: Color,
    val surface3: Color,
    val border: Color,
    val borderStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val accent: Color,
    val accentBright: Color,
    /** Muted red for icons, borders and progress. */
    val error: Color,
    /** Lighter variant of [error] for text, so error messages keep a 4.5:1 contrast on dark surfaces. */
    val errorText: Color,
    /** Text/icons drawn on top of [accent] (primary button). */
    val onAccent: Color,
    /** Scrim behind sheets and modal panels. */
    val scrim: Color,
) {
    companion object {
        val Dark = GraphiteColors(
            bg = Color(0xFF0B0B0C),
            surface1 = Color(0xFF141416),
            surface2 = Color(0xFF1C1C1F),
            surface3 = Color(0xFF26262A),
            border = Color(0xFF2A2A2E),
            borderStrong = Color(0xFF3A3A3F),
            textPrimary = Color(0xFFEDEDED),
            textSecondary = Color(0xFF9A9AA0),
            textDisabled = Color(0xFF5C5C61),
            accent = Color(0xFFC8C8CC),
            accentBright = Color(0xFFFFFFFF),
            error = Color(0xFFB3524F),
            errorText = Color(0xFFD98A87),
            onAccent = Color(0xFF0B0B0C),
            scrim = Color(0xB3000000),
        )

        /** "Pure black" option for AMOLED screens: only the background changes. */
        val Amoled = Dark.copy(bg = Color(0xFF000000))
    }
}

/** Spacing scale on a 4dp grid. */
@Immutable
data class GraphiteSpacing(
    val xxs: Dp = 4.dp,
    val xs: Dp = 8.dp,
    val sm: Dp = 12.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
)

@Immutable
data class GraphiteShapes(
    val card: Shape = RoundedCornerShape(16.dp),
    val control: Shape = RoundedCornerShape(12.dp),
    val pill: Shape = RoundedCornerShape(percent = 50),
    val sheet: Shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    val panel: Shape = RoundedCornerShape(16.dp),
)

/** Fixed sizes used by components and layouts. */
@Immutable
data class GraphiteDimens(
    val borderWidth: Dp = 1.dp,
    val minTouchTarget: Dp = 48.dp,
    val icon: Dp = 20.dp,
    val iconSmall: Dp = 16.dp,
    val progressBar: Dp = 3.dp,
    val sidebarCollapsed: Dp = 64.dp,
    val sidebarExpanded: Dp = 220.dp,
    val twoPaneBreakpoint: Dp = 1000.dp,
    val listPaneWidth: Dp = 420.dp,
    val maxContentWidth: Dp = 840.dp,
    val pieceCell: Dp = 6.dp,
    val pieceGap: Dp = 1.dp,
    val miniWindowWidth: Dp = 320.dp,
    val miniWindowHeight: Dp = 120.dp,
    val fab: Dp = 56.dp,
)

/** Motion: 200–300 ms with a decelerating ease-out. */
@Immutable
data class GraphiteMotion(
    val reduceMotion: Boolean = false,
    val short: Int = 200,
    val medium: Int = 250,
    val long: Int = 300,
    val logoReveal: Int = 600,
    val shimmerPeriod: Int = 1600,
    val controlsAutoHideMs: Long = 3000,
    val easing: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
) {
    /** Duration to use, collapsing to 0 when the user asked for reduced motion. */
    fun duration(base: Int): Int = if (reduceMotion) 0 else base
}
