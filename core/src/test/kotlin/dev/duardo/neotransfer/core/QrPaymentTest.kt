package dev.duardo.neotransfer.core

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.*

class QrPaymentTest {
    @Test fun `800 original QR vectors preserve fields and packet envelopes`() {
        val rows = requireNotNull(javaClass.getResourceAsStream("/qr-vectors.tsv")).bufferedReader().use { it.readLines().drop(1) }
        assertEquals(800, rows.size)
        for (row in rows) {
            val cells = row.split('\t')
            val name = cells[0]; val seed = cells[1].toInt(); val service = cells[2].toInt()
            val bank = Bank.entries.single { it.code == cells[3] }
            val p = cells[4].split('*')
            val unit = if (p[4] == "3") Currency.USD else Currency.CUP
            val amount = Money(BigDecimal(p[3]), unit)
            val static = service == 31
            val qr = QrPayment(p[2], p[6], if (name.contains("editable")) Money(BigDecimal.ZERO, unit) else amount,
                if (name.contains("special")) "11200301" else p[7], if (name.contains("fixed_description")) p[8] else "",
                "", if (p.size == 12) p[11] else null, null, null)
            val actual = BankCommands().qrPayment(bank, p[1].toCharArray(), qr, amount, if (static && p[8] != "0000") p[8] else "",
                sourceAccount = p[if (static) 9 else 8], phone = if (p.size > (if (static) 10 else 9)) p[if (static) 10 else 9] else null,
                sequence = "705", seed = seed).map { it.valueForTransport() }
            val expected = if (cells[6].length <= 130) listOf(cells[6]) else cells[8].split('|').map { "$it#" }
            assertEquals(expected, actual, "$name seed=$seed")
            // Every vector also exercises the original packetizer independently of direct-vs-partial selection.
            assertEquals(cells[8].split('|').map { "$it#" }, partialCommands(service, bank, cells[5], "705").map { it.valueForTransport() })
        }
    }

    private fun fields(amount: String = "0") = mapOf("id_transaccion" to "ESTATICO-123", "numero_proveedor" to "123", "importe" to amount, "moneda" to "CUP")

    @Test fun `QR without extra is valid and static amount can be entered`() {
        val qr = QrPayment.parse(fields())
        assertTrue(qr.editableAmount); assertEquals(31, qr.service)
        assertEquals("1", qr.auxiliary)
        assertFailsWith<IllegalArgumentException> { BankCommands().qrPayment(Bank.BPA, "1234".toCharArray(), qr,
            Money(BigDecimal.ZERO, Currency.CUP), "", sequence = "705") }
    }

    @Test fun `fixed QR values cannot be changed after review`() {
        val qr = QrPayment.parse(fields("10.00") + ("descripcion" to "Compra"))
        assertFalse(qr.editableAmount)
        assertFailsWith<IllegalArgumentException> { BankCommands().qrPayment(Bank.BPA, "1234".toCharArray(), qr,
            Money(BigDecimal("11.00"), Currency.CUP), "Compra", sequence = "705") }
        assertFailsWith<IllegalArgumentException> { BankCommands().qrPayment(Bank.BPA, "1234".toCharArray(), qr,
            qr.amount, "Otra", sequence = "705") }
    }

    @Test fun `malformed extra and payload delimiters fail before submission`() {
        for (extra in listOf("", "not base64!", "YWJjZA==")) assertFailsWith<IllegalArgumentException> { QrPayment.parse(fields() + ("extra" to extra)) }
        assertFailsWith<IllegalArgumentException> { QrPayment.parse(fields() + ("numero_proveedor" to "123*45")) }
        for (amount in listOf("NaN", "1e2", "-5", "0.001", "1,50")) assertFailsWith<IllegalArgumentException> { QrPayment.parse(fields(amount)) }
    }

    @Test fun `validity includes both dates and rejects outside interval`() {
        val today = LocalDate.of(2026, 9, 22)
        val qr = QrPayment("ESTATICO-1", "123", Money(BigDecimal.ONE, Currency.CUP), "1", "", "", null, today, today)
        qr.validateDate(today)
        assertFailsWith<IllegalArgumentException> { qr.validateDate(today.minusDays(1)) }
        assertFailsWith<IllegalArgumentException> { qr.validateDate(today.plusDays(1)) }
    }
}
