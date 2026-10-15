package dev.duardo.neotransfer.enzona

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Values captured for review. Payment credentials never belong to a draft. */
sealed interface EnzonaPaymentDraft {
    val fundingSourceUuid: String
    val amount: BigDecimal
    val currency: String
    val description: String?
}

data class EnzonaCardTransferDraft(
    override val fundingSourceUuid: String,
    val recipientPan: String,
    override val amount: BigDecimal,
    override val currency: String,
    override val description: String? = null,
    val phone: String = "",
) : EnzonaPaymentDraft {
    override fun toString(): String = "EnzonaCardTransferDraft(redacted)"
}

data class EnzonaAccountTransferDraft(
    override val fundingSourceUuid: String,
    val fundingRecipientUuid: String,
    val fundingRecipientUsername: String,
    override val amount: BigDecimal,
    override val currency: String,
    override val description: String? = null,
) : EnzonaPaymentDraft {
    override fun toString(): String = "EnzonaAccountTransferDraft(redacted)"
}

data class EnzonaQrMerchantPaymentDraft(
    override val fundingSourceUuid: String,
    val vendorIdentityCode: String,
    override val amount: BigDecimal,
    override val currency: String,
    override val description: String? = null,
    val phone: String = "",
) : EnzonaPaymentDraft {
    override fun toString(): String = "EnzonaQrMerchantPaymentDraft(redacted)"
}

/** Invoice identity and reviewed values; currency/description are local review fields, not wire keys. */
data class EnzonaInvoicePaymentDraft(
    val service: EnzonaInvoiceService,
    val clientId: String,
    val owner: String,
    override val fundingSourceUuid: String,
    override val amount: BigDecimal,
    override val currency: String,
    override val description: String? = null,
    val folio: String? = null,
    val route: String? = null,
    val period: String? = null,
) : EnzonaPaymentDraft {
    override fun toString(): String = "EnzonaInvoicePaymentDraft(redacted)"
}

/** Transient encoded body; submitting, session headers and persistence belong to the caller. */
class EnzonaPaymentRequest internal constructor(
    val path: String,
    val json: String,
    val service: EnzonaInvoiceService? = null,
) {
    val method: String = "POST"
    val url: String get() = "https://api.enzona.net$path"

    override fun toString(): String = "EnzonaPaymentRequest(redacted)"
}

