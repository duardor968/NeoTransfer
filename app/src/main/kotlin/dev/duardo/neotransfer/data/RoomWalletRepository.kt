package dev.duardo.neotransfer.data

import android.content.Context
import androidx.room.Room
import dev.duardo.neotransfer.core.BankMessage
import dev.duardo.neotransfer.core.BankSmsParser
import dev.duardo.neotransfer.core.Currency
import dev.duardo.neotransfer.core.Money
import dev.duardo.neotransfer.core.ProfileId
import dev.duardo.neotransfer.core.ProviderId
import dev.duardo.neotransfer.core.ProviderIdentity
import dev.duardo.neotransfer.core.ServiceReceiptCorrelation
import dev.duardo.neotransfer.core.FinancialMovement
import dev.duardo.neotransfer.core.fuel.FuelCoupon
import dev.duardo.neotransfer.core.fuel.FuelEnvelopeProtector
import dev.duardo.neotransfer.core.fuel.ProtectedFuelEnvelope
import dev.duardo.neotransfer.core.matchesAccount
import dev.duardo.neotransfer.platform.smsEvidenceAfter
import dev.duardo.neotransfer.platform.smsEvidenceTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.time.Instant
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Callable

class RoomWalletRepository internal constructor(internal val database: WalletDatabase) : WalletRepository {
    internal val dao = database.wallet()

    override fun snapshot(): WalletSnapshot = database.runInTransaction(Callable {
        val phones = dao.phones().groupBy { it.contactId }
        val cards = dao.contactCards().groupBy { it.contactId }
        WalletSnapshot(
            identities = dao.identities().map { it.value }, registrations = dao.registrations().map { it.value },
            accounts = dao.accounts().map { it.value }, cards = dao.cards().map { it.value },
            contacts = dao.contacts().map { c -> ContactRecord(c.id, c.name,
                phones[c.id].orEmpty().map { ContactPhone(it.number, it.label) },
                cards[c.id].orEmpty().map { ContactCard(it.number, it.label, it.bankCode) }, c.favorite) },
            services = dao.services().map { it.value }, operations = dao.operations().map { it.value },
            events = dao.events().map { it.value }, receipts = dao.receipts().map { it.value },
            movements = dao.movements().map { it.value }, balances = dao.balances().map { it.value },
            usedReferences = dao.usedReferences().map { it.value },
            settings = dao.settings()?.value ?: WalletSettings(), refresh = dao.refresh()?.value,
            migrationIssues = dao.legacyArchive().mapNotNull { it.issue },
            legacyMetadata = dao.legacyArchive().map { LegacyMetadataRecord(it.key, it.safeValue, it.issue) },
            histories = dao.histories().map { it.value }, historyObservations = dao.historyObservations().map { it.value },
            fuelCoupons = dao.fuelCoupons().map { it.value }, fuelObservations = dao.fuelObservations().map { it.value },
        )
    })

    override fun observe(): Flow<WalletSnapshot> = dao.observeRevision().map { snapshot() }.flowOn(Dispatchers.IO)

    internal fun <T> write(block: () -> T): T = database.runInTransaction(Callable {
        val result = block()
        val revision = (dao.metadata("revision")?.toLong() ?: 0L) + 1
        dao.put(MetadataRow("revision", revision.toString()))
        result
    })

    override fun putIdentity(value: IdentityRecord) = write {
        require(value.id.isNotBlank() && value.label.isNotBlank())
        dao.put(IdentityRow(value))
    }
    override fun deleteIdentity(id: String) = write { dao.deleteIdentity(id) }

    override fun putRegistration(value: RegistrationRecord) = write {
        require(value.id.isNotBlank() && value.providerId.isNotBlank() && value.profileId.isNotBlank())
        require(value.subscriptionId == null || value.subscriptionId >= 0)
        dao.registrations().singleOrNull { it.value.id == value.id }?.value?.let {
            require(it.identityId == value.identityId && it.providerId == value.providerId &&
                it.profileId == value.profileId && it.bankCode == value.bankCode) { "La identidad de un registro no se modifica" }
        }
        require(dao.registrations().none { row -> row.value.let { other ->
            other.id != value.id && other.providerId == value.providerId && other.profileId == value.profileId &&
                ((value.linePhone != null && other.linePhone == value.linePhone) ||
                    (value.subscriptionId != null && other.subscriptionId == value.subscriptionId))
        } }) { "Ya existe ese registro para la línea" }
        dao.put(RegistrationRow(value))
    }
    override fun deleteRegistration(id: String) = write {
        dao.deleteRegistration(id)
        val settings = dao.settings()?.value
        if (settings?.selectedRegistrationId == id) dao.put(SettingsRow(value = settings.copy(
            selectedRegistrationId = null, selectedCardId = null, selectedAccountId = null)))
    }

    override fun putAccount(value: AccountRecord) = write {
        require(value.id.isNotBlank())
        value.currency?.let(::checkCurrency)
        val registration = dao.registrations().singleOrNull { it.value.id == value.registrationId }?.value
            ?: error("Registro no encontrado")
        val walletWithoutNumber = value.number.isEmpty() && registration.providerId == "MITRANSFER" &&
            registration.profileId == "PERSONAL" && (value.profileId ?: registration.profileId) == "PERSONAL" &&
            value.currency in setOf("CUP", "USD")
        require(value.number.isNotBlank() || walletWithoutNumber)
        dao.accounts().singleOrNull { it.value.id == value.id }?.value?.let {
            require(it.registrationId == value.registrationId) { "La cuenta no se mueve a otro registro" }
        }
        val others = dao.accounts().map { it.value }.filter { it.id != value.id && it.registrationId == value.registrationId }
        require(others.none { sameAccountIdentity(it, value, registration.profileId) }) { "La cuenta ya existe en este registro" }
        if (value.isDefault) others.filter { it.isDefault }.forEach { dao.put(AccountRow(it.copy(isDefault = false))) }
        dao.put(AccountRow(value))
    }
    override fun deleteAccount(id: String) = write {
        dao.deleteAccount(id)
        dao.settings()?.value?.takeIf { it.selectedAccountId == id }?.let {
            dao.put(SettingsRow(value = it.copy(selectedAccountId = null)))
        }
        Unit
    }

