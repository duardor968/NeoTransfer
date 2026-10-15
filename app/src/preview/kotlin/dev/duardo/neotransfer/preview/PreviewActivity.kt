package dev.duardo.neotransfer.preview

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.activity.compose.BackHandler
import dev.duardo.neotransfer.designsystem.component.NeoRow
import dev.duardo.neotransfer.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.ui.model.Appearance

/**
 * Opens one screen with invented data:
 * `adb shell am start -n dev.duardo.neotransfer.preview/dev.duardo.neotransfer.preview.PreviewActivity
 *  --es screen home --es theme DARK --ef fontScale 1.5`. Without `screen`, lists every id.
 */
class PreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val appearance = when (intent.getStringExtra("theme")?.uppercase()) {
            "LIGHT" -> Appearance.Light
            "DARK" -> Appearance.Dark
            else -> Appearance.System
        }
        // Scale the activity's own configuration so sheets and dialogs (separate windows) follow it too.
        val fontScale = intent.getFloatExtra("fontScale", 0f)
        if (fontScale > 0f) {
            val configuration = Configuration(resources.configuration).apply { this.fontScale = fontScale }
            @Suppress("DEPRECATION")
            resources.updateConfiguration(configuration, resources.displayMetrics)
        }
        val initial = intent.getStringExtra("screen")
        setContent {
            var screen by rememberSaveable { mutableStateOf(initial) }
            val shown = screen
            PreviewHost(appearance) {
                if (shown != null && shown in PreviewCatalog.ids) {
                    BackHandler(enabled = initial == null) { screen = null }
                    PreviewCatalog.content(shown)
                } else {
                    CatalogList { screen = it }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
internal fun CatalogList(pick: (String) -> Unit) {
    androidx.compose.material3.Scaffold(topBar = { NeoTopBar("Pantallas", large = true) }) { padding ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = padding) {
            items(PreviewCatalog.ids) { id -> NeoRow(id, onClick = { pick(id) }) }
        }
    }
}
