package dev.duardo.neotransfer.scanner

import android.graphics.Matrix
import android.graphics.RectF
import android.media.Image
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.ZoomSuggestionOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService

/** One analysed camera frame, in preview-view pixels. [numbers] is null when text was not read this frame. */
internal class Frame(val qrs: List<QrSighting>, val numbers: List<NumberHit>?, val dark: Boolean)

/**
 * ML Kit first, ZXing when ML Kit decodes nothing (inverted codes). Results are mapped to the PreviewView
 * through the sensor-to-view transform the CameraController hands to view-referenced analyzers.
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
internal class CameraAnalyzer(
    private val main: Executor,
    private val onFrame: (Frame) -> Unit,
    private val onZoomSuggestion: (Float) -> Unit,
) : ImageAnalysis.Analyzer {
    @Volatile var mode = ScanMode.QR
    @Volatile var paused = false
    @Volatile var maxZoom = 0f
    @Volatile private var sensorToView: Matrix? = null
    @Volatile private var closed = false
    private var barcode: BarcodeScanner? = null
    private var text: TextRecognizer? = null
    private var count = 0L
    private var plane = ByteArray(0)

    override fun getTargetCoordinateSystem() = ImageAnalysis.COORDINATE_SYSTEM_VIEW_REFERENCED

    override fun updateTransform(matrix: Matrix?) {
        sensorToView = matrix?.let(::Matrix)
    }

    override fun analyze(image: ImageProxy) {
        image.use { proxy -> analyzeOpen(proxy) }
    }

    private fun analyzeOpen(proxy: ImageProxy) {
        val toView = sensorToView
        val media = proxy.image
        if (closed || paused || toView == null || media == null) return
        val mode = mode
        val index = count++
        val rotation = proxy.imageInfo.rotationDegrees
        val bufferToView = Matrix().apply {
            proxy.imageInfo.sensorToBufferTransformMatrix.invert(this)
            postConcat(toView)
        }
        val uprightToView = Matrix().apply {
            bufferToUpright(proxy.width, proxy.height, rotation).invert(this)
            postConcat(bufferToView)
        }
        val dark = meanLuma(proxy) < DARK_LUMA
        // In ANY mode text and the ZXing pass alternate frames so QR codes stay fast.
        val qrs = if (mode.readsQr) {
            qrs(proxy, media, rotation, uprightToView, bufferToView, zxing = mode == ScanMode.QR || index % 2 == 0L)
        } else emptyList()
        val numbers = if (mode == ScanMode.NUMBERS || (mode == ScanMode.ANY && index % 2 == 1L)) {
            numbers(media, rotation, uprightToView)
        } else null
        main.execute { if (!closed) onFrame(Frame(qrs, numbers, dark)) }
    }

    private fun qrs(
        proxy: ImageProxy, media: Image, rotation: Int, uprightToView: Matrix, bufferToView: Matrix, zxing: Boolean,
    ): List<QrSighting> {
        val client = barcodeClient()
        val found = if (client == null) emptyList() else runCatching {
            Tasks.await(client.process(InputImage.fromMediaImage(media, rotation, uprightToView)))
        }.getOrDefault(emptyList()).mapNotNull { it.sighting() }
        if (found.isNotEmpty() || !zxing) return found
        val y = proxy.planes[0]
        val buffer = y.buffer.duplicate().apply { rewind() }
        val size = buffer.remaining()
        if (plane.size < size) plane = ByteArray(size)
        buffer.get(plane, 0, size)
        val sighting = ZxingQr.decode(Luma(plane, proxy.width, proxy.height, y.rowStride)) ?: return emptyList()
        return listOf(sighting.copy(quad = sighting.quad.map(bufferToView::mapPt).upright()))
    }

    private fun numbers(media: Image, rotation: Int, uprightToView: Matrix): List<NumberHit> {
        val recognizer = text ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { text = it }
        val result = runCatching {
            Tasks.await(recognizer.process(InputImage.fromMediaImage(media, rotation, uprightToView)))
        }.getOrNull() ?: return emptyList()
        return NumberReader.read(result.toLines())
    }

    // Created once the camera reports its zoom range, so ML Kit can ask to zoom into small or far codes.
    private fun barcodeClient(): BarcodeScanner? {
        barcode?.let { return it }
        val max = maxZoom
        if (max <= 0f) return null
        val options = BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE)
        if (max > 1f) {
            options.setZoomSuggestionOptions(
                ZoomSuggestionOptions.Builder { ratio ->
                    main.execute { if (!closed) onZoomSuggestion(ratio) }
                    true
                }.setMaxSupportedZoomRatio(max).build()
            )
        }
        return BarcodeScanning.getClient(options.build()).also { barcode = it }
    }

    /** Stops delivering frames and releases the detectors after any frame in flight. */
    fun close(executor: ExecutorService) {
        closed = true
        executor.execute {
            barcode?.close()
            text?.close()
        }
        executor.shutdown()
    }

    private fun meanLuma(proxy: ImageProxy): Int {
        val y = proxy.planes[0]
        val buffer = y.buffer
        var sum = 0L
        var samples = 0
        for (row in 0 until proxy.height step 24) for (col in 0 until proxy.width step 24) {
            sum += buffer.get(row * y.rowStride + col).toInt() and 0xFF
            samples++
        }
        return if (samples == 0) 255 else (sum / samples).toInt()
    }

    private companion object {
        const val DARK_LUMA = 40
    }
}

/** The rotation ML Kit applies to make the buffer upright, as CameraX's MlKitAnalyzer computes it. */
private fun bufferToUpright(width: Int, height: Int, rotation: Int): Matrix {
    val source = RectF(0f, 0f, width.toFloat(), height.toFloat())
    val upright = if (rotation % 180 == 0) RectF(source) else RectF(0f, 0f, height.toFloat(), width.toFloat())
    val normalized = RectF(-1f, -1f, 1f, 1f)
    return Matrix().apply {
        setRectToRect(source, normalized, Matrix.ScaleToFit.FILL)
        postRotate(rotation.toFloat())
        postConcat(Matrix().apply { setRectToRect(normalized, upright, Matrix.ScaleToFit.FILL) })
    }
}

private fun Matrix.mapPt(point: Pt): Pt {
    val values = floatArrayOf(point.x, point.y)
    mapPoints(values)
    return Pt(values[0], values[1])
}
