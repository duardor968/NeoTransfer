package dev.duardo.neotransfer.data

import dev.duardo.neotransfer.core.fuel.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

internal data class FuelIngested(val couponIds: List<String> = emptyList(), val receiptIds: List<String> = emptyList())

/** Called only inside the repository transaction. Provider envelopes never become event bodies. */
internal class FuelStore(private val repository: RoomWalletRepository) {
    private val dao get() = repository.dao

    fun record(update: FuelSmsUpdate?, eventId: String, protector: FuelEnvelopeProtector?, rawBody: String,
               ambiguousDelivery: Boolean = false): FuelIngested {
        if (update == null) return FuelIngested()
        val previous = dao.fuelObservations(eventId).map { it.value }
        if (previous.isNotEmpty()) return FuelIngested(previous.mapNotNull { it.couponId }.distinct(), previous.mapNotNull { it.receiptId }.distinct())
        val event = checkNotNull(dao.event(eventId)).value
        // Reparse before withholding. Public DTOs alone must not manufacture purchase evidence.
        val parsed = FuelSmsParser.parse(event.sender, rawBody, event.receivedAt, event.subscriptionId,
            eventId, event.evidenceEligible) ?: error("La evidencia del cupón no coincide")
        parsed.use {
            require(update.updates.size == parsed.updates.size && update.rejectedRows == parsed.rejectedRows)
            val coupons = mutableListOf<String>()
            val receipts = mutableListOf<String>()
            parsed.updates.forEachIndexed { index, item ->
                val supplied = update.updates[index]
                val incoming = item.coupon.copy(sourceEventId = eventId)
                require(supplied.kind == item.kind && supplied.creditedAmount == item.creditedAmount &&
                    supplied.coupon.copy(sourceEventId = eventId) == incoming)
                if (runCatching { validateFuelCoupon(incoming); item.creditedAmount?.let(::validateFuelAmount) }.isFailure) {
                    dao.put(FuelObservationRow(FuelObservation(eventId, index, item.kind.name, "REJECTED", rejectedRows = 1)))
                    return@forEachIndexed
                }
                require(item.envelope.revision(incoming.serial, incoming.bankReference, incoming.tmReference) == incoming.secretRevision)
                val old = dao.fuelCoupon(incoming.id)?.value
                val disposition = if (ambiguousDelivery) "AMBIGUOUS_DELIVERY" else disposition(old, incoming, item.kind)
                val ambiguous = disposition == "CONFLICT" || incoming.ambiguous ||
                    item.kind == FuelUpdateKind.LIST && incoming.reportedDate == null
                if (old == null || disposition == "APPLIED") {
                    val merged = incoming.copy(paidAmount = incoming.paidAmount ?: old?.paidAmount,
                        balance = incoming.balance ?: old?.balance,
                        balanceObservedAt = incoming.balanceObservedAt ?: old?.balanceObservedAt,
                        bankCode = incoming.bankCode ?: old?.bankCode,
                        label = old?.label.orEmpty(), archived = old?.archived ?: false,
                        ambiguous = ambiguous || old?.ambiguous == true && !resolvesAmbiguity(old, incoming, item.kind, event),
                        evidenceEligible = incoming.evidenceEligible && event.evidenceEligible && event.originalSource == null && event.source != EventSource.LEGACY)
                    validateFuelCoupon(merged)
                    val chars = item.envelope.copyForProtection()
                    val protected = try { checkNotNull(protector) { "El cupón requiere protección local" }.protect(merged, chars) }
                        finally { chars.fill('\u0000') }
                    require(protected.couponId == merged.id && protected.revision == merged.secretRevision && protected.blob.isNotBlank())
                    dao.put(FuelCouponRow(merged)); dao.put(FuelSecretRow(protected))
                } else if (ambiguous && old != null) dao.put(FuelCouponRow(old.copy(ambiguous = true)))
                coupons += incoming.id
                val uncertainty = ambiguous || old?.ambiguous == true || !event.evidenceEligible ||
                    event.source == EventSource.LEGACY || event.originalSource != null
                val receiptId = when (item.kind) {
                    FuelUpdateKind.PURCHASE -> purchaseReceipt(incoming, event, uncertainty)
                    FuelUpdateKind.REFUND -> refundReceipt(incoming, item.creditedAmount, event, uncertainty || disposition == "STALE" || ambiguousDelivery)
                    else -> null
                }
                receiptId?.let(receipts::add)
                val proof = if (item.kind == FuelUpdateKind.PURCHASE) incoming.paidAmount?.let { paid ->
                    FuelPurchaseEvidence(incoming.id, incoming.serial, incoming.bankCode, incoming.subscriptionId,
                        incoming.bankReference, incoming.tmReference, paid, incoming.currency, event.receivedAt,
                        incoming.evidenceEligible && !uncertainty && disposition == "APPLIED")
                } else null
                dao.put(FuelObservationRow(FuelObservation(eventId, index, item.kind.name, disposition, incoming.id,
                    item.creditedAmount, receiptId, purchaseEvidence = proof)))
            }
            if (parsed.rejectedRows > 0) dao.put(FuelObservationRow(FuelObservation(eventId, parsed.updates.size,
                "LIST", "REJECTED", rejectedRows = parsed.rejectedRows)))
            return FuelIngested(coupons.distinct(), receipts.distinct())
        }
    }

