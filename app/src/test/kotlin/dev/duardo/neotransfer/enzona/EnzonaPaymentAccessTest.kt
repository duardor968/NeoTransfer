package dev.duardo.neotransfer.enzona

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnzonaPaymentAccessTest {
    @Test fun `returning from background never restores an old approval`() {
        val access = EnzonaPaymentAccess()
        val old = access.capture("A")
        assertTrue(access.isCurrent(old, "A", unlocked = true, resumed = true))
        access.invalidate()
        assertFalse(access.isCurrent(old, "A", unlocked = true, resumed = false))
        assertFalse(access.isCurrent(old, "A", unlocked = true, resumed = true))
        assertTrue(access.isCurrent(access.capture("A"), "A", unlocked = true, resumed = true))
    }

    @Test fun `old results cannot update another account or a locked screen`() {
        val access = EnzonaPaymentAccess()
        val ticket = access.capture("A")
        assertFalse(access.isCurrent(ticket, "B", unlocked = true, resumed = true))
        assertFalse(access.isCurrent(ticket, "A", unlocked = false, resumed = true))
        access.invalidate()
        assertFalse(access.isCurrent(ticket, "A", unlocked = true, resumed = true))
    }
}
