package dev.duardo.neotransfer.enzona

import java.math.BigDecimal
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Offline decoders for the three AOT-traced read envelopes. No status implies business success. */
object EnzonaReadJson {
    /** One retained history row, with the same envelope and field validation as a history page. */
    fun transaction(body: String): EnzonaTransaction = transaction(envelope(body))

    fun cards(body: String): List<EnzonaRemoteCard> = envelope(body).rows("card").map(::card)

    fun balance(body: String): EnzonaCardBalance {
        val root = envelope(body)
        require("balance" in root || "balance_available" in root) { "Missing balance fields" }
        return EnzonaCardBalance(
            bookBalance = root.decimal("balance"),
            availableBalance = root.decimal("balance_available"),
            currency = root.currency(),
            bankAccount = root.string("bank_account"),
            statusCode = root.int("status_code"),
            statusDenomination = root.string("status_denom"),
            transactionSignature = root.string("transaction_signature"),
            operations = root.rows("operation").map(::bankOperation),
            original = root,
        )
    }

    fun bankOperations(body: String): List<EnzonaBankOperation> =
        envelope(body).rows("operation").map(::bankOperation)

    fun transactions(body: String): EnzonaTransactionPage {
        val root = envelope(body)
        val pagination = root["pagination"] as? JsonObject
            ?: throw IllegalArgumentException("Missing or invalid pagination")
        return EnzonaTransactionPage(
            transactions = root.rows("transaction").map(::transaction),
            pagination = EnzonaPagination(
                first = pagination["first"],
                previous = pagination["prev"],
                next = pagination["next"],
                last = pagination["last"],
                total = pagination.long("total", allowString = false),
                original = pagination,
            ),
        )
    }

    // CardModel.fromJson 0x7c2618: only the fields with a traced toString are normalized.
    private fun card(row: JsonObject) = EnzonaRemoteCard(
        fundingSourceUuid = row.requiredId("funding_source_uuid"),
        fundingSourceId = row.string("funding_source_id"),
        last4 = row.text("last4"),
        cardholder = row.string("cardholder"),
        expiryDate = row.text("expdate"),
        createdAt = row.date("created_at"),
        updatedAt = row.date("updated_at"),
        status = row.text("status"),
        currency = row.currency(),
        primarySource = row.text("primary_source"),
        bankName = row.string("bank_name"),
        bankCode = row.text("bank_code"),
        verified = row.text("verified"),
        deleted = row["deleted"],
        bankCertificate = row.string("bank_certificate"),
        bin = row.int("bin"),
    )

    // OperationModel.fromJson 0x82a034: amount is a string in AOT, parsed exactly here.
    private fun bankOperation(row: JsonObject) = EnzonaBankOperation(
        operationDate = row.date("operationDate"),
        action = row.string("action"),
        channel = row.string("channel"),
        description = row.string("description"),
        amount = row.decimal("amount"),
        code = row.string("codigo"),
        avatar = row.string("avatar"),
        alias = row.string("alias"),
        original = row,
    )

    // TransactionModel.fromJson 0x6cb7dc. Repeated UUIDs remain repeated rows.
    private fun transaction(row: JsonObject) = EnzonaTransaction(
        transactionUuid = row.requiredId("transaction_uuid"),
        owner = row.string("owner"),
        clientId = row.text("client_id"),
        invoice = row.text("invoice"),
        invoiceNumber = row.text("invoice_number"),
        merchantAlias = row.text("merchant_alias"),
        merchantAvatar = row.text("merchant_avatar"),
        merchantName = row.text("merchant_name"),
        statusCode = row.int("status_code"),
        transactionStatusCode = row.int("transaction_status_code"),
        transactionStatus = row.text("transaction_status"),
        createdAt = row.date("transaction_created_at"),
        signature = row.text("transaction_signature"),
        period = row.text("period"),
        amount = row.decimal("amount"),
        totalAmount = row.decimal("total_amount"),
        currency = row.currency(),
        transactionDenomination = row.text("transaction_denom"),
        statusDenomination = row.text("status_denom"),
        transactionCode = row.int("transaction_code"),
        barcode = row.boolean("barcode"),
        donationTitle = row.text("donation_title"),
        description = row.text("transaction_description"),
        source = row.optionalObject("source")?.let(::source),
        recipient = row.optionalObject("recipient")?.let(::recipient),
        metadata = row["metadata"],
        bankDebitDetail = row["bank_debit_detail"],
        original = row,
    )

