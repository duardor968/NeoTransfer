package dev.duardo.neotransfer.core.enzona

import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * ENZONA 1.11.0 session wire contract recovered from ARM64 AOT; not server-validated.
 * The public client ID is not a client secret. The observed `code_challange` spelling
 * is intentional and does not establish that the server enforces PKCE.
 *
 * Requiring a fresh state and its exact echo is our protection, absent from the APK.
 * A server that does not echo it must fail closed. No nonce is sent without validation.
 * URI/body properties contain sensitive material: expose them only to the transport,
 * never logs. This object does not perform HTTP, validate tokens or persist a session.
 */
object EnzonaAuthContract {
    const val CLIENT_ID = "ofr3Wz9nnfZaFd18OewdZYvuTaEa"
    const val REDIRECT_URI = "http://apk-callback"
    private const val IDENTITY = "https://identity.enzona.net"
    private const val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded"

    fun newAttempt(random: SecureRandom = SecureRandom()): LoginAttempt {
        val verifier = randomValue(random)
        val state = randomValue(random)
        return LoginAttempt(verifier, state)
    }

    /** Only the exact callback origin and empty/root path, never a prefix match. */
    fun isCallbackUri(value: String): Boolean = parseUri(value)?.let(::isCallback) == true

    fun refreshTokenRequest(refreshToken: String): FormRequest {
        requireOpaque(refreshToken)
        return tokenRequest(
            "client_id" to CLIENT_ID,
            "grant_type" to "refresh_token",
            "refresh_token" to refreshToken,
        )
    }

    /** Browser logout; does not claim token revocation or success of remote SSO logout. */
    fun logoutUri(idToken: String, authorizationCode: String): URI {
        requireOpaque(idToken)
        requireOpaque(authorizationCode)
        return URI("$IDENTITY/oidc/logout?" + form(
            "id_token_hint" to idToken,
            "code" to authorizationCode,
            "post_logout_redirect_uri" to REDIRECT_URI,
        ))
    }

