package dev.duardo.neotransfer

import dev.duardo.neotransfer.core.*

/** Transfer currency describes the amount, independently of the selected bank account. */
internal fun OperationSpec.hasAmountCurrency(identity: ProviderIdentity?): Boolean =
    identity?.bank != null && effect == OperationEffect.MONEY && service in setOf(45, 104)

internal fun serviceCurrency(
    spec: OperationSpec, identity: ProviderIdentity?, productCurrency: Currency?, choice: Currency?,
): Currency? {
    if (spec.currencies.isEmpty()) return null
    val selected = choice?.takeIf { it in spec.currencies }
    val product = productCurrency?.takeIf { it in spec.currencies }
    return if (spec.hasAmountCurrency(identity)) selected ?: product ?: spec.currencies.singleOrNull()
        else product ?: selected ?: spec.currencies.singleOrNull()
}
