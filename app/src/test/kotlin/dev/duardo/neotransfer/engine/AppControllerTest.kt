package dev.duardo.neotransfer.engine

import dev.duardo.neotransfer.core.backup.BackupFailure
import dev.duardo.neotransfer.core.ledger.NotExecutedReason
import dev.duardo.neotransfer.core.ledger.OperationState
import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Contact
import dev.duardo.neotransfer.core.model.Money
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.core.model.ServiceKind
import dev.duardo.neotransfer.core.model.ServiceReference
import dev.duardo.neotransfer.core.protocol.CubacelQuery
import dev.duardo.neotransfer.core.protocol.CubacelQueryKind
import dev.duardo.neotransfer.core.protocol.CubacelTransfer
import dev.duardo.neotransfer.core.protocol.Transfer
import dev.duardo.neotransfer.data.WalletCard
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** User scenarios of the green app's mechanics through the controller, with a fake modem and inbox. */
@OptIn(ExperimentalCoroutinesApi::class)
class AppControllerTest {
    private val start = Instant.parse("2026-10-15T14:00:00Z")
    private val bpa = WalletCard(OwnCard("9205000000000001", Bank.BPA))
    private val bandec = WalletCard(OwnCard("9204000000000001", Bank.BANDEC))
    private val friend = "9204990000000007"

    private fun TestScope.now(): Instant = start.plusMillis(testScheduler.currentTime)

    private fun TestScope.rig(store: MemoryStore = MemoryStore(), inbox: FakeInbox = FakeInbox(), vault: FakeVault = FakeVault()): Rig {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return Rig(store, inbox, vault, { now() }, dispatcher, dispatcher)
    }

    /** First run: fingerprint without keys, both bank keys saved, the cards added. */
    private suspend fun Rig.ready(vararg cards: WalletCard) {
        controller.unlock(controller.unlockCipher())
        controller.saveKey(controller.keyCipher(), Bank.BPA, "1234".toCharArray())
        controller.saveKey(controller.keyCipher(), Bank.BANDEC, "12345".toCharArray())
        cards.forEach { controller.saveCard(it) }
    }

    private fun TestScope.wait(duration: Duration) { advanceTimeBy(duration.toMillis()); runCurrent() }
    private fun TestScope.deliver(rig: Rig, body: String, late: Duration = Duration.ofSeconds(5)) = rig.inbox.deliver(body, now() - late, now())
    private fun Rig.balance(card: WalletCard) = controller.cards.value.first { it.card == card }.balance.value
    private fun queued(submission: Submission) = assertIs<Submission.Queued>(submission).id

    @Test fun `refresh, SMS 14 minutes late, the balance updates and the result is notified only in the background`() = runTest {
        val rig = rig()
        rig.ready(bpa)
        rig.controller.onForeground()
        val id = queued(rig.controller.refreshBalance(bpa.card.number))
        wait(Duration.ofSeconds(10))
        assertEquals(listOf(40, 46), rig.modem.services) // the 46 leaves right after the 40's acknowledgement
        assertIs<OperationState.Sent>(rig.state(id))
        rig.controller.onBackground()
        wait(Duration.ofMinutes(14))
        assertIs<OperationState.Sent>(rig.state(id)) // no timeout, nothing resent
        deliver(rig, BankSms.balanceBpa("845.10"), late = Duration.ofMinutes(13))
        rig.controller.onBankSms().join()
        assertIs<OperationState.Confirmed>(rig.state(id))
        assertEquals(Money.cup("845.10"), rig.balance(bpa))
        val notice = rig.notifier.posted.single()
        assertEquals("Consulta de saldo" to "Confirmada", notice.title to notice.text)
        assertEquals("Operación confirmada", notice.publicTitle)
        assertEquals(listOf(40, 46), rig.modem.services)
    }

    @Test fun `no SMS in 15 minutes is Sin respuesta without anything else happening, and a late answer still closes it`() = runTest {
        val rig = rig()
        rig.ready(bpa)
        rig.controller.onForeground()
        val id = queued(rig.controller.refreshBalance(bpa.card.number))
        wait(Duration.ofSeconds(10))
        wait(Duration.ofMinutes(15))
        assertIs<OperationState.NoResponse>(rig.state(id))
        deliver(rig, BankSms.balanceBpa("10.00"), late = Duration.ofMinutes(14))
        rig.controller.onInboxChanged() // the inbox write seen by the observer
        runCurrent()
        assertIs<OperationState.Confirmed>(rig.state(id))
        assertTrue(rig.notifier.posted.isEmpty()) // the app is in front
    }

