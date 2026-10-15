package dev.duardo.neotransfer.enzona

import java.math.BigDecimal
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

enum class EnzonaQrKind { ACCOUNT, MERCHANT_PAYMENT, MERCHANT_INFO }

/** Resolution is information for review, never a payment authorization or an automatic draft. */
class EnzonaResolvedQr(
    val ownerSubject: String,
    val kind: EnzonaQrKind,
    val code: String,
    val vendorIdentityCode: String?,
    val accountUuid: String?,
    val username: String?,
    val merchantUuid: String?,
    val name: String?,
    val amount: BigDecimal?,
    val currency: String?,
    val description: String?,
    val requirePassword: JsonElement?,
    val observedAt: Long,
) {
    override fun toString() = "EnzonaResolvedQr(kind=$kind, privateFields=redacted)"
}

class EnzonaOwnQr(val ownerSubject: String, val code: String, val amount: BigDecimal?,
    val currency: String?, val description: String?, val observedAt: Long) {
    override fun toString() = "EnzonaOwnQr(redacted)"
}

internal class EnzonaQrResponseException : IllegalArgumentException("La respuesta QR de ENZONA no es válida")

internal class EnzonaQrApi(
    private val execute: suspend (Request) -> EnzonaHttpReply,
    private val now: () -> Long = { Instant.now().toEpochMilli() },
) {
    constructor(http: EnzonaHttp) : this(http::execute)

    suspend fun resolve(tokens: EnzonaTokens, ownerSubject: String, code: String): EnzonaResolvedQr {
        checkOwner(tokens, ownerSubject)
        // A traced prefix is a routing candidate, not proof of issuer or authenticity.
        require(isEnzonaQrCandidate(code)) { "Código QR no compatible con ENZONA" }
        require(code.isNotBlank() && code !in setOf(".", "..") && code.none(Char::isISOControl)) { "Código QR no válido" }
        val url = "https://api.enzona.net/qr/v1.0.0/qr/".toHttpUrl().newBuilder()
            .addPathSegment(code).addQueryParameter("cash_advance", "false").build()
        val root = envelope(execute(enzonaApiRequest(url.toString(), tokens).get().build()))
        return decode {
            val vendor = root.text("vendor_identity_code")
            val qr = root.text("qr_code")
            val merchant = root.text("merchant_uuid")
            val username = root.text("username")
            val kind = when {
                merchant != null && qr?.startsWith("07") == true -> EnzonaQrKind.MERCHANT_PAYMENT
                root["owners"] is JsonArray -> EnzonaQrKind.MERCHANT_INFO
                merchant != null -> throw EnzonaQrResponseException()
                vendor?.startsWith("01") == true || vendor?.startsWith("05") == true || username != null -> EnzonaQrKind.ACCOUNT
                else -> throw EnzonaQrResponseException()
            }
            EnzonaResolvedQr(ownerSubject, kind, qr ?: vendor ?: code, vendor, root.text("uuid"), username, merchant,
                root.text("name") ?: root.text("merchant_name"), root.amount(), root.text("currency"),
                root.text("description"), root["require_password"], now())
        }
    }

    /** Creates a server-issued receive code only; it does not submit any transfer/payment. */
    suspend fun createOwn(tokens: EnzonaTokens, ownerSubject: String, amount: BigDecimal,
        currency: String, description: String? = null): EnzonaOwnQr {
        checkOwner(tokens, ownerSubject)
        require(amount.signum() > 0 && currency.isNotBlank() && currency.none(Char::isISOControl)) { "Importe o moneda no válido" }
        val json = buildJsonObject {
            put("amount", amount.toPlainString()); put("currency", currency); put("description", description.orEmpty())
        }
        val root = envelope(execute(enzonaApiRequest("https://api.enzona.net/qr/v1.0.0/qr/account", tokens)
            .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()))
        return decode {
            EnzonaOwnQr(ownerSubject, requireNotNull(root.text("vendor_identity_code")), root.amount(),
                root.text("currency"), root.text("description"), now())
        }
    }

    private fun checkOwner(tokens: EnzonaTokens, owner: String) {
        require(owner.isNotBlank() && tokens.accountSubject() == owner) { "La cuenta de ENZONA cambió" }
    }

    private fun envelope(reply: EnzonaHttpReply): JsonObject {
        if (reply.status != 200) throw EnzonaApiException(reply.status)
        return decode {
            val root = Json.parseToJsonElement(reply.body) as? JsonObject ?: throw EnzonaQrResponseException()
            require(listOf("fault", "error", "errors", "error_description").none { it in root })
            root
        }
    }

    private fun JsonObject.text(key: String): String? {
        val value = get(key) ?: return null
        if (value == JsonNull) return null
        require(value is JsonPrimitive && value.isString)
        return value.content.takeIf { it.isNotBlank() }
    }

    private fun JsonObject.amount(): BigDecimal? {
        val value = get("amount") ?: return null
        if (value == JsonNull) return null
        require(value is JsonPrimitive)
        return value.content.toBigDecimalOrNull() ?: throw EnzonaQrResponseException()
    }

    private inline fun <T> decode(block: () -> T): T = try { block() }
    catch (_: IllegalArgumentException) { throw EnzonaQrResponseException() }
}
