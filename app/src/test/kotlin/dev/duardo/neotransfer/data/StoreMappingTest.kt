package dev.duardo.neotransfer.data

import dev.duardo.neotransfer.core.ledger.Ledger
import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Contact
import dev.duardo.neotransfer.core.model.Currency
import dev.duardo.neotransfer.core.model.Money
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.core.model.ServiceKind
import dev.duardo.neotransfer.core.model.ServiceReference
import dev.duardo.neotransfer.core.protocol.Source
import dev.duardo.neotransfer.core.protocol.Transfer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

/** Room rows <-> domain, both ways, on the JVM. */
class StoreMappingTest {
    private val at = Instant.parse("2026-10-15T14:00:00.123Z")

    @Test fun operations() {
        val operation = Ledger.submit("op-1", Transfer(Bank.BPA, Source.Default(), "9204990000000007", Money.cup("300.50")), at)
            .sending(at).replied("Su solicitud esta siendo procesada", at.plusSeconds(2))
        val row = operation.toEntity()
        assertEquals(at.toEpochMilli(), row.createdAt)
        assertEquals(operation, row.toOperation())
    }

    @Test fun `sms, cards, contacts and services`() {
        val sms = StoredSms(7, at, at.plusSeconds(6), "Banco Popular de Ahorro: La consulta de saldo fue completada. Saldo Disponible: CR 1.00 CUP")
        assertEquals(sms, sms.toEntity().toStoredSms())
        val card = WalletCard(OwnCard("9225000000000001", Bank.BPA, Currency.USD, "Dólares"), "DUARDO R", "12/29")
        assertEquals(card, card.toEntity(3).toWalletCard())
        assertEquals(3, card.toEntity(3).position)
        val contact = Contact("c1", "Juan", listOf("50000009", "52222222"), listOf("9204990000000007", "9205990000000001"), mapOf("9204990000000007" to "Salario"))
        assertEquals(contact, contact.toEntity().toContact())
        assertEquals(Contact("c2", "Ana"), Contact("c2", "Ana").toEntity().toContact())
        val service = ServiceReference(ServiceKind.NAUTA, "usuario@nauta.com.cu", null)
        assertEquals(service, service.toEntity().toServiceReference())
    }

    @Test fun preferences() {
        val preferences = Preferences(Settings(Theme.DARK, creditNotifications = false, resultNotifications = true),
            mapOf(Bank.BANDEC to at), inboxImported = true, lastBackup = at)
        val millis = Instant.ofEpochMilli(at.toEpochMilli())
        assertEquals(preferences.copy(keySavedAt = mapOf(Bank.BANDEC to millis), lastBackup = millis), preferences.toRows().toPreferences())
        assertEquals(Preferences(), emptyList<SettingEntity>().toPreferences())
    }
}
