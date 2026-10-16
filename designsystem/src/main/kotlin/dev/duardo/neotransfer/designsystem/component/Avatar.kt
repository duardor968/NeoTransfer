package dev.duardo.neotransfer.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.format.initialsOf
import dev.duardo.neotransfer.designsystem.theme.Neo

/** Small glyph on an avatar's corner: direction of money, or a state. */
@Immutable
data class AvatarBadge(@param:DrawableRes val icon: Int, val container: Color, val content: Color)

@Composable
fun NameAvatar(name: String, modifier: Modifier = Modifier, size: Dp = 44.dp, badge: AvatarBadge? = null) {
    AvatarFrame(modifier, size, badge) {
        // The circle does not grow with the font scale, so neither do the initials (toSp undoes non-linear scaling too).
        Text(
            initialsOf(name),
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = with(LocalDensity.current) { (size * 0.36f).toSp() }, fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun IconAvatar(@DrawableRes icon: Int, modifier: Modifier = Modifier, size: Dp = 44.dp, badge: AvatarBadge? = null, tint: Color = MaterialTheme.colorScheme.onSurface) {
    AvatarFrame(modifier, size, badge) {
        Icon(painterResource(icon), null, Modifier.size(size * 0.5f), tint = tint)
    }
}

@Composable
private fun AvatarFrame(modifier: Modifier, size: Dp, badge: AvatarBadge?, content: @Composable () -> Unit) {
    Box(modifier.size(size)) {
        Box(
            Modifier.size(size).background(Neo.colors.neutralContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) { content() }
        if (badge != null) {
            val badgeSize = size * 0.42f
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 3.dp, y = 3.dp)
                    .size(badgeSize)
                    .border(2.dp, Neo.colors.group, CircleShape)
                    .background(badge.container, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(badge.icon), null, Modifier.size(badgeSize * 0.62f), tint = badge.content)
            }
        }
    }
}
