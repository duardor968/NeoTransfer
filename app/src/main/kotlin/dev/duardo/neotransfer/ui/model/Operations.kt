package dev.duardo.neotransfer.ui.model

import androidx.compose.runtime.Immutable
import java.time.Instant

/**
 * Lifecycle of an operation sent to the bank.
 * Sent: acknowledged, awaiting the result. NoResponse: 15 min without a result (a late one still closes it).
 * NotExecuted: the bank was busy and dropped it; money is never resent automatically.
 */
enum class OperationStatus { Sent, Confirmed, Rejected, NoResponse, NotExecuted }

enum class OperationKind(val incoming: Boolean = false) {
    Transfer,
    Incoming(incoming = true),
    QrPayment,
    Electricity,
    Water,
    Landline,
    NautaHome,
    NautaWifi,
    MobileTopUp,
    CubacelPlan,
    CubacelTransfer,
}

/** The other side of an operation, as identified by the bank's receipt. */
@Immutable
sealed interface CounterpartUi {
    data class Card(val number: String, val bankName: String? = null) : CounterpartUi
    data class Phone(val number: String) : CounterpartUi
    /** Service reference (client code, account, phone) or merchant payment ID. */
    data class Reference(val value: String) : CounterpartUi
}

/**
 * A movement or an operation in flight. [title] is already human: contact name, merchant, saved reference
 * name, or the counterpart itself when nothing better is known.
 */
@Immutable
data class OperationUi(
    val id: String,
    val kind: OperationKind,
    val title: String,
    val status: OperationStatus,
    val at: Instant,
    val amount: Money? = null,
    val card: CardUi? = null,
    val counterpart: CounterpartUi? = null,
    val contactId: String? = null,
    val reason: String? = null,
    val transactionId: String? = null,
    val balanceAfter: Money? = null,
)
