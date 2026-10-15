package dev.duardo.neotransfer.enzona

import java.math.BigDecimal
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Source date text; decoding does not infer a format, timezone or instant. */
data class EnzonaRemoteDate(val value: String)

/** Account card identity is independent of any local registration SIM or bank account. */
data class EnzonaRemoteCard(
    val fundingSourceUuid: String,
    val fundingSourceId: String?,
    val last4: String?,
    val cardholder: String?,
    val expiryDate: String?,
    val createdAt: EnzonaRemoteDate?,
    val updatedAt: EnzonaRemoteDate?,
    val status: String?,
    val currency: String?,
    val primarySource: String?,
    val bankName: String?,
    val bankCode: String?,
    val verified: String?,
    val deleted: JsonElement?,
    val bankCertificate: String?,
    val bin: Int?,
)

/** Amounts are independent and nullable; currency is the source value, never a BIN mapping. */
data class EnzonaCardBalance(
    val bookBalance: BigDecimal?,
    val availableBalance: BigDecimal?,
    val currency: String?,
    val bankAccount: String?,
    val statusCode: Int?,
    val statusDenomination: String?,
    val transactionSignature: String?,
    val operations: List<EnzonaBankOperation>,
    val original: JsonObject,
)

/** Last bank operations have no demonstrated unique ID. Original IDs/references stay in original. */
data class EnzonaBankOperation(
    val operationDate: EnzonaRemoteDate?,
    val action: String?,
    val channel: String?,
    val description: String?,
    val amount: BigDecimal?,
    val code: String?,
    val avatar: String?,
    val alias: String?,
    val original: JsonObject,
)

/** ENZONA account history, distinct from the card's last bank operations. */
data class EnzonaTransactionPage(
    val transactions: List<EnzonaTransaction>,
    val pagination: EnzonaPagination,
)

/** Link shapes are not proven by AOT: retain strings, numbers, false and null without guessing. */
data class EnzonaPagination(
    val first: JsonElement?,
    val previous: JsonElement?,
    val next: JsonElement?,
    val last: JsonElement?,
    val total: Long?,
    val original: JsonObject,
)

data class EnzonaTransaction(
    val transactionUuid: String,
    val owner: String?,
    val clientId: String?,
    val invoice: String?,
    val invoiceNumber: String?,
    val merchantAlias: String?,
    val merchantAvatar: String?,
    val merchantName: String?,
    val statusCode: Int?,
    val transactionStatusCode: Int?,
    val transactionStatus: String?,
    val createdAt: EnzonaRemoteDate?,
    val signature: String?,
    val period: String?,
    val amount: BigDecimal?,
    val totalAmount: BigDecimal?,
    val currency: String?,
    val transactionDenomination: String?,
    val statusDenomination: String?,
    val transactionCode: Int?,
    val barcode: Boolean?,
    val donationTitle: String?,
    val description: String?,
    val source: EnzonaTransactionSource?,
    val recipient: EnzonaTransactionRecipient?,
    // AOT accepts dynamic metadata and a JSON-encoded string for bank_debit_detail.
    // Keep both verbatim; false, null and an empty string have different meanings.
    val metadata: JsonElement?,
    val bankDebitDetail: JsonElement?,
    val original: JsonObject,
)

data class EnzonaTransactionSource(
    val last4: String?,
    val bank: Int?,
    val avatar: String?,
    val name: String?,
    val lastName: String?,
    val username: String?,
    val amount: BigDecimal?,
    val verified: Int?,
    val merchantAlias: String?,
    val merchantAvatar: String?,
    val original: JsonObject,
)

data class EnzonaTransactionRecipient(
    val last4: String?,
    val bank: String?,
    val avatar: String?,
    val name: String?,
    val lastName: String?,
    val username: String?,
    val verified: String?,
    val donationTitle: String?,
    val merchantAlias: String?,
    val merchantAvatar: String?,
    val original: JsonObject,
)
