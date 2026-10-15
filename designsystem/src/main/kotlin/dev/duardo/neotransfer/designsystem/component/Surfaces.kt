package dev.duardo.neotransfer.designsystem.component

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoRadius
import dev.duardo.neotransfer.designsystem.theme.NeoSpace

/** Round shortcut with its label. One [primary] per row carries the accent. */
@Composable
fun QuickAction(@DrawableRes icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    val container = if (primary) Neo.colors.accent else Neo.colors.group
    val content = if (primary) Neo.colors.onAccent else MaterialTheme.colorScheme.onSurface
    Column(
        modifier.clip(RoundedCornerShape(NeoRadius.field)).clickable(onClick = onClick).padding(vertical = NeoSpace.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NeoSpace.s),
    ) {
        Box(Modifier.size(56.dp).background(container, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, tint = content)
        }
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
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
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Neo.colors.group,
        shape = RoundedCornerShape(topStart = NeoRadius.sheet, topEnd = NeoRadius.sheet),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = NeoSpace.l)) {
            if (title != null) Text(
                title,
                Modifier.padding(horizontal = NeoSpace.xxl).padding(bottom = NeoSpace.s),
                style = MaterialTheme.typography.titleLarge,
            )
            content()
        }
    }
}

@Immutable
data class SummaryLine(val label: String, val value: String, val detail: String? = null)

/** Label / value pairs of a review or receipt; values align right and may carry a second line. */
@Composable
fun SummaryLines(lines: List<SummaryLine>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = NeoSpace.xs)) {
        lines.forEach { line ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = NeoSpace.l, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(NeoSpace.l),
            ) {
                Text(line.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Text(line.value, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), textAlign = TextAlign.End)
                    line.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End) }
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
            drawPath(Path.combine(PathOperation.Difference, outline, cuts), color)
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
