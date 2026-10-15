package dev.duardo.neotransfer.core.model

import kotlinx.serialization.Serializable

/** A card the user owns. Numbers are stored complete; nothing is masked in the app. */
@Serializable
data class OwnCard(val number: String, val bank: Bank, val currency: Currency = Currency.CUP, val alias: String? = null) {
    init { require(Cards.isNumber(number)) { "La tarjeta debe tener 16 dígitos" } }
}

/** A person in the user's own directory. A contact may have several phones and cards. */
@Serializable
data class Contact(val id: String, val name: String, val phones: List<String> = emptyList(), val cards: List<String> = emptyList())

enum class ServiceKind { ELECTRICITY, PHONE, WATER_HAVANA, WATER_OTHER, WATER_VARADERO, NAUTA, NAUTA_HOME, MOBILE }

/** A saved service identifier (invoice, Nauta account, phone) with an optional label. */
@Serializable
data class ServiceReference(val kind: ServiceKind, val value: String, val label: String? = null)

object Cards {
    fun isNumber(value: String): Boolean = value.length == 16 && value.all { it in '0'..'9' }

    /** Digits only; null unless exactly 16 remain (spaces and dashes are tolerated). */
    fun normalize(value: String): String? = value.filter { it !in " -" }.takeIf(::isNumber)

    /**
     * Bank masks look like `920406XXXXXX1234` or `9204XXXXXXXX1234`. A mask needs at least
     * eight visible digits to identify a card.
     */
    fun matches(mask: String, number: String): Boolean =
        mask.length == 16 && number.length == 16 && mask.count { it in '0'..'9' } >= 8 &&
            mask.indices.all { mask[it] == number[it] || mask[it] in "Xx*" }
}

object Phones {
    /** Cuban mobile or landline as its last eight digits; accepts `+53`/`53` prefixes. */
    fun normalize(value: String): String? {
        val digits = value.filter { it in '0'..'9' }
        return when {
            digits.length == 8 -> digits
            digits.length == 10 && digits.startsWith("53") -> digits.drop(2)
            else -> null
        }
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
