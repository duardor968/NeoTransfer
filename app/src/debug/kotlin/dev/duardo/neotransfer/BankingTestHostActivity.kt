package dev.duardo.neotransfer

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/** Visible host for device checks; never instantiates banking access or requests permissions. */
class BankingTestHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply {
            text = "Comprobando NeoTransfer\n\nOperaciones simuladas"
            gravity = Gravity.CENTER
            textSize = 20f
            setPadding(32, 32, 32, 32)
        })
    }
}
