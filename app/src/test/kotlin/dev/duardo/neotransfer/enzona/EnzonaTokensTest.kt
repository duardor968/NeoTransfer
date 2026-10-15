package dev.duardo.neotransfer.enzona

import org.json.JSONObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnzonaTokensTest {
    private fun jwt(subject: String): String = "synthetic." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(JSONObject().put("sub", subject).toString().toByteArray()) + ".signature"

    private fun response(subject: String = "example@carbon.super") = JSONObject()
        .put("access_token", "ACCESS_TEST_ONLY").put("refresh_token", "REFRESH_TEST_ONLY")
        .put("id_token", jwt(subject)).put("token_type", "Bearer").put("expires_in", 3600)

    @Test fun expiryUsesSecondsAndPreservesTheExactSubject() {
        val token = EnzonaTokens.fromResponse(response().toString(), 1000)
        assertEquals(4600L, token.expiresAtEpochSeconds)
        assertEquals("example@carbon.super", token.accountSubject())
        assertFalse(token.needsRefresh(4569))
        assertTrue(token.needsRefresh(4570))
        assertTrue(token.needsRefresh(4700))
    }

    @Test fun refreshRotatesCredentialsAndCannotSilentlyChangeAccounts() {
        val old = EnzonaTokens.fromResponse(response().toString(), 1000)
        val rotated = response().put("access_token", "NEW_ACCESS").put("refresh_token", "NEW_REFRESH")
        val current = EnzonaTokens.fromResponse(rotated.toString(), 4500, old)
        assertEquals("NEW_REFRESH", current.refreshToken)
        assertEquals(8100L, current.expiresAtEpochSeconds)
        assertFailsWith<IllegalArgumentException> {
            EnzonaTokens.fromResponse(response("other-account").toString(), 4500, old)
        }
        rotated.remove("refresh_token")
        rotated.remove("id_token")
        assertEquals(old.refreshToken, EnzonaTokens.fromResponse(rotated.toString(), 4500, old).refreshToken)
    }

    @Test fun missingOrMalformedDataCannotCreateAnAuthenticatedSession() {
        for (invalid in listOf("{}", "[]", response().put("access_token", "").toString(),
            response().put("access_token", "token\nvalue").toString(),
            response().put("expires_in", "3600").toString(), response().put("expires_in", false).toString(),
            response().put("expires_in", -1).toString(), response().put("expires_in", 0).toString(),
            response().put("token_type", "Basic").toString(), response().put("id_token", "invalid").toString(),
            response().put("error_description", "PRIVATE_SYNTHETIC_MARKER").toString(),
            response().put("error", "invalid_grant").toString(),
            response().put("id_token", jwt("")).toString())) {
            assertFailsWith<IllegalArgumentException> { EnzonaTokens.fromResponse(invalid, 1000) }
        }
        assertFailsWith<IllegalArgumentException> {
            EnzonaTokens.fromResponse(response().put("expires_in", Long.MAX_VALUE).toString(), 1000)
        }
    }

    @Test fun encryptedStorageRoundTripDoesNotTurnExpiredCredentialsIntoAFreshSession() {
        val original = EnzonaTokens.fromResponse(response().toString(), 1000)
        val restored = EnzonaTokens.fromEncryptedStorage(original.forEncryptedStorage())
        assertEquals(original.accessToken, restored.accessToken)
        assertEquals(original.expiresAtEpochSeconds, restored.expiresAtEpochSeconds)
        assertTrue(restored.needsRefresh(9000))
        assertEquals("EnzonaTokens(redacted)", restored.toString())
    }
}