    @Test fun `credit with the app closed wakes a new process, names the contact and adds to the balance at once`() = runTest {
        val store = MemoryStore()
        val inbox = FakeInbox()
        inbox.deliver(BankSms.balanceBandec(bandec.card.number, "1000.00"), now() - Duration.ofMinutes(10), now() - Duration.ofMinutes(10))
        inbox.deliver(BankSms.received(bandec.card.number, "50.00", "AY000OLD"), now() - Duration.ofMinutes(5), now() - Duration.ofMinutes(5))
        val first = rig(store, inbox)
        first.controller.saveCard(bandec)
        first.controller.saveContact(Contact("", "Juan", listOf("+53 5000 0009")))
        runCurrent()
        assertTrue(first.notifier.posted.isEmpty()) // the inbox import at install is silent
        assertEquals(Money.cup("1050.00"), first.balance(bandec))
        first.controller.close() // the process dies

        wait(Duration.ofHours(1))
        deliver(rig = first, body = BankSms.received(bandec.card.number, "300.00", "AY000NEW"))
        val second = rig(store, inbox) // SMS_RECEIVED starts the process
        second.controller.onBankSms().join()
        val notice = second.notifier.posted.single()
        assertEquals("Juan te envió 300 CUP", notice.title)
        assertEquals("Transferencia recibida", notice.publicTitle)
        assertEquals(Routes.movement("ref:AY000NEW"), notice.route)
        assertEquals(Money.cup("1350.00"), second.balance(bandec))
        assertTrue(second.controller.locked.value)
    }

    @Test fun `the inbox is read from the last stored id, never again from the start`() = runTest {
        val inbox = FakeInbox()
        repeat(3) { inbox.deliver(BankSms.balanceBpa("$it.00"), now(), now()) }
        val rig = rig(inbox = inbox)
        runCurrent()
        deliver(rig, BankSms.balanceBpa("9.00"))
        rig.controller.onBankSms().join()
        rig.controller.onForeground()
        runCurrent()
        assertEquals(0L, inbox.reads.first())
        assertTrue(inbox.reads.drop(1).all { it >= 102 }, "reads: ${inbox.reads}")
        assertEquals(4, rig.store.let { it as MemoryStore }.sms.size)
    }

    @Test fun `without READ_SMS nothing is imported, and the import happens when it is granted`() = runTest {
        val inbox = FakeInbox().apply { granted = false }
        inbox.deliver(BankSms.received(bandec.card.number, "20.00", "AY000PRM"), now(), now())
        val rig = rig(inbox = inbox)
        rig.controller.saveCard(bandec)
        runCurrent()
        assertTrue(rig.controller.movements.value.isEmpty())
        inbox.granted = true
        rig.controller.onPermissionsChanged()
        runCurrent()
        assertEquals(1, rig.controller.movements.value.size)
        assertTrue(rig.notifier.posted.isEmpty())
    }

    @Test fun `busy bank retries a query once on its own and never resends money`() = runTest {
        val rig = rig()
        rig.ready(bandec)
        rig.controller.onForeground()
        val query = queued(rig.controller.refreshBalance(bandec.card.number))
        wait(Duration.ofSeconds(10))
        deliver(rig, BankSms.busy(), late = Duration.ofSeconds(2))
        rig.controller.onBankSms().join()
        assertIs<OperationState.Retrying>(rig.state(query))
        wait(Duration.ofSeconds(20))
        assertEquals(listOf(40, 46, 46), rig.modem.services)

        val transfer = queued(rig.controller.submit(Transfer(Bank.BANDEC, rig.controller.sourceFor(bandec.card.number), friend, Money.cup("300"))))
        wait(Duration.ofSeconds(5))
        deliver(rig, BankSms.busy(), late = Duration.ofSeconds(1))
        rig.controller.onBankSms().join()
        assertEquals(OperationState.NotExecuted(NotExecutedReason.BUSY), rig.state(transfer))
        wait(Duration.ofMinutes(5))
        assertEquals(listOf(40, 46, 46, 45), rig.modem.services)
        // "Reintentar": one tap, a new operation, no duplicate warning.
        val again = queued(rig.controller.retry(transfer))
        wait(Duration.ofSeconds(5))
        assertEquals(listOf(40, 46, 46, 45, 45), rig.modem.services)
        assertIs<OperationState.Sent>(rig.state(again))
    }

