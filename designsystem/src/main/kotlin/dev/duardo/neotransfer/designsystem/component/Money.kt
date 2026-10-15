package dev.duardo.neotransfer.designsystem.component

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.em
import dev.duardo.neotransfer.designsystem.format.DecimalSeparator
import dev.duardo.neotransfer.designsystem.format.formatAmount
import java.math.BigDecimal

enum class MoneySign { None, Plus, Minus }

/**
 * An amount with its currency. In [hero] mode cents and currency step down in size, the way a balance is read:
 * the whole units first.
 */
@Composable
fun MoneyText(
    amount: BigDecimal,
    currency: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    color: Color = LocalContentColor.current,
    sign: MoneySign = MoneySign.None,
    hero: Boolean = false,
) {
    val formatted = formatAmount(amount)
    val prefix = when (sign) { MoneySign.Plus -> "+"; MoneySign.Minus -> "−"; MoneySign.None -> "" }
    val muted = color.copy(alpha = if (hero) 0.55f else 0.7f)
    val text = buildAnnotatedString {
        if (hero) {
            val cut = formatted.lastIndexOf(DecimalSeparator)
            append(prefix + formatted.substring(0, cut))
            withStyle(SpanStyle(fontSize = 0.62.em, color = muted)) { append(formatted.substring(cut)) }
            withStyle(SpanStyle(fontSize = 0.42.em, fontWeight = FontWeight.Medium, color = muted)) { append(" $currency") }
        } else {
            append(prefix + formatted)
            withStyle(SpanStyle(fontSize = 0.8.em, fontWeight = FontWeight.Medium, color = muted)) { append(" $currency") }
        }
    }
    Text(text, modifier, style = style, color = color, maxLines = 1, softWrap = false)
}
