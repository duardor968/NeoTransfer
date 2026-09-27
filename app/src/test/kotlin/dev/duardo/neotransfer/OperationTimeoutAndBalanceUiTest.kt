package dev.duardo.neotransfer

import dev.duardo.neotransfer.core.Bank
import dev.duardo.neotransfer.data.BalanceRecord
import dev.duardo.neotransfer.data.OperationRecord
import dev.duardo.neotransfer.data.OperationStatus
import dev.duardo.neotransfer.data.RegistrationRecord
import dev.duardo.neotransfer.data.WalletSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OperationTimeoutAndBalanceUiTest {
    private val registration = RegistrationRecord("reg", "owner", "BANDEC", "PERSONAL", "02", 7, null, "BANDEC")

    private fun state(balances: List<BalanceRecord>) = AppUiState(
        unlocked = true, hasCredentials = true, bank = Bank.BANDEC, subscription = 7,
        sims = emptyList(), configuredBanks = setOf(Bank.BANDEC), busy = false,
        accounts = emptyList(), balanceAt = null, history = emptyList(), pending = null,
        confirmed = null, recipients = emptyList(), notice = null, permissions = false,
        cameraPermission = false, contact = null,
        wallet = WalletSnapshot(registrations = listOf(registration), balances = balances),
    )

    private fun balance(id: String, at: Long, amount: String, account: String? = "0000000000000001",
                        currency: String = "CUP", sentAt: Long? = null, bank: String = "02", sim: Int = 7) =
        BalanceRecord(id, bank, sim, at, account, amount, currency, sentAt = sentAt)

    @Test fun onlyTimedOutUncertainRequestsGetTheTimeoutTitle() {
        val operation = OperationRecord("op", "TRANSFER", "02", 7, "0000000000000002", "10.00", "CUP", 1L)
        assertEquals("Resultado por comprobar", operation.copy(status = OperationStatus.UNCERTAIN).statusTitle())
        assertEquals("Tiempo de espera agotado", operation.copy(status = OperationStatus.UNCERTAIN, timeoutAt = 30L).statusTitle())
        assertEquals("Confirmada", operation.copy(status = OperationStatus.CONFIRMED, timeoutAt = 30L).statusTitle())
    }

    @Test fun newerObservationOfSameAccountAndCurrencyFeedsRegistrationProduct() {
        val old = balance("old", 100L, "10.00", sentAt = 90L)
        val fresh = balance("sms", 200L, "20.00", sentAt = 190L)
        val product = walletProducts(state(listOf(old, fresh))).single()
        assertEquals("20.00", assertNotNull(product.available).amount.toPlainString())
        assertEquals(190L, product.balanceAt?.toEpochMilli())
        assertEquals("0000000000000001", product.number)
        assertEquals("10.00", assertNotNull(walletProducts(state(listOf(old))).single().available).amount.toPlainString())
    }

    @Test fun differentAccountsCurrenciesOrMissingAccountRemainAmbiguous() {
        val old = balance("old", 100L, "10.00")
        val alternatives = listOf(
            balance("other-account", 200L, "20.00", account = "0000000000000002"),
            balance("other-currency", 200L, "20.00", currency = "USD"),
            balance("missing-account", 200L, "20.00", account = null),
        )
        alternatives.forEach { other -> assertNull(walletProducts(state(listOf(old, other))).single().available) }
    }

    @Test fun delayedOlderSmsDoesNotHideOrOverwriteNewerEvidence() {
        val delayedOld = balance("old", 300L, "10.00", sentAt = 100L)
        val receivedFirst = balance("new", 200L, "20.00", sentAt = 190L)
        val product = walletProducts(state(listOf(delayedOld, receivedFirst))).single()
        assertEquals("20.00", assertNotNull(product.available).amount.toPlainString())
        assertEquals(190L, product.balanceAt?.toEpochMilli())
    }

    @Test fun missingOrInvalidSmscTimeCannotOrderDifferentBalances() {
        val dated = balance("dated", 200L, "20.00", sentAt = 190L)
        assertNull(walletProducts(state(listOf(balance("legacy", 300L, "10.00"), dated))).single().available)
        assertNull(walletProducts(state(listOf(balance("invalid", 300L, "10.00", sentAt = 301L), dated))).single().available)
        assertNull(walletProducts(state(listOf(balance("a", 100L, "10.00"),
            balance("b", 200L, "20.00")))).single().available)
    }

    @Test fun identicalLegacyEvidenceDeduplicatesButTiedSmscConflictsDoNot() {
        val duplicate = walletProducts(state(listOf(balance("a", 100L, "10.00"),
            balance("b", 200L, "10.00")))).single()
        assertEquals("10.00", assertNotNull(duplicate.available).amount.toPlainString())
        assertNull(walletProducts(state(listOf(balance("a", 200L, "10.00", sentAt = 190L),
            balance("b", 300L, "20.00", sentAt = 190L)))).single().available)
    }

    @Test fun bankAndSimMustMatchEvenWhenRegistrationIdDoes() {
        val wrongBank = balance("bank", 100L, "10.00", bank = "01").copy(registrationId = "reg")
        val wrongSim = balance("sim", 100L, "10.00", sim = 8).copy(registrationId = "reg")
        assertNull(walletProducts(state(listOf(wrongBank, wrongSim))).single().available)
    }
}
