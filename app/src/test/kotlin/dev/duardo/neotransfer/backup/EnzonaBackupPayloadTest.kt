package dev.duardo.neotransfer.backup

import dev.duardo.neotransfer.core.backup.BackupEnvelope
import dev.duardo.neotransfer.core.backup.BackupFailure
import dev.duardo.neotransfer.core.fuel.FuelBackupEnvelope
import dev.duardo.neotransfer.core.fuel.FuelCoupon
import dev.duardo.neotransfer.core.fuel.FuelProviderEnvelope
import dev.duardo.neotransfer.data.ContactRecord
import dev.duardo.neotransfer.data.WalletSnapshot
import dev.duardo.neotransfer.enzona.data.*
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class EnzonaBackupPayloadTest {
    private val preferences = BackupPreferences(BackupTheme.LIGHT, false)

    @Test fun `v3 retains exact owners journal links and existing wallet sections`() {
        val owners = owners()
        val wallet = WalletSnapshot(contacts = listOf(ContactRecord("local-contact", "Persona sintética")))
        val credentials = "synthetic-bank-access".toByteArray()
        val payload = BackupPayload.encode(wallet, credentials, preferences, enzona = owners)
        assertEquals(3, payload[7].toInt())
        BackupPayload.decode(payload).use {
            assertEquals(owners, it.enzona)
            assertEquals(wallet, it.snapshot)
            assertEquals(preferences, it.preferences)
            assertContentEquals(credentials, it.credentials)
            assertEquals("00010.000100", it.enzona.first().journal.single().intent.amount)
            assertEquals("literal-currency", it.enzona.first().journal.single().intent.currency)
        }
        assertContentEquals("synthetic-bank-access".toByteArray(), credentials)
    }

    @Test fun `v1 and v2 remain readable and exports without enzona stay v2`() {
        val v2 = BackupPayload.encode(WalletSnapshot(), byteArrayOf(), preferences)
        assertEquals(2, v2[7].toInt())
        val v1 = (v2.copyOfRange(0, 18) + v2.copyOfRange(22, v2.size)).also { it[7] = 1 }
        for (payload in listOf(v1, v2)) BackupPayload.decode(payload).use {
            assertEquals(emptyList(), it.enzona)
            assertEquals(WalletSnapshot(), it.snapshot)
        }
    }

    @Test fun `v3 retains portable fuel capsules and existing close behavior`() {
        val provider = "AAAAAAAAAAAAAAAAAAAAAA==|AQEBAQEBAQEBAQEBAQEBAQ==".toCharArray()
        val revision = FuelProviderEnvelope(provider).use { it.revision("1234567890123", "BANKREF", "TMREF") }
        val coupon = FuelCoupon("fuel:1234567890123", "1234567890123", "CUP", bankReference = "BANKREF",
            tmReference = "TMREF", observedAt = 123, sourceEventId = "event", subscriptionId = null,
            secretRevision = revision, evidenceEligible = true)
        FuelBackupEnvelope(coupon.id, revision, provider).use { capsule ->
            val bytes = BackupPayload.encode(WalletSnapshot(fuelCoupons = listOf(coupon)), byteArrayOf(), preferences,
                listOf(capsule), owners())
            val restored = BackupPayload.decode(bytes)
            assertEquals(owners(), restored.enzona)
            assertContentEquals(provider, restored.fuelEnvelopes.single().copyForEncryptedBackup())
            restored.close()
            assertFailsWith<IllegalArgumentException> { restored.fuelEnvelopes.single().copyForEncryptedBackup() }
            assertContentEquals(provider, capsule.copyForEncryptedBackup())
        }
        provider.fill('\u0000')
    }

    @Test fun `enzona section rejects any extra credential cache or raw payload field`() {
        val safe = EnzonaBackupCodec.encode(owners()).decodeToString()
        val forbidden = listOf("access_token", "refresh_token", "id_token", "payment_password", "fingerprint",
            "fingerpring", "client_secret", "authorization", "code_verifier", "password", "pin", "ciphertext", "cache", "rawPayload")
        forbidden.forEach { key ->
            assertFalse(safe.contains("\"$key\""))
            // Insert at root, owner, link, journal, intent and destination boundaries.
            listOf("format", "cardLinks", "pan", "state", "amount", "value").forEach { anchor ->
                val extra = safe.replaceFirst("\"$anchor\":", "\"$key\":\"synthetic-secret\",\"$anchor\":")
                val failure = assertFailsWith<BackupPayloadFailure.InvalidPayload> { EnzonaBackupCodec.decode(extra.toByteArray()) }
                assertNull(failure.cause)
                assertFalse(failure.message.orEmpty().contains("synthetic-secret"))
            }
        }
    }

    @Test fun `wrong versions scopes duplicates and scalar types reject before import`() {
        val source = owners()
        fun rejects(value: List<EnzonaLocalExport>) {
            assertFailsWith<BackupPayloadFailure.InvalidPayload> {
                BackupPayload.encode(WalletSnapshot(), byteArrayOf(), preferences, enzona = value)
            }
        }
        rejects(source + source.first())
        rejects(listOf(source.first().copy(version = 2)))
        rejects(listOf(source.first().copy(cardLinks = source.first().cardLinks + source.first().cardLinks)))
        rejects(listOf(source.first().copy(cardLinks = listOf(source.last().cardLinks.single()))))
        rejects(listOf(source.first(), source.last().copy(journal = listOf(source.last().journal.single().let {
            it.copy(intent = it.intent.copy(id = source.first().journal.single().intent.id))
        }))))
        val safe = EnzonaBackupCodec.encode(source).decodeToString()
        for (malformed in listOf(
            safe.replaceFirst("\"version\":1", "\"version\":2"),
            safe.replaceFirst("\"amount\":\"00010.000100\"", "\"amount\":10.000100"),
            safe.replaceFirst("\"preparedAt\":1000", "\"preparedAt\":\"1000\""),
            safe.replaceFirst("\"restored\":false", "\"restored\":\"false\""),
            safe.replaceFirst("\"state\":\"PREPARED\"", "\"state\":\"RETRY\""),
        )) assertFailsWith<BackupPayloadFailure.InvalidPayload> { EnzonaBackupCodec.decode(malformed.toByteArray()) }
        val root = Json.parseToJsonElement(safe) as JsonObject
        val duplicateOwners = JsonObject(root + ("owners" to JsonArray(listOf((root["owners"] as JsonArray).first(),
            (root["owners"] as JsonArray).first()))))
        assertFailsWith<BackupPayloadFailure.InvalidPayload> { EnzonaBackupCodec.decode(duplicateOwners.toString().toByteArray()) }
        assertEquals("00010.000100", source.first().journal.single().intent.amount)
    }

    @Test fun `v3 rejects damaged lengths utf8 missing section and trailing bytes`() {
        val bytes = BackupPayload.encode(WalletSnapshot(), byteArrayOf(), preferences, enzona = owners())
        val sectionOffset = 22 + ByteBuffer.wrap(bytes).getInt(8)
        for (length in listOf(-1, 0, Int.MAX_VALUE)) {
            assertFailsWith<BackupPayloadFailure.InvalidPayload> {
                BackupPayload.decode(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(sectionOffset, length) })
            }
        }
        assertFailsWith<BackupPayloadFailure.InvalidPayload> { BackupPayload.decode(bytes.copyOf(sectionOffset)) }
        assertFailsWith<BackupPayloadFailure.InvalidPayload> { BackupPayload.decode(bytes.copyOf(bytes.size - 1)) }
        assertFailsWith<BackupPayloadFailure.InvalidPayload> { BackupPayload.decode(bytes + byteArrayOf(0)) }
        assertFailsWith<BackupPayloadFailure.InvalidPayload> {
            BackupPayload.decode(bytes.copyOf().also { it[sectionOffset + 4] = 0xff.toByte() })
        }
    }

    @Test fun `existing password envelope encrypts v3 and rejects another password`() {
        val password = "synthetic-backup-password".toCharArray()
        val wrong = "different-fixture-password".toCharArray()
        val plain = BackupPayload.encode(WalletSnapshot(), byteArrayOf(), preferences, enzona = owners())
        val encrypted = BackupEnvelope.encrypt(plain, password)
        try {
            assertFailsWith<BackupFailure> { BackupEnvelope.decrypt(encrypted, wrong) }
            val decrypted = BackupEnvelope.decrypt(encrypted, password)
            try { BackupPayload.decode(decrypted).use { assertEquals(owners(), it.enzona) } }
            finally { decrypted.fill(0) }
        } finally { plain.fill(0); encrypted.fill(0); password.fill('\u0000'); wrong.fill('\u0000') }
    }

    private fun owners(): List<EnzonaLocalExport> = listOf("subject-one@exact", "subject-two@exact").mapIndexed { index, owner ->
        EnzonaLocalExport(owner, listOf(EnzonaCardLink(owner, "same-remote-card", "000000000000000${index + 1}", "Alias $index")),
            listOf(EnzonaJournalEntry(EnzonaIntent(
                id = "00000000-0000-0000-0000-00000000000${index + 1}", ownerSubject = owner, cardUuid = "same-remote-card",
                type = EnzonaIntentType.ACCOUNT_TRANSFER, destination = EnzonaDestination("recipient-fixture", "recipient-name"),
                amount = "00010.000100", currency = "literal-currency", description = null, phone = "", preparedAt = 1000,
            ), if (index == 0) EnzonaIntentState.PREPARED else EnzonaIntentState.UNKNOWN)))
    }
}
