package dev.duardo.neotransfer.enzona.data

import dev.duardo.neotransfer.enzona.EnzonaReadJson
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal object EnzonaCacheSanitizer {
    private val authorizationKeys = setOf(
        "access_token", "refresh_token", "id_token", "payment_password", "fingerprint",
        "fingerpring", "client_secret", "authorization", "code_verifier", "password", "pin",
    )
    private val errorKeys = listOf("fault", "error", "errors", "error_description")

    fun sanitize(kind: EnzonaReadKind, json: String): String {
        val safe = sanitizedObject(json)
        val safeJson = safe.toString()
        when (kind) {
            EnzonaReadKind.ACCOUNT -> {
                val account = safe["account"] as? JsonObject
                require(!account.isNullOrEmpty()) { "Missing or invalid account object" }
                require(errorKeys.none { it in account }) { "ENZONA account error" }
            }
            EnzonaReadKind.CARDS -> EnzonaReadJson.cards(safeJson)
            EnzonaReadKind.BALANCE -> EnzonaReadJson.balance(safeJson)
            EnzonaReadKind.HISTORY -> EnzonaReadJson.transactions(safeJson)
        }
        return safeJson
    }

    fun sanitizeTransaction(json: String): String = sanitizedObject(json).toString().also {
        EnzonaReadJson.transaction(it)
    }

    private fun sanitizedObject(json: String): JsonObject {
        val parsed = try {
            Json.parseToJsonElement(json)
        } catch (_: IllegalArgumentException) {
            // Parser diagnostics may contain raw input. Do not propagate them or their cause.
            throw IllegalArgumentException("Invalid ENZONA read JSON")
        }
        require(parsed is JsonObject) { "Expected an ENZONA read object" }
        val safe = redact(parsed) as JsonObject
        require(errorKeys.none { it in safe }) { "ENZONA error envelope" }
        return safe
    }

    private fun redact(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.filterKeys {
            it.lowercase(Locale.ROOT) !in authorizationKeys
        }.mapValues { redact(it.value) })
        is JsonArray -> JsonArray(value.map(::redact))
        else -> value // Keep numeric lexemes, strings, booleans and null unchanged.
    }
}
