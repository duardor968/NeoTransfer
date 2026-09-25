package dev.duardo.neotransfer.backup

import dev.duardo.neotransfer.data.ContactPhone
import dev.duardo.neotransfer.data.ContactRecord
import dev.duardo.neotransfer.data.WalletSnapshot
import dev.duardo.neotransfer.core.fuel.FuelBackupEnvelope
import dev.duardo.neotransfer.core.fuel.FuelCoupon
import dev.duardo.neotransfer.core.fuel.FuelProviderEnvelope
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackupPayloadTest {
    @Test fun `payload keeps wallet credentials and preferences separate`() {
        val snapshot = WalletSnapshot(contacts = listOf(ContactRecord("c1", "Persona de prueba", listOf(ContactPhone("50000000")))))
        val credentials = "synthetic-credential-bytes".toByteArray()
        val original = BackupPayload.encode(snapshot, credentials, BackupPreferences(BackupTheme.DARK, false))
        BackupPayload.decode(original).use { restored ->
            assertEquals(snapshot, restored.snapshot)
            assertContentEquals(credentials, restored.credentials)
            assertEquals(BackupPreferences(BackupTheme.DARK, false), restored.preferences)
        }
        assertContentEquals(ByteArray(credentials.size), BackupPayload.decode(original).use { it.credentials })
    }

    @Test fun `unknown version and malformed lengths reject before allocation`() {
        val source = BackupPayload.encode(WalletSnapshot(), "synthetic".toByteArray(), BackupPreferences(BackupTheme.SYSTEM, true))
        assertFailsWith<BackupPayloadFailure.UnsupportedVersion> {
            BackupPayload.decode(source.copyOf().also { it[7] = 3 })
        }
        assertFailsWith<BackupPayloadFailure.InvalidPayload> {
            BackupPayload.decode(source.copyOf().also { it[8] = 0x7f })
        }
        assertFailsWith<BackupPayloadFailure.InvalidPayload> { BackupPayload.decode(source.copyOf(source.size - 1)) }
    }
    @Test fun `data without bank credentials round trips in version one`() {
        val snapshot = WalletSnapshot(contacts = listOf(ContactRecord("c1", "Persona de prueba", listOf(ContactPhone("50000000")))))
        val preferences = BackupPreferences(BackupTheme.LIGHT, true)
        val v2 = BackupPayload.encode(snapshot, byteArrayOf(), preferences)
        val encoded = (v2.copyOfRange(0, 18) + v2.copyOfRange(22, v2.size)).also { it[7] = 1 }
        assertEquals(1, encoded[7].toInt())
        BackupPayload.decode(encoded).use {
            assertEquals(snapshot, it.snapshot)
            assertContentEquals(byteArrayOf(), it.credentials)
            assertEquals(preferences, it.preferences)
        }
    }

    @Test fun `oversized credentials malformed UTF8 and trailing bytes reject`() {
        assertFailsWith<BackupPayloadFailure.InvalidPayload> {
            BackupPayload.encode(WalletSnapshot(), ByteArray(65_537), BackupPreferences(BackupTheme.SYSTEM, true))
        }
        val encoded = BackupPayload.encode(WalletSnapshot(), byteArrayOf(), BackupPreferences(BackupTheme.SYSTEM, true))
        assertFailsWith<BackupPayloadFailure.InvalidPayload> {
            BackupPayload.decode(encoded.copyOf().also { it[22] = 0xff.toByte() })
        }
        assertFailsWith<BackupPayloadFailure.InvalidPayload> {
            BackupPayload.decode(encoded + byteArrayOf(1))
        }
    }

    private val provider = "AAAAAAAAAAAAAAAAAAAAAA==|AQEBAQEBAQEBAQEBAQEBAQ==".toCharArray()
    @Test fun `legacy v1 bank credential bytes still decode`() {
        val bank = "synthetic-bank-access".toByteArray()
        val v2 = BackupPayload.encode(WalletSnapshot(), bank, BackupPreferences(BackupTheme.LIGHT, false))
        val v1 = (v2.copyOfRange(0, 18) + v2.copyOfRange(22, v2.size)).also { it[7] = 1 }
        BackupPayload.decode(v1).use {
            assertContentEquals(bank, it.credentials)
            assertEquals(emptyList(), it.fuelEnvelopes)
        }
    }
    private fun coupon(): FuelCoupon {
        val revision = FuelProviderEnvelope(provider).use { it.revision("1234567890123", "BANKREF", "TMREF") }
        return FuelCoupon("fuel:1234567890123", "1234567890123", "CUP", bankReference = "BANKREF",
            tmReference = "TMREF", observedAt = 123, sourceEventId = "event", subscriptionId = null,
            secretRevision = revision, evidenceEligible = true)
    }

    @Test fun `v2 portable fuel round trips without bank credentials and closes its buffers`() {
        val coupon = coupon()
        val snapshot = WalletSnapshot(fuelCoupons = listOf(coupon))
        FuelBackupEnvelope(coupon.id, requireNotNull(coupon.secretRevision), provider).use { entry ->
            val bytes = BackupPayload.encode(snapshot, byteArrayOf(), BackupPreferences(BackupTheme.SYSTEM, true), listOf(entry))
            assertEquals(2, bytes[7].toInt())
            val restored = BackupPayload.decode(bytes)
            assertEquals(snapshot, restored.snapshot)
            assertEquals(0, restored.credentials.size)
            assertContentEquals(provider, restored.fuelEnvelopes.single().copyForEncryptedBackup())
            restored.close()
            assertFailsWith<IllegalArgumentException> { restored.fuelEnvelopes.single().copyForEncryptedBackup() }
            // Caller-owned source capsule remains available after encoding.
            assertContentEquals(provider, entry.copyForEncryptedBackup())
        }
    }

    @Test fun `fuel requires exact unique capsule binding and matching revision refs`() {
        val coupon = coupon()
        val snapshot = WalletSnapshot(fuelCoupons = listOf(coupon))
        val preferences = BackupPreferences(BackupTheme.SYSTEM, true)
        assertFailsWith<BackupPayloadFailure.InvalidPayload> { BackupPayload.encode(snapshot, byteArrayOf(), preferences) }
        FuelBackupEnvelope(coupon.id, requireNotNull(coupon.secretRevision), provider).use { entry ->
            assertFailsWith<BackupPayloadFailure.InvalidPayload> {
                BackupPayload.encode(snapshot, byteArrayOf(), preferences, listOf(entry, entry))
            }
            assertFailsWith<BackupPayloadFailure.InvalidPayload> {
                BackupPayload.encode(snapshot.copy(fuelCoupons = listOf(coupon, coupon)), byteArrayOf(), preferences, listOf(entry))
            }
            assertFailsWith<BackupPayloadFailure.InvalidPayload> {
                BackupPayload.encode(snapshot.copy(fuelCoupons = listOf(coupon.copy(bankReference = "CHANGED"))), byteArrayOf(), preferences, listOf(entry))
            }
            assertFailsWith<BackupPayloadFailure.InvalidPayload> {
                BackupPayload.encode(WalletSnapshot(), byteArrayOf(), preferences, listOf(entry))
            }
        }
    }

    @Test fun `fuel section rejects corrupt lengths utf8 missing capsules and trailing data`() {
        val coupon = coupon()
        FuelBackupEnvelope(coupon.id, requireNotNull(coupon.secretRevision), provider).use { entry ->
            val bytes = BackupPayload.encode(WalletSnapshot(fuelCoupons = listOf(coupon)), byteArrayOf(),
                BackupPreferences(BackupTheme.SYSTEM, false), listOf(entry))
            val fuelStart = 22 + ByteBuffer.wrap(bytes).getInt(8)
            assertFailsWith<BackupPayloadFailure.InvalidPayload> {
                BackupPayload.decode(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(fuelStart, Int.MAX_VALUE) })
            }
            assertFailsWith<BackupPayloadFailure.InvalidPayload> {
                BackupPayload.decode(bytes.copyOf().also { it[fuelStart + 4] = 0xff.toByte() })
            }
            assertFailsWith<BackupPayloadFailure.InvalidPayload> {
                BackupPayload.decode(bytes.copyOf(fuelStart).also { ByteBuffer.wrap(it).putInt(18, 0) })
            }
            assertFailsWith<BackupPayloadFailure.InvalidPayload> { BackupPayload.decode(bytes + byteArrayOf(0)) }
            assertFailsWith<BackupPayloadFailure.InvalidPayload> {
                BackupPayload.decode(bytes.copyOf().also { ByteBuffer.wrap(it).putInt(18, 10_001) })
            }
            assertFailsWith<BackupPayloadFailure.InvalidPayload> {
                BackupPayload.decode(bytes.copyOf().also { it[it.lastIndex - 2] = 'A'.code.toByte() })
            }
        }
    }
}
