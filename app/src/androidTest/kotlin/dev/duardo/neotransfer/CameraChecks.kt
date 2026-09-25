package dev.duardo.neotransfer

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.duardo.neotransfer.platform.QrInput
import java.io.File
import java.util.UUID

/** Offline device checks for the app's actual gallery decode and ML Kit callbacks. */
internal object CameraChecks {
    private const val TIMEOUT_MS = 10_000L

    fun run(host: Activity, instrumentation: Instrumentation, record: (String) -> Unit): Int {
        val files = File(host.cacheDir, "camera_checks_${UUID.randomUUID()}")
        check(files.mkdir()) { "Could not create isolated camera fixture directory" }
        var passed = 0
        try {
            fun case(name: String, mode: ScanMode, bitmap: Bitmap, verify: (Capture) -> Boolean, assertResult: (Capture) -> Unit) {
                val fixture = File(files, "image_$passed.png")
                try {
                    fixture.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally {
                    bitmap.recycle()
                }
                val capture = Capture()
                val owner = TestOwner()
                var scanner: CameraScanView? = null
                try {
                    onMain(instrumentation) {
                        scanner = CameraScanView(host, owner).apply {
                            onCode = { capture.code = it }
                            onError = { if (it !in CAMERA_ERRORS) capture.error = it }
                            onTargets = { capture.targets = it }
                            onGalleryLoading = { capture.loadingStates += it }
                            setMode(mode)
                            host.addContentView(this, ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                        }
                    }
                    val view = checkNotNull(scanner)
                    awaitLayout(instrumentation, view)
                    onMain(instrumentation) { view.openGallery(Uri.fromFile(fixture)) }
                    await(name, verify, capture)
                    assertResult(capture)
                    check(capture.loadingStates == listOf(true, false)) {
                        "$name: gallery loading callbacks ${capture.loadingStates}"
                    }
                    check(capture.targets.all { it.rect.width() > 0f && it.rect.height() > 0f }) {
                        "$name: detection boxes were not mapped into the gallery view"
                    }
                    record(name)
                    passed++
                } finally {
                    scanner?.let { view -> onMain(instrumentation) {
                        try { view.close() } finally { (view.parent as? ViewGroup)?.removeView(view) }
                    } }
                    check(fixture.delete()) { "Could not remove camera fixture $fixture" }
                }
            }

            val cardQr = "TRANSFERMOVIL_ETECSA,TRANSFERENCIA,0000000000000002,5350000000,"
            case("gallery QR decoded by ML Kit and parsed as card", ScanMode.QR, qr(cardQr),
                { it.code == cardQr || it.error != null }) { result ->
                check(result.error == null) { "QR failed: ${result.error}" }
                check(result.code == cardQr)
                check(result.targets.any { it.value == cardQr && it.stable })
                check(QrInput.parse(result.code!!) == QrInput.Card("0000000000000002", "50000000"))
            }

            case("gallery without QR reports absence", ScanMode.QR,
                print("IMAGEN SIN CODIGO", "REFERENCIA 2026"),
                { it.error == "No se encontró un QR en la imagen" || it.code != null }) { result ->
                check(result.code == null) { "Unexpected QR: ${result.code}" }
                check(result.error == "No se encontró un QR en la imagen") { "Error: ${result.error}" }
                check(result.targets.none { it.stable && it.value.isNotEmpty() })
            }

            case("printed mobile OCR keeps two numbers and excludes card", ScanMode.MOBILE,
                print("MOVIL 51234567", "OTRO 59876543", "TARJETA 1234 5678 9012 3456"),
                { it.values.containsAll(setOf("51234567", "59876543")) || it.error != null }) { result ->
                check(result.error == null) { "Mobile OCR failed: ${result.error}" }
                check(result.values.containsAll(setOf("51234567", "59876543"))) { "OCR values: ${result.values}" }
                check(result.values.none { it.length == 16 || it == "12345678" || it == "90123456" }) {
                    "Card fragments became mobiles: ${result.values}"
                }
                check(result.code == null)
            }

            case("printed card OCR keeps two cards and excludes mobile", ScanMode.CARD,
                print("TARJETA 1234 5678 9012 3456", "OTRA 4321 8765 4321 8765", "MOVIL 51234567"),
                { it.values.containsAll(setOf("1234567890123456", "4321876543218765")) || it.error != null }) { result ->
                check(result.error == null) { "Card OCR failed: ${result.error}" }
                check(result.values.containsAll(setOf("1234567890123456", "4321876543218765"))) {
                    "OCR values: ${result.values}"
                }
                check(result.values.none { it.length == 8 })
                check(result.code == null)
            }

            case("moderately rotated printed mobile OCR", ScanMode.MOBILE,
                print("MOVIL 51234567", "OTRO 59876543", rotation = 8f),
                { it.values.containsAll(setOf("51234567", "59876543")) || it.error != null }) { result ->
                check(result.error == null) { "Rotated OCR failed: ${result.error}" }
                check(result.values.containsAll(setOf("51234567", "59876543"))) { "OCR values: ${result.values}" }
            }
            return passed
        } finally {
            check(files.deleteRecursively()) { "Could not remove isolated camera fixture directory $files" }
        }
    }

    private class Capture {
        @Volatile var code: String? = null
        @Volatile var error: String? = null
        @Volatile var targets: List<ScanTarget> = emptyList()
        val loadingStates = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        val values: Set<String> get() = targets.filter { it.stable }.map { it.value }.toSet()
    }

    private val CAMERA_ERRORS = setOf(
        "Permite la cámara para escanear",
        "No se pudo abrir la cámara",
        "No se pudo iniciar la cámara",
    )

    private class TestOwner : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    private fun onMain(instrumentation: Instrumentation, action: () -> Unit) {
        var failure: Throwable? = null
        instrumentation.runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }

    private fun awaitLayout(instrumentation: Instrumentation, view: CameraScanView) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            var laidOut = false
            onMain(instrumentation) { laidOut = view.width > 0 && view.height > 0 }
            if (laidOut) return
            Thread.sleep(50)
        }
        error("Gallery view was not laid out after ${TIMEOUT_MS}ms")
    }

    private fun await(name: String, complete: (Capture) -> Boolean, capture: Capture) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (complete(capture)) return
            Thread.sleep(50)
        }
        error("$name timed out after ${TIMEOUT_MS}ms; code=${capture.code}, error=${capture.error}, values=${capture.values}, loading=${capture.loadingStates}")
    }

    private fun qr(value: String): Bitmap {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 560, 560)
        return Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(Color.WHITE)
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix[x, y]) bitmap.setPixel(x + 80, y + 80, Color.BLACK)
            }
        }
    }

    private fun print(vararg lines: String, rotation: Float = 0f): Bitmap =
        Bitmap.createBitmap(1400, 800, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.rotate(rotation, bitmap.width / 2f, bitmap.height / 2f)
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 68f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            lines.forEachIndexed { index, line -> canvas.drawText(line, 110f, 200f + index * 160f, ink) }
        }
}
