package dev.duardo.neotransfer.enzona

import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.time.LocalDate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EnzonaReadApiTest {
    private fun tokens(): EnzonaTokens {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"owner@carbon.super\"}".toByteArray())
        return EnzonaTokens("SYNTHETIC_ACCESS", null, "header.$payload.signature", Long.MAX_VALUE)
    }

    @Test fun requestsUseTheTracedIdentityAndDistinguishCardHistoryFromAccountHistory() = runBlocking {
        val requests = mutableListOf<okhttp3.Request>()
        val client = enzonaHttpClient().newBuilder().addInterceptor { chain ->
            val request = chain.request().also(requests::add)
            val json = when {
                request.url.encodedPath.contains("/card/username/") -> "{\"card\":[]}"
                request.url.encodedPath.contains("/balance/lastOperations/") -> "{\"balance\":12.50,\"currency\":\"cup\",\"operation\":[]}"
                else -> "{\"transaction\":[],\"pagination\":{\"total\":0,\"next\":false}}"
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody()).build()
        }.build()
        EnzonaHttp(EnzonaTransport(client)).use { http ->
            val api = EnzonaReadApi(http) { 1234 }
            val tokens = tokens()
            assertEquals(1234L, api.cards(tokens).observedAt)
            assertEquals("cup", api.balance(tokens, "synthetic-card-uuid").value.currency)
            api.transactions(tokens, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7))
            api.transactionPage(tokens, "?offset=8&limit=8")
        }
        assertEquals("/coremz/1.0.2/card/username/owner@carbon.super", requests[0].url.encodedPath)
        assertEquals("/coremz/1.0.2/api/balance/lastOperations/synthetic-card-uuid", requests[1].url.encodedPath)
        assertEquals("/operations/v1.0.0/operations/account", requests[2].url.encodedPath)
        assertEquals("100000", requests[2].url.queryParameter("limit"))
        assertEquals("2026-10-01", requests[2].url.queryParameter("start_date_filter"))
        assertEquals("8", requests[3].url.queryParameter("offset"))
        requests.forEach { request ->
            assertEquals("GET", request.method)
            assertNull(request.body)
            assertEquals("Bearer SYNTHETIC_ACCESS", request.header("Authorization"))
        }
    }

    @Test fun paginationCannotRedirectCredentialsToAnotherHostOrOperation() = runBlocking {
        var sends = 0
        val client = enzonaHttpClient().newBuilder().addInterceptor { sends++; error("Unexpected network call") }.build()
        EnzonaHttp(EnzonaTransport(client)).use { http ->
            val api = EnzonaReadApi(http)
            for (link in listOf("https://untrusted.example/", "http://api.enzona.net/operations/v1.0.0/operations/account",
                "/transfer/v1.0.0/transfer/card", "https://user@api.enzona.net/operations/v1.0.0/operations/account",
                "https://api.enzona.net:8443/operations/v1.0.0/operations/account")) {
                assertFailsWith<IllegalArgumentException> { api.transactionPage(tokens(), link) }
            }
        }
        assertEquals(0, sends)
    }
}
