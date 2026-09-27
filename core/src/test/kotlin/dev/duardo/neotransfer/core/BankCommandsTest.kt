package dev.duardo.neotransfer.core

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BankCommandsTest {
    private val commands = BankCommands()

    @Test
    fun `direct builders reject retired CUC while USD keeps its original wire code`() {
        val source = SourceSelector.Explicit("0000000000000001")
        val retired = Money(BigDecimal.ONE, Currency.CUC)
        assertFailsWith<IllegalArgumentException> { commands.bpaBalance(Currency.CUC) }
        assertFailsWith<IllegalArgumentException> { commands.bpaBalance(Currency.CUC, source.account) }
        assertFailsWith<IllegalArgumentException> { commands.balance(Bank.BPA, Currency.CUC, source) }
        assertFailsWith<IllegalArgumentException> { commands.recentOperations(Bank.BPA, Currency.CUC, source) }
        for (bank in listOf(Bank.BPA, Bank.BANDEC, Bank.BANMET)) {
            assertFailsWith<IllegalArgumentException> {
                commands.transfer(TransferRequest(bank, "0000000000000002", retired))
            }
        }
        val qr = QrPayment.parse(mapOf("id_transaccion" to "OLD-CURRENCY", "importe" to "1.00",
            "moneda" to "CUC", "numero_proveedor" to "123"))
        assertFailsWith<IllegalArgumentException> {
            commands.qrPayment(Bank.BPA, "1234".toCharArray(), qr, retired, "", sequence = "001")
        }
        val payload = ParameterCodec().encode(listOf("3", "0000"), 0)
        assertEquals("*444*46*$payload*1260416#", commands.bpaBalance(Currency.USD, seed = 0).valueForTransport())
        assertEquals("2", CurrencyContract.BANK.code(Currency.CUC))
    }

    @Test
    fun `bank authentication envelopes match independent reference examples`() {
        assertEquals("*444*40*000217*047527*1260416#", commands.authenticate(Bank.BPA, "1234".toCharArray(), 0).valueForTransport())
        assertEquals("*444*40*000257*0545323*1260416#", commands.authenticate(Bank.BANDEC, "12345".toCharArray(), 0).valueForTransport())
    }

    @Test
    fun `balance without parameters has no version while selected BPA balance does`() {
        assertEquals("*444*46#", commands.defaultBalance().valueForTransport())
        assertEquals("*444*46*00014*0449*1260416#", commands.bpaBalance(Currency.CUP, seed = 0).valueForTransport())
        assertEquals("*444*46*00016*16545556219109764*1260416#", commands.bpaBalance(Currency.CUP, "0000000000000123", 0).valueForTransport())
    }

    @Test
    fun `modern transfer preserves bank-specific currency fields and empty phone sentinel`() {
        val amount = Money(BigDecimal("10.50"), Currency.CUP)
        val bandec = TransferRequest(Bank.BANDEC, "0000000000000001", amount)
        val bpa = TransferRequest(Bank.BPA, "0000000000000001", amount)
        val codec = ParameterCodec()
        val bandecExpected = codec.encode(listOf("0000000000000001", "10.50", "0", "0", "0000", "0000", "0", "0000", "0"), 99)
        val bpaExpected = codec.encode(listOf("0000000000000001", "10.50", "1", "1", "0000", "0000", "0", "0000", "0"), 99)
        assertEquals("*444*45*$bandecExpected*1260416#", commands.transfer(bandec, 99).valueForTransport())
        assertEquals("*444*45*$bpaExpected*1260416#", commands.transfer(bpa, 99).valueForTransport())
    }

    @Test
    fun `input validation and safe string representation protect credentials`() {
        assertFailsWith<IllegalArgumentException> { commands.authenticate(Bank.BPA, "12345".toCharArray()) }
        assertFailsWith<IllegalArgumentException> { commands.authenticate(Bank.BANDEC, "1234*".toCharArray()) }
        val command = commands.authenticate(Bank.BPA, "1234".toCharArray(), 0)
        assertEquals("UssdCommand(service=40)", command.toString())
        assertFalse(command.toString().contains(command.valueForTransport()))
        assertFailsWith<IllegalArgumentException> { TransferRequest(Bank.BPA, "1", Money(BigDecimal.ONE, Currency.CUP)) }
        assertFailsWith<IllegalArgumentException> { TransferRequest(Bank.BPA, "0".repeat(16), Money(BigDecimal.ZERO, Currency.CUP)) }
    }

    @Test
    fun `bank session expires precisely and cannot cross bank SIM or clock boundaries`() {
        val start = Instant.parse("2026-09-22T12:00:00Z")
        val session = BankSession(Bank.BPA, 1, start)
        assertTrue(session.isValidFor(Bank.BPA, 1, start.plusSeconds(3599)))
        assertFalse(session.isValidFor(Bank.BPA, 1, start.plusSeconds(3600)))
        assertFalse(session.isValidFor(Bank.BANDEC, 1, start))
        assertFalse(session.isValidFor(Bank.BPA, 2, start))
        assertFalse(session.isValidFor(Bank.BPA, 1, start.minusSeconds(1)))
    }
}