    private fun resolvesAmbiguity(old: FuelCoupon, incoming: FuelCoupon, kind: FuelUpdateKind, event: EventRecord): Boolean {
        if (kind !in setOf(FuelUpdateKind.STATUS, FuelUpdateKind.REKEY) || !incoming.evidenceEligible ||
            !event.evidenceEligible || event.originalSource != null || event.source == EventSource.LEGACY ||
            old.bankCode == null || incoming.bankCode != old.bankCode || old.subscriptionId == null ||
            incoming.subscriptionId != old.subscriptionId || incoming.bankReference != old.bankReference ||
            incoming.tmReference != old.tmReference || incoming.secretRevision != old.secretRevision ||
            incoming.observedAt <= old.observedAt) return false
        // A live conflict does not overwrite the coupon's timestamp. Ineligible/imported evidence cannot block recovery.
        return dao.fuelObservations().none { row -> row.value.couponId == old.id && row.value.result != "INELIGIBLE" &&
            checkNotNull(dao.event(row.value.eventId)).value.let { previous ->
                previous.evidenceEligible && previous.source != EventSource.LEGACY && previous.originalSource == null &&
                    previous.receivedAt >= incoming.observedAt
            } }
    }

    private fun disposition(old: FuelCoupon?, incoming: FuelCoupon, kind: FuelUpdateKind): String {
        if (old == null) return "APPLIED"
        if (!incoming.evidenceEligible && old.evidenceEligible) return "INELIGIBLE"
        val changed = old.bankReference != incoming.bankReference || old.tmReference != incoming.tmReference ||
            old.secretRevision != incoming.secretRevision || old.currency != incoming.currency ||
            old.bankCode != null && incoming.bankCode != null && old.bankCode != incoming.bankCode ||
            incoming.balance != null && old.balance != null && !sameAmount(old.balance, incoming.balance) ||
            incoming.paidAmount != null && old.paidAmount != null && !sameAmount(old.paidAmount, incoming.paidAmount)
        // A list reports a calendar day, not the time at which this SMS arrived.
        val oldDay = old.reportedDate?.let(LocalDate::parse) ?: cubaDay(old.observedAt)
        if (kind == FuelUpdateKind.LIST) {
            val day = incoming.reportedDate?.let(LocalDate::parse) ?: return "CONFLICT"
            if (day < oldDay) return "STALE"
            if (day == oldDay) return if (changed) "CONFLICT" else "UNCHANGED"
        } else if (old.reportedDate != null) {
            val day = cubaDay(incoming.observedAt)
            if (day < oldDay) return "STALE"
            if (day == oldDay && incoming.observedAt <= old.observedAt) return if (changed) "CONFLICT" else "UNCHANGED"
        } else {
            if (incoming.observedAt < old.observedAt) return "STALE"
            if (incoming.observedAt == old.observedAt) return if (changed) "CONFLICT" else "UNCHANGED"
        }
        if (old.evidenceEligible && old.subscriptionId != null && incoming.subscriptionId != old.subscriptionId ||
            old.currency != incoming.currency || old.bankCode != null && incoming.bankCode != null && old.bankCode != incoming.bankCode)
            return "CONFLICT"
        if (kind == FuelUpdateKind.PURCHASE && (old.bankReference != incoming.bankReference || old.tmReference != incoming.tmReference ||
                old.paidAmount != null && !sameAmount(old.paidAmount, incoming.paidAmount))) return "CONFLICT"
        return "APPLIED"
    }

    private fun purchaseReceipt(coupon: FuelCoupon, event: EventRecord, uncertain: Boolean): String? {
        val amount = exactMoney(coupon.paidAmount) ?: return null
        val reference = normalizeReference(coupon.bankReference)
        val input = ReceiptRecord(UUID.randomUUID().toString(), event.id, coupon.bankCode, event.subscriptionId,
            "PAYMENT", coupon.bankReference, amount, coupon.currency, "Cupón de combustible ${coupon.serial}", purchaseId = coupon.serial)
        val related = dao.receiptsWithReference(reference).filter { it.value.bankCode == input.bankCode && it.value.subscriptionId == input.subscriptionId }
        val existing = related.singleOrNull { sameReceiptFacts(it.value, input) }
        if (existing != null) {
            if (uncertain) dao.put(existing.copy(value = existing.value.copy(referenceConflict = true)))
            return existing.value.id
        }
        val conflict = uncertain || related.isNotEmpty()
        if (conflict) related.forEach { dao.put(it.copy(value = it.value.copy(referenceConflict = true))); dao.acknowledgeTransferNotification(it.value.id) }
        return putReceipt(input.copy(referenceConflict = conflict), reference, event.receivedAt)
    }

    private fun refundReceipt(coupon: FuelCoupon, credited: String?, event: EventRecord, uncertain: Boolean): String? {
        val amount = exactMoney(credited) ?: return null
        // No new refund transaction identifier is supplied. Distinct deliveries stay distinct.
        return putReceipt(ReceiptRecord(UUID.randomUUID().toString(), event.id, null, event.subscriptionId, "FUEL_REFUND",
            null, amount, coupon.currency, coupon.serial, purchaseId = coupon.serial, referenceConflict = uncertain), null, event.receivedAt)
    }

    private fun putReceipt(value: ReceiptRecord, reference: String?, at: Long): String {
        dao.put(ReceiptRow(value, reference))
        dao.put(MovementRow(MovementRecord(UUID.randomUUID().toString(), value.id, value.kind, value.amount,
            value.currency, at, value.kind == "FUEL_REFUND")))
        reference?.let { WalletHistoryStore(repository).reconcile(it) }
        return value.id
    }

    private fun sameAmount(a: String?, b: String?): Boolean {
        val second = b?.toBigDecimalOrNull() ?: return false
        return a?.toBigDecimalOrNull()?.compareTo(second) == 0
    }
    private fun exactMoney(value: String?): String? = value?.toBigDecimalOrNull()?.stripTrailingZeros()
        ?.takeIf { it.signum() >= 0 && it.scale() <= 2 }?.toPlainString()
    private fun cubaDay(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(ZoneId.of("America/Havana")).toLocalDate()
}