/** Password branch traced at 0x7af5dc, 0x8f08cc and 0x8c694c in ENZONA 1.11.0. */
class EnzonaPaymentRequests(
    private val publicKey: RSAPublicKey,
    private val random: SecureRandom = SecureRandom(),
) {
    /** Consumes password: the supplied array is cleared on success and on every failure. */
    fun encode(draft: EnzonaPaymentDraft, password: CharArray): EnzonaPaymentRequest {
        try {
            require(draft.fundingSourceUuid.isNotBlank() && draft.fundingSourceUuid.none(Char::isISOControl)) {
                "Selecciona la tarjeta de origen"
            }
            require(draft.amount.signum() > 0) { "El importe debe ser mayor que cero" }
            require(draft.currency.isNotBlank() && draft.currency.none(Char::isISOControl)) { "La moneda de origen no es válida" }
            when (draft) {
                is EnzonaAccountTransferDraft -> require(draft.fundingRecipientUuid.isNotBlank() &&
                    draft.fundingRecipientUsername.isNotBlank()) { "Selecciona el destinatario" }
                is EnzonaQrMerchantPaymentDraft -> require(draft.vendorIdentityCode.isNotBlank()) { "El código de pago no es válido" }
                is EnzonaCardTransferDraft -> Unit
                is EnzonaInvoicePaymentDraft -> {
                    require(listOf(draft.clientId, draft.owner).all { it.isNotBlank() && it.none(Char::isISOControl) }) {
                        "Faltan datos de la factura"
                    }
                    if (draft.service == EnzonaInvoiceService.ELECTRICITY) {
                        require(listOf(draft.folio, draft.route, draft.period).all { !it.isNullOrBlank() && it.none(Char::isISOControl) }) {
                            "Faltan datos de la factura eléctrica"
                        }
                    } else require(draft.clientId == draft.owner) { "Los datos de la cuenta ETECSA no coinciden" }
                }
            }
            require(password.isNotEmpty()) { "La contraseña de pago está vacía" }
            val encryptedPan = if (draft is EnzonaCardTransferDraft) {
                val pan = draft.recipientPan.replace(" ", "")
                require(pan.length == 16 && pan.all { it in '0'..'9' }) {
                    "La tarjeta destinataria debe contener 16 dígitos"
                }
                encryptAndClear(pan.toByteArray(StandardCharsets.UTF_8))
            } else null
            val encryptedPassword = encryptPassword(password)
            val body = buildJsonObject {
                put("amount", draft.amount.toPlainString())
                put("funding_source_uuid", draft.fundingSourceUuid)
                if (draft !is EnzonaInvoicePaymentDraft) {
                    put("description", draft.description.orEmpty())
                    put("currency", draft.currency)
                    // The APK's transfer password branch retains this empty field; no signature is generated.
                    put("fingerprint", "")
                }
                when (draft) {
                    is EnzonaCardTransferDraft -> {
                        put("pan", requireNotNull(encryptedPan))
                        put("phone", draft.phone)
                    }
                    is EnzonaAccountTransferDraft -> {
                        put("funding_recipient_username", draft.fundingRecipientUsername)
                        put("funding_recipient_uuid", draft.fundingRecipientUuid)
                        put("type_transfer", "")
                    }
                    is EnzonaQrMerchantPaymentDraft -> {
                        put("vendor_identity_code", draft.vendorIdentityCode)
                        put("cash_advance", false)
                        put("phone", draft.phone)
                    }
                    is EnzonaInvoicePaymentDraft -> {
                        put("client_id", draft.clientId)
                        put("owner", draft.owner)
                        if (draft.service == EnzonaInvoiceService.ELECTRICITY) {
                            put("service_code", 2222)
                            put("metadata", buildJsonObject {
                                put("folio", requireNotNull(draft.folio))
                                put("route", requireNotNull(draft.route))
                            }.toString())
                            put("period", requireNotNull(draft.period))
                        } else put("service_code", draft.service.code)
                    }
                }
                put("payment_password", encryptedPassword)
            }
            val path = when (draft) {
                is EnzonaCardTransferDraft -> "/transfer/v1.0.0/transfer/card"
                is EnzonaAccountTransferDraft -> "/transfer/v1.0.0/transfer/account"
                is EnzonaQrMerchantPaymentDraft -> "/payment/v1.0.0/payments/vendor/code"
                is EnzonaInvoicePaymentDraft -> "/invoice/v1.0.0/invoice/payment"
            }
            return EnzonaPaymentRequest(path, body.toString(), (draft as? EnzonaInvoicePaymentDraft)?.service)
        } finally {
            password.fill('\u0000')
        }
    }

    private fun encryptPassword(password: CharArray): String {
        var encoded: ByteBuffer? = null
        try {
            encoded = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(password))
            val bytes = ByteArray(encoded.remaining())
            encoded.get(bytes)
            return encryptAndClear(bytes)
        } catch (_: CharacterCodingException) {
            throw IllegalArgumentException("La contraseña de pago no contiene texto UTF-8 válido")
        } finally {
            encoded?.let { buffer ->
                if (buffer.hasArray()) buffer.array().fill(0) else {
                    buffer.clear()
                    while (buffer.hasRemaining()) buffer.put(0.toByte())
                }
            }
        }
    }

    private fun encryptAndClear(bytes: ByteArray): String = try {
        val blockBytes = (publicKey.modulus.bitLength() + 7) / 8
        require(blockBytes > 11 && bytes.size <= blockBytes - 11) {
            "El texto supera el tamaño permitido por la clave de cifrado"
        }
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey, random)
        Base64.getEncoder().encodeToString(cipher.doFinal(bytes))
    } finally {
        bytes.fill(0)
    }

    companion object {
        /** SPKI PUBLIC KEY from res/raw/enzona_public_key.pem, or a synthetic test key. */
        fun publicKeyFromPem(pem: String): RSAPublicKey {
            val begin = "-----BEGIN PUBLIC KEY-----"
            val end = "-----END PUBLIC KEY-----"
            val text = pem.trim()
            require(text.startsWith(begin) && text.endsWith(end)) {
                "La clave de cifrado debe tener formato SPKI PEM"
            }
            val content = text.substring(begin.length, text.length - end.length)
                .filterNot(Char::isWhitespace)
            val key = KeyFactory.getInstance("RSA")
                .generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(content)))
            return key as? RSAPublicKey
                ?: throw IllegalArgumentException("La clave de cifrado debe ser RSA")
        }
    }
}