    override fun putCard(value: CardRecord) = write {
        require(value.id.isNotBlank() && value.number.isNotBlank())
        validateCardMetadata(value)
        value.currency?.let(::checkCurrency)
        dao.cards().singleOrNull { it.value.id == value.id }?.value?.let {
            require(it.registrationId == value.registrationId) { "La tarjeta no se mueve a otro registro" }
        }
        if (value.accountId != null) require(dao.accounts().any {
            it.value.id == value.accountId && it.value.registrationId == value.registrationId
        }) { "La cuenta pertenece a otro registro" }
        val others = dao.cards().map { it.value }.filter { it.id != value.id && it.registrationId == value.registrationId }
        require(others.none { it.number == value.number }) { "La tarjeta ya existe en este registro" }
        if (value.isDefault) others.filter { it.isDefault }.forEach { dao.put(CardRow(it.copy(isDefault = false))) }
        dao.put(CardRow(value))
    }
    override fun deleteCard(id: String) = write {
        dao.deleteCard(id)
        dao.settings()?.value?.takeIf { it.selectedCardId == id }?.let {
            dao.put(SettingsRow(value = it.copy(selectedCardId = null)))
        }
        Unit
    }

    override fun putContact(value: ContactRecord) = write {
        require(value.id.isNotBlank() && value.name.isNotBlank())
        require(value.phones.all { it.number.isNotBlank() } && value.cards.all { it.number.isNotBlank() })
        require(value.phones.map { canonicalPhone(it.number) }.distinct().size == value.phones.size)
        require(value.cards.map { it.number }.distinct().size == value.cards.size)
        dao.put(ContactRow(value.id, value.name, value.favorite))
        dao.deletePhones(value.id); dao.deleteContactCards(value.id)
        value.phones.forEachIndexed { index, phone -> dao.put(PhoneRow(value.id, index, phone.number, phone.label)) }
        value.cards.forEachIndexed { index, card -> dao.put(ContactCardRow(value.id, index, card.number, card.label, card.bankCode)) }
    }
    override fun deleteContact(id: String) = write { dao.deleteContact(id) }

    override fun putService(value: SavedServiceRecord) = write {
        require(value.id.isNotBlank() && value.kind.isNotBlank() && value.label.isNotBlank() && value.identifier.isNotBlank())
        value.amount?.let(::checkAmount)
        value.currency?.let(::checkCurrency)
        require(value.amount == null || value.currency != null)
        dao.put(ServiceRow(value))
    }
    override fun deleteService(id: String) = write { dao.deleteService(id) }

    override fun putOperation(value: OperationRecord) = write {
        validateOperation(value)
        val old = dao.operation(value.id)?.value
        require(value.httpEvidence == old?.httpEvidence) { "El resultado HTTP requiere evidencia correlacionada" }
        require(value.status != OperationStatus.CONFIRMED || old?.status == OperationStatus.CONFIRMED) { "La confirmación requiere un comprobante" }
        if (old != null) {
            require(old.status == value.status || allowedTransition(old.status, value.status))
            if (old.status != OperationStatus.PREPARED) require(sameRequest(old, value)) { "Una solicitud enviada no se modifica" }
        }
        dao.put(OperationRow(value))
    }
    override fun updateOperationStatus(id: String, expected: OperationStatus, status: OperationStatus, at: Long): Boolean = write {
        require(status != OperationStatus.CONFIRMED) { "La confirmación requiere un comprobante" }
        val old = dao.operation(id)?.value ?: return@write false
        if (old.status != expected || !allowedTransition(expected, status)) return@write false
        if (old.restored && status == OperationStatus.SUBMITTING) return@write false
        dao.put(OperationRow(old.copy(status = status, updatedAt = at)))
        true
    }

    override fun expireWaitingOperation(id: String, at: Long): Boolean = write {
        val old = dao.operation(id)?.value ?: return@write false
        if (old.restored || old.status !in setOf(OperationStatus.SUBMITTING, OperationStatus.AWAITING_CONFIRMATION) ||
            at < old.startedAt || at < old.updatedAt) return@write false
        dao.put(OperationRow(old.copy(status = OperationStatus.UNCERTAIN, updatedAt = at,
            reviewRequired = false, timeoutAt = at)))
        true
    }

    override fun setSettings(value: WalletSettings) = write {
        require(value.subscriptionId >= -1)
        val registration = value.selectedRegistrationId?.let { id -> dao.registrations().singleOrNull { it.value.id == id }?.value
            ?: error("Registro no encontrado") }
        if (value.selectedCardId != null) require(dao.cards().any {
            it.value.id == value.selectedCardId && it.value.registrationId == registration?.id
        })
        if (value.selectedAccountId != null) require(dao.accounts().any {
            it.value.id == value.selectedAccountId && it.value.registrationId == registration?.id
        })
        require(value.selectedCardId == null || value.selectedAccountId == null)
        dao.put(SettingsRow(value = value))
    }
    override fun setRefresh(value: RefreshRequest?) = write { putRefresh(value) }
    private fun putRefresh(value: RefreshRequest?) { if (value == null) dao.deleteRefresh() else dao.put(RefreshRow(value = value)) }

