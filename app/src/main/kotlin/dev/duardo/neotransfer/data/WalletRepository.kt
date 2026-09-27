package dev.duardo.neotransfer.data

import kotlinx.coroutines.flow.Flow
import dev.duardo.neotransfer.core.fuel.FuelCoupon
import dev.duardo.neotransfer.core.fuel.FuelEnvelopeProtector
import dev.duardo.neotransfer.core.fuel.FuelSmsUpdate
import dev.duardo.neotransfer.core.fuel.ProtectedFuelEnvelope

/** Persistent metadata only. Credentials and encoded modem commands never belong here. */
data class IdentityRecord(val id: String, val label: String, val documentNumber: String? = null)

data class RegistrationRecord(
    val id: String, val identityId: String, val providerId: String, val profileId: String,
    val bankCode: String?, val subscriptionId: Int?, val linePhone: String?, val label: String,
    val credentialAlias: String? = null, val enabled: Boolean = true,
    val previousSubscriptionId: Int? = null, val previousLinePhone: String? = null,
)

data class AccountRecord(
    val id: String, val registrationId: String, val number: String, val label: String,
    val currency: String? = null, val isDefault: Boolean = false, val favorite: Boolean = false,
    val profileId: String? = null,
)

data class CardRecord(
    val id: String, val registrationId: String, val number: String, val label: String,
    val accountId: String? = null, val currency: String? = null,
    val isDefault: Boolean = false, val favorite: Boolean = false,
    val profileId: String? = null,
    val holderName: String? = null, val expiry: String? = null,
)

data class ContactPhone(val number: String, val label: String = "")
data class ContactCard(val number: String, val label: String = "", val bankCode: String? = null)
data class ContactRecord(
    val id: String, val name: String, val phones: List<ContactPhone> = emptyList(),
    val cards: List<ContactCard> = emptyList(), val favorite: Boolean = false,
)

data class SavedServiceRecord(
    val id: String, val kind: String, val label: String, val identifier: String,
    val registrationId: String? = null, val amount: String? = null, val currency: String? = null,
    val fieldKey: String? = null,
)

enum class OperationStatus { PREPARED, SUBMITTING, AWAITING_CONFIRMATION, UNCERTAIN, CONFIRMED, REJECTED, CANCELLED }

data class QrDetails(
    val transactionId: String, val provider: String, val amount: String, val currency: String,
    val auxiliary: String, val description: String, val descriptionHint: String,
    val qrId: String?, val validFrom: String?, val validThrough: String?,
)

/** Read-only legacy HTTP evidence retained for schema and backup compatibility. */
data class BulevarHttpEvidence(
    val requestId: String, val specId: String, val httpStatus: Int, val responseCode: String,
    val receivedAt: Long, val reference: String? = null,
)

data class OperationRecord(
    val id: String, val kind: String, val bankCode: String?, val subscriptionId: Int,
    val destination: String, val amount: String?, val currency: String?, val startedAt: Long,
    val registrationId: String? = null, val source: String = "0000", val phone: String? = null,
    val description: String = "", val qr: QrDetails? = null,
    val status: OperationStatus = OperationStatus.PREPARED, val updatedAt: Long = startedAt,
    val refreshTaken: Boolean = false, val restored: Boolean = false,
    val reviewRequired: Boolean = false, val legacyState: String? = null,
    val specId: String = kind, val providerId: String? = null, val profileId: String = "PERSONAL",
    val parameters: Map<String, String> = emptyMap(),
    val httpEvidence: BulevarHttpEvidence? = null,
    val timeoutAt: Long? = null,
)

enum class EventSource { BROADCAST, INBOX, LEGACY }

/** One delivery per source; original timestamps survive cross-delivery deduplication. */
data class EventRecord(
    val id: String, val source: EventSource, val sourceId: String?, val sender: String,
    val body: String?, val receivedAt: Long, val subscriptionId: Int?,
    val canonicalEventId: String, val bodyWithheld: Boolean = false,
    val originalSource: EventSource? = null, val originalSourceId: String? = null,
    val evidenceEligible: Boolean = true,
    val sentAt: Long? = null,
)

