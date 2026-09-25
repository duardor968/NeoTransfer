package dev.duardo.neotransfer

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.biometrics.BiometricManager
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Process
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.duardo.neotransfer.platform.BiometricGate

/** Debug-only view of the exact package icon and the system biometric prompt. */
class IconDiagnosticActivity : Activity() {
    private var biometric: CancellationSignal? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(Color.WHITE)
        }
        val scroll = ScrollView(this).apply { addView(body) }
        setContentView(scroll)

        body.addView(label("Icono usado por el sistema", 22f))
        body.addView(label("Paquete: $packageName · Android ${android.os.Build.VERSION.SDK_INT}", 14f))
        comparison(body, "PackageManager.getApplicationIcon") { packageIcon() }
        comparison(body, "ApplicationIcon en 32 dp", 32) { packageIcon() }
        comparison(body, "UserBadgedIcon en 32 dp", 32) {
            packageManager.getUserBadgedIcon(packageIcon(), Process.myUserHandle())
        }
        rasterComparison(body, 32)
        rasterComparison(body, 80)
        val hasMonochrome = (packageIcon() as? AdaptiveIconDrawable)?.monochrome != null
        if (hasMonochrome) {
            comparison(body, "AdaptiveIconDrawable.monochrome sin tinte") { monochromeIcon() }
            comparison(body, "Monochrome con tinte de contraste") { dark ->
                monochromeIcon()?.apply { setTint(if (dark) Color.WHITE else Color.BLACK) }
            }
        } else {
            body.addView(label("El icono devuelto no contiene capa monocroma.", 14f))
        }

        val gate = BiometricGate(this)
        val availability = gate.availability()
        val status = label("Biometría fuerte: código $availability", 14f)
        body.addView(status)
        val button = Button(this).apply {
            text = "Abrir diálogo biométrico del sistema"
            isEnabled = availability == BiometricManager.BIOMETRIC_SUCCESS
        }
        body.addView(button)
        button.setOnClickListener {
            button.isEnabled = false
            status.text = "Diálogo abierto; no se accede a claves ni se ejecutan operaciones."
            try {
                biometric = gate.confirm(
                    title = "Diagnóstico de icono",
                    subtitle = "Comprueba el icono mostrado por el sistema",
                    onSuccess = { finishPrompt(button, status, "Autenticación correcta; sin operaciones.") },
                    onCancelled = { finishPrompt(button, status, "Cancelado.") },
                    onError = { finishPrompt(button, status, "Error biométrico: $it") },
                )
            } catch (error: Exception) {
                finishPrompt(button, status, "No se pudo abrir el diálogo: ${error.message}")
            }
        }
    }

    private fun packageIcon(): Drawable = packageManager.getApplicationIcon(applicationInfo)

    private fun monochromeIcon(): Drawable? =
        (packageIcon() as? AdaptiveIconDrawable)?.monochrome?.let { icon ->
            icon.constantState?.newDrawable(resources)?.mutate() ?: icon.mutate()
        }

    private fun comparison(parent: LinearLayout, title: String, sizeDp: Int = 80, icon: (Boolean) -> Drawable?) {
        parent.addView(label(title, 17f))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        parent.addView(row)
        swatch(row, "Claro", Color.rgb(242, 242, 242), icon(false), sizeDp)
        swatch(row, "Oscuro", Color.rgb(23, 23, 23), icon(true), sizeDp)
    }

    private fun rasterComparison(parent: LinearLayout, sizeDp: Int) {
        val pixels = dp(sizeDp)
        val bitmap = Bitmap.createBitmap(pixels, pixels, Bitmap.Config.ARGB_8888)
        packageIcon().apply {
            setBounds(0, 0, pixels, pixels)
            draw(Canvas(bitmap))
        }
        var visible = 0
        var green = 0
        for (y in 0 until pixels) for (x in 0 until pixels) {
            val color = bitmap.getPixel(x, y)
            if (Color.alpha(color) > 128) {
                visible++
                if (Color.green(color) > Color.red(color) + 25 &&
                    Color.green(color) > Color.blue(color) + 5 && Color.green(color) > 80
                ) green++
            }
        }
        comparison(parent, "Canvas directo ${sizeDp} dp: verdes $green / visibles $visible", sizeDp) {
            BitmapDrawable(resources, bitmap)
        }
    }

    private fun swatch(parent: LinearLayout, title: String, background: Int, icon: Drawable?, sizeDp: Int) {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(background)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        parent.addView(cell, LinearLayout.LayoutParams(0, dp(sizeDp + 46), 1f))
        cell.addView(label(title, 14f).apply {
            setTextColor(if (background == Color.rgb(23, 23, 23)) Color.WHITE else Color.BLACK)
        })
        cell.addView(ImageView(this).apply {
            setImageDrawable(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, ViewGroup.LayoutParams(dp(sizeDp), dp(sizeDp)))
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(Color.BLACK)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun finishPrompt(button: Button, status: TextView, message: String) {
        biometric = null
        button.isEnabled = true
        status.text = message
    }

    override fun onDestroy() {
        biometric?.cancel()
        biometric = null
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
