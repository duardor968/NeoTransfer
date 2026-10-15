package dev.duardo.neotransfer.designsystem.card

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.designsystem.theme.CardPrintFamily
import dev.duardo.neotransfer.designsystem.theme.Neo
import java.text.Normalizer

/** ISO/IEC 7810 ID-1: 85.60 × 53.98 mm. */
const val CardAspectRatio = 85.60f / 53.98f

/** Corner radius of an ID-1 card (3.18 mm) relative to its height. */
private const val CornerPercent = 6

val CardShape = RoundedCornerShape(percent = CornerPercent)

/**
 * Where a line is printed on the physical card. [x] and [maxWidth] are fractions of the card width,
 * [baseline] a fraction of its height and [size] the font size as a fraction of the width.
 */
@Immutable
data class CardPrint(val x: Float, val baseline: Float, val size: Float, val maxWidth: Float)

@Immutable
data class CardFaceLayout(
    val number: CardPrint,
    val holder: CardPrint,
    val currency: CardPrint,
    val expiry: CardPrint,
)

/**
 * A bank card drawn 1:1: the issuer's artwork with the number, holder, expiry and currency printed where and
 * how the card printer puts them. Print scales with the card, never with the user's font size, like an image.
 */
@Composable
fun CardFace(
    art: Painter,
    layout: CardFaceLayout,
    number: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    holder: String? = null,
    expiry: String? = null,
    currency: String? = null,
    elevation: Dp = 10.dp,
    onClick: (() -> Unit)? = null,
) {
    val measurer = rememberTextMeasurer(cacheSize = 8)
    val ink = Neo.colors.cardInk
    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(CardAspectRatio)
            .shadow(elevation, CardShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CardShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .clearAndSetSemantics {
                this.contentDescription = contentDescription
                if (onClick != null) role = Role.Button
            },
    ) {
        Image(art, null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Canvas(Modifier.matchParentSize()) {
            if (number.isNotEmpty()) printLine(measurer, formatCardNumber(number), layout.number, ink)
            holder?.takeIf { it.isNotBlank() }?.let { printLine(measurer, it.asPrinted(), layout.holder, ink) }
            currency?.let { printLine(measurer, it, layout.currency, ink) }
            expiry?.takeIf { it.isNotBlank() }?.let { printLine(measurer, "VENCE: $it", layout.expiry, ink) }
        }
        Box(Modifier.matchParentSize().cardFinish())
    }
}

/** Card printers emboss plain capitals: no accents. */
private fun String.asPrinted(): String =
    Normalizer.normalize(trim().uppercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

private fun DrawScope.printLine(measurer: TextMeasurer, text: String, spec: CardPrint, ink: Color) {
    val nominal = spec.size * size.width
    fun measure(px: Float) = measurer.measure(
        text, TextStyle(fontFamily = CardPrintFamily, fontSize = px.toSp(), color = ink),
        softWrap = false, maxLines = 1, density = this, layoutDirection = layoutDirection,
    )
    var result = measure(nominal)
    val available = spec.maxWidth * size.width
    if (result.size.width > available) result = measure(nominal * available / result.size.width)
    drawText(result, topLeft = Offset(spec.x * size.width, spec.baseline * size.height - result.firstBaseline))
}

/** Laminate: a soft diagonal sheen and a hairline edge, the only effect added to the artwork. */
private fun Modifier.cardFinish(): Modifier = this
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.16f),
                0.42f to Color.White.copy(alpha = 0f),
                0.75f to Color.Black.copy(alpha = 0f),
                1f to Color.Black.copy(alpha = 0.08f),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            ),
        )
    }
    .border(0.75.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.10f))), CardShape)
