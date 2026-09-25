package dev.duardo.neotransfer.core

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConsumerCatalogTest {
    @Test
    fun `removed providers and profiles remain readable but have no active catalog or encoder`() {
        assertEquals(ProviderId.BFI, ProviderId.valueOf("BFI"))
        assertEquals(ProfileId.CLASSIC_BUSINESS, ProfileId.valueOf("CLASSIC_BUSINESS"))
        assertEquals(ProfileId.AGENT, ProfileId.valueOf("AGENT"))
        val excluded = setOf(ProviderIdentity(ProviderId.BFI), ProviderIdentity(ProviderId.MITRANSFER, ProfileId.AGENT),
            ProviderIdentity(ProviderId.MITRANSFER, ProfileId.CLASSIC_BUSINESS))
        val all = ServiceOperations.all + WalletOperations.all + WalletQrOperations.all + BankManagementOperations.all + TelecomOperations.all
        assertTrue(all.none { it.identities.any(excluded::contains) })
        assertTrue(all.none { it.id.startsWith("wallet.agent.") || it.id.startsWith("wallet.business.") || it.id.startsWith("bfi.") || it.id.startsWith("wallet.ticket.") })
        assertTrue(all.none { it.id.startsWith("wallet.vote.") || it.id in setOf("wallet.ofa.reserve", "wallet.ofa.list", "wallet.ofa.query") })
        assertFailsWith<IllegalArgumentException> { BankManagementOperations.encode(ServiceRequest("bfi.register", ProviderIdentity(ProviderId.BFI))) }
        for (id in listOf("wallet.agent.request-code", "wallet.agent.mobile", "wallet.business.associate", "wallet.business.transfer", "wallet.ticket.buy",
            "wallet.ofa.reserve", "wallet.ofa.list", "wallet.ofa.query", "wallet.vote.free", "wallet.vote.paid"))
            assertFailsWith<IllegalArgumentException> { WalletOperations.encode(ServiceRequest(id, ProviderIdentity(ProviderId.MITRANSFER))) }
        val qr = QrPayment("TEST", "12", Money(BigDecimal("10.50"), Currency.CUP), "1", "", "", null, null, null)
        for (identity in excluded) {
            assertFailsWith<IllegalArgumentException> { WalletQrOperations.fromQr(identity, SourceSelector.Default, Currency.CUP, qr, qr.amount) }
            val command = WalletOperations.encode(ServiceRequest("wallet.balance", ProviderIdentity(ProviderId.MITRANSFER)))
            assertFailsWith<IllegalArgumentException> { command.commandsForTransport(identity, "1234") }
        }
    }

    @Test
    fun `consumer banks wallet and personal Clasica retain their operations`() {
        for (bank in listOf(ProviderId.BPA, ProviderId.BANDEC, ProviderId.BANMET))
            assertTrue(BankManagementOperations.all.any { it.id == "${bank.name.lowercase()}.register" && it.supports(ProviderIdentity(bank)) })
        val wallet = ProviderIdentity(ProviderId.MITRANSFER)
        val classic = ProviderIdentity(ProviderId.MITRANSFER, ProfileId.CLASSIC)
        assertEquals("*444*46#", WalletOperations.encode(ServiceRequest("wallet.balance", wallet)).valueForTransport())
        assertEquals("*222#", TelecomOperations.encode(ServiceRequest("cubacel.balance", ProviderIdentity(ProviderId.CUBACEL))).valueForTransport())
        assertTrue(WalletOperations.all.single { it.id == "wallet.mobile.cup" }.supports(wallet))
        assertTrue(WalletOperations.all.single { it.id == "wallet.ofa.contribution" }.supports(wallet))
        assertEquals(setOf(ProviderId.BANDEC, ProviderId.BANMET), ServiceOperations.all.single { it.id == "service.ofa.contribution" }.identities.map { it.provider }.toSet())
        assertTrue(WalletOperations.all.single { it.id == "wallet.classic.transfer" }.supports(classic))
        val qr = QrPayment("ESTATICO123", "12", Money(BigDecimal.ZERO, Currency.USD), "1", "", "", null, null, null)
        val request = WalletQrOperations.fromQr(classic, SourceSelector.Explicit("1234567890123456"), null, qr, Money(BigDecimal("10"), Currency.USD))
        assertEquals("wallet.classic.qr.static", request.operationId)
        assertEquals(emptyList(), WalletQrOperations.validate(request))
    }
}
