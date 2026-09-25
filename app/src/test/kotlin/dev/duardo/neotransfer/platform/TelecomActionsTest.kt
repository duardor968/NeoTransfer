package dev.duardo.neotransfer.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TelecomActionsTest {
    @Test fun `information choices preserve exact destination and SMS bodies`() {
        val info = TelecomActions.all.filter { it.kind == TelecomActionKind.SMS_INFO }
        assertEquals(listOf("cubacel.info.offers", "cubacel.info.tariffs", "cubacel.info.plans", "cubacel.info.friends"),
            info.map { it.id })
        assertEquals(listOf("OFERTA", "TARIFA", "PLANES", "AMIGOS"), info.map { it.body })
        assertTrue(info.all { it.destination == "2266" })
    }

    @Test fun `emergency choices preserve original numbers without an SMS body`() {
        val calls = TelecomActions.all.filter { it.kind == TelecomActionKind.DIALER }
        assertEquals(listOf("104", "105", "106", "107", "103"), calls.map { it.destination })
        assertEquals(listOf("cubacel.emergency.ambulance", "cubacel.emergency.fire", "cubacel.emergency.police",
            "cubacel.emergency.maritime", "cubacel.emergency.drugs"), calls.map { it.id })
        assertTrue(calls.all { it.body == null })
        assertNull(TelecomActions.find("cubacel.emergency.unknown"))
        assertEquals(9, TelecomActions.all.map { it.id }.toSet().size)
    }
}