    /** Compatibility for the legacy all-accounts snapshot; new product reads use upsertBalances. */
    override fun replaceBalances(bankCode: String, subscriptionId: Int, values: List<BalanceRecord>) = write {
        require(values.all { it.bankCode == bankCode && it.subscriptionId == subscriptionId })
        values.forEach(::validateBalance)
        dao.deleteBalances(bankCode, subscriptionId)
        values.forEach { dao.put(BalanceRow(it)) }
    }
    override fun upsertBalances(values: List<BalanceRecord>) = write { putBalances(values); Unit }
    private fun putBalances(values: List<BalanceRecord>): Boolean {
        values.forEach(::validateBalance)
        var resolved = true
        values.forEach { value ->
            val existing = dao.balances().map { it.value }
            val old = existing.singleOrNull { it.id == value.id }
            require(old == null || (old.bankCode == value.bankCode && old.subscriptionId == value.subscriptionId &&
                old.registrationId == value.registrationId && old.cardId == value.cardId && old.accountId == value.accountId))
            val incomingOrder = smsEvidenceTime(Instant.ofEpochMilli(value.at), value.sentAt?.let(Instant::ofEpochMilli),
                Instant.ofEpochMilli(value.at))?.toEpochMilli()
            val peers = existing.filter { sameBalanceInstrument(it, value) }.ifEmpty { listOfNotNull(old) }
            val peerOrders = peers.map { previous -> previous to smsEvidenceTime(Instant.ofEpochMilli(previous.at),
                previous.sentAt?.let(Instant::ofEpochMilli), Instant.ofEpochMilli(previous.at))?.toEpochMilli() }
            if (peerOrders.any { (previous, previousOrder) ->
                incomingOrder != null && (previousOrder != null && incomingOrder <= previousOrder ||
                    previousOrder == null && value.at < previous.at) ||
                    incomingOrder == null && (previousOrder != null || value.at < previous.at)
            }) {
                val newer = incomingOrder != null && peerOrders.all { (_, order) ->
                    order != null && order > incomingOrder
                } && peers.map { it.available }.distinct().size == 1
                val same = incomingOrder != null && peerOrders.all { (previous, order) ->
                    order == incomingOrder && previous.available == value.available
                }
                if (!newer && !same) resolved = false
                return@forEach
            }
            peers.filter { it.id != value.id }.forEach { previous ->
                dao.put(BalanceRow(previous.copy(at = value.at, sentAt = value.sentAt,
                    available = value.available, ledger = value.ledger)))
            }
            dao.put(BalanceRow(value))
        }
        return resolved
    }

    private fun sameBalanceInstrument(a: BalanceRecord, b: BalanceRecord): Boolean =
        a.bankCode == b.bankCode && a.subscriptionId == b.subscriptionId &&
            a.registrationId != null && a.registrationId == b.registrationId && a.currency == b.currency &&
            (a.cardId != null && a.cardId == b.cardId && a.accountId == null && b.accountId == null ||
                a.accountId != null && a.accountId == b.accountId && a.cardId == null && b.cardId == null)

    override fun markReferenceUsed(value: UsedReference): Boolean = write { claimReference(value) }
    internal fun claimReference(value: UsedReference): Boolean {
        val reference = normalizeReference(value.reference)
        require(reference.isNotEmpty())
        return dao.claim(UsedReferenceRow(value, reference)) != -1L
    }

