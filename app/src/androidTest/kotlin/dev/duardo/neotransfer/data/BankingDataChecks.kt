package dev.duardo.neotransfer.data

import android.content.Context
import dev.duardo.neotransfer.core.BankMessage
import dev.duardo.neotransfer.platform.BankSmsRecord
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.time.Instant

/** Synthetic app-private fixtures only. Called from the existing instrumentation worker. */
object BankingDataChecks {
    fun run(context: Context, onPassed: (String) -> Unit = {}): Int {
        var passed = 0
        fun case(name: String, body: (RoomWalletRepository, String) -> Unit) {
            val databaseName = "banking_data_test_${UUID.randomUUID()}.db"
            val repository = RoomWalletRepository.open(context, databaseName)
            try { body(repository, databaseName); passed++; onPassed(name) }
            finally { repository.close(); check(context.deleteDatabase(databaseName)) }
        }

        case("Room v0.2 migration preserves pending, uncertain, contacts, balances and consumed references") { r, _ ->
            val source = legacyFixture()
            val before = source.toMap()
            val result = r.migrateLegacy(source)
            check(!result.alreadyMigrated && result.issues.isEmpty())
            val state = r.snapshot()
            check(state.operations.size == 2 && state.operations.all { it.restored && it.reviewRequired })
            check(state.operations.single { it.id == "pending-id" }.let {
                it.status == OperationStatus.AWAITING_CONFIRMATION && it.source == "0000000000000001" && it.refreshTaken
            })
            check(state.operations.single { it.id == "uncertain-id" }.status == OperationStatus.UNCERTAIN)
            check(state.contacts.single().let { it.name == "Persona sintética" && it.cards.single().number == "0000000000000002" && it.phones.single().number == "50000001" })
            check(state.balances.single().let { it.account == null && it.ledger == null && it.available == "420.00" && it.label == "Ahorro" })
            check(state.usedReferences.single().bankCode == "02")
            check(!r.markReferenceUsed(UsedReference("02", "ref-01.")))
            check(state.settings.activeBankCode == "02" && state.settings.subscriptionId == 7)
            check(state.refresh == RefreshRequest("02", 7, 1234))
            check(source == before)
            check(r.dao.legacyArchive().size == source.size)
        }

        case("Room migration is idempotent across reopen and never returns a restored payment to submission") { r, name ->
            r.migrateLegacy(legacyFixture())
            val before = r.snapshot()
            check(!r.updateOperationStatus("pending-id", OperationStatus.AWAITING_CONFIRMATION, OperationStatus.SUBMITTING, 9000))
            r.close()
            RoomWalletRepository.open(context, name).use { reopened ->
                check(reopened.migrateLegacy(legacyFixture()).alreadyMigrated)
                check(reopened.snapshot() == before)
                val restored = before.operations.first()
                check(runCatching { reopened.putOperation(restored.copy(status = OperationStatus.SUBMITTING)) }.isFailure)
            }
        }

        case("Room migration archive failure rolls back all imported tables and permits retry") { r, _ ->
            r.dao.put(LegacyArchiveRow("pending", "synthetic collision", null))
            check(runCatching { r.migrateLegacy(legacyFixture()) }.isFailure)
            check(r.snapshot().operations.isEmpty() && r.snapshot().identities.isEmpty())
            check(r.dao.metadata(LegacyWalletMigration.MARKER) == null)
            r.database.openHelper.writableDatabase.execSQL("DELETE FROM legacy_archive WHERE `key` = 'pending'")
            check(!r.migrateLegacy(legacyFixture()).alreadyMigrated)
            check(r.snapshot().operations.size == 2)
        }

        case("Room migration quarantines malformed legacy metadata and omits secret keys") { r, _ ->
            val source = legacyFixture().toMutableMap().apply {
                this["pending"] = """{"kind":"QR","bank":"BANDEC","pin":"99999","unknown":{"password":"hidden","merchant":"fixture"}}"""
                this["credentials"] = "NEVER_IMPORT_SECRET"
            }
            val result = r.migrateLegacy(source)
            check(result.issues.any { it.startsWith("pending:") })
            check(r.snapshot().operations.single().id == "uncertain-id")
            val archive = r.dao.legacyArchive()
            check(archive.none { it.key == "credentials" })
            check(archive.none { "99999" in it.safeValue || "hidden" in it.safeValue || "NEVER_IMPORT_SECRET" in it.safeValue })
            check("merchant" in archive.single { it.key == "pending" }.safeValue)
            check(r.snapshot().migrationIssues.isNotEmpty())
        }

        case("Room empty installation does not invent an imported access") { r, _ ->
            r.migrateLegacy(emptyMap<String, Any>())
            check(r.snapshot().identities.isEmpty() && r.snapshot().registrations.isEmpty())
        }

        case("Room retains multiple contact phones and cards through edit and deletion") { r, _ ->
            val contact = ContactRecord("contact", "Persona sintética", listOf(ContactPhone("50000001", "Casa"), ContactPhone("50000002", "Trabajo")),
                listOf(ContactCard("0000000000000001", "CUP", "02"), ContactCard("0000000000000002", "USD", "01")), true)
            r.putContact(contact); check(r.snapshot().contacts.single() == contact)
            val edited = contact.copy(name = "Nombre editado", phones = contact.phones.take(1), cards = contact.cards.drop(1))
            r.putContact(edited); check(r.snapshot().contacts.single() == edited)
            check(r.dao.phones().size == 1 && r.dao.contactCards().size == 1)
            r.deleteContact(contact.id)
            check(r.snapshot().contacts.isEmpty() && r.dao.phones().isEmpty() && r.dao.contactCards().isEmpty())
        }

        case("Room separates accounts and cards and rejects cross-registration association") { r, _ ->
            identity(r)
            r.putRegistration(registration("reg-two", 8))
            r.putAccount(AccountRecord("account", "registration", "000000000001", "Ahorro", "CUP", true))
            r.putCard(CardRecord("card", "registration", "0000000000000001", "Débito", "account", "CUP", true))
            check(runCatching { r.putCard(CardRecord("wrong", "reg-two", "0000000000000002", "Otra", "account")) }.isFailure)
            check(runCatching { r.putAccount(AccountRecord("account", "reg-two", "000000000001", "Ahorro", "CUP")) }.isFailure)
            check(runCatching { r.putRegistration(registration("registration", 7).copy(providerId = "BPA", bankCode = "01")) }.isFailure)
            r.setSettings(WalletSettings(selectedRegistrationId = "registration", selectedCardId = "card"))
            r.deleteAccount("account")
            check(r.snapshot().cards.single().accountId == null)
            r.deleteRegistration("registration")
            check(r.snapshot().cards.isEmpty() && r.snapshot().settings.selectedRegistrationId == null)
            r.deleteRegistration("reg-two"); r.deleteIdentity("identity")
            check(r.snapshot().identities.isEmpty())
        }

        case("Room product balance upsert preserves other accounts and ignores older evidence") { r, _ ->
            val first = BalanceRecord("one", "02", 7, 200, "XXXX0001", "10.00", "CUP", accountId = "a")
            val second = BalanceRecord("two", "02", 7, 200, "XXXX0002", "20.00", "CUP", accountId = "b")
            r.upsertBalances(listOf(first, second))
            r.upsertBalances(listOf(first.copy(at = 300, available = "15.00")))
            r.upsertBalances(listOf(first.copy(at = 100, available = "999.00")))
            check(r.snapshot().balances.associate { it.id to it.available } == mapOf("one" to "15.00", "two" to "20.00"))
            check(runCatching { r.upsertBalances(listOf(first.copy(accountId = "another"))) }.isFailure)
        }

        case("Room operation round trip retains complete QR and non-secret operation fields") { r, name ->
            val qr = QrDetails("ESTATICO_fixture", "provider-fixture", "0.00", "CUP", "1", "Compra", "Concepto", "qr-fixture", "2026-01-01", "2026-12-31")
            val operation = operation().copy(qr = qr, description = "Factura sintética", specId = "qr.static",
                providerId = "BANDEC", parameters = mapOf("territory" to "01", "invoice" to "00000000001"))
            r.putOperation(operation)
            r.close()
            RoomWalletRepository.open(context, name).use { reopened -> check(reopened.snapshot().operations.single() == operation) }
        }

        case("Room rejects operation credentials and encoded modem commands") { r, _ ->
            check(runCatching { r.putOperation(operation().copy(parameters = mapOf("pin" to "99999"))) }.isFailure)
            check(runCatching { r.putOperation(operation().copy(parameters = mapOf("description" to "*444*40*secret#"))) }.isFailure)
            check(r.snapshot().operations.isEmpty())
        }

        case("Room pairs broadcast and inbox once while retaining both original timestamps") { r, _ ->
            val first = r.ingest(event(EventSource.BROADCAST, null, 1000))
            val second = r.ingest(event(EventSource.INBOX, "sms-1", 2000))
            check(second.duplicate && first.canonicalEventId == second.canonicalEventId)
            check(r.snapshot().events.map { it.receivedAt }.toSet() == setOf(1000L, 2000L))
            check(r.snapshot().receipts.size == 1 && r.snapshot().movements.size == 1)
            check(r.ingest(event(EventSource.INBOX, "sms-1", 2000)).duplicate)
            check(r.snapshot().events.size == 2)
        }

        case("Room preserves same-source repetitions, other SIMs and distinct financial facts") { r, _ ->
            val plain = event(EventSource.BROADCAST, null, 1000).copy(body = "Consulta completada", receipt = null)
            r.ingest(plain); r.ingest(plain.copy(receivedAt = 2000))
            check(r.snapshot().events.size == 2)
            val incoming = event(EventSource.INBOX, "sms-2", 3000)
            r.ingest(incoming)
            r.ingest(incoming.copy(sourceId = "sms-3", subscriptionId = 8, receipt = receipt().copy(subscriptionId = 8)))
            r.ingest(incoming.copy(sourceId = "sms-4", body = "synthetic different amount", receipt = receipt().copy(amount = "99.00")))
            check(r.snapshot().receipts.size == 3)
            check(r.snapshot().receipts.filter { it.subscriptionId == 7 }.all { it.referenceConflict })
            check(!r.snapshot().receipts.single { it.subscriptionId == 8 }.referenceConflict)
        }

        case("Room reference normalization pairs equivalent cross-source financial receipts") { r, _ ->
            val first = r.ingest(event(EventSource.BROADCAST, null, 1000))
            val equivalent = event(EventSource.INBOX, "normalized", 2000).copy(body = "Equivalent synthetic receipt with different spacing",
                receipt = receipt().copy(reference = " REF-01. ", amount = "10.0"))
            val second = r.ingest(equivalent)
            check(second.duplicate && first.receiptId == second.receiptId)
            check(r.snapshot().events.size == 2 && r.snapshot().movements.size == 1)
        }

        case("Room withholds access secrets in SMS bodies without discarding delivery evidence") { r, _ ->
            r.ingest(event(EventSource.BROADCAST, null, 1000).copy(body = "Su PIN es 99999", receipt = null))
            val stored = r.snapshot().events.single()
            check(stored.body == null && stored.bodyWithheld && stored.receivedAt == 1000L)
        }

        case("Room confirms operation and consumes reference atomically without double confirmation") { r, _ ->
            r.putOperation(operation())
            check(r.updateOperationStatus("operation", OperationStatus.PREPARED, OperationStatus.SUBMITTING, 900))
            check(r.updateOperationStatus("operation", OperationStatus.SUBMITTING, OperationStatus.AWAITING_CONFIRMATION, 950))
            val result = r.ingest(event(EventSource.BROADCAST, null, 1000))
            val id = checkNotNull(result.receiptId)
            check(!r.confirmOperation("operation", id, 999, null))
            check(r.snapshot().usedReferences.isEmpty())
            check(r.confirmOperation("operation", id, 1000, RefreshRequest("02", 7, 1010)))
            check(!r.confirmOperation("operation", id, 1001, null))
            val state = r.snapshot()
            check(state.operations.single().status == OperationStatus.CONFIRMED && state.receipts.single().operationId == "operation")
            check(state.usedReferences.size == 1 && state.refresh?.dueAt == 1010L)
        }

        case("Room observable snapshots emit committed CRUD changes") { r, _ -> runBlocking {
            val channel = Channel<WalletSnapshot>(Channel.UNLIMITED)
            val job = launch { r.observe().collect { channel.send(it) } }
            try {
                withTimeout(5000) { check(channel.receive().contacts.isEmpty()) }
                r.putContact(ContactRecord("flow", "Contacto observado"))
                withTimeout(5000) { check(channel.receive().contacts.single().id == "flow") }
            } finally { job.cancelAndJoin(); channel.close() }
        } }

        case("Wallet backup codec round trips every metadata table and refuses unknown versions") { r, _ ->
            r.migrateLegacy(legacyFixture())
            val registration = r.snapshot().registrations.first().id
            r.putAccount(AccountRecord("codec-account", registration, "00000001", "Cuenta", "CUP"))
            r.putCard(CardRecord("codec-card", registration, "0000000000000001", "Tarjeta", "codec-account", "CUP"))
            r.putService(SavedServiceRecord("codec-service", "ELECTRICITY", "Casa", "00000000001", registration, "10.00", "CUP"))
            r.putOperation(operation().copy(qr = QrDetails("fixture", "provider", "10.00", "CUP", "1", "Descripción", "Pista", null, null, null), parameters = mapOf("invoice" to "00001")))
            r.ingest(event(EventSource.INBOX, "codec-sms", 2000))
            val snapshot = r.snapshot()
            check(SnapshotCodec.decode(SnapshotCodec.encode(snapshot)) == snapshot)
            val unsupported = String(SnapshotCodec.encode(snapshot), Charsets.UTF_8).replace("\"version\":1", "\"version\":99")
            check(runCatching { SnapshotCodec.decode(unsupported.toByteArray()) }.isFailure)
        }

        case("Wallet restore detaches foreign phone bindings and preserves historical delivery provenance") { r, _ ->
            val source = WalletSnapshot(
                identities = listOf(IdentityRecord("identity", "Persona sintética")),
                registrations = listOf(registration("registration", 77).copy(linePhone = "50000001")),
                operations = listOf(operation().copy(registrationId = "registration", subscriptionId = 77)),
                events = listOf(EventRecord("event", EventSource.INBOX, "123", "PAGOxMOVIL", "synthetic", 1000, 77, "event")),
                settings = WalletSettings("02", 77, "registration"), refresh = RefreshRequest("02", 77, 1001),
            )
            val result = r.importSnapshot(SnapshotCodec.decode(SnapshotCodec.encode(source)))
            check(result.conflicts.isEmpty() && result.registrationsToReassociate == listOf("registration"))
            val restored = r.snapshot()
            check(restored.registrations.single().let {
                it.subscriptionId == null && it.linePhone == null && !it.enabled && it.previousSubscriptionId == 77 && it.previousLinePhone == "50000001"
            })
            check(restored.operations.single().let { it.status == OperationStatus.UNCERTAIN && it.restored && it.reviewRequired })
            check(restored.events.single().let { it.source == EventSource.LEGACY && it.sourceId == null && it.originalSource == EventSource.INBOX && it.originalSourceId == "123" })
            check(restored.settings.subscriptionId == -1 && restored.refresh == null)
            check(r.importSnapshot(source).let { it.importedEntries == 0 && it.conflicts.isEmpty() })
        }

        case("Wallet restore reports conflicting operation IDs without replacing current records") { r, _ ->
            r.putOperation(operation())
            val conflicting = operation().copy(amount = "999.00")
            val result = r.importSnapshot(WalletSnapshot(operations = listOf(conflicting),
                contacts = listOf(ContactRecord("restored-contact", "Contacto conservado"))))
            check(result.conflicts == listOf("operation:operation"))
            check(r.snapshot().operations.single().amount == "10.00")
            check(r.snapshot().contacts.single().id == "restored-contact")
        }

        case("Wallet restore invalid foreign relation rolls back the entire merge") { r, _ ->
            val malformed = WalletSnapshot(identities = listOf(IdentityRecord("restore", "Perfil")),
                accounts = listOf(AccountRecord("orphan", "missing", "0001", "Cuenta")))
            check(runCatching { r.importSnapshot(malformed) }.isFailure)
            check(r.snapshot().identities.isEmpty() && r.snapshot().accounts.isEmpty())
        }

        case("Shared SMS ingestion stores financial history while locked and excludes administrative movements") { r, _ ->
            val ingestor = SmsIngestor(r, now = { Instant.ofEpochMilli(2000) })
            val body = "Banco Bandec: La Transferencia fue completada.\nBeneficiario: 0000XXXXXXXX0002\nMonto: 10.00 CUP\nNro. Transaccion: SMS01"
            val first = ingestor.ingest(BankSmsRecord(null, body, Instant.ofEpochMilli(1000), 7))
            check(first.newFinancialMovement && first.message is BankMessage.TransferSent)
            val duplicate = ingestor.ingest(BankSmsRecord(1, body, Instant.ofEpochMilli(1100), 7))
            check(!duplicate.newFinancialMovement && duplicate.stored.duplicate)
            ingestor.ingest(BankSmsRecord(2, "Mensaje administrativo sintético", Instant.ofEpochMilli(1200), 7))
            check(r.snapshot().movements.size == 1 && r.snapshot().events.size == 3)
        }

        case("Shared SMS ingestion future evidence cannot poison the current counterpart") { r, _ ->
            val ingestor = SmsIngestor(r, now = { Instant.ofEpochMilli(2000) })
            val body = "Banco Bandec: La Transferencia fue completada.\nBeneficiario: 0000XXXXXXXX0002\nMonto: 10.00 CUP\nNro. Transaccion: FUTURE01"
            val future = ingestor.ingest(BankSmsRecord(1, body, Instant.ofEpochMilli(3000), 7))
            check(!future.evidenceEligible && !future.newFinancialMovement)
            val current = ingestor.ingest(BankSmsRecord(null, body, Instant.ofEpochMilli(1500), 7))
            check(current.evidenceEligible && current.newFinancialMovement && !current.stored.duplicate)
            check(r.snapshot().receipts.none { it.referenceConflict })
        }
        case("Query completion requires parsed bank SIM account and fresh eligible evidence") { r, _ ->
            val body = "Banco Bandec La consulta de saldo fue completada.\nCuenta;Saldo Contable;Saldo Disponible;Moneda\n0000XXXXXXXX0002; CR 100.00 ; CR 90.00 ;CUP |"
            val query = operation().copy(id = "query", kind = "bandec.balance", specId = "bandec.balance", source = "0000000000000002",
                destination = "", amount = null, providerId = "BANDEC")
            r.putOperation(query); check(r.updateOperationStatus(query.id, OperationStatus.PREPARED, OperationStatus.SUBMITTING, 950))
            val wrongSim = r.ingest(IncomingEvent(EventSource.INBOX, "bad-sim", "PAGOxMOVIL", body, 1000, 8))
            check(!r.completeQuery(query.id, wrongSim.canonicalEventId, 1100))
            val future = r.ingest(IncomingEvent(EventSource.INBOX, "future-query", "PAGOxMOVIL", body, 2000, 7, evidenceEligible = false))
            check(!r.completeQuery(query.id, future.canonicalEventId, 2500))
            val wrongAccount = r.ingest(IncomingEvent(EventSource.INBOX, "wrong-account", "PAGOxMOVIL", body.replace("0002;", "0003;"), 1000, 7))
            check(!r.completeQuery(query.id, wrongAccount.canonicalEventId, 1100))
            val correct = r.ingest(IncomingEvent(EventSource.INBOX, "correct-query", "PAGOxMOVIL", body, 1000, 7))
            val money = query.copy(id = "money-is-not-a-query", kind = "TRANSFER", amount = "90.00")
            r.putOperation(money); check(r.updateOperationStatus(money.id, OperationStatus.PREPARED, OperationStatus.SUBMITTING, 950))
            check(!r.completeQuery(money.id, correct.canonicalEventId, 1100))
            check(r.completeQuery(query.id, correct.canonicalEventId, 1100))
            check(r.snapshot().operations.single { it.id == query.id }.status == OperationStatus.CONFIRMED)
            check(r.snapshot().movements.isEmpty())
        }

        case("Unknown full invoice amount requires paid receipt exact invoice reference SIM and current evidence") { r, _ ->
            val body = "Banco Bandec: El pago de la factura telefonica fue completado.\nNro. Factura Pagada: 00000000000001\nImporte Factura: 100.00 CUP\nMonto Pagado: 90.00 CUP\nNro. Transaccion: PHONE01"
            val operation = operation().copy(id = "invoice", kind = "service.telephone", specId = "service.telephone",
                destination = "00000000000001", amount = null, parameters = mapOf("paymentMode" to "FULL_INVOICE"))
            r.putOperation(operation); check(r.updateOperationStatus(operation.id, OperationStatus.PREPARED, OperationStatus.SUBMITTING, 950))
            val ingestor = SmsIngestor(r, now = { Instant.ofEpochMilli(1500) })
            val bad = listOf(
                body.replace("00000000000001", "00000000000002"),
                body.replace("Monto Pagado: 90.00 CUP\n", ""),
                body.replace("\nNro. Transaccion: PHONE01", ""),
            )
            bad.forEachIndexed { index, text ->
                val receipt = ingestor.ingest(BankSmsRecord(100L + index, text.replace("PHONE01", "BAD$index"), Instant.ofEpochMilli(1000), 7)).stored.receiptId
                check(receipt == null || !r.confirmOperation(operation.id, receipt, 1100, null))
            }
            val wrongSim = ingestor.ingest(BankSmsRecord(200, body.replace("PHONE01", "BADSIM"), Instant.ofEpochMilli(1000), 8)).stored.receiptId!!
            check(!r.confirmOperation(operation.id, wrongSim, 1100, null))
            val future = ingestor.ingest(BankSmsRecord(201, body.replace("PHONE01", "FUTURE"), Instant.ofEpochMilli(2000), 7)).stored.receiptId!!
            check(!r.confirmOperation(operation.id, future, 2500, null))
            val valid = ingestor.ingest(BankSmsRecord(202, body, Instant.ofEpochMilli(1000), 7)).stored.receiptId!!
            val restored = operation.copy(id = "restored-invoice", restored = true, status = OperationStatus.UNCERTAIN)
            r.putOperation(restored)
            check(!r.confirmOperation(restored.id, valid, 1100, null))
            check(r.confirmOperation(operation.id, valid, 1100, null))
            check(r.snapshot().operations.single { it.id == operation.id }.amount == "90.00")
        }
        case("Received-transfer notification eligibility stays deduplicated after reopening Room") { r, databaseName ->
            val body = "El titular del telefono 50000001 le ha realizado una transferencia a la cuenta 0000000000000002 de 10.00 CUP. Nro. Transaccion NOTIFY01"
            val first = SmsIngestor(r, now = { Instant.ofEpochMilli(2000) }).ingest(BankSmsRecord(null, body, Instant.ofEpochMilli(1000), 7, "synthetic-pdu-one"))
            check(first.newFinancialMovement)
            check(r.pendingTransferNotifications().single().id == first.stored.receiptId)
            r.close()
            RoomWalletRepository.open(context, databaseName).use { reopened ->
                val ingestor = SmsIngestor(reopened, now = { Instant.ofEpochMilli(2000) })
                val delivery = BankSmsRecord(77, body, Instant.ofEpochMilli(1100), 7)
                repeat(2) {
                    val duplicate = ingestor.ingest(delivery)
                    check(!duplicate.newFinancialMovement && duplicate.stored.receiptId == first.stored.receiptId)
                }
                val samePdu = ingestor.ingest(BankSmsRecord(null, body, Instant.ofEpochMilli(1200), 7, "synthetic-pdu-one"))
                check(!samePdu.newFinancialMovement && samePdu.stored.receiptId == first.stored.receiptId)
                val bankRedelivery = ingestor.ingest(BankSmsRecord(null, body, Instant.ofEpochMilli(1300), 7, "synthetic-pdu-two"))
                check(!bankRedelivery.newFinancialMovement && bankRedelivery.stored.receiptId == first.stored.receiptId)
                check(reopened.snapshot().movements.size == 1 && reopened.snapshot().events.size == 3)
                check(reopened.pendingTransferNotifications().single().id == first.stored.receiptId)
                reopened.markTransferNotificationDelivered(checkNotNull(first.stored.receiptId))
                check(reopened.pendingTransferNotifications().isEmpty())
            }
        }
        case("Notification outbox excludes inbox future and contradictory transfer evidence") { r, _ ->
            val body = "El titular del telefono 50000001 le ha realizado una transferencia a la cuenta 0000000000000002 de 10.00 CUP. Nro. Transaccion QUEUE01"
            val ingestor = SmsIngestor(r, now = { Instant.ofEpochMilli(1500) })
            ingestor.ingest(BankSmsRecord(1, body.replace("QUEUE01", "INBOX01"), Instant.ofEpochMilli(1000), 7))
            ingestor.ingest(BankSmsRecord(null, body.replace("QUEUE01", "FUTURE01"), Instant.ofEpochMilli(2000), 7, "future-pdu"))
            check(r.pendingTransferNotifications().isEmpty())
            ingestor.ingest(BankSmsRecord(null, body, Instant.ofEpochMilli(1100), 7, "valid-pdu"))
            check(r.pendingTransferNotifications().size == 1)
            ingestor.ingest(BankSmsRecord(null, body.replace("10.00 CUP", "99.00 CUP"), Instant.ofEpochMilli(1200), 7, "contrary-pdu"))
            check(r.pendingTransferNotifications().isEmpty())
            r.database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM notification_outbox").use {
                check(it.moveToFirst() && it.getInt(0) == 0)
            }
        }
        case("Legacy activation codes and provider tokens never reach persisted event or journal text") { r, _ ->
            val bodies = listOf("El código de activación del número 50000001 para agentes debe insertarlo: 123456789",
                "Mi Boleto: Token: SYNTHETIC_PROVIDER_SECRET")
            bodies.forEachIndexed { index, body ->
                val input = IncomingEvent(EventSource.INBOX, "sensitive-$index", "PAGOxMOVIL", body, 1000, 7)
                check(body !in input.toString())
                r.ingest(input)
            }
            check(r.snapshot().events.all { it.body == null && it.bodyWithheld })
            check("123456789" !in String(SnapshotCodec.encode(r.snapshot()), Charsets.UTF_8))
            check("SYNTHETIC_PROVIDER_SECRET" !in String(SnapshotCodec.encode(r.snapshot()), Charsets.UTF_8))
            check(runCatching { r.putOperation(operation().copy(parameters = mapOf("activationCode" to "123456789"))) }.isFailure)
            check("123456789" !in safeArchive("El codigo de activacion es 123456789"))
        }
        case("Legacy HTTP evidence remains readable and restorable without a live HTTP completion API") { r, _ ->
            val evidence = BulevarHttpEvidence("legacy-http", "bulevar.refund_request", 200, "Ok", 1100, "REFUND01")
            val operation = OperationRecord("legacy-http", "bulevar.refund_request", null, -1, "PAYMENT01", "10.00", "CUP", 1000,
                source = "2", status = OperationStatus.CONFIRMED, updatedAt = 1100, providerId = "BULEVAR", profileId = "COMMERCE",
                parameters = mapOf("userId" to "1", "title" to "Devolución", "sourceId" to "2", "externalCode" to "EXTERNAL01"), httpEvidence = evidence)
            val snapshot = WalletSnapshot(operations = listOf(operation))
            check(SnapshotCodec.decode(SnapshotCodec.encode(snapshot)) == snapshot)
            check(r.importSnapshot(snapshot).conflicts.isEmpty())
            check(r.snapshot().operations.single().let { it.httpEvidence == evidence && it.restored && it.status == OperationStatus.CONFIRMED })
            check(r.snapshot().movements.isEmpty() && r.snapshot().receipts.isEmpty())
        }
        case("Card holder and expiry remain distinct from the alias and round trip without rejecting past dates") { r, _ ->
            identity(r)
            val card = CardRecord("card", "registration", "0000000000000001", "Alias de prueba", currency = "CUP",
                holderName = "PERSONA DE PRUEBA", expiry = "01/20")
            r.putCard(card)
            check(r.snapshot().cards.single() == card)
            val decoded = SnapshotCodec.decode(SnapshotCodec.encode(r.snapshot()))
            check(decoded.cards.single().holderName == "PERSONA DE PRUEBA" && decoded.cards.single().expiry == "01/20")
            check(decoded.cards.single().label == "Alias de prueba")
        }
        case("Legacy card backup fields stay null and never use the alias as the printed holder") { r, _ ->
            val legacy = WalletSnapshot(identities = listOf(IdentityRecord("identity", "Perfil")),
                registrations = listOf(registration("registration", 7)),
                cards = listOf(CardRecord("card", "registration", "0000000000000001", "Alias heredado", currency = "CUP")))
            val json = org.json.JSONObject(String(SnapshotCodec.encode(legacy), Charsets.UTF_8))
            json.getJSONArray("cards").getJSONObject(0).apply { remove("holderName"); remove("expiry") }
            val decoded = SnapshotCodec.decode(json.toString().toByteArray(Charsets.UTF_8))
            check(decoded.cards.single().holderName == null && decoded.cards.single().expiry == null)
            check(r.importSnapshot(decoded).conflicts.isEmpty())
            check(r.snapshot().cards.single().let { it.holderName == null && it.expiry == null && it.label == "Alias heredado" })
        }
        case("Invalid holder and expiry metadata reject CRUD and backup restoration before changing existing data") { r, _ ->
            identity(r)
            val original = CardRecord("card", "registration", "0000000000000001", "Alias", currency = "CUP", isDefault = true,
                holderName = "PERSONA DE PRUEBA", expiry = "12/25")
            r.putCard(original)
            val before = r.snapshot()
            val invalid = listOf(
                original.copy(holderName = " PERSONA"), original.copy(holderName = "PERSONA "),
                original.copy(holderName = "PERSONA\nOTRA"), original.copy(holderName = "\u202ePERSONA"),
                original.copy(holderName = "A".repeat(81)), original.copy(holderName = ""),
                original.copy(expiry = "00/29"), original.copy(expiry = "13/29"),
                original.copy(expiry = "1/29"), original.copy(expiry = "01/2029"),
                original.copy(expiry = "01-29"), original.copy(expiry = "٠١/٢٩"),
            )
            invalid.forEach { card ->
                val newCard = card.copy(id = "invalid", number = "0000000000000002")
                check(runCatching { r.putCard(newCard) }.isFailure)
                val backup = WalletSnapshot(identities = listOf(IdentityRecord("must-not-exist", "Perfil")), cards = listOf(newCard))
                check(runCatching { SnapshotCodec.decode(SnapshotCodec.encode(backup)) }.isFailure)
                check(runCatching { r.importSnapshot(backup) }.isFailure)
                check(r.snapshot() == before)
            }
        }
        case("Coupon credential aliases are withheld during ingestion while event provenance and harmless information remain") { r, _ ->
            val sensitive = listOf("Datos del cupon:\nCOUPONVALUE_01", "Estado del cupón.\nDatos\ndel\ncupón: COUPONVALUE_02",
                "Mis cupones de combustible. No.Serie;Saldo actual;Importe pagado;Moneda;ID Banco;ID TM;Fecha;Banco;DatosCupon " +
                    "0000000000001;10;50;CUP;B01;T01;24/09/2026;BANDEC;COUPONVALUE_03")
            sensitive.forEachIndexed { index, body -> r.ingest(IncomingEvent(EventSource.INBOX, "coupon-$index", "PAGOxMOVIL", body, 1000L + index, 7)) }
            val harmless = "Datos del cupón disponibles en la aplicación. No.Serie: 0000000000001, Saldo actual: 10.00 CUP"
            r.ingest(IncomingEvent(EventSource.INBOX, "coupon-info", "PAGOxMOVIL", harmless, 1100, 7))
            val stored = r.snapshot()
            check(stored.events.filter { it.sourceId != "coupon-info" }.all { it.body == null && it.bodyWithheld && it.sender == "PAGOxMOVIL" && it.subscriptionId == 7 })
            check(stored.events.single { it.sourceId == "coupon-info" }.let { it.body == harmless && !it.bodyWithheld })
            check("COUPONVALUE_" !in String(SnapshotCodec.encode(stored), Charsets.UTF_8))
            check(runCatching { r.putOperation(operation().copy(parameters = mapOf("DatosCupon" to "COUPONVALUE_04"))) }.isFailure)
        }
        case("Restoring old coupon events and nested archives strips credentials without discarding metadata") { r, _ ->
            val body = "Datos del cupon: COUPONVALUE_01"
            val archive = """{"cupones":[{"serie":"0000000000001","saldo":"10.00","DatosCupon":"COUPONVALUE_02"}]}"""
            val input = WalletSnapshot(events = listOf(EventRecord("coupon-event", EventSource.INBOX, "1", "PAGOxMOVIL", body, 1000, 7, "coupon-event")),
                legacyMetadata = listOf(LegacyMetadataRecord("coupon-fixture", archive)))
            check(r.importSnapshot(input).conflicts.isEmpty())
            val stored = r.snapshot()
            check(stored.events.single().let { it.body == null && it.bodyWithheld && it.receivedAt == 1000L && it.subscriptionId == 7 })
            val row = org.json.JSONObject(stored.legacyMetadata.single().safeValue).getJSONArray("cupones").getJSONObject(0)
            check(!row.has("DatosCupon") && row.getString("serie") == "0000000000001" && row.getString("saldo") == "10.00")
            check("COUPONVALUE_" !in String(SnapshotCodec.encode(stored), Charsets.UTF_8))
        }
        return passed + RoomMigrationChecks.run(context, onPassed) + BankHistoryDataChecks.run(context, onPassed) + ServiceReceiptDataChecks.run(context, onPassed) + FuelDataChecks.run(context, onPassed) +
            dev.duardo.neotransfer.OperationExecutorChecks.run(context, onPassed)
    }

    private fun identity(r: WalletRepository) {
        r.putIdentity(IdentityRecord("identity", "Perfil sintético"))
        r.putRegistration(registration("registration", 7))
    }
    private fun registration(id: String, subscription: Int) = RegistrationRecord(id, "identity", "BANDEC", "PERSONAL", "02", subscription, null, "BANDEC")
    private fun operation() = OperationRecord("operation", "TRANSFER", "02", 7, "0000000000000002", "10.00", "CUP", 900)
    private fun receipt() = ReceiptRecord("", "", "02", 7, "SENT", "ref-01", "10.00", "CUP", "0000000000000002")
    private fun event(source: EventSource, sourceId: String?, at: Long) = IncomingEvent(source, sourceId,
        "PAGOxMOVIL", "synthetic financial receipt", at, 7, receipt())
    private fun legacyFixture(): Map<String, Any> = mapOf(
        "bank" to "BANDEC", "subscription" to 7,
        "pending" to """{"id":"pending-id","kind":"QR","bank":"BANDEC","destination":"fixture","amount":"10.00","currency":"CUP","source":"0000000000000001","phone":"50000001","subscription":7,"started":1000,"refresh":true}""",
        "uncertain" to """[{"id":"uncertain-id","bank":"BANDEC","subscription":7,"destination":"0000000000000002","amount":"10.00","currency":"CUP","started":500}]""",
        "receipts" to setOf("BANDEC:REF-01"),
        "recipients" to """[{"name":"Persona sintética","card":"0000000000000002","phone":"50000001"}]""",
        "refresh" to """{"bank":"BANDEC","subscription":7,"due":1234}""",
        "balance_BANDEC_7" to """{"at":1000,"accounts":[{"account":null,"currency":"CUP","ledger":null,"available":"420.00","label":"Ahorro"}]}""",
    )
}
