package dev.duardo.neotransfer.core.enzona

import dev.duardo.neotransfer.core.enzona.EnzonaAuthContract.CallbackException
import dev.duardo.neotransfer.core.enzona.EnzonaAuthContract.CallbackFailure
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic offline contracts, never captured credentials or server acceptance tests. */
class EnzonaAuthContractTest {
    private fun attempt() = EnzonaAuthContract.newAttempt(object : SecureRandom() {
        private var offset = 0
        override fun nextBytes(bytes: ByteArray) {
            bytes.indices.forEach { bytes[it] = (offset + it).toByte() }
            offset += bytes.size
        }
    })

    private fun fields(encoded: String): Map<String, String> = encoded.split('&').associate {
        val parts = it.split('=', limit = 2)
        URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
    }

    private fun state(attempt: EnzonaAuthContract.LoginAttempt) = fields(attempt.authorizationUri.rawQuery).getValue("state")

    private fun callback(attempt: EnzonaAuthContract.LoginAttempt, code: String = "TEST_CODE") =
        "http://apk-callback/?code=${URLEncoder.encode(code, "UTF-8")}&state=${state(attempt)}"

    private fun rejected(reason: CallbackFailure, block: () -> Unit) {
        assertEquals(reason, assertFailsWith<CallbackException>(block = block).reason)
    }

    @Test fun `authorize preserves observed spelling and independently generated state`() {
        val attempt = attempt()
        val uri = attempt.authorizationUri
        assertEquals("https", uri.scheme)
        assertEquals("identity.enzona.net", uri.host)
        assertEquals("/oauth2/authorize", uri.path)
        assertEquals(mapOf(
            "response_type" to "code",
            "client_id" to "ofr3Wz9nnfZaFd18OewdZYvuTaEa",
            "scope" to "openid",
            "redirect_uri" to "http://apk-callback",
            "code_challange" to "6oZqdX5MOLq_qBJ8vppAnT4fk6AP8UiP9zX8-Rev_9A",
            "code_challange_method" to "S256",
            "state" to "ICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj8",
        ), fields(uri.rawQuery))
        assertFalse(uri.rawQuery.contains("code_verifier"))
        assertFalse(uri.rawQuery.contains("code_challenge="))
        assertFalse(uri.rawQuery.contains("nonce="))
    }

    @Test fun `new production attempts have fresh state and verifier challenges`() {
        val first = fields(EnzonaAuthContract.newAttempt().authorizationUri.rawQuery)
        val second = fields(EnzonaAuthContract.newAttempt().authorizationUri.rawQuery)
        for (key in listOf("state", "code_challange")) {
            assertTrue(first.getValue(key).matches(Regex("[A-Za-z0-9_-]{43}")))
            assertTrue(first[key] != second[key])
        }
    }

    @Test fun `code exchange accepts root or empty path and preserves opaque form values`() {
        for (path in listOf("", "/")) {
            val attempt = attempt()
            val code = "TEST+ /&=?#%é"
            val uri = callback(attempt, code).replace("apk-callback/", "apk-callback$path")
            assertTrue(EnzonaAuthContract.isCallbackUri(uri))
            val exchange = attempt.consumeCallback(uri)
            assertEquals(code, exchange.authorizationCode)
            assertEquals(URI("https://identity.enzona.net/oauth2/token"), exchange.tokenRequest.endpoint)
            assertEquals("application/x-www-form-urlencoded", exchange.tokenRequest.contentType)
            assertEquals(mapOf(
                "client_id" to EnzonaAuthContract.CLIENT_ID,
                "grant_type" to "authorization_code",
                "redirect_uri" to EnzonaAuthContract.REDIRECT_URI,
                "post_logout_redirect_uri" to "/",
                "code" to code,
                "code_verifier" to "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
            ), fields(exchange.tokenRequest.body))
            assertTrue(exchange.tokenRequest.body.contains("code=TEST%2B+%2F%26%3D%3F%23%25%C3%A9"))
            rejected(CallbackFailure.ALREADY_CONSUMED) { attempt.consumeCallback(uri) }
        }
    }

    @Test fun `callback validates authority path and absence of fragment without prefix matching`() {
        val attempt = attempt()
        val query = "?code=TEST_CODE&state=${state(attempt)}"
        for (base in listOf(
            "https://apk-callback/", "http://apk-callback.evil/", "http://apk-callback@evil/",
            "http://evil@apk-callback/", "http://apk-callback:80/", "http://apk-callback:/",
            "http://apk-callback./", "http://apk-callback/other", "http://apk-callback/%2F",
            "http://apk-callback//", "http://apk-callback/../", "//apk-callback/", "http:apk-callback",
        )) {
            assertFalse(EnzonaAuthContract.isCallbackUri(base + query), base)
            rejected(CallbackFailure.INVALID_CALLBACK_URI) { attempt.consumeCallback(base + query) }
        }
        rejected(CallbackFailure.INVALID_CALLBACK_URI) { attempt.consumeCallback(callback(attempt) + "#fragment") }
        // Failed navigation probes must not consume a valid pending attempt.
        assertEquals("TEST_CODE", attempt.consumeCallback(callback(attempt)).authorizationCode)
    }

