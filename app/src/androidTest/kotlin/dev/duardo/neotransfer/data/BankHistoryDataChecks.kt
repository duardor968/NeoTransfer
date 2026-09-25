package dev.duardo.neotransfer.data

import android.content.Context
import dev.duardo.neotransfer.platform.BankSmsRecord
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Synthetic statement and receipt evidence; no inbox, modem or production database access. */
object BankHistoryDataChecks {
    fun run(context: Context, onPassed: (String) -> Unit = {}): Int {
        var passed = 0
        fun case(name: String, body: (RoomWalletRepository, SmsIngestor) -> Unit) {
            val databaseName = "banking_history_test_${UUID.randomUUID()}.db"
            RoomWalletRepository.open(context, databaseName).use { repository ->
                try {
                    body(repository, SmsIngestor(repository, now = { NOW }))
                    passed++; onPassed(name)
                } finally { repository.close(); check(context.deleteDatabase(databaseName)) }
            }
        }

        case("Bank history keeps dates and both own-transfer directions without creating payment receipts") { r, ingestor ->
            val result = ingestor.ingest(record(1, statement(row("REF01", "Db"), row("REF01", "Cr")), NOW.minusSeconds(30)))
            val snapshot = r.snapshot()
            check(result.stored.historyIds.size == 2 && !result.newFinancialMovement)
            check(snapshot.histories.map { it.incoming }.toSet() == setOf(true, false))
            check(snapshot.histories.all { it.postedOn == "2026-09-22" && it.account == null && it.receiptId == null })
            check(snapshot.receipts.isEmpty() && snapshot.movements.isEmpty() && snapshot.usedReferences.isEmpty())
        }

        case("Bank history references deduplicate repeat statements while unreferenced rows retain separate observations") { r, ingestor ->
            val body = statement(row("REF01"), "22/09/2026;Intereses;Cr;0.10;CUP; |")
            ingestor.ingest(record(1, body, NOW.minusSeconds(30)))
            ingestor.ingest(record(2, body, NOW.minusSeconds(20)))
            ingestor.ingest(record(2, body, NOW.minusSeconds(20)))
            val snapshot = r.snapshot()
            check(snapshot.histories.count { it.reference == "REF01" } == 1)
            check(snapshot.histories.count { it.reference == null } == 2)
            check(snapshot.historyObservations.size == 4)
        }

        case("A late direct receipt joins exactly one history row using Cuba calendar day") { r, ingestor ->
            // 02:00 UTC on 23 September is still 22 September in Cuba.
            ingestor.ingest(record(1, statement(row("REF01", "Db"), row("REF01", "Cr")), NOW.minusSeconds(20)))
            val paid = ingestor.ingest(record(2, receipt("REF01"), NOW.minusSeconds(10)))
            val snapshot = r.snapshot()
            check(snapshot.histories.single { !it.incoming }.receiptId == paid.stored.receiptId)
            check(snapshot.histories.single { it.incoming }.receiptId == null)
            check(snapshot.histories.all { it.account == null }) // Beneficiary is never the source account.
            check(snapshot.movements.size == 1 && snapshot.operations.isEmpty() && snapshot.usedReferences.isEmpty())
        }

        case("A statement arriving after a receipt uses the same exact reconciliation") { r, ingestor ->
            val receipt = ingestor.ingest(record(1, receipt("REF01"), NOW.minusSeconds(20)))
            ingestor.ingest(record(2, statement(row("REF01")), NOW.minusSeconds(10)))
            check(r.snapshot().histories.single().receiptId == receipt.stored.receiptId)
        }

        case("History matching rejects wrong day SIM bank amount currency and unknown SIM") { r, ingestor ->
            ingestor.ingest(record(1, statement(row("DAY01", date = "21/09/2026"), row("SIM01"), row("BANK01"),
                row("AMOUNT01"), row("CURRENCY01"), row(null)), NOW.minusSeconds(30)))
            ingestor.ingest(record(2, receipt("DAY01"), NOW.minusSeconds(20)))
            ingestor.ingest(record(3, receipt("SIM01"), NOW.minusSeconds(19), 8))
            ingestor.ingest(record(4, receipt("BANK01").replace("Bandec", "BPA"), NOW.minusSeconds(18)))
            ingestor.ingest(record(5, receipt("AMOUNT01").replace("10.00 CUP", "99.00 CUP"), NOW.minusSeconds(17)))
            ingestor.ingest(record(6, receipt("CURRENCY01").replace("10.00 CUP", "10.00 USD"), NOW.minusSeconds(16)))
            ingestor.ingest(record(7, statement(row("UNKNOWN01")), NOW.minusSeconds(15), null))
            ingestor.ingest(record(8, receipt("UNKNOWN01"), NOW.minusSeconds(14), null))
            val snapshot = r.snapshot()
            check(snapshot.histories.all { it.receiptId == null })
            check(snapshot.histories.single { it.reference == "AMOUNT01" }.referenceConflict)
            check(snapshot.histories.single { it.reference == "CURRENCY01" }.referenceConflict)
            check(snapshot.receipts.none { it.referenceConflict }) // A statement cannot invalidate a direct confirmation.
        }

        case("Multiple matching receipts revoke an earlier unique link without choosing a counterparty") { r, ingestor ->
            ingestor.ingest(record(1, statement(row("REF01")), NOW.minusSeconds(30)))
            ingestor.ingest(record(2, receipt("REF01"), NOW.minusSeconds(300)))
            check(r.snapshot().histories.single().receiptId != null)
            ingestor.ingest(record(3, receipt("REF01"), NOW.minusSeconds(10)))
            check(r.snapshot().histories.single().let { it.receiptId == null && it.ambiguous })
        }

        case("Fresh query may bind its explicit account but cannot confirm a payment by a statement") { r, ingestor ->
            val started = NOW.minusSeconds(25)
            r.putOperation(query("query", started, "0000000000000001"))
            val result = ingestor.ingest(record(1, statement(row("REF01")), NOW.minusSeconds(20)))
            check(r.bindHistoryToQuery(result.stored.eventId, "query", NOW.toEpochMilli()))
            val snapshot = r.snapshot()
            check(snapshot.histories.single().let { it.account == "0000000000000001" && it.queryOperationId == "query" })
            check(snapshot.operations.single().status == OperationStatus.CONFIRMED)
            check(snapshot.receipts.isEmpty() && snapshot.movements.isEmpty() && snapshot.usedReferences.isEmpty())
        }

        case("Default query keeps the account unknown and stale restored or wrong-SIM queries cannot bind") { r, ingestor ->
            val result = ingestor.ingest(record(1, statement(row("REF01")), NOW.minusSeconds(10)))
            r.putOperation(query("stale", NOW.minusSeconds(60), "0000000000000001"))
            r.putOperation(query("restored", NOW.minusSeconds(20), "0000000000000001").copy(restored = true, status = OperationStatus.UNCERTAIN))
            r.putOperation(query("wrong-sim", NOW.minusSeconds(20), "0000000000000001").copy(subscriptionId = 8))
            for (id in listOf("stale", "restored", "wrong-sim")) check(!r.bindHistoryToQuery(result.stored.eventId, id, NOW.toEpochMilli()))
            check(r.snapshot().histories.single().account == null)
            r.putOperation(query("default", NOW.minusSeconds(20), "0000"))
            check(r.bindHistoryToQuery(result.stored.eventId, "default", NOW.toEpochMilli()))
            check(r.snapshot().histories.single().account == null)
        }

        case("Two correlated source accounts split a repeated reference rather than overwrite its attribution") { r, ingestor ->
            val body = statement(row("REF01"))
            r.putOperation(query("query-a", NOW.minusSeconds(25), "0000000000000001"))
            val first = ingestor.ingest(record(1, body, NOW.minusSeconds(20)))
            check(r.bindHistoryToQuery(first.stored.eventId, "query-a", NOW.minusSeconds(19).toEpochMilli()))
            r.putOperation(query("query-b", NOW.minusSeconds(15), "0000000000000002"))
            val second = ingestor.ingest(record(2, body, NOW.minusSeconds(10)))
            check(r.bindHistoryToQuery(second.stored.eventId, "query-b", NOW.toEpochMilli()))
            check(r.snapshot().histories.map { it.account }.toSet() == setOf("0000000000000001", "0000000000000002"))
            check(r.snapshot().historyObservations.map { it.entryId }.distinct().size == 2)
        }

        case("History metadata survives backup and earlier version-one snapshots remain readable") { r, ingestor ->
            ingestor.ingest(record(1, statement(row("REF01")), NOW.minusSeconds(20)))
            ingestor.ingest(record(2, receipt("REF01"), NOW.minusSeconds(10)))
            val snapshot = r.snapshot()
            val decoded = SnapshotCodec.decode(SnapshotCodec.encode(snapshot))
            check(decoded == snapshot)
            val old = JSONObject(String(SnapshotCodec.encode(snapshot), Charsets.UTF_8)).apply {
                remove("histories"); remove("historyObservations")
            }
            check(SnapshotCodec.decode(old.toString().toByteArray()).histories.isEmpty())
            val destinationName = "banking_history_restore_${UUID.randomUUID()}.db"
            RoomWalletRepository.open(context, destinationName).use { destination ->
                try {
                    check(destination.importSnapshot(decoded).conflicts.isEmpty())
                    check(destination.snapshot().histories == snapshot.histories)
                    check(destination.snapshot().historyObservations == snapshot.historyObservations)
                } finally { destination.close(); check(context.deleteDatabase(destinationName)) }
            }
        }

        case("MiTransfer CUP and USD accounts may omit PAN while other providers and duplicate currency cannot") { r, _ ->
            r.putIdentity(IdentityRecord("owner", "Perfil sintético"))
            r.putRegistration(RegistrationRecord("wallet", "owner", "MITRANSFER", "PERSONAL", null, 7, null, "MiTransfer"))
            r.putAccount(AccountRecord("cup", "wallet", "", "Monedero CUP", "CUP"))
            r.putAccount(AccountRecord("usd", "wallet", "", "Monedero USD", "USD"))
            check(r.snapshot().accounts.size == 2)
            check(runCatching { r.putAccount(AccountRecord("cup-duplicate", "wallet", "", "Otro", "CUP")) }.isFailure)
            check(runCatching { r.putAccount(AccountRecord("eur", "wallet", "", "EUR", "EUR")) }.isFailure)
            check(runCatching { r.putAccount(AccountRecord("classic", "wallet", "", "Clásica", "USD", profileId = "CLASSIC")) }.isFailure)
            r.putRegistration(RegistrationRecord("bank", "owner", "BANDEC", "PERSONAL", "02", 7, null, "BANDEC"))
            check(runCatching { r.putAccount(AccountRecord("bank-empty", "bank", "", "Cuenta", "CUP")) }.isFailure)
            val service = SavedServiceRecord("service", "telephone", "Teléfono", "00000000000001", fieldKey = "invoice")
            r.putService(service)
            check(SnapshotCodec.decode(SnapshotCodec.encode(r.snapshot())).services.single() == service)
        }

        case("Corrupt financial backup values fail before any imported identity is written") { r, _ ->
            val base = WalletSnapshot(identities = listOf(IdentityRecord("must-not-exist", "Perfil")),
                receipts = listOf(ReceiptRecord("receipt", "event", "02", 7, "SENT", "REF01", "10.00", "CUP", "fixture")))
            listOf(base.copy(receipts = listOf(base.receipts.single().copy(currency = "UNKNOWN"))),
                base.copy(receipts = listOf(base.receipts.single().copy(amount = "NaN"))),
                base.copy(receipts = listOf(base.receipts.single().copy(kind = "FUTURE_KIND")))).forEach { bad ->
                check(runCatching { SnapshotCodec.decode(SnapshotCodec.encode(bad)) }.isFailure)
                check(runCatching { r.importSnapshot(bad) }.isFailure)
                check(r.snapshot().identities.isEmpty() && r.snapshot().receipts.isEmpty())
            }
        }
        return passed
    }

    private val NOW = Instant.parse("2026-09-23T02:00:00Z")
    private fun record(id: Long, body: String, at: Instant, sim: Int? = 7) = BankSmsRecord(id, body, at, sim)
    private fun row(reference: String?, direction: String = "Db", date: String = "22/09/2026") =
        "$date;Banca Movil Transferencia${reference?.let { " Ref: $it" }.orEmpty()};$direction;10.00;CUP; |"
    private fun statement(vararg rows: String) = "Banco Bandec Ultimas operaciones.\n\nFecha;Servicio;Operacion;Monto;Moneda;NoTransaccion\n\n" + rows.joinToString("\n")
    private fun receipt(reference: String) = "Banco Bandec: La Transferencia fue completada.\nBeneficiario: 0000XXXXXXXX0002\nMonto: 10.00 CUP\nNro. Transaccion: $reference"
    private fun query(id: String, started: Instant, source: String) = OperationRecord(id, "bandec.recent-operations", "02", 7,
        "", null, null, started.toEpochMilli(), source = source, status = OperationStatus.AWAITING_CONFIRMATION,
        providerId = "BANDEC", parameters = mapOf("type" to "1"))
}
