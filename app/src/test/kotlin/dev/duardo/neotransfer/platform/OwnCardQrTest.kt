package dev.duardo.neotransfer.platform

import kotlin.test.*

class OwnCardQrTest {
    @Test fun transferQrRoundTripsFullCardAndOptionalPhone() {
        assertEquals(QrInput.Card("1234567890123456", "50001234"), QrInput.parse(OwnCardQr.encode("1234567890123456", "50001234")))
        assertEquals(QrInput.Card("1234567890123456", null), QrInput.parse(OwnCardQr.encode("1234567890123456")))
    }
    @Test fun partialCardAndInjectedDelimitersCannotBecomePaymentCodes() {
        assertFailsWith<IllegalArgumentException> { OwnCardQr.encode("1234XXXXXXXX3456") }
        assertFailsWith<IllegalArgumentException> { OwnCardQr.encode("1234567890123456", "5000,1234") }
    }
}
