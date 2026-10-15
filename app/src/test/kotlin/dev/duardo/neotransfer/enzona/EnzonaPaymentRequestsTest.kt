package dev.duardo.neotransfer.enzona

import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import javax.crypto.Cipher
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EnzonaPaymentRequestsTest {
    private val requests = EnzonaPaymentRequests(pair.public as RSAPublicKey)

    @Test fun incompleteOrNonPositivePaymentCannotBeEncodedAndStillClearsThePassword() {
        for (draft in listOf(card().copy(fundingSourceUuid = ""), card().copy(amount = BigDecimal.ZERO),
            card().copy(amount = BigDecimal("-1")), card().copy(currency = ""),
            account().copy(fundingRecipientUuid = ""), account().copy(fundingRecipientUsername = ""))) {
            val password = "synthetic".toCharArray()
            assertFailsWith<IllegalArgumentException> { requests.encode(draft, password) }
            assertCleared(password)
        }
    }

    private fun card() = EnzonaCardTransferDraft(
        fundingSourceUuid = "synthetic-source-uuid",
        recipientPan = "1234 5678 9012 3456",
        amount = BigDecimal("12345678901234567890.1200"),
        currency = "cup",
        description = "Descripción \"revisada\"\nñ\\detalle",
        phone = "synthetic-phone",
    )

    private fun account() = EnzonaAccountTransferDraft(
        fundingSourceUuid = "synthetic-source-uuid",
        fundingRecipientUuid = "synthetic-recipient-uuid",
        fundingRecipientUsername = "synthetic-recipient@carbon.super",
        amount = BigDecimal("20.00"),
        currency = "CUP",
    )

    private fun body(request: EnzonaPaymentRequest): JsonObject = Json.parseToJsonElement(request.json).jsonObject
    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content
    private fun assertCleared(password: CharArray) = assertTrue(password.all { it == '\u0000' })
    private fun decrypted(encoded: String, keyPair: KeyPair = pair): ByteArray =
        Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.DECRYPT_MODE, keyPair.private)
            doFinal(Base64.getDecoder().decode(encoded))
        }

    @Test fun cardRequestEncryptsDestinationAndPasswordAndPreservesReviewedValues() {
        val draft = card()
        val password = charArrayOf('f', 'i', 'x', 't', 'u', 'r', 'e', '-', 'ñ', '€')
        val expectedPassword = StandardCharsets.UTF_8.newEncoder()
            .encode(java.nio.CharBuffer.wrap(password)).let { buffer ->
                ByteArray(buffer.remaining()).also(buffer::get)
            }
        val request = requests.encode(draft, password)
        assertCleared(password)
        val json = body(request)
        assertEquals("POST", request.method)
        assertEquals("/transfer/v1.0.0/transfer/card", request.path)
        assertEquals("https://api.enzona.net${request.path}", request.url)
        assertEquals(setOf("amount", "pan", "funding_source_uuid", "description", "currency", "fingerprint", "phone", "payment_password"), json.keys)
        assertEquals("12345678901234567890.1200", json.text("amount"))
        assertTrue(json.getValue("amount").jsonPrimitive.isString)
        assertEquals(draft.fundingSourceUuid, json.text("funding_source_uuid"))
        assertEquals(draft.currency, json.text("currency"))
        assertEquals(draft.description, json.text("description"))
        assertEquals(draft.phone, json.text("phone"))
        assertEquals("", json.text("fingerprint"))
        assertContentEquals("1234567890123456".toByteArray(StandardCharsets.UTF_8), decrypted(json.text("pan")))
        assertContentEquals(expectedPassword, decrypted(json.text("payment_password")))
        assertFalse(request.json.contains(draft.recipientPan))
        assertEquals(256, Base64.getDecoder().decode(json.text("payment_password")).size)
        assertEquals(344, json.text("payment_password").length)
        expectedPassword.fill(0)
    }

    @Test fun accountRequestKeepsRecipientUsernameAndUuidSeparateAndDoesNotConvertCurrency() {
        val draft = account()
        val password = charArrayOf('t', 'e', 's', 't')
        val request = requests.encode(draft, password)
        assertCleared(password)
        val json = body(request)
        assertEquals("/transfer/v1.0.0/transfer/account", request.path)
        assertEquals(setOf("amount", "funding_recipient_username", "funding_source_uuid", "description", "currency", "funding_recipient_uuid", "fingerprint", "type_transfer", "payment_password"), json.keys)
        assertEquals(draft.fundingRecipientUsername, json.text("funding_recipient_username"))
        assertEquals(draft.fundingRecipientUuid, json.text("funding_recipient_uuid"))
        assertEquals(draft.fundingSourceUuid, json.text("funding_source_uuid"))
        assertEquals("20.00", json.text("amount"))
        assertEquals("CUP", json.text("currency"))
        assertEquals("", json.text("description"))
        assertEquals("", json.text("type_transfer"))
        assertEquals("", json.text("fingerprint"))
        assertFalse("pan" in json)
    }

    @Test fun merchantQrCannotRequestCashAdvanceAndSafelyEscapesCodeAndDescription() {
        val draft = EnzonaQrMerchantPaymentDraft(
            fundingSourceUuid = "synthetic-source",
            vendorIdentityCode = "07-synthetic-code\"\\\n",
            amount = BigDecimal("1.2300"),
            currency = "cup",
            description = "texto\tcon\"escape",
            phone = "synthetic-phone",
        )
        val password = charArrayOf('q', 'r', '-', 't', 'e', 's', 't')
        val request = requests.encode(draft, password)
        assertCleared(password)
        val json = body(request)
        assertEquals("POST", request.method)
        assertEquals("/payment/v1.0.0/payments/vendor/code", request.path)
        assertEquals(setOf("amount", "funding_source_uuid", "description", "vendor_identity_code", "currency", "cash_advance", "phone", "fingerprint", "payment_password"), json.keys)
        assertEquals("false", json.getValue("cash_advance").jsonPrimitive.content)
        assertFalse(json.getValue("cash_advance").jsonPrimitive.isString)
        assertEquals(draft.vendorIdentityCode, json.text("vendor_identity_code"))
        assertEquals(draft.description, json.text("description"))
        assertEquals(draft.phone, json.text("phone"))
        assertEquals("1.2300", json.text("amount"))
        assertEquals("cup", json.text("currency"))
        assertContentEquals("qr-test".toByteArray(StandardCharsets.UTF_8), decrypted(json.text("payment_password")))
    }

    @Test fun decimalSerializationDoesNotUseFloatingPointRoundingOrScientificNotation() {
        val draft = account().copy(amount = BigDecimal("1E+20"), currency = "literal-origin-currency")
        val json = body(requests.encode(draft, charArrayOf('t')))
        assertEquals("100000000000000000000", json.text("amount"))
        assertEquals("literal-origin-currency", json.text("currency"))
        assertEquals("20.00", account().amount.toPlainString())
    }

    @Test fun rsaPaddingIsRandomizedAndDoesNotMutateTheReviewedDraft() {
        val draft = card()
        val first = body(requests.encode(draft, charArrayOf('s', 'a', 'm', 'e')))
        val second = body(requests.encode(draft, charArrayOf('s', 'a', 'm', 'e')))
        assertNotEquals(first.text("payment_password"), second.text("payment_password"))
        assertNotEquals(first.text("pan"), second.text("pan"))
        assertContentEquals(decrypted(first.text("pan")), decrypted(second.text("pan")))
        assertEquals(card(), draft)
    }

    @Test fun blockBoundaryUsesUtf8BytesAndPasswordIsClearedOnRejection() {
        val exact = CharArray(245) { 'a' }
        val json = body(requests.encode(account(), exact))
        assertCleared(exact)
        assertEquals(245, decrypted(json.text("payment_password")).size)
        for (oversize in listOf(CharArray(246) { 'a' }, CharArray(123) { 'é' })) {
            assertFailsWith<IllegalArgumentException> { requests.encode(account(), oversize) }
            assertCleared(oversize)
        }
        val unicodeBoundary = CharArray(244) { if (it == 0) 'é' else 'a' }
        assertEquals(245, decrypted(body(requests.encode(account(), unicodeBoundary)).text("payment_password")).size)
        assertCleared(unicodeBoundary)
    }

    @Test fun blockBoundaryComesFromInjectedKeyModulusRatherThanTheBundledKeySize() {
        val smaller = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val encoder = EnzonaPaymentRequests(smaller.public as RSAPublicKey)
        val accepted = CharArray(117) { 'a' }
        val request = encoder.encode(account(), accepted)
        assertCleared(accepted)
        assertEquals(117, decrypted(body(request).text("payment_password"), smaller).size)
        val rejected = CharArray(118) { 'a' }
        assertFailsWith<IllegalArgumentException> { encoder.encode(account(), rejected) }
        assertCleared(rejected)
    }

    @Test fun invalidPanIsRejectedWithoutInventingDigitsOrKeepingThePassword() {
        for (pan in listOf("123456789012345", "12345678901234567", "1234-5678-9012-3456", "abcdefghijklmnop", "１２３４５６７８９０１２３４５６")) {
            val password = charArrayOf('s', 'e', 'c', 'r', 'e', 't')
            val error = assertFailsWith<IllegalArgumentException> { requests.encode(card().copy(recipientPan = pan), password) }
            assertCleared(password)
            assertEquals("La tarjeta destinataria debe contener 16 dígitos", error.message)
        }
    }

    @Test fun malformedUtf16AndEmptyPasswordDoNotProduceARequest() {
        val malformed = charArrayOf('\uD800')
        assertFailsWith<IllegalArgumentException> { requests.encode(account(), malformed) }
        assertCleared(malformed)
        assertFailsWith<IllegalArgumentException> { requests.encode(account(), charArrayOf()) }
    }

    @Test fun passwordIsClearedWhenTheCryptoProviderFails() {
        val encoder = EnzonaPaymentRequests(pair.public as RSAPublicKey, object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) { throw IllegalStateException("Synthetic crypto failure") }
        })
        val password = charArrayOf('t', 'e', 's', 't')
        assertFailsWith<IllegalStateException> { encoder.encode(account(), password) }
        assertCleared(password)
    }

    @Test fun syntheticSpkiPemCanBeLoadedButOtherPemLabelsAreRejected() {
        val base64 = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(pair.public.encoded)
        val pem = "-----BEGIN PUBLIC KEY-----\n$base64\n-----END PUBLIC KEY-----"
        val key = EnzonaPaymentRequests.publicKeyFromPem(pem)
        assertEquals((pair.public as RSAPublicKey).modulus, key.modulus)
        assertFailsWith<IllegalArgumentException> {
            EnzonaPaymentRequests.publicKeyFromPem(pem.replace("PUBLIC KEY", "RSA PUBLIC KEY"))
        }
    }

    @Test fun draftsAndRequestsHaveRedactedDiagnosticRepresentations() {
        val merchant = EnzonaQrMerchantPaymentDraft("synthetic-source", "synthetic-code", BigDecimal("1.00"), "cup")
        assertEquals("EnzonaCardTransferDraft(redacted)", card().toString())
        assertEquals("EnzonaAccountTransferDraft(redacted)", account().toString())
        assertEquals("EnzonaQrMerchantPaymentDraft(redacted)", merchant.toString())
        assertEquals("EnzonaPaymentRequest(redacted)", requests.encode(card(), charArrayOf('t')).toString())
    }

    @Test fun electricityInvoiceHasIntegerCodeStringMetadataAndEncryptedPasswordOnlyOnWire() {
        val draft = EnzonaInvoicePaymentDraft(EnzonaInvoiceService.ELECTRICITY, "0000123", "Titular \"synthetic\"",
            "remote-card", BigDecimal("15.0000"), "cup", "local review only", "folio \"one\"", "route\\two", "202610")
        val secret = charArrayOf('f', 'i', 'x', 't', 'u', 'r', 'e')
        val request = requests.encode(draft, secret)
        assertCleared(secret)
        assertEquals("POST", request.method)
        assertEquals("https://api.enzona.net/invoice/v1.0.0/invoice/payment", request.url)
        assertEquals(EnzonaInvoiceService.ELECTRICITY, request.service)
        val json = body(request)
        assertEquals(setOf("service_code", "funding_source_uuid", "client_id", "owner", "metadata", "period", "amount", "payment_password"), json.keys)
        assertEquals("2222", json.text("service_code"))
        assertFalse(json.getValue("service_code").jsonPrimitive.isString)
        assertEquals("0000123", json.text("client_id"))
        assertEquals(draft.owner, json.text("owner"))
        assertEquals("15.0000", json.text("amount"))
        assertTrue(json.getValue("amount").jsonPrimitive.isString)
        assertTrue(json.getValue("metadata").jsonPrimitive.isString)
        val metadata = Json.parseToJsonElement(json.text("metadata")).jsonObject
        assertEquals(setOf("folio", "route"), metadata.keys)
        assertEquals(draft.folio, metadata.text("folio"))
        assertEquals(draft.route, metadata.text("route"))
        assertEquals("202610", json.text("period"))
        assertContentEquals("fixture".toByteArray(StandardCharsets.UTF_8), decrypted(json.text("payment_password")))
        assertEquals("EnzonaInvoicePaymentDraft(redacted)", draft.toString())
    }

    @Test fun etecsaFamiliesKeepStringCodesAndExactServiceNameWithoutInventingExtraKeys() {
        for ((service, code) in listOf(EnzonaInvoiceService.FIXED_PHONE to "4441", EnzonaInvoiceService.NAUTA to "4443",
            EnzonaInvoiceService.NAUTA_HOME to "4445")) {
            val serviceName = "  synthetic-${service.name}@provider  "
            val draft = EnzonaInvoicePaymentDraft(service, serviceName, serviceName, "card-uuid", BigDecimal("123.4500"), "CUP")
            val secret = charArrayOf('t', 'e', 's', 't')
            val request = requests.encode(draft, secret)
            assertCleared(secret)
            assertEquals(service, request.service)
            val json = body(request)
            assertEquals(setOf("service_code", "funding_source_uuid", "client_id", "owner", "amount", "payment_password"), json.keys)
            assertEquals(code, json.text("service_code"))
            assertTrue(json.getValue("service_code").jsonPrimitive.isString)
            assertEquals(serviceName, json.text("client_id"))
            assertEquals(serviceName, json.text("owner"))
            assertEquals("123.4500", json.text("amount"))
            assertContentEquals("test".toByteArray(StandardCharsets.UTF_8), decrypted(json.text("payment_password")))
        }
    }

    @Test fun incompleteInvoiceIdentityIsRejectedAndPasswordIsCleared() {
        val electric = EnzonaInvoicePaymentDraft(EnzonaInvoiceService.ELECTRICITY, "client", "owner", "card", BigDecimal.ONE,
            "cup", folio = "folio", route = "route", period = "period")
        val invalid = listOf(electric.copy(folio = null), electric.copy(route = ""), electric.copy(period = null),
            electric.copy(clientId = ""), electric.copy(owner = "\n"),
            EnzonaInvoicePaymentDraft(EnzonaInvoiceService.NAUTA, "service-name", "unrelated-owner", "card", BigDecimal.ONE, "cup"))
        for (draft in invalid) {
            val secret = charArrayOf('t', 'e', 's', 't')
            assertFailsWith<IllegalArgumentException> { requests.encode(draft, secret) }
            assertCleared(secret)
        }
    }

    private companion object {
        val pair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
}