    override fun ingest(value: IncomingEvent, fuelProtector: FuelEnvelopeProtector?): IngestResult = write {
        require(value.sender.equals("PAGOxMOVIL", ignoreCase = true)) { "Remitente no bancario" }
        require(value.source != EventSource.LEGACY)
        require(value.receipt == null || value.history == null)
        require(value.fuelUpdate == null || value.receipt == null && value.history == null)
        val historyStore = WalletHistoryStore(this)
        val fuelStore = FuelStore(this)
        if (value.sourceId != null) dao.delivery(value.source, value.sourceId)?.let { existing ->
            require(existing.value.subscriptionId == value.subscriptionId && existing.bodyHash == bodyHash(value.body)) {
                "Un identificador de entrega no puede cambiar su contenido"
            }
            val previousSentAt = existing.value.sentAt
            val suppliedSentAt = value.sentAt
            val conflictingTime = previousSentAt != null && suppliedSentAt != null && suppliedSentAt > 0 &&
                suppliedSentAt <= value.receivedAt && previousSentAt / 1000 != suppliedSentAt / 1000
            if (conflictingTime) {
                quarantineSmsTime(existing.value.canonicalEventId)
            } else {
                enrichSmsTime(existing.value.id, value.sentAt, value.receivedAt, value.evidenceEligible)
                if (existing.value.id != existing.value.canonicalEventId)
                    enrichSmsTime(existing.value.canonicalEventId, value.sentAt, value.receivedAt, value.evidenceEligible)
            }
            val fuel = fuelStore.record(value.fuelUpdate, existing.value.canonicalEventId, fuelProtector, value.body)
            return@write IngestResult(existing.value.id, existing.value.canonicalEventId,
                dao.receiptForEvent(existing.value.canonicalEventId)?.value?.id, true,
                historyStore.record(value.history, existing.value.canonicalEventId), fuel.couponIds, fuel.receiptIds)
        }
        val hash = bodyHash(value.body)
        // Only pair opposite sources. A second live authentication is a separate event.
        val referenceCandidates = value.receipt?.reference?.let(::normalizeReference)?.let(dao::receiptsWithReference)
            .orEmpty().filter { !it.value.referenceConflict && sameReceiptFacts(it.value, checkNotNull(value.receipt)) }
            .mapNotNull { dao.event(it.value.eventId) }
        val oppositeDeliveries = dao.matchingEvents(hash, value.receivedAt - 120_000, value.receivedAt + 120_000)
            .filter { it.value.source != value.source && !dao.hasDeliverySource(it.value.canonicalEventId, value.source) }
            .filter { it.value.sentAt == null || value.sentAt == null ||
                it.value.sentAt?.div(1000) == value.sentAt?.div(1000) }
        // A referenced receipt with the same complete facts can be re-delivered by the bank.
        // Preserve the delivery row while reusing its receipt; administrative events do not use this path.
        val candidates = (oppositeDeliveries + referenceCandidates)
            .filter { it.value.source != EventSource.LEGACY &&
                it.value.subscriptionId == value.subscriptionId && it.value.evidenceEligible == value.evidenceEligible }
            .filter { it.value.receivedAt in (value.receivedAt - 120_000)..(value.receivedAt + 120_000) }
            .filter { candidate -> value.receipt == null || dao.receiptForEvent(candidate.value.canonicalEventId)
                ?.let { sameReceiptFacts(it.value, value.receipt) } != false }
            .distinctBy { it.value.canonicalEventId }
        val canonical = candidates.singleOrNull()?.value?.canonicalEventId
        val id = UUID.randomUUID().toString()
        val secret = containsSecret(value.body)
        val event = EventRecord(id, value.source, value.sourceId, value.sender,
            value.body.takeUnless { secret }, value.receivedAt, value.subscriptionId, canonical ?: id, secret,
            evidenceEligible = value.evidenceEligible, sentAt = value.sentAt)
        dao.put(EventRow(event, hash))
        if (canonical != null) {
            enrichSmsTime(canonical, value.sentAt, value.receivedAt, value.evidenceEligible)
            val fuel = fuelStore.record(value.fuelUpdate, canonical, fuelProtector, value.body)
            return@write IngestResult(id, canonical, dao.receiptForEvent(canonical)?.value?.id, true,
                historyStore.record(value.history, canonical), fuel.couponIds, fuel.receiptIds)
        }
        val historyIds = historyStore.record(value.history, id)
        val fuel = fuelStore.record(value.fuelUpdate, id, fuelProtector, value.body, ambiguousDelivery = candidates.size > 1)
        val input = value.receipt ?: return@write IngestResult(id, id, fuel.receiptIds.singleOrNull(), false, historyIds, fuel.couponIds, fuel.receiptIds)
        checkAmount(input.amount); input.nominalAmount?.let(::checkAmount)
        checkCurrency(input.currency)
        require(input.kind in setOf("SENT", "RECEIVED", "PAYMENT", "RECHARGE"))
        require(input.subscriptionId == value.subscriptionId && input.operationId == null)
        val reference = input.reference?.let(::normalizeReference)?.takeIf(String::isNotEmpty)
        val receiptId = UUID.randomUUID().toString()
        val related = reference?.let(dao::receiptsWithReference).orEmpty().filter {
            it.value.bankCode == input.bankCode && it.value.subscriptionId == input.subscriptionId &&
                dao.event(it.value.eventId)?.value?.evidenceEligible == value.evidenceEligible
        }
        // A reference reused with different facts is retained and cannot confirm a payment.
        val conflict = related.any { !sameReceiptFacts(it.value, input) }
        if (conflict) related.forEach {
            dao.put(it.copy(value = it.value.copy(referenceConflict = true)))
            dao.acknowledgeTransferNotification(it.value.id)
        }
        val receipt = input.copy(id = receiptId, eventId = id, referenceConflict = conflict)
        dao.put(ReceiptRow(receipt, reference))
        dao.put(MovementRow(MovementRecord(UUID.randomUUID().toString(), receiptId, receipt.kind,
            receipt.amount, receipt.currency, value.receivedAt, receipt.kind == "RECEIVED")))
        if (value.source == EventSource.BROADCAST && value.evidenceEligible && !conflict && receipt.kind == "RECEIVED" &&
            BankSmsParser().parse(value.sender, value.body) is BankMessage.TransferReceived) {
            dao.enqueue(NotificationOutboxRow(receiptId, value.receivedAt))
        }
        reference?.let(historyStore::reconcile)
        IngestResult(id, id, receiptId, false)
    }

    /** A later inbox read may add the SMSC time to a previously stored delivery. */
    private fun enrichSmsTime(canonicalId: String, sentAt: Long?, receivedAt: Long, eligible: Boolean) {
        if (!eligible || sentAt == null || sentAt <= 0 || sentAt > receivedAt) return
        val canonical = dao.event(canonicalId) ?: return
        if (!canonical.value.evidenceEligible || canonical.value.source == EventSource.LEGACY ||
            canonical.value.originalSource != null || sentAt > canonical.value.receivedAt ||
            canonical.value.sentAt != null) return
        dao.update(canonical.copy(value = canonical.value.copy(sentAt = sentAt)))
        dao.fuelObservations(canonicalId).forEach { row ->
            val proof = row.value.purchaseEvidence ?: return@forEach
            if (proof.sentAt == null && proof.receivedAt == canonical.value.receivedAt)
                dao.put(row.copy(value = row.value.copy(purchaseEvidence = proof.copy(sentAt = sentAt))))
        }
    }

    private fun quarantineSmsTime(canonicalId: String) {
        dao.events().filter { it.value.canonicalEventId == canonicalId && it.value.evidenceEligible }.forEach { row ->
            dao.update(row.copy(value = row.value.copy(evidenceEligible = false)))
        }
    }

