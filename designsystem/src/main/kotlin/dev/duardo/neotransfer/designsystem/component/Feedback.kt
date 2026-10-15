package dev.duardo.neotransfer.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoMotion
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import kotlin.math.ceil

/** Empty or blocked state: what is missing and the one action that fixes it. */
@Composable
fun EmptyState(
    @DrawableRes icon: Int,
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
    busy: Boolean = false,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = NeoSpace.xxl, vertical = NeoSpace.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NeoSpace.s),
    ) {
        Box(Modifier.size(64.dp).background(Neo.colors.neutralContainer, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(title, Modifier.padding(top = NeoSpace.s), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null && onAction != null) {
            NeoButton(action, onAction, Modifier.padding(top = NeoSpace.m), style = NeoButtonStyle.Tonal, fill = false, loading = busy)
        }
    }
}

/**
 * Confirmation for what cannot be undone, or for money that may go twice: a sheet from the bottom, like every other
 * choice in the app, stating the consequence once. The confirming action carries its verb and object.
 */
@Composable
fun NeoConfirmSheet(
    title: String,
    text: String,
    confirm: String,
    dismiss: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false,
) {
    NeoBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = NeoSpace.gutter)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(
                text, Modifier.padding(top = NeoSpace.s),
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            NeoButton(confirm, onConfirm, Modifier.padding(top = NeoSpace.xxl), style = if (destructive) NeoButtonStyle.Danger else NeoButtonStyle.Primary)
            NeoTextButton(dismiss, onDismiss, Modifier.fillMaxWidth().padding(top = NeoSpace.xs), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Turns a glyph (refresh, sync) round and round while [active]; when it stops, it finishes the turn instead of
 * snapping back. The glyph stays readable at every frame, unlike a thin arc.
 */
@Composable
fun Modifier.spinning(active: Boolean): Modifier {
    val angle = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            angle.animateTo(angle.value + 360f, infiniteRepeatable(tween(900, easing = LinearEasing)))
        } else {
            val rest = ceil(angle.value / 360f) * 360f
            if (rest != angle.value) angle.animateTo(rest, tween(NeoMotion.long, easing = NeoMotion.emphasized))
            angle.snapTo(0f)
        }
    }
    return graphicsLayer { rotationZ = angle.value }
}

/** Placeholder that copies the final layout while content loads. */
@Composable
fun Modifier.skeleton(shape: Shape = RoundedCornerShape(6.dp)): Modifier {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    val color = Neo.colors.skeleton
    return clip(shape).drawWithContent { drawRect(color, alpha = alpha) }
}