    @Test fun `BANDEC refresh with two cards sends 40 and 46 only, never 60, and last operations ask for that card`() = runTest {
        val second = WalletCard(OwnCard("9204000000000002", Bank.BANDEC))
        val rig = rig()
        rig.ready(bandec, second)
        queued(rig.controller.refreshBalance(second.card.number))
        wait(Duration.ofSeconds(10))
        assertEquals(listOf(40, 46), rig.modem.services)
        assertEquals("*444*46#", rig.modem.sent.last().valueForTransport())
        deliver(rig, BankSms.balanceBandec(second.card.number, "320.00"))
        rig.controller.onBankSms().join()
        assertEquals(Money.cup("320.00"), rig.balance(second))
        assertNull(rig.balance(bandec))
        queued(rig.controller.refreshHistory(second.card.number))
        wait(Duration.ofSeconds(5))
        assertEquals(listOf(40, 46, 48), rig.modem.services) // the session is still on: no second 40
        assertEquals(dev.duardo.neotransfer.core.protocol.Source.Card(second.card.number), rig.controller.sourceFor(second.card.number))
    }

    @Test fun `an identical pending payment asks for confirmation instead of blocking`() = runTest {
        val rig = rig()
        rig.ready(bandec)
        val transfer = Transfer(Bank.BANDEC, rig.controller.sourceFor(bandec.card.number), friend, Money.cup("300"))
        val first = queued(rig.controller.submit(transfer))
        wait(Duration.ofSeconds(10))
        val duplicate = assertIs<Submission.ConfirmDuplicate>(rig.controller.submit(transfer))
        assertEquals(first, duplicate.pending.operation.id)
        assertEquals(listOf(40, 45), rig.modem.services)
        queued(rig.controller.submit(transfer, confirmed = true))
        wait(Duration.ofSeconds(5))
        assertEquals(listOf(40, 45, 45), rig.modem.services)
    }

    @Test fun `a rejection closes the operation with its reason and is notified in the background`() = runTest {
        val rig = rig()
        rig.ready(bandec)
        rig.controller.saveContact(Contact("", "Ana", cards = listOf(friend)))
        val id = queued(rig.controller.submit(Transfer(Bank.BANDEC, rig.controller.sourceFor(bandec.card.number), friend, Money.cup("300"))))
        wait(Duration.ofSeconds(10))
        rig.controller.onBackground()
        deliver(rig, BankSms.rejectedTransfer())
        rig.controller.onBankSms().join()
        assertEquals("La cuenta no está asociada a una tarjeta magnética.", assertIs<OperationState.Rejected>(rig.state(id)).reason)
        val notice = rig.notifier.posted.single()
        assertEquals("Transferencia de 300 CUP a Ana", notice.title)
        assertEquals("Rechazada: La cuenta no está asociada a una tarjeta magnética.", notice.text)
    }

    @Test fun `the process dies with a 40 in the air - the next start waits out the acknowledgement and resumes the queue`() = runTest {
        val store = MemoryStore()
        val vault = FakeVault()
        val first = rig(store, vault = vault)
        first.ready(bpa)
        first.modem.answer = { null } // the network never answers
        val balance = queued(first.controller.refreshBalance(bpa.card.number))
        val cubacel = queued(first.controller.submit(CubacelQuery(CubacelQueryKind.BALANCE)))
        wait(Duration.ofSeconds(5))
        assertEquals(listOf(40), first.modem.services)
        assertTrue(store.operations.values.any { it.unlocks == balance && it.attempts.size == 1 }) // recorded before the modem had it
        first.controller.close()

        val second = rig(store, vault = vault)
        second.modem.answer = { UssdReply.Text("Saldo: 10.00 CUP") }
        runCurrent()
        assertTrue(second.modem.sent.isEmpty()) // the 40 may still be acknowledged: the SIM is not free yet
        wait(Duration.ofSeconds(50))
        assertEquals(NotExecutedReason.AUTHENTICATION, assertIs<OperationState.NotExecuted>(second.state(balance)).reason)
        assertEquals(listOf<Int?>(null), second.modem.services) // the Cubacel query went out
        assertEquals("Saldo: 10.00 CUP", assertIs<OperationState.Answered>(second.state(cubacel)).text)
    }

