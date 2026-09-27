package dev.duardo.neotransfer.data

import org.json.JSONObject

internal fun fuelEvidenceJson(value: FuelPurchaseEvidence): JSONObject = JSONObject().apply {
    put("couponId", value.couponId); put("serial", value.serial); put("bankCode", value.bankCode ?: JSONObject.NULL)
    put("subscriptionId", value.subscriptionId ?: JSONObject.NULL); put("bankReference", value.bankReference)
    put("tmReference", value.tmReference); put("paidAmount", value.paidAmount); put("currency", value.currency)
    put("receivedAt", value.receivedAt); put("evidenceEligible", value.evidenceEligible)
    put("sentAt", value.sentAt ?: JSONObject.NULL)
}

internal fun readFuelEvidence(value: JSONObject): FuelPurchaseEvidence = value.run {
    FuelPurchaseEvidence(getString("couponId"), getString("serial"), nullableString("bankCode"),
        if (isNull("subscriptionId")) null else getInt("subscriptionId"), getString("bankReference"),
        getString("tmReference"), getString("paidAmount"), getString("currency"), getLong("receivedAt"), getBoolean("evidenceEligible"),
        if (!has("sentAt") || isNull("sentAt")) null else getLong("sentAt"))
}
