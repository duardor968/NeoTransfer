package dev.duardo.neotransfer.scanner

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.ResultPoint
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.detector.FinderPattern
import kotlin.math.ceil
import kotlin.math.min

/** 8-bit luminance, row by row; [stride] may exceed [width] (camera planes). */
internal class Luma(val data: ByteArray, val width: Int, val height: Int, val stride: Int = width)

/** ZXing as the second reader: it also decodes inverted codes (light on dark), which ML Kit misses. */
internal object ZxingQr {
    fun decode(luma: Luma, region: Region? = null, tryHarder: Boolean = false): QrSighting? {
        val r = region ?: Region(0, 0, luma.width, luma.height)
        val plain = PlanarYUVLuminanceSource(luma.data, luma.stride, luma.height, r.left, r.top, r.width, r.height, false)
        val source = luma.stretch(r)?.let { Stretched(plain, it) } ?: plain
        val hints = buildMap<DecodeHintType, Any> {
            put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
            put(DecodeHintType.ALSO_INVERTED, true)
            if (tryHarder) put(DecodeHintType.TRY_HARDER, true)
        }
        val result = try {
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints)
        } catch (_: ReaderException) {
            return null
        }
        val quad = finderQuad(result.resultPoints) ?: return null
        return QrSighting(result.text, quad.map { Pt(it.x + r.left, it.y + r.top) }.upright())
    }

    /**
     * ZXing reports the centres of the three finder patterns (bottom-left, top-left, top-right). The symbol's
     * corners sit 3.5 modules further out; the fourth corner completes the parallelogram.
     */
    internal fun finderQuad(points: Array<ResultPoint>?): Quad? {
        if (points == null || points.size < 3) return null
        val bottomLeft = Pt(points[0].x, points[0].y)
        val topLeft = Pt(points[1].x, points[1].y)
        val topRight = Pt(points[2].x, points[2].y)
        val across = topRight - topLeft
        val down = bottomLeft - topLeft
        val module = (points[1] as? FinderPattern)?.estimatedModuleSize ?: (across.length() / 18f)
        val u = across.unit() * (3.5f * module)
        val v = down.unit() * (3.5f * module)
        return Quad(
            tl = topLeft - u - v,
            tr = topRight + u - v,
            br = topRight + down + u + v,
            bl = bottomLeft - u + v,
        )
    }
}

/**
 * Lookup table that spreads the region's 2nd–98th percentile over the full range, or null when the contrast is
 * already wide. ZXing's binarizer misreads faded print and grey-on-grey screens whose dark tone sits near half the
 * light one.
 */
private fun Luma.stretch(region: Region): ByteArray? {
    val histogram = IntArray(256)
    var samples = 0
    for (y in region.top until region.top + region.height step 3) {
        val row = y * stride
        for (x in region.left until region.left + region.width step 3) {
            histogram[data[row + x].toInt() and 0xFF]++
            samples++
        }
    }
    fun percentile(fraction: Float): Int {
        var seen = 0
        histogram.forEachIndexed { value, count -> seen += count; if (seen >= samples * fraction) return value }
        return 255
    }
    val low = percentile(0.02f)
    val high = percentile(0.98f)
    if (high - low >= 160 || high <= low) return null
    return ByteArray(256) { ((it - low) * 255 / (high - low)).coerceIn(0, 255).toByte() }
}

private class Stretched(private val source: LuminanceSource, private val table: ByteArray) :
    LuminanceSource(source.width, source.height) {
    override fun getRow(y: Int, row: ByteArray?): ByteArray =
        source.getRow(y, row).also { for (i in 0 until width) it[i] = table[it[i].toInt() and 0xFF] }

    // The plain source may hand out its backing array, so map a copy.
    override fun getMatrix(): ByteArray = source.matrix.copyOf().also { for (i in it.indices) it[i] = table[it[i].toInt() and 0xFF] }
}

/**
 * Still-image strategy: the whole image with [primary] (ML Kit on the device), its negative, then ZXing; if
 * nothing turns up, the same per tile so a small code in a large photo or screenshot gets enough pixels.
 */
internal fun findQrs(luma: Luma, primary: (Region, invert: Boolean) -> List<QrSighting>): List<QrSighting> {
    val whole = Region(0, 0, luma.width, luma.height)
    val first = primary(whole, false).ifEmpty { primary(whole, true) }
        .ifEmpty { listOfNotNull(ZxingQr.decode(luma, whole, tryHarder = true)) }
    if (first.isNotEmpty()) return first
    return scanTiles(luma.width, luma.height).flatMap { tile ->
        primary(tile, false).ifEmpty { listOfNotNull(ZxingQr.decode(luma, tile, tryHarder = true)) }
    }.distinctBy { it.raw }
}

/**
 * Overlapping tiles for still images, so a small code in a large photo or screenshot gets enough pixels.
 * Any square up to a third of a tile's short side lies whole inside at least one tile.
 */
internal fun scanTiles(width: Int, height: Int, target: Int = 1_100): List<Region> {
    val cols = ceil(width / target.toFloat()).toInt().coerceIn(1, 3)
    val rows = ceil(height / target.toFloat()).toInt().coerceIn(1, 3)
    if (cols == 1 && rows == 1) return emptyList()
    val tileWidth = min(width, (width / cols.toFloat() * 1.5f).toInt())
    val tileHeight = min(height, (height / rows.toFloat() * 1.5f).toInt())
    return buildList {
        for (row in 0 until rows) for (col in 0 until cols) {
            val left = if (cols == 1) 0 else (width - tileWidth) * col / (cols - 1)
            val top = if (rows == 1) 0 else (height - tileHeight) * row / (rows - 1)
            add(Region(left, top, tileWidth, tileHeight))
        }
    }
}
