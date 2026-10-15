package dev.duardo.neotransfer.scanner

import androidx.annotation.DrawableRes
import androidx.camera.view.CameraController
import androidx.camera.view.TapToFocusInfo
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** The camera UI is always dark, so problems use the dark-scheme error tones whatever the host theme is. */
private val ProblemColor = Color(0xFFFFB4AB)
private val OnProblemColor = Color(0xFF690005)
private val ChipText = Color(0xFF16181A)

/**
 * Dim mask with a hole, the four corner brackets and number highlights. The frame rests in the centre and,
 * like Telegram's scanner, springs onto the real corners of a detected code.
 */
@Composable
internal fun ScanOverlay(
    state: QrFlow.State,
    rest: Quad,
    showFrame: Boolean,
    boxes: List<Box>,
    accent: Color,
    focus: TapToFocusInfo?,
) {
    val outset = with(LocalDensity.current) { 14.dp.toPx() }
    val target = when (state) {
        QrFlow.State.Idle -> rest
        is QrFlow.State.Seeking -> state.quad.outset(outset)
        is QrFlow.State.Accepted -> state.quad.outset(outset)
        is QrFlow.State.Rejected -> state.quad.outset(outset)
    }
    val corners = remember { List(4) { Animatable(Offset.Zero, Offset.VectorConverter) } }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(target) {
        val center = target.bounds().center
        target.corners.forEachIndexed { index, point ->
            launch {
                val to = Offset(point.x, point.y)
                // First appearance: the frame shrinks in from a little larger.
                if (!placed) corners[index].snapTo(to + (to - Offset(center.x, center.y)) * 0.25f)
                corners[index].animateTo(to, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow))
            }
        }
        placed = true
    }
    val frameColor by animateColorAsState(
        when (state) {
            is QrFlow.State.Accepted -> accent
            is QrFlow.State.Rejected -> ProblemColor
            else -> Color.White
        },
        label = "frameColor",
    )
    val dim by animateFloatAsState(
        when {
            !showFrame -> 0f
            state is QrFlow.State.Accepted -> 0.66f
            state is QrFlow.State.Rejected -> 0.58f
            else -> 0.45f
        },
        label = "dim",
    )
    val frameAlpha by animateFloatAsState(if (showFrame) 1f else 0f, label = "frameAlpha")

    Canvas(Modifier.fillMaxSize()) {
        val quad = corners.map { it.value }
        if (dim > 0f) {
            val mask = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addPath(roundedQuad(quad, 18.dp.toPx()))
            }
            drawPath(mask, Color.Black.copy(alpha = dim))
        }
        if (frameAlpha > 0f) drawBrackets(quad, frameColor.copy(alpha = frameAlpha), 4.dp.toPx(), 34.dp.toPx(), 18.dp.toPx())
        val pad = 6.dp.toPx()
        boxes.forEach { box ->
            val topLeft = Offset(box.left - pad, box.top - pad)
            val boxSize = Size(box.width + 2 * pad, box.height + 2 * pad)
            drawRoundRect(accent.copy(alpha = 0.2f), topLeft, boxSize, CornerRadius(8.dp.toPx()))
            drawRoundRect(accent, topLeft, boxSize, CornerRadius(8.dp.toPx()), style = Stroke(2.dp.toPx()))
        }
    }
    FocusRing(focus, accent)
}

private fun along(from: Offset, to: Offset, distance: Float): Offset {
    val delta = to - from
    val length = delta.getDistance()
    return if (length == 0f) from else from + delta * (min(distance, length / 2f) / length)
}

private fun roundedQuad(corners: List<Offset>, radius: Float) = Path().apply {
    corners.indices.forEach { i ->
        val corner = corners[i]
        val start = along(corner, corners[(i + 3) % 4], radius)
        val end = along(corner, corners[(i + 1) % 4], radius)
        if (i == 0) moveTo(start.x, start.y) else lineTo(start.x, start.y)
        quadraticTo(corner.x, corner.y, end.x, end.y)
    }
    close()
}

