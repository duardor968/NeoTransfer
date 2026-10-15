package dev.duardo.neotransfer.designsystem.card

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.R
import dev.duardo.neotransfer.designsystem.component.dashedOutline
import dev.duardo.neotransfer.designsystem.theme.NeoMotion
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import kotlin.math.absoluteValue

/**
 * Horizontal wallet of cards. Neighbours peek from the edges and the card in focus sits flat while the others
 * tilt away, so the stack reads as physical objects without decoration.
 */
@Composable
fun CardCarousel(
    state: PagerState,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 28.dp,
    pageSpacing: Dp = 12.dp,
    key: ((Int) -> Any)? = null,
    page: @Composable (index: Int) -> Unit,
) {
    HorizontalPager(
        state = state,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 18.dp),
        pageSpacing = pageSpacing,
        key = key,
    ) { index ->
        Box(
            Modifier.graphicsLayer {
                val offset = (state.currentPage - index + state.currentPageOffsetFraction).coerceIn(-1f, 1f)
                val scale = 1f - 0.06f * offset.absoluteValue
                scaleX = scale
                scaleY = scale
                rotationY = offset * 9f
                cameraDistance = 14f * density
            },
        ) { page(index) }
    }
}

/** Position within the wallet; the current dot stretches into a pill. */
@Composable
fun PagerDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    if (count < 2) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { index ->
            val selected = index == current
            val width by animateDpAsState(if (selected) 18.dp else 6.dp, tween(NeoMotion.medium, easing = NeoMotion.emphasized), label = "dot")
            val color by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                tween(NeoMotion.medium), label = "dotColor",
            )
            Box(Modifier.height(6.dp).width(width).background(color, CircleShape))
        }
    }
}

/** Last slot of the wallet: same footprint as a card, dashed, to add another one. */
@Composable
fun AddCardSlot(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val radius = maxWidth / CardAspectRatio * 0.06f
        Column(
            Modifier
                .fillMaxWidth()
                .aspectRatio(CardAspectRatio)
                .clip(CardShape)
                .dashedOutline(outline, radius)
                .clickable(role = Role.Button, onClick = onClick),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.size(48.dp).border(1.5.dp, outline, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_add), null, tint = MaterialTheme.colorScheme.onSurface)
            }
            Text(label, Modifier.padding(top = NeoSpace.m), style = MaterialTheme.typography.titleSmall)
        }
    }
}
