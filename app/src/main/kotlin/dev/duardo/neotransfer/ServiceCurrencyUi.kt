package dev.duardo.neotransfer

import dev.duardo.neotransfer.core.*

/** Transfer currency describes the amount, independently of the selected bank account. */
internal fun OperationSpec.hasAmountCurrency(identity: ProviderIdentity?): Boolean =
    identity?.bank != null && effect == OperationEffect.MONEY && service in setOf(45, 104)

internal val OperationSpec.activeCurrencies: List<Currency> get() = currencies.filterNot { it == Currency.CUC }

internal fun serviceCurrency(
    spec: OperationSpec, identity: ProviderIdentity?, productCurrency: Currency?, choice: Currency?,
): Currency? {
    val available = spec.activeCurrencies
    if (available.isEmpty()) return null
    val selected = choice?.takeIf { it in available }
    val product = productCurrency?.takeIf { it in available }
    return if (spec.hasAmountCurrency(identity)) selected ?: product ?: available.singleOrNull()
        else product ?: selected ?: available.singleOrNull()
}
