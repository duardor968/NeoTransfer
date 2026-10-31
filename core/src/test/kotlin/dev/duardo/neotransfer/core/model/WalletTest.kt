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

    @Test fun `a phone field keeps eight digits whether the number is pasted or typed with its prefix`() {
        assertEquals("51234567", Phones.input("+53 5 123 4567"))
        assertEquals("51234567", Phones.input("⁨+53 51234567⁩")) // as the address book copies it
        // Typed key by key: the ninth digit waits for the tenth instead of cutting the number.
        val typed = "+53 51234567".fold("") { field, key -> Phones.input(field + key) }
        assertEquals("51234567", typed)
        assertEquals("53000009", "53000009".fold("") { field, key -> Phones.input(field + key) })
        assertEquals("51234567", Phones.input("512345678")) // one key too many
        assertEquals("51234567", Phones.input("0053 5 123 4567"))
        assertEquals("51234567", "0053 51234567".fold("") { field, key -> Phones.input(field + key) })
        assertEquals("Número incompleto", Phones.mobileProblem("5123456"))
        assertEquals("No es un móvil cubano", Phones.mobileProblem("71234567"))
        assertNull(Phones.mobileProblem("51234567"))
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

    @Test fun `the number tells the bank and the currency, as Transfermóvil reads it`() {
        assertEquals(Issuer(Bank.BANDEC, Currency.CUP), Cards.issuer("9204069900000000"))
        assertEquals(Issuer(Bank.BPA, Currency.CUP), Cards.issuer("9212129900000000"))
        assertEquals(Issuer(Bank.BANMET, Currency.CUP), Cards.issuer("9205959800000000"))
        assertEquals(Issuer(Bank.BPA, Currency.USD), Cards.issuer("9225129900000000"))
        assertEquals(Issuer(Bank.BANDEC, Currency.USD), Cards.issuer("9240069900000000")) // Tropical
        assertEquals(Issuer(Bank.BANDEC, null), Cards.issuer("9250069900000000"))
        assertEquals(Issuer(null, Currency.CUP), Cards.issuer("9204779900000000")) // a bank the app does not know
        assertEquals(Issuer(null, Currency.USD), Cards.issuer("9760030000000000")) // Clásica
        assertEquals(Issuer(null, null), Cards.issuer("4111111111111111"))
        // While typed: the currency from the fourth digit, the bank from the sixth.
        assertEquals(Issuer(null, null), Cards.issuer("920"))
        assertEquals(Issuer(null, Currency.CUP), Cards.issuer("9204"))
        assertEquals(Issuer(Bank.BANDEC, Currency.CUP), Cards.issuer("920406"))
    }
}