    @Test fun `state must be present unique and match even when parameter names are encoded`() {
        val attempt = attempt()
        val valid = callback(attempt)
        rejected(CallbackFailure.MISSING_STATE) { attempt.consumeCallback("http://apk-callback/?code=TEST_CODE") }
        rejected(CallbackFailure.STATE_MISMATCH) { attempt.consumeCallback("http://apk-callback/?code=TEST_CODE&state=") }
        rejected(CallbackFailure.STATE_MISMATCH) { attempt.consumeCallback(valid.replace(state(attempt), "different")) }
        rejected(CallbackFailure.DUPLICATE_STATE) { attempt.consumeCallback(valid + "&%73tate=${state(attempt)}") }
        rejected(CallbackFailure.DUPLICATE_STATE) { attempt.consumeCallback(valid + "&state=") }
        assertEquals("TEST_CODE", attempt.consumeCallback(valid).authorizationCode)
    }

    @Test fun `authorization code must be unique nonblank and free of control characters`() {
        val attempt = attempt()
        val base = "http://apk-callback/?state=${state(attempt)}"
        rejected(CallbackFailure.MISSING_CODE) { attempt.consumeCallback(base) }
        for (encoded in listOf("", "+", "%00", "TEST%0D%0ACODE")) {
            rejected(CallbackFailure.INVALID_CODE) { attempt.consumeCallback("$base&code=$encoded") }
        }
        rejected(CallbackFailure.DUPLICATE_CODE) { attempt.consumeCallback("$base&code=TEST_CODE&%63ode=OTHER") }
        assertEquals("TEST_CODE", attempt.consumeCallback("$base&session_state=unknown&code=TEST_CODE").authorizationCode)
    }

    @Test fun `malformed encoding is rejected instead of replaced or double decoded`() {
        val attempt = attempt()
        val base = "http://apk-callback/?state=${state(attempt)}&code="
        for (encoded in listOf("%C3%28", "%FF", "%ED%A0%80", "%E2%82")) {
            rejected(CallbackFailure.MALFORMED_QUERY) { attempt.consumeCallback(base + encoded) }
        }
        for (encoded in listOf("%", "%0", "%GG")) {
            rejected(CallbackFailure.INVALID_CALLBACK_URI) { attempt.consumeCallback(base + encoded) }
        }
        assertEquals("%2B", attempt.consumeCallback(base + "%252B").authorizationCode)
    }

    @Test fun `oauth error consumes only its matching attempt and never becomes a code exchange`() {
        val attempt = attempt()
        val base = "http://apk-callback/?state=${state(attempt)}"
        rejected(CallbackFailure.STATE_MISMATCH) { attempt.consumeCallback("http://apk-callback/?state=other&error=access_denied") }
        rejected(CallbackFailure.AMBIGUOUS_RESPONSE) { attempt.consumeCallback("$base&error=access_denied&code=TEST_CODE") }
        rejected(CallbackFailure.AMBIGUOUS_RESPONSE) { attempt.consumeCallback("$base&error=one&error=two") }
        rejected(CallbackFailure.AUTHORIZATION_REJECTED) { attempt.consumeCallback("$base&error=access_denied&error_description=SECRET_MESSAGE") }
        rejected(CallbackFailure.ALREADY_CONSUMED) { attempt.consumeCallback(callback(attempt)) }
    }

    @Test fun `concurrent duplicated webview callbacks produce exactly one exchange`() {
        val attempt = attempt()
        val uri = callback(attempt)
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = (1..2).map {
                executor.submit<Boolean> {
                    check(gate.await(5, TimeUnit.SECONDS))
                    try {
                        attempt.consumeCallback(uri)
                        true
                    } catch (error: CallbackException) {
                        assertEquals(CallbackFailure.ALREADY_CONSUMED, error.reason)
                        false
                    }
                }
            }
            gate.countDown()
            assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
        } finally {
            gate.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun `refresh and logout preserve opaque values without secret credentials or extra grants`() {
        val refresh = "TEST_REFRESH+/&=é"
        val request = EnzonaAuthContract.refreshTokenRequest(refresh)
        assertEquals(URI("https://identity.enzona.net/oauth2/token"), request.endpoint)
        assertEquals(mapOf("client_id" to EnzonaAuthContract.CLIENT_ID, "grant_type" to "refresh_token", "refresh_token" to refresh), fields(request.body))
        val id = "TEST.JWT+/=&"
        val code = "TEST_CODE +/=&"
        val logout = EnzonaAuthContract.logoutUri(id, code)
        assertEquals("https", logout.scheme)
        assertEquals("identity.enzona.net", logout.host)
        assertEquals("/oidc/logout", logout.path)
        assertEquals(mapOf("id_token_hint" to id, "code" to code, "post_logout_redirect_uri" to EnzonaAuthContract.REDIRECT_URI), fields(logout.rawQuery))
        assertFailsWith<IllegalArgumentException> { EnzonaAuthContract.refreshTokenRequest("") }
        assertFailsWith<IllegalArgumentException> { EnzonaAuthContract.logoutUri("", code) }
        assertFailsWith<IllegalArgumentException> { EnzonaAuthContract.logoutUri(id, "TEST\nCODE") }
    }

    @Test fun `objects and rejected callback diagnostics redact all session material`() {
        val attempt = attempt()
        val exchange = attempt.consumeCallback(callback(attempt, "SECRET_CODE"))
        val error = assertFailsWith<CallbackException> { attempt.consumeCallback("invalid SECRET_URL") }
        val text = listOf(attempt, exchange, exchange.tokenRequest, EnzonaAuthContract.refreshTokenRequest("SECRET_REFRESH"), error).joinToString()
        for (sensitive in listOf("SECRET_CODE", "SECRET_REFRESH", "SECRET_URL", "http://", "https://", state(attempt), "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8")) {
            assertFalse(text.contains(sensitive))
        }
        assertEquals(null, error.cause)
    }
}
