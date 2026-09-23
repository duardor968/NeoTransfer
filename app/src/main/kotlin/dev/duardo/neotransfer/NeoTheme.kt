package dev.duardo.neotransfer

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.platform.LocalView

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val inter = FontFamily(
    Font(R.font.inter, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.inter, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.inter, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
)

@Composable
fun NeoTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val view = LocalView.current
    SideEffect {
        (view.context as? android.app.Activity)?.window?.insetsController?.apply {
            setSystemBarsAppearance(if (dark) 0 else android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        }
    }
    val colors = if (dark) darkColorScheme(
        primary = Color(0xFF3DDC97), onPrimary = Color(0xFF092B1C), background = Color(0xFF0E0F12),
        surface = Color(0xFF0E0F12), surfaceContainer = Color(0xFF17191E), surfaceContainerHigh = Color(0xFF1C1F25),
        surfaceContainerLowest = Color(0xFF0E0F12), surfaceContainerLow = Color(0xFF17191E), surfaceContainerHighest = Color(0xFF262A31),
        onSurface = Color(0xFFF2F4F6), onSurfaceVariant = Color(0xFFA9ADB5), outline = Color(0xFF555A64),
        surfaceVariant = Color(0xFF1C1F25), outlineVariant = Color(0xFF343943),
        secondary = Color(0xFF3DDC97), onSecondary = Color(0xFF092B1C),
        inverseSurface = Color(0xFFE9EEEB), inverseOnSurface = Color(0xFF151917),
        secondaryContainer = Color(0xFF19382C), onSecondaryContainer = Color(0xFF3DDC97),
    ) else lightColorScheme(
        primary = Color(0xFF1FB36F), onPrimary = Color(0xFF092B1C), background = Color.White,
        surface = Color.White, surfaceContainer = Color(0xFFF3F5F4), surfaceContainerHigh = Color(0xFFE9EEEB),
        surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8FAF9), surfaceContainerHighest = Color(0xFFDCE4DF),
        onSurface = Color(0xFF151917), onSurfaceVariant = Color(0xFF5C665F), outline = Color(0xFF77847D),
        surfaceVariant = Color(0xFFE9EEEB), outlineVariant = Color(0xFFCCD5D0),
        secondary = Color(0xFF075F36), onSecondary = Color.White,
        inverseSurface = Color(0xFF262A31), inverseOnSurface = Color(0xFFF2F4F6),
        secondaryContainer = Color(0xFFD7F6E6), onSecondaryContainer = Color(0xFF075F36),
    )
    val type = Typography()
    MaterialTheme(colorScheme = colors, typography = Typography(
        displayLarge = type.displayLarge.copy(fontFamily = inter, fontFeatureSettings = "tnum"),
        displayMedium = type.displayMedium.copy(fontFamily = inter, fontFeatureSettings = "tnum"),
        headlineLarge = type.headlineLarge.copy(fontFamily = inter), headlineMedium = type.headlineMedium.copy(fontFamily = inter),
        titleLarge = type.titleLarge.copy(fontFamily = inter), titleMedium = type.titleMedium.copy(fontFamily = inter),
        bodyLarge = type.bodyLarge.copy(fontFamily = inter, fontFeatureSettings = "tnum"), bodyMedium = type.bodyMedium.copy(fontFamily = inter, fontFeatureSettings = "tnum"),
        bodySmall = type.bodySmall.copy(fontFamily = inter, fontFeatureSettings = "tnum"), labelLarge = type.labelLarge.copy(fontFamily = inter),
        labelMedium = type.labelMedium.copy(fontFamily = inter), labelSmall = type.labelSmall.copy(fontFamily = inter),
    ), content = content)
}