    override fun confirmOperation(operationId: String, receiptId: String, at: Long, refresh: RefreshRequest?): Boolean = write {
        val operation = dao.operation(operationId)?.value ?: return@write false
        val row = dao.receipt(receiptId) ?: return@write false
        val receipt = row.value
        val event = checkNotNull(dao.event(receipt.eventId)).value
        val fuelObservations = dao.fuelObservations().map { it.value }.filter { it.receiptId == receiptId }
        if (fuelObservations.isNotEmpty() || operation.specId == "service.fuel") {
            val observation = fuelObservations.singleOrNull { it.eventId == receipt.eventId && it.kind == "PURCHASE" && it.result == "APPLIED" }
                ?: return@write false
            val proof = observation.purchaseEvidence ?: return@write false
            if (!fuelPurchaseMatches(operation, proof) || !event.bodyWithheld || event.body != null ||
                proof.receivedAt != event.receivedAt || proof.subscriptionId != event.subscriptionId ||
                proof.bankCode != receipt.bankCode || proof.bankReference != receipt.reference || proof.serial != receipt.purchaseId ||
                receipt.party != "Cupón de combustible ${proof.serial}" || receipt.account != null || receipt.kind != "PAYMENT" ||
                proof.paidAmount.toBigDecimal().compareTo(receipt.amount.toBigDecimal()) != 0 || proof.currency != receipt.currency ||
                dao.fuelCoupon(proof.couponId)?.value?.ambiguous != false) return@write false
            val candidates = dao.operations().map { it.value }.filter {
                !it.restored && it.status in pendingPaymentStatuses && fuelPurchaseMatches(it, proof)
            }
            if (candidates.singleOrNull()?.id != operationId) return@write false
        }
        val parsedService = event.body?.let { BankSmsParser().parse(event.sender, it) } as? BankMessage.ServicePaymentCompleted
        val servicePayment = parsedService != null || operation.specId in correlatedServiceSpecs
        val fullInvoice = operation.amount == null && operation.parameters["paymentMode"] == "FULL_INVOICE" &&
            operation.specId in setOf("service.electricity", "service.telephone") && receipt.account != null &&
            receipt.account == operation.destination && receipt.kind == "PAYMENT"
        if (operation.status !in setOf(OperationStatus.SUBMITTING, OperationStatus.AWAITING_CONFIRMATION, OperationStatus.UNCERTAIN) ||
            operation.restored || event.source == EventSource.LEGACY || event.originalSource != null ||
            receipt.operationId != null || receipt.referenceConflict || receipt.amountIsNominal || receipt.bankCode == null ||
            receipt.bankCode != operation.bankCode || receipt.subscriptionId != operation.subscriptionId ||
            !event.evidenceEligible || !event.after(operation, at) ||
            operation.currency != receipt.currency || !fullInvoice && !servicePayment && (operation.amount == null ||
            BigDecimal(operation.amount).compareTo(BigDecimal(receipt.amount)) != 0) || receipt.kind == "RECEIVED") return@write false
        if (servicePayment) {
            val parsed = parsedService ?: return@write false
            val paid = parsed.paid ?: return@write false
            val projection = FinancialMovement.from(parsed) ?: return@write false
            val recordedNominal = receipt.nominalAmount?.toBigDecimalOrNull() ?: return@write false
            if (receipt.kind != "PAYMENT" || parsed.bank.code != receipt.bankCode || parsed.reference != receipt.reference ||
                receipt.party != projection.party || receipt.purchaseId != projection.purchaseId || receipt.account != projection.account ||
                paid.currency.name != receipt.currency || paid.amount.compareTo(BigDecimal(receipt.amount)) != 0 ||
                parsed.nominal.currency.name != receipt.currency || parsed.nominal.amount.compareTo(recordedNominal) != 0)
                return@write false
            val candidates = dao.operations().map { it.value }.filter {
                !it.restored && it.status in pendingPaymentStatuses && it.subscriptionId == receipt.subscriptionId &&
                    event.after(it, at) && matchesServiceReceipt(it, parsed, forAmbiguity = true)
            }
            if (candidates.singleOrNull()?.id != operation.id || !matchesServiceReceipt(operation, parsed)) return@write false
        }
        if (fullInvoice) {
            val bill = event.body?.let { BankSmsParser().parse(event.sender, it) } as? BankMessage.BillPaymentCompleted ?: return@write false
            val expectedService = if (operation.specId == "service.electricity") "ELECTRICITY" else "TELEPHONE"
            val paid = bill.paid ?: return@write false
            if (bill.account != operation.destination || bill.bank.code != operation.bankCode ||
                bill.reference != receipt.reference || paid.currency.name != receipt.currency ||
                paid.amount.compareTo(BigDecimal(receipt.amount)) != 0 ||
                bill.service.name != expectedService) return@write false
        }
        if (!servicePayment && operation.specId != "service.fuel") {
            val candidates = dao.operations().map { it.value }.filter {
                !it.restored && it.status in pendingPaymentStatuses && it.bankCode == receipt.bankCode &&
                    it.subscriptionId == receipt.subscriptionId && it.currency == receipt.currency &&
                    event.after(it, at) && (fullInvoice && it.destination == receipt.account ||
                        !fullInvoice && it.amount?.toBigDecimalOrNull()?.compareTo(BigDecimal(receipt.amount)) == 0 &&
                        (receipt.kind != "SENT" || (it.kind == "TRANSFER" || it.specId.endsWith(".transfer")) &&
                            matchesAccount(receipt.party, it.destination)))
            }
            if (candidates.singleOrNull()?.id != operationId) return@write false
        }
        val reference = receipt.reference ?: return@write false
        val remaining = transferRemaining(operation, receipt, event)
        if (!claimReference(UsedReference(receipt.bankCode, reference, operationId))) return@write false
        dao.put(row.copy(value = receipt.copy(operationId = operationId)))
        val remainingResolved = remaining?.let { putBalances(listOf(it)) } == true
        dao.put(OperationRow(operation.copy(status = OperationStatus.CONFIRMED, updatedAt = at, reviewRequired = false,
            amount = if (fullInvoice || servicePayment) receipt.amount else operation.amount,
            refreshTaken = operation.refreshTaken || remainingResolved)))
        row.normalizedReference?.let { WalletHistoryStore(this).reconcile(it) }
        putRefresh(if (remainingResolved) null else refresh)
        true
    }

