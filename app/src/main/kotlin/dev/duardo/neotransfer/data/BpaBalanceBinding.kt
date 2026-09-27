package dev.duardo.neotransfer.data

import dev.duardo.neotransfer.core.Bank
import dev.duardo.neotransfer.core.BankMessage

internal data class BpaBalanceBinding(val balance: BalanceRecord, val queries: List<OperationRecord>)

/** Service 46 sends an explicit source, but BPA's flat reply does not echo it. */
internal fun bpaBalanceBinding(
    snapshot: WalletSnapshot, event: EventRecord, message: BankMessage.Balance, now: Long,
): BpaBalanceBinding? {
    val row = message.accounts.singleOrNull() ?: return null
    if (message.bank != Bank.BPA || row.account != null || row.label != null ||
        !event.evidenceEligible || event.source == EventSource.LEGACY || event.originalSource != null) return null
    val canonical = if (event.canonicalEventId == event.id) event else
        snapshot.events.singleOrNull { it.id == event.canonicalEventId } ?: return null
    if (!canonical.evidenceEligible || canonical.source == EventSource.LEGACY || canonical.originalSource != null ||
        canonical.subscriptionId != event.subscriptionId) return null
    val currency = row.available.currency.name
    val queries = snapshot.operations.filter { query ->
        query.specId == "bpa.balance" && query.kind == query.specId && query.providerId == "BPA" &&
            query.bankCode == "01" && query.profileId == "PERSONAL" && !query.restored &&
            query.amount == null && query.destination.isEmpty() && query.subscriptionId == event.subscriptionId &&
            query.status in setOf(OperationStatus.SUBMITTING, OperationStatus.AWAITING_CONFIRMATION, OperationStatus.UNCERTAIN) &&
            event.after(query, now) && canonical.after(query, now) &&
            (query.source != "0000" || query.parameters["sourceCurrency"] == null || query.parameters["sourceCurrency"] == currency)
    }
    // Repeated reads of the same source can identify the instrument, not the individual query. A default
    // or different outstanding source cannot be disambiguated by this response format.
    val target = queries.map { it.registrationId to it.source }.distinct().singleOrNull() ?: return null
    if (!target.second.matches(Regex("[0-9]{16}"))) return null
    val registration = snapshot.registrations.singleOrNull { it.id == target.first && it.enabled &&
        it.providerId == "BPA" && it.bankCode == "01" && it.profileId == "PERSONAL" &&
        it.subscriptionId == event.subscriptionId } ?: return null
    val cards = snapshot.cards.filter { it.registrationId == registration.id && it.number == target.second }
    val accounts = snapshot.accounts.filter { it.registrationId == registration.id && it.number == target.second }
    if (cards.size + accounts.size != 1) return null
    val card = cards.singleOrNull()
    val account = accounts.singleOrNull()
    val linkedAccount = card?.accountId?.let { id -> snapshot.accounts.singleOrNull { it.id == id } }
    if (listOfNotNull(card?.currency, account?.currency, linkedAccount?.currency).any { it != currency }) return null
    val productId = card?.id ?: requireNotNull(account).id
    return BpaBalanceBinding(BalanceRecord(
        "01:${event.subscriptionId}:${registration.id}:$productId:${target.second}:$currency",
        "01", requireNotNull(event.subscriptionId), event.receivedAt, null, row.available.amount.toPlainString(), currency,
        ledger = row.ledger?.amount?.toPlainString(), registrationId = registration.id,
        cardId = card?.id, accountId = account?.id, sentAt = event.sentAt,
    ), queries)
}
