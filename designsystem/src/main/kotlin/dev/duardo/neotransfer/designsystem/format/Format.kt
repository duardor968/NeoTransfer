package dev.duardo.neotransfer.designsystem.format

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Decimal point, as the banks print amounts, and a space between groups of three: a comma reads as a decimal
 * separator to many people. Fixed so the UI never depends on device locale.
 */
private val symbols = DecimalFormatSymbols(Locale.ROOT).apply {
    decimalSeparator = DecimalSeparator
    groupingSeparator = NoBreak
}

const val DecimalSeparator = '.'

/** `12480.5` → `12 480.50`, never split across lines. */
fun formatAmount(amount: BigDecimal): String =
    DecimalFormat("#,##0.00", symbols).format(amount.setScale(2, RoundingMode.HALF_UP))

/** `12480.5`, `CUP` → `12 480.50 CUP`. */
fun formatMoney(amount: BigDecimal, currency: String): String = "${formatAmount(amount)} $currency"

/** No-break space: what joins the groups of a number so a line never splits it. */
const val NoBreak = ' '

/** The same text with its spaces made unbreakable, for values that must stay on one line ("12 480.50 CUP"). */
fun String.unbroken(): String = replace(' ', NoBreak)

/** Groups a PAN as printed on the card, never split across lines: `9204 1234 5678 9012`. Partial input is grouped as typed. */
fun formatCardNumber(digits: String): String = digits.filter(Char::isDigit).chunked(4).joinToString(NoBreak.toString())

/** Cuban numbers as Android's Contacts writes them, never split across lines: `5 3123456`, `7 8302214`, `32 291234`. */
fun formatPhone(digits: String): String {
    val d = digits.filter(Char::isDigit)
    val split = phoneSplit(d)
    return if (d.length == 8 && split > 0) "${d.substring(0, split)}$NoBreak${d.substring(split)}" else d
}

/**
 * Where Cuba's numbering splits a number (libphonenumber's rules, which Android uses): after the 5 or 6 of mobiles
 * and the 7 of Havana, after the two-digit code of the other provinces; 0 when it does not.
 */
fun phoneSplit(digits: String): Int = when {
    digits.isEmpty() -> 0
    digits[0] in "567" -> 1
    digits[0] in "34" || (digits[0] == '2' && digits.getOrNull(1)?.let { it in "1234" } == true) -> 2
    else -> 0
}

/** Up to two initials from a display name. */
fun initialsOf(name: String): String = name.trim().split(Regex("\\s+"))
    .filter { it.isNotEmpty() && it.first().isLetter() }
    .let { words -> listOfNotNull(words.firstOrNull(), words.drop(1).lastOrNull()) }
    .joinToString("") { it.first().uppercase() }
