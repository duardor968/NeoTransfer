package dev.duardo.neotransfer.data

import android.content.Context
import dev.duardo.neotransfer.core.BankMessage
import dev.duardo.neotransfer.platform.BankSmsRecord
import org.json.JSONObject
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
            var bodyFailure: Throwable? = null
            try { body(repository, databaseName); passed++; onPassed(name) }
            catch (failure: Throwable) { bodyFailure = failure; throw failure }
            finally {
                val cleanupFailure = runCatching {
                    repository.close()
                    if (context.getDatabasePath(databaseName).exists()) check(context.deleteDatabase(databaseName))
                }.exceptionOrNull()
                if (cleanupFailure != null) {
                    val original = bodyFailure ?: throw cleanupFailure
                    original.addSuppressed(cleanupFailure)
                }
            }
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

        case("Confirmed transfer applies its attributed remaining balance and consumes refresh") { r, _ ->
            transferBalanceFixture(r)
            val original = r.snapshot().balances.single()
            val receiptId = transferReceipt(r)
            check(r.confirmOperation("operation", receiptId, 1_200_000, RefreshRequest("02", 7, 1_200_000)))
            val state = r.snapshot()
            check(state.operations.single().refreshTaken && state.refresh == null)
            check(state.balances.single() == original.copy(at = 1_100_000, sentAt = 1_050_000,
                available = "90.00", ledger = null))
            check(state.receipts.single().let { it.amount == "10.00" && it.account == null })
            check(state.events.single().body?.contains("Saldo restante: CR 90.00 CUP") == true)
        }

        case("Remaining balance follows SMSC order in both delivery orders") { r, _ ->
            transferBalanceFixture(r)
            val receiptId = transferReceipt(r, receivedAt = 1_120_000)
            val newer = balanceSms(1_130_000, 1_060_000, "80.00", "0000000000000001")
            SmsIngestor(r, now = { Instant.ofEpochMilli(1_300_000) }).ingest(newer)
            check(r.confirmOperation("operation", receiptId, 1_200_000, null))
            check(r.snapshot().balances.let { rows -> rows.size == 2 && rows.all {
                it.available == "80.00" && it.sentAt == 1_060_000L
            } })
            check(r.snapshot().operations.single().refreshTaken)
        }

        case("Contradictory balance at the same SMSC time keeps one refresh pending") { r, _ ->
            transferBalanceFixture(r)
            SmsIngestor(r, now = { Instant.ofEpochMilli(1_300_000) }).ingest(
                balanceSms(1_090_000, 1_050_000, "80.00"))
            val receiptId = transferReceipt(r)
            check(r.confirmOperation("operation", receiptId, 1_200_000, RefreshRequest("02", 7, 1_200_000)))
            val state = r.snapshot()
            check(state.operations.single().let { it.status == OperationStatus.CONFIRMED && !it.refreshTaken })
            check(state.balances.single().let { it.available == "80.00" && it.sentAt == 1_050_000L })
            check(state.refresh == RefreshRequest("02", 7, 1_200_000))
        }

        case("Later balance SMS replaces already applied transfer balance") { r, _ ->
            transferBalanceFixture(r)
            val receiptId = transferReceipt(r)
            check(r.confirmOperation("operation", receiptId, 1_200_000, null))
            SmsIngestor(r, now = { Instant.ofEpochMilli(1_300_000) }).ingest(
                balanceSms(1_130_000, 1_060_000, "80.00", "0000000000000001"))
            check(r.snapshot().balances.let { rows -> rows.size == 2 && rows.all {
                it.available == "80.00" && it.sentAt == 1_060_000L
            } })
        }

        case("Late older full account query cannot replace a newer masked transfer balance") { r, _ ->
            transferBalanceFixture(r)
            check(r.confirmOperation("operation", transferReceipt(r), 1_200_000, null))
            SmsIngestor(r, now = { Instant.ofEpochMilli(1_300_000) }).ingest(
                balanceSms(1_130_000, 1_040_000, "999.00", "0000000000000001"))
            check(r.snapshot().balances.single().let { it.available == "90.00" && it.sentAt == 1_050_000L })
        }

        case("Newer remaining synchronizes old full and masked IDs without touching another card") { r, _ ->
            transferBalanceFixture(r, extraCard = true)
            r.upsertBalances(listOf(BalanceRecord("other-balance", "02", 7, 1_140_000,
                "0000000000000003", "50.00", "CUP", registrationId = "registration", cardId = "other", sentAt = 1_040_000)))
            SmsIngestor(r, now = { Instant.ofEpochMilli(1_300_000) }).ingest(
                balanceSms(1_140_000, 1_040_000, "100.00", "0000000000000001"))
            check(r.confirmOperation("operation", transferReceipt(r, receivedAt = 1_120_000), 1_200_000, null))
            val rows = r.snapshot().balances
            check(rows.filter { it.cardId == "card" }.let { it.size == 2 && it.all { row ->
                row.available == "90.00" && row.at == 1_120_000L && row.sentAt == 1_050_000L
            } })
            check(rows.single { it.cardId == "other" }.let { it.available == "50.00" && it.at == 1_140_000L })
        }

        case("Missing remaining balance keeps the refresh available") { r, _ ->
            transferBalanceFixture(r)
            val receiptId = transferReceipt(r, remaining = null)
            check(r.confirmOperation("operation", receiptId, 1_200_000, RefreshRequest("02", 7, 1_200_000)))
            check(!r.snapshot().operations.single().refreshTaken && r.snapshot().balances.single().available == "100.00")
            check(r.snapshot().refresh != null)
        }

        case("One saved card does not prove the bank default source") { r, _ ->
            transferBalanceFixture(r, source = "0000")
            check(r.confirmOperation("operation", transferReceipt(r), 1_200_000, RefreshRequest("02", 7, 1_200_000)))
            check(!r.snapshot().operations.single().refreshTaken && r.snapshot().balances.single().available == "100.00")
            check(r.snapshot().refresh != null)
        }

        case("Exact explicit source creates a stable balance row without prior balance") { r, _ ->
            transferBalanceFixture(r, includeBalance = false)
            check(r.confirmOperation("operation", transferReceipt(r), 1_200_000, null))
            val created = r.snapshot().balances.single()
            check(created.id == "02:7:registration:card:0000000000000001:CUP" &&
                created.account == "0000000000000001" && created.cardId == "card" && created.accountId == null &&
                created.available == "90.00" && created.ledger == null && created.sentAt == 1_050_000L)
            check(r.snapshot().operations.single().refreshTaken)
            SmsIngestor(r, now = { Instant.ofEpochMilli(1_300_000) }).ingest(
                balanceSms(1_130_000, 1_060_000, "80.00", "0000000000000001"))
            check(r.snapshot().balances.single().let { it.id == created.id && it.available == "80.00" })
        }

        case("Masked source does not identify an explicit product") { r, _ ->
            transferBalanceFixture(r, source = "0000XXXXXXXX0001")
            check(r.confirmOperation("operation", transferReceipt(r), 1_200_000, null))
            check(!r.snapshot().operations.single().refreshTaken && r.snapshot().balances.single().available == "100.00")
        }

        case("Default source without a unique product never consumes remaining") { r, _ ->
            transferBalanceFixture(r, source = "0000", extraCard = true)
            check(r.confirmOperation("operation", transferReceipt(r), 1_200_000, null))
            check(!r.snapshot().operations.single().refreshTaken && r.snapshot().balances.single().available == "100.00")
        }

        case("Default source without a known product never consumes remaining") { r, _ ->
            identity(r)
            r.putOperation(operation().copy(registrationId = "registration", source = "0000", startedAt = 1_000_000))
            check(r.updateOperationStatus("operation", OperationStatus.PREPARED, OperationStatus.SUBMITTING, 1_000_001))
            check(r.confirmOperation("operation", transferReceipt(r), 1_200_000, null))
            check(!r.snapshot().operations.single().refreshTaken && r.snapshot().balances.isEmpty())
        }

        case("Ambiguous source masks do not attribute a transfer balance") { r, _ ->
            transferBalanceFixture(r, extraCard = true, secondNumber = "0000111100000001")
            check(r.confirmOperation("operation", transferReceipt(r), 1_200_000, null))
            check(!r.snapshot().operations.single().refreshTaken && r.snapshot().balances.single().available == "100.00")
        }

        case("Different remaining currency does not consume refresh") { r, _ ->
            transferBalanceFixture(r)
            check(r.confirmOperation("operation", transferReceipt(r, remaining = "CR 90.00 USD"), 1_200_000, null))
            check(!r.snapshot().operations.single().refreshTaken && r.snapshot().balances.single().available == "100.00")
        }

        case("Another bank balance cannot receive the transfer remainder") { r, _ ->
            transferBalanceFixture(r, balanceBankCode = "01")
            check(r.confirmOperation("operation", transferReceipt(r), 1_200_000, null))
            check(r.snapshot().operations.single().refreshTaken)
            check(r.snapshot().balances.single { it.bankCode == "01" }.available == "100.00")
            check(r.snapshot().balances.single { it.bankCode == "02" }.available == "90.00")
        }

        case("Another SIM balance cannot receive the transfer remainder") { r, _ ->
            transferBalanceFixture(r, balanceSubscriptionId = 8)
            check(r.confirmOperation("operation", transferReceipt(r), 1_200_000, null))
            check(r.snapshot().operations.single().refreshTaken)
            check(r.snapshot().balances.single { it.subscriptionId == 8 }.available == "100.00")
            check(r.snapshot().balances.single { it.subscriptionId == 7 }.available == "90.00")
        }

        case("Restored or ineligible transfer evidence cannot apply remaining") { r, _ ->
            transferBalanceFixture(r)
            val restored = operation().copy(id = "restored", registrationId = "registration", source = "0000000000000001",
                startedAt = 1_000_000, status = OperationStatus.UNCERTAIN, updatedAt = 1_000_001, restored = true)
            r.putOperation(restored)
            val receiptId = transferReceipt(r)
            check(!r.confirmOperation(restored.id, receiptId, 1_200_000, null))
            check(r.snapshot().balances.single().available == "100.00")
            r.dao.events().forEach { row -> r.dao.update(row.copy(value = row.value.copy(evidenceEligible = false))) }
            check(!r.confirmOperation("operation", receiptId, 1_200_000, null))
            check(r.snapshot().balances.single().available == "100.00" && !r.snapshot().operations.single { it.id == "operation" }.refreshTaken)
        }

        case("SMSC order keeps a delayed older balance from replacing the latest balance") { r, _ ->
            val body = "Banco Bandec La consulta de saldo fue completada.\nCuenta;Saldo Contable;Saldo Disponible;Moneda\n0000XXXXXXXX0002; CR 100.00 ; CR 90.00 ;CUP |"
            val ingestor = SmsIngestor(r, now = { Instant.ofEpochMilli(1_200_000) })
            val newest = BankSmsRecord(1, body, Instant.ofEpochMilli(1_060_000), 7,
                sentAt = Instant.ofEpochMilli(1_050_000))
            val older = BankSmsRecord(2, body.replace("90.00", "10.00"), Instant.ofEpochMilli(1_163_000), 7,
                sentAt = Instant.ofEpochMilli(1_001_000))
            ingestor.ingest(newest)
            check(r.snapshot().balances.single().available == "90.00")
            ingestor.ingest(older)
            check(r.snapshot().balances.single().let { it.available == "90.00" && it.sentAt == 1_050_000L })
            check(r.snapshot().events.size == 2)
        }

        case("Missing and future SMSC dates remain observations without completing or replacing a balance") { r, _ ->
            val body = "Banco Bandec La consulta de saldo fue completada.\nCuenta;Saldo Contable;Saldo Disponible;Moneda\n0000XXXXXXXX0002; CR 100.00 ; CR 90.00 ;CUP |"
            val ingestor = SmsIngestor(r, now = { Instant.ofEpochMilli(1_200_000) })
            val query = operation().copy(id = "query-date", kind = "bandec.balance", specId = "bandec.balance",
                destination = "", amount = null, providerId = "BANDEC", startedAt = 1_000_000)
            r.putOperation(query)
            val missing = ingestor.ingest(BankSmsRecord(1, body, Instant.ofEpochMilli(1_163_000), 7))
            val future = ingestor.ingest(BankSmsRecord(2, body, Instant.ofEpochMilli(1_164_000), 7,
                sentAt = Instant.ofEpochMilli(1_165_000)))
            check(r.snapshot().events.size == 2 && r.snapshot().balances.isEmpty())
            check(!r.completeQuery(query.id, missing.stored.eventId, 1_200_000))
            check(!r.completeQuery(query.id, future.stored.eventId, 1_200_000))
        }

        case("A payment received 163 seconds late confirms an expired wait only with valid SMSC evidence") { r, _ ->
            val request = operation().copy(startedAt = 1_000_000, updatedAt = 1_000_000)
            r.putOperation(request)
            check(r.updateOperationStatus(request.id, OperationStatus.PREPARED, OperationStatus.SUBMITTING, 1_000_001))
            check(r.expireWaitingOperation(request.id, 1_030_000))
            check(r.snapshot().operations.single().let {
                it.status == OperationStatus.UNCERTAIN && !it.reviewRequired && it.timeoutAt == 1_030_000L
            })
            val body = "Banco Bandec: La Transferencia fue completada.\nBeneficiario: 0000XXXXXXXX0002\nMonto: 10.00 CUP\nNro. Transaccion: LATE163"
            val result = SmsIngestor(r, now = { Instant.ofEpochMilli(1_164_000) }).ingest(
                BankSmsRecord(1, body, Instant.ofEpochMilli(1_163_000), 7, sentAt = Instant.ofEpochMilli(1_001_000)))
            check(r.confirmOperation(request.id, checkNotNull(result.stored.receiptId), 1_164_000, null))
            check(r.snapshot().operations.single().let { it.status == OperationStatus.CONFIRMED && it.timeoutAt == 1_030_000L })
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
            val second = r.ingest(event(EventSource.INBOX, "sms-1", 2000).copy(sentAt = 1000))
            check(second.duplicate && first.canonicalEventId == second.canonicalEventId)
            check(r.snapshot().events.map { it.receivedAt }.toSet() == setOf(1000L, 2000L))
            check(r.snapshot().receipts.size == 1 && r.snapshot().movements.size == 1)
            check(r.ingest(event(EventSource.INBOX, "sms-1", 2000).copy(sentAt = 1000)).duplicate)
            check(r.snapshot().events.size == 2)
        }

        case("Inbox reread enriches the same delivery and compatible broadcast without changing a restored event") { r, _ ->
            val first = IncomingEvent(EventSource.BROADCAST, "pdu", "PAGOxMOVIL", "Banco Bandec autenticado", 3000, 7)
            val broadcast = r.ingest(first)
            val inbox = r.ingest(first.copy(source = EventSource.INBOX, sourceId = "42", receivedAt = 3100, sentAt = 1900))
            check(inbox.duplicate && inbox.canonicalEventId == broadcast.eventId)
            check(r.snapshot().events.all { it.sentAt == 1900L })
            val repeat = r.ingest(first.copy(source = EventSource.INBOX, sourceId = "42", receivedAt = 3100, sentAt = 1900))
            check(repeat.duplicate && r.snapshot().events.size == 2)
            r.ingest(first.copy(source = EventSource.INBOX, sourceId = "42", receivedAt = 3100, sentAt = 800))
            check(r.snapshot().events.none { it.evidenceEligible })
            val archived = EventRecord("archived", EventSource.INBOX, "old", "PAGOxMOVIL", "Archivo", 1000, 7, "archived")
            check(r.importSnapshot(WalletSnapshot(events = listOf(archived))).conflicts.isEmpty())
            r.ingest(IncomingEvent(EventSource.INBOX, "old", "PAGOxMOVIL", "Archivo", 1000, 7, sentAt = 900))
            check(r.snapshot().events.single { it.id == "archived" }.let { it.sentAt == null && !it.evidenceEligible && it.source == EventSource.LEGACY })
        }

        case("Conflicting broadcast reread quarantines every linked delivery for query completion") { r, _ ->
            val body = "Banco Bandec La consulta de saldo fue completada.\nCuenta;Saldo Contable;Saldo Disponible;Moneda\n0000XXXXXXXX0002; CR 100.00 ; CR 90.00 ;CUP |"
            val query = operation().copy(id = "linked-query", kind = "bandec.balance", specId = "bandec.balance",
                destination = "", amount = null, providerId = "BANDEC")
            r.putOperation(query)
            check(r.updateOperationStatus(query.id, OperationStatus.PREPARED, OperationStatus.SUBMITTING, 900))
            val broadcast = r.ingest(IncomingEvent(EventSource.BROADCAST, "linked-pdu", "PAGOxMOVIL", body, 1000, 7,
                sentAt = 1000))
            val inbox = r.ingest(IncomingEvent(EventSource.INBOX, "linked-inbox", "PAGOxMOVIL", body, 1100, 7,
                sentAt = 1000))
            check(inbox.duplicate && inbox.canonicalEventId == broadcast.eventId)
            r.ingest(IncomingEvent(EventSource.BROADCAST, "linked-pdu", "PAGOxMOVIL", body, 1000, 7,
                sentAt = 800))
            check(r.snapshot().events.filter { it.canonicalEventId == broadcast.eventId }.all { !it.evidenceEligible })
            check(!r.completeQuery(query.id, inbox.eventId, 1200))
            check(!r.completeQuery(query.id, broadcast.eventId, 1200))
            val independent = r.ingest(IncomingEvent(EventSource.INBOX, "independent", "PAGOxMOVIL", body, 1200, 7,
                sentAt = 1100))
            check(r.completeQuery(query.id, independent.eventId, 1200))
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
            val equivalent = event(EventSource.INBOX, "normalized", 2000).copy(body = "Equivalent synthetic receipt with different spacing", sentAt = 1000,
                receipt = receipt().copy(reference = " REF-01. ", amount = "10.0"))
            val second = r.ingest(equivalent)
            check(second.duplicate && first.receiptId == second.receiptId)
            check(r.snapshot().events.size == 2 && r.snapshot().movements.size == 1)
        }

        case("Same reference and facts redelivered a minute later remain one movement") { r, _ ->
            val first = r.ingest(event(EventSource.BROADCAST, "first-pdu", 1000))
            val repeated = r.ingest(event(EventSource.BROADCAST, "second-pdu", 61_000))
            check(repeated.duplicate && repeated.receiptId == first.receiptId)
            check(r.snapshot().movements.size == 1)
            val differentFacts = r.ingest(event(EventSource.BROADCAST, "third-pdu", 62_000).copy(
                receipt = receipt().copy(amount = "11.00")))
            check(!differentFacts.duplicate && differentFacts.receiptId != first.receiptId)
            check(r.snapshot().movements.size == 2 && r.snapshot().receipts.all { it.referenceConflict })
            val otherSim = r.ingest(event(EventSource.BROADCAST, "other-sim-pdu", 63_000).copy(subscriptionId = 8,
                receipt = receipt().copy(subscriptionId = 8)))
            check(!otherSim.duplicate && r.snapshot().movements.size == 3)
        }

        case("Contradictory SMSC on a receipt quarantines all three delivery aliases") { r, _ ->
            val request = operation()
            r.putOperation(request)
            check(r.updateOperationStatus(request.id, OperationStatus.PREPARED, OperationStatus.SUBMITTING, 900))
            val broadcast = r.ingest(event(EventSource.BROADCAST, "receipt-pdu", 1000))
            val inbox = r.ingest(event(EventSource.INBOX, "receipt-inbox", 1100).copy(sentAt = 1000))
            val redelivery = r.ingest(event(EventSource.BROADCAST, "receipt-redelivery", 1200).copy(sentAt = 1000))
            check(inbox.duplicate && redelivery.duplicate &&
                listOf(inbox, redelivery).all { it.canonicalEventId == broadcast.eventId })
            r.ingest(event(EventSource.BROADCAST, "receipt-pdu", 1000).copy(sentAt = 800))
            check(r.snapshot().events.filter { it.canonicalEventId == broadcast.eventId }.let {
                it.size == 3 && it.all { alias -> !alias.evidenceEligible }
            })
            check(!r.confirmOperation(request.id, checkNotNull(broadcast.receiptId), 1300, null))
            val independent = r.ingest(event(EventSource.BROADCAST, "independent-receipt", 1300).copy(
                receipt = receipt().copy(reference = "REF-02")))
            check(r.confirmOperation(request.id, checkNotNull(independent.receiptId), 1300, null))
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

        case("Distinct beneficiaries disambiguate equal amounts but shared masks do not") { r, _ ->
            val first = operation().copy(id = "first-beneficiary", destination = "0000000000000002")
            val second = operation().copy(id = "second-beneficiary", destination = "0000000000000003")
            listOf(first, second).forEach {
                r.putOperation(it)
                check(r.updateOperationStatus(it.id, OperationStatus.PREPARED, OperationStatus.SUBMITTING, 900))
            }
            val clear = r.ingest(event(EventSource.BROADCAST, "clear-pdu", 1000))
            check(r.confirmOperation(first.id, checkNotNull(clear.receiptId), 1100, null))
            check(r.snapshot().operations.single { it.id == second.id }.status == OperationStatus.SUBMITTING)
            val ambiguous = operation().copy(id = "ambiguous-beneficiary", destination = "0000111100000003")
            r.putOperation(ambiguous)
            check(r.updateOperationStatus(ambiguous.id, OperationStatus.PREPARED, OperationStatus.SUBMITTING, 900))
            val masked = r.ingest(event(EventSource.BROADCAST, "masked-pdu", 2000).copy(
                receipt = receipt().copy(reference = "MASKED02", party = "0000XXXXXXXX0003")))
            check(!r.confirmOperation(second.id, checkNotNull(masked.receiptId), 2100, null))
            check(!r.confirmOperation(ambiguous.id, checkNotNull(masked.receiptId), 2100, null))
            check(r.snapshot().usedReferences.size == 1)
        }

        case("Identical pending transfers and a mismatched beneficiary cannot claim a receipt") { r, _ ->
            val first = operation().copy(id = "identical-one")
            val second = first.copy(id = "identical-two")
            listOf(first, second).forEach {
                r.putOperation(it)
                check(r.updateOperationStatus(it.id, OperationStatus.PREPARED, OperationStatus.SUBMITTING, 900))
            }
            val shared = r.ingest(event(EventSource.BROADCAST, "identical-pdu", 1000))
            check(!r.confirmOperation(first.id, checkNotNull(shared.receiptId), 1100, null))
            check(!r.confirmOperation(second.id, checkNotNull(shared.receiptId), 1100, null))
            check(r.snapshot().usedReferences.isEmpty())
        }

        case("A unique amount with the wrong beneficiary cannot confirm a transfer") { r, _ ->
            val request = operation()
            r.putOperation(request)
            check(r.updateOperationStatus(request.id, OperationStatus.PREPARED, OperationStatus.SUBMITTING, 900))
            val wrong = r.ingest(event(EventSource.BROADCAST, "wrong-beneficiary-pdu", 1000).copy(
                receipt = receipt().copy(party = "0000000000000003")))
            check(!r.confirmOperation(request.id, checkNotNull(wrong.receiptId), 1100, null))
            check(r.snapshot().operations.single().status == OperationStatus.SUBMITTING &&
                r.snapshot().usedReferences.isEmpty())
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

        case("Backup preserves SMSC and timeout provenance while old snapshots decode as unknown") { _, _ ->
            val current = WalletSnapshot(
                operations = listOf(operation().copy(status = OperationStatus.UNCERTAIN, updatedAt = 30_900,
                    timeoutAt = 30_900)),
                events = listOf(EventRecord("sms", EventSource.INBOX, "1", "PAGOxMOVIL", "Consulta", 163_000, 7,
                    "sms", sentAt = 1_000)),
                balances = listOf(BalanceRecord("balance", "02", 7, 163_000, null, "90.00", "CUP", sentAt = 1_000)),
            )
            check(SnapshotCodec.decode(SnapshotCodec.encode(current)) == current)
            val old = JSONObject(String(SnapshotCodec.encode(current), Charsets.UTF_8))
            old.getJSONArray("operations").getJSONObject(0).remove("timeoutAt")
            old.getJSONArray("events").getJSONObject(0).remove("sentAt")
            old.getJSONArray("balances").getJSONObject(0).remove("sentAt")
            val decoded = SnapshotCodec.decode(old.toString().toByteArray())
            check(decoded.operations.single().timeoutAt == null && decoded.events.single().sentAt == null &&
                decoded.balances.single().sentAt == null)
        }

        case("Wallet restore detaches foreign phone bindings and preserves historical delivery provenance") { r, _ ->
            val source = WalletSnapshot(
                identities = listOf(IdentityRecord("identity", "Persona sintética")),
                registrations = listOf(registration("registration", 77).copy(linePhone = "50000001")),
                operations = listOf(operation().copy(registrationId = "registration", subscriptionId = 77)),
                events = listOf(EventRecord("event", EventSource.INBOX, "123", "PAGOxMOVIL", "synthetic", 1000, 77, "event",
                    sentAt = 900)),
                balances = listOf(BalanceRecord("balance", "02", 77, 1000, null, "10.00", "CUP", sentAt = 900)),
                settings = WalletSettings("02", 77, "registration"), refresh = RefreshRequest("02", 77, 1001),
            )
            val result = r.importSnapshot(SnapshotCodec.decode(SnapshotCodec.encode(source)))
            check(result.conflicts.isEmpty() && result.registrationsToReassociate == listOf("registration"))
            val restored = r.snapshot()
            check(restored.registrations.single().let {
                it.subscriptionId == null && it.linePhone == null && !it.enabled && it.previousSubscriptionId == 77 && it.previousLinePhone == "50000001"
            })
            check(restored.operations.single().let { it.status == OperationStatus.UNCERTAIN && it.restored && it.reviewRequired })
            check(restored.events.single().let { it.source == EventSource.LEGACY && it.sourceId == null &&
                it.originalSource == EventSource.INBOX && it.originalSourceId == "123" && it.sentAt == 900L && !it.evidenceEligible })
            check(restored.balances.single().sentAt == 900L)
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
        case("BPA flat balance updates the explicitly queried card through shared ingestion") { r, _ ->
            r.putIdentity(IdentityRecord("identity", "Prueba"))
            r.putRegistration(RegistrationRecord("bpa", "identity", "BPA", "PERSONAL", "01", 7, null, "BPA"))
            r.putCard(CardRecord("bpa-card", "bpa", "0000000000000001", "Tarjeta", currency = "CUP"))
            r.putOperation(OperationRecord("bpa-query", "bpa.balance", "01", 7, "", null, "CUP", 1_000_000,
                registrationId = "bpa", source = "0000000000000001", providerId = "BPA",
                status = OperationStatus.AWAITING_CONFIRMATION, parameters = mapOf("sourceCurrency" to "CUP")))
            val body = "Banco Popular de Ahorro:  La consulta de saldo fue completada. \n\n Saldo Disponible: CR 42.00 CUP"
            val ingestor = SmsIngestor(r, now = { Instant.ofEpochMilli(1_020_000) })
            val record = BankSmsRecord(90, body, Instant.ofEpochMilli(1_015_000), 7, sentAt = Instant.ofEpochMilli(1_010_000))
            val result = ingestor.ingest(record)
            val balance = r.snapshot().balances.singleOrNull { it.cardId == "bpa-card" }
            check(balance?.available == "42.00") { "BPA response parsed but not linked to the queried card" }
            check(r.snapshot().operations.single().status == OperationStatus.CONFIRMED)
            check(result.completedQueryIds == setOf("bpa-query"))
            check(ingestor.ingest(record).completedQueryIds.isEmpty())
            check(r.snapshot().movements.isEmpty())
        }
        case("Repeated BPA reads preserve ambiguity before a different source and a delayed second reply") { r, _ ->
            bpaBalanceFixture(r)
            val first = r.snapshot().operations.single()
            r.putOperation(first.copy(id = "bpa-second", startedAt = 1_001_000, updatedAt = 1_001_000))
            val ingestor = SmsIngestor(r, now = { Instant.ofEpochMilli(1_100_000) })
            ingestor.ingest(bpaFlatSms(1, 1_015_000, 1_010_000, "42.00"))
            check(r.snapshot().balances.single { it.cardId == "bpa-card" }.available == "42.00")
            check(r.snapshot().operations.none { it.status == OperationStatus.CONFIRMED })
            r.putCard(CardRecord("other-card", "bpa", "0000000000000002", "Otra", currency = "CUP"))
            r.putOperation(first.copy(id = "other-query", source = "0000000000000002", startedAt = 1_020_000, updatedAt = 1_020_000))
            ingestor.ingest(bpaFlatSms(2, 1_040_000, 1_030_000, "37.00"))
            check(r.snapshot().balances.none { it.cardId == "other-card" })
            check(r.snapshot().operations.none { it.status == OperationStatus.CONFIRMED })
        }
        case("BPA flat evidence can update after timeout and repository recreation without executing anything") { r, name ->
            bpaBalanceFixture(r)
            check(r.expireWaitingOperation("bpa-query", 1_030_000))
            r.close()
            RoomWalletRepository.open(context, name).use { reopened ->
                SmsIngestor(reopened, now = { Instant.ofEpochMilli(1_200_000) })
                    .ingest(bpaFlatSms(1, 1_150_000, 1_010_000, "42.00"))
                check(reopened.snapshot().balances.single { it.cardId == "bpa-card" }.available == "42.00")
                check(reopened.snapshot().operations.single().let {
                    it.status == OperationStatus.CONFIRMED && it.timeoutAt == 1_030_000L
                })
                check(reopened.snapshot().movements.isEmpty())
            }
        }
        case("Equal-SMSC conflicting BPA balance cannot complete a query or replace an existing identified balance") { r, _ ->
            bpaBalanceFixture(r)
            r.upsertBalances(listOf(BalanceRecord("previous", "01", 7, 1_012_000, null, "50.00", "CUP",
                registrationId = "bpa", cardId = "bpa-card", sentAt = 1_010_000)))
            val result = SmsIngestor(r, now = { Instant.ofEpochMilli(1_100_000) })
                .ingest(bpaFlatSms(1, 1_015_000, 1_010_000, "42.00"))
            check(result.completedQueryIds.isEmpty())
            check(r.snapshot().operations.single().status == OperationStatus.AWAITING_CONFIRMATION)
            check(r.snapshot().balances.single { it.cardId == "bpa-card" }.available == "50.00")
        }
        case("BPA broadcast inbox replay cannot confirm another card queried later within the same SMSC second") { r, _ ->
            bpaBalanceFixture(r)
            val first = r.snapshot().operations.single()
            val ingestor = SmsIngestor(r, now = { Instant.ofEpochMilli(1_100_000) })
            val broadcast = bpaFlatSms(1, 1_010_100, 1_010_000, "42.00")
            ingestor.ingest(broadcast, EventSource.BROADCAST)
            check(r.snapshot().operations.single().status == OperationStatus.CONFIRMED)
            r.putCard(CardRecord("other-card", "bpa", "0000000000000002", "Otra", currency = "CUP"))
            r.putOperation(first.copy(id = "other-query", source = "0000000000000002", startedAt = 1_010_200, updatedAt = 1_010_200))
            val replay = ingestor.ingest(bpaFlatSms(2, 1_010_300, 1_010_000, "42.00"))
            check(replay.stored.duplicate && replay.completedQueryIds.isEmpty())
            check(!r.completeQuery("other-query", replay.stored.eventId, 1_100_000))
            check(r.snapshot().balances.none { it.cardId == "other-card" })
            check(r.snapshot().operations.single { it.id == "other-query" }.status == OperationStatus.AWAITING_CONFIRMATION)
        }
        case("BPA reread may supply missing SMSC to an earlier broadcast for the original query") { r, _ ->
            bpaBalanceFixture(r)
            val ingestor = SmsIngestor(r, now = { Instant.ofEpochMilli(1_100_000) })
            val record = bpaFlatSms(1, 1_015_000, 1_010_000, "42.00")
            ingestor.ingest(BankSmsRecord(null, record.body, record.receivedAt, 7), EventSource.BROADCAST)
            check(r.snapshot().operations.single().status == OperationStatus.AWAITING_CONFIRMATION)
            val enriched = ingestor.ingest(record)
            check(enriched.stored.duplicate && enriched.completedQueryIds == setOf("bpa-query"))
            check(r.snapshot().balances.single { it.cardId == "bpa-card" }.available == "42.00")
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
            val correct = r.ingest(IncomingEvent(EventSource.INBOX, "correct-query", "PAGOxMOVIL", body, 1000, 7, sentAt = 1000))
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
            val valid = ingestor.ingest(BankSmsRecord(202, body, Instant.ofEpochMilli(1000), 7,
                sentAt = Instant.ofEpochMilli(1000))).stored.receiptId!!
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
    private fun bpaBalanceFixture(r: RoomWalletRepository) {
        r.putIdentity(IdentityRecord("identity", "Prueba"))
        r.putRegistration(RegistrationRecord("bpa", "identity", "BPA", "PERSONAL", "01", 7, null, "BPA"))
        r.putCard(CardRecord("bpa-card", "bpa", "0000000000000001", "Propia", currency = "CUP"))
        r.putOperation(OperationRecord("bpa-query", "bpa.balance", "01", 7, "", null, "CUP", 1_000_000,
            registrationId = "bpa", source = "0000000000000001", providerId = "BPA",
            status = OperationStatus.AWAITING_CONFIRMATION, parameters = mapOf("sourceCurrency" to "CUP")))
    }
    private fun bpaFlatSms(id: Long, received: Long, sent: Long, amount: String) = BankSmsRecord(id,
        "Banco Popular de Ahorro:  La consulta de saldo fue completada. \n\n Saldo Disponible: CR $amount CUP",
        Instant.ofEpochMilli(received), 7, sentAt = Instant.ofEpochMilli(sent))

    private fun transferBalanceFixture(r: RoomWalletRepository, source: String = "0000000000000001",
                                       extraCard: Boolean = false, secondNumber: String = "0000000000000003",
                                       balanceBankCode: String = "02", balanceSubscriptionId: Int = 7,
                                       includeBalance: Boolean = true) {
        identity(r)
        r.putCard(CardRecord("card", "registration", "0000000000000001", "Origen", currency = "CUP"))
        if (extraCard) r.putCard(CardRecord("other", "registration", secondNumber, "Otro", currency = "CUP"))
        if (includeBalance) r.upsertBalances(listOf(BalanceRecord(
            "$balanceBankCode:$balanceSubscriptionId:registration:card:0000XXXXXXXX0001:CUP",
            balanceBankCode, balanceSubscriptionId, 1_010_000, "0000XXXXXXXX0001", "100.00", "CUP",
            ledger = "110.00", registrationId = "registration", cardId = "card", sentAt = 1_005_000)))
        r.putOperation(operation().copy(registrationId = "registration", source = source, startedAt = 1_000_000))
        check(r.updateOperationStatus("operation", OperationStatus.PREPARED, OperationStatus.SUBMITTING, 1_000_001))
    }
    private fun transferReceipt(r: RoomWalletRepository, remaining: String? = "CR 90.00 CUP",
                                receivedAt: Long = 1_100_000): String {
        val body = "Banco Bandec: La Transferencia fue completada.\nBeneficiario: 0000XXXXXXXX0002\n" +
            "Monto: 10.00 CUP\nNro. Transaccion: REMAIN01" + remaining?.let { "\nSaldo restante: $it" }.orEmpty()
        return checkNotNull(SmsIngestor(r, now = { Instant.ofEpochMilli(1_300_000) }).ingest(
            BankSmsRecord(null, body, Instant.ofEpochMilli(receivedAt), 7, sentAt = Instant.ofEpochMilli(1_050_000))).stored.receiptId)
    }
    private fun balanceSms(receivedAt: Long, sentAt: Long, available: String,
                           account: String = "0000XXXXXXXX0001") = BankSmsRecord(null,
        "Banco Bandec La consulta de saldo fue completada.\nCuenta;Saldo Contable;Saldo Disponible;Moneda\n" +
            "$account; CR 110.00; CR $available;CUP |",
        Instant.ofEpochMilli(receivedAt), 7, sentAt = Instant.ofEpochMilli(sentAt))
    private fun registration(id: String, subscription: Int) = RegistrationRecord(id, "identity", "BANDEC", "PERSONAL", "02", subscription, null, "BANDEC")
    private fun operation() = OperationRecord("operation", "TRANSFER", "02", 7, "0000000000000002", "10.00", "CUP", 900)
    private fun receipt() = ReceiptRecord("", "", "02", 7, "SENT", "ref-01", "10.00", "CUP", "0000000000000002")
    private fun event(source: EventSource, sourceId: String?, at: Long) = IncomingEvent(source, sourceId,
        "PAGOxMOVIL", "synthetic financial receipt", at, 7, receipt(), sentAt = at)
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
