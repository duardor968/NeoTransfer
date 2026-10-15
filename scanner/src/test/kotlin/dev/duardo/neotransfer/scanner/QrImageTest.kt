package dev.duardo.neotransfer.scanner

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

// Synthetic payloads in the shapes the apps scan; no real card or phone.
private const val CARD_QR = "TRANSFERMOVIL_ETECSA,TRANSFERENCIA,9204129951234568,5351234567,"
private const val PAY_QR = """{"id_transaccion":"TEST-0001","importe":"150.00","moneda":"CUP","numero_proveedor":"0000"}"""

/**
 * Generates QR images (normal, inverted, low contrast, rotated, small inside a busy photo), saves them as PNG under
 * build/scanner-test-images for inspection, and reads them back through the still-image strategy. Android unit
 * tests have no java.awt, so the test draws with its own small grey canvas.
 */
class QrImageTest {
    private val out = File("build/scanner-test-images").apply { mkdirs() }

    private class Drawn(val image: Gray, val corners: Quad, val module: Float)

    private fun draw(
        text: String, width: Int, height: Int, module: Int, center: Pt,
        angle: Double = 0.0, ink: Int = 0, paper: Int = 255, background: (Gray) -> Unit = {},
    ): Drawn {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
        val quiet = 4 * module
        val side = matrix.width * module
        val code = Gray(side + 2 * quiet, side + 2 * quiet, paper)
        for (row in 0 until matrix.height) for (col in 0 until matrix.width) {
            if (matrix[col, row]) code.rect(quiet + col * module, quiet + row * module, module, module, ink)
        }
        val image = Gray(width, height, paper).also(background)
        image.stamp(code, center, angle)
        val half = side / 2f
        fun corner(x: Float, y: Float) = center + rotate(Pt(x, y), angle)
        val corners = Quad(corner(-half, -half), corner(half, -half), corner(half, half), corner(-half, half)).upright()
        return Drawn(image, corners, module.toFloat())
    }

    /** ML Kit is absent on the JVM; an empty primary reader forces the ZXing fallback and the tiling. */
    private fun scan(name: String, drawn: Drawn, expected: String) {
        drawn.image.png(File(out, "$name.png"))
        val found = findQrs(drawn.image.luma()) { _, _ -> emptyList() }
        val hit = found.singleOrNull() ?: fail("$name: expected one code, found ${found.map { it.raw }}")
        assertEquals(expected, hit.raw, name)
        assertNear(drawn.corners, hit.quad, drawn.module * 2.5f, name)
    }

    private fun assertNear(expected: Quad, actual: Quad, tolerance: Float, name: String) {
        expected.corners.zip(actual.corners).forEach { (e, a) ->
            assertTrue((e - a).length() <= tolerance, "$name: corner $a is ${(e - a).length()} px from $e")
        }
    }

    @Test
    fun printedCode() = scan("qr-normal", draw(CARD_QR, 700, 700, module = 12, center = Pt(350f, 350f)), CARD_QR)

    @Test
    fun invertedCode() = scan(
        "qr-inverted",
        draw(PAY_QR, 700, 700, module = 10, center = Pt(350f, 350f), ink = 255, paper = 18),
        PAY_QR,
    )

    @Test
    fun lowContrastCode() = scan(
        "qr-low-contrast",
        draw(CARD_QR, 700, 700, module = 12, center = Pt(350f, 350f), ink = 90, paper = 180),
        CARD_QR,
    )

    @Test
    fun fadedCode() = scan(
        "qr-faded",
        draw(PAY_QR, 700, 700, module = 10, center = Pt(350f, 350f), ink = 125, paper = 205),
        PAY_QR,
    )

    @Test
    fun rotatedCode() = scan("qr-rotated-30", draw(CARD_QR, 900, 900, module = 10, center = Pt(450f, 450f), angle = 30.0), CARD_QR)

    @Test
    fun upsideDownCode() = scan("qr-rotated-160", draw(PAY_QR, 900, 900, module = 9, center = Pt(450f, 450f), angle = 160.0), PAY_QR)

    @Test
    fun smallCodeInALargeBusyPhoto() {
        val drawn = draw(CARD_QR, 3000, 2250, module = 3, center = Pt(2650f, 1950f), angle = 8.0, background = ::busy)
        scan("qr-small-in-photo", drawn, CARD_QR)
        // The tile holding the code reports corners in whole-image pixels.
        val tile = scanTiles(3000, 2250).last()
        val hit = assertNotNull(ZxingQr.decode(drawn.image.luma(), tile, tryHarder = true))
        assertNear(drawn.corners, hit.quad, 7.5f, "tile")
    }

    @Test
    fun noCodeMeansNoResult() {
        val image = Gray(1600, 1200, 255).also(::busy)
        image.png(File(out, "no-qr.png"))
        assertEquals(emptyList(), findQrs(image.luma()) { _, _ -> emptyList() })
    }

    @Test
    fun primaryReaderResultsWinOverZxing() {
        val drawn = draw(CARD_QR, 700, 700, module = 12, center = Pt(350f, 350f))
        val fromPrimary = QrSighting("ML Kit", drawn.corners)
        assertEquals(listOf(fromPrimary), findQrs(drawn.image.luma()) { _, invert -> if (invert) emptyList() else listOf(fromPrimary) })
    }

