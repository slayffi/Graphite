package com.graphite.torrent.ui.designsystem.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.tooling.preview.Preview
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme

/**
 * Shortens [text] to [maxChars] characters by cutting the middle, keeping more of the end (where
 * episode numbers, quality and the file extension usually are).
 */
fun middleEllipsis(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    if (maxChars <= 1) return "…"
    val available = maxChars - 1
    val back = available * 3 / 5
    val front = available - back
    return text.take(front) + "…" + text.takeLast(back)
}

/**
 * Single-line text that is truncated in the middle to fit the available width.
 * The full text stays available to screen readers.
 */
@Composable
fun MiddleEllipsisText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = GraphiteTheme.colors.textPrimary,
) {
    val measurer = rememberTextMeasurer(cacheSize = 8)
    BoxWithConstraints(modifier.clearAndSetSemantics { contentDescription = text }) {
        val maxWidth = constraints.maxWidth
        val shown = remember(text, style, maxWidth) {
            if (maxWidth == Constraints.Infinity) {
                text
            } else {
                val full = measurer.measure(text, style, maxLines = 1, softWrap = false)
                if (full.size.width <= maxWidth) {
                    text
                } else {
                    // Binary search the longest middle-ellipsized string that fits.
                    var lo = 1
                    var hi = text.length
                    while (lo < hi) {
                        val mid = (lo + hi + 1) / 2
                        val w = measurer.measure(middleEllipsis(text, mid), style, maxLines = 1, softWrap = false).size.width
                        if (w <= maxWidth) lo = mid else hi = mid - 1
                    }
                    middleEllipsis(text, lo)
                }
            }
        }
        Text(shown, style = style, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

/**
 * Numbers that change by rolling each changed character vertically instead of jumping.
 * Use a monospaced [style] so the width stays stable.
 */
@Composable
fun RollingText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = GraphiteTheme.colors.textPrimary,
) {
    val motion = GraphiteTheme.motion
    Row(modifier.clearAndSetSemantics { contentDescription = text }) {
        text.forEachIndexed { index, char ->
            AnimatedContent(
                targetState = char,
                transitionSpec = {
                    val d = motion.duration(motion.medium)
                    val up = targetState > initialState
                    (slideInVertically(tween(d, easing = motion.easing)) { h -> if (up) h else -h } + fadeIn(tween(d))) togetherWith
                        (slideOutVertically(tween(d, easing = motion.easing)) { h -> if (up) -h else h } + fadeOut(tween(d)))
                },
                label = "rolling-$index",
            ) { c ->
                Text(c.toString(), style = style, color = color, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Preview
@Composable
private fun TextComponentsPreview() {
    GraphiteTheme {
        Column(Modifier.padding(16.dp)) {
            MiddleEllipsisText(
                "Some.Very.Long.Series.Name.S02E07.Episode.Title.2160p.WEB-DL.DDP5.1.HDR.mkv",
                GraphiteTheme.typography.itemTitle,
                Modifier.width(240.dp),
            )
            RollingText("12,4 МБ/с", GraphiteTheme.typography.displayNumber)
        }
    }
}
