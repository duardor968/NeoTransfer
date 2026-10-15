package dev.duardo.neotransfer.designsystem.component

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoRadius

/** How far the top light of a dark surface reaches before it fades out. */
private val LightReach = 28.dp

/**
 * The rim of a raised surface (group, sheet, field, tile) in the theme's terms: in the dark a hairline of light on
 * the top edge that fades within 28 dp, the way a lit edge separates layers; in the light a plain hairline all
 * round. Apply after the clip and background.
 */
@Composable
fun Modifier.rim(shape: Shape): Modifier {
    val colors = Neo.colors
    return litEdge(shape, colors.edge, fade = colors.isDark)
}

/** [rim] with an explicit [color]: fading from the top when [fade], uniform otherwise. Transparent draws nothing. */
fun Modifier.litEdge(shape: Shape, color: Color, fade: Boolean = true): Modifier = if (color.alpha == 0f) this else drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val brush = if (fade) Brush.verticalGradient(0f to color, (LightReach.toPx() / size.height).coerceIn(0.01f, 1f) to Color.Transparent) else SolidColor(color)
    val stroke = Stroke(1.dp.toPx())
    onDrawWithContent {
        drawContent()
        drawOutline(outline, brush, style = stroke)
    }
}

/**
 * Rim of item [index] of [count] in a group built inside a lazy list: the edges between items are left out so the
 * pieces read as one [NeoGroup]. In the dark only the first item carries the top light.
 */
@Composable
internal fun Modifier.itemRim(index: Int, count: Int, radius: Dp = NeoRadius.group): Modifier {
    val colors = Neo.colors
    val color = colors.edge
    val first = index == 0
    val last = index == count - 1
    if (color.alpha == 0f || (colors.isDark && !first)) return this
    return drawWithCache {
        val width = 1.dp.toPx()
        val r = radius.toPx()
        val beyond = 4.dp.toPx()
        // Edges shared with the neighbours are pushed outside the item, where its clip cuts them.
        val top = if (first) width / 2 else -beyond
        val bottom = if (last) size.height - width / 2 else size.height + beyond
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    width / 2, top, size.width - width / 2, bottom,
                    topLeftCornerRadius = CornerRadius(if (first) r else 0f),
                    topRightCornerRadius = CornerRadius(if (first) r else 0f),
                    bottomRightCornerRadius = CornerRadius(if (last) r else 0f),
                    bottomLeftCornerRadius = CornerRadius(if (last) r else 0f),
                ),
            )
        }
        val brush = if (colors.isDark) Brush.verticalGradient(0f to color, (LightReach.toPx() / size.height).coerceIn(0.01f, 1f) to Color.Transparent) else SolidColor(color)
        onDrawWithContent {
            drawContent()
            drawPath(path, brush, style = Stroke(width))
        }
    }
}

/**
 * Soft light of [color] behind the content, strongest at ([x], [y]) as fractions of the size: an ellipse [reach]
 * times the width across that fades out before the nearest top or bottom edge, so it never shows a cut. The halo
 * behind a hero object (the brand while locked, the card in hand).
 */
fun Modifier.glow(color: Color, x: Float = 0.5f, y: Float = 0.5f, reach: Float = 0.75f): Modifier = drawBehind {
    val center = Offset(size.width * x, size.height * y)
    val across = size.width * reach
    val down = minOf(center.y, size.height - center.y)
    if (color.alpha == 0f || down <= 0f) return@drawBehind
    scale(scaleX = 1f, scaleY = down / across, pivot = center) {
        drawCircle(Brush.radialGradient(listOf(color, Color.Transparent), center, across), across, center)
    }
}

/** Gives under the finger: shrinks slightly while pressed and springs back on release. */
@Composable
fun Modifier.pressScale(interaction: InteractionSource, pressed: Float = 0.96f): Modifier {
    val down by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (down) pressed else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
    return graphicsLayer { scaleX = scale; scaleY = scale }
}
