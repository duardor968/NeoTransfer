package dev.duardo.neotransfer

import dev.duardo.neotransfer.core.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test fun `CUC is absent from new products and never inferred from a saved source`() {
        val identity = ProviderIdentity.forBank(Bank.BPA)
        val spec = BankingOperations.all.single { it.id == "bpa.transfer" }
        assertEquals(listOf(Currency.CUP, Currency.USD), productCurrencies(identity))
        assertTrue(OperationCatalog.all.none { Currency.CUC in it.currencies })
        assertTrue(OperationCatalog.all.flatMap { it.fields }.none { field ->
            field.options.any { it.label.contains("CUC", ignoreCase = true) || field.key == "amountCurrency" && it.value == "2" }
        })
        assertEquals(null, serviceCurrency(spec, identity, Currency.CUC, null))
        assertEquals(Currency.USD, serviceCurrency(spec, identity, Currency.CUC, Currency.USD))
        assertTrue(OperationCatalog.validate(ServiceRequest("bank.qr", identity, currency = Currency.CUC))
            .any { it.fieldKey == "currency" })
    }
}
