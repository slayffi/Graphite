package com.graphite.torrent.ui.designsystem.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.graphite.torrent.ui.designsystem.resources.Res
import com.graphite.torrent.ui.designsystem.resources.inter_medium
import com.graphite.torrent.ui.designsystem.resources.inter_regular
import com.graphite.torrent.ui.designsystem.resources.inter_semibold
import com.graphite.torrent.ui.designsystem.resources.jetbrains_mono_medium
import com.graphite.torrent.ui.designsystem.resources.jetbrains_mono_regular
import org.jetbrains.compose.resources.Font

/**
 * Type scale. Interface text uses Inter; every number (speed, size, percent, ETA) uses JetBrains Mono
 * so values never jump while they change.
 */
@Immutable
data class GraphiteTypography(
    /** Screen title, 24 SemiBold. */
    val screenTitle: TextStyle,
    /** Torrent name, 16 Medium. */
    val itemTitle: TextStyle,
    /** Body text, 14 Regular. */
    val body: TextStyle,
    /** Body text, 14 Medium (buttons, chips, tabs). */
    val label: TextStyle,
    /** Captions and secondary lines, 12 Regular. */
    val caption: TextStyle,
    /** Big speed number, 32 Medium mono. */
    val displayNumber: TextStyle,
    /** Percent on a card, 20 Medium mono. */
    val numberLarge: TextStyle,
    /** Inline numbers in body text, 14 Regular mono. */
    val numberBody: TextStyle,
    /** Inline numbers in captions, 12 Regular mono. */
    val numberCaption: TextStyle,
    /** Section headers inside screens, 12 Medium, letter spaced. */
    val overline: TextStyle,
)

private val trimmed = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

@Composable
fun interFontFamily(): FontFamily = FontFamily(
    Font(Res.font.inter_regular, FontWeight.Normal),
    Font(Res.font.inter_medium, FontWeight.Medium),
    Font(Res.font.inter_semibold, FontWeight.SemiBold),
)

@Composable
fun monoFontFamily(): FontFamily = FontFamily(
    Font(Res.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(Res.font.jetbrains_mono_medium, FontWeight.Medium),
)

@Composable
fun graphiteTypography(): GraphiteTypography {
    val inter = interFontFamily()
    val mono = monoFontFamily()
    return GraphiteTypography(
        screenTitle = TextStyle(fontFamily = inter, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp, lineHeightStyle = trimmed),
        itemTitle = TextStyle(fontFamily = inter, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, lineHeightStyle = trimmed),
        body = TextStyle(fontFamily = inter, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, lineHeightStyle = trimmed),
        label = TextStyle(fontFamily = inter, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, lineHeightStyle = trimmed),
        caption = TextStyle(fontFamily = inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, lineHeightStyle = trimmed),
        displayNumber = TextStyle(fontFamily = mono, fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 40.sp, lineHeightStyle = trimmed),
        numberLarge = TextStyle(fontFamily = mono, fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 24.sp, lineHeightStyle = trimmed),
        numberBody = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, lineHeightStyle = trimmed),
        numberCaption = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, lineHeightStyle = trimmed),
        overline = TextStyle(fontFamily = inter, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.6.sp, lineHeightStyle = trimmed),
    )
}
