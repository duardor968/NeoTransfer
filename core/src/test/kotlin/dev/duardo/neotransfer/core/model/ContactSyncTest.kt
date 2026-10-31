package dev.duardo.neotransfer.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContactSyncTest {
    private val juan = Contact("c1", "Juan", listOf("50000009", "52222222"), listOf("9204990000000007", "9205990000000001"),
        mapOf("9204990000000007" to "Salario BANDEC"))

    @Test fun `a provider row reads back as the record it was written from`() {
        val record = ContactRecord(juan, 1_792_000_000_123)
        val row = ContactSync.row(record)
        assertEquals("50000009\n52222222", row.phones)
        assertEquals("9204990000000007\tSalario BANDEC\n9205990000000001\t", row.cards)
        assertEquals(record, ContactSync.record(row))
        val tombstone = ContactSync.tombstone(juan, 5)
        assertEquals(ContactRecord(Contact("c1", "Juan"), 5, deleted = true), tombstone)
        assertEquals(ContactRow("c1", "Juan", "", "", 5, true), ContactSync.row(tombstone))
        assertEquals(tombstone, ContactSync.record(ContactSync.row(tombstone)))
    }

    @Test fun `an unknown contact is taken unless it is a tombstone`() {
        val ana = ContactRecord(Contact("c2", "Ana", listOf("51111111")), 0)
        val gone = ContactRecord(Contact("c3", "Luis"), 7, deleted = true)
        assertEquals(listOf(ana), ContactSync.merge(emptyList(), listOf(ana, gone)))
    }

    @Test fun `the newer side wins and keeps its time`() {
        val mine = ContactRecord(juan, 100)
        val theirs = ContactRecord(juan.copy(name = "Juan P.", cards = emptyList(), labels = emptyMap()), 200)
        assertEquals(listOf(theirs), ContactSync.merge(listOf(mine), listOf(theirs)))
        assertTrue(ContactSync.merge(listOf(theirs.copy(updated = 300)), listOf(mine)).isEmpty())
        // A newer deletion deletes; an older one does not.
        val deleted = ContactSync.tombstone(juan, 200)
        assertEquals(listOf(deleted), ContactSync.merge(listOf(mine), listOf(deleted)))
        assertTrue(ContactSync.merge(listOf(ContactRecord(juan, 300)), listOf(deleted)).isEmpty())
        // Saved again after the deletion: it comes back on the side that deleted it.
        val revived = ContactRecord(juan, 400)
        assertEquals(listOf(revived), ContactSync.merge(listOf(deleted), listOf(revived)))
    }

    @Test fun `on a tie both are joined, local first, and nothing is written when nothing changes`() {
        val mine = ContactRecord(Contact("c1", "Juan", listOf("50000009"), listOf("9204990000000007"), mapOf("9204990000000007" to "Salario")), 0)
        val theirs = ContactRecord(Contact("c1", "Juan Prueba", listOf("52222222", "50000009"), listOf("9205990000000001", "9204990000000007"),
            mapOf("9204990000000007" to "Sueldo", "9205990000000001" to "Ahorro")), 0)
        val joined = ContactRecord(Contact("c1", "Juan", listOf("50000009", "52222222"), listOf("9204990000000007", "9205990000000001"),
            mapOf("9204990000000007" to "Salario", "9205990000000001" to "Ahorro")), 0)
        assertEquals(listOf(joined), ContactSync.merge(listOf(mine), listOf(theirs)))
        assertTrue(ContactSync.merge(listOf(joined), listOf(theirs)).isEmpty())
        assertTrue(ContactSync.merge(listOf(mine), listOf(mine)).isEmpty())
        // Deleted only if both are.
        val gone = ContactSync.tombstone(juan, 9)
        assertEquals(listOf(ContactRecord(juan, 9)), ContactSync.merge(listOf(gone), listOf(ContactRecord(juan, 9))))
        assertTrue(ContactSync.merge(listOf(gone), listOf(gone)).isEmpty())
    }
}
