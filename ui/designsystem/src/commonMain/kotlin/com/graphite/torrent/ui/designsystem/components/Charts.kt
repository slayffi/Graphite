package com.graphite.torrent.ui.designsystem.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Speed chart: a thin light line with a translucent fill below, no grid. Touch or hover shows the
 * value at that point using [valueLabel].
 */
@Composable
fun SpeedChart(
    values: List<Long>,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
    interactive: Boolean = true,
    contentDescription: String = "",
    valueLabel: (index: Int, value: Long) -> String = { _, v -> v.toString() },
) {
    val colors = GraphiteTheme.colors
    val typography = GraphiteTheme.typography
    val measurer = rememberTextMeasurer()
    var selected by remember { mutableStateOf<Int?>(null) }

    val input = if (interactive && values.size > 1) {
        Modifier
            .pointerInput(values.size) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        when (event.type) {
                            PointerEventType.Move, PointerEventType.Enter -> {
                                val x = event.changes.first().position.x
                                selected = ((x / size.width) * (values.size - 1)).roundToInt().coerceIn(0, values.size - 1)
                            }
                            PointerEventType.Exit -> selected = null
                        }
                    }
                }
            }
            .pointerInput(values.size) {
                detectTapGestures(onPress = { offset ->
                    selected = ((offset.x / size.width) * (values.size - 1)).roundToInt().coerceIn(0, values.size - 1)
                    tryAwaitRelease()
                })
            }
            .pointerInput(values.size) {
                detectDragGestures(onDragEnd = { selected = null }, onDragCancel = { selected = null }) { change, _ ->
                    selected = ((change.position.x / size.width) * (values.size - 1)).roundToInt().coerceIn(0, values.size - 1)
                }
            }
    } else {
        Modifier
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { this.contentDescription = contentDescription }
            .then(input),
    ) {
        if (values.isEmpty()) return@Canvas
        val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat() * 1.15f
        val stepX = if (values.size > 1) size.width / (values.size - 1) else size.width
        fun point(i: Int) = Offset(i * stepX, size.height - (values[i] / max) * size.height)

        val line = Path()
        val fill = Path()
        values.indices.forEach { i ->
            val p = point(i)
            if (i == 0) {
                line.moveTo(p.x, p.y)
                fill.moveTo(p.x, size.height)
                fill.lineTo(p.x, p.y)
            } else {
                val prev = point(i - 1)
                val midX = (prev.x + p.x) / 2
                line.cubicTo(midX, prev.y, midX, p.y, p.x, p.y)
                fill.cubicTo(midX, prev.y, midX, p.y, p.x, p.y)
            }
        }
        val last = point(values.lastIndex)
        fill.lineTo(last.x, size.height)
        fill.close()
        drawPath(fill, Brush.verticalGradient(listOf(colors.accent.copy(alpha = 0.22f), colors.accent.copy(alpha = 0f))))
        drawPath(line, colors.accent, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        val sel = selected
        if (sel != null && sel in values.indices) {
            val p = point(sel)
            drawLine(colors.borderStrong, Offset(p.x, 0f), Offset(p.x, size.height), strokeWidth = 1.dp.toPx())
            drawCircle(colors.accentBright, radius = 3.5.dp.toPx(), center = p)
            val label = measurer.measure(valueLabel(sel, values[sel]), typography.numberCaption.copy(color = colors.textPrimary))
            val pad = 6.dp.toPx()
            val w = label.size.width + pad * 2
            val h = label.size.height + pad
            val x = (p.x - w / 2).coerceIn(0f, size.width - w)
            val y = (p.y - h - 8.dp.toPx()).coerceAtLeast(0f)
            drawRoundRect(colors.surface3, Offset(x, y), Size(w, h), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
            drawText(label, topLeft = Offset(x + pad, y + pad / 2))
        }
    }
}

/** Data for one bar of the statistics chart. */
@Immutable
data class BarValue(val label: String, val value: Long)

/** Simple vertical bars for daily statistics (same monochrome language as the speed chart). */
@Composable
fun BarChart(
    bars: List<BarValue>,
    modifier: Modifier = Modifier,
    height: Dp = 140.dp,
    valueLabel: (Long) -> String = { it.toString() },
) {
    val colors = GraphiteTheme.colors
    val typography = GraphiteTheme.typography
    val measurer = rememberTextMeasurer()
    var selected by remember { mutableStateOf<Int?>(null) }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(bars.size) {
                detectTapGestures { o ->
                    if (bars.isNotEmpty()) selected = (o.x / (size.width / bars.size)).toInt().coerceIn(0, bars.lastIndex)
                }
            }
            .pointerInput(bars.size) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        when (e.type) {
                            PointerEventType.Move -> if (bars.isNotEmpty()) {
                                selected = (e.changes.first().position.x / (size.width / bars.size)).toInt().coerceIn(0, bars.lastIndex)
                            }
                            PointerEventType.Exit -> selected = null
                        }
                    }
                }
            },
    ) {
        if (bars.isEmpty()) return@Canvas
        val labelHeight = 16.dp.toPx()
        val chartHeight = size.height - labelHeight
        val max = bars.maxOf { it.value }.coerceAtLeast(1L).toFloat()
        val slot = size.width / bars.size
        val barWidth = (slot * 0.6f).coerceAtMost(24.dp.toPx())
        val showEvery = if (bars.size > 14) 5 else 1
        bars.forEachIndexed { i, bar ->
            val h = (bar.value / max) * (chartHeight - 4.dp.toPx())
            val x = i * slot + (slot - barWidth) / 2
            val isSel = i == selected
            drawRoundRect(
                color = if (isSel) colors.accentBright else if (bar.value > 0) colors.accent.copy(alpha = 0.7f) else colors.border,
                topLeft = Offset(x, chartHeight - h.coerceAtLeast(2.dp.toPx())),
                size = Size(barWidth, h.coerceAtLeast(2.dp.toPx())),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
            )
            if (i % showEvery == 0 || i == bars.lastIndex) {
                val t = measurer.measure(bar.label, typography.caption.copy(color = colors.textSecondary))
                drawText(t, topLeft = Offset(i * slot + (slot - t.size.width) / 2, chartHeight + 2.dp.toPx()))
            }
        }
        val sel = selected
        if (sel != null && sel in bars.indices) {
            val t = measurer.measure(valueLabel(bars[sel].value), typography.numberCaption.copy(color = colors.textPrimary))
            val pad = 6.dp.toPx()
            val w = t.size.width + pad * 2
            val x = (sel * slot + slot / 2 - w / 2).coerceIn(0f, size.width - w)
            drawRoundRect(colors.surface3, Offset(x, 0f), Size(w, t.size.height + pad), androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
            drawText(t, topLeft = Offset(x + pad, pad / 2))
        }
    }
}

