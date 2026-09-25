package dev.duardo.neotransfer.fuel

import dev.duardo.neotransfer.core.fuel.*
import org.json.JSONObject
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

class FuelSecretStoreTest {
    private val pair = "W3ePK80M6VKxq9C60HXAHg==|+yRrfQTQ/zLIJShacXR3ow=="
    private fun vault(keyByte: Byte) = FuelSecretStore { SecretKeySpec(ByteArray(32) { keyByte }, "AES") }
    private fun coupon(envelope: FuelProviderEnvelope): FuelCoupon = FuelCoupon("fuel:0000000000001", "0000000000001", "CUP",
        paidAmount = "10.00", bankReference = "BANK1", tmReference = "TM1", observedAt = 100, sourceEventId = "event1", subscriptionId = 3,
        secretRevision = envelope.revision("0000000000001", "BANK1", "TM1"), evidenceEligible = true)

    @Test fun `GCM protects provider envelope and refuses mixed references or another coupon`() {
        val store = vault(1)
        FuelProviderEnvelope(pair.toCharArray()).use { envelope ->
            val coupon = coupon(envelope)
            val chars = envelope.copyForProtection()
            val stored = store.protect(coupon, chars)
            assertTrue(chars.all { it == '\u0000' })
            assertFalse(stored.blob.contains(pair))
            assertFalse(stored.toString().contains(pair))
            store.open(coupon, stored).use { assertEquals(coupon.secretRevision, it.revision(coupon.serial, coupon.bankReference, coupon.tmReference)) }
            assertFails { store.open(coupon.copy(bankReference = "MIXED"), stored) }
            assertFails { store.open(coupon.copy(id = "fuel:0000000000002", serial = "0000000000002"), stored) }
            assertFails { vault(2).open(coupon, stored) }
            assertFails { store.open(coupon, ProtectedFuelEnvelope(coupon.id, stored.revision, stored.blob.dropLast(4))) }
        }
    }

    @Test fun `portable backup retains a usable QR after re-encryption with another device key`() {
        val oldDevice = vault(1)
        val newDevice = vault(2)
        FuelProviderEnvelope(pair.toCharArray()).use { envelope ->
            val coupon = coupon(envelope)
            val original = oldDevice.protect(coupon, envelope.copyForProtection())
            val capsules = oldDevice.exportForBackup(listOf(coupon), listOf(original))
            try {
                val payload = capsules.single().copyForEncryptedBackup()
                try { assertEquals(pair, String(payload)) } finally { payload.fill('\u0000') }
                val restored = newDevice.protectAfterRestore(listOf(coupon), capsules).single()
                assertNotEquals(original.blob, restored.blob)
                newDevice.open(coupon, restored).use { raw -> FuelCredential.fromProviderEnvelope(coupon, raw).use { credential ->
                    val json = JSONObject(credential.qrPayload())
                    assertEquals(setOf("Pin", "Token"), json.keys().asSequence().toSet())
                    assertEquals("1234", json.getString("Pin"))
                    assertEquals("TOKEN987", json.getString("Token"))
                } }
                assertFails { newDevice.protectAfterRestore(listOf(coupon.copy(tmReference = "OTHER")), capsules) }
                assertFails { oldDevice.exportForBackup(listOf(coupon), emptyList()) }
            } finally { capsules.forEach(FuelBackupEnvelope::close) }
            assertFails { capsules.single().copyForEncryptedBackup() }
        }
    }
}
