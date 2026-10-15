package dev.duardo.neotransfer.scanner

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Synthetic data only.
private const val CARD_QR = "TRANSFERMOVIL_ETECSA,TRANSFERENCIA,9204129951234568,5351234567,"
private const val CARD = "9204129951234568"

/** ML Kit on the device with generated images, through the public [scanImage]. */
@RunWith(AndroidJUnit4::class)
class ScanImageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "scan-image-test").apply { mkdirs() }
    private val classify: (String) -> QrVerdict = {
        if (it.startsWith("TRANSFERMOVIL")) QrVerdict.Accepted else QrVerdict.Rejected("Este QR no es de pago")
    }

    @After
    fun clean() {
        dir.deleteRecursively()
    }

    private fun scan(name: String, bitmap: Bitmap, mode: ScanMode): ImageScan {
        val file = File(dir, "$name.png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return runBlocking { scanImage(context, Uri.fromFile(file), mode, classify) }
    }

    private fun qr(text: String, width: Int, height: Int, module: Int, x: Float, y: Float, angle: Float = 0f,
                   ink: Int = Color.BLACK, paper: Int = Color.WHITE, background: Int = paper): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 4))
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(background)
            canvas.rotate(angle, x + matrix.width * module / 2f, y + matrix.height * module / 2f)
            val paint = Paint().apply { color = paper }
            canvas.drawRect(x, y, x + matrix.width * module, y + matrix.height * module, paint)
            paint.color = ink
            for (row in 0 until matrix.height) for (col in 0 until matrix.width) {
                if (matrix[col, row]) {
                    canvas.drawRect(x + col * module, y + row * module, x + (col + 1) * module, y + (row + 1) * module, paint)
                }
            }
        }
    }

    private fun print(vararg lines: String, rotation: Float = 0f, size: Float = 64f): Bitmap =
        Bitmap.createBitmap(1400, 900, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.rotate(rotation, bitmap.width / 2f, bitmap.height / 2f)
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = size
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            }
            lines.forEachIndexed { index, line -> canvas.drawText(line, 100f, 220f + index * 140f, ink) }
        }

    @Test
    fun qrCodes() {
        listOf(
            "normal" to qr(CARD_QR, 720, 720, 14, 80f, 80f),
            "inverted" to qr(CARD_QR, 720, 720, 14, 80f, 80f, ink = Color.WHITE, paper = Color.BLACK),
            "rotated" to qr(CARD_QR, 900, 900, 12, 200f, 200f, angle = 30f),
            "small" to qr(CARD_QR, 3000, 2250, 3, 2600f, 1900f, background = Color.rgb(200, 200, 195)),
            "transparent" to qr(CARD_QR, 720, 720, 14, 80f, 80f, paper = Color.TRANSPARENT),
        ).forEach { (name, bitmap) ->
            val result = scan("qr-$name", bitmap, ScanMode.QR)
            assertEquals(listOf<ScanResult>(ScanResult.Qr(CARD_QR)), result.results, name)
        }
    }

    @Test
    fun rejectedQrCarriesTheReason() {
        val result = scan("qr-wifi", qr("WIFI:S:Casa;T:WPA;P:secreto;;", 720, 720, 14, 80f, 80f), ScanMode.QR)
        assertEquals(ImageScan(emptyList(), "Este QR no es de pago"), result)
    }

    @Test
    fun printedNumbers() {
        val result = scan("numbers", print("Tarjeta 9204 1299 5123 4568", "Movil +53 5123 4567", "Fecha 12/05/2025  1 500,00 CUP"), ScanMode.NUMBERS)
        assertEquals(listOf(ScanResult.CardNumber(CARD), ScanResult.PhoneNumber("51234567")), result.results)
    }

    @Test
    fun cardOverTwoLinesAndSideways() {
        val stacked = scan("card-two-lines", print("9204 1299", "5123 4568", size = 90f), ScanMode.NUMBERS)
        assertEquals(listOf<ScanResult>(ScanResult.CardNumber(CARD)), stacked.results)
        val sideways = scan("card-sideways", print("9204 1299 5123 4568", rotation = 90f, size = 56f), ScanMode.NUMBERS)
        assertEquals(listOf<ScanResult>(ScanResult.CardNumber(CARD)), sideways.results)
    }

    @Test
    fun anyModeReadsBoth() {
        val bitmap = qr(CARD_QR, 1400, 1400, 10, 700f, 80f)
        Canvas(bitmap).drawText("Movil 5123 4567", 80f, 1200f, Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 64f })
        val result = scan("any", bitmap, ScanMode.ANY)
        assertTrue(ScanResult.Qr(CARD_QR) in result.results && ScanResult.PhoneNumber("51234567") in result.results, "${result.results}")
    }
}
