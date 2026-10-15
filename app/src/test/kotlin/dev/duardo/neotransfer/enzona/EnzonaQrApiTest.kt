package dev.duardo.neotransfer.enzona

import java.math.BigDecimal
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnzonaQrApiTest {
    private val owner = "synthetic-owner"
    private fun tokens(): EnzonaTokens {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"$owner\"}".toByteArray())
        return EnzonaTokens("SYNTHETIC_ACCESS", null, "header.$payload.signature", Long.MAX_VALUE)
    }

    @Test fun resolutionUsesOneEncodedPathAndNeverRequestsCashAdvance() = runBlocking {
        var seen: Request? = null
        val api = EnzonaQrApi({ seen = it; EnzonaHttpReply(200,
            "{\"vendor_identity_code\":\"05-code\",\"uuid\":\"recipient\",\"username\":\"other-owner\",\"amount\":\"1.2300\",\"currency\":\"cup\"}") }, { 123 })
        val scanned = "05-code/?cash_advance=true&other=value"
        val result = api.resolve(tokens(), owner, scanned)
        assertEquals("GET", seen!!.method)
        assertEquals("api.enzona.net", seen!!.url.host)
        assertEquals(scanned, seen!!.url.pathSegments.last())
        assertEquals(setOf("cash_advance"), seen!!.url.queryParameterNames)
        assertEquals("false", seen!!.url.queryParameter("cash_advance"))
        assertNull(seen!!.body)
        assertEquals("1.9.11.241103", seen!!.header("Version"))
        assertEquals(EnzonaQrKind.ACCOUNT, result.kind)
        assertEquals("recipient", result.accountUuid)
        assertEquals(owner, result.ownerSubject)
        assertEquals("other-owner", result.username)
        assertEquals(BigDecimal("1.2300"), result.amount)
        assertEquals(123L, result.observedAt)
    }

    @Test fun distinctResponseFamiliesRemainDistinctWithoutAuthorizingPayments() = runBlocking {
        val fixtures = listOf(
            "{\"merchant_uuid\":\"merchant\",\"qr_code\":\"07-code\",\"require_password\":false}" to EnzonaQrKind.MERCHANT_PAYMENT,
            "{\"owners\":[]}" to EnzonaQrKind.MERCHANT_INFO,
            "{\"vendor_identity_code\":\"01-code\"}" to EnzonaQrKind.ACCOUNT,
            "{\"username\":\"recipient\"}" to EnzonaQrKind.ACCOUNT,
        )
        for ((json, kind) in fixtures) {
            var calls = 0
            val api = EnzonaQrApi({ calls++; EnzonaHttpReply(200, json) })
            assertEquals(kind, api.resolve(tokens(), owner, "05-synthetic").kind)
            assertEquals(1, calls)
        }
    }

    @Test fun ownQrUsesExactDecimalStringsAndTheServerIssuedCode() = runBlocking {
        var seen: Request? = null
        val api = EnzonaQrApi({ seen = it; EnzonaHttpReply(200,
            "{\"vendor_identity_code\":\"05-issued-by-fixture\",\"amount\":\"4.2500\",\"currency\":\"cup\",\"description\":\"reply\"}") })
        val description = "reviewed \"quote\"\nñ"
        val result = api.createOwn(tokens(), owner, BigDecimal("4.2500"), "cup", description)
        assertEquals("https://api.enzona.net/qr/v1.0.0/qr/account", seen!!.url.toString())
        assertEquals("POST", seen!!.method)
        val json = Json.parseToJsonElement(Buffer().also { seen!!.body!!.writeTo(it) }.readUtf8()).jsonObject
        assertEquals(setOf("amount", "currency", "description"), json.keys)
        assertTrue(json.getValue("amount").jsonPrimitive.isString)
        assertEquals("4.2500", json.getValue("amount").jsonPrimitive.content)
        assertEquals(description, json.getValue("description").jsonPrimitive.content)
        assertEquals("05-issued-by-fixture", result.code)
        assertEquals(BigDecimal("4.2500"), result.amount)
    }

    @Test fun malformedOrUnclassifiedQrResponsesUseSanitizedErrors() = runBlocking {
        for (json in listOf("{SENSITIVE_FIXTURE", "[]", "{}", "{\"fault\":\"SENSITIVE_FIXTURE\"}",
            "{\"merchant_uuid\":\"merchant\",\"username\":\"cannot-reclassify-as-account\"}",
            "{\"vendor_identity_code\":\"05-code\",\"amount\":true}")) {
            val api = EnzonaQrApi({ EnzonaHttpReply(200, json) })
            val error = assertFailsWith<EnzonaQrResponseException> { api.resolve(tokens(), owner, "05-synthetic") }
            assertEquals("La respuesta QR de ENZONA no es válida", error.message)
            assertNull(error.cause)
        }
        val api = EnzonaQrApi({ EnzonaHttpReply(200, "{}") })
        assertFailsWith<EnzonaQrResponseException> { api.createOwn(tokens(), owner, BigDecimal.ONE, "cup") }
        Unit
    }

    @Test fun ownerMismatchAndInvalidPathCannotExecuteRequests() = runBlocking {
        var calls = 0
        val api = EnzonaQrApi({ calls++; error("unexpected") })
        assertFailsWith<IllegalArgumentException> { api.resolve(tokens(), "other", "05-synthetic") }
        assertFailsWith<IllegalArgumentException> { api.createOwn(tokens(), "other", BigDecimal.ONE, "cup") }
        for (code in listOf(".", "..", "", "05-synthetic\n")) {
            assertFailsWith<IllegalArgumentException> { api.resolve(tokens(), owner, code) }
        }
        assertEquals(0, calls)
    }

    @Test fun httpErrorsAreNotRetriedOrDecodedAsSuccessfulQr() = runBlocking {
        var calls = 0
        val api = EnzonaQrApi({ calls++; EnzonaHttpReply(401, "private-fixture") })
        assertFailsWith<EnzonaApiException> { api.resolve(tokens(), owner, "05-synthetic") }
        assertEquals(1, calls)
    }

    @Test fun urlsWifiAndArbitraryQrDataNeverReachTheTransport() = runBlocking {
        var calls = 0
        val api = EnzonaQrApi({ calls++; error("must not send unrelated QR data") })
        for (code in listOf("https://api.enzona.net/qr/05-synthetic", "https://unrelated.invalid/07-synthetic",
            "WIFI:T:WPA;S:SYNTHETIC;P:SYNTHETIC;;", "mailto:synthetic@example.invalid", "arbitrary", "03-synthetic")) {
            assertFailsWith<IllegalArgumentException> { api.resolve(tokens(), owner, code) }
        }
        assertEquals(0, calls)
    }

    @Test fun tracedPrefixesAdmitOpaqueCandidatesWithoutClaimingAuthenticity() = runBlocking {
        var calls = 0
        val api = EnzonaQrApi({ request ->
            calls++
            assertEquals("api.enzona.net", request.url.host)
            assertEquals("false", request.url.queryParameter("cash_advance"))
            EnzonaHttpReply(200, "{}") // A prefix cannot make an unrecognized response valid.
        })
        for (code in listOf("01-synthetic", "05-synthetic/?x=y&z=1", "07-opaque:ñ")) {
            assertFailsWith<EnzonaQrResponseException> { api.resolve(tokens(), owner, code) }
        }
        assertEquals(3, calls)
    }
}
