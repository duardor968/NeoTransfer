package dev.duardo.neotransfer.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoMotion
import kotlinx.coroutines.delay

/** Meaning of a state, never decoration: color plus a glyph so it reads without color too. */
enum class StatusTone { Pending, Positive, Danger, Neutral, Warning }

@Composable
fun StatusTone.content(): Color = when (this) {
    StatusTone.Pending, StatusTone.Neutral -> Neo.colors.neutral
    StatusTone.Positive -> Neo.colors.positive
    StatusTone.Danger -> Neo.colors.danger
    StatusTone.Warning -> Neo.colors.warning
}

@Composable
fun StatusTone.container(): Color = when (this) {
    StatusTone.Pending, StatusTone.Neutral -> Neo.colors.neutralContainer
    StatusTone.Positive -> Neo.colors.positiveContainer
    StatusTone.Danger -> Neo.colors.dangerContainer
    StatusTone.Warning -> Neo.colors.warningContainer
}

/** Operation state pill. [StatusTone.Pending] shows a live spinner instead of an icon. */
@Composable
fun StatusChip(label: String, tone: StatusTone, @DrawableRes icon: Int?, modifier: Modifier = Modifier) {
    val content = tone.content()
    Row(
        modifier
            .heightIn(min = 28.dp)
            .background(tone.container(), CircleShape)
            .padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (tone == StatusTone.Pending) {
            CircularProgressIndicator(Modifier.size(14.dp).padding(1.dp), color = content, strokeWidth = 1.75.dp, trackColor = content.copy(alpha = 0.2f))
        } else if (icon != null) {
            Icon(painterResource(icon), null, Modifier.size(16.dp), tint = content)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = content, maxLines = 1)
    }
}

/**
 * The state of an operation as one mark in a fixed [size] slot, so the text under it never moves. Pending: a ring
 * whose arc turns around the operation's [icon]. When the answer comes the arc closes into the full ring in the
 * state's tone; a positive end then gives way to [positive] art, which springs in while the ring fades. Danger,
 * warning and neutral keep the ring with their own glyph.
 */
@Composable
fun StatusMark(
    tone: StatusTone,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    size: Dp = 112.dp,
    positive: (@Composable () -> Unit)? = null,
) {
    val colors = Neo.colors
    val pending = tone == StatusTone.Pending
    val ink by animateColorAsState(if (pending) colors.accent else tone.content(), tween(NeoMotion.medium), label = "ink")
    val track by animateColorAsState(if (pending) colors.accentContainer else tone.container(), tween(NeoMotion.medium), label = "track")
    val glyph by animateColorAsState(if (pending) colors.onAccentContainer else tone.content(), tween(NeoMotion.medium), label = "glyph")
    val rotation = remember { Animatable(0f) }
    val sweep = remember { Animatable(if (pending) PendingSweep else 360f) }
    LaunchedEffect(pending) {
        if (pending) {
            sweep.snapTo(PendingSweep)
            rotation.animateTo(rotation.value + 360f, infiniteRepeatable(tween(1200, easing = LinearEasing)))
        } else {
            sweep.animateTo(360f, tween(NeoMotion.long - 100, easing = FastOutSlowInEasing))
        }
    }
    val art = tone == StatusTone.Positive && positive != null
    var artShown by remember { mutableStateOf(art) }
    LaunchedEffect(art) {
        if (art && !artShown) delay(NeoMotion.long - 100L)
        artShown = art
    }
    val ring by animateFloatAsState(if (artShown) 0f else 1f, tween(NeoMotion.medium), label = "ring")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = ring; scaleX = 0.9f + 0.1f * ring; scaleY = scaleX }
                .drawBehind {
                    val stroke = 4.dp.toPx()
                    val inset = stroke / 2
                    val arcSize = Size(this.size.minDimension - stroke, this.size.minDimension - stroke)
                    drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                    drawArc(ink, rotation.value - 90f, sweep.value, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxSize(0.76f).background(track, CircleShape), contentAlignment = Alignment.Center) {
                Crossfade(icon, animationSpec = tween(NeoMotion.medium), label = "glyph") {
                    Icon(painterResource(it), null, Modifier.size(size * 0.36f), tint = glyph)
                }
            }
        }
        AnimatedVisibility(
            artShown,
            enter = fadeIn(tween(NeoMotion.short)) + scaleIn(spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.8f),
            exit = fadeOut(tween(NeoMotion.short)),
        ) { positive?.invoke() }
    }
}

/** Arc of a pending ring, in degrees. */
private const val PendingSweep = 70f
