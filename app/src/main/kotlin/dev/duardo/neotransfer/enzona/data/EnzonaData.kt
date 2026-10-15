package dev.duardo.neotransfer.enzona.data

import androidx.room.Embedded

/** Only remote read responses belong here; session and payment authorization bodies do not. */
enum class EnzonaReadKind { ACCOUNT, CARDS, BALANCE, HISTORY }

data class EnzonaCachedRead(
    val ownerSubject: String,
    val kind: EnzonaReadKind,
    val resourceId: String,
    val observedAt: Long,
    val json: String,
) {
    override fun toString(): String = "EnzonaCachedRead(redacted)"
}

/** A known full PAN is optional; neither a remote UUID nor last4 can supply it. */
data class EnzonaCardLink(
    val ownerSubject: String,
    val cardUuid: String,
    val pan: String? = null,
    val alias: String? = null,
) {
    override fun toString(): String = "EnzonaCardLink(redacted)"
}

enum class EnzonaIntentType { CARD_TRANSFER, ACCOUNT_TRANSFER, QR_MERCHANT_PAYMENT, INVOICE_PAYMENT }
enum class EnzonaIntentState { PREPARED, SUBMITTING, UNKNOWN, CONFIRMED, REJECTED }

/** PAN, recipient account UUID, vendor identity code, or invoice client_id. Only account transfer needs username. */
data class EnzonaDestination(val value: String, val username: String? = null) {
    override fun toString(): String = "EnzonaDestination(redacted)"
}

/** Exact reviewed values. No payment password, fingerprint or encoded request is accepted. */
data class EnzonaIntent(
    val id: String,
    val ownerSubject: String,
    val cardUuid: String,
    val type: EnzonaIntentType,
    @Embedded(prefix = "destination_") val destination: EnzonaDestination,
    val amount: String,
    val currency: String,
    val description: String? = null,
    val phone: String? = null,
    val preparedAt: Long,
    val invoiceService: String? = null,
    val invoiceOwner: String? = null,
    val invoiceFolio: String? = null,
    val invoiceRoute: String? = null,
    val invoicePeriod: String? = null,
) {
    override fun toString(): String = "EnzonaIntent(redacted)"
}

data class EnzonaJournalEntry(
    @Embedded val intent: EnzonaIntent,
    val state: EnzonaIntentState = EnzonaIntentState.PREPARED,
    val remoteId: String? = null,
    val restored: Boolean = false,
) {
    override fun toString(): String = "EnzonaJournalEntry(redacted)"
}

/** A decoded and sanitized remote transaction, keyed by its evidenced UUID and exact owner. */
data class EnzonaStoredTransaction(
    val ownerSubject: String,
    val transactionUuid: String,
    val observedAt: Long,
    val json: String,
) {
    override fun toString(): String = "EnzonaStoredTransaction(redacted)"
}

/** Versioned local DTO. Read-window cache is omitted; retained history is not payment confirmation. */
data class EnzonaLocalExport(
    val ownerSubject: String,
    val cardLinks: List<EnzonaCardLink>,
    val journal: List<EnzonaJournalEntry>,
    val version: Int = 1,
    val transactions: List<EnzonaStoredTransaction> = emptyList(),
) {
    override fun toString(): String = "EnzonaLocalExport(redacted)"
}
