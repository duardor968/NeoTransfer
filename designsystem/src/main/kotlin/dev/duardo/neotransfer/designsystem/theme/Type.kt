package dev.duardo.neotransfer.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.duardo.neotransfer.designsystem.R

@OptIn(ExperimentalTextApi::class)
private fun inter(weight: Int) = Font(
    R.font.inter,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Inter, variable. Every style enables tabular figures so amounts, times and card numbers align in columns. */
val InterFamily = FontFamily(inter(400), inter(500), inter(600), inter(700))

/** Arial-metric face used to print on card art, matching the thermal print of BPA and BANDEC cards. */
val CardPrintFamily = FontFamily(Font(R.font.arimo, FontWeight.Normal))

private const val Tabular = "tnum"

private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun style(size: Int, line: Int, weight: Int, tracking: Double = 0.0) = TextStyle(
    fontFamily = InterFamily,
    fontWeight = FontWeight(weight),
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
    fontFeatureSettings = Tabular,
    lineHeightStyle = trim,
)

internal val NeoTypography = Typography(
    displayLarge = style(46, 54, 600, -0.025),
    displayMedium = style(38, 46, 600, -0.02),
    displaySmall = style(30, 38, 600, -0.015),
    headlineLarge = style(30, 36, 600, -0.015),
    headlineMedium = style(26, 32, 600, -0.01),
    headlineSmall = style(22, 28, 600, -0.005),
    titleLarge = style(20, 26, 600),
    titleMedium = style(16, 22, 600),
    titleSmall = style(14, 20, 600),
    bodyLarge = style(16, 24, 400),
    bodyMedium = style(14, 20, 400),
    bodySmall = style(13, 18, 400),
    labelLarge = style(15, 20, 600),
    labelMedium = style(13, 16, 500),
    labelSmall = style(12, 16, 500),
)
