package dev.duardo.neotransfer.designsystem.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoRadius
import dev.duardo.neotransfer.designsystem.theme.NeoSpace

/** Round shortcut with its label. One [primary] per row carries the accent. In rows, prefer [QuickActionGrid]. */
@Composable
fun QuickAction(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    labelStyle: TextStyle = MaterialTheme.typography.labelMedium,
) {
    val container = if (primary) Neo.colors.accent else Neo.colors.group
    val content = if (primary) Neo.colors.onAccent else MaterialTheme.colorScheme.onSurface
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier
            .clip(RoundedCornerShape(NeoRadius.field))
            .clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(vertical = NeoSpace.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NeoSpace.s),
    ) {
        Box(
            Modifier
                .pressScale(interaction, 0.92f)
                .size(56.dp)
                .clip(CircleShape)
                .background(container)
                .then(if (primary) Modifier else Modifier.rim(CircleShape))
                .indication(interaction, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(icon), null, tint = content)
        }
        Text(label, style = labelStyle, textAlign = TextAlign.Center, maxLines = 2)
    }
}

/**
 * One line that steps its size down (to [minScale]) instead of cutting words when the user's font scale is large.
 * For labels that must stay on one line: buttons, tabs, bar titles, placeholders.
 */
@Composable
fun FittedText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current,
    textAlign: TextAlign? = null,
    minScale: Float = 0.65f,
) {
    BasicText(
        text,
        modifier,
        style = style.copy(color = color, textAlign = textAlign ?: style.textAlign),
        overflow = TextOverflow.Ellipsis,
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = style.fontSize * minScale, maxFontSize = style.fontSize, stepSize = 0.5.sp),
    )
}

/** Modal sheet with the system's surface and handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeoBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(topStart = NeoRadius.sheet, topEnd = NeoRadius.sheet)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.litEdge(shape, if (Neo.colors.isDark) Neo.colors.edge else Color.Transparent),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Neo.colors.group,
        shape = shape,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = NeoSpace.l)) {
            if (title != null) Text(
                title,
                Modifier.padding(horizontal = NeoSpace.gutter).padding(bottom = NeoSpace.s),
                style = MaterialTheme.typography.titleLarge,
            )
            content()
        }
    }
}

@Immutable
data class SummaryLine(val label: String, val value: String, val detail: String? = null)

/**
 * Label / value pairs of a review or receipt; values align right and may carry a second line. With large fonts the
 * label goes above its value so numbers and IDs keep their full width.
 */
@Composable
fun SummaryLines(lines: List<SummaryLine>, modifier: Modifier = Modifier) {
    val stacked = LocalDensity.current.fontScale > 1.25f
    Column(modifier.fillMaxWidth().padding(vertical = NeoSpace.xs)) {
        lines.forEach { line ->
            val value = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            if (stacked) {
                Column(Modifier.fillMaxWidth().padding(horizontal = NeoSpace.l, vertical = 8.dp)) {
                    Text(line.label, style = MaterialTheme.typography.bodySmall, color = muted)
                    Text(line.value, style = value)
                    line.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = NeoSpace.l, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(NeoSpace.l),
                ) {
                    Text(line.label, style = MaterialTheme.typography.bodyMedium, color = muted)
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(line.value, style = value, textAlign = TextAlign.End)
                        line.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted, textAlign = TextAlign.End) }
                    }
                }
            }
        }
    }
}

/**
 * Receipt: a header (who, how much, state) torn from its details by a perforation, the shape people share.
 */
@Composable
fun ReceiptTicket(modifier: Modifier = Modifier, header: @Composable () -> Unit, body: @Composable () -> Unit) {
    val tear = remember { mutableFloatStateOf(-1f) }
    val color = Neo.colors.group
    val edge = Neo.colors.edge
    val fade = Neo.colors.isDark
    val dash = Neo.colors.divider
    Layout(
        contents = listOf(header, body),
        modifier = modifier.fillMaxWidth().drawBehind {
            val notch = 11.dp.toPx()
            val y = tear.floatValue
            val outline = Path().apply { addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(NeoRadius.group.toPx()))) }
            val cuts = Path().apply {
                addOval(Rect(Offset(0f, y), notch))
                addOval(Rect(Offset(size.width, y), notch))
            }
            val ticket = Path.combine(PathOperation.Difference, outline, cuts)
            drawPath(ticket, color)
            if (edge.alpha > 0f) drawPath(
                ticket,
                if (fade) Brush.verticalGradient(0f to edge, (28.dp.toPx() / size.height).coerceIn(0.01f, 1f) to Color.Transparent) else SolidColor(edge),
                style = Stroke(1.dp.toPx()),
            )
            val inset = notch + NeoSpace.s.toPx()
            drawLine(
                dash, Offset(inset, y), Offset(size.width - inset, y), strokeWidth = 1.5.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
            )
        },
    ) { (top, bottom), constraints ->
        val loose = constraints.copy(minHeight = 0)
        val topPlaced = top.map { it.measure(loose) }
        val bottomPlaced = bottom.map { it.measure(loose) }
        val topHeight = topPlaced.sumOf { it.height }
        val height = topHeight + bottomPlaced.sumOf { it.height }
        tear.floatValue = topHeight.toFloat()
        layout(constraints.maxWidth, height) {
            var y = 0
            (topPlaced + bottomPlaced).forEach { it.place(0, y); y += it.height }
        }
    }
}

/** True only in preview builds, so screens with secrets can still be captured for review. */
val LocalCaptureAllowed = staticCompositionLocalOf { false }

/** Keeps secrets out of screenshots and the recents thumbnail while shown. */
@Composable
fun SecureWindow() {
    if (LocalCaptureAllowed.current) return
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.activity()?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

@Immutable
data class QuickActionItem(@param:DrawableRes val icon: Int, val label: String, val onClick: () -> Unit, val primary: Boolean = false)

/**
 * Shortcuts in rows of up to [columns]: the first and last columns sit on the edges and the rest spread evenly,
 * so every row lines up with the screen's margins. Labels keep the user's font size and wrap to two lines at word
 * breaks; when a single word would not fit, the grid drops to three columns first and only then steps the size
 * down, the same for every label, so a grid never mixes sizes.
 */
@Composable
fun QuickActionGrid(items: List<QuickActionItem>, modifier: Modifier = Modifier, columns: Int = 4) {
    val measurer = rememberTextMeasurer()
    val base = MaterialTheme.typography.labelMedium
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        fun widthOf(text: String) = with(density) { measurer.measure(text, base, maxLines = 1).size.width.toDp() }
        val longestWord = items.flatMap { it.label.split(' ') }.maxOf(::widthOf)
        val longestLabel = items.maxOf { widthOf(it.label) }
        val count = if (columns > 3 && (maxWidth / columns - NeoSpace.s) < longestWord) 3 else columns
        val room = maxWidth / count - NeoSpace.s
        val scale = (room / longestWord).coerceAtMost(1f)
        val cell = (longestLabel * scale).coerceIn(64.dp, room)
        val step = if (count > 1) (maxWidth - cell) / (count - 1) else 0.dp
        val label = base.copy(fontSize = base.fontSize * scale)
        Column(verticalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
            items.chunked(count).forEach { row ->
                Box(Modifier.fillMaxWidth()) {
                    row.forEachIndexed { index, item ->
                        QuickAction(item.icon, item.label, item.onClick, Modifier.offset(x = step * index).width(cell), item.primary, label)
                    }
                }
            }
        }
    }
}
