package dev.duardo.neotransfer.scanner

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Reads QR codes and card or mobile numbers from an image (gallery, share sheet). Numbers are candidates: let
 * the person confirm them. Throws [java.io.IOException] when the image cannot be decoded.
 */
suspend fun scanImage(context: Context, uri: Uri, mode: ScanMode, classifyQr: (String) -> QrVerdict): ImageScan {
    val found = withContext(Dispatchers.Default) {
        val bitmap = decodeImage(context, uri)
        val luma = bitmap.toLuma()
        bitmap.recycle()
        StillScanner(mode).use { it.scan(luma) }
    }
    val verdicts = found.qrs.map { it.raw to classifyQr(it.raw) }
    val accepted = verdicts.filter { it.second == QrVerdict.Accepted }.map { ScanResult.Qr(it.first) }
    val numbers = found.numbers.sortedBy { it.kind }.map {
        if (it.kind == NumberKind.CARD) ScanResult.CardNumber(it.digits) else ScanResult.PhoneNumber(it.digits)
    }
    val rejection = if (accepted.isNotEmpty()) null else verdicts.firstNotNullOfOrNull { (it.second as? QrVerdict.Rejected)?.reason }
    return ImageScan(accepted + numbers, rejection)
}

/** Detections in image pixels. */
internal class StillScan(val qrs: List<QrSighting>, val numbers: List<NumberHit>)

private const val MAX_EDGE = 3_000

internal suspend fun decodeImage(context: Context, uri: Uri): Bitmap = withContext(Dispatchers.IO) {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
        val edge = max(info.size.width, info.size.height)
        if (edge > MAX_EDGE) {
            val ratio = MAX_EDGE.toFloat() / edge
            decoder.setTargetSize(max(1, (info.size.width * ratio).roundToInt()), max(1, (info.size.height * ratio).roundToInt()))
        }
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
}

/** Luminance over white, so a code drawn on a transparent background stays readable. */
internal fun Bitmap.toLuma(): Luma {
    val out = ByteArray(width * height)
    val band = 64
    val pixels = IntArray(width * band)
    var y = 0
    while (y < height) {
        val rows = min(band, height - y)
        getPixels(pixels, 0, width, 0, y, width, rows)
        for (i in 0 until width * rows) {
            val p = pixels[i]
            val alpha = p ushr 24
            val gray = (((p shr 16) and 0xFF) * 77 + ((p shr 8) and 0xFF) * 150 + (p and 0xFF) * 29) shr 8
            out[y * width + i] = ((gray * alpha + 255 * (255 - alpha)) / 255).toByte()
        }
        y += rows
    }
    return Luma(out, width, height)
}

/** Blocking ML Kit + ZXing passes over a still image; call it off the main thread. */
internal class StillScanner(private val mode: ScanMode) : Closeable {
    private val barcode = if (mode.readsQr) {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    } else null
    private val text = if (mode.readsNumbers) TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) else null

    fun scan(luma: Luma) = StillScan(
        qrs = if (barcode != null) qrs(luma) else emptyList(),
        numbers = if (text != null) numbers(luma) else emptyList(),
    )

    private fun qrs(luma: Luma) = findQrs(luma) { region, invert -> mlKitQr(luma, region, invert) }

    // An ML Kit failure leaves the region to ZXing.
    private fun mlKitQr(luma: Luma, region: Region, invert: Boolean): List<QrSighting> {
        val (image, area) = luma.inputImage(region, invert, 0)
        val codes = runCatching { Tasks.await(checkNotNull(barcode).process(image)) }.getOrElse { return emptyList() }
        return codes.mapNotNull { code -> code.sighting { Pt(it.x + area.left, it.y + area.top) } }
    }

    // Whole image, then tiles (small print), then sideways (a card photographed in portrait).
    private fun numbers(luma: Luma): List<NumberHit> {
        val whole = Region(0, 0, luma.width, luma.height)
        return read(luma, whole, 0)
            .ifEmpty { scanTiles(luma.width, luma.height).flatMap { read(luma, it, 0) }.distinctBy { it.kind to it.digits } }
            .ifEmpty { read(luma, whole, 90) }
            .ifEmpty { read(luma, whole, 270) }
    }

    private fun read(luma: Luma, region: Region, rotation: Int): List<NumberHit> {
        val (image, area) = luma.inputImage(region, invert = false, rotation = rotation)
        val lines = Tasks.await(checkNotNull(text).process(image)).toLines()
        return NumberReader.read(lines).map { hit ->
            val box = Quad.of(hit.box).map { fromUpright(it, area.width, area.height, rotation) }.bounds()
            hit.copy(box = Box(box.left + area.left, box.top + area.top, box.right + area.left, box.bottom + area.top))
        }
    }

    override fun close() {
        barcode?.close()
        text?.close()
    }
}

/** Grey NV21 for ML Kit (even size, neutral chroma), optionally inverted. */
private fun Luma.inputImage(region: Region, invert: Boolean, rotation: Int): Pair<InputImage, Region> {
    val w = region.width and 1.inv()
    val h = region.height and 1.inv()
    val nv21 = ByteArray(w * h * 3 / 2)
    for (y in 0 until h) {
        val from = (region.top + y) * stride + region.left
        if (invert) {
            for (x in 0 until w) nv21[y * w + x] = (255 - (data[from + x].toInt() and 0xFF)).toByte()
        } else {
            System.arraycopy(data, from, nv21, y * w, w)
        }
    }
    nv21.fill(128.toByte(), w * h, nv21.size)
    return InputImage.fromByteArray(nv21, w, h, rotation, InputImage.IMAGE_FORMAT_NV21) to Region(region.left, region.top, w, h)
}
