package dev.duardo.neotransfer.data

import android.content.Context
import dev.duardo.neotransfer.platform.BankSmsRecord
import java.time.Instant
import java.util.UUID

/** Explicit paid debit is correlated against immutable authorized service fields inside the Room transaction. */
object ServiceReceiptDataChecks {
    fun run(context: Context, onPassed: (String) -> Unit = {}): Int {
        var passed = 0
        fun case(name: String, body: (RoomWalletRepository, SmsIngestor) -> Unit) {
            val databaseName = "service_receipt_test_${UUID.randomUUID()}.db"
            RoomWalletRepository.open(context, databaseName).use { repository ->
                try { body(repository, SmsIngestor(repository, now = { Instant.ofEpochMilli(2000) })); passed++; onPassed(name) }
                finally { repository.close(); check(context.deleteDatabase(databaseName)) }
            }
        }

        case("Nauta home recharge and stamp confirmations retain authorized nominal and record only explicit paid debit") { r, io ->
            val examples = listOf(
                nautaOperation("nauta") to nautaReceipt("NAUTA01"),
                nautaOperation("home", home = true, bank = "BANDEC", bankCode = "02", accountType = "2") to
                    nautaReceipt("HOME01", bank = "Bandec", home = true, account = "fixture@nauta.co.cu", paid = "300.00"),
                stampOperation("stamp") to stampReceipt("STAMP01"),
            )
            examples.forEachIndexed { index, (operation, body) ->
                r.putOperation(operation)
                val receiptId = checkNotNull(io.ingest(sms(index + 1L, body)).stored.receiptId)
                val receipt = r.snapshot().receipts.single { it.id == receiptId }
                check(r.confirmOperation(operation.id, receiptId, 1600, null))
                val stored = r.snapshot().operations.single { it.id == operation.id }
                check(stored.status == OperationStatus.CONFIRMED && stored.amount == receipt.amount)
                check(stored.parameters == operation.parameters && stored.parameters["amount"] == operation.amount)
                check(receipt.nominalAmount?.toBigDecimal()?.compareTo(operation.amount!!.toBigDecimal()) == 0)
                check(r.snapshot().receipts.single { it.id == receiptId }.operationId == operation.id)
            }
            check(r.snapshot().operations.single { it.id == "nauta" }.amount == "270.00")
            check(r.snapshot().usedReferences.size == 3 && r.snapshot().movements.size == 3)
        }

        case("Wrong Nauta account domain nominal paid bound SIM provider and future evidence remain unbound history") { r, io ->
            val operation = nautaOperation("nauta")
            r.putOperation(operation)
            val bodies = listOf(
                nautaReceipt("WRONGUSER", account = "other@nauta.com.cu"),
                nautaReceipt("WRONGDOMAIN", account = "fixture@nauta.co.cu"),
                nautaReceipt("WRONGNOMINAL", nominal = "400.00"),
                nautaReceipt("TOOMUCH", paid = "301.00"),
                nautaReceipt("NOPAID", paid = null),
                nautaReceipt("WRONGBANK", bank = "Bandec"),
            )
            bodies.forEachIndexed { index, body ->
                val receipt = checkNotNull(io.ingest(sms(index + 1L, body)).stored.receiptId)
                check(!r.confirmOperation(operation.id, receipt, 1600, null))
            }
            val wrongSim = checkNotNull(io.ingest(sms(90, nautaReceipt("WRONGSIM"), sim = 8)).stored.receiptId)
            check(!r.confirmOperation(operation.id, wrongSim, 1600, null))
            val future = checkNotNull(io.ingest(sms(91, nautaReceipt("FUTURE"), at = 3000)).stored.receiptId)
            check(!r.confirmOperation(operation.id, future, 4000, null))
            check(r.snapshot().operations.single() == operation)
            check(r.snapshot().receipts.size == 8 && r.snapshot().receipts.all { it.operationId == null })
            check(r.snapshot().usedReferences.isEmpty())
        }

        case("Stamp requires exact payer entity and stamp identity rather than matching a paid amount") { r, io ->
            val operation = stampOperation("stamp")
            r.putOperation(operation)
            val bodies = listOf(stampReceipt("WRONGCI", payer = "00000000002"),
                stampReceipt("WRONGENTITY", entity = "Otros trámites"),
                stampReceipt("NOSTAMP").replace("IdSello: SELLO01.\n", ""),
                stampReceipt("TOOMUCH", paid = "101.00"))
            bodies.forEachIndexed { index, body ->
                val id = checkNotNull(io.ingest(sms(index + 1L, body)).stored.receiptId)
                check(!r.confirmOperation(operation.id, id, 1600, null))
            }
            check(r.snapshot().operations.single() == operation && r.snapshot().usedReferences.isEmpty())
            check(r.snapshot().receipts.all { it.operationId == null })
        }

        case("Two identical Nauta requests including reviewed uncertainty cannot attribute a late receipt to the latest request") { r, io ->
            val old = nautaOperation("old").copy(status = OperationStatus.UNCERTAIN, reviewRequired = false)
            val latest = nautaOperation("latest").copy(startedAt = 1200, updatedAt = 1200)
            r.putOperation(old); r.putOperation(latest)
            val id = checkNotNull(io.ingest(sms(1, nautaReceipt("LATE01"), at = 1500)).stored.receiptId)
            check(!r.confirmOperation(latest.id, id, 1600, null))
            check(!r.confirmOperation(old.id, id, 1600, null))
            check(r.snapshot().receipts.single().operationId == null && r.snapshot().usedReferences.isEmpty())
            check(r.snapshot().operations.associate { it.id to it.status } == mapOf("old" to OperationStatus.UNCERTAIN, "latest" to OperationStatus.SUBMITTING))
        }

        case("Service confirmation reparses original evidence and rejects altered receipt metadata and restored requests") { r, io ->
            val operation = nautaOperation("nauta")
            r.putOperation(operation)
            val body = nautaReceipt("ALTERED", paid = "280.00")
            val metadata = ReceiptRecord("", "", "01", 7, "PAYMENT", "ALTERED", "270.00", "CUP", "fixture@nauta.com.cu", nominalAmount = "300.00")
            val altered = checkNotNull(r.ingest(IncomingEvent(EventSource.INBOX, "altered", "PAGOxMOVIL", body, 1500, 7, metadata)).receiptId)
            check(!r.confirmOperation(operation.id, altered, 1600, null))
            val restored = operation.copy(id = "restored", restored = true, status = OperationStatus.UNCERTAIN)
            r.putOperation(restored)
            val valid = checkNotNull(io.ingest(sms(10, nautaReceipt("VALID"))).stored.receiptId)
            check(!r.confirmOperation(restored.id, valid, 1600, null))
            check(r.snapshot().usedReferences.isEmpty() && r.snapshot().receipts.all { it.operationId == null })
        }
        case("Nauta debt cannot self-confirm and blocks an indistinguishable Nauta Hogar payment") { r, io ->
            val debt = nautaOperation("debt", home = true).copy(kind = "service.nauta.debt", specId = "service.nauta.debt",
                status = OperationStatus.UNCERTAIN, reviewRequired = false)
            r.putOperation(debt)
            val receipt = checkNotNull(io.ingest(sms(1, nautaReceipt("DEBT84", home = true))).stored.receiptId)
            check(!r.confirmOperation(debt.id, receipt, 1600, null))
            val hogar = nautaOperation("hogar", home = true).copy(startedAt = 1200, updatedAt = 1200)
            r.putOperation(hogar)
            check(!r.confirmOperation(hogar.id, receipt, 1600, null))
            check(r.snapshot().receipts.single().operationId == null && r.snapshot().usedReferences.isEmpty())
        }
        return passed
    }