    @Test fun `an operation queued before the process died waits for the fingerprint, then the 40 and the query go`() = runTest {
        val store = MemoryStore()
        val vault = FakeVault()
        val first = rig(store, vault = vault)
        first.ready(bpa)
        first.modem.latency = 60_000
        val cubacel = queued(first.controller.submit(CubacelQuery(CubacelQueryKind.DATA)))
        val balance = queued(first.controller.refreshBalance(bpa.card.number))
        runCurrent()
        first.controller.close() // dies while the Cubacel query waits for its answer

        val second = rig(store, vault = vault)
        wait(Duration.ofMinutes(2))
        assertEquals(OperationState.Queued, second.state(balance)) // locked: the key is not in memory
        assertTrue(second.modem.sent.isEmpty())
        assertIs<OperationState.NoResponse>(second.state(cubacel))
        second.controller.unlock(second.controller.unlockCipher())
        wait(Duration.ofSeconds(10))
        assertEquals(listOf(40, 46), second.modem.services)
        assertIs<OperationState.Sent>(second.state(balance))
    }

    @Test fun `the UI lock after a minute in the background keeps the bank session`() = runTest {
        val rig = rig()
        rig.ready(bpa)
        rig.controller.onForeground()
        queued(rig.controller.refreshBalance(bpa.card.number))
        wait(Duration.ofSeconds(10))
        rig.controller.onBackground()
        wait(Duration.ofSeconds(61))
        assertTrue(rig.controller.locked.value)
        assertEquals(Bank.BPA, rig.controller.session.value?.bank)
        queued(rig.controller.refreshBalance(bpa.card.number)) // no 40, so no key needed
        wait(Duration.ofSeconds(5))
        assertEquals(listOf(40, 46, 46), rig.modem.services)
        wait(Duration.ofMinutes(60))
        assertNull(rig.controller.session.value)
    }

    @Test fun `a bank without a saved key, or with a rejected one, asks for the key first`() = runTest {
        val rig = rig()
        rig.controller.unlock(null)
        rig.controller.saveCard(bandec)
        assertEquals(Submission.KeyRequired(Bank.BANDEC), rig.controller.refreshBalance(bandec.card.number))
        assertTrue(rig.modem.sent.isEmpty())
        assertTrue(rig.controller.operations.value.isEmpty())
    }

    @Test fun `the Cubacel transfer key is used once and never stored`() = runTest {
        val rig = rig()
        rig.controller.unlock(null)
        assertFailsWith<IllegalArgumentException> { rig.controller.submit(CubacelTransfer("53000009", 25)) }
        val key = "4321".toCharArray()
        queued(rig.controller.submit(CubacelTransfer("53000009", 25), transferKey = key))
        assertTrue(key.all { it == '\u0000' })
        wait(Duration.ofSeconds(5))
        assertEquals("*234*1*53000009*4321*25#", rig.modem.sent.single().valueForTransport())
        val stored = (rig.store as MemoryStore).operations.values.single()
        assertTrue("4321" !in dev.duardo.neotransfer.core.ledger.Operation.encode(stored))
    }

    @Test fun `Cubacel packages are dialed, never queued`() = runTest {
        val rig = rig()
        rig.controller.cubacelPackages(listOf(1, 4))
        val dialed = rig.modem.dialed.single()
        assertEquals("*133*1*4#" to dev.duardo.neotransfer.core.protocol.UssdTransport.DIAL, dialed.valueForTransport() to dialed.transport)
        runCurrent()
        assertTrue(rig.modem.sent.isEmpty() && rig.controller.operations.value.isEmpty())
    }

    @Test fun `bank keys live in the vault and in memory only while unlocked`() = runTest {
        val vault = FakeVault()
        val rig = rig(vault = vault)
        rig.ready()
        assertEquals(setOf(Bank.BPA, Bank.BANDEC), rig.controller.savedKeys.value)
        assertTrue(vault.contains("BANDEC=12345"))
        rig.controller.saveKey(rig.controller.keyCipher(), Bank.BPA, null)
        assertEquals(setOf(Bank.BANDEC), rig.controller.savedKeys.value)
        assertTrue(!vault.contains("BPA="))
        assertFailsWith<IllegalArgumentException> { rig.controller.saveKey(rig.controller.keyCipher(), Bank.BPA, "12".toCharArray()) }
    }

