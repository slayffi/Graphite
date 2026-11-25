package com.graphite.torrent.ui.designsystem.theme

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

val LocalGraphiteColors = staticCompositionLocalOf { GraphiteColors.Dark }
val LocalGraphiteSpacing = staticCompositionLocalOf { GraphiteSpacing() }
val LocalGraphiteShapes = staticCompositionLocalOf { GraphiteShapes() }
val LocalGraphiteDimens = staticCompositionLocalOf { GraphiteDimens() }
val LocalGraphiteMotion = staticCompositionLocalOf { GraphiteMotion() }
val LocalGraphiteTypography = staticCompositionLocalOf<GraphiteTypography> {
    error("GraphiteTheme is missing: wrap the content in GraphiteTheme { }")
}
val LocalUnitLabels = staticCompositionLocalOf { UnitLabels.English }

/** Accessors for the current theme: `GraphiteTheme.colors.textPrimary`, `GraphiteTheme.spacing.md`, ... */
object GraphiteTheme {
    val colors: GraphiteColors
        @Composable @ReadOnlyComposable get() = LocalGraphiteColors.current
    val typography: GraphiteTypography
        @Composable @ReadOnlyComposable get() = LocalGraphiteTypography.current
    val spacing: GraphiteSpacing
        @Composable @ReadOnlyComposable get() = LocalGraphiteSpacing.current
    val shapes: GraphiteShapes
        @Composable @ReadOnlyComposable get() = LocalGraphiteShapes.current
    val dimens: GraphiteDimens
        @Composable @ReadOnlyComposable get() = LocalGraphiteDimens.current
    val motion: GraphiteMotion
        @Composable @ReadOnlyComposable get() = LocalGraphiteMotion.current
    val units: UnitLabels
        @Composable @ReadOnlyComposable get() = LocalUnitLabels.current
}

/**
 * The only theme of the app (dark only). Material 3 components used inside (text fields, switches,
 * sliders, sheets) are mapped onto the Graphite tokens so nothing falls back to Material defaults.
 */
@Composable
fun GraphiteTheme(
    amoled: Boolean = false,
    reduceMotion: Boolean = false,
    units: UnitLabels = UnitLabels.English,
    content: @Composable () -> Unit,
) {
    val colors = if (amoled) GraphiteColors.Amoled else GraphiteColors.Dark
    val typography = graphiteTypography()
    val motion = remember(reduceMotion) { GraphiteMotion(reduceMotion = reduceMotion) }
    val scheme = remember(colors) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            primaryContainer = colors.surface3,
            onPrimaryContainer = colors.textPrimary,
            secondary = colors.textSecondary,
            onSecondary = colors.bg,
            secondaryContainer = colors.surface3,
            onSecondaryContainer = colors.textPrimary,
            tertiary = colors.accent,
            onTertiary = colors.onAccent,
            background = colors.bg,
            onBackground = colors.textPrimary,
            surface = colors.surface1,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surface2,
            onSurfaceVariant = colors.textSecondary,
            surfaceTint = colors.surface1,
            surfaceContainerLowest = colors.bg,
            surfaceContainerLow = colors.surface1,
            surfaceContainer = colors.surface1,
            surfaceContainerHigh = colors.surface2,
            surfaceContainerHighest = colors.surface3,
            inverseSurface = colors.textPrimary,
            inverseOnSurface = colors.bg,
            outline = colors.borderStrong,
            outlineVariant = colors.border,
            error = colors.error,
            onError = colors.textPrimary,
            errorContainer = colors.surface2,
            onErrorContainer = colors.errorText,
            scrim = colors.scrim,
        )
    }
    val materialTypography = remember(typography) {
        Typography(
            headlineSmall = typography.screenTitle,
            titleMedium = typography.itemTitle,
            titleSmall = typography.label,
            bodyLarge = typography.body,
            bodyMedium = typography.body,
            bodySmall = typography.caption,
            labelLarge = typography.label,
            labelMedium = typography.caption,
            labelSmall = typography.caption,
        )
    }
    val materialShapes = remember {
        Shapes(
            extraSmall = RoundedCornerShape(8.dp),
            small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(16.dp),
        )
    }
    val selection = remember(colors) { TextSelectionColors(colors.accent, colors.accent.copy(alpha = 0.3f)) }

    CompositionLocalProvider(
        LocalGraphiteColors provides colors,
        LocalGraphiteTypography provides typography,
        LocalGraphiteSpacing provides GraphiteSpacing(),
        LocalGraphiteShapes provides GraphiteShapes(),
        LocalGraphiteDimens provides GraphiteDimens(),
        LocalGraphiteMotion provides motion,
        LocalUnitLabels provides units,
    ) {
        MaterialTheme(colorScheme = scheme, typography = materialTypography, shapes = materialShapes) {
            CompositionLocalProvider(
                LocalContentColor provides colors.textPrimary,
                LocalTextSelectionColors provides selection,
                LocalIndication provides ripple(color = colors.accent),
                content = content,
            )
        }
    }
}
