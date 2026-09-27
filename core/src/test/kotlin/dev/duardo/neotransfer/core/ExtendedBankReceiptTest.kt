package dev.duardo.neotransfer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtendedBankReceiptTest {
    private val parser = BankSmsParser()

    @Test fun `Metro transfer retains its bank and does not imply credit at destination`() {
        val body = "Banco Metropolitano: La Transferencia fue completada.\nBeneficiario: 0000XXXXXXXX0002\nMonto: 10.00 CUP\nNro. Transaccion: METRO01"
        val result = assertIs<BankMessage.TransferSent>(parser.parse("PAGOxMOVIL", body))
        assertEquals(Bank.BANMET, result.bank)
        assertNull(result.remainingBalance)
        assertEquals(MovementKind.SENT, FinancialMovement.from(result)?.kind)
        assertEquals(BankMessage.Unrecognized, parser.parse("PAGOxMOVIL", body.replace("fue completada", "NO fue completada")))
    }

    @Test fun `demonstrated bill templates preserve provider and actual paid value`() {
        listOf("Popular de Ahorro" to Bank.BPA, "Bandec" to Bank.BANDEC, "Metropolitano" to Bank.BANMET).forEach { (name, bank) ->
            val result = assertIs<BankMessage.BillPaymentCompleted>(parser.parse("PAGOxMOVIL", electricity(name)))
            assertEquals(bank, result.bank)
            assertEquals(BillService.ELECTRICITY, result.service)
            assertEquals("00000000001", result.account)
            assertEquals("90.00", result.paid?.amount?.toPlainString())
            assertNull(result.nominal)
            val movement = checkNotNull(FinancialMovement.from(result))
            assertEquals("90.00", movement.amount.amount.toPlainString())
            assertEquals(false, movement.amountIsNominal)
        }
    }

    @Test fun `telephone nominal and paid amounts are independent and missing paid stays unknown`() {
        val result = assertIs<BankMessage.BillPaymentCompleted>(parser.parse("PAGOxMOVIL", telephone()))
        assertEquals("100.00", result.nominal?.amount?.toPlainString())
        assertEquals("90.00", result.paid?.amount?.toPlainString())
        val nominalOnly = assertIs<BankMessage.BillPaymentCompleted>(parser.parse("PAGOxMOVIL", telephone().replace("Monto Pagado: 90.00 CUP\n", "")))
        assertNull(nominalOnly.paid)
        assertTrue(checkNotNull(FinancialMovement.from(nominalOnly)).amountIsNominal)
        val paidOnly = assertIs<BankMessage.BillPaymentCompleted>(parser.parse("PAGOxMOVIL", telephone().replace("Importe Factura: 100.00 CUP\n", "")))
        assertNull(paidOnly.nominal)
    }

    @Test fun `bill receipt missing account preserves absence and gas keeps its own type`() {
        val body = electricity("Metropolitano").replace("de electricidad", "del Gas").replace("Nro. Factura Pagada: 00000000001\n", "")
        val result = assertIs<BankMessage.BillPaymentCompleted>(parser.parse("PAGOxMOVIL", body))
        assertEquals(BillService.GAS, result.service)
        assertNull(result.account)
    }

    @Test fun `contradictory malformed and duplicated bill fields never confirm money`() {
        listOf(
            electricity("Metropolitano").replace("fue completado", "no fue completado"),
            electricity("Metropolitano").replace("90.00", "90.001"),
            electricity("Metropolitano").replace("90.00", "-90.00"),
            electricity("Metropolitano").replace("Nro. Transaccion: BILL01", ""),
            electricity("Metropolitano") + "\nImporte Pagado: 900.00 CUP",
            electricity("Metropolitano") + "\nNro. Transaccion: OTHER",
            telephone().replace("90.00 CUP", "90.00 USD"),
        ).forEach { assertEquals(BankMessage.Unrecognized, parser.parse("PAGOxMOVIL", it), it) }
    }

    @Test fun `unproven BFI and query reply formats stay unrecognized`() {
        assertEquals(BankMessage.Unrecognized, parser.parse("PAGOxMOVIL", electricity("BFI")))
        listOf(
            "Banco Bandec: Ultimas operaciones. Fecha;Importe;Referencia\n2026-09-23;10.00 CUP;REF01",
            "Banco Bandec: Todas las cuentas. Cuenta: 0000000000000001",
            "Banco Bandec: Estado del pago. Nro. Transaccion: REF01. Estado: Completado",
            "Banco Bandec: La cuenta 0000000000000001 fue seleccionada.",
        ).forEach { assertEquals(BankMessage.Unrecognized, parser.parse("PAGOxMOVIL", it), it) }
    }

    private fun electricity(bank: String) = "Banco $bank: El pago de la factura de electricidad fue completado.\nNro. Factura Pagada: 00000000001\nImporte Pagado: 90.00 CUP\nNro. Transaccion: BILL01"
    private fun telephone() = "Banco Bandec: El pago de la factura telefonica fue completado.\nNro. Factura Pagada: 00000000000001\nImporte Factura: 100.00 CUP\nMonto Pagado: 90.00 CUP\nNro. Transaccion: PHONE01"
}
