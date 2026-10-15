package dev.duardo.neotransfer.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.theme.Neo

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

/** Status as a short line of text, for dense rows: "Enviada · 14:32". */
@Composable
fun StatusText(text: String, tone: StatusTone, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = if (tone == StatusTone.Neutral) MaterialTheme.colorScheme.onSurfaceVariant else tone.content(), maxLines = 1)
}