    class LoginAttempt internal constructor(
        private val verifier: String,
        private val state: String,
    ) {
        private var consumed = false

        val authorizationUri: URI = URI("$IDENTITY/oauth2/authorize?" + form(
            "response_type" to "code",
            "client_id" to CLIENT_ID,
            "scope" to "openid",
            "redirect_uri" to REDIRECT_URI,
            "code_challange" to base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))),
            "code_challange_method" to "S256",
            "state" to state,
        ))

        /**
         * At most one successful exchange, including concurrent WebView callbacks.
         * Invalid callbacks leave the attempt pending. A correlated OAuth rejection
         * consumes it. Discard this object after success, rejection or cancellation.
         */
        @Synchronized
        fun consumeCallback(value: String): CodeExchange {
            if (consumed) fail(CallbackFailure.ALREADY_CONSUMED)
            val uri = parseUri(value) ?: fail(CallbackFailure.INVALID_CALLBACK_URI)
            if (!isCallback(uri)) fail(CallbackFailure.INVALID_CALLBACK_URI)
            val parameters = queryParameters(uri)
            val receivedStates = parameters["state"] ?: fail(CallbackFailure.MISSING_STATE)
            if (receivedStates.size != 1) fail(CallbackFailure.DUPLICATE_STATE)
            if (!MessageDigest.isEqual(state.toByteArray(Charsets.UTF_8), receivedStates.single().toByteArray(Charsets.UTF_8))) {
                fail(CallbackFailure.STATE_MISMATCH)
            }
            val codes = parameters["code"]
            val errors = parameters["error"]
            if (errors != null) {
                if (codes != null || errors.size != 1 || !isOpaque(errors.single())) {
                    fail(CallbackFailure.AMBIGUOUS_RESPONSE)
                }
                consumed = true
                fail(CallbackFailure.AUTHORIZATION_REJECTED)
            }
            if (codes == null) fail(CallbackFailure.MISSING_CODE)
            if (codes.size != 1) fail(CallbackFailure.DUPLICATE_CODE)
            val code = codes.single()
            if (!isOpaque(code)) fail(CallbackFailure.INVALID_CODE)
            val exchange = CodeExchange(code, tokenRequest(
                "client_id" to CLIENT_ID,
                "grant_type" to "authorization_code",
                "redirect_uri" to REDIRECT_URI,
                "post_logout_redirect_uri" to "/",
                "code" to code,
                "code_verifier" to verifier,
            ))
            consumed = true
            return exchange
        }

        override fun toString(): String = "EnzonaLoginAttempt(<redacted>)"
    }

    class CodeExchange internal constructor(val authorizationCode: String, val tokenRequest: FormRequest) {
        override fun toString(): String = "EnzonaCodeExchange(<redacted>)"
    }

    class FormRequest internal constructor(val endpoint: URI, val body: String) {
        val contentType: String = FORM_CONTENT_TYPE
        override fun toString(): String = "EnzonaFormRequest(<redacted>)"
    }

    enum class CallbackFailure {
        INVALID_CALLBACK_URI, MALFORMED_QUERY, MISSING_STATE, DUPLICATE_STATE, STATE_MISMATCH,
        MISSING_CODE, DUPLICATE_CODE, INVALID_CODE, AMBIGUOUS_RESPONSE, AUTHORIZATION_REJECTED, ALREADY_CONSUMED,
    }

    class CallbackException internal constructor(val reason: CallbackFailure) :
        IllegalArgumentException("ENZONA callback rejected: ${reason.name}")

    private fun tokenRequest(vararg fields: Pair<String, String>) =
        FormRequest(URI("$IDENTITY/oauth2/token"), form(*fields))

    private fun form(vararg fields: Pair<String, String>): String = fields.joinToString("&") { (name, value) ->
        URLEncoder.encode(name, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
    }

    private fun randomValue(random: SecureRandom): String =
        base64Url(ByteArray(32).also(random::nextBytes))

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun isOpaque(value: String): Boolean = value.isNotBlank() && value.none(Char::isISOControl)

    private fun requireOpaque(value: String) = require(isOpaque(value)) { "ENZONA credential is missing or malformed" }

    private fun parseUri(value: String): URI? = try {
        URI(value)
    } catch (_: URISyntaxException) {
        null
    }

    private fun isCallback(uri: URI): Boolean = !uri.isOpaque &&
        uri.scheme.equals("http", ignoreCase = true) && uri.host.equals("apk-callback", ignoreCase = true) &&
        uri.rawUserInfo == null && uri.port == -1 && uri.rawAuthority.equals("apk-callback", ignoreCase = true) &&
        (uri.rawPath == "" || uri.rawPath == "/") && uri.rawFragment == null

    private fun queryParameters(uri: URI): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        for (part in uri.rawQuery.orEmpty().split('&')) {
            if (part.isEmpty()) continue
            val separator = part.indexOf('=')
            val name = decodeQueryPart(if (separator < 0) part else part.substring(0, separator))
            val value = decodeQueryPart(if (separator < 0) "" else part.substring(separator + 1))
            result.getOrPut(name) { mutableListOf() }.add(value)
        }
        return result
    }

    /** Strict UTF-8 and percent decoding; encoded duplicate names must stay duplicates. */
    private fun decodeQueryPart(value: String): String {
        val bytes = ByteArrayOutputStream()
        var index = 0
        while (index < value.length) {
            when (value[index]) {
                '%' -> {
                    if (index + 2 >= value.length) fail(CallbackFailure.MALFORMED_QUERY)
                    val high = value[index + 1].digitToIntOrNull(16) ?: fail(CallbackFailure.MALFORMED_QUERY)
                    val low = value[index + 2].digitToIntOrNull(16) ?: fail(CallbackFailure.MALFORMED_QUERY)
                    bytes.write((high shl 4) or low)
                    index += 3
                }
                '+' -> { bytes.write(32); index++ }
                else -> {
                    val point = Character.codePointAt(value, index)
                    if (point in 0xD800..0xDFFF) fail(CallbackFailure.MALFORMED_QUERY)
                    val encoded = String(Character.toChars(point)).toByteArray(Charsets.UTF_8)
                    bytes.write(encoded, 0, encoded.size)
                    index += Character.charCount(point)
                }
            }
        }
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: CharacterCodingException) {
            fail(CallbackFailure.MALFORMED_QUERY)
        }
    }

    private fun fail(reason: CallbackFailure): Nothing = throw CallbackException(reason)
}
