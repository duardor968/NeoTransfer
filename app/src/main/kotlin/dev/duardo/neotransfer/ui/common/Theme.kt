package dev.duardo.neotransfer.ui.common

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.duardo.neotransfer.designsystem.theme.NeoAccents
import dev.duardo.neotransfer.designsystem.theme.NeoTheme
import dev.duardo.neotransfer.ui.model.Appearance
import java.time.Clock

/** Time source for relative labels ("hace 5 min"); fixed in previews and tests. */
val LocalClock = staticCompositionLocalOf<Clock> { Clock.systemDefaultZone() }

@Composable
fun NeoTransferTheme(appearance: Appearance = Appearance.System, clock: Clock = Clock.systemDefaultZone(), content: @Composable () -> Unit) {
    val dark = when (appearance) {
        Appearance.System -> isSystemInDarkTheme()
        Appearance.Light -> false
        Appearance.Dark -> true
    }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalClock provides clock) {
        NeoTheme(darkTheme = dark, accent = NeoAccents.Mint, content = content)
    }
}
