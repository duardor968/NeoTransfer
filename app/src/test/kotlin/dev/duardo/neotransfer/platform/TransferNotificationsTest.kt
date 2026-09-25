package dev.duardo.neotransfer.platform

import dev.duardo.neotransfer.core.BankMessage
import dev.duardo.neotransfer.core.Currency
import dev.duardo.neotransfer.core.Money
import dev.duardo.neotransfer.data.ContactCard
import dev.duardo.neotransfer.data.ContactPhone
import dev.duardo.neotransfer.data.ContactRecord
import dev.duardo.neotransfer.data.ReceiptRecord
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransferNotificationsTest {
    private val receipt = BankMessage.TransferReceived("1234567890123456", "50001234",
        Money(BigDecimal("25.00"), Currency.CUP), "TEST123")

    @Test fun exactNationalAndCountryPrefixedPhoneResolveTheSameContact() {
        for (phone in listOf("50001234", "+53 (5000) 1234", "53 5000-1234")) {
            assertEquals("Contacto de prueba", incomingTransferSender(receipt, listOf(contact("one", phone))))
        }
        assertEquals("Contacto de prueba", incomingTransferSender(receipt.copy(senderPhone = "+5350001234"),
            listOf(contact("one", "50001234"))))
    }

    @Test fun repeatedNumberWithinOneContactIsNotAmbiguous() {
        val person = contact("one", "50001234").copy(phones = listOf(ContactPhone("50001234"), ContactPhone("+5350001234")))
        assertEquals(person.name, incomingTransferSender(receipt, listOf(person)))
    }

    @Test fun twoContactsWithTheExactPhoneDoNotPickAnArbitraryName() {
        assertEquals(receipt.senderPhone, incomingTransferSender(receipt,
            listOf(contact("one", "50001234"), contact("two", "+5350001234"))))
    }

    @Test fun maskedPartialAndMalformedNumbersCannotBecomeExactMatches() {
        for (phone in listOf("****50001234", "50001234 ext", "1234", "990050001234", "+50001234", "٥٠٠٠١٢٣٤")) {
            assertEquals(receipt.senderPhone, incomingTransferSender(receipt, listOf(contact("one", phone))))
        }
        assertEquals("****50001234", incomingTransferSender(receipt.copy(senderPhone = "****50001234"),
            listOf(contact("one", "50001234"))))
    }

    @Test fun recipientAccountMustNotMatchASendersContactCard() {
        val person = ContactRecord("one", "Contacto de prueba", cards = listOf(ContactCard(receipt.account)))
        assertEquals(receipt.senderPhone, incomingTransferSender(receipt, listOf(person)))
    }

    @Test fun unknownOrUnnamedContactUsesTheReceivedPhone() {
        assertEquals(receipt.senderPhone, incomingTransferSender(receipt, emptyList()))
        assertEquals(receipt.senderPhone, incomingTransferSender(receipt, listOf(contact("one", "50001234").copy(name = " "))))
    }

    @Test fun durableReceivedReceiptKeepsItsSenderAccountAmountAndReference() {
        assertEquals(receipt, queuedTransferMessage(storedReceipt()))
    }

    @Test fun nonReceiptOrConflictingOrNominalOutboxDataCannotAnnounceAnIncomingTransfer() {
        val stored = storedReceipt()
        for (invalid in listOf(stored.copy(id = ""), stored.copy(kind = "SENT"), stored.copy(kind = "LEDGER_CREDIT"),
            stored.copy(referenceConflict = true), stored.copy(amountIsNominal = true))) {
            assertNull(queuedTransferMessage(invalid))
        }
    }

    @Test fun malformedStoredAmountOrCurrencyDoesNotInventAnAmountOrBlockQueueProcessing() {
        val stored = storedReceipt()
        for (amount in listOf("", "invalid", "-25.00", "25.001")) assertNull(queuedTransferMessage(stored.copy(amount = amount)))
        assertNull(queuedTransferMessage(stored.copy(currency = "UNKNOWN")))
        assertEquals(receipt.copy(amount = Money(BigDecimal("0.00"), Currency.CUP)), queuedTransferMessage(stored.copy(amount = "0.00")))
    }

    private fun storedReceipt() = ReceiptRecord("receipt-test", "event-test", null, 1, "RECEIVED",
        receipt.reference, "25.00", "CUP", receipt.senderPhone, receipt.account)

    private fun contact(id: String, phone: String) = ContactRecord(id, "Contacto de prueba", listOf(ContactPhone(phone)))
}
