package dev.duardo.neotransfer.ui.model

import androidx.compose.runtime.Immutable
import dev.duardo.neotransfer.designsystem.format.NoBreak
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import java.math.BigDecimal
import java.time.Instant

/**
 * Issuers of the cards the wallet holds: the three banks and Fincimex's Clásica. The Clásica is no bank: MiTransfer
 * operates it, always in USD, and its key is MiTransfer's PIN.
 */
enum class Bank(val shortName: String, val fullName: String) {
    BPA("BPA", "Banco Popular de Ahorro"),
    BANDEC("BANDEC", "Banco de Crédito y Comercio"),
    BANMET("Metropolitano", "Banco Metropolitano"),
    CLASSIC("Clásica", "Clásica de Fincimex"),
}

@Immutable
data class Money(val amount: BigDecimal, val currency: String)

/**
 * One of the user's own cards. [number] is the full 16-digit PAN; [expiry] is `MM/AA`. [alias] is the user's own name
 * for it («Nómina»): it only labels the card in the app and never goes to the bank.
 */
@Immutable
data class CardUi(
    val id: String,
    val bank: Bank,
    val number: String,
    val currency: String,
    val holder: String? = null,
    val expiry: String? = null,
    val alias: String? = null,
) {
    /** What names the card where it is picked or recognized: its alias, or its whole number. */
    val title: String get() = alias ?: formatCardNumber(number)

    /** `•••• 6783`, never split across lines. */
    val lastDigits: String get() = "••••$NoBreak${number.takeLast(4)}"

    /** Under an alias, what tells the card apart: `BPA · CUP · •••• 6783`. */
    val identity: String get() = listOf(bank.shortName, currency, lastDigits).joinToString("$NoBreak·$NoBreak")
}

/** Longest alias a card takes: it has to fit a picker's line and Activity's card filter. */
const val CARD_ALIAS_LENGTH = 20

@Immutable
sealed interface BalanceUi {
    /** No balance observed yet for this card. */
    data object Unknown : BalanceUi
    data class Known(val money: Money, val updatedAt: Instant) : BalanceUi
    /** The Clásica's balance as MiTransfer wrote it, which the app does not read into a figure; [at] is when it came. */
    data class Text(val text: String, val at: Instant) : BalanceUi
}

/** A wallet card with its projected balance; [refreshing] while a balance query awaits the bank. */
@Immutable
data class WalletCardUi(val card: CardUi, val balance: BalanceUi, val refreshing: Boolean = false)

/** Card that belongs to someone else (contact or typed recipient). [bank] and [currency] only as far as the number tells. */
@Immutable
data class ForeignCardUi(val id: String, val number: String, val bank: Bank? = null, val currency: String? = null, val label: String? = null) {
    /** `BANDEC · CUP`, never split across lines. */
    val issuer: String? get() = issuerOf(bank, currency)
}

fun issuerOf(bank: Bank?, currency: String?): String? = listOfNotNull(bank?.shortName, currency).joinToString("$NoBreak·$NoBreak").ifEmpty { null }

/** [fromAddressBook]: name and phones come from the phone's address book. [last]: the newest movement with them. */
@Immutable
data class ContactUi(
    val id: String,
    val name: String,
    val cards: List<ForeignCardUi>,
    val phones: List<String>,
    val fromAddressBook: Boolean = false,
    val last: LastMovementUi? = null,
)

@Immutable
data class LastMovementUi(val kind: OperationKind, val amount: Money?, val at: Instant)

/** Destination of a transfer: a card, named when it belongs to a contact. */
@Immutable
data class RecipientUi(val card: ForeignCardUi, val name: String? = null, val contactId: String? = null)

enum class Appearance(val label: String) { System("Sistema"), Light("Claro"), Dark("Oscuro") }
