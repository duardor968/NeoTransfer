package dev.duardo.neotransfer.scanner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.view.ViewGroup
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/** Time the frame stays locked on an accepted QR before the result goes out, so the person sees it land. */
private const val HOLD_MS = 450L

/**
 * Full-screen camera scanner: QR codes (ML Kit, ZXing for inverted codes) and Cuban card or mobile numbers
 * (printed or handwritten). Torch, zoom buttons and pinch, tap to focus, gallery. An accepted QR is delivered
 * once the frame has landed on it; a rejected one shows [classifyQr]'s reason and scanning goes on. Numbers are
 * proposed after several matching frames and delivered once the person confirms or corrects them.
 * It asks for the camera permission itself when missing; the gallery works without it.
 */
@Composable
fun ScannerView(
    mode: ScanMode,
    classifyQr: (String) -> QrVerdict,
    onResult: (ScanResult) -> Unit,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val classify by rememberUpdatedState(classifyQr)
    val deliver by rememberUpdatedState(onResult)
    val currentMode by rememberUpdatedState(mode)

    val flow = remember { QrFlow() }
    val tracker = remember { NumberTracker() }
    val announced = remember { mutableSetOf<String>() }
    var qr by remember { mutableStateOf(flow.state) }
    var tracked by remember { mutableStateOf(emptyList<TrackedNumber>()) }
    var dark by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf<Photo?>(null) }
    var photoJob by remember { mutableStateOf<Job?>(null) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    fun onVerdict(verdict: QrVerdict?) {
        qr = flow.state
        when (verdict) {
            null -> Unit
            QrVerdict.Accepted -> {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                scope.launch {
                    delay(HOLD_MS)
                    val accepted = flow.state as? QrFlow.State.Accepted ?: return@launch
                    flow.delivered(SystemClock.elapsedRealtime())
                    qr = flow.state
                    deliver(ScanResult.Qr(accepted.raw))
                }
            }
            is QrVerdict.Rejected -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
        }
    }

    val session = remember {
        CameraSession(context) { frame ->
            val now = SystemClock.elapsedRealtime()
            dark = frame.dark
            if (currentMode.readsQr) onVerdict(flow.onFrame(frame.qrs, now, needed = 2, classify))
            frame.numbers?.let { hits ->
                tracked = tracker.update(hits, now)
                if (tracked.any { announced.add(it.kind.name + it.digits) }) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
        }
    }

    var permitted by remember { mutableStateOf(context.cameraPermitted()) }
    var refused by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permitted = granted
        refused = !granted
    }
    LaunchedEffect(Unit) { if (!permitted) permission.launch(Manifest.permission.CAMERA) }
    LifecycleResumeEffect(Unit) {
        permitted = context.cameraPermitted()
        onPauseOrDispose { }
    }

    // Declared first so it is disposed last, after the camera is unbound.
    DisposableEffect(session) { onDispose { session.close() } }
    DisposableEffect(session, lifecycleOwner, permitted) {
        if (permitted) session.bind(lifecycleOwner)
        onDispose { if (permitted) session.unbind() }
    }
    LaunchedEffect(mode) {
        session.setMode(mode)
        flow.reset()
        tracker.clear()
        qr = flow.state
        tracked = emptyList()
    }
    SideEffect { session.analyzer.paused = photo != null || editing != null }

    fun closePhoto() {
        photoJob?.cancel()
        photo = null
        flow.reset()
        qr = flow.state
    }

    fun openPhoto(uri: Uri) {
        photoJob?.cancel()
        flow.reset()
        tracker.clear()
        qr = flow.state
        tracked = emptyList()
        photo = Photo(loading = true)
        val scanMode = currentMode
        photoJob = scope.launch {
            val bitmap = try {
                decodeImage(context, uri)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                photo = Photo(message = "No se pudo abrir la imagen")
                return@launch
            }
            val image = bitmap.asImageBitmap()
            photo = Photo(image = image, loading = true)
            val found = try {
                withContext(Dispatchers.Default) { StillScanner(scanMode).use { it.scan(bitmap.toLuma()) } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                photo = Photo(image = image, message = "No se pudo analizar la imagen")
                return@launch
            }
            val fit = Fit(bitmap.width, bitmap.height, viewSize)
            val qrs = found.qrs.map { it.copy(quad = it.quad.map(fit::map)) }
            val numbers = found.numbers.map { it.copy(box = fit.map(it.box)) }
            photo = Photo(image = image, numbers = numbers, message = if (qrs.isEmpty() && numbers.isEmpty()) nothingFound(scanMode) else null)
            if (qrs.isNotEmpty()) onVerdict(flow.onFrame(qrs, SystemClock.elapsedRealtime(), needed = 1, classify))
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) openPhoto(uri)
    }

    val currentPhoto = photo
    val shown = currentPhoto?.numbers?.map { Shown(it.kind, it.digits, it.box) }
        ?: tracked.map { Shown(it.kind, it.digits, it.box.takeIf { _ -> it.visible }) }
    val cameraUsable = permitted && !session.failed
    val accent = MaterialTheme.colorScheme.primary

    fun edit(number: Shown) {
        val frozen = if (photo == null) session.preview.bitmap?.asImageBitmap() else null
        editing = Editing(number.kind, number.digits, frozen)
    }

    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black).onSizeChanged { viewSize = it }) {
        val frameSide = min(maxWidth, maxHeight) * 0.68f
        val sidePx = with(density) { frameSide.toPx() }

        if (permitted) {
            AndroidView(
                factory = { session.preview.also { (it.parent as? ViewGroup)?.removeView(it) } },
                modifier = Modifier.fillMaxSize(),
            )
        }
        editing?.frozen?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        if (currentPhoto != null) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                currentPhoto.image?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            }
        }
        Scrims()

        if (viewSize != IntSize.Zero) {
            val showFrame = mode.readsQr && if (currentPhoto != null) qr != QrFlow.State.Idle else cameraUsable
            ScanOverlay(
                state = qr,
                rest = restQuad(viewSize, sidePx),
                showFrame = showFrame,
                boxes = shown.mapNotNull { it.box },
                accent = accent,
                focus = session.focus.takeIf { currentPhoto == null },
            )
        }

        shown.forEach { number ->
            val box = number.box ?: return@forEach
            val margin = with(density) { 10.dp.toPx() }
            Box(
                Modifier
                    .offset { IntOffset((box.left - margin).roundToInt(), (box.top - margin).roundToInt()) }
                    .size(with(density) { (box.width + 2 * margin).toDp() }, with(density) { (box.height + 2 * margin).toDp() })
                    .semantics { contentDescription = number.description }
                    .clickable { edit(number) },
            )
        }

        if (!permitted && currentPhoto == null) {
            PermissionPrompt(
                blocked = refused && activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false,
                onAllow = { permission.launch(Manifest.permission.CAMERA) },
                onSettings = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    )
                },
                modifier = Modifier.align(Alignment.Center),
            )
        } else if (permitted && session.failed && currentPhoto == null) {
            Text(
                "No se pudo abrir la cámara",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }

        val label = when {
            currentPhoto?.message != null -> Label(currentPhoto.message, problem = true)
            qr is QrFlow.State.Rejected -> Label((qr as QrFlow.State.Rejected).reason, problem = true)
            currentPhoto != null || !cameraUsable || qr is QrFlow.State.Accepted || shown.isNotEmpty() -> null
            else -> Label(hint(mode), problem = false)
        }
        val labelPlace = if (mode.readsQr) {
            Modifier.align(Alignment.Center).offset(y = -(frameSide / 2 + 44.dp))
        } else {
            Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(top = 72.dp)
        }
        Crossfade(label, labelPlace.padding(horizontal = 24.dp), label = "label") { current ->
            if (current != null) LabelText(current)
        }

        if (currentPhoto?.loading == true) {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
        }

        if (onClose != null) {
            RoundButton(
                icon = R.drawable.scanner_ic_close,
                description = "Cerrar",
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(12.dp),
                size = 48.dp,
            )
        }

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AnimatedVisibility(shown.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                NumberTray(shown, onPick = ::edit, modifier = Modifier.padding(bottom = 20.dp))
            }
            Controls(
                photo = currentPhoto != null,
                camera = session.takeIf { cameraUsable },
                dark = dark,
                onGallery = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onCamera = ::closePhoto,
            )
        }
    }

    editing?.let { current ->
        NumberSheet(
            kind = current.kind,
            digits = current.digits,
            onDismiss = { editing = null },
            onUse = { digits ->
                editing = null
                deliver(if (current.kind == NumberKind.CARD) ScanResult.CardNumber(digits) else ScanResult.PhoneNumber(digits))
            },
        )
    }
}

