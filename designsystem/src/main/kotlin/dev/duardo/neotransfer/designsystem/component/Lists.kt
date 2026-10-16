package dev.duardo.neotransfer.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoRadius
import dev.duardo.neotransfer.designsystem.theme.NeoSpace

/** Rows that belong together on one rounded surface. Dividers are inset to the text column. */
@Composable
fun NeoGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(NeoRadius.group)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Neo.colors.group)
            .rim(shape),
        content = content,
    )
}

/** Divider between rows of a [NeoGroup]; [inset] aligns it with the row text. */
@Composable
fun GroupDivider(inset: Dp = RowTextInset) {
    HorizontalDivider(Modifier.padding(start = inset), thickness = 1.dp, color = Neo.colors.divider)
}

/** Start of the text column in a [NeoRow] with a 44 dp leading element. */
val RowTextInset: Dp = NeoSpace.l + 44.dp + NeoSpace.m

/** Shape of one item in a lazily built group: only the outer corners are rounded. */
fun groupItemShape(index: Int, count: Int): Shape {
    val r = NeoRadius.group
    return when {
        count == 1 -> RoundedCornerShape(r)
        index == 0 -> RoundedCornerShape(topStart = r, topEnd = r)
        index == count - 1 -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
        else -> RoundedCornerShape(0.dp)
    }
}

/** Surface of item [index] of [count] in a group built inside a lazy list: one [NeoGroup] cut in rows. */
@Composable
fun Modifier.groupItem(index: Int, count: Int): Modifier {
    val shape = groupItemShape(index, count)
    return clip(shape).background(Neo.colors.group).itemRim(index, count)
}

/**
 * The single row of the system: leading slot (44 dp), title, optional supporting line, trailing slot.
 * Used by every list so spacing, ripple and alignment are identical everywhere. With [trailingBelowWhenLarge],
 * large font scales move the trailing element under the text so names keep the width.
 */
@Composable
fun NeoRow(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    titleColor: Color = Color.Unspecified,
    supportingContent: (@Composable () -> Unit)? = null,
    trailingBelowWhenLarge: Boolean = false,
) {
    val trailingBelow = trailingBelowWhenLarge && LocalDensity.current.fontScale >= 1.3f
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 64.dp)
            .padding(horizontal = NeoSpace.l, vertical = NeoSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NeoSpace.m),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // One line: a name or a card number steps down before it ellipsizes, and never breaks inside a group.
            FittedText(
                title,
                MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                color = if (titleColor.isSpecified) titleColor else LocalContentColor.current,
                minScale = 0.8f,
            )
            when {
                supportingContent != null -> supportingContent()
                supporting != null -> Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (trailingBelow) trailing?.invoke()
        }
        if (!trailingBelow) trailing?.invoke()
    }
}

/**
 * For an icon or text button that closes a row or a header: moves it by its own inner padding so the glyph, not the
 * touch target, lines up with the edge the text of the other rows ends on.
 */
fun Modifier.flushEnd(): Modifier = offset(x = 12.dp)

/** The same for text buttons that open a block: their glyph lines up with the fields above them. */
fun Modifier.flushStart(): Modifier = offset(x = (-12).dp)

/** Title above a group, aligned with the group's edge; optional action on the right. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleMedium,
        )
        if (action != null && onAction != null) NeoTextButton(action, onAction, Modifier.flushEnd())
    }
}
