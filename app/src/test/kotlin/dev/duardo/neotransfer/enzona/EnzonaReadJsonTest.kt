package dev.duardo.neotransfer.enzona

import java.math.BigDecimal
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnzonaReadJsonTest {
    // All input below is synthetic; no APK fixtures, account data or banking requests.
    private val cardUuid = "00000000-0000-4000-8000-000000000001"
    private val transactionUuid = "00000000-0000-4000-8000-000000000002"

    private fun cards(row: String) = """{"card":[$row]}"""
    private fun history(rows: String, pagination: String = """{"total":1}""") =
        """{"transaction":[$rows],"pagination":$pagination}"""

    @Test fun cardIdentityAndBankMetadataAreIndependentAndUnfiltered() {
        val result = EnzonaReadJson.cards(cards("""{
            "funding_source_uuid":"$cardUuid","funding_source_id":"source-test",
            "last4":"0007","cardholder":"Synthetic Holder","expdate":"12/30",
            "created_at":"2026-10-07 12:34:56","updated_at":"source date without zone",
            "status":"pending","currency":"CUP","primary_source":"1",
            "bank_name":"Synthetic Other Bank","bank_code":"unknown-bank",
            "verified":"0","deleted":false,"bank_certificate":"CERT_TEST_ONLY","bin":123456
        }""")).single()
        assertEquals(cardUuid, result.fundingSourceUuid)
        assertEquals("source-test", result.fundingSourceId)
        assertEquals("0007", result.last4)
        assertEquals("Synthetic Holder", result.cardholder)
        assertEquals("12/30", result.expiryDate)
        assertEquals("2026-10-07 12:34:56", result.createdAt?.value)
        assertEquals("source date without zone", result.updatedAt?.value)
        assertEquals("pending", result.status)
        assertEquals("CUP", result.currency)
        assertEquals("1", result.primarySource)
        assertEquals("Synthetic Other Bank", result.bankName)
        assertEquals("unknown-bank", result.bankCode)
        assertEquals("0", result.verified)
        assertEquals(JsonPrimitive(false), result.deleted)
        assertEquals("CERT_TEST_ONLY", result.bankCertificate)
        assertEquals(123456, result.bin)
    }

    @Test fun cardNormalizesOnlyTheAotTracedStringConversions() {
        val result = EnzonaReadJson.cards(cards("""{
            "funding_source_uuid":"$cardUuid","funding_source_id":null,
            "last4":9876,"expdate":1230,"status":0,"primary_source":false,
            "bank_code":9007199254740993,"verified":true,"deleted":null,"bin":"123456"
        }""")).single()
        assertEquals("9876", result.last4)
        assertEquals("1230", result.expiryDate)
        assertEquals("0", result.status)
        assertEquals("false", result.primarySource)
        assertEquals("9007199254740993", result.bankCode)
        assertEquals("true", result.verified)
        assertEquals(JsonNull, result.deleted)
        assertNull(result.fundingSourceId)
        assertNull(result.currency)
        assertNull(result.bankCertificate)
        assertEquals(123456, result.bin)
        for (field in listOf("funding_source_id", "cardholder", "bank_name", "bank_certificate")) {
            assertFailsWith<IllegalArgumentException> {
                EnzonaReadJson.cards(cards("""{"funding_source_uuid":"$cardUuid","$field":123}"""))
            }
        }
    }

    @Test fun cardOptionalNullAndFalseAreNeverTurnedIntoAnEmptyStringOrInventedId() {
        val minimal = EnzonaReadJson.cards(cards("""{"funding_source_uuid":"$cardUuid"}""")).single()
        assertNull(minimal.last4)
        assertNull(minimal.fundingSourceId)
        assertNull(minimal.createdAt)
        assertNull(minimal.deleted)
        assertNull(minimal.bin)
        val explicitNull = EnzonaReadJson.cards(cards("""{
            "funding_source_uuid":"$cardUuid","last4":null,"primary_source":null,"deleted":false
        }""")).single()
        assertNull(explicitNull.last4)
        assertNull(explicitNull.primarySource)
        assertEquals(JsonPrimitive(false), explicitNull.deleted)
    }

    @Test fun balanceReadsTheFlatEnvelopeAndPreservesIndependentExactAmounts() {
        val result = EnzonaReadJson.balance("""{
            "balance":9007199254740993.12345678901234567890,
            "balance_available":"-0.00000000000000000001","currency":"CUP",
            "bank_account":"ACCOUNT_TEST_ONLY","status_code":"17","status_denom":"pending",
            "transaction_signature":"SIGNATURE_TEST_ONLY","operation":[]
        }""")
        assertEquals(BigDecimal("9007199254740993.12345678901234567890"), result.bookBalance)
        assertEquals(BigDecimal("-0.00000000000000000001"), result.availableBalance)
        assertEquals("CUP", result.currency)
        assertEquals("ACCOUNT_TEST_ONLY", result.bankAccount)
        assertEquals(17, result.statusCode)
        assertEquals("pending", result.statusDenomination)
        assertEquals("SIGNATURE_TEST_ONLY", result.transactionSignature)
        assertEquals(emptyList(), result.operations)
    }

    @Test fun absentBalanceAndAvailableBalanceDoNotSubstituteEachOther() {
        val bookOnly = EnzonaReadJson.balance("""{"balance":"123.40","operation":[]}""")
        assertEquals(BigDecimal("123.40"), bookOnly.bookBalance)
        assertNull(bookOnly.availableBalance)
        val availableOnly = EnzonaReadJson.balance("""{"balance_available":5,"operation":[]}""")
        assertNull(availableOnly.bookBalance)
        assertEquals(BigDecimal("5"), availableOnly.availableBalance)
        val unavailable = EnzonaReadJson.balance("""{
            "balance":false,"balance_available":null,"status_code":0,"status_denom":"unavailable",
            "operation":[]
        }""")
        assertNull(unavailable.bookBalance)
        assertNull(unavailable.availableBalance)
        assertEquals(JsonPrimitive(false), unavailable.original["balance"])
        assertEquals(JsonNull, unavailable.original["balance_available"])
        assertEquals(0, unavailable.statusCode)
        assertEquals("unavailable", unavailable.statusDenomination)
    }

    @Test fun bankRowsRetainOriginalReferencesMetadataAndDuplicatesWithoutGeneratedIds() {
        val row = """{
            "operationDate":"07/10/2026 12:34","action":"DB","channel":"synthetic-channel",
            "description":"Synthetic debit","amount":"-9999999999999999.123456789",
            "codigo":"007","avatar":null,"alias":"synthetic-alias",
            "id":"BANK_ROW_TEST_ONLY","reference":"REF_TEST_ONLY",
            "metadata":{"applicable":false,"detail":null,"amount":9007199254740993.1}
        }"""
        val result = EnzonaReadJson.bankOperations("""{"operation":[$row,$row]}""")
        assertEquals(2, result.size)
        assertEquals(result[0], result[1])
        assertEquals("07/10/2026 12:34", result[0].operationDate?.value)
        assertEquals("DB", result[0].action)
        assertEquals("synthetic-channel", result[0].channel)
        assertEquals("Synthetic debit", result[0].description)
        assertEquals(BigDecimal("-9999999999999999.123456789"), result[0].amount)
        assertEquals("007", result[0].code)
        assertNull(result[0].avatar)
        assertEquals("synthetic-alias", result[0].alias)
        assertEquals(JsonPrimitive("BANK_ROW_TEST_ONLY"), result[0].original["id"])
        assertEquals(JsonPrimitive("REF_TEST_ONLY"), result[0].original["reference"])
        val metadata = result[0].original["metadata"] as JsonObject
        assertEquals(JsonPrimitive(false), metadata["applicable"])
        assertEquals(JsonNull, metadata["detail"])
        assertEquals("9007199254740993.1", (metadata["amount"] as JsonPrimitive).content)
        val fromBalance = EnzonaReadJson.balance("""{"balance":0,"operation":[$row]}""")
        assertEquals(result.first(), fromBalance.operations.single())
    }

    @Test fun unknownBankRowIdentityStaysAbsentAndNullAmountIsNotZero() {
        val result = EnzonaReadJson.bankOperations("""{"operation":[{"amount":null},{}]}""")
        assertNull(result[0].amount)
        assertNull(result[1].amount)
        assertNull(result[0].operationDate)
        assertNull(result[0].original["id"])
        assertNull(result[1].original["reference"])
        assertEquals(JsonNull, result[0].original["amount"])
    }

    @Test fun accountHistoryDecodesItsOwnStatusFieldsMoneyAndNestedParties() {
        val result = EnzonaReadJson.transactions(history("""{
            "transaction_uuid":"$transactionUuid","owner":"synthetic-owner","client_id":15,
            "invoice":false,"invoice_number":7,"merchant_alias":"synthetic-merchant",
            "merchant_avatar":false,"merchant_name":"Synthetic Merchant",
            "transaction_status_code":"9","status_code":0,"transaction_status":"pending",
            "transaction_created_at":"2026-10-07T12:34:56","transaction_signature":"SIGNATURE_TEST_ONLY",
            "period":"source-period","amount":9007199254740993.12345678901234567890,
            "total_amount":"9007199254740994.12345678901234567890","currency":"USD",
            "transaction_denom":"source-denom","status_denom":"source-status-denom",
            "transaction_code":"12","barcode":"false","donation_title":null,
            "transaction_description":"Synthetic description","metadata":{"enabled":false},
            "bank_debit_detail":"{\"detail\":null}",
            "source":{"funding_source_last4":9876,"funding_source_bank":"99",
                "funding_source_amount":"-0.000000000000000001","funding_source_verified":"0"},
            "recipient":{"funding_recipient_last4":"0007","funding_recipient_bank":101,
                "funding_recipient_verified":false,"funding_recipient_username":"synthetic-recipient"}
        }""")).transactions.single()
        assertEquals(transactionUuid, result.transactionUuid)
        assertEquals("synthetic-owner", result.owner)
        assertEquals("15", result.clientId)
        assertEquals("false", result.invoice)
        assertEquals("7", result.invoiceNumber)
        assertEquals("synthetic-merchant", result.merchantAlias)
        assertEquals("false", result.merchantAvatar)
        assertEquals("Synthetic Merchant", result.merchantName)
        assertEquals(9, result.transactionStatusCode)
        assertEquals(0, result.statusCode)
        assertEquals("pending", result.transactionStatus)
        assertEquals("2026-10-07T12:34:56", result.createdAt?.value)
        assertEquals("SIGNATURE_TEST_ONLY", result.signature)
        assertEquals("source-period", result.period)
        assertEquals(BigDecimal("9007199254740993.12345678901234567890"), result.amount)
        assertEquals(BigDecimal("9007199254740994.12345678901234567890"), result.totalAmount)
        assertEquals("USD", result.currency)
        assertEquals("source-denom", result.transactionDenomination)
        assertEquals("source-status-denom", result.statusDenomination)
        assertEquals(12, result.transactionCode)
        assertEquals(false, result.barcode)
        assertNull(result.donationTitle)
        assertEquals("Synthetic description", result.description)
        assertEquals(JsonPrimitive(false), (result.metadata as JsonObject)["enabled"])
        assertEquals(JsonPrimitive("{\"detail\":null}"), result.bankDebitDetail)
        val source = assertNotNull(result.source)
        assertEquals("9876", source.last4)
        assertEquals(99, source.bank)
        assertEquals(BigDecimal("-0.000000000000000001"), source.amount)
        assertEquals(0, source.verified)
        val recipient = assertNotNull(result.recipient)
        assertEquals("0007", recipient.last4)
        assertEquals("101", recipient.bank)
        assertEquals("false", recipient.verified)
        assertEquals("synthetic-recipient", recipient.username)
    }

    @Test fun paginationPreservesMultiplePagesAndRepeatedTransactionUuids() {
        val row = """{"transaction_uuid":"$transactionUuid","amount":null}"""
        val first = EnzonaReadJson.transactions(history("$row,$row", """{
            "first":"?offset=0&limit=2","prev":false,"next":"?offset=2&limit=2",
            "last":"?offset=2&limit=2","total":3
        }"""))
        val last = EnzonaReadJson.transactions(history(row, """{
            "first":"?offset=0&limit=2","prev":"?offset=0&limit=2","next":null,
            "last":"?offset=2&limit=2","total":3
        }"""))
        assertEquals(2, first.transactions.size)
        assertEquals(first.transactions[0], first.transactions[1])
        assertEquals(1, last.transactions.size)
        assertEquals(transactionUuid, last.transactions.single().transactionUuid)
        assertNull(last.transactions.single().amount)
        assertEquals(3L, first.pagination.total)
        assertEquals(JsonPrimitive(false), first.pagination.previous)
        assertEquals(JsonPrimitive("?offset=2&limit=2"), first.pagination.next)
        assertEquals(JsonNull, last.pagination.next)
        val numericLinks = EnzonaReadJson.transactions(history(row, """{"first":0,"next":2,"total":3}"""))
        assertEquals(JsonPrimitive(2), numericLinks.pagination.next)
        assertNull(numericLinks.pagination.previous)
    }

    @Test fun optionalFalseNullAndAbsentTransactionFieldsRemainDistinctInOriginal() {
        val result = EnzonaReadJson.transactions(history("""{
            "transaction_uuid":"$transactionUuid","amount":false,"total_amount":null,
            "metadata":false,"bank_debit_detail":null,"source":false,"recipient":null
        }""")).transactions.single()
        assertNull(result.amount)
        assertNull(result.totalAmount)
        assertNull(result.createdAt)
        assertNull(result.source)
        assertNull(result.recipient)
        assertEquals(JsonPrimitive(false), result.metadata)
        assertEquals(JsonNull, result.bankDebitDetail)
        assertEquals(JsonPrimitive(false), result.original["amount"])
        assertEquals(JsonPrimitive(false), result.original["source"])
        assertEquals(JsonNull, result.original["recipient"])
        assertNull(result.original["transaction_status"])
        val absent = EnzonaReadJson.transactions(history("""{"transaction_uuid":"$transactionUuid"}"""))
            .transactions.single()
        assertNull(absent.metadata)
        assertNull(absent.bankDebitDetail)
    }

    @Test fun onlyExplicitEmptyListsAreAcceptedAndFaultsCannotMasqueradeAsSuccess() {
        assertEquals(emptyList(), EnzonaReadJson.cards("""{"card":[]}"""))
        assertEquals(emptyList(), EnzonaReadJson.bankOperations("""{"operation":[]}"""))
        assertEquals(emptyList(), EnzonaReadJson.transactions(history("", """{"total":0}""")).transactions)
        val decoders: List<(String) -> Any> = listOf(
            EnzonaReadJson::cards, EnzonaReadJson::balance,
            EnzonaReadJson::bankOperations, EnzonaReadJson::transactions,
        )
        for (decode in decoders) {
            for (invalid in listOf("", "{}", "[]", "null", "{", "{} trailing")) {
                assertFailsWith<IllegalArgumentException> { decode(invalid) }
            }
            for (key in listOf("fault", "error", "errors")) {
                for (value in listOf("{}", "null", "false", "\"\"")) {
                    val error = """{"$key":$value,"card":[],"operation":[],
                        "balance":0,"transaction":[],"pagination":{"total":0}}"""
                    assertFailsWith<IllegalArgumentException> { decode(error) }
                }
            }
        }
        for (value in listOf("null", "false", "{}", "\"\"", "[null]", "[false]")) {
            assertFailsWith<IllegalArgumentException> { EnzonaReadJson.cards("""{"card":$value}""") }
            assertFailsWith<IllegalArgumentException> { EnzonaReadJson.bankOperations("""{"operation":$value}""") }
            assertFailsWith<IllegalArgumentException> {
                EnzonaReadJson.transactions("""{"transaction":$value,"pagination":{"total":0}}""")
            }
        }
    }

    @Test fun missingIdentityCannotUseSourceIdCardUuidOrTransactionCodeAsReplacement() {
        for (value in listOf("null", "false", "123", "\"\"", "\"  \"", "{}", "[]")) {
            assertFailsWith<IllegalArgumentException> {
                EnzonaReadJson.cards(cards("""{"funding_source_uuid":$value,"funding_source_id":"test"}"""))
            }
            assertFailsWith<IllegalArgumentException> {
                EnzonaReadJson.transactions(history("""{"transaction_uuid":$value,"transaction_code":7}"""))
            }
        }
        assertFailsWith<IllegalArgumentException> {
            EnzonaReadJson.cards(cards("""{"card_uuid":"$cardUuid","funding_source_id":"test"}"""))
        }
        assertFailsWith<IllegalArgumentException> {
            EnzonaReadJson.transactions(history("""{"id":"$transactionUuid","transaction_code":7}"""))
        }
    }

    @Test fun wrongShapesContradictoryTypesAndMalformedNumbersFailWithoutCoercion() {
        for (value in listOf("true", "\"NaN\"", "\"Infinity\"", "\"1,25\"", "\"\"", "{}", "[]")) {
            assertFailsWith<IllegalArgumentException> {
                EnzonaReadJson.balance("""{"balance":$value,"operation":[]}""")
            }
            assertFailsWith<IllegalArgumentException> {
                EnzonaReadJson.bankOperations("""{"operation":[{"amount":$value}]}""")
            }
            assertFailsWith<IllegalArgumentException> {
                EnzonaReadJson.transactions(history("""{"transaction_uuid":"$transactionUuid","amount":$value}"""))
            }
        }
        for (value in listOf("false", "1.2", "\"1.2\"", "2147483648")) {
            assertFailsWith<IllegalArgumentException> {
                EnzonaReadJson.balance("""{"balance":0,"operation":[],"status_code":$value}""")
            }
        }
        for (value in listOf("17", "\"\"")) {
            assertFailsWith<IllegalArgumentException> {
                EnzonaReadJson.cards(cards("""{"funding_source_uuid":"$cardUuid","currency":$value}"""))
            }
        }
        assertFailsWith<IllegalArgumentException> { EnzonaReadJson.balance("""{"balance":{"balance":5},"operation":[]}""") }
        assertFailsWith<IllegalArgumentException> { EnzonaReadJson.balance("""{"balance":5}""") }
        assertFailsWith<IllegalArgumentException> { EnzonaReadJson.transactions("""{"transaction":[]}""") }
        assertFailsWith<IllegalArgumentException> { EnzonaReadJson.transactions(history("", "null")) }
        assertFailsWith<IllegalArgumentException> { EnzonaReadJson.transactions(history("", """{"total":"1"}""")) }
        assertFailsWith<IllegalArgumentException> {
            EnzonaReadJson.transactions(history("""{"transaction_uuid":"$transactionUuid","source":true}"""))
        }
    }

    @Test fun lowerCaseCurrencyFromTheObservedBalanceShapeIsPreserved() {
        val result = EnzonaReadJson.balance("""{"balance":12.50,"currency":"cup","operation":[]}""")
        assertEquals("cup", result.currency)
        assertEquals(BigDecimal("12.50"), result.bookBalance)
        val unfamiliar = EnzonaReadJson.balance("""{"balance":12.50,"currency":"provider-value","operation":[]}""")
        assertEquals("provider-value", unfamiliar.currency)
    }

    @Test fun scientificNotationAndScaleArePreservedThroughTheNumericLexeme() {
        val result = EnzonaReadJson.balance("""{
            "balance":1.234567890123456789e+30,"balance_available":0.0100,"operation":[]
        }""")
        assertEquals(BigDecimal("1.234567890123456789e+30"), result.bookBalance)
        assertEquals(BigDecimal("0.0100"), result.availableBalance)
        assertEquals(4, result.availableBalance?.scale())
    }

    @Test fun decodeErrorsDoNotEchoPayloadsOrAttachJsonDiagnostics() {
        val marker = "PRIVATE_SYNTHETIC_MARKER"
        val error = assertFailsWith<IllegalArgumentException> {
            EnzonaReadJson.cards("""{"$marker": [""")
        }
        assertTrue(marker !in error.toString())
        assertNull(error.cause)
        val badAmount = assertFailsWith<IllegalArgumentException> {
            EnzonaReadJson.balance("""{"balance":"$marker","operation":[]}""")
        }
        assertTrue(marker !in badAmount.toString())
        assertNull(badAmount.cause)
    }
}
