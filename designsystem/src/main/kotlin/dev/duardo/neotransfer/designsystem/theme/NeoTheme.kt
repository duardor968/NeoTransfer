package dev.duardo.neotransfer.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember

@Composable
fun NeoTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: NeoAccent = NeoAccents.Mint,
    content: @Composable () -> Unit,
) {
    val colors = remember(darkTheme, accent) { neoColors(accent, darkTheme) }
    val scheme = remember(colors) { neoColorScheme(colors) }
    CompositionLocalProvider(LocalNeoColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = NeoTypography, shapes = NeoShapes) {
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}

object Neo {
    val colors: NeoColors
        @Composable @ReadOnlyComposable get() = LocalNeoColors.current
}
