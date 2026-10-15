package dev.duardo.neotransfer.backup

import dev.duardo.neotransfer.data.WalletSnapshot
import dev.duardo.neotransfer.enzona.data.EnzonaLocalExport
import dev.duardo.neotransfer.enzona.data.EnzonaStoredTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class EnzonaHistoryBackupTest {
    private val preferences = BackupPreferences(BackupTheme.SYSTEM, true)

    @Test fun `typed history preserves exact financial lexemes and same UUID across owners`() {
        val owners = listOf(export("owner-a"), export("owner-b"))
        val bytes = BackupPayload.encode(WalletSnapshot(), byteArrayOf(), preferences, enzona = owners)
        try {
            BackupPayload.decode(bytes).use { decoded ->
                assertEquals(owners, decoded.enzona)
                val row = Json.parseToJsonElement(decoded.enzona.first().transactions.single().json) as JsonObject
                assertEquals("9007199254740993.000100", (row["amount"] as JsonPrimitive).content)
                assertEquals("1.2300e+10", (row["total_amount"] as JsonPrimitive).content)
                assertEquals("false", row["source"].toString())
            }
        } finally { bytes.fill(0) }
    }

    @Test fun `v3 local exports without optional history remain readable`() {
        val oldOwner = EnzonaLocalExport("owner-a", emptyList(), emptyList())
        val section = EnzonaBackupCodec.encode(listOf(oldOwner))
        val root = Json.parseToJsonElement(section.decodeToString()) as JsonObject
        val owner = (root.getValue("owners") as JsonArray).single() as JsonObject
        assertEquals(false, "transactions" in owner)
        assertEquals(listOf(oldOwner), EnzonaBackupCodec.decode(section))
    }

    @Test fun `history rejects credentials arbitrary payloads ID mismatches duplicates and wrong owner`() {
        val owner = export("owner-a")
        val row = owner.transactions.single()
        val invalid = listOf(
            owner.copy(transactions = listOf(row, row)),
            owner.copy(transactions = listOf(row.copy(ownerSubject = "owner-b"))),
            owner.copy(transactions = listOf(row.copy(transactionUuid = "different"))),
            owner.copy(transactions = listOf(row.copy(observedAt = -1))),
            owner.copy(transactions = listOf(row.copy(json = "{}"))),
            owner.copy(transactions = listOf(row.copy(json = """{"access_token":"synthetic-secret"}"""))),
            owner.copy(transactions = listOf(row.copy(json = """{"transaction_uuid":"shared-uuid","metadata":{"payment_password":"synthetic-secret"}}"""))),
        )
        invalid.forEach { data ->
            assertFailsWith<BackupPayloadFailure.InvalidPayload> {
                BackupPayload.encode(WalletSnapshot(), byteArrayOf(), preferences, enzona = listOf(data))
            }
        }
    }

    @Test fun `history decoder rejects unvalidated object and binding instead of discarding it`() {
        val safe = EnzonaBackupCodec.encode(listOf(export("owner-a"))).decodeToString()
        for (invalid in listOf(
            safe.replaceFirst("\"transactionUuid\":\"shared-uuid\"", "\"transactionUuid\":\"wrong-uuid\""),
            safe.replaceFirst("\"amount\":", "\"authorization\":\"synthetic-secret\",\"amount\":"),
            safe.replaceFirst("\"observedAt\":100", "\"observedAt\":\"100\""),
            safe.replaceFirst("\"transactions\":[", "\"unknown_section\":["),
        )) assertFailsWith<BackupPayloadFailure.InvalidPayload> { EnzonaBackupCodec.decode(invalid.toByteArray()) }
    }

    private fun export(owner: String) = EnzonaLocalExport(owner, emptyList(), emptyList(), transactions = listOf(
        EnzonaStoredTransaction(owner, "shared-uuid", 100,
            """{"transaction_uuid":"shared-uuid","amount":9007199254740993.000100,"total_amount":1.2300e+10,"currency":"1","source":false,"recipient":false,"codigo":"REF-KEEP"}"""),
    ))
}
