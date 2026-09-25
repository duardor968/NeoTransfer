package dev.duardo.neotransfer.data

import dev.duardo.neotransfer.core.BankHistory
import dev.duardo.neotransfer.core.BankSmsParser
import dev.duardo.neotransfer.core.HistoryDirection
import dev.duardo.neotransfer.core.matchesAccount
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal val bankingZone: ZoneId = ZoneId.of("America/Havana")

/** Invoked inside the repository transaction; a statement never claims a payment reference. */
internal class WalletHistoryStore(private val repository: RoomWalletRepository) {
    private val dao get() = repository.dao

    fun record(history: IncomingHistory?, eventId: String): List<String> {
        if (history == null) return emptyList()
        val existing = dao.historyObservations(eventId)
        if (existing.isNotEmpty()) return existing.map { it.value.entryId }
        val event = checkNotNull(dao.event(eventId)).value
        val message = event.body?.let { BankSmsParser().parse(event.sender, it) } as? BankHistory
            ?: error("El evento no contiene un estado de cuenta reconocido")
        require(message.bank.code == history.bankCode && message.entries.size == history.entries.size)
        require(message.entries.zip(history.entries).all { (parsed, supplied) ->
            parsed.date.toString() == supplied.postedOn && parsed.service == supplied.service &&
                (parsed.direction == HistoryDirection.CREDIT) == supplied.incoming &&
                parsed.amount.currency.name == supplied.currency && sameValue(parsed.amount.amount.toPlainString(), supplied.amount) &&
                parsed.reference == supplied.reference && parsed.transactionNumber == supplied.transactionNumber
        })
        val rows = history.entries.map { item ->
            checkAmount(item.amount); LocalDate.parse(item.postedOn)
            HistoryEntryRecord("", history.bankCode, event.subscriptionId, item.postedOn, item.service,
                item.incoming, item.amount, item.currency, item.reference, item.transactionNumber)
        }
        val counts = rows.filter { !it.reference.isNullOrBlank() }.groupingBy(::financialKey).eachCount()
        val ids = rows.mapIndexed { position, row ->
            val reference = row.reference?.let(::normalizeReference)?.takeIf(String::isNotEmpty)
            val repeated = reference != null && counts[financialKey(row)] != 1
            val candidates = reference?.let(dao::historiesWithReference).orEmpty().filter {
                sameFinancialFacts(it.value, row) && it.value.service == row.service && !it.value.ambiguous
            }
            val reusable = candidates.singleOrNull()?.takeUnless { repeated || !event.evidenceEligible || event.subscriptionId == null }
                ?.takeIf { candidate -> dao.observationsOfHistory(candidate.value.id).any {
                    dao.event(it.value.eventId)?.value?.evidenceEligible == true
                } }
            val id = reusable?.value?.id ?: "history-${digest("$eventId:$position").take(32)}"
            if (reusable == null) dao.put(HistoryRow(row.copy(id = id, ambiguous = repeated || candidates.size > 1), reference))
            dao.put(HistoryObservationRow(HistoryObservationRecord(eventId, position, id)))
            id
        }
        rows.mapNotNull { it.reference?.let(::normalizeReference) }.distinct().forEach(::reconcile)
        return ids
    }

    /** Re-evaluate both sides so a later receipt can remove a duplicate from the activity projection. */
    fun reconcile(reference: String) {
        val history = dao.historiesWithReference(reference)
        if (history.isEmpty()) return
        val receipts = dao.receiptsWithReference(reference).map { it.value }.filter {
            !it.amountIsNominal && !it.referenceConflict && it.bankCode != null && it.subscriptionId != null &&
                dao.event(it.eventId)?.value?.evidenceEligible == true
        }
        val eligibleHistory = history.filter { row -> row.value.subscriptionId != null &&
            dao.observationsOfHistory(row.value.id).any { dao.event(it.value.eventId)?.value?.evidenceEligible == true } }
        val matches = eligibleHistory.associate { row -> row.value.id to receipts.filter { receipt -> matches(row.value, receipt) } }
        val reverse = matches.flatMap { (id, values) -> values.map { it.id to id } }.groupBy({ it.first }, { it.second })
        history.forEach { row ->
            val entry = row.value
            // Restored/ineligible observations keep their archived links; they cannot establish new evidence.
            if (eligibleHistory.none { it.value.id == entry.id }) return@forEach
            val candidates = matches[entry.id].orEmpty()
            val receipt = candidates.singleOrNull()?.takeIf { reverse[it.id]?.size == 1 }
            val related = receipts.filter { it.bankCode == entry.bankCode && it.subscriptionId == entry.subscriptionId &&
                (it.kind == "RECEIVED") == entry.incoming && dao.event(it.eventId)?.value?.let { event ->
                    Instant.ofEpochMilli(event.receivedAt).atZone(bankingZone).toLocalDate().toString() == entry.postedOn
                } == true }
            val discrepancy = related.any { !sameValue(it.amount, entry.amount) || it.currency != entry.currency }
            val ambiguous = candidates.size > 1 || candidates.any { reverse[it.id].orEmpty().size > 1 } ||
                eligibleHistory.any { it.value.id != entry.id && sameFinancialFacts(it.value, entry) &&
                    (it.value.account == null || entry.account == null || compatibleAccounts(it.value.account, entry.account)) }
            val subject = receipt?.let(::receiptSubject)
            val fromQuery = entry.queryOperationId != null
            dao.put(row.copy(value = entry.copy(receiptId = receipt?.id, ambiguous = ambiguous,
                referenceConflict = discrepancy,
                account = if (fromQuery) entry.account else subject?.account,
                registrationId = if (fromQuery) entry.registrationId else subject?.registrationId,
                accountId = if (fromQuery) entry.accountId else subject?.accountId,
                cardId = if (fromQuery) entry.cardId else subject?.cardId)))
        }
    }

