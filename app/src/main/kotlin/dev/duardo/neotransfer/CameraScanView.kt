package dev.duardo.neotransfer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.SystemClock
import android.util.Size
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.camera.view.transform.OutputTransform
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

internal data class ScanTarget(val value: String, val rect: RectF, val stable: Boolean)

/** A lifecycle-bound, local-only camera analyzer. All state callbacks run on the main thread. */
@androidx.annotation.OptIn(TransformExperimental::class, ExperimentalGetImage::class)
internal class CameraScanView(context: Context, private val owner: LifecycleOwner) : FrameLayout(context) {
    @Volatile var paused = false
    var onCode: (String) -> Unit = {}
    var onError: (String) -> Unit = {}
    var onTargets: (List<ScanTarget>) -> Unit = {}
    var onGalleryLoading: (Boolean) -> Unit = {}
    var onCameraFeatures: (Boolean, Boolean, Float) -> Unit = { _, _, _ -> }

    private val main = ContextCompat.getMainExecutor(context)
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "NeoTransferScan") }
    private val barcode = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAllPotentialBarcodes().build()
    )
    private val text = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val previewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }
    private val photoView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
    }
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    @Volatile private var mode = ScanMode.QR
    @Volatile private var photo: Bitmap? = null
    @Volatile private var photoLoading = false
    @Volatile private var generation = 0
    @Volatile private var closed = false
    private var reportedPermission = false
    private var lastFrameAt = 0L
    private var lastOutputAt = 0L
    private var lastCode = ""
    private var lastCodeAt = 0L
    private var lastErrorAt = 0L
    private var lastHaptic = ""
    private val tracks = mutableMapOf<String, Track>()

    private data class Track(var box: RectF, var count: Int, var at: Long)
    private data class Detection(val value: String, val box: RectF)

    init {
        addView(previewView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(photoView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> bindIfReady() }
        ProcessCameraProvider.getInstance(context).also { future ->
            future.addListener({
                if (closed) return@addListener
                runCatching { future.get() }
                    .onSuccess { provider = it; bindIfReady() }
                    .onFailure { onError("No se pudo abrir la cámara") }
            }, main)
        }
    }

    fun setMode(value: ScanMode) {
        if (mode == value) return
        mode = value
        generation++
        resetTracking()
        photo?.let { analyzePhoto(it, generation) }
    }

    private fun bindIfReady() {
        if (closed || camera != null || width == 0 || height == 0) return
        val activeProvider = provider ?: return
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            if (!reportedPermission) {
                reportedPermission = true
                onError("Permite la cámara para escanear")
            }
            return
        }
        val viewport = previewView.viewPort ?: return
        val nextPreview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val nextAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(
                ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            ).build())
            .build().also { it.setAnalyzer(worker, ::analyzeFrame) }
        val group = UseCaseGroup.Builder().setViewPort(viewport)
            .addUseCase(nextPreview).addUseCase(nextAnalysis).build()
        runCatching {
            activeProvider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, group)
        }.onSuccess {
            camera = it
            preview = nextPreview
            analysis = nextAnalysis
            publishFeatures()
        }.onFailure {
            nextAnalysis.clearAnalyzer()
            onError("No se pudo iniciar la cámara")
        }
    }

    fun refreshCamera() = bindIfReady()

    private fun analyzeFrame(frame: ImageProxy) {
        val now = SystemClock.elapsedRealtime()
        if (closed || paused || photoLoading || photo != null || now - lastFrameAt < 140) {
            frame.close(); return
        }
        lastFrameAt = now
        val media = frame.image
        if (media == null) { frame.close(); return }
        val input = runCatching { InputImage.fromMediaImage(media, frame.imageInfo.rotationDegrees) }
            .getOrElse { frame.close(); return }
        val source = ImageProxyTransformFactory().getOutputTransform(frame)
        val sourceWidth = frame.width
        val sourceHeight = frame.height
        val rotation = frame.imageInfo.rotationDegrees
        val frameMode = mode
        val frameGeneration = generation
        val task = runCatching { if (frameMode == ScanMode.QR) barcode.process(input) else text.process(input) }
            .getOrElse { frame.close(); reportAnalysisError(); return }
        task.addOnSuccessListener(main) { result ->
            if (closed || paused || photoLoading || photo != null || generation != frameGeneration) return@addOnSuccessListener
            val found = when (result) {
                is List<*> -> result.filterIsInstance<Barcode>().mapNotNull { item ->
                    item.boundingBox?.let { Detection(item.rawValue.orEmpty(), it.toRectF()) }
                }
                is com.google.mlkit.vision.text.Text -> numberDetections(result, frameMode)
                else -> emptyList()
            }
            runCatching { mapCameraDetections(found, source, sourceWidth, sourceHeight, rotation) }
                .onSuccess { showDetections(it, frameMode, false) }
                .onFailure { reportAnalysisError() }
        }.addOnFailureListener(main) {
            if (!closed && generation == frameGeneration) reportAnalysisError()
        }.addOnCompleteListener(main) { frame.close() }
    }

    private fun mapCameraDetections(
        found: List<Detection>, source: OutputTransform, width: Int, height: Int, rotation: Int,
    ): List<Detection> {
        val target = previewView.outputTransform ?: return emptyList()
        val converter = CoordinateTransform(source, target)
        return found.map { detected ->
            val rawBox = rotatedToBuffer(detected.box, width, height, rotation)
            converter.mapRect(rawBox)
            Detection(detected.value, rawBox)
        }.filter { it.box.width() > 0f && it.box.height() > 0f }
    }

    private fun numberDetections(result: com.google.mlkit.vision.text.Text, kind: ScanMode): List<Detection> =
        result.textBlocks.flatMap { block -> block.lines }.flatMap { line ->
            val box = line.boundingBox?.toRectF() ?: return@flatMap emptyList()
            numberMatches(line.text, kind).map { Detection(it, RectF(box)) }
        }

    private fun showDetections(found: List<Detection>, frameMode: ScanMode, still: Boolean) {
        if (closed) return
        val now = SystemClock.elapsedRealtime()
        if (found.isEmpty() && now - lastOutputAt < 380 && !still) return
        val keys = found.map { it.value.ifEmpty { "#qr" } }.toSet()
        tracks.keys.retainAll(keys)
        if (found.isEmpty()) lastHaptic = ""
        val output = found.map { detected ->
            val key = detected.value.ifEmpty { "#qr" }
            val old = tracks[key]
            val track = if (old == null || still || now - old.at > 650) {
                Track(RectF(detected.box), if (still) 2 else 1, now).also { tracks[key] = it }
            } else {
                old.box = interpolate(old.box, detected.box, .42f)
                old.count++
                old.at = now
                old
            }
            val stable = track.count >= 2 && detected.value.isNotEmpty()
            if (stable && lastHaptic != key) {
                performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                lastHaptic = key
            }
            ScanTarget(detected.value, RectF(track.box), stable)
        }
        lastOutputAt = now
        onTargets(output)
        if (frameMode == ScanMode.QR) output.firstOrNull { it.stable }?.let { candidate ->
            if (candidate.value != lastCode || now - lastCodeAt > 3_000) {
                lastCode = candidate.value
                lastCodeAt = now
                onCode(candidate.value)
            }
        }
    }

    fun openGallery(uri: Uri) {
        if (closed) return
        val request = ++generation
        photo = null
        photoLoading = true
        photoView.setImageDrawable(null)
        photoView.setBackgroundColor(android.graphics.Color.BLACK)
        photoView.visibility = View.VISIBLE
        onGalleryLoading(true)
        resetTracking()
        worker.execute {
            val bitmap = runCatching {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val edge = max(info.size.width, info.size.height)
                    if (edge > 2048) {
                        val ratio = 2048f / edge
                        decoder.setTargetSize(max(1, (info.size.width * ratio).toInt()),
                            max(1, (info.size.height * ratio).toInt()))
                    }
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                }
            }
            main.execute {
                if (closed || request != generation) return@execute
                photoLoading = false
                onGalleryLoading(false)
                bitmap.onSuccess {
                    photo = it
                    photoView.setImageBitmap(it)
                    photoView.visibility = View.VISIBLE
                    analyzePhoto(it, request)
                }.onFailure { onError("No se pudo abrir la imagen") }
            }
        }
    }

    private fun analyzePhoto(bitmap: Bitmap, request: Int) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val photoMode = mode
        val task = if (photoMode == ScanMode.QR) barcode.process(image) else text.process(image)
        task.addOnSuccessListener(main) { result ->
            if (closed || request != generation || photo !== bitmap) return@addOnSuccessListener
            val found = when (result) {
                is List<*> -> result.filterIsInstance<Barcode>().mapNotNull { item ->
                    item.boundingBox?.let { Detection(item.rawValue.orEmpty(), it.toRectF()) }
                }
                is com.google.mlkit.vision.text.Text -> numberDetections(result, photoMode)
                else -> emptyList()
            }
            val scale = min(photoView.width.toFloat() / bitmap.width, photoView.height.toFloat() / bitmap.height)
            val dx = (photoView.width - bitmap.width * scale) / 2f
            val dy = (photoView.height - bitmap.height * scale) / 2f
            showDetections(found.map { it.copy(box = RectF(
                dx + it.box.left * scale, dy + it.box.top * scale,
                dx + it.box.right * scale, dy + it.box.bottom * scale,
            )) }, photoMode, true)
            if (found.none { it.value.isNotEmpty() }) {
                onError(if (photoMode == ScanMode.QR) "No se encontró un QR en la imagen" else "No se encontró un número válido en la imagen")
            }
        }.addOnFailureListener(main) {
            if (!closed && request == generation) onError("No se pudo analizar la imagen")
        }
    }

    fun showCamera() {
        generation++
        photo = null
        photoLoading = false
        onGalleryLoading(false)
        photoView.setImageDrawable(null)
        photoView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        photoView.visibility = View.GONE
        resetTracking()
        bindIfReady()
    }

    fun focus(x: Float, y: Float) {
        val active = camera ?: return
        val point = previewView.meteringPointFactory.createPoint(x, y)
        active.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    fun zoomBy(factor: Float) {
        val active = camera ?: return
        val state = active.cameraInfo.zoomState.value ?: return
        val ratio = (state.zoomRatio * factor).coerceIn(state.minZoomRatio, state.maxZoomRatio)
        active.cameraControl.setZoomRatio(ratio)
        onCameraFeatures(active.cameraInfo.hasFlashUnit(), active.cameraInfo.torchState.value == 1, ratio)
    }

    fun nextZoom() {
        val active = camera ?: return
        val state = active.cameraInfo.zoomState.value ?: return
        val next = listOf(1f, 2f, 4f).firstOrNull { it > state.zoomRatio + .2f && it <= state.maxZoomRatio } ?: 1f
        active.cameraControl.setZoomRatio(next)
        onCameraFeatures(active.cameraInfo.hasFlashUnit(), active.cameraInfo.torchState.value == 1, next)
    }

    fun toggleTorch() {
        val active = camera ?: return
        if (!active.cameraInfo.hasFlashUnit()) return
        val enabled = active.cameraInfo.torchState.value == 1
        active.cameraControl.enableTorch(!enabled)
        onCameraFeatures(true, !enabled, active.cameraInfo.zoomState.value?.zoomRatio ?: 1f)
    }

    private fun publishFeatures() {
        val active = camera ?: return
        onCameraFeatures(active.cameraInfo.hasFlashUnit(), active.cameraInfo.torchState.value == 1,
            active.cameraInfo.zoomState.value?.zoomRatio ?: 1f)
    }

    private fun reportAnalysisError() {
        main.execute {
            val now = SystemClock.elapsedRealtime()
            if (!closed && now - lastErrorAt > 3_000) {
                lastErrorAt = now
                onError("No se pudo analizar la imagen")
            }
        }
    }

    private fun resetTracking() {
        tracks.clear()
        lastHaptic = ""
        lastCode = ""
        onTargets(emptyList())
    }

    fun close() {
        if (closed) return
        closed = true
        generation++
        photo = null
        photoView.setImageDrawable(null)
        analysis?.clearAnalyzer()
        provider?.unbind(*(listOfNotNull(preview, analysis).toTypedArray()))
        camera = null
        barcode.close()
        text.close()
        worker.shutdown()
    }
}

