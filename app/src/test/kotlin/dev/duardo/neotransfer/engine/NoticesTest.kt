package dev.duardo.neotransfer.engine

import dev.duardo.neotransfer.core.ledger.Direction
import dev.duardo.neotransfer.core.ledger.Movement
import dev.duardo.neotransfer.core.ledger.MovementKind
import dev.duardo.neotransfer.core.model.Agency
import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Contact
import dev.duardo.neotransfer.core.model.MiTransfer
import dev.duardo.neotransfer.core.model.Money
import dev.duardo.neotransfer.designsystem.format.formatPhone
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NoticesTest {
    private fun credit(phone: String) = Movement("ref:AY1", MovementKind.TRANSFER, null, Direction.IN, Money.cup("300.00"),
        Instant.EPOCH, Bank.BANDEC, null, "9204000000000001", phone, "AY1", null, null)

    @Test fun `credit names the sender only when exactly one contact has the phone`() {
        val juan = Contact("1", "Juan", listOf("50000009"))
        assertEquals("Juan te envió 300 CUP", Notices.credit(credit("5350000009"), listOf(juan)).title)
        assertEquals("5 0000009 te envió 300 CUP", Notices.credit(credit("5350000009"), listOf(juan, Contact("2", "Otro", listOf("50000009")))).title)
        assertEquals("5 0000009 te envió 300 CUP", Notices.credit(credit("5350000009"), emptyList()).title)
    }

    @Test fun `money that never reached the bank is announced with its reason`() {
        val request = dev.duardo.neotransfer.core.protocol.Transfer(Bank.BPA, dev.duardo.neotransfer.core.protocol.Source.Default(), "9204990000000007", Money.cup("50"))
        val status = dev.duardo.neotransfer.core.ledger.OperationStatus(
            dev.duardo.neotransfer.core.ledger.Operation("op", request, Instant.EPOCH),
            dev.duardo.neotransfer.core.ledger.OperationState.NotExecuted(dev.duardo.neotransfer.core.ledger.NotExecutedReason.BUSY),
        )
        val notice = Notices.result(status, listOf(Contact("1", "Juan", cards = listOf("9204990000000007"))))
        assertEquals("Transferencia de 50 CUP a Juan" to "No se ejecutó: el banco estaba ocupado", notice.title to notice.text)
        assertEquals("Operación no ejecutada", notice.publicTitle)
        // Refused at once: the bank's own words, never a summary that blames the network.
        val refused = dev.duardo.neotransfer.core.ledger.OperationState.NotExecuted(
            dev.duardo.neotransfer.core.ledger.NotExecutedReason.REFUSED, "Formato Incorrecto, Repita su solicitud")
        assertEquals("formato Incorrecto, Repita su solicitud", Notices.notExecuted(refused))
    }

    @Test fun `services of the line name their bank, and the Clásica's removal is of its association`() {
        fun describe(request: dev.duardo.neotransfer.core.protocol.UssdRequest) = Notices.describe(request, emptyList())
        assertEquals("Cambio de clave en BPA", describe(dev.duardo.neotransfer.core.protocol.PinChange(Bank.BPA)))
        assertEquals("Cuentas en BANDEC", describe(dev.duardo.neotransfer.core.protocol.AccountsQuery(Bank.BANDEC)))
        assertEquals("Eliminar el registro en BPA", describe(dev.duardo.neotransfer.core.protocol.RegistrationRemoval(Bank.BPA)))
        assertEquals("Registro en BANDEC",
            describe(dev.duardo.neotransfer.core.protocol.BankRegistration(Bank.BANDEC, "9206069900000041", "Laura Mendez", expiry = "2812")))
        assertEquals("Quitar la asociación de la Clásica", describe(dev.duardo.neotransfer.core.protocol.ClassicRemoval("9760030000007813", "58072304511")))
    }

    @Test fun `an answer is notified as MiTransfer's words without its signature, and a new PIN in it is masked`() {
        val request = dev.duardo.neotransfer.core.protocol.MiTransferRegistration("58072304511")
        val status = dev.duardo.neotransfer.core.ledger.OperationStatus(
            dev.duardo.neotransfer.core.ledger.Operation("op", request, Instant.EPOCH),
            dev.duardo.neotransfer.core.ledger.OperationState.Answered(Instant.EPOCH, "Monedero Mi Transfer: Su PIN 2468 quedó registrado."),
        )
        val notice = Notices.result(status, emptyList())
        assertEquals(listOf("Registro en MiTransfer", "Su PIN •••• quedó registrado.", "Operación respondida"), listOf(notice.title, notice.text, notice.publicTitle))
        assertEquals("Su saldo es 86.40 USD", Notices.unsigned("Bolsa Mi Transfer:  Su saldo es 86.40 USD"))
    }

    @Test fun `phones are written as Contacts writes them`() {
        assertEquals("5 2854627", formatPhone("52854627"))
        assertEquals("6 3123456", formatPhone("63123456"))
        assertEquals("7 8302214", formatPhone("78302214"))
        assertEquals("32 291234", formatPhone("32291234"))
        assertEquals("5285462", formatPhone("5285462")) // incomplete: as typed
    }

    @Test fun amounts() {
        assertEquals("300 CUP", Notices.amount(Money.cup("300.00")))
        assertEquals("10 000 CUP", Notices.amount(Money.cup("10000"))) // a space between thousands, never split
        assertEquals("300.50 CUP", Notices.amount(Money.cup("300.5")))
    }

    @Test fun `bank keys encode and decode without strings`() {
        val keys = mapOf<Agency, CharArray>(Bank.BPA to "1234".toCharArray(), Bank.BANDEC to "12345".toCharArray(), MiTransfer to "4321".toCharArray())
        val decoded = BankKeys.decode(BankKeys.encode(keys))
        assertEquals(keys.keys, decoded.keys)
        keys.forEach { (bank, key) -> assertContentEquals(key, decoded.getValue(bank)) }
        assertFailsWith<IllegalArgumentException> { BankKeys.decode("BPA=12\n".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { BankKeys.decode("BPA=1234".toByteArray()) }
        assertFailsWith<IllegalArgumentException> { BankKeys.decode("MITRANSFER=12345".toByteArray() + 10) }
    }
}