    @Test
    fun tilesCoverAnySmallSquare() {
        assertEquals(emptyList(), scanTiles(1000, 900))
        listOf(3000 to 2250, 1080 to 2400, 2400 to 1080, 3000 to 3000).forEach { (w, h) ->
            val tiles = scanTiles(w, h)
            val side = tiles.minOf { minOf(it.width, it.height) } / 3
            for (x in 0..(w - side) step 37) for (y in 0..(h - side) step 41) {
                assertTrue(
                    tiles.any { x >= it.left && y >= it.top && x + side <= it.left + it.width && y + side <= it.top + it.height },
                    "${w}x$h: square $side at $x,$y is not inside a tile",
                )
            }
            assertTrue(tiles.all { it.left >= 0 && it.top >= 0 && it.left + it.width <= w && it.top + it.height <= h })
        }
    }

    @Test
    fun uprightMappingInvertsMlKitRotation() {
        val w = 640
        val h = 480
        val point = Pt(100f, 50f)
        mapOf(
            0 to point,
            90 to Pt(h - point.y, point.x),
            180 to Pt(w - point.x, h - point.y),
            270 to Pt(point.y, w - point.x),
        ).forEach { (rotation, upright) -> assertEquals(point, fromUpright(upright, w, h, rotation), "rotation $rotation") }
    }

    @Test
    fun cornersAreOrderedClockwiseFromTopLeft() {
        val square = Quad(Pt(10f, 10f), Pt(110f, 10f), Pt(110f, 110f), Pt(10f, 110f))
        assertEquals(square, Quad(square.br, square.tl, square.bl, square.tr).upright())
    }

    /** Light paper with text-like grey bars, like a photographed document. */
    private fun busy(image: Gray) {
        val random = Random(7)
        for (y in 0 until image.height step 8) image.rect(0, y, image.width, 8, 222 + y * 25 / image.height)
        repeat(900) {
            image.rect(
                random.nextInt(image.width - 200), random.nextInt(image.height - 30),
                random.nextInt(20, 200), random.nextInt(6, 22), random.nextInt(40, 160),
            )
        }
    }
}

private fun rotate(point: Pt, degrees: Double): Pt {
    val r = Math.toRadians(degrees)
    return Pt((point.x * cos(r) - point.y * sin(r)).toFloat(), (point.x * sin(r) + point.y * cos(r)).toFloat())
}

/** 8-bit grey canvas: rectangles, rotated stamping with bilinear sampling, PNG output. */
private class Gray(val width: Int, val height: Int, fill: Int) {
    val px = IntArray(width * height) { fill }

    fun rect(x: Int, y: Int, w: Int, h: Int, value: Int) {
        for (row in maxOf(0, y) until minOf(height, y + h)) for (col in maxOf(0, x) until minOf(width, x + w)) {
            px[row * width + col] = value
        }
    }

    /** Draws [source] centred on [center], rotated clockwise by [degrees]. */
    fun stamp(source: Gray, center: Pt, degrees: Double) {
        val reach = (Pt(source.width / 2f, source.height / 2f).length() + 2).toInt()
        for (y in (center.y.toInt() - reach)..(center.y.toInt() + reach)) for (x in (center.x.toInt() - reach)..(center.x.toInt() + reach)) {
            if (x !in 0 until width || y !in 0 until height) continue
            val s = rotate(Pt(x + 0.5f - center.x, y + 0.5f - center.y), -degrees) + Pt(source.width / 2f - 0.5f, source.height / 2f - 0.5f)
            source.sample(s.x, s.y)?.let { px[y * width + x] = it }
        }
    }

    private fun sample(x: Float, y: Float): Int? {
        if (x < -0.5f || y < -0.5f || x > width - 0.5f || y > height - 0.5f) return null
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0
        fun at(cx: Int, cy: Int) = px[cy.coerceIn(0, height - 1) * width + cx.coerceIn(0, width - 1)]
        val top = at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx
        val bottom = at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx
        return (top * (1 - fy) + bottom * fy).toInt()
    }

    fun luma() = Luma(ByteArray(px.size) { px[it].toByte() }, width, height)

    fun png(file: File) {
        val raw = ByteArrayOutputStream()
        DeflaterOutputStream(raw).use { zip ->
            for (y in 0 until height) {
                zip.write(0)
                for (x in 0 until width) zip.write(px[y * width + x])
            }
        }
        DataOutputStream(file.outputStream().buffered()).use { png ->
            png.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            fun chunk(type: String, data: ByteArray) {
                png.writeInt(data.size)
                val body = type.toByteArray() + data
                png.write(body)
                png.writeInt(CRC32().apply { update(body) }.value.toInt())
            }
            val header = ByteArrayOutputStream()
            DataOutputStream(header).apply { writeInt(width); writeInt(height); write(byteArrayOf(8, 0, 0, 0, 0)) }
            chunk("IHDR", header.toByteArray())
            chunk("IDAT", raw.toByteArray())
            chunk("IEND", ByteArray(0))
        }
    }
}
