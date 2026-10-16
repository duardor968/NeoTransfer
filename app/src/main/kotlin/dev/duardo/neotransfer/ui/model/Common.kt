package dev.duardo.neotransfer.ui.model

import androidx.compose.runtime.Immutable
import java.math.BigDecimal
import java.time.Instant

/** Banks whose cards can be added to the wallet. */
enum class Bank(val shortName: String, val fullName: String) {
    BPA("BPA", "Banco Popular de Ahorro"),
    BANDEC("BANDEC", "Banco de Crédito y Comercio"),
    BANMET("Metropolitano", "Banco Metropolitano"),
}

@Immutable
data class Money(val amount: BigDecimal, val currency: String)

/** One of the user's own cards. [number] is the full 16-digit PAN; [expiry] is `MM/AA`. */
@Immutable
data class CardUi(
    val id: String,
    val bank: Bank,
    val number: String,
    val currency: String,
    val holder: String? = null,
    val expiry: String? = null,
)

@Immutable
sealed interface BalanceUi {
    /** No balance observed yet for this card. */
    data object Unknown : BalanceUi
    data class Known(val money: Money, val updatedAt: Instant) : BalanceUi
}

/** A wallet card with its projected balance; [refreshing] while a balance query awaits the bank. */
@Immutable
data class WalletCardUi(val card: CardUi, val balance: BalanceUi, val refreshing: Boolean = false)

/** Card that belongs to someone else (contact or typed recipient). [bankName] only when known from the number. */
@Immutable
data class ForeignCardUi(val id: String, val number: String, val bankName: String? = null, val label: String? = null)

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
