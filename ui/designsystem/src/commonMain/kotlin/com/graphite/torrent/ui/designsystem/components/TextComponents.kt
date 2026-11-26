package com.graphite.torrent.ui.designsystem.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
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
    // Measured and drawn directly: the built-in middle ellipsis is not available on every platform, and a
    // sub-composition per row (BoxWithConstraints) is too expensive for a list that scrolls.
    val measurer = rememberTextMeasurer(cacheSize = 2)
    val resolved = remember(style, color) { style.copy(color = color) }
    val fitted = remember { FittedText() }
    var shown by remember { mutableStateOf<TextLayoutResult?>(null) }
    Layout(
        modifier = modifier
            .clearAndSetSemantics { contentDescription = text }
            .drawBehind { shown?.let { drawText(it) } },
    ) { _, constraints ->
        val result = fitted.fit(measurer, text, resolved, constraints.maxWidth)
        shown = result
        layout(result.size.width.coerceIn(constraints.minWidth, constraints.maxWidth), result.size.height.coerceIn(constraints.minHeight, constraints.maxHeight)) {}
    }
}

/** Remembers the last fit so that scrolling and unrelated re-layouts cost nothing. */
private class FittedText {
    private var text: String? = null
    private var style: TextStyle? = null
    private var maxWidth = -1
    private var result: TextLayoutResult? = null

    fun fit(measurer: TextMeasurer, text: String, style: TextStyle, maxWidth: Int): TextLayoutResult {
        result?.let { if (this.text == text && this.style == style && this.maxWidth == maxWidth) return it }
        fun measure(value: String) = measurer.measure(value, style, maxLines = 1, softWrap = false)
        var best = measure(text)
        if (maxWidth != Constraints.Infinity && best.size.width > maxWidth) {
            // Binary search the longest middle-ellipsized string that fits.
            var lo = 1
            var hi = text.length
            best = measure(middleEllipsis(text, 1))
            while (lo < hi) {
                val mid = (lo + hi + 1) / 2
                val candidate = measure(middleEllipsis(text, mid))
                if (candidate.size.width <= maxWidth) {
                    lo = mid
                    best = candidate
                } else {
                    hi = mid - 1
                }
            }
        }
        this.text = text
        this.style = style
        this.maxWidth = maxWidth
        result = best
        return best
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