    @Test fun `the registration SMS is never copied`() = runTest {
        val inbox = FakeInbox()
        inbox.deliver(BankSms.registration("99887"), now(), now())
        val rig = rig(inbox = inbox)
        runCurrent()
        val stored = (rig.store as MemoryStore).sms.values.single()
        assertTrue("99887" !in stored.body)
        assertEquals(dev.duardo.neotransfer.core.sms.SmsMessage.Registration, stored.record().message)
    }

    @Test fun `contacts, backup round trip and wrong password`() = runTest {
        val rig = rig()
        rig.controller.saveCard(bpa.copy(holder = "Duardo", expiry = "03/29"))
        val juan = rig.controller.saveContact(Contact("", " Juan ", listOf("+53 5000 0009", "50000009"), listOf("9204-9900-0000-0007"),
            mapOf("9204-9900-0000-0007" to " Salario  BANDEC ", "9204990000000099" to "Sin tarjeta")))
        assertEquals(Contact(juan.id, "Juan", listOf("50000009"), listOf(friend), mapOf(friend to "Salario BANDEC")), juan)
        rig.controller.saveService(ServiceReference(ServiceKind.ELECTRICITY, "12345678901", "Casa"))
        // The address book is the source: Ana is listed without importing anything; her card is the app's.
        rig.addressBook = listOf(PhoneContact("lookup-1", "Ana", listOf("52222222")))
        rig.controller.onPermissionsChanged()
        runCurrent()
        assertEquals(listOf("Ana", "Juan"), rig.controller.contacts.value.map { it.name })
        assertEquals(setOf("phone:lookup-1"), rig.controller.addressBook.value)
        val ana = rig.controller.saveContact(Contact("phone:lookup-1", "Ana", listOf("52222222"), listOf("9204990000000015")))
        // Renamed in the phone: the app follows, and her card stays.
        rig.addressBook = listOf(PhoneContact("lookup-1", "Ana B.", listOf("52222222")))
        rig.controller.onForeground()
        runCurrent()
        assertEquals(Contact(ana.id, "Ana B.", listOf("52222222"), listOf("9204990000000015")), rig.controller.contacts.value.first { it.id == ana.id })
        // Without the permission only what the app keeps remains.
        rig.addressBook = null
        rig.controller.onPermissionsChanged()
        runCurrent()
        assertNull(rig.controller.addressBook.value)
        assertEquals(listOf("Ana", "Juan"), rig.controller.contacts.value.map { it.name })
        rig.addressBook = listOf(PhoneContact("lookup-1", "Ana B.", listOf("52222222")))
        rig.controller.onPermissionsChanged()
        runCurrent()

        rig.controller.exportBackup("content://backup", "clave segura".toCharArray())
        assertEquals(now(), rig.controller.lastBackup.value)
        val restored = rig()
        restored.documents.files["content://backup"] = rig.documents.files.getValue("content://backup")
        assertFailsWith<BackupFailure.AuthenticationFailed> { restored.controller.importBackup("content://backup", "otra".toCharArray()) }
        assertEquals(ImportSummary(1, 2, 1), restored.controller.importBackup("content://backup", "clave segura".toCharArray()))
        assertEquals(listOf(bpa.copy(holder = "Duardo", expiry = "03/29")), restored.controller.cards.value.map { it.card })
        assertEquals(setOf(juan.id, ana.id), restored.controller.contacts.value.map { it.id }.toSet())
        assertEquals(mapOf(friend to "Salario BANDEC"), restored.controller.contacts.value.first { it.id == juan.id }.labels)
        assertEquals(ImportSummary(0, 0, 0), restored.controller.importBackup("content://backup", "clave segura".toCharArray()))
        rig.controller.deleteContact(juan.id)
        assertEquals(listOf("Ana B."), rig.controller.contacts.value.map { it.name })
    }

    /** Local only: `NEOTRANSFER_TRM` = a real Transfermóvil backup. Prints counts, never contents. */
    @Test fun `a real trm imports`() = runTest {
        val path = System.getenv("NEOTRANSFER_TRM") ?: return@runTest
        val rig = rig()
        rig.documents.files["content://trm"] = File(path).readBytes()
        val tables = dev.duardo.neotransfer.core.backup.TrmImporter.import(File(path).readBytes())
        val summary = rig.controller.importTrm("content://trm")
        println("TRM: added $summary; rows imported ${tables.imported}, skipped ${tables.skipped}")
        assertEquals(tables.content.cards.size, summary.cards)
        assertEquals(ImportSummary(0, 0, 0), rig.controller.importTrm("content://trm")) // importing again adds nothing
    }
}