    /** The executor calls this only after USSD acknowledgement; persisted evidence is checked again here. */
    fun bind(eventId: String, operationId: String, at: Long): Boolean {
        val event = dao.event(eventId)?.value ?: return false
        val canonical = dao.event(event.canonicalEventId)?.value ?: return false
        val operation = dao.operation(operationId)?.value ?: return false
        if (operation.specId != "bandec.recent-operations" || operation.kind != operation.specId ||
            operation.bankCode != "02" || operation.providerId != "BANDEC" || operation.profileId != "PERSONAL" ||
            operation.restored || operation.amount != null || operation.destination.isNotEmpty() ||
            operation.status !in setOf(OperationStatus.SUBMITTING, OperationStatus.AWAITING_CONFIRMATION, OperationStatus.UNCERTAIN) ||
            !event.evidenceEligible || !canonical.evidenceEligible || event.source == EventSource.LEGACY ||
            event.originalSource != null || canonical.originalSource != null ||
            event.subscriptionId != operation.subscriptionId || event.receivedAt < operation.startedAt ||
            event.receivedAt > at || at - operation.startedAt !in 0..30_000) return false
        val message = event.body?.let { BankSmsParser().parse(event.sender, it) } as? BankHistory ?: return false
        if (message.bank.code != operation.bankCode) return false
        val observations = dao.historyObservations(canonical.id)
        if (observations.size != message.entries.size) return false
        if (operation.source != "0000") {
            if (!operation.source.matches(Regex("[0-9]{16}"))) return false
            val subject = subject(operation.source, operation.registrationId)
            observations.forEach { observation ->
                val row = checkNotNull(dao.history(observation.value.entryId))
                val entry = row.value
                val incompatible = entry.account != null && !compatibleAccounts(entry.account, operation.source)
                val id = if (incompatible) "history-${digest("${entry.id}:${canonical.id}:${operation.source}").take(32)}" else entry.id
                dao.put(HistoryRow(entry.copy(id = id, account = operation.source, registrationId = operation.registrationId,
                    cardId = subject.cardId, accountId = subject.accountId, queryOperationId = operation.id, receiptId = null), row.normalizedReference))
                if (incompatible) dao.put(HistoryObservationRow(observation.value.copy(entryId = id)))
            }
        }
        observations.mapNotNull { dao.history(it.value.entryId)?.normalizedReference }.distinct().forEach(::reconcile)
        dao.put(OperationRow(operation.copy(status = OperationStatus.CONFIRMED, updatedAt = at, reviewRequired = false)))
        return true
    }

    private fun matches(entry: HistoryEntryRecord, receipt: ReceiptRecord): Boolean {
        val event = dao.event(receipt.eventId)?.value ?: return false
        if (receipt.bankCode != entry.bankCode || receipt.subscriptionId != entry.subscriptionId ||
            receipt.currency != entry.currency || !sameValue(receipt.amount, entry.amount) ||
            (receipt.kind == "RECEIVED") != entry.incoming ||
            Instant.ofEpochMilli(event.receivedAt).atZone(bankingZone).toLocalDate().toString() != entry.postedOn) return false
        val subject = receiptSubject(receipt)
        return entry.account == null || subject?.account == null || compatibleAccounts(entry.account, subject.account)
    }

    private data class Subject(val account: String, val registrationId: String?, val accountId: String?, val cardId: String?)
    private fun receiptSubject(receipt: ReceiptRecord): Subject? {
        val operation = receipt.operationId?.let(dao::operation)?.value
        if (operation?.status == OperationStatus.CONFIRMED && operation.source != "0000") return subject(operation.source, operation.registrationId)
        // In service receipts account is an invoice; in outgoing receipts party is the beneficiary, not the source.
        return if (receipt.kind == "RECEIVED") receipt.account?.let { Subject(it, null, null, null) } else null
    }
    private fun subject(account: String, registrationId: String?): Subject {
        val card = dao.cards().map { it.value }.filter { it.registrationId == registrationId && it.number == account }.singleOrNull()
        val bankAccount = dao.accounts().map { it.value }.filter { it.registrationId == registrationId && it.number == account }.singleOrNull()
        return Subject(account, registrationId, bankAccount?.id.takeIf { card == null }, card?.id)
    }
}

private fun financialKey(value: HistoryEntryRecord): List<Any?> = listOf(value.bankCode, value.subscriptionId,
    value.postedOn, value.reference?.let(::normalizeReference), BigDecimal(value.amount).stripTrailingZeros().toPlainString(), value.currency, value.incoming)
private fun sameFinancialFacts(a: HistoryEntryRecord, b: HistoryEntryRecord): Boolean = financialKey(a) == financialKey(b)
private fun sameValue(a: String, b: String): Boolean = BigDecimal(a).compareTo(BigDecimal(b)) == 0
private fun compatibleAccounts(a: String, b: String): Boolean = a == b || matchesAccount(a, b) || matchesAccount(b, a)
