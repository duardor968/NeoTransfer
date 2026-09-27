package dev.duardo.neotransfer.data

import dev.duardo.neotransfer.core.Currency
import java.time.LocalDate
import dev.duardo.neotransfer.core.fuel.FuelCoupon

/** Reject incompatible or corrupt financial values before any restore write. */
internal fun validateSnapshot(value: WalletSnapshot) {
    fun currency(unit: String?) { unit?.let(::checkCurrency) }
    fun amount(number: String?, unit: String?) {
        currency(unit)
        if (number != null) { checkAmount(number); require(unit != null) { "Importe sin moneda" } }
    }
    val receiptKinds = setOf("SENT", "RECEIVED", "PAYMENT", "RECHARGE", "FUEL_REFUND")
    value.accounts.forEach { currency(it.currency) }
    value.cards.forEach { currency(it.currency); validateCardMetadata(it) }
    value.services.forEach { amount(it.amount, it.currency) }
    value.operations.forEach { operation ->
        validateOperation(operation)
        operation.amount?.let(::checkAmount); currency(operation.currency)
        operation.qr?.let { amount(it.amount, it.currency) }
    }
    value.receipts.forEach {
        require(it.kind in receiptKinds) { "Tipo de comprobante no compatible" }
        amount(it.amount, it.currency); amount(it.nominalAmount, it.currency)
    }
    value.movements.forEach {
        require(it.kind in receiptKinds && it.incoming == (it.kind in setOf("RECEIVED", "FUEL_REFUND"))) { "Tipo o dirección de movimiento no compatible" }
        amount(it.amount, it.currency)
    }
    value.balances.forEach { amount(it.available, it.currency); amount(it.ledger, it.currency) }
    value.histories.forEach {
        amount(it.amount, it.currency); LocalDate.parse(it.postedOn)
        require(it.bankCode == "02" && it.currency in setOf("CUP", "USD", "CUC")) { "Estado de cuenta no compatible" }
    }
    value.historyObservations.forEach { require(it.position >= 0) }
    value.usedReferences.forEach { require(normalizeReference(it.reference).isNotEmpty()) }
    value.fuelCoupons.forEach(::validateFuelCoupon)
    value.fuelObservations.forEach {
        require(it.eventId.isNotBlank() && it.position >= 0 && it.rejectedRows >= 0 && it.kind.isNotBlank() && it.result.isNotBlank())
        it.creditedAmount?.let(::validateFuelAmount)
        it.purchaseEvidence?.let { proof ->
            require(it.kind == "PURCHASE" && proof.couponId == it.couponId && proof.couponId == FuelCoupon.idFor(proof.serial))
            require(proof.bankReference.isNotBlank() && proof.tmReference.isNotBlank())
            validateFuelAmount(proof.paidAmount); checkCurrency(proof.currency)
            proof.subscriptionId?.let { sim -> require(sim >= 0) }
        }
    }
}

internal fun checkCurrency(value: String) {
    require(Currency.entries.any { it.name == value }) { "Moneda no compatible" }
}

internal fun validateCardMetadata(value: CardRecord) {
    value.holderName?.let { holder ->
        require(holder.isNotEmpty() && holder == holder.trim() && holder.length <= 80 &&
            holder.none { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }) { "Titular no válido" }
    }
    value.expiry?.let { require(it.matches(Regex("(?:0[1-9]|1[0-2])/[0-9]{2}"))) { "El vencimiento debe tener formato MM/AA" } }
}

internal fun validateFuelAmount(value: String) {
    require(value.toBigDecimalOrNull()?.signum()?.let { it >= 0 } == true && value.toDoubleOrNull()?.isFinite() == true) { "Importe del cupón no válido" }
}
internal fun validateFuelCoupon(value: FuelCoupon) {
    require(value.id == FuelCoupon.idFor(value.serial) && value.bankReference.isNotBlank() && value.tmReference.isNotBlank() &&
        value.bankReference.none(Char::isISOControl) && value.tmReference.none(Char::isISOControl) && value.sourceEventId.isNotBlank())
    checkCurrency(value.currency)
    value.paidAmount?.let(::validateFuelAmount); value.balance?.let(::validateFuelAmount)
    value.reportedDate?.let { LocalDate.parse(it) }
    value.subscriptionId?.let { require(it >= 0) }
    value.secretRevision?.let { require(it.matches(Regex("[0-9a-f]{64}"))) }
    require(value.label == value.label.trim() && value.label.length <= 80 && value.label.none(Char::isISOControl))
    value.balanceObservedAt?.let { require(it <= value.observedAt) }
}
