package dev.duardo.neotransfer.core

import java.util.Base64
import kotlin.test.*

class ServiceReceiptParserTest {
    @Test fun `service receipts preserve paid and nominal fields without calculating discounts`() {
        val rows = checkNotNull(javaClass.getResourceAsStream("/service-receipts.tsv")).bufferedReader().readLines()
        assertEquals(5, rows.size)
        rows.forEach { line ->
            val columns = line.split('\t')
            val body = String(Base64.getDecoder().decode(columns[5]), Charsets.UTF_8)
            val receipt = assertIs<BankMessage.ServicePaymentCompleted>(BankSmsParser().parse("PAGOxMOVIL", body))
            assertEquals(columns[1].toBigDecimal(), receipt.nominal.amount)
            assertEquals(columns[2].takeUnless { it == "null" }?.toBigDecimal(), receipt.paid?.amount)
            assertEquals(columns[3], receipt.reference)
            assertEquals(columns[4], receipt.bank.name)
            val movement = checkNotNull(FinancialMovement.from(receipt))
            assertEquals(receipt.paid == null, movement.amountIsNominal)
            assertEquals(receipt.paid ?: receipt.nominal, movement.amount)
        }
    }

    @Test fun `notifications failures and malformed paid amounts are not debit receipts`() {
        val valid = "Banco Popular de Ahorro: La cuenta Nauta Hogar: fixture@example.invalid ha sido pagada con 100.00 CUP. Monto Pagado: 90 CUP. Id Transaccion: TEST01."
        assertNotNull(parseServiceReceipt(valid))
        assertNull(parseServiceReceipt(valid.replace("90 CUP", "USD 90")))
        assertNull(parseServiceReceipt(valid.replace("90 CUP", "90 USD")))
        assertNull(parseServiceReceipt(valid.replace("Id Transaccion: TEST01.", "")))
        assertNull(parseServiceReceipt("Se ha pagado la cuenta nauta hogar fixture@example.invalid con 100.00 CUP. Nro. Transaccion: TEST01."))
        assertNull(parseServiceReceipt("Fallo el pago de la cuenta Nauta Hogar. $valid"))
    }
}
