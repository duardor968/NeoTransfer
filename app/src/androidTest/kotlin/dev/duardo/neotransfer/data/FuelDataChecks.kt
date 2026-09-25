package dev.duardo.neotransfer.data

import android.content.Context
import dev.duardo.neotransfer.core.fuel.*
import dev.duardo.neotransfer.fuel.FuelSecretStore
import dev.duardo.neotransfer.platform.BankSmsRecord
import java.time.Instant
import java.util.UUID
import javax.crypto.spec.SecretKeySpec

/** Synthetic provider ciphertext and independent local AES keys; no device credentials or transport. */
object FuelDataChecks {
    private const val pair = "W3ePK80M6VKxq9C60HXAHg==|+yRrfQTQ/zLIJShacXR3ow=="
    private val at = Instant.parse("2026-09-24T16:00:00Z").toEpochMilli()
    private const val couponId = "fuel:0000000000001"
    private fun store(seed: Int = 1) = FuelSecretStore { SecretKeySpec(ByteArray(32) { seed.toByte() }, "AES") }
    private fun body(kind: String = "purchase", amount: String = "10.00", bank: String = "BANK1", tm: String = "TM1", envelope: String = pair): String {
        val (header, label) = when (kind) {
            "status" -> "Estado del cupon de combustible con" to "Saldo actual"
            "rekey" -> "Actualizado el token del cupon de combustible con" to "Saldo actual"
            "refund" -> "Se ha realizado una devolucion al cupon de combustible con" to "Importe acreditado"
            else -> "El pago del cupon de combustible fue completado." to "Importe pagado"
        }
        return "Banco Bandec: $header\nID TM: $tm.\nNo. Transaccion : $bank.\nNo. Serie: 0000000000001.\n$label: $amount CUP\nDatos del cupon: $envelope"
    }
    private fun list(day: String, balance: String = "5.00", bank: String = "BANK1", tm: String = "TM1", extra: String = "") =
        "Mis cupones de combustible.\nNo.Serie;Saldo actual;Importe pagado;Moneda;ID Banco;ID TM;Fecha;Banco;DatosCupon\n" +
            "0000000000001;$balance;10.00;CUP;$bank;$tm;$day;BANDEC;$pair$extra"
    private fun ingest(r: WalletRepository, text: String = body(), time: Long = at, sim: Int = 7,
                       id: Long? = null, delivery: String = UUID.randomUUID().toString(), protector: FuelEnvelopeProtector = store()) =
        SmsIngestor(r, now = { Instant.ofEpochMilli(at + 3_600_000) }, fuelProtector = protector)
            .ingest(BankSmsRecord(id, text, Instant.ofEpochMilli(time), sim, delivery)).stored
    private fun operation(id: String = "fuel", started: Long = at - 1000) = OperationRecord(id, "service.fuel", "02", 7, "", "10.00", "CUP", started,
        status = OperationStatus.AWAITING_CONFIRMATION, providerId = "BANDEC", parameters = mapOf("amount" to "10.00", "amountCurrency" to "1"))

