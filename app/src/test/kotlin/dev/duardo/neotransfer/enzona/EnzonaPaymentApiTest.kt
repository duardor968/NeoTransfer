package dev.duardo.neotransfer.enzona

import dev.duardo.neotransfer.enzona.data.EnzonaIntentState
import java.net.SocketTimeoutException
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class EnzonaPaymentApiTest {
    private val card = "/transfer/v1.0.0/transfer/card"
    private val account = "/transfer/v1.0.0/transfer/account"
    private val merchant = "/payment/v1.0.0/payments/vendor/code"

    private fun tokens(): EnzonaTokens {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"synthetic-owner\"}".toByteArray())
        return EnzonaTokens("SYNTHETIC_ACCESS", null, "header.$payload.signature", Long.MAX_VALUE)
    }

    @Test fun realHttpWrapperSendsOneTracedPostWithSessionHeadersAndNoRealNetwork() = runBlocking {
        var sends = 0
        val client = enzonaHttpClient().newBuilder().addInterceptor { chain ->
            sends++
            val request = chain.request()
            assertEquals("https://api.enzona.net$card", request.url.toString())
            assertEquals("POST", request.method)
            assertEquals("Bearer SYNTHETIC_ACCESS", request.header("Authorization"))
            assertEquals("1.9.11.241103", request.header("Version"))
            assertEquals("application/json", request.header("accept"))
            assertEquals("application/json; charset=utf-8", request.body!!.contentType().toString())
            assertEquals("{}", Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("{\"statusCode\":1111,\"transaction_uuid\":\"observed-id\"}".toResponseBody()).build()
        }.build()
        EnzonaHttp(EnzonaTransport(client)).use { http ->
            val evidence = EnzonaPaymentApi(http).submit(tokens(), EnzonaPaymentRequest(card, "{}"))
            assertEquals(EnzonaIntentState.CONFIRMED, evidence.state)
            assertEquals("observed-id", evidence.transactionUuid)
        }
        assertEquals(1, sends)
    }

    @Test fun onlyTransfer1111CanConfirmWithoutInventingARemoteIdentifier() = runBlocking {
        val api = EnzonaPaymentApi({ EnzonaHttpReply(200, "{\"status_code\":\"1111\"}") }, { 123 })
        val evidence = api.submit(tokens(), EnzonaPaymentRequest(account, "{}"))
        assertEquals(EnzonaIntentState.CONFIRMED, evidence.state)
        assertEquals(123L, evidence.observedAt)
        assertNull(evidence.transactionUuid)
        assertNull(evidence.uuid)
        assertEquals(EnzonaIntentState.UNKNOWN, api.submit(tokens(), EnzonaPaymentRequest(merchant, "{}")).state)
    }

    @Test fun malformedFaultUnknownAndConflictingResponsesNeverBecomeSuccessOrRejection() = runBlocking {
        val bodies = listOf("[]", "{private-fixture", "{}", "{\"status_code\":0}",
            "{\"statusCode\":1111,\"fault\":null}", "{\"statusCode\":1111,\"error\":\"private-fixture\"}",
            "{\"statusCode\":1111,\"status_code\":2222}", "{\"statusCode\":1111,\"status_code\":null}",
            "{\"statusCode\":true}", "{\"statusCode\":1111.0}")
        for (json in bodies) {
            val evidence = EnzonaPaymentApi({ EnzonaHttpReply(200, json) }).submit(tokens(), EnzonaPaymentRequest(card, "{}"))
            assertEquals(EnzonaIntentState.UNKNOWN, evidence.state)
        }
    }

    @Test fun differentProviderIdentifiersRemainDistinctAndDoNotInvalidate1111() = runBlocking {
        val api = EnzonaPaymentApi({ EnzonaHttpReply(200,
            "{\"statusCode\":1111,\"transaction_uuid\":\"history-id\",\"uuid\":\"separate-id\"}") })
        val evidence = api.submit(tokens(), EnzonaPaymentRequest(card, "{}"))
        assertEquals(EnzonaIntentState.CONFIRMED, evidence.state)
        assertEquals("history-id", evidence.transactionUuid)
        assertEquals("separate-id", evidence.uuid)
        val onlyUuid = EnzonaPaymentApi({ EnzonaHttpReply(200, "{\"statusCode\":1111,\"uuid\":\"separate-id\"}") })
            .submit(tokens(), EnzonaPaymentRequest(card, "{}"))
        assertEquals(EnzonaIntentState.CONFIRMED, onlyUuid.state)
        assertNull(onlyUuid.transactionUuid)
        assertEquals("separate-id", onlyUuid.uuid)
    }

    @Test fun camelCaseSuccessIsNotAssumedForAccountOrMerchant() = runBlocking {
        val api = EnzonaPaymentApi({ EnzonaHttpReply(200, "{\"statusCode\":\"1111\"}") })
        for (path in listOf(account, merchant)) assertEquals(EnzonaIntentState.UNKNOWN,
            api.submit(tokens(), EnzonaPaymentRequest(path, "{}")).state)
    }

    @Test fun authenticationOrHttpFailureNeverTriggersASecondExecution() = runBlocking {
        for (status in listOf(401, 400, 500, 503)) {
            var sends = 0
            val api = EnzonaPaymentApi({ sends++; EnzonaHttpReply(status, "{\"statusCode\":1111}") })
            assertEquals(EnzonaIntentState.UNKNOWN, api.submit(tokens(), EnzonaPaymentRequest(card, "{}")).state)
            assertEquals(1, sends)
        }
    }

    @Test fun socketTimeoutIsPropagatedToTheJournalWithoutRetry() = runBlocking {
        var sends = 0
        val api = EnzonaPaymentApi({ sends++; throw SocketTimeoutException("synthetic") })
        assertFailsWith<SocketTimeoutException> { api.submit(tokens(), EnzonaPaymentRequest(card, "{}")) }
        assertEquals(1, sends)
    }

    @Test fun unsupportedPathsCannotEscapeTheFinancialEndpointAllowlist() = runBlocking {
        var sends = 0
        val api = EnzonaPaymentApi({ sends++; error("unexpected") })
        for (path in listOf("/untraced", "$card?extra=1", "https://other.invalid/")) {
            assertFailsWith<IllegalArgumentException> { api.submit(tokens(), EnzonaPaymentRequest(path, "{}")) }
        }
        assertEquals(0, sends)
    }

    @Test fun invoiceRequestsUseOnePostAndOnlyFamilySpecificTerminalEvidence() = runBlocking {
        val fixtures = listOf(
            Triple(EnzonaInvoiceService.ELECTRICITY, """{"charged_invoice":{"status_code":1111,"uuid":"invoice-id","invoice_number":"0007"}}""", EnzonaIntentState.CONFIRMED),
            Triple(EnzonaInvoiceService.ELECTRICITY, """{"charged_invoice":{"status_code":"1111"}}""", EnzonaIntentState.UNKNOWN),
            Triple(EnzonaInvoiceService.ELECTRICITY, """{"charged_invoice":{"status_code":1111,"status":"Fallida"}}""", EnzonaIntentState.UNKNOWN),
            Triple(EnzonaInvoiceService.FIXED_PHONE, """{"charged_invoice":{"status":"Aceptada"}}""", EnzonaIntentState.CONFIRMED),
            Triple(EnzonaInvoiceService.FIXED_PHONE, """{"getPaymentPasswordIntent":{},"charged_invoice":{"status":"Aceptada"}}""", EnzonaIntentState.CONFIRMED),
            Triple(EnzonaInvoiceService.FIXED_PHONE, """{"charged_invoice":{"status":"Aceptada","getPaymentPasswordIntent":null}}""", EnzonaIntentState.CONFIRMED),
            Triple(EnzonaInvoiceService.NAUTA, """{"charged_invoice":{"status":"Fallida"}}""", EnzonaIntentState.REJECTED),
            Triple(EnzonaInvoiceService.NAUTA_HOME, """{"charged_invoice":{"status":"Pendiente"}}""", EnzonaIntentState.UNKNOWN),
            Triple(EnzonaInvoiceService.NAUTA, """{"charged_invoice":{"status_code":1111}}""", EnzonaIntentState.UNKNOWN),
        )
        for ((service, response, expected) in fixtures) {
            var sends = 0
            val api = EnzonaPaymentApi({ outgoing ->
                sends++
                assertEquals("POST", outgoing.method)
                assertEquals("https://api.enzona.net/invoice/v1.0.0/invoice/payment", outgoing.url.toString())
                EnzonaHttpReply(200, response)
            })
            val evidence = api.submit(tokens(), EnzonaPaymentRequest("/invoice/v1.0.0/invoice/payment", "{}", service))
            assertEquals(expected, evidence.state)
            assertNull(evidence.transactionUuid)
            if (response.contains("invoice-id")) {
                assertEquals("invoice-id", evidence.uuid)
                assertEquals("0007", evidence.serviceResult!!.invoiceNumber)
            }
            assertEquals(1, sends)
        }
    }

    @Test fun invoiceFaultsPasswordIntentsAndTransportStatusCannotBecomeTerminalOrRetry() = runBlocking {
        val cases = listOf(200 to """{"getPaymentPasswordIntent":{"synthetic":"authorization-result"},"charged_invoice":{"status":"Aceptada"}}""",
            200 to """{"fault":{},"charged_invoice":{"status":"Fallida"}}""",
            401 to """{"charged_invoice":{"status":"Aceptada"}}""", 200 to "not-json")
        for ((status, json) in cases) {
            var sends = 0
            val api = EnzonaPaymentApi({ sends++; EnzonaHttpReply(status, json) })
            val evidence = api.submit(tokens(), EnzonaPaymentRequest("/invoice/v1.0.0/invoice/payment", "{}", EnzonaInvoiceService.NAUTA))
            assertEquals(EnzonaIntentState.UNKNOWN, evidence.state)
            assertEquals(1, sends)
        }
    }

    @Test fun invoiceRouteAndDecoderFamilyMustMatchBeforeAnySend() = runBlocking {
        var sends = 0
        val api = EnzonaPaymentApi({ sends++; error("must not send") })
        assertFailsWith<IllegalArgumentException> { api.submit(tokens(), EnzonaPaymentRequest("/invoice/v1.0.0/invoice/payment", "{}")) }
        assertFailsWith<IllegalArgumentException> { api.submit(tokens(), EnzonaPaymentRequest(card, "{}", EnzonaInvoiceService.NAUTA)) }
        assertEquals(0, sends)
    }
}