    private fun transferRemaining(operation: OperationRecord, receipt: ReceiptRecord, event: EventRecord): BalanceRecord? {
        if (receipt.kind != "SENT" || operation.kind != "TRANSFER" && !operation.specId.endsWith(".transfer")) return null
        val bankCode = receipt.bankCode ?: return null
        val subscriptionId = receipt.subscriptionId ?: return null
        val message = event.body?.let { BankSmsParser().parse(event.sender, it) } as? BankMessage.TransferSent ?: return null
        val remaining = message.remainingBalance ?: return null
        if (message.bank.code != bankCode || message.reference != receipt.reference ||
            message.beneficiary != receipt.party || message.amount.amount.compareTo(BigDecimal(receipt.amount)) != 0 ||
            message.amount.currency.name != receipt.currency || remaining.currency.name != operation.currency) return null
        val registration = dao.registrations().map { it.value }.singleOrNull {
            it.id == operation.registrationId && it.enabled && it.bankCode == bankCode &&
                it.subscriptionId == subscriptionId
        } ?: return null
        val cards = dao.cards().map { it.value }.filter { it.registrationId == registration.id &&
            (it.currency == null || it.currency == receipt.currency) }
        val accounts = dao.accounts().map { it.value }.filter { it.registrationId == registration.id &&
            (it.currency == null || it.currency == receipt.currency) }
        val products = cards.map { Triple(it.id, it.number, true) } + accounts.map { Triple(it.id, it.number, false) }
        val source = operation.source
        if (source == "0000" || source.isEmpty() || source.any { it !in '0'..'9' }) return null
        val product = products.filter { (_, number) -> number == source }.singleOrNull() ?: return null
        val peers = dao.balances().map { it.value }.filter { value ->
            value.bankCode == bankCode && value.subscriptionId == subscriptionId &&
                value.registrationId == registration.id && value.currency == receipt.currency &&
                (product.third && value.cardId == product.first && value.accountId == null ||
                    !product.third && value.accountId == product.first && value.cardId == null)
        }
        if (peers.any { value -> value.account?.let { mask ->
            products.count { (_, number) -> number == mask || matchesAccount(mask, number) } != 1 ||
                mask != source && !matchesAccount(mask, source)
        } == true }) return null
        val existing = peers.maxByOrNull { it.at }
        return existing?.copy(at = event.receivedAt, sentAt = event.sentAt,
            available = remaining.amount.toPlainString(), ledger = null)
            ?: BalanceRecord("$bankCode:$subscriptionId:${registration.id}:${product.first}:$source:${receipt.currency}",
                bankCode, subscriptionId, event.receivedAt, source,
                remaining.amount.toPlainString(), receipt.currency, registrationId = registration.id,
                cardId = product.first.takeIf { product.third }, accountId = product.first.takeUnless { product.third },
                sentAt = event.sentAt)
    }

    override fun completeQuery(operationId: String, eventId: String, at: Long): Boolean = write {
        val operation = dao.operation(operationId)?.value ?: return@write false
        val event = dao.event(eventId)?.value ?: return@write false
        if (operation.restored || operation.profileId != "PERSONAL" || operation.kind != operation.specId ||
            operation.amount != null || operation.destination.isNotEmpty() ||
            !operation.specId.matches(Regex("(bpa|bandec|banmet|wallet)\\.(authenticate|balance)|bandec\\.(card-select|card-balance|origin-balance)")) ||
            operation.status !in setOf(OperationStatus.SUBMITTING, OperationStatus.AWAITING_CONFIRMATION, OperationStatus.UNCERTAIN) ||
            !event.evidenceEligible || event.source == EventSource.LEGACY || event.originalSource != null ||
            event.subscriptionId != operation.subscriptionId || !event.after(operation, at))
            return@write false
        val message = event.body?.let { BankSmsParser().parse(event.sender, it) } ?: return@write false
        fun matches(candidate: OperationRecord): Boolean {
            val content = when (message) {
                is BankMessage.Authenticated -> candidate.specId == "${message.bank.name.lowercase()}.authenticate" &&
                    candidate.providerId == message.bank.name && candidate.bankCode == message.bank.code
                is BankMessage.ProviderAuthenticated -> candidate.specId == "wallet.authenticate" &&
                    candidate.providerId == message.identity.provider.name && candidate.profileId == message.identity.profile.name
                is BankMessage.Balance -> (candidate.specId == "${message.bank.name.lowercase()}.balance" ||
                    message.bank == dev.duardo.neotransfer.core.Bank.BANDEC && candidate.specId in setOf("bandec.card-select", "bandec.card-balance", "bandec.origin-balance")) &&
                    candidate.providerId == message.bank.name && candidate.bankCode == message.bank.code &&
                    (candidate.source != "0000" || candidate.parameters["sourceCurrency"] == null ||
                        message.accounts.all { it.available.currency.name == candidate.parameters["sourceCurrency"] }) &&
                    message.accounts.isNotEmpty() && (candidate.source == "0000" || message.accounts.count { balance ->
                        balance.account?.let { matchesAccount(it, candidate.source) } == true
                    } == 1)
                else -> false
            }
            if (!content) return false
            if (candidate.specId != "bandec.card-select") return true
            if (candidate.parameters["verification"] != "SELECTED_CARD_BALANCE") return false
            val read = candidate.parameters["readOperationId"]?.let { dao.operation(it)?.value } ?: return false
            return read.status == OperationStatus.CONFIRMED && read.specId == "bandec.card-balance" &&
                read.source == candidate.source && read.subscriptionId == candidate.subscriptionId &&
                read.registrationId == candidate.registrationId && read.startedAt >= candidate.startedAt
        }
        val candidates = dao.operations().map { it.value }.filter {
            !it.restored && it.profileId == "PERSONAL" && it.kind == it.specId && it.amount == null &&
                it.destination.isEmpty() && it.status in pendingPaymentStatuses &&
                it.subscriptionId == event.subscriptionId && event.after(it, at) && matches(it)
        }
        if (candidates.singleOrNull()?.id != operationId) return@write false
        dao.put(OperationRow(operation.copy(status = OperationStatus.CONFIRMED, updatedAt = at, reviewRequired = false)))
        true
    }

