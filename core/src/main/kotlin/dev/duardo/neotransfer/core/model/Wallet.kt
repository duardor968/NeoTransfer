package dev.duardo.neotransfer.core.model

import kotlinx.serialization.Serializable

/** A card the user owns. Numbers are stored complete; nothing is masked in the app. */
@Serializable
data class OwnCard(val number: String, val bank: Bank, val currency: Currency = Currency.CUP, val alias: String? = null) {
    init { require(Cards.isNumber(number)) { "La tarjeta debe tener 16 dígitos" } }
}

/** A person in the user's own directory: several phones and cards; [labels] names some cards ("Salario"), by number. */
@Serializable
data class Contact(
    val id: String,
    val name: String,
    val phones: List<String> = emptyList(),
    val cards: List<String> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
)

enum class ServiceKind { ELECTRICITY, PHONE, WATER_HAVANA, WATER_OTHER, WATER_VARADERO, NAUTA, NAUTA_HOME, MOBILE }

/** A saved service identifier (invoice, Nauta account, phone) with an optional label. */
@Serializable
data class ServiceReference(val kind: ServiceKind, val value: String, val label: String? = null)

object Cards {
    fun isNumber(value: String): Boolean = value.length == 16 && value.all { it in '0'..'9' }

    /** Digits only; null unless exactly 16 remain (spaces and dashes are tolerated). */
    fun normalize(value: String): String? = value.filter { it !in " -" }.takeIf(::isNumber)

    /** Fincimex's personal Clásica (`976003…`), the only card MiTransfer operates for NeoTransfer. The business one is `976004`. */
    fun isClassic(value: String): Boolean = isNumber(value) && value.startsWith("976003")

    /**
     * Bank masks look like `920406XXXXXX1234` or `9204XXXXXXXX1234`. A mask needs at least
     * eight visible digits to identify a card.
     */
    fun matches(mask: String, number: String): Boolean =
        mask.length == 16 && number.length == 16 && mask.count { it in '0'..'9' } >= 8 &&
            mask.indices.all { mask[it] == number[it] || mask[it] in "Xx*" }

    /**
     * What a number, whole or while typed, says about its card, read as Transfermóvil reads it. On national cards
     * (`92…`) digits 5–6 are the bank and digits 3–4 the product: dollars for 25, 26, 28, 29, 35 and 45 (Transfermóvil's
     * list) and 40 (BANDEC's Tropical, which it misses), unknown for 50, pesos for the rest. Fincimex's Clásica (`9760`)
     * and AIS (`9560`) are dollar cards of no bank.
     */
    fun issuer(digits: String): Issuer {
        if (digits.length !in 4..16 || !digits.all { it in '0'..'9' }) return Issuer(null, null)
        if (digits.startsWith("9760") || digits.startsWith("9560")) return Issuer(null, Currency.USD)
        if (!digits.startsWith("92")) return Issuer(null, null)
        val bank = if (digits.length < 6) null else when (digits.substring(4, 6)) {
            "12" -> Bank.BPA
            "06" -> Bank.BANDEC
            "95" -> Bank.BANMET
            else -> null
        }
        val currency = when (digits.substring(2, 4)) {
            in DollarProducts -> Currency.USD
            "50" -> null
            else -> Currency.CUP
        }
        return Issuer(bank, currency)
    }

    private val DollarProducts = setOf("25", "26", "28", "29", "35", "40", "45")
}

/** The bank and currency a card number tells; null where it does not. */
data class Issuer(val bank: Bank?, val currency: Currency?)

object Phones {
    /** Cuban mobile or landline as its last eight digits; accepts `+53`, `53` and `0053` prefixes. */
    fun normalize(value: String): String? {
        val digits = value.filter { it in '0'..'9' }
        return when {
            digits.length == 8 -> digits
            digits.length == 10 && digits.startsWith("53") -> digits.drop(2)
            digits.length == 12 && digits.startsWith("0053") -> digits.drop(4)
            else -> null
        }
    }

    /**
     * What a phone field keeps of a keystroke or a paste: at most eight digits. A `+53` or `0053` typed key by key
     * passes through more digits before [normalize] can drop it.
     */
    fun input(value: String): String {
        normalize(value)?.let { return it }
        val digits = value.filter { it in '0'..'9' }
        val prefixed = (digits.startsWith("53") && digits.length < 10) || (digits.startsWith("0053") && digits.length < 12)
        return if (prefixed) digits else digits.take(8)
    }

    /** Why [digits], as a phone field keeps them, are not a Cuban mobile yet; null when they are or nothing is typed. */
    fun mobileProblem(digits: String): String? = when {
        digits.isEmpty() -> null
        normalize(digits) == null -> "Número incompleto"
        normalize(digits)!![0] !in "56" -> "No es un móvil cubano"
        else -> null
    }
}

/** Returns the contact only when exactly one contact owns the phone or card. */
fun List<Contact>.identify(phone: String? = null, card: String? = null): Contact? {
    val wantedPhone = phone?.let(Phones::normalize)
    val wantedCard = card?.let(Cards::normalize)
    if (wantedPhone == null && wantedCard == null) return null
    return filter { contact ->
        (wantedPhone != null && contact.phones.any { Phones.normalize(it) == wantedPhone }) ||
            (wantedCard != null && contact.cards.any { Cards.normalize(it) == wantedCard })
    }.singleOrNull()
}
