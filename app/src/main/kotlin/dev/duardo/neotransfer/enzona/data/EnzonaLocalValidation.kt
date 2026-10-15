package dev.duardo.neotransfer.enzona.data

import dev.duardo.neotransfer.enzona.EnzonaInvoiceService
import dev.duardo.neotransfer.enzona.EnzonaReadJson
import java.util.UUID

/** Shared by backup decoding and Room restore, before any mutation. */
internal fun validateEnzonaExports(data: List<EnzonaLocalExport>) {
    require(data.map { it.ownerSubject }.distinct().size == data.size) { "Duplicate ENZONA owner" }
    val ids = mutableSetOf<String>()
    data.forEach { owner ->
        require(owner.version == 1 && owner.ownerSubject.isNotBlank()) { "Invalid ENZONA local export" }
        require(owner.cardLinks.map { it.cardUuid }.distinct().size == owner.cardLinks.size) { "Duplicate ENZONA card link" }
        owner.cardLinks.forEach {
            require(it.ownerSubject == owner.ownerSubject) { "ENZONA owner mismatch" }
            validateEnzonaCardLink(it)
        }
        owner.journal.forEach {
            require(it.intent.ownerSubject == owner.ownerSubject) { "ENZONA owner mismatch" }
            require(ids.add(it.intent.id)) { "Duplicate ENZONA intent UUID" }
            validateEnzonaIntent(it.intent)
            require(it.remoteId == null || it.remoteId.isNotBlank())
            require(it.state.isTerminal() || it.remoteId == null)
        }
        require(owner.transactions.map { it.transactionUuid }.distinct().size == owner.transactions.size) { "Duplicate ENZONA transaction UUID" }
        owner.transactions.forEach {
            require(it.ownerSubject == owner.ownerSubject) { "ENZONA owner mismatch" }
            validateEnzonaTransaction(it)
        }
    }
}

internal fun validateEnzonaTransaction(value: EnzonaStoredTransaction) {
    require(value.ownerSubject.isNotBlank() && value.transactionUuid.isNotBlank() && value.observedAt >= 0)
    require(EnzonaCacheSanitizer.sanitizeTransaction(value.json) == value.json) { "Transaction JSON must be sanitized and canonical" }
    require(EnzonaReadJson.transaction(value.json).transactionUuid == value.transactionUuid) { "Transaction UUID mismatch" }
}

internal fun validateEnzonaCardLink(link: EnzonaCardLink) {
    require(link.ownerSubject.isNotBlank() && link.cardUuid.isNotBlank())
    require(link.pan == null || link.pan.length == 16 && link.pan.all { it in '0'..'9' }) { "A known full PAN is required" }
    require(link.alias == null || link.alias.isNotBlank())
}

internal fun validateEnzonaIntent(intent: EnzonaIntent) {
    require(intent.ownerSubject.isNotBlank())
    require(runCatching { UUID.fromString(intent.id).toString() == intent.id }.getOrDefault(false)) { "Local UUID is required" }
    require(intent.cardUuid.isNotBlank() && intent.destination.value.isNotBlank())
    if (intent.type == EnzonaIntentType.ACCOUNT_TRANSFER) require(!intent.destination.username.isNullOrBlank())
    else require(intent.destination.username == null)
    if (intent.type == EnzonaIntentType.INVOICE_PAYMENT) {
        val service = EnzonaInvoiceService.entries.firstOrNull { it.name == intent.invoiceService }
        require(service != null && !intent.invoiceOwner.isNullOrBlank()) { "Invoice service and owner are required" }
        if (service == EnzonaInvoiceService.ELECTRICITY) {
            require(!intent.invoiceFolio.isNullOrBlank() && !intent.invoiceRoute.isNullOrBlank() && !intent.invoicePeriod.isNullOrBlank()) {
                "Electricity invoice folio, route and period are required"
            }
        } else require(intent.invoiceFolio == null && intent.invoiceRoute == null && intent.invoicePeriod == null) {
            "Unexpected electricity invoice fields"
        }
    } else require(intent.invoiceService == null && intent.invoiceOwner == null && intent.invoiceFolio == null &&
        intent.invoiceRoute == null && intent.invoicePeriod == null) { "Invoice fields require INVOICE_PAYMENT" }
    require(intent.amount.toBigDecimalOrNull()?.signum() == 1) { "Positive exact amount is required" }
    require(intent.currency.isNotBlank() && intent.preparedAt >= 0)
}

internal fun EnzonaIntentState.isTerminal(): Boolean = this == EnzonaIntentState.CONFIRMED || this == EnzonaIntentState.REJECTED
