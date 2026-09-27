package dev.duardo.neotransfer.fuel

import dev.duardo.neotransfer.core.fuel.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import javax.crypto.spec.SecretKeySpec
import kotlin.test.*

class FuelControllerTest {
    private val pair = "W3ePK80M6VKxq9C60HXAHg==|+yRrfQTQ/zLIJShacXR3ow=="
    private val store = FuelSecretStore { SecretKeySpec(ByteArray(32) { 9 }, "AES") }
    private fun coupon(): FuelCoupon = FuelProviderEnvelope(pair.toCharArray()).use { envelope ->
        FuelCoupon(FuelCoupon.idFor("0000000000001"), "0000000000001", "CUP", paidAmount = "10.00",
            bankReference = "BANK1", tmReference = "TM1", observedAt = 100, sourceEventId = "sms1",
            subscriptionId = 3, secretRevision = envelope.revision("0000000000001", "BANK1", "TM1"), evidenceEligible = true)
    }

    @Test fun `reveal requires an affirmative gate and closed presentations cannot expose secrets`() {
        val coupon = coupon()
        val stored = store.protect(coupon, pair.toCharArray())
        var reads = 0
        val controller = FuelController(store, { _, _ -> reads++; stored }, { coupon }, Dispatchers.Unconfined, Dispatchers.Unconfined)
        try {
            var answer: ((Boolean) -> Unit)? = null
            controller.showCoupon(coupon) { _, _, callback -> answer = callback }
            assertTrue(controller.state.value.busy)
            assertEquals(0, reads)
            requireNotNull(answer)(false)
            assertFalse(controller.state.value.busy)
            assertNull(controller.state.value.presentation)
            assertEquals(0, reads)
            controller.showCoupon(coupon) { _, _, callback -> answer = callback }
            requireNotNull(answer)(true)
            requireNotNull(answer)(true)
            assertEquals(1, reads)
            val presentation = assertNotNull(controller.state.value.presentation)
            assertEquals("1234", presentation.pinText())
            assertEquals("TOKEN987", presentation.tokenText())
            assertFalse(controller.state.value.toString().contains("TOKEN987"))
            controller.clearSensitive()
            assertNull(controller.state.value.presentation)
            assertFails { presentation.qrPayload() }
        } finally { controller.close() }
    }

    @Test fun `lock or a changed credential invalidates outstanding biometric authorization`() {
        val original = coupon()
        var current = original
        var reads = 0
        val controller = FuelController(store, { _, _ -> reads++; null }, { current }, Dispatchers.Unconfined, Dispatchers.Unconfined)
        try {
            var answer: ((Boolean) -> Unit)? = null
            controller.showCoupon(original) { _, _, callback -> answer = callback }
            controller.clearSensitive()
            requireNotNull(answer)(true)
            assertEquals(0, reads)
            controller.showCoupon(original) { _, _, callback -> answer = callback }
            current = original.copy(tmReference = "CHANGED")
            requireNotNull(answer)(true)
            assertEquals(0, reads)
            assertNull(controller.state.value.presentation)
            assertNotNull(controller.state.value.error)
        } finally { controller.close() }
    }

    @Test fun `a delayed storage read cannot publish secrets after lock or a newer key`(): Unit = runBlocking {
        val original = coupon()
        var current = original
        val stored = store.protect(original, pair.toCharArray())
        var deferred = CompletableDeferred<ProtectedFuelEnvelope?>()
        val controller = FuelController(store, { _, _ -> deferred.await() }, { current }, Dispatchers.Unconfined, Dispatchers.Unconfined)
        try {
            controller.showCoupon(original) { _, _, callback -> callback(true) }
            controller.clearSensitive()
            deferred.complete(stored)
            assertNull(controller.state.value.presentation)
            deferred = CompletableDeferred()
            controller.showCoupon(original) { _, _, callback -> callback(true) }
            current = original.copy(secretRevision = "new-key")
            deferred.complete(stored)
            assertNull(controller.state.value.presentation)
            assertNotNull(controller.state.value.error)
        } finally { controller.close() }
    }

    @Test fun `restored evidence can reveal its protected coupon but ambiguous and corrupt data cannot`() {
        val original = coupon().copy(evidenceEligible = false)
        var current = original
        var stored = store.protect(original, pair.toCharArray())
        val controller = FuelController(store, { _, _ -> stored }, { current }, Dispatchers.Unconfined, Dispatchers.Unconfined)
        try {
            controller.showCoupon(original) { _, _, callback -> callback(true) }
            assertNotNull(controller.state.value.presentation)
            current = original.copy(ambiguous = true)
            controller.syncCoupons(listOf(current))
            assertNull(controller.state.value.presentation)
            var prompted = false
            controller.showCoupon(current) { _, _, _ -> prompted = true }
            assertFalse(prompted)
            current = original
            stored = ProtectedFuelEnvelope(original.id, requireNotNull(original.secretRevision), stored.blob.dropLast(5))
            controller.showCoupon(original) { _, _, callback -> callback(true) }
            assertNull(controller.state.value.presentation)
            assertNotNull(controller.state.value.error)
            assertFalse(controller.state.value.error.orEmpty().contains(pair))
        } finally { controller.close() }
    }
}
