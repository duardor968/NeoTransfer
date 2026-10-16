package dev.duardo.neotransfer.core.sms

import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Money
import java.time.Instant
import java.time.LocalDate

/**
 * Meaning of one bank SMS: PAGOxMOVIL's, or a bank's own alert ([CardOperation]). Messages are backend data: the UI never shows their text.
 * Masks (`920406XXXXXX1234`) are kept as sent; balances written `DB x` in receipts are the balance x.
 */
sealed interface SmsMessage {
    /** "Usted se ha autenticado…"; BANDEC names the card the session is on. */
    data class Authenticated(val bank: Bank, val cardMask: String?) : SmsMessage

    /** Authentication refused. [wrongPin] only for an explicit "Clave incorrecta". */
    data class AuthenticationFailed(val bank: Bank?, val wrongPin: Boolean, val reason: String) : SmsMessage

    /** "Usted tiene una solicitud en proceso…": the last request sent was not executed. */
    data object Busy : SmsMessage

    data class Balance(val bank: Bank, val rows: List<BalanceRow>) : SmsMessage

    /** Last operations (`48`), as listed by the bank. Rows no receipt reported become movements. */
    data class History(val bank: Bank, val entries: List<HistoryEntry>) : SmsMessage

    /** Outgoing transfer receipt. [sourceMask] is null when the default account paid ("Ordenante: CUP"). */
    data class TransferSent(
        val bank: Bank, val beneficiaryMask: String, val sourceMask: String?, override val amount: Money,
        override val reference: String, val balance: Money?,
    ) : Receipt

    /** "El titular del telefono … le ha realizado una transferencia a la cuenta …": a credit to [account]. */
    data class TransferReceived(val account: String, val senderPhone: String, override val amount: Money, override val reference: String) : Receipt

    /** Impersonal "Se ha realizado una transferencia a la cuenta …": a copy, or ambiguous when alone. */
    data class TransferNotice(val account: String, override val amount: Money, override val reference: String) : Receipt

    /** QR or online purchase. [amount] is what was paid. */
    data class PaymentCompleted(
        val bank: Bank, val merchant: String?, val purchaseId: String?, val price: Money?, override val amount: Money,
        override val reference: String, val balance: Money?,
    ) : Receipt

    /** Service receipt. [amount] is the debit; [nominal] what the service was credited, when it differs or is the only figure. */
    data class ServicePaid(
        val bank: Bank, val service: PaidService, val account: String?, val nominal: Money?, override val amount: Money,
        override val reference: String, val balance: Money?,
    ) : Receipt

    /** Impersonal copy of a Nauta receipt ("Se ha pagado/recargado la cuenta …"). */
    data class ServiceNotice(val service: PaidService, val account: String, override val amount: Money, override val reference: String) : Receipt

    /** The bank refused an operation; [reason] is ready to show. */
    data class Rejected(val bank: Bank?, val operation: RejectedOperation, val reason: String, val detail: String? = null) : SmsMessage

    /**
     * A bank's own alert of a card operation made outside the app ("BANDEC >> Operacion en Linea Sucursal por …"):
     * [label] says what it was. The text names no currency, so [amount] is CUP until the card says otherwise. Alerts
     * leave in batches, hours or weeks after the operation: their time is not the operation's.
     */
    data class CardOperation(
        val bank: Bank, val card: String, val label: String, val credit: Boolean, override val amount: Money, override val reference: String,
    ) : Receipt

    /** Cubacel phone balance received from another number; not bank money. */
    data class MobileBalanceReceived(val amount: Money, val fromPhone: String) : SmsMessage

    /** Registration welcome; it carries secrets, so nothing is extracted. */
    data object Registration : SmsMessage

    data object Unrecognized : SmsMessage
}

/** Messages that identify a bank transaction by its reference. */
sealed interface Receipt : SmsMessage {
    val reference: String
    val amount: Money
}

enum class PaidService { NAUTA_RECHARGE, NAUTA_HOME, MOBILE_RECHARGE, ELECTRICITY, PHONE, WATER, STAMP }

enum class RejectedOperation {
    BALANCE, TRANSFER, PAYMENT, MOBILE_RECHARGE, NAUTA_RECHARGE, NAUTA_HOME, NAUTA_HOME_DEBT, ELECTRICITY, PHONE, WATER,
    /** Card association (`60`); the app never sends it. */
    CARD_ASSOCIATION,
    /** Out-of-scope operations (fixed-term deposits…). */
    OTHER,
    /** A refusal that does not say what it refuses. */
    UNKNOWN,
}

/** One balance line. BANDEC gives the card mask and both balances; BPA only the available one. */
data class BalanceRow(val account: String?, val label: String?, val ledger: Money?, val available: Money)

data class HistoryEntry(val date: LocalDate, val description: String, val credit: Boolean, val amount: Money, val reference: String?)

/**
 * An ingested bank SMS. [sentAt] orders them: the SMSC stamp (`date_sent`) for PAGOxMOVIL, reliable for that
 * sender; the reception time for BANDEC's alerts, whose stamp is 11 hours off. [receivedAt] is the phone's
 * reception time (`date`).
 */
data class SmsRecord(val id: Long, val sentAt: Instant, val receivedAt: Instant, val message: SmsMessage) {
    companion object {
        const val SENDER = "PAGOxMOVIL"
        /** BANDEC's own alerts of card operations made at the bank (configured at kiosco.bandec.cu). */
        const val BANDEC_ALERTS = "BANDEC"
        val SENDERS = listOf(SENDER, BANDEC_ALERTS)

        /** Builds a record from `content://sms` columns; null for other senders. */
        fun from(id: Long, address: String, date: Long, dateSent: Long, body: String): SmsRecord? {
            val sender = SENDERS.firstOrNull { it.equals(address, ignoreCase = true) } ?: return null
            val received = Instant.ofEpochMilli(date)
            val sent = if (sender == SENDER && dateSent > 0) Instant.ofEpochMilli(dateSent) else received
            return SmsRecord(id, sent, received, SmsParser.parse(body))
        }
    }
}
