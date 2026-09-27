package dev.duardo.neotransfer.core

import java.time.LocalDate
import kotlin.test.*

class BankHistoryTest {
    private val parser = BankSmsParser()

    @Test fun `historical CUC statement remains readable without conversion`() {
        val result = assertIs<BankHistory>(parser.parse("PAGOxMOVIL", statement().replace(";CUP;", ";CUC;")))
        assertEquals(listOf(Currency.CUC, Currency.CUC, Currency.CUC), result.entries.map { it.amount.currency })
    }

    @Test fun `BANDEC statement preserves day direction reference and service without inventing account or clock`() {
        val result = assertIs<BankHistory>(parser.parse("PAGOxMOVIL", statement()))
        assertEquals(Bank.BANDEC, result.bank)
        assertEquals(3, result.entries.size)
        assertEquals(LocalDate.of(2026, 9, 20), result.entries[0].date)
        assertEquals(HistoryDirection.CREDIT, result.entries[0].direction)
        assertEquals("FIXTURE01", result.entries[0].reference)
        assertEquals("Banca Movil Transferencia Ref: FIXTURE01", result.entries[0].service)
        assertEquals(HistoryDirection.DEBIT, result.entries[1].direction)
        assertNull(result.entries[2].reference)
        assertTrue(result.entries.all { it.transactionNumber == null })
        assertNull(FinancialMovement.from(result))
    }

    @Test fun `statement credit and debit sharing a reference remain two distinct rows`() {
        val result = assertIs<BankHistory>(parser.parse("PAGOxMOVIL", statement().replace("FIXTURE02", "FIXTURE01")))
        assertEquals(listOf(HistoryDirection.CREDIT, HistoryDirection.DEBIT), result.entries.take(2).map { it.direction })
        assertEquals(2, result.entries.count { it.reference == "FIXTURE01" })
    }

    @Test fun `unsupported malformed or ambiguous statement rows reject the whole statement`() {
        listOf(
            statement().replace("20/09/2026", "31/02/2026"),
            statement().replace(";123.45;", ";123.456;"),
            statement().replace(";123.45;", ";-123.45;"),
            statement().replace(";Cr;", ";Unknown;"),
            statement().replace(";CUP;", ";EUR;"),
            statement().replace(";CUP; |", ";CUP;unexpected |"),
            statement().replace("Ref: FIXTURE01", "Ref: FIXTURE01 Ref: OTHER"),
            statement().replace("Ref: FIXTURE01", "Ref:"),
            statement().replace("Banco Bandec", "Banco BFI"),
        ).forEach { assertEquals(BankMessage.Unrecognized, parser.parse("PAGOxMOVIL", it), it) }
        assertNull(parser.parse("PERSONA", statement()))
    }

    @Test fun `an unobserved empty statement is not a successful financial result`() {
        assertEquals(BankMessage.Unrecognized, parser.parse("PAGOxMOVIL", header()))
    }

    private fun header() = "Banco Bandec Ultimas operaciones.\n\nFecha;Servicio;Operacion;Monto;Moneda;NoTransaccion\n\n"
    private fun statement() = header() + "20/09/2026;Banca Movil Transferencia Ref: FIXTURE01;Cr;123.45;CUP; |\n" +
        "21/09/2026;Transferencia ATM Ref: FIXTURE02;Db;45.67;CUP; |\n" +
        "22/09/2026;Intereses;Cr;0.10;CUP; |"
}