    override fun migrateLegacy(values: Map<String, *>): MigrationResult = LegacyWalletMigration(this).migrate(values)
    override fun bindHistoryToQuery(eventId: String, queryOperationId: String, at: Long): Boolean = write {
        WalletHistoryStore(this).bind(eventId, queryOperationId, at)
    }
    override fun pendingTransferNotifications(): List<ReceiptRecord> = dao.pendingTransferNotifications().map { it.value }
    override fun markTransferNotificationDelivered(receiptId: String) = write { dao.acknowledgeTransferNotification(receiptId) }
    override fun updateFuelCouponPresentation(couponId: String, label: String, archived: Boolean) = write {
        require(label == label.trim() && label.length <= 80 && label.none(Char::isISOControl))
        val coupon = checkNotNull(dao.fuelCoupon(couponId)).value
        dao.put(FuelCouponRow(coupon.copy(label = label, archived = archived)))
    }
    override fun protectedFuelEnvelopes(expected: List<FuelCoupon>): List<ProtectedFuelEnvelope> = database.runInTransaction(Callable {
        require(expected.map { it.id }.distinct().size == expected.size)
        expected.mapNotNull { wanted ->
            val current = checkNotNull(dao.fuelCoupon(wanted.id)).value
            check(current.serial == wanted.serial && current.bankReference == wanted.bankReference && current.tmReference == wanted.tmReference &&
                current.secretRevision == wanted.secretRevision) { "El cupón cambió; vuelve a abrirlo" }
            if (current.secretRevision == null) null
            else checkNotNull(dao.fuelSecret(current.id)).value.also { check(it.revision == current.secretRevision) }
        }
    })
    override fun importSnapshot(value: WalletSnapshot, fuelEnvelopes: List<ProtectedFuelEnvelope>): RestoreResult = WalletSnapshotRestore(this).restore(value, fuelEnvelopes)
    override fun close() = database.close()

    companion object {
        /** Android credential-protected app-private storage; no destructive migrations. */
        fun open(context: Context, databaseName: String = "wallet.db"): RoomWalletRepository {
            require(!context.isDeviceProtectedStorage) { "Los datos bancarios requieren almacenamiento protegido por credenciales" }
            require(databaseName.isNotBlank() && '/' !in databaseName && '\\' !in databaseName)
            return RoomWalletRepository(Room.databaseBuilder(context, WalletDatabase::class.java, databaseName).build())
        }
    }
}

private val correlatedServiceSpecs = setOf("service.nauta", "service.nauta.home", "service.nauta.debt", "service.stamp")
private val pendingPaymentStatuses = setOf(OperationStatus.SUBMITTING, OperationStatus.AWAITING_CONFIRMATION, OperationStatus.UNCERTAIN)
internal fun EventRecord.after(operation: OperationRecord, now: Long): Boolean =
    smsEvidenceAfter(Instant.ofEpochMilli(receivedAt), sentAt?.let(Instant::ofEpochMilli),
        Instant.ofEpochMilli(operation.startedAt), Instant.ofEpochMilli(now))
private fun matchesServiceReceipt(operation: OperationRecord, receipt: BankMessage.ServicePaymentCompleted, forAmbiguity: Boolean = false): Boolean {
    if (operation.kind != operation.specId || operation.specId !in correlatedServiceSpecs || operation.bankCode != receipt.bank.code) return false
    val provider = ProviderId.entries.singleOrNull { it.name == operation.providerId } ?: return false
    val profile = ProfileId.entries.singleOrNull { it.name == operation.profileId } ?: return false
    val currency = Currency.entries.singleOrNull { it.name == operation.currency } ?: return false
    val amount = operation.amount?.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 && it.scale() <= 2 } ?: return false
    val identity = ProviderIdentity(provider, profile)
    val nominal = Money(amount, currency)
    return if (forAmbiguity) ServiceReceiptCorrelation.couldBelongTo(operation.specId, identity, nominal, operation.parameters, receipt)
        else ServiceReceiptCorrelation.matches(operation.specId, identity, nominal, operation.parameters, receipt)
}

internal fun normalizeReference(value: String): String = value.trim().trimEnd('.', ',', ';').uppercase(Locale.ROOT)
internal fun bodyHash(value: String): String = digest(value.trim().replace(Regex("\\s+"), " "))
internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
internal fun canonicalPhone(value: String): String = value.filter(Char::isDigit).let {
    if (it.length == 10 && it.startsWith("53")) it.drop(2) else it
}
internal fun sameAccountIdentity(a: AccountRecord, b: AccountRecord, registrationProfile: String): Boolean =
    if (a.number.isNotEmpty() || b.number.isNotEmpty()) a.number == b.number
    else a.currency == b.currency && (a.profileId ?: registrationProfile) == (b.profileId ?: registrationProfile)

// The provider uses this field for the coupon credential, including a final table column with no colon.
private const val COUPON_DATA_FIELD = "datos(?:[\\s_-]*del)?[\\s_-]*cupon"
private val couponDataLabel = Regex("(?i)(?<![\\p{L}\\p{N}_])$COUPON_DATA_FIELD[\"']?\\s*:")
private val couponDataColumn = Regex("(?im)(?:[;|][ \\t\\r\\n]*[\"']?$COUPON_DATA_FIELD[\"']?(?=[\\s;|]|$)|" +
    "^[ \\t]*[\"']?$COUPON_DATA_FIELD[\"']?[ \\t]*(?=[;|]|$))")
