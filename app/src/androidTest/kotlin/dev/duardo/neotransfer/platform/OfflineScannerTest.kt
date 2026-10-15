package dev.duardo.neotransfer.platform

import android.content.pm.PackageManager
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
import dev.duardo.neotransfer.scanner.QrVerdict
import dev.duardo.neotransfer.scanner.ScanMode
import dev.duardo.neotransfer.scanner.ScanResult
import dev.duardo.neotransfer.scanner.scanImage
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The app has no INTERNET permission: bundled ML Kit must still read codes and numbers in its process. Synthetic data only. */
@RunWith(AndroidJUnit4::class)
class OfflineScannerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "offline-scanner-test").apply { mkdirs() }

    @After fun clean() { dir.deleteRecursively() }

    private fun scan(bitmap: Bitmap, mode: ScanMode) = runBlocking {
        val file = File(dir, "image.png")
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        scanImage(context, Uri.fromFile(file), mode) { QrVerdict.Accepted }
    }

    @Test fun readsWithoutNetworkPermission() {
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(android.Manifest.permission.INTERNET))

        val text = "TRANSFERMOVIL_ETECSA,TRANSFERENCIA,9204129951234568,5351234567,"
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 4))
        val qr = Bitmap.createBitmap(matrix.width * 12, matrix.height * 12, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val ink = Paint().apply { color = Color.BLACK }
            for (row in 0 until matrix.height) for (col in 0 until matrix.width) if (matrix[col, row]) {
                canvas.drawRect(col * 12f, row * 12f, (col + 1) * 12f, (row + 1) * 12f, ink)
            }
        }
        assertEquals(listOf<ScanResult>(ScanResult.Qr(text)), scan(qr, ScanMode.QR).results)

        val printed = Bitmap.createBitmap(1400, 500, Bitmap.Config.ARGB_8888).also { bitmap ->
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 72f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) }
            canvas.drawText("9204 1299 5123 4568", 100f, 260f, ink)
        }
        assertTrue(ScanResult.CardNumber("9204129951234568") in scan(printed, ScanMode.NUMBERS).results)
    }
}
