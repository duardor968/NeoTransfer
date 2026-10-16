package dev.duardo.neotransfer.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.duardo.neotransfer.core.ledger.Ledger
import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Contact
import dev.duardo.neotransfer.core.model.Money
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.core.model.ServiceKind
import dev.duardo.neotransfer.core.model.ServiceReference
import dev.duardo.neotransfer.core.protocol.BalanceQuery
import dev.duardo.neotransfer.core.protocol.Source
import dev.duardo.neotransfer.core.protocol.Transfer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class RoomStoreTest {
    private val database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, NeoDatabase::class.java).build()
    private val store = RoomStore(database.dao())
    private val at = Instant.ofEpochMilli(1_792_000_000_000)

    @After fun close() = database.close()

    @Test fun everythingSurvivesARoundTrip() = runBlocking {
        val transfer = Ledger.submit("t", Transfer(Bank.BANDEC, Source.Default(), "9204990000000007", Money.cup("300")), at)
            .sending(at).replied("Su solicitud esta siendo procesada", at.plusSeconds(3))
        val query = Ledger.submit("q", BalanceQuery(Bank.BPA), at)
        store.saveOperation(query)
        store.saveOperation(transfer)
        store.saveSms(listOf(StoredSms(101, at, at.plusSeconds(5), "uno"), StoredSms(102, at, at.plusSeconds(6), "dos")))
        store.saveSms(listOf(StoredSms(102, at, at.plusSeconds(7), "dos, otro")))
        val bpa = WalletCard(OwnCard("9205000000000001", Bank.BPA), "TITULAR", "01/30")
        val bandec = WalletCard(OwnCard("9204000000000001", Bank.BANDEC, alias = "Sueldo"))
        store.saveCards(listOf(bandec, bpa))
        store.saveCards(listOf(bandec, bpa, WalletCard(OwnCard("9204000000000002", Bank.BANDEC))))
        store.deleteCard("9204000000000002")
        val juan = Contact("c1", "Juan", listOf("50000009"), listOf("9204990000000007"))
        store.saveContacts(listOf(juan, Contact("c2", "Ana")))
        store.deleteContact("c2")
        val electricity = ServiceReference(ServiceKind.ELECTRICITY, "12345678901", "Casa")
        store.saveServices(listOf(electricity, ServiceReference(ServiceKind.PHONE, "123456789012345")))
        store.deleteService(ServiceReference(ServiceKind.PHONE, "123456789012345"))
        val preferences = Preferences(Settings(Theme.LIGHT), mapOf(Bank.BPA to at, Bank.BANDEC to at), inboxVersion = 2)
        store.savePreferences(preferences)
        store.savePreferences(preferences.copy(keySavedAt = mapOf(Bank.BANDEC to at)))

        val snapshot = store.load()
        assertEquals(setOf(query, transfer), snapshot.operations.toSet())
        assertEquals(listOf("uno", "dos, otro"), snapshot.sms.sortedBy { it.id }.map { it.body })
        assertEquals(listOf(bandec, bpa), snapshot.cards)
        assertEquals(listOf(juan), snapshot.contacts)
        assertEquals(listOf(electricity), snapshot.services)
        assertEquals(preferences.copy(keySavedAt = mapOf(Bank.BANDEC to at)), snapshot.preferences)
    }
}
