package dev.duardo.neotransfer.enzona

import dev.duardo.neotransfer.enzona.data.EnzonaIntentState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant

/** Only decoded evidence; never retains a response body that could echo payment authorization. */
internal class EnzonaPaymentEvidence(
    val state: EnzonaIntentState,
    val httpStatus: Int,
    val statusCode: String?,
    val transactionUuid: String?,
    /** Distinct provider field. Not established as an account-history transaction identifier. */
    val uuid: String?,
    val observedAt: Long,
    val serviceResult: EnzonaServicePaymentResult? = null,
) {
    init { require(state in setOf(EnzonaIntentState.CONFIRMED, EnzonaIntentState.REJECTED, EnzonaIntentState.UNKNOWN)) }
    override fun toString() = "EnzonaPaymentEvidence(state=$state, privateFields=redacted)"
}

internal fun interface EnzonaPaymentNetwork {
    suspend fun submit(tokens: EnzonaTokens, request: EnzonaPaymentRequest): EnzonaPaymentEvidence
}

/** Exactly one HTTP execution. Authentication renewal happens before the journal CAS. */
internal class EnzonaPaymentApi(
    private val execute: suspend (Request) -> EnzonaHttpReply,
    private val now: () -> Long = { Instant.now().toEpochMilli() },
) : EnzonaPaymentNetwork {
    constructor(http: EnzonaHttp) : this(http::execute)

    override suspend fun submit(tokens: EnzonaTokens, request: EnzonaPaymentRequest): EnzonaPaymentEvidence {
        require(if (request.path == INVOICE) request.service != null
            else request.service == null && request.path in setOf(CARD, ACCOUNT, MERCHANT)) { "Operación de pago no válida" }
        val reply = execute(enzonaApiRequest(request.url, tokens)
            .post(request.json.toRequestBody("application/json; charset=utf-8".toMediaType())).build())
        return request.service?.let { service ->
            val result = EnzonaServiceJson.payment(service, reply.status, reply.body)
            val state = when (result.state) {
                EnzonaServicePaymentState.ACCEPTED -> EnzonaIntentState.CONFIRMED
                EnzonaServicePaymentState.FAILED -> EnzonaIntentState.REJECTED
                EnzonaServicePaymentState.PENDING, EnzonaServicePaymentState.UNKNOWN -> EnzonaIntentState.UNKNOWN
            }
            // charged_invoice.uuid identifies an invoice; it is not transaction_uuid from history.
            EnzonaPaymentEvidence(state, reply.status, result.statusCode, null, result.uuid, now(), result)
        } ?: decode(request.path, reply, now())
    }

    private fun decode(path: String, reply: EnzonaHttpReply, observedAt: Long): EnzonaPaymentEvidence {
        val root = try { Json.parseToJsonElement(reply.body) as? JsonObject } catch (_: IllegalArgumentException) { null }
        val error = root == null || listOf("fault", "error", "errors", "error_description").any { it in root }
        // Camel-case normalization is demonstrated for the card transfer decoder only.
        val names = if (path == CARD) listOf("statusCode", "status_code") else listOf("status_code")
        val codes = root?.let { obj -> names.filter { it in obj }.map { code(obj[it]) } }.orEmpty()
        val code = codes.firstOrNull()?.takeIf { candidate -> codes.all { it == candidate } }
        fun id(name: String) = (root?.get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
        val confirmed = reply.status == 200 && !error && path != MERCHANT && code == "1111"
        return EnzonaPaymentEvidence(
            if (confirmed) EnzonaIntentState.CONFIRMED else EnzonaIntentState.UNKNOWN,
            reply.status, code, id("transaction_uuid"), id("uuid"), observedAt,
        )
    }

    private fun code(value: kotlinx.serialization.json.JsonElement?): String? =
        (value as? JsonPrimitive)?.takeIf { it != JsonNull }?.content
            ?.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }

    private companion object {
        const val CARD = "/transfer/v1.0.0/transfer/card"
        const val ACCOUNT = "/transfer/v1.0.0/transfer/account"
        const val MERCHANT = "/payment/v1.0.0/payments/vendor/code"
        const val INVOICE = "/invoice/v1.0.0/invoice/payment"
    }
}