/** Piece map: a grid of small squares in four greys (missing, queued, downloading, done). */
@Composable
fun PieceMap(
    states: ByteArray,
    modifier: Modifier = Modifier,
    contentDescription: String = "",
) {
    val colors = GraphiteTheme.colors
    val dimens = GraphiteTheme.dimens
    val palette = listOf(colors.border, colors.borderStrong, colors.textSecondary, colors.accent)
    Canvas(modifier.fillMaxWidth().semantics { this.contentDescription = contentDescription }.height(pieceMapHeight(states.size))) {
        if (states.isEmpty()) return@Canvas
        val cell = dimens.pieceCell.toPx()
        val gap = dimens.pieceGap.toPx()
        val columns = ((size.width + gap) / (cell + gap)).toInt().coerceAtLeast(1)
        val maxCells = columns * ((size.height + gap) / (cell + gap)).toInt().coerceAtLeast(1)
        val perCell = ((states.size + maxCells - 1) / maxCells).coerceAtLeast(1)
        val cells = (states.size + perCell - 1) / perCell
        for (c in 0 until cells) {
            // A cell summarizes several pieces on very large torrents: show the "least done" state.
            var state = 3
            val start = c * perCell
            val end = minOf(start + perCell, states.size)
            for (i in start until end) state = minOf(state, states[i].toInt())
            if (perCell > 1 && state < 3) {
                var anyProgress = false
                for (i in start until end) if (states[i].toInt() >= 2) anyProgress = true
                if (anyProgress) state = 2
            }
            val row = c / columns
            val col = c % columns
            drawRect(
                palette[state.coerceIn(0, 3)],
                topLeft = Offset(col * (cell + gap), row * (cell + gap)),
                size = Size(cell, cell),
            )
        }
    }
}

@Composable
private fun pieceMapHeight(count: Int): Dp = when {
    count <= 0 -> 0.dp
    count < 400 -> 48.dp
    count < 2000 -> 120.dp
    else -> 200.dp
}

@Preview
@Composable
private fun ChartsPreview() {
    GraphiteTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SpeedChart(List(60) { i -> ((sin(i / 6.0) + 1.3) * 4_000_000).toLong() })
            BarChart(List(7) { BarValue("${it + 1}", (it * 13L % 7) * 1_000_000) })
            Box { PieceMap(ByteArray(900) { i -> (if (i < 400) 3 else if (i < 420) 2 else if (i < 700) 1 else 0).toByte() }) }
            Text("—", style = GraphiteTheme.typography.caption)
        }
    }
}
