package dev.duardo.neotransfer.designsystem.format

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** Decimal point and comma grouping, as the banks print amounts. Fixed so the UI never depends on device locale. */
private val symbols = DecimalFormatSymbols(Locale.ROOT).apply {
    decimalSeparator = '.'
    groupingSeparator = ','
}

const val DecimalSeparator = '.'

/** `12480.5` → `12,480.50`. */
fun formatAmount(amount: BigDecimal): String =
    DecimalFormat("#,##0.00", symbols).format(amount.setScale(2, RoundingMode.HALF_UP))

/** `12480.5`, `CUP` → `12,480.50 CUP`. */
fun formatMoney(amount: BigDecimal, currency: String): String = "${formatAmount(amount)} $currency"

/** No-break space: what joins the groups of a number so a line never splits it. */
const val NoBreak = ' '

/** The same text with its spaces made unbreakable, for values that must stay on one line ("12,480.50 CUP"). */
fun String.unbroken(): String = replace(' ', NoBreak)

/** Groups a PAN as printed on the card, never split across lines: `9204 1234 5678 9012`. Partial input is grouped as typed. */
fun formatCardNumber(digits: String): String = digits.filter(Char::isDigit).chunked(4).joinToString(NoBreak.toString())

/** Cuban 8-digit numbers, never split across lines: `5312 3456`. */
fun formatPhone(digits: String): String {
    val d = digits.filter(Char::isDigit)
    return if (d.length == 8) "${d.substring(0, 4)}$NoBreak${d.substring(4)}" else d
}

/** Up to two initials from a display name. */
fun initialsOf(name: String): String = name.trim().split(Regex("\\s+"))
    .filter { it.isNotEmpty() && it.first().isLetter() }
    .let { words -> listOfNotNull(words.firstOrNull(), words.drop(1).lastOrNull()) }
    .joinToString("") { it.first().uppercase() }
