package dev.duardo.neotransfer.core

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class BankingOperationsTest {
    private val commands = BankCommands()
    private val account = "0000000000000123"
    private val source = SourceSelector.Explicit(account)

    private fun envelope(service: Int, vararg parameters: String, seed: Int = 0): String =
        "*444*$service*${ParameterCodec().encode(parameters.toList(), seed)}*1260416#"

    @Test
    fun `new bank identities preserve legacy agency and pin semantics`() {
        assertEquals("01", Bank.BPA.code)
        assertEquals(5, Bank.BANDEC.pinLength)
        assertEquals(envelope(40, "03", "1234"), commands.authenticate(Bank.BANMET, "1234".toCharArray(), 0).valueForTransport())
        assertFailsWith<IllegalArgumentException> { commands.authenticate(Bank.BFI, "1234".toCharArray(), 0) }
        assertFailsWith<IllegalArgumentException> { commands.authenticate(Bank.BFI, "12345".toCharArray()) }
        assertEquals(ProviderIdentity(ProviderId.BPA), ProviderIdentity.forBank(Bank.BPA))
        assertNotEquals(ProviderIdentity(ProviderId.MITRANSFER), ProviderIdentity(ProviderId.MITRANSFER, ProfileId.CLASSIC))
    }

    @Test
    fun `currency belongs to the operation contract not the account`() {
        assertEquals(listOf(Currency.CUP, Currency.USD), CurrencyContract.BANK.active)
        assertEquals(listOf(Currency.CUP, Currency.CUC, Currency.USD), CurrencyContract.BANK.supported)
        assertEquals("2", CurrencyContract.BANK.code(Currency.CUC))
        assertEquals("3", CurrencyContract.BANK.code(Currency.USD))
        assertFailsWith<IllegalArgumentException> { CurrencyContract.BANK.code(Currency.EUR) }
        assertEquals("12345678901234567890", SourceSelector.Explicit("12345678901234567890").wireValue)
        assertEquals(SourceSelector.Default, SourceSelector.fromLegacy("0000"))
        assertFailsWith<IllegalArgumentException> { commands.balance(Bank.BFI, source = SourceSelector.Explicit("12345")) }
    }

    @Test fun `new bank transfer rejects CUC while USD retains wire code three`() {
        val identity = ProviderIdentity.forBank(Bank.BPA)
        val values = mapOf("destination" to "0000000000000001", "amount" to "10.50")
        val obsolete = ServiceRequest("bpa.transfer", identity, currency = Currency.CUC, values = values)
        assertTrue(BankingOperations.validate(obsolete).any { it.fieldKey == "currency" })
        assertFailsWith<IllegalArgumentException> { BankingOperations.encode(obsolete, 0) }
        val usd = ServiceRequest("bpa.transfer", identity, currency = Currency.USD, values = values)
        assertEquals(emptyList(), BankingOperations.validate(usd))
        assertEquals(envelope(45, "0000000000000001", "10.50", "3", "3", "0000", "0000", "0", "0000", "0"),
            BankingOperations.encode(usd, 0).valueForTransport())
    }

    @Test
    fun `balance uses only the source selector each bank actually exposes`() {
        assertEquals("*444*46#", commands.balance(Bank.BANDEC).valueForTransport())
        assertEquals(envelope(46, "3", "0000"), commands.balance(Bank.BANMET, Currency.USD, seed = 0).valueForTransport())
        assertFailsWith<IllegalArgumentException> { commands.balance(Bank.BFI, source = source, seed = 0) }
        assertEquals(envelope(46, "0", account), commands.balance(Bank.BPA, source = source, seed = 0).valueForTransport())
        assertFailsWith<IllegalArgumentException> { commands.balance(Bank.BANDEC, source = source) }
        assertFailsWith<IllegalArgumentException> { commands.balance(Bank.BFI, Currency.USD) }
        assertFailsWith<IllegalArgumentException> { commands.balance(Bank.BPA) }
    }

    @Test
    fun `service 48 preserves BPA zero and BANMET filter contracts`() {
        for (seed in 0..99) {
            assertEquals(envelope(48, "0", "1", "0000", seed = seed),
                commands.recentOperations(Bank.BPA, Currency.CUP, seed = seed).valueForTransport())
            assertEquals(envelope(48, "0", "1", "0000", seed = seed),
                commands.recentOperations(Bank.BANDEC, seed = seed).valueForTransport())
            assertEquals(envelope(48, "0", "0", account, seed = seed),
                commands.recentOperations(Bank.BANDEC, source = source, seed = seed).valueForTransport())
            assertEquals(envelope(48, "12", "0", account, seed = seed),
                commands.recentOperations(Bank.BANMET, source = source, filter = BankOperationFilter.ONLINE_PAYMENT, seed = seed).valueForTransport())
        }
        assertFailsWith<IllegalArgumentException> { commands.recentOperations(Bank.BANMET, Currency.CUP) }
        assertFailsWith<IllegalArgumentException> { commands.recentOperations(Bank.BPA, Currency.CUP, filter = BankOperationFilter.ALL) }
    }

    @Test
    fun `58 60 63 and 89 retain optional source omission and bank scopes`() {
        assertEquals("*444*58#", commands.allAccounts(Bank.BANDEC).valueForTransport())
        assertEquals("*444*58#", commands.allAccounts(Bank.BANMET).valueForTransport())
        assertEquals(envelope(60, account), commands.associateAccount(Bank.BPA, account, 0).valueForTransport())
        assertEquals(envelope(63, "10"), commands.recentPayments(Bank.BANDEC, BankPaymentFilter.ONLINE_PAYMENT, seed = 0).valueForTransport())
        assertEquals(envelope(63, "4", account), commands.recentPayments(Bank.BANDEC, BankPaymentFilter.TRANSFERS, source, 0).valueForTransport())
        assertEquals(envelope(89, "AB1234", "0000"), commands.paymentStatus(Bank.BANDEC, "AB1234", seed = 0).valueForTransport())
        assertFailsWith<IllegalArgumentException> { commands.allAccounts(Bank.BFI) }
        assertFailsWith<IllegalArgumentException> { commands.associateAccount(Bank.BANMET, account) }
        assertFailsWith<IllegalArgumentException> { commands.recentPayments(Bank.BPA, BankPaymentFilter.ALL) }
        assertFailsWith<IllegalArgumentException> { commands.paymentStatus(Bank.BANDEC, "123*456") }
    }

    @Test
    fun `BFI identifiers remain readable but no BFI operation can be prepared or encoded`() {
        assertEquals("05", Bank.valueOf("BFI").code)
        assertEquals(Bank.BFI, ProviderId.valueOf("BFI").bank)
        assertTrue(BankingOperations.all.none { it.identities.any { identity -> identity.provider == ProviderId.BFI } })
        for (suffix in listOf("authenticate", "balance", "recent-operations", "disconnect", "transfer.joint-request", "transfer.individual")) {
            val request = ServiceRequest("bfi.$suffix", ProviderIdentity(ProviderId.BFI), source, Currency.USD)
            assertTrue(BankingOperations.validate(request).any { it.fieldKey == "operation" })
            assertFailsWith<IllegalArgumentException> { BankingOperations.encode(request, 0) }
        }
        assertFailsWith<IllegalArgumentException> { commands.recentOperations(Bank.BFI, source = source) }
        assertFailsWith<IllegalArgumentException> {
            commands.transfer(TransferRequest(Bank.BFI, "0000000000000001", Money("10.50".toBigDecimal(), Currency.USD)))
        }
        assertEquals(setOf(ProviderId.BPA, ProviderId.BANDEC, ProviderId.BANMET),
            BankingOperations.all.flatMap { it.identities }.map { it.provider }.toSet())
    }

    @Test
    fun `catalog validates provider profile fields and currency before review`() {
        assertEquals(BankingOperations.all.size, BankingOperations.all.map { it.id }.toSet().size)
        assertTrue(BankingOperations.all.all { it.evidence.isNotEmpty() })
        val correct = ServiceRequest("bandec.recent-operations", ProviderIdentity.forBank(Bank.BANDEC), source)
        assertTrue(BankingOperations.validate(correct).isEmpty())
        assertEquals(commands.recentOperations(Bank.BANDEC, source = source, seed = 0).valueForTransport(),
            BankingOperations.encode(correct, 0).valueForTransport())
        val crossBank = ServiceRequest("bandec.recent-operations", ProviderIdentity.forBank(Bank.BPA), source)
        assertTrue(BankingOperations.validate(crossBank).any { it.fieldKey == "operation" })
        assertFailsWith<IllegalArgumentException> { BankingOperations.encode(crossBank) }
        val pin = ServiceRequest("bpa.authenticate", ProviderIdentity.forBank(Bank.BPA), values = mapOf("pin" to "1234"))
        assertTrue(BankingOperations.all.single { it.id == pin.operationId }.fields.single().sensitive)
        assertFalse(pin.toString().contains("1234"))
    }

    @Test
    fun `provider session cannot cross registration line profile or time boundaries`() {
        val now = Instant.parse("2026-09-23T12:00:00Z")
        val identity = ProviderIdentity(ProviderId.MITRANSFER, ProfileId.CLASSIC)
        val session = ProviderSession(identity, "registration-1", 7, now)
        assertTrue(session.isValidFor(identity, "registration-1", 7, now.plusSeconds(3599)))
        assertFalse(session.isValidFor(identity, "registration-2", 7, now))
        assertFalse(session.isValidFor(identity, "registration-1", 8, now))
        assertFalse(session.isValidFor(identity.copy(profile = ProfileId.PERSONAL), "registration-1", 7, now))
        assertFalse(session.isValidFor(identity, "registration-1", 7, now.plusSeconds(3600)))
        assertFalse(session.isValidFor(identity, "registration-1", 7, now.minusSeconds(1)))
    }
}
