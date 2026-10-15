package dev.duardo.neotransfer.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import dev.duardo.neotransfer.designsystem.component.LocalCaptureAllowed
import dev.duardo.neotransfer.ui.common.NeoTransferTheme
import dev.duardo.neotransfer.ui.model.Appearance
import java.lang.reflect.Proxy

/** Theme and the preview clock around a screen; secrets stay capturable for review. */
@Composable
fun PreviewHost(appearance: Appearance, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCaptureAllowed provides true) {
        NeoTransferTheme(appearance, PreviewData.clock) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                content()
            }
        }
    }
}

/** Actions that do nothing: previews are static. */
inline fun <reified T : Any> noop(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, _, _ -> null } as T