    private fun sms(id: Long, body: String, at: Long = 1500, sim: Int = 7) = BankSmsRecord(id, body, Instant.ofEpochMilli(at), sim)
    private fun nautaOperation(id: String, home: Boolean = false, bank: String = "BPA", bankCode: String = "01", accountType: String = "1"): OperationRecord {
        val spec = if (home) "service.nauta.home" else "service.nauta"
        return OperationRecord(id, spec, bankCode, 7, "", "300.00", "CUP", 1000, status = OperationStatus.SUBMITTING,
            providerId = bank, parameters = mapOf("amount" to "300.00", "username" to "fixture", "accountType" to accountType))
    }
    private fun stampOperation(id: String) = OperationRecord(id, "service.stamp", "01", 7, "", "100.00", "CUP", 1000,
        status = OperationStatus.SUBMITTING, providerId = "BPA", parameters = mapOf("amount" to "100.00", "taxpayer" to "00000000001", "entity" to "95016"))
    private fun nautaReceipt(reference: String, bank: String = "Popular de Ahorro", home: Boolean = false,
        account: String = "fixture@nauta.com.cu", nominal: String = "300.00", paid: String? = "270.00") =
        "Banco $bank: La cuenta ${if (home) "Nauta Hogar" else ""}: $account ha sido ${if (home) "pagada" else "recargada"} con $nominal CUP.\n" +
            (paid?.let { "Monto Pagado: $it CUP.\n" } ?: "") + "Id Transaccion: $reference."
    private fun stampReceipt(reference: String, payer: String = "00000000001", entity: String = "Oficinas Trámites Minint", paid: String = "90.00") =
        "Banco Popular de Ahorro El pago del impuesto sobre el documento (sello del timbre) fue completado.\n" +
            "CI: $payer\nEntidad: $entity\nValor del sello: 100.00 CUP.\nImporte Pagado: $paid CUP.\nIdSello: SELLO01.\nNro. Transaccion Banco: $reference."
}
