package dev.duardo.neotransfer.core

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class BankSmsParserTest {
    private val parser = BankSmsParser()

    @Test
    fun `outgoing confirmation contains amount and remaining balance`() {
        val result = assertIs<BankMessage.TransferSent>(parser.parse("PAGOxMOVIL", outgoing()))
        assertEquals(Bank.BANDEC, result.bank)
        assertEquals("0000XXXXXXXX0001", result.beneficiary)
        assertEquals(Money(BigDecimal("123.45"), Currency.CUP), result.amount)
        assertEquals(Money(BigDecimal("876.55"), Currency.CUP), result.remainingBalance)
        assertEquals("TEST001", result.reference)
    }

    @Test
    fun `balance preserves ledger and available separately`() {
        val result = assertIs<BankMessage.Balance>(parser.parse("PAGOxMOVIL", balance()))
        assertEquals(BigDecimal("1000.00"), result.accounts.single().ledger?.amount)
        assertEquals(BigDecimal("850.25"), result.accounts.single().available.amount)
    }

    @Test
    fun `incoming message has its own format without a bank or remaining balance`() {
        val result = assertIs<BankMessage.TransferReceived>(parser.parse("PAGOxMOVIL", """
            El titular del telefono 00000000 le ha realizado una transferencia a la cuenta 0000000000000002 de 100.00 CUP. Nro. Transaccion TEST002. Fecha: 22/9/2026.
        """.trimIndent()))
        assertEquals("0000000000000002", result.account)
        assertEquals("TEST002", result.reference)
        assertEquals(BigDecimal("100.00"), result.amount.amount)
    }

    @Test
    fun `authentication and recharge rejection are not transfer confirmations`() {
        assertIs<BankMessage.Authenticated>(parser.parse("PAGOxMOVIL", """
            Usted se ha autenticado en la plataforma de pagos moviles, en el Banco Bandec con la cuenta 000000XXXXXX0002, puede comenzar a utilizar nuestros servicios de pagos a traves del movil
        """.trimIndent()))
        assertIs<BankMessage.RechargeRejected>(parser.parse("PAGOxMOVIL", """
            Fallo la recarga del movil 00000000 alcanzo el monto limite de recarga permitido en 30 dias (360 CUP), puede recargar posterior al dia 28-09-2026.
        """.trimIndent()))
    }

    @Test
    fun `a balance marker not yet understood never becomes a positive balance`() {
        assertEquals(BankMessage.Unrecognized, parser.parse("PAGOxMOVIL", balance().replace("CR", "DR")))
    }

    @Test
    fun `unsupported sender and misleading success phrase cannot confirm a transfer`() {
        assertNull(parser.parse("PERSONA", outgoing()))
        assertEquals(BankMessage.Unrecognized, parser.parse("PAGOxMOVIL", outgoing().replace(
            "La Transferencia fue completada.", "La Transferencia NO fue completada.",
        )))
    }

    @Test
    fun `missing or invalid essential fields preserve an unrecognized result`() {
        listOf(
            outgoing().replace("Monto: 123.45 CUP", "Monto: 123.456 CUP"),
            outgoing().replace("Monto: 123.45 CUP", "Monto: -123.45 CUP"),
            outgoing().replace("Nro. Transaccion: TEST001", ""),
            balance().replace("850.25", "NaN"),
        ).forEach { body -> assertEquals(BankMessage.Unrecognized, parser.parse("PAGOxMOVIL", body)) }
    }

    @Test
    fun `a confirmation without balance remains a valid confirmation`() {
        val result = assertIs<BankMessage.TransferSent>(parser.parse("PAGOxMOVIL", outgoing().replace(
            "Saldo restante: CR 876.55 CUP", "",
        )))
        assertNull(result.remainingBalance)
    }

    @Test
    fun `synthetic multibank and multiaccount formats retain their identities`() {
        val sent = assertIs<BankMessage.TransferSent>(parser.parse("PAGOxMOVIL", outgoing().replace("Bandec", "BPA")))
        assertEquals(Bank.BPA, sent.bank)
        val balances = assertIs<BankMessage.Balance>(parser.parse("PAGOxMOVIL", balance() + "\n0000XXXXXXXX0003; CR 20.00 ; CR 15.00 ;USD |"))
        assertEquals(listOf(Currency.CUP, Currency.USD), balances.accounts.map { it.available.currency })
    }

    @Test
    fun `BPA authentication identifies the bank without inventing an account`() {
        for (suffix in listOf("", "\n\n Informacion: \n\nAviso informativo de prueba.")) {
            val result = assertIs<BankMessage.Authenticated>(parser.parse("PAGOxMOVIL", bpaAuthentication() + suffix))
            assertEquals(Bank.BPA, result.bank)
            assertNull(result.account)
        }
        assertEquals(BankMessage.Unrecognized, parser.parse("PAGOxMOVIL", bpaAuthentication().replace("se ha autenticado", "no se ha autenticado")))
        assertEquals(BankMessage.Unrecognized, parser.parse("PAGOxMOVIL", bpaAuthentication() + " texto desconocido"))
    }

    @Test
    fun `BANDEC authentication with an empty account does not fabricate its identity`() {
        val body = bpaAuthentication().replace("Popular de Ahorro", "Bandec con la cuenta ")
        val result = assertIs<BankMessage.Authenticated>(parser.parse("PAGOxMOVIL", body))
        assertEquals(Bank.BANDEC, result.bank)
        assertNull(result.account)
        assertEquals(BankMessage.Unrecognized, parser.parse("PAGOxMOVIL", body.replace("cuenta ", "cuenta 123 ")))
    }

    @Test
    fun `BPA default available balance has neither card nor ledger`() {
        val result = assertIs<BankMessage.Balance>(parser.parse("PAGOxMOVIL", bpaDefaultBalance()))
        assertEquals(Bank.BPA, result.bank)
        val account = result.accounts.single()
        assertNull(account.account)
        assertNull(account.ledger)
        assertNull(account.label)
        assertEquals(Money(BigDecimal("725.50"), Currency.CUP), account.available)
        assertEquals(Currency.CUP, sourceCurrency(result.accounts, "0000"))
        assertNull(sourceCurrency(result.accounts, "0000000000000002"))
    }

    @Test
    fun `BPA account labels remain labels and do not become card numbers or ledger balances`() {
        val result = assertIs<BankMessage.Balance>(parser.parse("PAGOxMOVIL", bpaTableBalance()))
        assertEquals(Bank.BPA, result.bank)
        assertEquals(listOf("AAA-MN", "BBB.USD", "Ahorro"), result.accounts.map { it.label })
        assertEquals(listOf(Currency.CUP, Currency.USD, Currency.CUP), result.accounts.map { it.available.currency })
        assertEquals(listOf(BigDecimal("200.00"), BigDecimal("5.25"), BigDecimal("10.00")), result.accounts.map { it.available.amount })
        result.accounts.forEach { assertNull(it.account); assertNull(it.ledger) }
        assertNull(sourceCurrency(result.accounts, "0000"))
        assertNull(sourceCurrency(result.accounts, "0000000000000002"))
    }

    @Test
    fun `BPA unavailable signs malformed rows and unsupported amounts stay unrecognized`() {
        listOf(
            bpaDefaultBalance().replace("CR", "DB"),
            bpaDefaultBalance().replace("CR", "DR"),
            bpaDefaultBalance().replace("725.50", "-725.50"),
            bpaTableBalance().replace("200.00", "200.001"),
            bpaTableBalance().replace("200.00", "2E2"),
            bpaTableBalance().replace("200.00", "+200.00"),
            bpaTableBalance().replace("200.00", "NaN"),
            bpaTableBalance().replace("200.00", "DB 200.00"),
            bpaTableBalance().replace("BBB.USD", ""),
            bpaTableBalance().replace("5.25; USD", "5.25; EUR"),
            bpaTableBalance().replace("; Ahorro ;", "; Ahorro ; unexpected ;"),
        ).forEach { assertEquals(BankMessage.Unrecognized, parser.parse("PAGOxMOVIL", it), it) }
    }

    @Test
    fun `incoming transfer may end at its reference without a date`() {
        val body = "El titular del telefono 00000000 le ha realizado una transferencia a la cuenta 0000000000000002 de 100.00 CUP. Nro. Transaccion TEST002"
        val result = assertIs<BankMessage.TransferReceived>(parser.parse("PAGOxMOVIL", body))
        assertEquals("TEST002", result.reference)
        assertEquals("0000000000000002", result.account)
        assertEquals(Money(BigDecimal("100.00"), Currency.CUP), result.amount)
        assertEquals(BankMessage.Unrecognized, parser.parse("PAGOxMOVIL", body.replace("100.00", "-100.00")))
        assertEquals(BankMessage.Unrecognized, parser.parse("PAGOxMOVIL", body + " texto desconocido"))
    }

    private fun bpaAuthentication() = "Usted se ha autenticado en la plataforma de pagos moviles, en el Banco Popular de Ahorro, puede comenzar a utilizar nuestros servicios de pagos a traves del movil"

    private fun bpaDefaultBalance() = """
        Banco Popular de Ahorro:  La consulta de saldo fue completada.

        Saldo Disponible: CR 725.50 CUP
    """.trimIndent()

    // Labels are synthetic; column positions and the lack of account/ledger values reproduce the observed format.
    private fun bpaTableBalance() = """
        Banco Popular de Ahorro La consulta de saldo fue completada.

        Nombre Cuenta;Nro Cuenta; Saldo Disponible; Moneda

        ; AAA-MN ; 200.00; CUP

        ; BBB.USD ; 5.25; USD

        ; Ahorro ; 10.00; CUP
    """.trimIndent()

    private fun outgoing() = """
        Banco Bandec:  La Transferencia fue completada.

        Fecha: 22/9/2026
        Beneficiario: 0000XXXXXXXX0001
        Ordenante: CUP
        Monto: 123.45 CUP
        Nro. Transaccion: TEST001
        Saldo restante: CR 876.55 CUP
    """.trimIndent()

    private fun balance() = """
        Banco Bandec La consulta de saldo fue completada.

        Cuenta;Saldo Contable;Saldo Disponible;Moneda
        0000XXXXXXXX0002; CR 1000.00 ; CR 850.25 ;CUP |
    """.trimIndent()
}