private class Photo(
    val image: ImageBitmap? = null,
    val numbers: List<NumberHit> = emptyList(),
    val loading: Boolean = false,
    val message: String? = null,
)

private class Editing(val kind: NumberKind, val digits: String, val frozen: ImageBitmap?)

internal class Shown(val kind: NumberKind, val digits: String, val box: Box?) {
    val description get() = (if (kind == NumberKind.CARD) "Tarjeta " else "Móvil ") + NumberReader.format(digits)
}

internal data class Label(val text: String, val problem: Boolean)

/** Maps image pixels to the view where the image is drawn with ContentScale.Fit. */
private class Fit(width: Int, height: Int, view: IntSize) {
    private val scale = min(view.width / width.toFloat(), view.height / height.toFloat())
    private val dx = (view.width - width * scale) / 2f
    private val dy = (view.height - height * scale) / 2f
    fun map(point: Pt) = Pt(dx + point.x * scale, dy + point.y * scale)
    fun map(box: Box) = Box(dx + box.left * scale, dy + box.top * scale, dx + box.right * scale, dy + box.bottom * scale)
}

private fun restQuad(view: IntSize, side: Float): Quad {
    val cx = view.width / 2f
    val cy = view.height / 2f
    return Quad.of(Box(cx - side / 2f, cy - side / 2f, cx + side / 2f, cy + side / 2f))
}

private fun hint(mode: ScanMode) = when (mode) {
    ScanMode.QR -> "Apunta al código QR"
    ScanMode.NUMBERS -> "Apunta a un número de tarjeta o de móvil"
    ScanMode.ANY -> "Apunta a un QR o a un número"
}

private fun nothingFound(mode: ScanMode) = when (mode) {
    ScanMode.QR -> "No hay ningún QR en la imagen"
    ScanMode.NUMBERS -> "No hay números de tarjeta ni de móvil en la imagen"
    ScanMode.ANY -> "No hay ningún QR ni número en la imagen"
}

private fun Context.cameraPermitted() =
    checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

@Composable
private fun Scrims() {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(140.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.45f), Color.Transparent)))
        )
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(240.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
        )
    }
}