data class ReceiptRecord(
    val id: String, val eventId: String, val bankCode: String?, val subscriptionId: Int?,
    val kind: String, val reference: String?, val amount: String, val currency: String,
    val party: String, val account: String? = null, val purchaseId: String? = null,
    val bankDate: String? = null, val nominalAmount: String? = null,
    val amountIsNominal: Boolean = false, val operationId: String? = null,
    val referenceConflict: Boolean = false,
)

data class MovementRecord(
    val id: String, val receiptId: String, val kind: String, val amount: String,
    val currency: String, val occurredAt: Long, val incoming: Boolean,
)

/** Calendar-day statement evidence. It never creates or confirms a payment operation. */
data class HistoryEntryRecord(
    val id: String, val bankCode: String, val subscriptionId: Int?, val postedOn: String,
    val service: String, val incoming: Boolean, val amount: String, val currency: String,
    val reference: String?, val transactionNumber: String? = null,
    val account: String? = null, val registrationId: String? = null,
    val accountId: String? = null, val cardId: String? = null,
    val queryOperationId: String? = null, val receiptId: String? = null,
    val ambiguous: Boolean = false, val referenceConflict: Boolean = false,
)
data class HistoryObservationRecord(val eventId: String, val position: Int, val entryId: String)
data class IncomingHistoryEntry(
    val postedOn: String, val service: String, val incoming: Boolean,
    val amount: String, val currency: String, val reference: String?, val transactionNumber: String? = null,
)
data class IncomingHistory(val bankCode: String, val entries: List<IncomingHistoryEntry>)

data class BalanceRecord(
    val id: String, val bankCode: String, val subscriptionId: Int, val at: Long,
    val account: String?, val available: String, val currency: String,
    val ledger: String? = null, val label: String? = null, val registrationId: String? = null,
    val cardId: String? = null, val accountId: String? = null,
    val sentAt: Long? = null,
)

data class UsedReference(val bankCode: String, val reference: String, val operationId: String? = null)
data class LegacyMetadataRecord(val key: String, val safeValue: String, val issue: String? = null)
/** Immutable, non-secret purchase facts captured before the source body is withheld. */
data class FuelPurchaseEvidence(val couponId: String, val serial: String, val bankCode: String?,
    val subscriptionId: Int?, val bankReference: String, val tmReference: String,
    val paidAmount: String, val currency: String, val receivedAt: Long, val evidenceEligible: Boolean,
    val sentAt: Long? = null)

data class FuelObservation(val eventId: String, val position: Int, val kind: String, val result: String,
    val couponId: String? = null, val creditedAmount: String? = null, val receiptId: String? = null, val rejectedRows: Int = 0,
    val purchaseEvidence: FuelPurchaseEvidence? = null)
data class RefreshRequest(val bankCode: String, val subscriptionId: Int, val dueAt: Long)
data class WalletSettings(
    val activeBankCode: String = "02", val subscriptionId: Int = -1,
    val selectedRegistrationId: String? = null, val selectedCardId: String? = null,
    val selectedAccountId: String? = null,
)

data class WalletSnapshot(
    val identities: List<IdentityRecord> = emptyList(),
    val registrations: List<RegistrationRecord> = emptyList(),
    val accounts: List<AccountRecord> = emptyList(), val cards: List<CardRecord> = emptyList(),
    val contacts: List<ContactRecord> = emptyList(), val services: List<SavedServiceRecord> = emptyList(),
    val operations: List<OperationRecord> = emptyList(), val events: List<EventRecord> = emptyList(),
    val receipts: List<ReceiptRecord> = emptyList(), val movements: List<MovementRecord> = emptyList(),
    val balances: List<BalanceRecord> = emptyList(), val usedReferences: List<UsedReference> = emptyList(),
    val settings: WalletSettings = WalletSettings(), val refresh: RefreshRequest? = null,
    val migrationIssues: List<String> = emptyList(),
    val legacyMetadata: List<LegacyMetadataRecord> = emptyList(),
    val histories: List<HistoryEntryRecord> = emptyList(),
    val historyObservations: List<HistoryObservationRecord> = emptyList(),
    val fuelCoupons: List<FuelCoupon> = emptyList(),
    val fuelObservations: List<FuelObservation> = emptyList(),
)

