package dev.duardo.neotransfer.data

import dev.duardo.neotransfer.platform.smsEvidenceAfter
import java.time.Instant

/** Shared selection rule. The repository additionally checks the persisted source and uniqueness. */
fun fuelPurchaseMatches(operation: OperationRecord, evidence: FuelPurchaseEvidence): Boolean {
    val bank = mapOf("BPA" to "01", "BANDEC" to "02", "BANMET" to "03")[operation.providerId] ?: return false
    val amount = operation.amount?.toBigDecimalOrNull() ?: return false
    val parameter = operation.parameters["amount"]?.toBigDecimalOrNull() ?: return false
    val paid = evidence.paidAmount.toBigDecimalOrNull() ?: return false
    return operation.specId == "service.fuel" && operation.kind == operation.specId && operation.profileId == "PERSONAL" &&
        operation.bankCode == bank && evidence.bankCode == bank && operation.currency == "CUP" && evidence.currency == "CUP" &&
        operation.parameters["amountCurrency"] == "1" &&
        operation.subscriptionId == evidence.subscriptionId && evidence.evidenceEligible &&
        smsEvidenceAfter(Instant.ofEpochMilli(evidence.receivedAt), evidence.sentAt?.let(Instant::ofEpochMilli),
            Instant.ofEpochMilli(operation.startedAt), Instant.ofEpochMilli(evidence.receivedAt)) &&
        amount.signum() > 0 && amount.compareTo(parameter) == 0 &&
        amount.compareTo(paid) == 0 && evidence.bankReference.isNotBlank() && evidence.tmReference.isNotBlank() &&
        evidence.couponId == "fuel:${evidence.serial}" && evidence.serial.matches(Regex("[0-9]{13}"))
}