    fun run(context: Context, onPassed: (String) -> Unit = {}): Int {
        var passed = 0
        fun case(name: String, action: (RoomWalletRepository, String) -> Unit) {
            val nameDb = "fuel_test_${UUID.randomUUID()}.db"
            val r = RoomWalletRepository.open(context, nameDb)
            try { action(r, nameDb); passed++; onPassed(name) }
            finally { r.close(); check(context.deleteDatabase(nameDb)) }
        }
        case("Fuel purchase status rekey and refund preserve distinct monetary meanings and protected metadata") { r, _ ->
            ingest(r)
            check(r.snapshot().fuelCoupons.single().balance == null)
            ingest(r, body("status", "6.00"), at + 1000)
            val before = r.snapshot().fuelCoupons.single()
            r.updateFuelCouponPresentation(couponId, "Viaje", true)
            ingest(r, body("refund", "2.00"), at + 2000)
            check(r.snapshot().fuelCoupons.single().balance == "6.00")
            ingest(r, body("rekey", "6.00", bank = "BANK2", tm = "TM2"), at + 3000)
            val state = r.snapshot()
            check(state.fuelCoupons.single().let { it.label == "Viaje" && it.archived && it.paidAmount == "10.00" && it.bankReference == "BANK2" })
            check(state.receipts.size == 2 && state.movements.size == 2 && state.balances.isEmpty())
            check(state.receipts.single { it.kind == "FUEL_REFUND" }.let { it.reference == null && it.bankCode == null && it.account == null && it.amount == "2" })
            check(state.fuelObservations.single { it.kind == "PURCHASE" }.purchaseEvidence?.bankReference == "BANK1")
            check(state.events.all { it.body == null && it.bodyWithheld } && r.pendingTransferNotifications().isEmpty())
            check(runCatching { r.protectedFuelEnvelopes(listOf(before)) }.isFailure)
            val encoded = SnapshotCodec.encode(state)
            check(pair !in String(encoded) && "TOKEN987" !in String(encoded) && "blob" !in String(encoded))
            check(SnapshotCodec.decode(encoded) == state)
        }
        case("Fuel exact PDU and inbox duplicates survive reopening while separate refunds remain separate") { r, db ->
            val first = ingest(r, delivery = "purchase-pdu")
            r.close()
            RoomWalletRepository.open(context, db).use { reopened ->
                val repeat = ingest(reopened, delivery = "purchase-pdu")
                val inbox = ingest(reopened, id = 70, time = at + 1)
                check(repeat.duplicate && inbox.duplicate && first.fuelReceiptIds == repeat.fuelReceiptIds && first.fuelReceiptIds == inbox.fuelReceiptIds)
                ingest(reopened, body("refund", "2.00"), at + 1000, delivery = "refund-a")
                ingest(reopened, body("refund", "2.00"), at + 1100, delivery = "refund-b")
                ingest(reopened, body("refund", "2.00"), at + 1000, delivery = "refund-a")
                check(reopened.snapshot().receipts.count { it.kind == "FUEL_REFUND" } == 2)
                check(reopened.snapshot().movements.size == 3)
                ingest(reopened, body("refund", "2.00"), at + 1200, id = 71)
                val refunds = reopened.snapshot().receipts.filter { it.kind == "FUEL_REFUND" }
                check(refunds.size == 3 && refunds.count { !it.referenceConflict } == 2)
                check(reopened.snapshot().fuelObservations.any { it.result == "AMBIGUOUS_DELIVERY" })
            }
        }
        case("Fuel crypto failure rolls back source event coupon observation and receipt atomically") { r, _ ->
            val failing = FuelEnvelopeProtector { _, chars -> chars.fill('\u0000'); error("Synthetic encryption failure") }
            check(runCatching { ingest(r, protector = failing) }.isFailure)
            val state = r.snapshot()
            check(state.events.isEmpty() && state.fuelCoupons.isEmpty() && state.fuelObservations.isEmpty() && state.receipts.isEmpty())
            ingest(r)
            check(r.snapshot().fuelCoupons.size == 1)
        }
        case("Fuel incomplete evidence and rejected list rows preserve redaction without manufacturing coupons or expenses") { r, _ ->
            ingest(r, body().replace("No. Transaccion : BANK1.\n", ""))
            check(r.snapshot().fuelCoupons.isEmpty() && r.snapshot().events.single().bodyWithheld)
            ingest(r, list("24/09/2026", extra = "\ninvalid;row"), at + 1000)
            check(r.snapshot().fuelCoupons.single().balance == "5.00" && r.snapshot().receipts.isEmpty())
            check(r.snapshot().fuelObservations.single { it.result == "REJECTED" }.rejectedRows == 1)
            ingest(r, body("status", "-1.00"), at + 2000)
            check(r.snapshot().fuelCoupons.single().balance == "5.00")
            check(r.snapshot().fuelObservations.count { it.result == "REJECTED" } == 2)
        }
        case("Fuel day-only old list cannot replace a newer rekey and same-day contradictions remain ambiguous") { r, _ ->
            ingest(r)
            ingest(r, body("rekey", "6.00", "BANK2", "TM2"), at + 1000)
            val current = r.snapshot().fuelCoupons.single()
            ingest(r, list("23/09/2026"), at + 2000)
            check(r.snapshot().fuelCoupons.single() == current)
            check(r.snapshot().fuelObservations.any { it.result == "STALE" })
            ingest(r, list("24/09/2026"), at + 3000)
            val conflicted = r.snapshot().fuelCoupons.single()
            check(conflicted.ambiguous && conflicted.bankReference == "BANK2" && conflicted.secretRevision == current.secretRevision)
            check(r.snapshot().receipts.size == 1)
        }
        case("Fuel same serial conflicting purchase and another live SIM never silently replace the credential") { r, _ ->
            ingest(r)
            val original = r.snapshot().fuelCoupons.single()
            ingest(r, body(amount = "20.00", bank = "OTHER"), at + 1000)
            ingest(r, body("status", "9.00"), at + 2000, sim = 8)
            check(r.snapshot().fuelCoupons.single().let { it.ambiguous && it.bankReference == original.bankReference && it.secretRevision == original.secretRevision })
            check(r.snapshot().fuelObservations.count { it.result == "CONFLICT" } == 2)
            check(r.snapshot().receipts.single { it.reference == "OTHER" }.referenceConflict)
        }
        case("Fuel fresh matching status resolves ambiguity only after every conflicting observation") { r, _ ->
            ingest(r)
            ingest(r, body("status", "6.00"), at + 1000)
            ingest(r, list("24/09/2026"), at + 3000)
            check(r.snapshot().fuelCoupons.single().ambiguous)
            // A status at the conflict's timestamp cannot establish which observation is newer.
            ingest(r, body("status", "6.00"), at + 3000)
            check(r.snapshot().fuelCoupons.single().ambiguous)
            ingest(r, body("status", "6.00"), at + 5000, sim = 8)
            ingest(r, body("status", "6.00"), at + 4000)
            check(r.snapshot().fuelCoupons.single().ambiguous)
            val conflicts = r.snapshot().fuelObservations.filter { it.result == "CONFLICT" }
            check(conflicts.size == 2)
            ingest(r, body("status", "6.00"), at + 6000)
            val current = r.snapshot().fuelCoupons.single()
            check(!current.ambiguous && current.bankReference == "BANK1" && current.subscriptionId == 7)
            check(r.snapshot().fuelObservations.filter { it.result == "CONFLICT" } == conflicts)
            store().open(current, r.protectedFuelEnvelopes(listOf(current)).single()).use { envelope ->
                FuelCredential.fromProviderEnvelope(current, envelope).use { credential ->
                    check(credential.qrPayload() == "{\"Pin\":\"1234\",\"Token\":\"TOKEN987\"}")
                }
            }
        }
        case("Fuel changed credential cannot itself clear ambiguity but a later matching rekey can") { r, _ ->
            ingest(r)
            ingest(r, body(amount = "20.00", bank = "OTHER"), at + 1000)
            check(r.snapshot().fuelCoupons.single().ambiguous)
            ingest(r, body("rekey", "6.00", "BANK2", "TM2"), at + 2000)
            check(r.snapshot().fuelCoupons.single().ambiguous)
            ingest(r, body("rekey", "6.00", "BANK2", "TM2"), at + 3000)
            check(!r.snapshot().fuelCoupons.single().ambiguous)
            check(r.snapshot().fuelObservations.any { it.result == "CONFLICT" })
            check(r.snapshot().receipts.single { it.reference == "OTHER" }.referenceConflict)
        }
        case("Fuel ineligible future observation neither changes balance nor blocks fresh recovery") { r, _ ->
            ingest(r)
            ingest(r, body("status", "6.00"), at + 1000)
            ingest(r, list("24/09/2026"), at + 2000)
            val conflicted = r.snapshot().fuelCoupons.single()
            check(conflicted.ambiguous)
            // The ingestor's observed clock is at + 3_600_000; this SMS is not eligible evidence.
            ingest(r, body("status", "99.00"), at + 7_200_000)
            check(r.snapshot().fuelCoupons.single() == conflicted)
            val ignored = r.snapshot().fuelObservations.single { it.result == "INELIGIBLE" }
            check(!r.snapshot().events.single { it.id == ignored.eventId }.evidenceEligible)
            ingest(r, body("status", "6.00"), at + 3000)
            check(r.snapshot().fuelCoupons.single().let { !it.ambiguous && it.balance == "6.00" && it.observedAt == at + 3000 })
            check(r.snapshot().fuelObservations.any { it == ignored })
            check(r.snapshot().fuelObservations.any { it.result == "CONFLICT" })
        }
        case("Fuel backup needs portable re-sealing with a different local key and preserves evidence age") { r, _ ->
            ingest(r)
            r.putOperation(operation())
            val original = r.snapshot()
            val local = r.protectedFuelEnvelopes(original.fuelCoupons)
            val targetStore = store(2)
            check(runCatching { targetStore.open(original.fuelCoupons.single(), local.single()) }.isFailure)
            val targetName = "fuel_restore_${UUID.randomUUID()}.db"
            val target = RoomWalletRepository.open(context, targetName)
            try {
                check(runCatching { target.importSnapshot(original) }.isFailure)
                check(target.snapshot().events.isEmpty() && target.snapshot().operations.isEmpty())
                val portable = store().exportForBackup(original.fuelCoupons, local)
                try {
                    val wrapped = targetStore.protectAfterRestore(original.fuelCoupons, portable)
                    check(target.importSnapshot(SnapshotCodec.decode(SnapshotCodec.encode(original)), wrapped).conflicts.isEmpty())
                    val restored = target.snapshot()
                    check(restored.fuelCoupons.single().let { !it.evidenceEligible && !it.ambiguous && it.observedAt == at })
                    check(restored.fuelObservations.single().purchaseEvidence?.evidenceEligible == false)
                    check(restored.operations.single().let { it.restored && it.status == OperationStatus.UNCERTAIN })
                    check(!target.confirmOperation("fuel", restored.receipts.single().id, at + 1000, null))
                    targetStore.open(restored.fuelCoupons.single(), target.protectedFuelEnvelopes(restored.fuelCoupons).single()).use { envelope ->
                        FuelCredential.fromProviderEnvelope(restored.fuelCoupons.single(), envelope).use { credential ->
                            check(credential.qrPayload() == "{\"Pin\":\"1234\",\"Token\":\"TOKEN987\"}")
                        }
                    }
                    check(target.importSnapshot(original).conflicts.isEmpty())
                } finally { portable.forEach(FuelBackupEnvelope::close) }
            } finally { target.close(); check(context.deleteDatabase(targetName)) }
        }
        case("Fuel exact purchase evidence can confirm only a unique matching service35 operation") { r, _ ->
            r.putOperation(operation())
            val stored = ingest(r)
            val receipt = stored.fuelReceiptIds.single()
            check(r.confirmOperation("fuel", receipt, at + 1000, null))
            check(r.snapshot().operations.single().status == OperationStatus.CONFIRMED)
            check(r.snapshot().usedReferences.single().reference == "BANK1")
            check(!r.confirmOperation("fuel", receipt, at + 2000, null))
        }
        case("Fuel late purchase cannot choose the latest of two identical uncertain requests or another service family") { r, _ ->
            r.putOperation(operation("old", at - 3000).copy(status = OperationStatus.UNCERTAIN, reviewRequired = false))
            r.putOperation(operation("new", at - 1000))
            r.putOperation(operation("telephone").copy(kind = "service.telephone", specId = "service.telephone"))
            val receipt = ingest(r).fuelReceiptIds.single()
            check(listOf("old", "new", "telephone").none { r.confirmOperation(it, receipt, at + 1000, null) })
            check(r.snapshot().usedReferences.isEmpty())
        }
        case("Fuel unsupported currency mismatched contract amount missing CUP marker and generic receipt cannot confirm") { r, _ ->
            val proof = FuelPurchaseEvidence(couponId, "0000000000001", "02", 7, "BANK1", "TM1", "10.00", "CUP", at, true)
            check(fuelPurchaseMatches(operation(), proof))
            check(!fuelPurchaseMatches(operation().copy(parameters = mapOf("amount" to "10.00")), proof))
            check(!fuelPurchaseMatches(operation().copy(amount = "11.00"), proof))
            check(!fuelPurchaseMatches(operation(), proof.copy(currency = "USD")))
            r.putOperation(operation())
            val generic = ReceiptRecord("", "", "02", 7, "PAYMENT", "GENERIC", "10.00", "CUP", "Servicio")
            val receipt = r.ingest(IncomingEvent(EventSource.INBOX, "generic", "PAGOxMOVIL", "Synthetic generic receipt", at, 7, generic)).receiptId!!
            check(!r.confirmOperation("fuel", receipt, at + 1000, null))
            ingest(r)
            val before = r.snapshot()
            val corrupt = before.copy(fuelCoupons = listOf(before.fuelCoupons.single().copy(balance = "NaN")))
            check(runCatching { r.importSnapshot(corrupt) }.isFailure && r.snapshot() == before)
        }
        return passed
    }
}
