package dev.duardo.neotransfer.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.duardo.neotransfer.designsystem.R
import dev.duardo.neotransfer.designsystem.format.DecimalSeparator
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoMotion

sealed interface KeypadKey {
    data class Digit(val value: Int) : KeypadKey
    data object Decimal : KeypadKey
    data object Backspace : KeypadKey
}

/** On-screen keypad for amounts and PINs. Without [decimal] the bottom-left slot stays empty. */
@Composable
fun NeoKeypad(onKey: (KeypadKey) -> Unit, modifier: Modifier = Modifier, decimal: Boolean = true) {
    val rows = listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7, 8, 9))
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { digit -> Key(Modifier.weight(1f), digit.toString()) { onKey(KeypadKey.Digit(digit)) } }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (decimal) Key(Modifier.weight(1f), DecimalSeparator.toString(), "Separador decimal") { onKey(KeypadKey.Decimal) }
            else Box(Modifier.weight(1f))
            Key(Modifier.weight(1f), "0") { onKey(KeypadKey.Digit(0)) }
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 60.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button, onClick = { onKey(KeypadKey.Backspace) })
                    .semantics { contentDescription = "Borrar" },
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(R.drawable.ic_backspace), null, tint = MaterialTheme.colorScheme.onSurface) }
        }
    }
}

@Composable
private fun Key(modifier: Modifier, label: String, description: String? = null, onClick: () -> Unit) {
    Box(
        modifier
            .heightIn(min = 60.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Medium))
    }
}

/**
 * Amount being typed, as large as the width allows. [input] is the raw keypad text ("300", "12.5");
 * the currency follows in a lighter weight.
 */
@Composable
fun AmountDisplay(input: String, currency: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val empty = input.isEmpty()
    val shown = if (empty) "0" else groupInput(input)
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = if (empty) MaterialTheme.colorScheme.outline else color)) { append(shown) }
        withStyle(SpanStyle(fontSize = 0.45.em, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)) { append(" $currency") }
    }
    BasicText(
        text,
        modifier.fillMaxWidth().semantics { contentDescription = "$shown $currency" },
        style = MaterialTheme.typography.displayLarge.copy(fontSize = 64.sp, lineHeight = 72.sp, textAlign = TextAlign.Center, color = color),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 28.sp, maxFontSize = 64.sp, stepSize = 2.sp),
    )
}

private fun groupInput(input: String): String {
    val cut = input.indexOf(DecimalSeparator)
    val whole = if (cut >= 0) input.substring(0, cut) else input
    val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
    return if (cut >= 0) grouped + input.substring(cut) else grouped
}

/** Secret digits entered so far. */
@Composable
fun PinDots(length: Int, filled: Int, modifier: Modifier = Modifier, error: Boolean = false) {
    Row(modifier.semantics { contentDescription = "$filled de $length dígitos" }, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        repeat(length) { index ->
            val on = index < filled
            val color by animateColorAsState(
                when {
                    error -> Neo.colors.danger
                    on -> MaterialTheme.colorScheme.onSurface
                    else -> Color.Transparent
                },
                tween(NeoMotion.short), label = "pin",
            )
            Box(
                Modifier
                    .size(16.dp)
                    .border(1.5.dp, if (error) Neo.colors.danger else MaterialTheme.colorScheme.outline, CircleShape)
                    .background(color, CircleShape),
            )
        }
    }
}