private fun DrawScope.drawBrackets(corners: List<Offset>, color: Color, stroke: Float, length: Float, radius: Float) {
    corners.indices.forEach { i ->
        val corner = corners[i]
        val previous = corners[(i + 3) % 4]
        val next = corners[(i + 1) % 4]
        val reach = min(length, min((previous - corner).getDistance(), (next - corner).getDistance()) * 0.3f)
        val path = Path().apply {
            along(corner, previous, reach).let { moveTo(it.x, it.y) }
            along(corner, previous, min(radius, reach)).let { lineTo(it.x, it.y) }
            along(corner, next, min(radius, reach)).let { quadraticTo(corner.x, corner.y, it.x, it.y) }
            along(corner, next, reach).let { lineTo(it.x, it.y) }
        }
        drawPath(path, color, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun FocusRing(info: TapToFocusInfo?, accent: Color) {
    val point = info?.tapPoint ?: return
    val started = info.focusState == CameraController.TAP_TO_FOCUS_STARTED
    val appear = remember(point) { Animatable(0f) }
    LaunchedEffect(point) { appear.animateTo(1f, tween(220)) }
    val alpha by animateFloatAsState(
        if (started) 1f else 0f,
        if (started) tween(0) else tween(500, delayMillis = 450),
        label = "focus",
    )
    val color = if (info.focusState == CameraController.TAP_TO_FOCUS_FOCUSED) accent else Color.White
    Canvas(Modifier.fillMaxSize()) {
        val radius = 44.dp.toPx() + (30.dp.toPx() - 44.dp.toPx()) * appear.value
        drawCircle(color.copy(alpha = alpha), radius, Offset(point.x, point.y), style = Stroke(2.dp.toPx()))
    }
}

@Composable
internal fun LabelText(label: Label) {
    val modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    if (label.problem) {
        Text(
            label.text,
            color = OnProblemColor,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            textAlign = TextAlign.Center,
            modifier = modifier
                .background(ProblemColor, RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    } else {
        Text(
            label.text,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 12f)),
            textAlign = TextAlign.Center,
            modifier = modifier,
        )
    }
}

@Composable
internal fun RoundButton(
    @DrawableRes icon: Int,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 56.dp,
    lit: Boolean = false,
    attention: Color? = null,
) {
    Box(
        modifier
            .size(size)
            .background(if (lit) Color.White else Color.Black.copy(alpha = 0.45f), CircleShape)
            .then(if (attention != null) Modifier.border(2.dp, attention, CircleShape) else Modifier)
            .clickable(onClickLabel = description, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), null, tint = if (lit) Color.Black else Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
internal fun Controls(photo: Boolean, camera: CameraSession?, dark: Boolean, onGallery: () -> Unit, onCamera: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(R.drawable.scanner_ic_photo_library, "Galería", onGallery)
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (!photo && camera != null) ZoomChips(camera.zoom, camera.maxZoom, camera::chooseZoom)
        }
        when {
            photo -> RoundButton(R.drawable.scanner_ic_photo_camera, "Cámara", onCamera)
            camera != null && camera.hasFlash -> RoundButton(
                icon = if (camera.torch) R.drawable.scanner_ic_flashlight_on else R.drawable.scanner_ic_flashlight_off,
                description = "Linterna",
                onClick = camera::toggleTorch,
                lit = camera.torch,
                attention = MaterialTheme.colorScheme.primary.takeIf { dark && !camera.torch },
            )
            else -> Spacer(Modifier.size(56.dp))
        }
    }
}

@Composable
private fun ZoomChips(zoom: Float, maxZoom: Float, onChoose: (Float) -> Unit) {
    val presets = listOf(1f, 2f, 4f).filter { it <= maxZoom + 0.01f }
    if (presets.size < 2) return
    val selected = presets.last { it <= zoom + 0.05f }
    Row(
        Modifier.background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        presets.forEach { preset ->
            val active = preset == selected
            Box(
                Modifier
                    .size(40.dp)
                    .background(if (active) Color.White.copy(alpha = 0.18f) else Color.Transparent, CircleShape)
                    .clickable(onClickLabel = "Zoom ${zoomLabel(preset)}", role = Role.Button) { onChoose(preset) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    zoomLabel(if (active) zoom else preset),
                    color = if (active) Color.White else Color.White.copy(alpha = 0.75f),
                    fontSize = if (active) 13.sp else 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
    }
}

private fun zoomLabel(ratio: Float): String =
    if (abs(ratio - ratio.roundToInt()) < 0.05f) "${ratio.roundToInt()}×"
    else String.format(Locale.forLanguageTag("es"), "%.1f×", ratio)

@Composable
internal fun NumberTray(numbers: List<Shown>, onPick: (Shown) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        numbers.take(3).forEach { number ->
            Surface(
                onClick = { onPick(number) },
                shape = RoundedCornerShape(20.dp),
                color = Color.White,
                contentColor = ChipText,
                modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(if (number.kind == NumberKind.CARD) R.drawable.scanner_ic_credit_card else R.drawable.scanner_ic_smartphone),
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            if (number.kind == NumberKind.CARD) "Tarjeta" else "Móvil",
                            style = MaterialTheme.typography.labelMedium,
                            color = ChipText.copy(alpha = 0.6f),
                        )
                        Text(
                            NumberReader.format(number.digits),
                            style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun PermissionPrompt(blocked: Boolean, onAllow: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(painterResource(R.drawable.scanner_ic_photo_camera), null, tint = Color.White, modifier = Modifier.size(40.dp))
        Text(
            "Permite el acceso a la cámara para escanear",
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        FilledTonalButton(onClick = if (blocked) onSettings else onAllow) {
            Text(if (blocked) "Abrir ajustes" else "Permitir")
        }
    }
}

/** Confirm or correct a recognised number before it reaches the host. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NumberSheet(kind: NumberKind, digits: String, onDismiss: () -> Unit, onUse: (String) -> Unit) {
    val length = if (kind == NumberKind.CARD) 16 else 8
    var value by remember(digits) { mutableStateOf(digits) }
    val valid = if (kind == NumberKind.CARD) value.length == 16 && NumberReader.luhn(value) else NumberReader.isPhone(value)
    val wrong = value.length == length && !valid
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (kind == NumberKind.CARD) "Tarjeta" else "Móvil", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = value,
                onValueChange = { input ->
                    value = (if (kind == NumberKind.PHONE) NumberReader.phoneDigits(input) else input.filter(Char::isDigit)).take(length)
                },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(fontSize = 22.sp, fontFeatureSettings = "tnum", letterSpacing = 0.5.sp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onUse(value) }),
                visualTransformation = GroupsOfFour,
                isError = wrong,
                supportingText = if (wrong) ({ Text("Revisa el número") }) else null,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { onUse(value) }, enabled = valid, modifier = Modifier.fillMaxWidth()) { Text("Usar") }
        }
    }
}

/** Shows "9204 1299 5123 4567" while the field holds plain digits. */
private object GroupsOfFour : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val shown = raw.chunked(4).joinToString(" ")
        return TransformedText(AnnotatedString(shown), object : OffsetMapping {
            override fun originalToTransformed(offset: Int) =
                (if (offset == 0) 0 else offset + (offset - 1) / 4).coerceAtMost(shown.length)

            override fun transformedToOriginal(offset: Int) = (offset - offset / 5).coerceIn(0, raw.length)
        })
    }
}
