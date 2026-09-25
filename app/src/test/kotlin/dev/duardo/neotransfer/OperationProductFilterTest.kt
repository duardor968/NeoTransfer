package dev.duardo.neotransfer

import dev.duardo.neotransfer.core.*
import dev.duardo.neotransfer.data.OperationRecord
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OperationProductFilterTest {
    private fun operation(source: String = "0000", parameters: Map<String, String> = emptyMap()) =
        OperationRecord("op", "TRANSFER", "02", 1, "0000000000000002", "10.00", "CUP", 1L,
            registrationId = "reg", source = source, parameters = parameters)

    @Test fun maskedDefaultAccountStillShowsItsDefaultRequests() {
        val product = WalletProductUi("registration:reg", "reg", ProviderIdentity(ProviderId.BANDEC),
            ProductKind.ACCOUNT, "Predeterminada", "0000XXXXXXXX0001", Currency.CUP, SourceSelector.Default)
        assertTrue(operationBelongsToProduct(operation(), product))
        assertFalse(operationBelongsToProduct(operation("0000000000000009"), product))
    }

    @Test fun savedProductIdentityWinsOverTheSameNumber() {
        val number = "0000000000000001"
        val product = WalletProductUi("card", "reg", ProviderIdentity(ProviderId.BANDEC), ProductKind.CARD,
            "Tarjeta", number, Currency.CUP, SourceSelector.Explicit(number))
        assertTrue(operationBelongsToProduct(operation(number, mapOf("walletProductId" to "card")), product))
        assertFalse(operationBelongsToProduct(operation(number, mapOf("walletProductId" to "other-card")), product))
    }

    @Test fun defaultWalletsRemainSeparatedBySourceCurrency() {
        val cup = WalletProductUi("cup", "reg", ProviderIdentity(ProviderId.MITRANSFER),
            ProductKind.WALLET, "Monedero", null, Currency.CUP, SourceSelector.Default)
        val op = operation(parameters = mapOf("sourceCurrency" to "USD"))
        assertFalse(operationBelongsToProduct(op, cup))
        assertTrue(operationBelongsToProduct(op, cup.copy(id = "usd", currency = Currency.USD)))
        assertFalse(operationBelongsToProduct(operation(), cup))
    }
}
