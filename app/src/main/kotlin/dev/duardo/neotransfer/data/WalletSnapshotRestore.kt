package dev.duardo.neotransfer.data

import dev.duardo.neotransfer.core.fuel.ProtectedFuelEnvelope

/** Merge only: existing records win conflicts, historical evidence is never overwritten. */
internal class WalletSnapshotRestore(private val repository: RoomWalletRepository) {
    private val dao get() = repository.dao

    fun restore(value: WalletSnapshot, fuelEnvelopes: List<ProtectedFuelEnvelope> = emptyList()): RestoreResult {
        validateSnapshot(value)
        return repository.write {
        val before = repository.snapshot()
        val conflicts = mutableListOf<String>()
        val reassociate = mutableListOf<String>()
        var imported = 0
        fun conflict(kind: String, id: String) { conflicts += "$kind:$id" }
        fun <T> unique(values: List<T>, id: (T) -> String) {
            val keys = values.map(id)
            require(keys.all(String::isNotBlank) && keys.distinct().size == keys.size) { "El respaldo contiene identificadores repetidos o vacíos" }
        }
        fun <T> merge(kind: String, id: String, existing: T?, original: T, restored: T = original, put: (T) -> Unit): Boolean {
            if (existing != null) {
                if (existing == original || existing == restored) return true
                conflict(kind, id); return false
            }
            put(restored); imported++; return true
        }
        unique(value.identities) { it.id }; unique(value.registrations) { it.id }
        unique(value.accounts) { it.id }; unique(value.cards) { it.id }; unique(value.contacts) { it.id }
        unique(value.services) { it.id }; unique(value.operations) { it.id }; unique(value.events) { it.id }
        unique(value.receipts) { it.id }; unique(value.movements) { it.id }; unique(value.balances) { it.id }
        unique(value.legacyMetadata) { it.key }
        unique(value.histories) { it.id }
        unique(value.historyObservations) { "${it.eventId}:${it.position}" }
        unique(value.fuelCoupons) { it.id }
        unique(value.fuelObservations) { "${it.eventId}:${it.position}" }
        unique(fuelEnvelopes) { it.couponId }
        val incomingCoupons = value.fuelCoupons.associateBy { it.id }
        val sealedFuel = fuelEnvelopes.associateBy { it.couponId }
        fuelEnvelopes.forEach { secret ->
            require(secret.blob.isNotBlank() && incomingCoupons[secret.couponId]?.secretRevision == secret.revision) {
                "El sobre protegido no corresponde al cupón del respaldo"
            }
        }
        val blockedIdentities = mutableSetOf<String>()
        val blockedRegistrations = mutableSetOf<String>()
        val blockedAccounts = mutableSetOf<String>()
        val blockedCards = mutableSetOf<String>()
        val blockedOperations = mutableSetOf<String>()
        val blockedEvents = mutableSetOf<String>()
        val blockedReceipts = mutableSetOf<String>()

        val identities = before.identities.associateBy { it.id }
        value.identities.forEach { item ->
            if (!merge("identity", item.id, identities[item.id], item, put = repository::putIdentity)) blockedIdentities += item.id
        }
        val registrations = before.registrations.associateBy { it.id }
        value.registrations.forEach { item ->
            if (item.identityId in blockedIdentities) { blockedRegistrations += item.id; conflict("registration", item.id) }
            else {
                val detached = item.copy(subscriptionId = null, linePhone = null, enabled = false,
                    previousSubscriptionId = item.subscriptionId ?: item.previousSubscriptionId,
                    previousLinePhone = item.linePhone ?: item.previousLinePhone)
                if (!merge("registration", item.id, registrations[item.id], item, detached, repository::putRegistration)) blockedRegistrations += item.id
                else if (registrations[item.id] == null) reassociate += item.id
            }
        }
        val accounts = before.accounts.associateBy { it.id }
        value.accounts.forEach { item ->
            val profile = dao.registrations().singleOrNull { it.value.id == item.registrationId }?.value?.profileId.orEmpty()
            val collision = dao.accounts().any { row -> row.value.let {
                it.id != item.id && it.registrationId == item.registrationId &&
                    (sameAccountIdentity(it, item, profile) || (it.isDefault && item.isDefault))
            } }
            if (item.registrationId in blockedRegistrations || collision) { blockedAccounts += item.id; conflict("account", item.id) }
            else if (!merge("account", item.id, accounts[item.id], item, put = repository::putAccount)) blockedAccounts += item.id
        }
        val cards = before.cards.associateBy { it.id }
        value.cards.forEach { item ->
            val collision = dao.cards().any { row -> row.value.let {
                it.id != item.id && it.registrationId == item.registrationId &&
                    (it.number == item.number || (it.isDefault && item.isDefault))
            } }
            if (item.registrationId in blockedRegistrations || item.accountId in blockedAccounts || collision) { blockedCards += item.id; conflict("card", item.id) }
            else if (!merge("card", item.id, cards[item.id], item, put = repository::putCard)) blockedCards += item.id
        }
        val contacts = before.contacts.associateBy { it.id }
        value.contacts.forEach { merge("contact", it.id, contacts[it.id], it, put = repository::putContact) }
        val services = before.services.associateBy { it.id }
        value.services.forEach {
            if (it.registrationId in blockedRegistrations) conflict("service", it.id)
            else merge("service", it.id, services[it.id], it, put = repository::putService)
        }
        val operations = before.operations.associateBy { it.id }
        value.operations.forEach { item ->
            val active = item.status in setOf(OperationStatus.PREPARED, OperationStatus.SUBMITTING, OperationStatus.AWAITING_CONFIRMATION, OperationStatus.UNCERTAIN)
            val restored = item.copy(status = if (active) OperationStatus.UNCERTAIN else item.status,
                restored = true, reviewRequired = active || item.reviewRequired)
            validateOperation(restored)
            if (item.registrationId in blockedRegistrations || !merge("operation", item.id, operations[item.id], item, restored) { dao.put(OperationRow(it)) }) {
                if (item.registrationId in blockedRegistrations) conflict("operation", item.id)
                blockedOperations += item.id
            }
        }
        val events = before.events.associateBy { it.id }
        val incomingEventIds = value.events.map { it.id }.toSet()
        value.events.forEach { item ->
            require(item.sender.equals("PAGOxMOVIL", ignoreCase = true))
            require(item.canonicalEventId in incomingEventIds || item.canonicalEventId in events)
            val secret = item.body?.let(::containsSecret) == true
            // Inbox IDs are local to the old phone; retaining them as a source key would alias new SMS.
            val restored = item.copy(source = EventSource.LEGACY, sourceId = null,
                body = item.body.takeUnless { secret }, bodyWithheld = secret || item.bodyWithheld,
                originalSource = item.originalSource ?: item.source, originalSourceId = item.originalSourceId ?: item.sourceId,
                evidenceEligible = false)
            if (!merge("event", item.id, events[item.id], item, restored) {
                dao.put(EventRow(it, it.body?.let(::bodyHash) ?: digest("withheld:${it.id}")))
            }) blockedEvents += item.id
        }
        val receipts = before.receipts.associateBy { it.id }
        value.receipts.forEach { item ->
            checkAmount(item.amount); item.nominalAmount?.let(::checkAmount)
            val collision = dao.receiptForEvent(item.eventId)?.value?.id?.let { it != item.id } == true
            if (item.eventId in blockedEvents || item.operationId in blockedOperations || collision) { blockedReceipts += item.id; conflict("receipt", item.id) }
            else if (!merge("receipt", item.id, receipts[item.id], item) {
                val normalized = it.reference?.let(::normalizeReference)?.takeIf(String::isNotEmpty)
                val related = normalized?.let(dao::receiptsWithReference).orEmpty().filter { row ->
                    row.value.bankCode == it.bankCode && row.value.subscriptionId == it.subscriptionId
                }
                val conflicting = related.any { row -> !sameReceiptFacts(row.value, it) }
                if (conflicting) related.forEach { row ->
                    dao.put(row.copy(value = row.value.copy(referenceConflict = true)))
                    dao.acknowledgeTransferNotification(row.value.id)
                }
                dao.put(ReceiptRow(it.copy(referenceConflict = it.referenceConflict || conflicting), normalized))
            }) blockedReceipts += item.id
        }
        val movements = before.movements.associateBy { it.id }
        value.movements.forEach {
            checkAmount(it.amount)
            val collision = dao.movements().any { row -> row.value.receiptId == it.receiptId && row.value.id != it.id }
            if (it.receiptId in blockedReceipts || collision) conflict("movement", it.id)
            else merge("movement", it.id, movements[it.id], it) { row -> dao.put(MovementRow(row)) }
        }
        val balances = before.balances.associateBy { it.id }
        value.balances.forEach {
            if (it.registrationId in blockedRegistrations || it.cardId in blockedCards || it.accountId in blockedAccounts) conflict("balance", it.id)
            else merge("balance", it.id, balances[it.id], it) { row -> repository.upsertBalances(listOf(row)) }
        }
        value.usedReferences.forEach {
            val key = normalizeReference(it.reference)
            val current = dao.usedReference(it.bankCode, key)?.value
            if (it.operationId in blockedOperations) {
                conflict("reference", "${it.bankCode}:$key")
                if (current == null) { repository.claimReference(it.copy(operationId = null)); imported++ }
            } else if (current == null) { repository.claimReference(it); imported++ }
            else if (current.operationId != it.operationId) conflict("reference", "${it.bankCode}:$key")
        }
        val archive = before.legacyMetadata.associateBy { it.key }
        value.legacyMetadata.forEach { item ->
            val safe = item.copy(safeValue = safeArchive(item.safeValue))
            merge("legacy", item.key, archive[item.key], safe) { dao.put(LegacyArchiveRow(it.key, it.safeValue, it.issue)) }
        }
        val histories = before.histories.associateBy { it.id }
        val blockedHistories = mutableSetOf<String>()
        value.histories.forEach { item ->
            if (item.receiptId in blockedReceipts || item.registrationId in blockedRegistrations ||
                item.accountId in blockedAccounts || item.cardId in blockedCards) {
                blockedHistories += item.id; conflict("history", item.id)
            } else if (!merge("history", item.id, histories[item.id], item) {
                dao.put(HistoryRow(it, it.reference?.let(::normalizeReference)?.takeIf(String::isNotEmpty)))
            }) blockedHistories += item.id
        }
        val observations = before.historyObservations.associateBy { "${it.eventId}:${it.position}" }
        value.historyObservations.forEach { item ->
            val id = "${item.eventId}:${item.position}"
            if (item.eventId in blockedEvents || item.entryId in blockedHistories) conflict("historyObservation", id)
            else merge("historyObservation", id, observations[id], item) { dao.put(HistoryObservationRow(it)) }
        }
        val oldCoupons = before.fuelCoupons.associateBy { it.id }
        val blockedCoupons = mutableSetOf<String>()
        value.fuelCoupons.forEach { item ->
            val restored = item.copy(evidenceEligible = false)
            val current = oldCoupons[item.id]
            if (item.sourceEventId in blockedEvents || current != null && current != item && current != restored) {
                blockedCoupons += item.id; conflict("fuelCoupon", item.id)
            } else {
                val matchingLocal = current?.takeIf { it.serial == item.serial && it.bankReference == item.bankReference &&
                    it.tmReference == item.tmReference && it.secretRevision == item.secretRevision }
                    ?.let { dao.fuelSecret(it.id)?.value }?.takeIf { it.revision == item.secretRevision }
                val secret = sealedFuel[item.id] ?: matchingLocal
                require(item.secretRevision == null || secret != null) { "Falta la cápsula portable del cupón" }
                if (merge("fuelCoupon", item.id, current, item, restored) { dao.put(FuelCouponRow(it)) }) {
                    if (secret != null) dao.put(FuelSecretRow(secret))
                } else blockedCoupons += item.id
            }
        }
        val oldFuelObservations = before.fuelObservations.associateBy { "${it.eventId}:${it.position}" }
        value.fuelObservations.forEach { item ->
            val key = "${item.eventId}:${item.position}"
            if (item.eventId in blockedEvents || item.couponId in blockedCoupons || item.receiptId in blockedReceipts)
                conflict("fuelObservation", key)
            else merge("fuelObservation", key, oldFuelObservations[key], item,
                item.copy(purchaseEvidence = item.purchaseEvidence?.copy(evidenceEligible = false))) { dao.put(FuelObservationRow(it)) }
        }
        if (before.identities.isEmpty() && before.registrations.isEmpty()) {
            val incoming = value.settings
            val selected = incoming.selectedRegistrationId?.takeUnless { it in blockedRegistrations }
                ?.takeIf { id -> dao.registrations().any { it.value.id == id } }
            repository.setSettings(incoming.copy(subscriptionId = -1, selectedRegistrationId = selected,
                selectedCardId = incoming.selectedCardId?.takeIf { id -> dao.cards().any { it.value.id == id && it.value.registrationId == selected } },
                selectedAccountId = incoming.selectedAccountId?.takeIf { id -> dao.accounts().any { it.value.id == id && it.value.registrationId == selected } }))
        }
        // No refresh import: it is a local scheduled request, not historical evidence.
        RestoreResult(imported, conflicts.distinct(), reassociate)
        }
    }
}