    private fun source(row: JsonObject) = EnzonaTransactionSource(
        last4 = row.text("funding_source_last4"),
        bank = row.int("funding_source_bank"),
        avatar = row.text("funding_source_avatar"),
        name = row.text("funding_source_name"),
        lastName = row.text("funding_source_lastname"),
        username = row.text("funding_source_username"),
        amount = row.decimal("funding_source_amount"),
        verified = row.int("funding_source_verified"),
        merchantAlias = row.text("merchant_alias"),
        merchantAvatar = row.text("merchant_avatar"),
        original = row,
    )

    private fun recipient(row: JsonObject) = EnzonaTransactionRecipient(
        last4 = row.text("funding_recipient_last4"),
        bank = row.text("funding_recipient_bank"),
        avatar = row.text("funding_recipient_avatar"),
        name = row.text("funding_recipient_name"),
        lastName = row.text("funding_recipient_lastname"),
        username = row.text("funding_recipient_username"),
        verified = row.text("funding_recipient_verified"),
        donationTitle = row.text("donation_title"),
        merchantAlias = row.text("merchant_alias"),
        merchantAvatar = row.text("merchant_avatar"),
        original = row,
    )

    private fun envelope(body: String): JsonObject {
        val root = try {
            Json.parseToJsonElement(body)
        } catch (_: SerializationException) {
            // Serialization diagnostics can contain body text: do not retain them as a cause.
            throw IllegalArgumentException("Invalid ENZONA JSON")
        } as? JsonObject ?: throw IllegalArgumentException("Expected ENZONA object")
        require(listOf("fault", "error", "errors").none { it in root }) { "ENZONA error envelope" }
        return root
    }

    private fun JsonObject.rows(key: String): List<JsonObject> {
        val array = this[key] as? JsonArray
            ?: throw IllegalArgumentException("Missing or invalid $key list")
        return array.map { it as? JsonObject ?: throw IllegalArgumentException("Invalid $key row") }
    }

    private fun JsonObject.requiredId(key: String): String {
        val value = string(key)
        require(!value.isNullOrBlank()) { "Missing or invalid $key" }
        return value
    }

    private fun JsonObject.primitive(key: String): JsonPrimitive? {
        val value = this[key]
        if (value == null || value == JsonNull) return null
        return value as? JsonPrimitive ?: throw IllegalArgumentException("Invalid $key type")
    }

    private fun JsonObject.string(key: String): String? {
        val value = primitive(key) ?: return null
        require(value.isString) { "Invalid $key type" }
        return value.content
    }

    private fun JsonObject.text(key: String): String? = primitive(key)?.content

    private fun JsonObject.date(key: String): EnzonaRemoteDate? = string(key)?.let(::EnzonaRemoteDate)

    private fun JsonObject.currency(): String? = string("currency")?.also {
        require(it.isNotBlank() && it.none(Char::isISOControl)) { "Invalid currency value" }
    }

    private fun JsonObject.decimal(key: String): BigDecimal? {
        val value = primitive(key) ?: return null
        if (!value.isString && value.content == "false") return null
        // JsonTreeReader 1.9.0 retains numeric lexemes; no floating-point intermediate.
        return try {
            BigDecimal(value.content)
        } catch (_: NumberFormatException) {
            throw IllegalArgumentException("Invalid $key decimal")
        }
    }

    private fun JsonObject.long(key: String, allowString: Boolean = true): Long? {
        val value = primitive(key) ?: return null
        require(allowString || !value.isString) { "Invalid $key type" }
        return value.content.toLongOrNull() ?: throw IllegalArgumentException("Invalid $key integer")
    }

    private fun JsonObject.int(key: String): Int? = long(key)?.let {
        require(it in Int.MIN_VALUE..Int.MAX_VALUE) { "Invalid $key integer" }
        it.toInt()
    }

    private fun JsonObject.boolean(key: String): Boolean? = primitive(key)?.let {
        it.content.toBooleanStrictOrNull() ?: throw IllegalArgumentException("Invalid $key boolean")
    }

    private fun JsonObject.optionalObject(key: String): JsonObject? {
        val value = this[key]
        if (value == null || value == JsonNull || value == JsonPrimitive(false)) return null
        return value as? JsonObject ?: throw IllegalArgumentException("Invalid $key object")
    }
}
