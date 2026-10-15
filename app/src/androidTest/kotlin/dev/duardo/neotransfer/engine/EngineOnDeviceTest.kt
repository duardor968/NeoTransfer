package dev.duardo.neotransfer.engine

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.duardo.neotransfer.core.ledger.OperationState
import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Contact
import dev.duardo.neotransfer.core.model.Money
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.data.NeoDatabase
import dev.duardo.neotransfer.data.RoomStore
import dev.duardo.neotransfer.data.WalletCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The controller on the phone with real Room and dispatchers; the modem and the inbox are fakes.
 * Kept within a few seconds: this phone's power manager stops an instrumentation process after ~5 s.
 */
@RunWith(AndroidJUnit4::class)
class EngineOnDeviceTest {
    private val database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, NeoDatabase::class.java).build()
    private val inbox = FakeInbox()
    private val vault = FakeVault()
    private val bandec = WalletCard(OwnCard("9204000000000001", Bank.BANDEC))
    private val rigs = mutableListOf<Rig>()

    private fun process() = Rig(RoomStore(database.dao()), inbox, vault, Instant::now, Dispatchers.Default.limitedParallelism(1), Dispatchers.IO, FakeModem(latency = 50))
        .also { rigs += it }

    @After fun close() {
        rigs.forEach { it.controller.close() }
        database.close()
    }

    private suspend fun until(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "No llegó: $what" }
            delay(20)
        }
    }

    @Test fun lateSmsConfirmsAndTheNextProcessKeepsEverything() = runBlocking {
        val first = process()
        first.controller.unlock(first.controller.unlockCipher())
        first.controller.saveKey(first.controller.keyCipher(), Bank.BANDEC, "12345".toCharArray())
        first.controller.saveCard(bandec)
        first.controller.saveContact(Contact("", "Juan", listOf("50000009")))
        val id = assertIs<Submission.Queued>(first.controller.refreshBalance(bandec.card.number)).id
        until("40 y 46 enviados") { first.modem.services == listOf(40, 46) }
        until("enviada") { first.state(id) is OperationState.Sent }
        first.controller.onBackground()

        delay(200) // the answer comes later
        inbox.deliver(BankSms.balanceBandec(bandec.card.number, "1000.00"), Instant.now().minusMillis(500), Instant.now())
        first.controller.onBankSms().join()
        until("confirmada") { first.state(id) is OperationState.Confirmed }
        assertEquals(Money.cup("1000.00"), first.controller.cards.value.single().balance.value)
        assertEquals("Consulta de saldo", first.notifier.posted.single().title)
        first.controller.close() // the process dies

        inbox.deliver(BankSms.received(bandec.card.number, "300.00", "AY000DEV"), Instant.now(), Instant.now())
        val second = process() // its start reads the inbox from the last stored id
        until("abono notificado") { second.notifier.posted.any { it.title == "Juan te envió 300 CUP" } }
        assertIs<OperationState.Confirmed>(second.state(id))
        assertEquals(Money.cup("1300.00"), second.controller.cards.value.single().balance.value)
        assertEquals(Bank.BANDEC, second.controller.session.value?.bank)
        assertTrue(second.modem.sent.isEmpty()) // nothing is resent after a restart
    }
}
