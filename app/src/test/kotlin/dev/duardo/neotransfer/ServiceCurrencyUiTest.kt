package dev.duardo.neotransfer

import dev.duardo.neotransfer.core.*
import kotlin.test.Test
import kotlin.test.assertEquals

class ServiceCurrencyUiTest {
    @Test fun `bank transfer retains the reviewed amount currency when its source differs`() {
        val spec = BankingOperations.all.single { it.id == "bpa.transfer" }
        assertEquals(Currency.USD, serviceCurrency(spec, ProviderIdentity.forBank(Bank.BPA), Currency.CUP, Currency.USD))
    }

    @Test fun `balance query keeps the selected account currency`() {
        val spec = BankingOperations.all.single { it.id == "bpa.balance" }
        assertEquals(Currency.CUP, serviceCurrency(spec, ProviderIdentity.forBank(Bank.BPA), Currency.CUP, Currency.USD))
        val bandec = BankingOperations.all.single { it.id == "bandec.balance" }
        assertEquals(null, serviceCurrency(bandec, ProviderIdentity.forBank(Bank.BANDEC), Currency.CUP, Currency.USD))
    }
}
