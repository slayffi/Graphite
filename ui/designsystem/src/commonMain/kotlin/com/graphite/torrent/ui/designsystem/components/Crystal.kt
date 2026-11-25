package com.graphite.torrent.ui.designsystem.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import com.graphite.torrent.ui.designsystem.icons.GraphiteIcons
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme

/**
 * Geometry of the Graphite mark: a faceted hexagonal crystal in a 100×100 box.
 * The same numbers are used by tools/generate_brand_assets.py for the launcher and Windows icons.
 */
object CrystalGeometry {
    val top = Offset(50f, 6f)
    val upperLeft = Offset(24f, 30f)
    val upperMiddle = Offset(50f, 40f)
    val upperRight = Offset(76f, 30f)
    val lowerLeft = Offset(24f, 70f)
    val lowerMiddle = Offset(50f, 80f)
    val lowerRight = Offset(76f, 70f)
    val bottom = Offset(50f, 96f)

    /** Facets with their grey, lit from the upper left. */
    val facets: List<Pair<List<Offset>, Color>> = listOf(
        listOf(top, upperLeft, upperMiddle) to Color(0xFF5C5C61),
        listOf(top, upperMiddle, upperRight) to Color(0xFF3A3A3F),
        listOf(upperLeft, lowerLeft, lowerMiddle, upperMiddle) to Color(0xFF34343A),
        listOf(upperMiddle, lowerMiddle, lowerRight, upperRight) to Color(0xFF1E1E22),
        listOf(lowerLeft, bottom, lowerMiddle) to Color(0xFF26262A),
        listOf(lowerMiddle, bottom, lowerRight) to Color(0xFF141416),
    )

    /** Edges drawn as thin light "pencil" strokes, in drawing order for the reveal animation. */
    val edges: List<Pair<Offset, Offset>> = listOf(
        top to upperLeft, top to upperRight, top to upperMiddle,
        upperLeft to upperMiddle, upperMiddle to upperRight,
        upperLeft to lowerLeft, upperMiddle to lowerMiddle, upperRight to lowerRight,
        lowerLeft to lowerMiddle, lowerMiddle to lowerRight,
        lowerLeft to bottom, lowerMiddle to bottom, lowerRight to bottom,
    )
}

private fun DrawScope.drawCrystal(reveal: Float, fillAlpha: Float, edgeColor: Color, outlineOnly: Boolean) {
    val scale = minOf(size.width, size.height) / 100f
    val dx = (size.width - 100f * scale) / 2
    val dy = (size.height - 100f * scale) / 2
    fun Offset.map() = Offset(dx + x * scale, dy + y * scale)

    if (!outlineOnly && fillAlpha > 0f) {
        for ((points, color) in CrystalGeometry.facets) {
            val path = Path().apply {
                val first = points.first().map()
                moveTo(first.x, first.y)
                points.drop(1).forEach { val p = it.map(); lineTo(p.x, p.y) }
                close()
            }
            drawPath(path, color.copy(alpha = fillAlpha))
        }
    }

    val strokeWidth = (1.2f * scale).coerceAtLeast(1f)
    val total = CrystalGeometry.edges.size
    val measure = PathMeasure()
    CrystalGeometry.edges.forEachIndexed { index, (a, b) ->
        // Edges are drawn one after another like pencil strokes.
        val local = (reveal * total - index).coerceIn(0f, 1f)
        if (local <= 0f) return@forEachIndexed
        val path = Path().apply {
            val p1 = a.map()
            val p2 = b.map()
            moveTo(p1.x, p1.y)
            lineTo(p2.x, p2.y)
        }
        val segment = Path()
        measure.setPath(path, false)
        measure.getSegment(0f, measure.length * local, segment, true)
        drawPath(segment, edgeColor, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Static Graphite crystal (facets + light edges). */
@Composable
fun CrystalLogo(modifier: Modifier = Modifier, size: Dp = 96.dp, contentDescription: String = "Graphite") {
    val edge = GraphiteTheme.colors.accent.copy(alpha = 0.75f)
    Canvas(modifier.size(size).semantics { this.contentDescription = contentDescription }) {
        drawCrystal(reveal = 1f, fillAlpha = 1f, edgeColor = edge, outlineOnly = false)
    }
}

/** Splash mark: edges appear stroke by stroke in 600 ms, then the facets fade in. */
@Composable
fun AnimatedCrystalLogo(
    modifier: Modifier = Modifier,
    size: Dp = 120.dp,
    contentDescription: String = "Graphite",
    onFinished: () -> Unit = {},
) {
    val motion = GraphiteTheme.motion
    val reveal = remember { Animatable(if (motion.reduceMotion) 1f else 0f) }
    val fill = remember { Animatable(if (motion.reduceMotion) 1f else 0f) }
    LaunchedEffect(Unit) {
        reveal.animateTo(1f, tween(motion.duration(motion.logoReveal), easing = motion.easing))
        fill.animateTo(1f, tween(motion.duration(motion.medium)))
        onFinished()
    }
    val edge = GraphiteTheme.colors.accent.copy(alpha = 0.8f)
    Canvas(modifier.size(size).semantics { this.contentDescription = contentDescription }) {
        drawCrystal(reveal = reveal.value, fillAlpha = fill.value, edgeColor = edge, outlineOnly = false)
    }
}

/** Outline-only crystal for empty states. */
@Composable
fun CrystalOutline(modifier: Modifier = Modifier, size: Dp = 96.dp) {
    val edge = GraphiteTheme.colors.textDisabled
    Canvas(modifier.size(size)) { drawCrystal(reveal = 1f, fillAlpha = 0f, edgeColor = edge, outlineOnly = true) }
}

/** Empty list: crystal outline, a short sentence and the primary action. */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxWidth().padding(GraphiteTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CrystalOutline(size = 112.dp)
        Spacer(Modifier.height(GraphiteTheme.spacing.lg))
        Text(title, style = GraphiteTheme.typography.itemTitle, color = GraphiteTheme.colors.textPrimary, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(GraphiteTheme.spacing.xs))
            Text(message, style = GraphiteTheme.typography.body, color = GraphiteTheme.colors.textSecondary, textAlign = TextAlign.Center)
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(GraphiteTheme.spacing.lg))
            GraphiteButton(actionLabel, onAction, icon = GraphiteIcons.Plus)
        }
    }
}

@Preview
@Composable
private fun CrystalPreview() {
    GraphiteTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CrystalLogo()
            AnimatedCrystalLogo()
            EmptyState("Добавьте первую загрузку", actionLabel = "Добавить", onAction = {})
        }
    }
}