private fun normalizedMetadataText(value: String): String =
    java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
private fun isCouponDataKey(value: String): Boolean = normalizedMetadataText(value)
    .filterNot { it.isWhitespace() || it == '_' || it == '-' }.lowercase(Locale.ROOT) in setOf("datoscupon", "datosdelcupon")

internal fun containsSecret(value: String): Boolean {
    val normalized = normalizedMetadataText(value)
    return couponDataLabel.containsMatchIn(normalized) || couponDataColumn.containsMatchIn(normalized) ||
        Regex("(?i)\\b(pin|clave|contrasena|password|credential|secret|otp|token|bearer)\\b|" +
            "codigo\\s+(?:de\\s+)?(?:acceso|verificacion|autenticacion|seguridad|activacion)|\\*444\\*").containsMatchIn(normalized)
}
internal fun sensitiveKey(value: String): Boolean = isCouponDataKey(value) || Regex(
    "(?i)pin|clave|password|credential|secret|otp|token|ussd|payload|coord|contrase|activation.?code|codigo.?activaci[oó]n"
).containsMatchIn(value)
internal fun checkAmount(value: String) { require(value.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?"))) { "Importe no válido" } }
private fun validateBalance(value: BalanceRecord) {
    require(value.id.isNotBlank() && value.bankCode.isNotBlank())
    checkAmount(value.available); value.ledger?.let(::checkAmount)
    checkCurrency(value.currency)
    require(value.cardId == null || value.accountId == null)
}
internal fun validateOperation(value: OperationRecord) {
    require(value.id.isNotBlank() && value.kind.isNotBlank() && value.specId.isNotBlank())
    value.amount?.let(::checkAmount)
    value.currency?.let(::checkCurrency)
    value.qr?.let { checkAmount(it.amount); checkCurrency(it.currency) }
    require(value.parameters.keys.none(::sensitiveKey) && value.parameters.values.none(::containsSecret)) { "La operación contiene credenciales o comandos" }
    require(listOfNotNull(value.destination, value.source, value.phone, value.description,
        value.qr?.description, value.qr?.auxiliary).none(::containsSecret)) { "La operación contiene credenciales o comandos" }
    require(!value.restored || value.status !in setOf(OperationStatus.PREPARED, OperationStatus.SUBMITTING)) { "Una operación restaurada requiere revisión" }
    require(value.timeoutAt == null || value.timeoutAt >= value.startedAt && value.timeoutAt <= value.updatedAt)
    if (value.providerId == "BULEVAR" || value.kind.startsWith("bulevar.") || value.specId.startsWith("bulevar.")) {
        require(isHistoricalBulevarRecord(value)) { "Solicitud Bulevar incompatible" }
        val allowed = setOf("userId", "title", "sourceId") + if (value.specId == "bulevar.refund_request") setOf("externalCode") else emptySet()
        require(value.parameters.keys.all { it in allowed } && value.parameters.values.none {
            it.trimStart().startsWith('{') || it.trimStart().startsWith('[') || it.startsWith("Bearer ", true)
        }) { "Bulevar solo admite metadatos de solicitud" }
    }
    value.httpEvidence?.let { evidence ->
        require(isHistoricalBulevarRecord(value) && value.status == OperationStatus.CONFIRMED &&
            evidence.requestId == value.id && evidence.specId == value.specId &&
            evidence.httpStatus == 200 && evidence.responseCode == "Ok" &&
            evidence.receivedAt >= value.startedAt && evidence.receivedAt <= value.updatedAt &&
            validBulevarReference(evidence.reference)) { "Evidencia HTTP no compatible" }
    }
}
internal fun isHistoricalBulevarRecord(value: OperationRecord): Boolean = value.providerId == "BULEVAR" &&
    value.bankCode == null && value.subscriptionId == -1 && value.profileId == "COMMERCE" &&
    value.registrationId == null && value.qr == null && value.kind == value.specId &&
    value.specId in setOf("bulevar.create_payment", "bulevar.refund_request")
private fun validBulevarReference(value: String?): Boolean = value == null || value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}"))
private fun sameRequest(a: OperationRecord, b: OperationRecord): Boolean =
    a.copy(status = b.status, updatedAt = b.updatedAt, refreshTaken = b.refreshTaken, restored = b.restored,
        reviewRequired = b.reviewRequired, legacyState = b.legacyState, timeoutAt = b.timeoutAt) == b
private fun allowedTransition(from: OperationStatus, to: OperationStatus): Boolean = when (from) {
    OperationStatus.PREPARED -> to in setOf(OperationStatus.SUBMITTING, OperationStatus.CANCELLED)
    OperationStatus.SUBMITTING -> to in setOf(OperationStatus.AWAITING_CONFIRMATION, OperationStatus.UNCERTAIN, OperationStatus.REJECTED)
    OperationStatus.AWAITING_CONFIRMATION -> to in setOf(OperationStatus.UNCERTAIN, OperationStatus.REJECTED)
    OperationStatus.UNCERTAIN, OperationStatus.CONFIRMED, OperationStatus.REJECTED, OperationStatus.CANCELLED -> false
}
internal fun sameReceiptFacts(a: ReceiptRecord, b: ReceiptRecord): Boolean =
    a.kind == b.kind && a.bankCode == b.bankCode && a.subscriptionId == b.subscriptionId &&
        BigDecimal(a.amount).compareTo(BigDecimal(b.amount)) == 0 && a.currency == b.currency &&
        a.party == b.party && a.account == b.account && a.purchaseId == b.purchaseId && a.bankDate == b.bankDate &&
        a.amountIsNominal == b.amountIsNominal && a.nominalAmount == b.nominalAmount
