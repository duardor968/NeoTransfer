package dev.duardo.neotransfer.enzona

import java.io.IOException
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnzonaServiceApiTest {
    private val tokens = EnzonaTokens("SYNTHETIC_ACCESS", null, "SYNTHETIC_ID", Long.MAX_VALUE)
    private val encrypted = "SYNTHETIC_ENCRYPTED_PASSWORD"
    private val electricity = """{"client":[{"invoice":[{"client_id":"000000000001","owner":"SYNTHETIC OWNER","folio":"01","route":"02","period":"202609","amount":"125.50","total_amount":"120.00","charged":"false"}]}]}"""

    private fun account(type: String = "adsl_nauta", valid: String = "true"): EnzonaEtecsaAccount =
        EnzonaServiceJson.etecsaAccounts("""{"success":"true","resultmsg":"SYNTHETIC","name":"SYNTHETIC","datalist":{"data":[{
          "service_type":"$type","service_name":"synthetic@nauta.com.cu","number":"DIFFERENT_NUMBER","is_valid":"$valid",
          "to_pay":"80.00","current_debt":"50.00","unpaid_debt":"30.00","current_unpaid_debt":"20.00",
          "store_name":"STORE","store_code":"000","contract_eid":"CONTRACT","account_state_eid":"STATE",
          "address":"SYNTHETIC ADDRESS","partner_name":"SYNTHETIC OWNER"
        }]}}""").accounts.single()

    private fun body(request: Request): JsonObject = Buffer().let { buffer ->
        requireNotNull(request.body).writeTo(buffer)
        Json.parseToJsonElement(buffer.readUtf8()).jsonObject
    }

    @Test fun queriesUseProvenCodesAndCorrectlyEncodeFullIdentifiers() = runBlocking {
        val sent = mutableListOf<Request>()
        val api = EnzonaServiceApi(execute = { request ->
            sent += request
            EnzonaHttpReply(200, when {
                "client-id" in request.url.encodedPath -> """{"client_id":[]}"""
                request.url.queryParameter("service_code") == "2222" -> """{"client":[]}"""
                else -> """{"success":"true","datalist":{"data":[]}}"""
            })
        }, now = { 1234 })
        assertEquals(1234L, api.electricityBills(tokens, "0001").observedAt)
        api.electricityBills(tokens)
        api.etecsaAccounts(tokens, EnzonaInvoiceService.FIXED_PHONE, "70000000")
        api.etecsaAccounts(tokens, EnzonaInvoiceService.NAUTA, "test+synthetic@nauta.cu&x=1")
        api.etecsaAccounts(tokens, EnzonaInvoiceService.NAUTA_HOME, "synthetic@nauta.com.cu")
        api.savedEtecsaAccounts(tokens)
        api.references(tokens, EnzonaInvoiceService.ELECTRICITY)
        api.references(tokens, EnzonaInvoiceService.NAUTA_HOME)
        assertEquals(listOf("2222", "2222", "4441", "4443", "4445", "444", "2222", "444"), sent.map { it.url.queryParameter("service_code") })
        assertEquals("test+synthetic@nauta.cu&x=1", sent[3].url.queryParameter("client_id"))
        assertNull(sent[3].url.queryParameter("x"))
        assertNull(sent[1].url.queryParameter("client_id"))
        sent.forEachIndexed { index, request ->
            assertEquals("GET", request.method)
            assertNull(request.body)
            assertEquals("api.enzona.net", request.url.host)
            assertEquals("/invoice/v1.0.0/${if (index < 6) "invoice" else "client-id"}/search", request.url.encodedPath)
            assertEquals("Bearer SYNTHETIC_ACCESS", request.header("Authorization"))
            assertEquals("application/json", request.header("accept"))
            assertEquals("1.9.11.241103", request.header("Version"))
        }
    }

    @Test fun electricityEncodesNumericServiceCodeAndStringMetadataFromTheQueriedBill() {
        val bill = EnzonaServiceJson.electricityBills(electricity).single()
        val request = EnzonaServiceRequests.electricityPayment(bill, "synthetic-card", encrypted)
        val json = Json.parseToJsonElement(request.json).jsonObject
        assertEquals(JsonPrimitive(2222), json["service_code"])
        assertEquals(setOf("service_code", "funding_source_uuid", "client_id", "owner", "metadata", "period", "amount", "payment_password"), json.keys)
        assertEquals("125.50", json["amount"]!!.jsonPrimitive.content)
        assertEquals("202609", json["period"]!!.jsonPrimitive.content)
        assertTrue(json["metadata"]!!.jsonPrimitive.isString)
        assertEquals("""{"folio":"01","route":"02"}""", json["metadata"]!!.jsonPrimitive.content)
        assertEquals(encrypted, json["payment_password"]!!.jsonPrimitive.content)
        assertFalse(request.toString().contains(encrypted))
        assertFailsWith<IllegalArgumentException> { EnzonaServiceRequests.electricityPayment(bill.copy(period = null), "card", encrypted) }
    }

    @Test fun fixedPhoneAndTopUpsUseServiceNameRatherThanNumberAndNeverPayDebtByInference() {
        val fixed = Json.parseToJsonElement(EnzonaServiceRequests.fixedPhonePayment(account("tb"), "card", encrypted).json).jsonObject
        assertEquals(JsonPrimitive("4441"), fixed["service_code"])
        assertEquals(JsonPrimitive("80.00"), fixed["amount"])
        for (type in listOf("nauta", "adsl_nauta")) {
            val account = account(type)
            assertEquals(BigDecimal("50.00"), account.currentDebt)
            assertEquals(BigDecimal("30.00"), account.unpaidDebt)
            assertEquals(BigDecimal("20.00"), account.currentUnpaidDebt)
            val request = EnzonaServiceRequests.nautaTopUp(account, BigDecimal("250.75"), "card", encrypted)
            val json = Json.parseToJsonElement(request.json).jsonObject
            assertEquals(JsonPrimitive(if (type == "nauta") "4443" else "4445"), json["service_code"])
            assertEquals(JsonPrimitive("250.75"), json["amount"])
            assertEquals(setOf("service_code", "funding_source_uuid", "client_id", "owner", "amount", "payment_password"), json.keys)
            assertEquals(JsonPrimitive("synthetic@nauta.com.cu"), json["client_id"])
            assertEquals(json["client_id"], json["owner"])
            assertFalse(request.json.contains("DIFFERENT_NUMBER"))
        }
        assertFailsWith<IllegalArgumentException> { EnzonaServiceRequests.fixedPhonePayment(account(), "card", encrypted) }
        assertFailsWith<IllegalArgumentException> { EnzonaServiceRequests.nautaTopUp(account("ca"), BigDecimal.ONE, "card", encrypted) }
        assertFailsWith<IllegalArgumentException> { EnzonaServiceRequests.nautaTopUp(account(valid = "false"), BigDecimal.ONE, "card", encrypted) }
        assertEquals(4, EnzonaInvoiceService.entries.size) // No invented water/mobile/debt service code.
    }

    @Test fun remoteReferenceMetadataAndDistinctSaveEnvelopesArePreserved() = runBlocking {
        val sent = mutableListOf<Request>()
        val reference = """{"service_uuid":"remote-service","uuid":"remote-reference","client_id":"synthetic","metadata":"{\"service_type\":\"adsl_nauta\"}"}"""
        val api = EnzonaServiceApi(execute = { request ->
            sent += request
            EnzonaHttpReply(200, if (sent.size == 1) """{"client_id":$reference}""" else reference)
        })
        assertEquals("remote-service", api.saveElectricityReference(tokens, EnzonaServiceJson.electricityBills(electricity).single()).value.serviceUuid)
        assertEquals("remote-reference", api.saveEtecsaReference(tokens, account(), "card", "SYNTHETIC ALIAS").value.uuid)
        val electricalBody = body(sent[0])
        assertEquals(JsonPrimitive("2222"), electricalBody["service_code"])
        assertEquals(JsonPrimitive(""), electricalBody["funding_source_uuid"])
        val etecsaBody = body(sent[1])
        assertEquals(JsonPrimitive("0"), etecsaBody["automatic"])
        assertEquals(JsonPrimitive("SYNTHETIC ALIAS"), etecsaBody["owner"])
        assertEquals(setOf("store_name", "store_code", "contract_eid", "account_state_eid", "address", "service_type"),
            Json.parseToJsonElement(etecsaBody["metadata"]!!.jsonPrimitive.content).jsonObject.keys)
        sent.forEach { assertEquals("POST", it.method); assertEquals("/invoice/v1.0.0/client-id", it.url.encodedPath) }
        val list = EnzonaServiceJson.references("""{"client_id":[$reference]}""")
        assertEquals(JsonPrimitive("adsl_nauta"), list.single().metadata?.get("service_type"))
        assertFailsWith<IllegalArgumentException> { EnzonaServiceJson.savedReference(EnzonaInvoiceService.ELECTRICITY, reference) }
        Unit
    }

    @Test fun paymentsOnlyRecognizeFamilySpecificConclusiveEvidence() {
        fun decode(service: EnzonaInvoiceService, json: String, http: Int = 200) = EnzonaServiceJson.payment(service, http, json).state
        val electric = EnzonaInvoiceService.ELECTRICITY
        val nauta = EnzonaInvoiceService.NAUTA
        assertEquals(EnzonaServicePaymentState.ACCEPTED, decode(electric, """{"charged_invoice":{"status_code":1111}}"""))
        for (json in listOf("""{"charged_invoice":{"status_code":"1111"}}""", """{"charged_invoice":{"status_code":1121}}""",
            """{"charged_invoice":{"status_code":1111,"status":"Fallida"}}""", """{"status_code":1111}""", "not-json")) {
            assertEquals(EnzonaServicePaymentState.UNKNOWN, decode(electric, json))
        }
        for ((status, expected) in listOf("Aceptada" to EnzonaServicePaymentState.ACCEPTED, "Pendiente" to EnzonaServicePaymentState.PENDING,
            "Fallida" to EnzonaServicePaymentState.FAILED, "NEW_STATUS" to EnzonaServicePaymentState.UNKNOWN)) {
            assertEquals(expected, decode(nauta, """{"charged_invoice":{"status":"$status"}}"""))
        }
        assertEquals(EnzonaServicePaymentState.UNKNOWN, decode(nauta, """{"charged_invoice":{"status":"Aceptada"}}""", 401))
        assertEquals(EnzonaServicePaymentState.UNKNOWN, decode(nauta, """{"fault":{"code":123},"charged_invoice":{"status":"Aceptada"}}"""))
        val intent = EnzonaServiceJson.payment(electric, 200, """{"getPaymentPasswordIntent":{"synthetic":"AUTHORIZATION_RESULT"},"charged_invoice":{"status_code":1111}}""")
        assertTrue(intent.passwordIntent)
        assertEquals(EnzonaServicePaymentState.UNKNOWN, intent.state)
    }

    @Test fun absentEmptyAndNullPasswordIntentPreserveTheFamilyPaymentState() {
        for (service in EnzonaInvoiceService.entries) {
            val statuses = if (service == EnzonaInvoiceService.ELECTRICITY) {
                listOf("\"status_code\":1111" to EnzonaServicePaymentState.ACCEPTED,
                    "\"status_code\":1121" to EnzonaServicePaymentState.UNKNOWN)
            } else {
                listOf("\"status\":\"Aceptada\"" to EnzonaServicePaymentState.ACCEPTED,
                    "\"status\":\"Pendiente\"" to EnzonaServicePaymentState.PENDING,
                    "\"status\":\"Fallida\"" to EnzonaServicePaymentState.FAILED)
            }
            for ((status, expected) in statuses) {
                for (rootIntent in listOf("", "\"getPaymentPasswordIntent\":{},", "\"getPaymentPasswordIntent\":null,")) {
                    for (nestedIntent in listOf("", ",\"getPaymentPasswordIntent\":{}", ",\"getPaymentPasswordIntent\":null")) {
                        val result = EnzonaServiceJson.payment(service, 200, "{$rootIntent\"charged_invoice\":{$status$nestedIntent}}")
                        assertEquals(expected, result.state)
                        assertFalse(result.passwordIntent)
                    }
                }
            }
            for (json in listOf("{}", """{"getPaymentPasswordIntent":{}}""", """{"getPaymentPasswordIntent":null}""")) {
                assertEquals(EnzonaServicePaymentState.UNKNOWN, EnzonaServiceJson.payment(service, 200, json).state)
            }
        }
    }

    @Test fun nonEmptyOrMalformedPasswordIntentCannotConfirmAPayment() {
        for (service in EnzonaInvoiceService.entries) {
            for (value in listOf("""{"synthetic":"AUTHORIZATION_RESULT"}""", "false", "[]", "\"\"", "0")) {
                for (nested in listOf(false, true)) {
                    val intent = "\"getPaymentPasswordIntent\":$value"
                    val status = if (service == EnzonaInvoiceService.ELECTRICITY) "\"status_code\":1111" else "\"status\":\"Aceptada\""
                    val json = if (nested) "{\"charged_invoice\":{$status,$intent}}" else "{$intent,\"charged_invoice\":{$status}}"
                    val result = EnzonaServiceJson.payment(service, 200, json)
                    assertEquals(EnzonaServicePaymentState.UNKNOWN, result.state)
                    assertEquals(value.startsWith("{"), result.passwordIntent)
                }
            }
        }
    }

    @Test fun submitUsesOnePaymentPostAndDoesNotRetryAnUnknownResultOrTransportError() = runBlocking {
        var sends = 0
        val request = EnzonaServiceRequests.nautaTopUp(account(), BigDecimal("150.00"), "card", encrypted)
        val api = EnzonaServiceApi(execute = { outgoing ->
            sends++
            assertEquals("POST", outgoing.method)
            assertEquals("/invoice/v1.0.0/invoice/payment", outgoing.url.encodedPath)
            assertEquals(Json.parseToJsonElement(request.json), body(outgoing))
            EnzonaHttpReply(401, """{"fault":{"code":900901,"description":"Invalid Credentials"}}""")
        })
        val result = api.submit(tokens, request)
        assertEquals(EnzonaServicePaymentState.UNKNOWN, result.state)
        assertEquals("900901", result.fault?.code)
        assertEquals(1, sends)
        val broken = EnzonaServiceApi(execute = { sends++; throw IOException("SYNTHETIC_TRANSPORT_FAILURE") })
        assertFailsWith<IOException> { broken.submit(tokens, request) }
        assertEquals(2, sends)
    }

    @Test fun malformedEnvelopesAreNotEmptyListsAndReadFaultsRemainStructured() = runBlocking {
        assertFailsWith<IllegalArgumentException> { EnzonaServiceJson.electricityBills("""{"invoice":[]}""") }
        assertFailsWith<IllegalArgumentException> { EnzonaServiceJson.etecsaAccounts("""{"data":[]}""") }
        assertFailsWith<IllegalArgumentException> { EnzonaServiceJson.references("""{"client_id":"synthetic"}""") }
        assertEquals(emptyList(), EnzonaServiceJson.electricityBills("""{"client":[]}"""))
        val api = EnzonaServiceApi(execute = { EnzonaHttpReply(200, """{"fault":{"cod":123,"message":"SYNTHETIC"}}""") })
        val exception = assertFailsWith<EnzonaServiceException> { api.electricityBills(tokens, "id") }
        assertEquals("123", exception.fault?.code)
        assertFalse(exception.toString().contains("SYNTHETIC"))
    }
}
