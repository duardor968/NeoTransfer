package dev.duardo.neotransfer.designsystem.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Spacing scale. [gutter] is the horizontal margin of every screen. */
object NeoSpace {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val huge = 48.dp
    val gutter = 20.dp
}

/** Radii. Nested containers subtract their padding so corners stay concentric. */
object NeoRadius {
    val chip = 10.dp
    val field = 14.dp
    val group = 20.dp
    val sheet = 28.dp
}

internal val NeoShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(NeoRadius.chip),
    medium = RoundedCornerShape(NeoRadius.field),
    large = RoundedCornerShape(NeoRadius.group),
    extraLarge = RoundedCornerShape(NeoRadius.sheet),
)

/** Motion: every transition lives between 100 and 500 ms. */
object NeoMotion {
    const val short = 150
    const val medium = 250
    const val long = 400
    val emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val standard = CubicBezierEasing(0.2f, 0f, 0.2f, 1f)
}
