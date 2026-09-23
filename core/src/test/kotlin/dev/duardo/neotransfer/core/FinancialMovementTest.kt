package dev.duardo.neotransfer.core

import java.math.BigDecimal
import kotlin.test.*

class FinancialMovementTest {
    private val parser = BankSmsParser()
    @Test fun `administrative messages and failed requests are not transactions`() {
        assertNull(FinancialMovement.from(BankMessage.Authenticated(Bank.BANDEC, "0000XXXXXX000001")))
        assertNull(FinancialMovement.from(BankMessage.Balance(Bank.BPA, emptyList())))
        assertNull(FinancialMovement.from(BankMessage.RechargeRejected("50000000")))
        assertNull(FinancialMovement.from(BankMessage.Unrecognized))
    }
    @Test fun `preserve the whole account and sender without inventing bank`() {
        val sent = FinancialMovement.from(BankMessage.TransferSent(Bank.BANDEC, "123456XXXXXX7890", Money(BigDecimal("10.00"), Currency.CUP), "EXAMPLE01", null))!!
        assertEquals("123456XXXXXX7890", sent.party)
        assertTrue(sent.matches("7890 example01"))
        val incoming = FinancialMovement.from(BankMessage.TransferReceived("0000000000000001", "5350000000", sent.amount, "EXAMPLE02"))!!
        assertNull(incoming.bank)
        assertEquals("5350000000", incoming.party)
        assertEquals("0000000000000001", incoming.account)
    }
    @Test fun `purchase history uses the amount paid rather than nominal amount`() {
        val receipt = parser.parse("PAGOxMOVIL", "Pago completado.\nFecha: 22/09/2026\nEntidad: Comercio de prueba\nId Compra: ORDER01\nImporte: 100.00 CUP\nImporte pagado: 95.00 CUP\nNo. Transaccion: EXAMPLE03\nSaldo disponible: DB 500.00 CUP")
        val movement = FinancialMovement.from(receipt!!)!!
        assertEquals(MovementKind.PAYMENT, movement.kind)
        assertEquals(BigDecimal("95.00"), movement.amount.amount)
        assertEquals("Comercio de prueba", movement.party)
        assertEquals("ORDER01", movement.purchaseId)
        assertEquals("EXAMPLE03", movement.reference)
        assertNull(movement.bank)
        assertEquals(BankMessage.Unrecognized, parser.parse("PAGOxMOVIL", "Pago completado.\nImporte pagado: 100.00 CUP"))
    }
    @Test fun `recharge parses one-line receipt with separate credited and paid amounts`() {
        val receipt = parser.parse("PAGOxMOVIL", "La recarga se realizo con exito. Saldo a acreditar: 200 CUP. Saldo acreditado: 200 CUP. Monto Pagado: 195 CUP. Telefono: 50000000. Id transaccion: EXAMPLE04. Saldo Restante: DB 12.\nGracias por utilizar nuestros servicios, ETECSA.")
        val movement = FinancialMovement.from(receipt!!)!!
        assertEquals(MovementKind.RECHARGE, movement.kind)
        assertEquals(BigDecimal("195"), movement.amount.amount)
        assertEquals("50000000", movement.party)
        assertEquals("EXAMPLE04", movement.reference)
    }
}