data class IncomingEvent(
    val source: EventSource, val sourceId: String?, val sender: String, val body: String,
    val receivedAt: Long, val subscriptionId: Int?, val receipt: ReceiptRecord? = null,
    val evidenceEligible: Boolean = true,
    val history: IncomingHistory? = null,
    val fuelUpdate: FuelSmsUpdate? = null,
    val sentAt: Long? = null,
) {
    override fun toString(): String = "IncomingEvent(source=$source, sourceId=$sourceId)"
}
data class IngestResult(val eventId: String, val canonicalEventId: String, val receiptId: String?, val duplicate: Boolean,
    val historyIds: List<String> = emptyList(), val fuelCouponIds: List<String> = emptyList(), val fuelReceiptIds: List<String> = emptyList())
data class MigrationResult(val alreadyMigrated: Boolean, val importedEntries: Int, val issues: List<String>)
data class RestoreResult(val importedEntries: Int, val conflicts: List<String>, val registrationsToReassociate: List<String>)

/** All non-Flow calls are blocking IO and must run off the Android main thread. */
interface WalletRepository : AutoCloseable {
    fun snapshot(): WalletSnapshot
    fun observe(): Flow<WalletSnapshot>
    fun putIdentity(value: IdentityRecord)
    fun deleteIdentity(id: String)
    fun putRegistration(value: RegistrationRecord)
    fun deleteRegistration(id: String)
    fun putAccount(value: AccountRecord)
    fun deleteAccount(id: String)
    fun putCard(value: CardRecord)
    fun deleteCard(id: String)
    fun putContact(value: ContactRecord)
    fun deleteContact(id: String)
    fun putService(value: SavedServiceRecord)
    fun deleteService(id: String)
    fun putOperation(value: OperationRecord)
    fun updateOperationStatus(id: String, expected: OperationStatus, status: OperationStatus, at: Long): Boolean
    /** A wait expired; the operation may still receive later positive evidence. */
    fun expireWaitingOperation(id: String, at: Long): Boolean
    fun setSettings(value: WalletSettings)
    fun setRefresh(value: RefreshRequest?)
    fun replaceBalances(bankCode: String, subscriptionId: Int, values: List<BalanceRecord>)
    fun upsertBalances(values: List<BalanceRecord>)
    fun markReferenceUsed(value: UsedReference): Boolean
    fun ingest(value: IncomingEvent, fuelProtector: FuelEnvelopeProtector? = null): IngestResult
    fun confirmOperation(operationId: String, receiptId: String, at: Long, refresh: RefreshRequest?): Boolean
    /** Only parsed authentication/balance evidence may complete these non-financial operations. */
    fun completeQuery(operationId: String, eventId: String, at: Long): Boolean
    fun bindHistoryToQuery(eventId: String, queryOperationId: String, at: Long): Boolean
    fun pendingTransferNotifications(): List<ReceiptRecord>
    fun markTransferNotificationDelivered(receiptId: String)
    fun migrateLegacy(values: Map<String, *>): MigrationResult
    fun updateFuelCouponPresentation(couponId: String, label: String, archived: Boolean)
    fun protectedFuelEnvelopes(expected: List<FuelCoupon>): List<ProtectedFuelEnvelope>
    fun importSnapshot(value: WalletSnapshot, fuelEnvelopes: List<ProtectedFuelEnvelope> = emptyList()): RestoreResult
}
