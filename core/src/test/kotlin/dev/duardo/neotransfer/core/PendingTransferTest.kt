package dev.duardo.neotransfer.core

import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PendingTransferTest {
    private val startedAt = Instant.parse("2026-09-22T12:00:00Z")
    private val amount = Money(BigDecimal("123.45"), Currency.CUP)
    private val request = TransferRequest(Bank.BANDEC, "0000000000000001", amount)

    @Test
    fun `after ten seconds request a balance once but leave transfer pending`() {
        val pending = pending()
        assertFalse(pending.takeBalanceRefresh(startedAt.plusSeconds(9), true))
        assertFalse(pending.takeBalanceRefresh(startedAt.plusSeconds(10), false))
        assertTrue(pending.takeBalanceRefresh(startedAt.plusSeconds(10), true))
        assertFalse(pending.takeBalanceRefresh(startedAt.plusSeconds(11), true))
        assertNull(pending.confirmation())
        assertTrue(pending.accept(receipt(), startedAt.plusSeconds(90), 1))
    }

    @Test
    fun `balance and authentication messages never confirm payment`() {
        val pending = pending()
        assertFalse(pending.accept(BankMessage.Authenticated(Bank.BANDEC, request.sourceAccount), startedAt, 1))
        assertFalse(pending.accept(BankMessage.Balance(Bank.BANDEC, emptyList()), startedAt, 1))
        assertNull(pending.confirmation())
    }

    @Test
    fun `correlation requires bank value destination time and SIM`() {
        val pending = pending()
        assertFalse(pending.accept(receipt().copy(bank = Bank.BPA), startedAt, 1))
        assertFalse(pending.accept(receipt().copy(beneficiary = "0000XXXXXXXX0002"), startedAt, 1))
        assertFalse(pending.accept(receipt().copy(beneficiary = "XXXXXXXXXXXX0001"), startedAt, 1))
        assertFalse(pending.accept(receipt().copy(amount = Money(BigDecimal("1"), Currency.CUP)), startedAt, 1))
        assertFalse(pending.accept(receipt(), startedAt.minusSeconds(1), 1))
        assertFalse(pending.accept(receipt(), startedAt, 2))
        assertFalse(pending.accept(receipt(), startedAt, null))
        assertTrue(pending.accept(receipt(), startedAt, 1))
        assertFalse(pending.accept(receipt(), startedAt, 1))
        assertEquals("TEST001", pending.confirmation()?.reference)
    }

    @Test
    fun `receipt with remaining balance avoids an extra query`() {
        val pending = pending()
        assertTrue(pending.accept(receipt().copy(remainingBalance = amount), startedAt, 1))
        assertFalse(pending.takeBalanceRefresh(startedAt.plusSeconds(20), true))
    }

    @Test
    fun `receipt without balance triggers refresh without waiting ten seconds`() {
        val pending = pending()
        assertTrue(pending.accept(receipt(), startedAt.plusSeconds(1), 1))
        assertTrue(pending.takeBalanceRefresh(startedAt.plusSeconds(1), true))
    }

    private fun pending() = PendingTransfer(request, startedAt, 1)
    private fun receipt() = BankMessage.TransferSent(Bank.BANDEC, "0000XXXXXXXX0001", amount, "TEST001", null)
}
