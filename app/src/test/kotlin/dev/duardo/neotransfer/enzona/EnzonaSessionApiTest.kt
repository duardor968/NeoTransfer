package dev.duardo.neotransfer.enzona

import dev.duardo.neotransfer.core.enzona.EnzonaAuthContract
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EnzonaSessionApiTest {
    private fun tokenBody(): String {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "{\"sub\":\"synthetic@carbon.super\"}".toByteArray())
        return JSONObject().put("access_token", "ACCESS_TEST_ONLY").put("refresh_token", "REFRESH_TEST_ONLY")
            .put("id_token", "header.$payload.signature").put("token_type", "Bearer").put("expires_in", 3600).toString()
    }

    private fun form(body: String): Map<String, String> = body.split('&').associate { field ->
        val pair = field.split('=', limit = 2)
        URLDecoder.decode(pair[0], "UTF-8") to URLDecoder.decode(pair[1], "UTF-8")
    }

    @Test fun exchangeBootstrapAndRefreshFollowTheTracedRequestShapes() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val bodies = mutableListOf<String?>()
        val client = enzonaHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            bodies.add(request.body?.let { body -> Buffer().also(body::writeTo).readUtf8() })
            val body = when (request.url.encodedPath) {
                "/oauth2/token" -> tokenBody()
                "/account/v1.0.0/account/authentication" -> "{}"
                "/account/v1.0.0/account" -> "{\"account\":{\"username\":\"synthetic@carbon.super\"}}"
                else -> error("Unexpected synthetic request")
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        EnzonaHttp(EnzonaTransport(client)).use { http ->
            val api = EnzonaSessionApi(http) { 1000 }
            val attempt = EnzonaAuthContract.newAttempt()
            val state = form(attempt.authorizationUri.rawQuery).getValue("state")
            val exchange = attempt.consumeCallback("http://apk-callback/?code=SYNTHETIC_CODE&state=$state")
            val token = api.exchange(exchange)
            api.recordDevice(token, linkedMapOf("accessType" to "MOBILE", "imei" to "SYNTHETIC_BUILD_ID"))
            assertEquals("synthetic@carbon.super", api.account(token).getString("username"))
            api.refresh(token)
        }
        assertEquals(listOf("POST", "POST", "GET", "POST"), requests.map { it.method })
        assertEquals("authorization_code", form(requireNotNull(bodies[0]))["grant_type"])
        assertEquals("http://apk-callback", form(requireNotNull(bodies[0]))["redirect_uri"])
        assertNull(requests[0].header("Authorization"))
        assertEquals("{accessType: MOBILE, imei: SYNTHETIC_BUILD_ID}", JSONObject(requireNotNull(bodies[1])).getString("data"))
        assertEquals("synthetic@carbon.super", requests[2].url.queryParameter("username"))
        assertNull(bodies[2])
        for (request in requests.slice(1..2)) {
            assertEquals("Bearer ACCESS_TEST_ONLY", request.header("Authorization"))
            assertEquals("1.9.11.241103", request.header("Version"))
        }
        assertEquals(mapOf("client_id" to EnzonaAuthContract.CLIENT_ID, "grant_type" to "refresh_token",
            "refresh_token" to "REFRESH_TEST_ONLY"), form(requireNotNull(bodies[3])))
    }

    @Test fun failedRefreshIsReturnedWithoutAutomaticRetry() = runBlocking {
        var calls = 0
        val client = enzonaHttpClient().newBuilder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(401).message("Unauthorized")
                .body("{\"error\":\"invalid_grant\"}".toResponseBody()).build()
        }.build()
        EnzonaHttp(EnzonaTransport(client)).use { http ->
            val original = EnzonaTokens.fromResponse(tokenBody(), 1000)
            val error = assertFailsWith<EnzonaApiException> { EnzonaSessionApi(http).refresh(original) }
            assertEquals(401, error.httpStatus)
            assertEquals(EnzonaOAuthFailure.INVALID_GRANT, error.oauthFailure)
            assertEquals("REFRESH_TEST_ONLY", original.refreshToken)
        }
        assertEquals(1, calls)
    }

    @Test fun oauthErrorsStayDistinguishableWithoutKeepingTheirDescriptions() = runBlocking {
        for (failure in listOf(EnzonaOAuthFailure.INVALID_GRANT, EnzonaOAuthFailure.INVALID_CLIENT,
            EnzonaOAuthFailure.TEMPORARILY_UNAVAILABLE)) {
            val client = enzonaHttpClient().newBuilder().addInterceptor { chain ->
                val body = JSONObject().put("error", failure.name.lowercase())
                    .put("error_description", "PRIVATE_SYNTHETIC_MARKER").toString()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(400).message("Bad Request")
                    .body(body.toResponseBody()).build()
            }.build()
            EnzonaHttp(EnzonaTransport(client)).use { http ->
                val error = assertFailsWith<EnzonaApiException> {
                    EnzonaSessionApi(http).refresh(EnzonaTokens.fromResponse(tokenBody(), 1000))
                }
                assertEquals(failure, error.oauthFailure)
                assertEquals("ENZONA no pudo completar la solicitud", error.message)
                assertNull(error.cause)
            }
        }
    }

    @Test fun malformedAccountJsonCannotLeakItsContentsThroughAnException() = runBlocking {
        val client = enzonaHttpClient().newBuilder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"account\":{\"PRIVATE_SYNTHETIC_MARKER\":".toResponseBody()).build()
        }.build()
        EnzonaHttp(EnzonaTransport(client)).use { http ->
            val error = assertFailsWith<IllegalArgumentException> {
                EnzonaSessionApi(http).account(EnzonaTokens.fromResponse(tokenBody(), 1000))
            }
            assertEquals("La respuesta de la cuenta no es válida", error.message)
            assertNull(error.cause)
        }
    }
}