private fun Rect.toRectF() = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())

/** ML Kit reports boxes in the upright image; CameraX's source transform uses the raw buffer. */
private fun rotatedToBuffer(box: RectF, width: Int, height: Int, rotation: Int): RectF = when (rotation) {
    90 -> RectF(box.top, height - box.right, box.bottom, height - box.left)
    180 -> RectF(width - box.right, height - box.bottom, width - box.left, height - box.top)
    270 -> RectF(width - box.bottom, box.left, width - box.top, box.right)
    else -> RectF(box)
}

private fun interpolate(a: RectF, b: RectF, amount: Float) = RectF(
    a.left + (b.left - a.left) * amount,
    a.top + (b.top - a.top) * amount,
    a.right + (b.right - a.right) * amount,
    a.bottom + (b.bottom - a.bottom) * amount,
)

private val mobilePattern = Regex("(?<![0-9])(?:\\+?53[\\s-]?)?[0-9](?:[\\s-]?[0-9]){7}(?![0-9])")
private val cardPattern = Regex("(?<![0-9])[0-9](?:[\\s-]?[0-9]){15}(?![0-9])")

internal fun numberMatches(value: String, mode: ScanMode): List<String> {
    // A spaced card number must not become two mobile suggestions.
    if (mode == ScanMode.MOBILE && cardPattern.containsMatchIn(value)) return emptyList()
    val pattern = when (mode) {
        ScanMode.MOBILE -> mobilePattern
        ScanMode.CARD -> cardPattern
        ScanMode.QR -> return emptyList()
    }
    return pattern.findAll(value).mapNotNull { normalizeScanNumber(it.value, mode) }.distinct().toList()
}
