package dev.duardo.neotransfer

import android.graphics.RectF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.Locale
import kotlin.math.roundToInt

enum class ScanMode { QR, MOBILE, CARD }

/** Number results are suggestions until the person taps, edits and accepts one. */
@Composable
fun QrCamera(
    modifier: Modifier,
    paused: Boolean,
    onCode: (String) -> Unit,
    onError: (String) -> Unit,
    mode: ScanMode = ScanMode.QR,
    onNumber: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val code by rememberUpdatedState(onCode)
    val error by rememberUpdatedState(onError)
    val number by rememberUpdatedState(onNumber)
    val scanner = remember(context, owner) { CameraScanView(context, owner) }
    var targets by remember { mutableStateOf<List<ScanTarget>>(emptyList()) }
    var selected by remember { mutableStateOf<ScanTarget?>(null) }
    var edited by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var gallery by remember { mutableStateOf(false) }
    var galleryLoading by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            gallery = true
            scanner.openGallery(uri)
        }
    }

    LaunchedEffect(mode) {
        targets = emptyList()
        selected = null
        scanner.setMode(mode)
    }
    DisposableEffect(scanner) { onDispose { scanner.close() } }

    Box(modifier.background(Color.Black)) {
        AndroidView(
            factory = { scanner },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                view.paused = paused || selected != null
                view.onCode = { code(it) }
                view.onError = { error(it) }
                view.onTargets = { targets = it }
                view.onGalleryLoading = { galleryLoading = it }
                view.onCameraFeatures = { flash, flashOn, ratio ->
                    cameraReady = true; hasFlash = flash; torch = flashOn; zoom = ratio
                }
                view.refreshCamera()
            },
        )
        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(gallery) {
                    detectTapGestures { tap ->
                        if (!gallery) scanner.focus(tap.x, tap.y)
                    }
                }
                .pointerInput(gallery) {
                    if (!gallery) detectTransformGestures { _, _, scale, _ -> scanner.zoomBy(scale) }
                },
        ) {
            val accent = Color(0xFF3DDC97)
            if (targets.isEmpty() && !gallery) {
                val side = minOf(size.width * .64f, size.height * .42f)
                val left = (size.width - side) / 2f
                val top = (size.height - side) / 2f
                drawTrackingCorners(RectF(left, top, left + side, top + side), accent.copy(alpha = .56f), 2.dp.toPx())
            }
            targets.forEach { target ->
                val rect = target.rect
                drawTrackingCorners(rect, accent.copy(alpha = if (target.stable) 1f else .62f),
                    if (target.stable) 3.dp.toPx() else 2.dp.toPx())
            }
        }
        if (mode != ScanMode.QR) targets.filter { it.stable }.forEach { candidate ->
            val margin = 12f * density.density
            Box(Modifier
                .offset { IntOffset((candidate.rect.left - margin).roundToInt(), (candidate.rect.top - margin).roundToInt()) }
                .width(((candidate.rect.width() + 2f * margin) / density.density).dp)
                .height(((candidate.rect.height() + 2f * margin) / density.density).dp)
                .semantics { contentDescription = "Revisar número ${candidate.value}" }
                .clickable {
                    selected = candidate
                    edited = candidate.value
                    invalid = false
                })
        }
        if (galleryLoading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (gallery) {
                FilledTonalButton(onClick = { gallery = false; scanner.showCamera() }) { Text("Cámara") }
            } else if (cameraReady) {
                if (hasFlash) FilledTonalButton(onClick = { scanner.toggleTorch() }) { Text(if (torch) "Apagar luz" else "Linterna") }
                FilledTonalButton(onClick = { scanner.nextZoom() }) { Text(String.format(Locale.ROOT, "%.1f×", zoom)) }
            }
            Spacer(Modifier.weight(1f))
            FilledTonalButton(onClick = {
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("Galería") }
        }
    }

    if (selected != null) {
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(if (mode == ScanMode.CARD) "Número de tarjeta" else "Número de móvil") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = edited,
                        onValueChange = { edited = it; invalid = false },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = if (mode == ScanMode.MOBILE) KeyboardType.Phone else KeyboardType.Number),
                        isError = invalid,
                        label = { Text("Revisar número") },
                    )
                    if (invalid) Text("Número no válido", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val accepted = normalizeScanNumber(edited, mode)
                    if (accepted == null) invalid = true else {
                        selected = null
                        number(accepted)
                    }
                }) { Text("Usar número") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Cancelar") } },
        )
    }
}

private fun DrawScope.drawTrackingCorners(rect: RectF, color: Color, stroke: Float) {
    val segment = minOf(rect.width(), rect.height()) * .22f
    if (segment <= 0f) return
    listOf(
        Offset(rect.left, rect.top) to Offset(rect.left + segment, rect.top),
        Offset(rect.left, rect.top) to Offset(rect.left, rect.top + segment),
        Offset(rect.right, rect.top) to Offset(rect.right - segment, rect.top),
        Offset(rect.right, rect.top) to Offset(rect.right, rect.top + segment),
        Offset(rect.left, rect.bottom) to Offset(rect.left + segment, rect.bottom),
        Offset(rect.left, rect.bottom) to Offset(rect.left, rect.bottom - segment),
        Offset(rect.right, rect.bottom) to Offset(rect.right - segment, rect.bottom),
        Offset(rect.right, rect.bottom) to Offset(rect.right, rect.bottom - segment),
    ).forEach { (from, to) -> drawLine(color, from, to, strokeWidth = stroke, cap = StrokeCap.Round) }
}

internal fun normalizeScanNumber(value: String, mode: ScanMode): String? {
    val compact = value.trim().replace(Regex("[\\s-]"), "")
    return when (mode) {
        ScanMode.MOBILE -> when {
            compact.matches(Regex("[0-9]{8}")) -> compact
            compact.matches(Regex("\\+?53[0-9]{8}")) -> compact.takeLast(8)
            else -> null
        }
        ScanMode.CARD -> compact.takeIf { it.matches(Regex("[0-9]{16}")) }
        ScanMode.QR -> null
    }
}
