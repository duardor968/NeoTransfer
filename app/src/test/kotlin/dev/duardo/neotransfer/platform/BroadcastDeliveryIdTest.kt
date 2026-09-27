package dev.duardo.neotransfer.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BroadcastDeliveryIdTest {
    @Test fun exactRedeliveryKeepsIdentityAcrossInstances() {
        assertEquals(broadcastDeliveryId(1, listOf(byteArrayOf(1, 2, 3))),
            broadcastDeliveryId(1, listOf(byteArrayOf(1, 2, 3))))
    }

    @Test fun otherLineNewPduAndDifferentPartBoundariesAreSeparateDeliveries() {
        val original = broadcastDeliveryId(1, listOf(byteArrayOf(1, 2, 3)))
        assertNotEquals(original, broadcastDeliveryId(2, listOf(byteArrayOf(1, 2, 3))))
        assertNotEquals(original, broadcastDeliveryId(1, listOf(byteArrayOf(1, 2, 4))))
        assertNotEquals(original, broadcastDeliveryId(1, listOf(byteArrayOf(1), byteArrayOf(2, 3))))
    }
}
