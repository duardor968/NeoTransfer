package dev.duardo.neotransfer.backup

import dev.duardo.neotransfer.enzona.data.EnzonaCardLink
import dev.duardo.neotransfer.enzona.data.EnzonaDestination
import dev.duardo.neotransfer.enzona.data.EnzonaIntent
import dev.duardo.neotransfer.enzona.data.EnzonaIntentState
import dev.duardo.neotransfer.enzona.data.EnzonaIntentType
import dev.duardo.neotransfer.enzona.data.EnzonaJournalEntry
import dev.duardo.neotransfer.enzona.data.EnzonaLocalExport
import dev.duardo.neotransfer.enzona.data.EnzonaStoredTransaction
import dev.duardo.neotransfer.enzona.data.validateEnzonaExports
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Explicit V1 allowlist inside the encrypted payload: links, journal and validated retained history. */
internal object EnzonaBackupCodec {
    private val invoiceFields = setOf("invoiceService", "invoiceOwner", "invoiceFolio", "invoiceRoute", "invoicePeriod")

    fun encode(owners: List<EnzonaLocalExport>): ByteArray {
        validateEnzonaExports(owners)
        return buildJsonObject {
            put("format", "neotransfer-enzona-local")
            put("version", 1)
            put("owners", JsonArray(owners.map { owner -> buildJsonObject {
                put("ownerSubject", owner.ownerSubject)
                put("version", owner.version)
                put("cardLinks", JsonArray(owner.cardLinks.map { link -> buildJsonObject {
                    put("ownerSubject", link.ownerSubject); put("cardUuid", link.cardUuid)
                    put("pan", nullable(link.pan)); put("alias", nullable(link.alias))
                } }))
                put("journal", JsonArray(owner.journal.map { entry -> buildJsonObject {
                    put("intent", buildJsonObject {
                        val intent = entry.intent
                        put("id", intent.id); put("ownerSubject", intent.ownerSubject)
                        put("cardUuid", intent.cardUuid); put("type", intent.type.name)
                        put("destination", buildJsonObject {
                            put("value", intent.destination.value); put("username", nullable(intent.destination.username))
                        })
                        put("amount", intent.amount); put("currency", intent.currency)
                        put("description", nullable(intent.description)); put("phone", nullable(intent.phone))
                        put("preparedAt", intent.preparedAt)
                        put("invoiceService", nullable(intent.invoiceService)); put("invoiceOwner", nullable(intent.invoiceOwner))
                        put("invoiceFolio", nullable(intent.invoiceFolio)); put("invoiceRoute", nullable(intent.invoiceRoute))
                        put("invoicePeriod", nullable(intent.invoicePeriod))
                    })
                    put("state", entry.state.name); put("remoteId", nullable(entry.remoteId)); put("restored", entry.restored)
                } }))
                if (owner.transactions.isNotEmpty()) put("transactions", JsonArray(owner.transactions.map { row -> buildJsonObject {
                    put("ownerSubject", row.ownerSubject); put("transactionUuid", row.transactionUuid)
                    put("observedAt", row.observedAt); put("transaction", Json.parseToJsonElement(row.json))
                } }))
            } }))
        }.toString().toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray): List<EnzonaLocalExport> = try {
        val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true))
            .objectWith("format", "version", "owners")
        require(root.string("format") == "neotransfer-enzona-local" && root.number("version") == 1L)
        root.array("owners").map { element ->
            val owner = element.objectWith("ownerSubject", "version", "cardLinks", "journal", optionalFields = setOf("transactions"))
            require(owner.number("version") == 1L)
            EnzonaLocalExport(owner.string("ownerSubject"), owner.array("cardLinks").map { linkElement ->
                val link = linkElement.objectWith("ownerSubject", "cardUuid", "pan", "alias")
                EnzonaCardLink(link.string("ownerSubject"), link.string("cardUuid"), link.optionalString("pan"), link.optionalString("alias"))
            }, owner.array("journal").map { entryElement ->
                val entry = entryElement.objectWith("intent", "state", "remoteId", "restored")
                val intent = entry.getValue("intent").objectWith("id", "ownerSubject", "cardUuid", "type", "destination",
                    "amount", "currency", "description", "phone", "preparedAt", optionalFields = invoiceFields)
                val destination = intent.getValue("destination").objectWith("value", "username")
                EnzonaJournalEntry(EnzonaIntent(
                    id = intent.string("id"), ownerSubject = intent.string("ownerSubject"), cardUuid = intent.string("cardUuid"),
                    type = EnzonaIntentType.valueOf(intent.string("type")),
                    destination = EnzonaDestination(destination.string("value"), destination.optionalString("username")),
                    amount = intent.string("amount"), currency = intent.string("currency"),
                    description = intent.optionalString("description"), phone = intent.optionalString("phone"), preparedAt = intent.number("preparedAt"),
                    invoiceService = intent.invoiceString("invoiceService"), invoiceOwner = intent.invoiceString("invoiceOwner"),
                    invoiceFolio = intent.invoiceString("invoiceFolio"), invoiceRoute = intent.invoiceString("invoiceRoute"),
                    invoicePeriod = intent.invoiceString("invoicePeriod"),
                ), EnzonaIntentState.valueOf(entry.string("state")), entry.optionalString("remoteId"), entry.boolean("restored"))
            }, transactions = if ("transactions" !in owner) emptyList() else owner.array("transactions").map { rowElement ->
                val row = rowElement.objectWith("ownerSubject", "transactionUuid", "observedAt", "transaction")
                val transaction = row.getValue("transaction") as? JsonObject ?: throw IllegalArgumentException()
                EnzonaStoredTransaction(row.string("ownerSubject"), row.string("transactionUuid"), row.number("observedAt"), transaction.toString())
            })
        }.also(::validateEnzonaExports)
    } catch (_: Exception) {
        // Decoder and enum diagnostics must not expose input, PANs or descriptions.
        throw BackupPayloadFailure.InvalidPayload()
    }

    private fun nullable(value: String?): JsonElement = value?.let(::JsonPrimitive) ?: JsonNull
    private fun JsonElement.objectWith(vararg fields: String, optionalFields: Set<String> = emptySet()): JsonObject {
        val value = this as? JsonObject ?: throw IllegalArgumentException()
        require(value.keys.containsAll(fields.toSet()) && value.keys.all { it in fields || it in optionalFields })
        return value
    }
    private fun JsonObject.array(key: String): JsonArray = getValue(key) as? JsonArray ?: throw IllegalArgumentException()
    private fun JsonObject.string(key: String): String {
        val value = getValue(key) as? JsonPrimitive ?: throw IllegalArgumentException()
        require(value.isString)
        return value.content
    }
    private fun JsonObject.optionalString(key: String): String? = if (getValue(key) == JsonNull) null else string(key)
    // Existing V3 local exports predate these nullable intent fields.
    private fun JsonObject.invoiceString(key: String): String? = if (key !in this) null else optionalString(key)
    private fun JsonObject.number(key: String): Long {
        val value = getValue(key) as? JsonPrimitive ?: throw IllegalArgumentException()
        require(!value.isString)
        return requireNotNull(value.longOrNull)
    }
    private fun JsonObject.boolean(key: String): Boolean {
        val value = getValue(key) as? JsonPrimitive ?: throw IllegalArgumentException()
        require(!value.isString)
        return requireNotNull(value.booleanOrNull)
    }
}
