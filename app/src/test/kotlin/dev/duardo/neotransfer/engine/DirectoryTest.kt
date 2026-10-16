package dev.duardo.neotransfer.engine

import dev.duardo.neotransfer.core.model.Contact
import kotlin.test.Test
import kotlin.test.assertEquals

class DirectoryTest {
    private val ana = PhoneContact("k-ana", "Ana", listOf("52222222"))
    private val luis = PhoneContact("k-luis", "Luis", listOf("53333333", "54444444"))
    private val card1 = "9204990000000015"
    private val card2 = "9205990000000023"

    @Test fun `the address book names everyone and the app adds the cards`() {
        val stored = listOf(Contact("phone:k-ana", "Ana vieja", listOf("50000000"), listOf(card1)), Contact("x", "Abuela", cards = listOf(card2)))
        assertEquals(
            listOf(Contact("x", "Abuela", cards = listOf(card2)), Contact("phone:k-ana", "Ana", listOf("52222222"), listOf(card1)),
                Contact("phone:k-luis", "Luis", listOf("53333333", "54444444"))),
            Directory.merge(stored, listOf(ana, luis)),
        )
        // Unreadable address book: the stored copies stand in.
        assertEquals(listOf("Abuela", "Ana vieja"), Directory.merge(stored, null).map { it.name })
    }

    @Test fun `a contact kept in the app joins the one person it shares a phone with`() {
        val legacy = Contact("uuid-1", "Luisito", listOf("54444444"), listOf(card1), mapOf(card1 to "Salario"))
        val (save, delete) = Directory.link(listOf(legacy), listOf(ana, luis))
        assertEquals(listOf(Contact("phone:k-luis", "Luis", luis.phones, listOf(card1), mapOf(card1 to "Salario"))), save)
        assertEquals(listOf("uuid-1"), delete)
    }

    @Test fun `joining keeps the cards already saved for that person`() {
        val saved = Contact("phone:k-luis", "Luis", luis.phones, listOf(card2))
        val stale = Contact("phone:old-key", "Luis", listOf("53333333"), listOf(card1))
        val (save, delete) = Directory.link(listOf(saved, stale), listOf(luis))
        assertEquals(listOf(saved.copy(cards = listOf(card2, card1))), save)
        assertEquals(listOf("phone:old-key"), delete)
    }

    @Test fun `ambiguous or phoneless contacts stay in the app`() {
        val shared = PhoneContact("k-casa", "Casa", listOf("52222222"))
        val both = Contact("a", "Ana o Casa", listOf("52222222"), listOf(card1))
        val cardOnly = Contact("b", "Abuela", cards = listOf(card2))
        assertEquals(emptyList<Contact>() to emptyList(), Directory.link(listOf(both, cardOnly), listOf(ana, shared)))
    }
}
