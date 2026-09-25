package dev.duardo.neotransfer.data

import dev.duardo.neotransfer.core.BankMessage
import dev.duardo.neotransfer.core.BankSmsParser
import dev.duardo.neotransfer.core.BankHistory
import dev.duardo.neotransfer.core.HistoryDirection
import dev.duardo.neotransfer.core.FinancialMovement
import dev.duardo.neotransfer.platform.BankSmsRecord
import java.time.Instant
import dev.duardo.neotransfer.core.fuel.FuelEnvelopeProtector
import dev.duardo.neotransfer.core.fuel.FuelSmsParser

data class SmsIngestResult(
    val stored: IngestResult, val message: BankMessage, val movement: FinancialMovement?,
    val evidenceEligible: Boolean,
) {
    val newFinancialMovement: Boolean get() = movement != null && evidenceEligible && !stored.duplicate
}

/** Blocking IO; usable by an inbox worker or receiver without a controller, session or PIN. */
class SmsIngestor(
    private val repository: WalletRepository,
    private val parser: BankSmsParser = BankSmsParser(),
    private val now: () -> Instant = Instant::now,
    private val fuelProtector: FuelEnvelopeProtector? = null,
) {
    fun ingest(
        record: BankSmsRecord,
        source: EventSource = if (record.id == null) EventSource.BROADCAST else EventSource.INBOX,
        message: BankMessage = parser.parse("PAGOxMOVIL", record.body) ?: BankMessage.Unrecognized,
    ): SmsIngestResult {
        val financial = FinancialMovement.from(message)
        val observedAt = now()
        val eligible = record.receivedAt <= observedAt
        val receipt = financial?.let { movement -> ReceiptRecord(
            id = "", eventId = "", bankCode = movement.bank?.code, subscriptionId = record.subscriptionId,
            kind = movement.kind.name, reference = movement.reference,
            amount = movement.amount.amount.toPlainString(), currency = movement.amount.currency.name,
            party = movement.party, account = movement.account, purchaseId = movement.purchaseId,
            bankDate = movement.bankDate, nominalAmount = movement.nominalAmount?.amount?.toPlainString(),
            amountIsNominal = movement.amountIsNominal,
        ) }
        val history = (message as? BankHistory)?.let { statement -> IncomingHistory(statement.bank.code,
            statement.entries.map { IncomingHistoryEntry(it.date.toString(), it.service, it.direction == HistoryDirection.CREDIT,
                it.amount.amount.toPlainString(), it.amount.currency.name, it.reference, it.transactionNumber) }) }
        val deliveryId = if (source == EventSource.BROADCAST) record.deliveryId else record.id?.toString()
        val fuel = FuelSmsParser.parse("PAGOxMOVIL", record.body, record.receivedAt.toEpochMilli(), record.subscriptionId, evidenceEligible = eligible)
        val stored = fuel.use { repository.ingest(IncomingEvent(source, deliveryId, "PAGOxMOVIL", record.body,
            record.receivedAt.toEpochMilli(), record.subscriptionId, receipt, eligible, history, it), fuelProtector) }
        return SmsIngestResult(stored, message, financial, eligible)
    }
}
