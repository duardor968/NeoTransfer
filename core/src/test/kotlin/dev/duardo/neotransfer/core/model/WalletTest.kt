package dev.duardo.neotransfer.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WalletTest {
    @Test fun `contacts are identified only by an unambiguous phone or card`() {
        val ana = Contact("1", "Ana", phones = listOf("+53 5000 0001"), cards = listOf("9204-0000-0000-0007"))
        val luis = Contact("2", "Luis", phones = listOf("50000002"))
        val shared = Contact("3", "Casa", phones = listOf("50000002"))
        val contacts = listOf(ana, luis, shared)
        assertEquals(ana, contacts.identify(phone = "5350000001"))
        assertEquals(ana, contacts.identify(card = "9204000000000007"))
        assertNull(contacts.identify(phone = "50000002")) // two people share it
        assertNull(contacts.identify(phone = "50000009"))
    }

    @Test fun `masks and money compare by meaning`() {
        assertTrue(Cards.matches("920400XXXXXX0001", "9204000000000001"))
        assertTrue(Cards.matches("9204XXXXXXXX0001", "9204000000000001"))
        assertFalse(Cards.matches("9204XXXXXXXX0001", "9204000000000002"))
        assertFalse(Cards.matches("XXXXXXXXXXXX0001", "9204000000000001")) // too little to identify a card
        assertEquals(Money.cup("10.5"), Money.cup("10.50"))
        assertEquals(Money.cup("10.5").hashCode(), Money.cup("10.50").hashCode())
        assertEquals("10.50", Money.cup("10.50").plain())
    }
}
