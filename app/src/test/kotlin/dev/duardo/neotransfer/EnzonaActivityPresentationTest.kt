package dev.duardo.neotransfer

import dev.duardo.neotransfer.enzona.EnzonaReadJson
import dev.duardo.neotransfer.enzona.data.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EnzonaActivityPresentationTest {
    @Test fun `refreshing older history does not put it before newer transactions`() {
        val entries = EnzonaReadJson.transactions("""{"transaction":[
          {"transaction_uuid":"older-refreshed","transaction_created_at":"2026-10-01T12:00:00Z"},
          {"transaction_uuid":"newer","transaction_created_at":"2026-10-08T13:00:00+01:00"},
          {"transaction_uuid":"date-only","transaction_created_at":"2026-10-07"}
        ],"pagination":{}}""").transactions
        assertEquals(listOf("newer", "date-only", "older-refreshed"), orderedEnzonaTransactions(entries).map { it.transactionUuid })
    }

    @Test fun `unfamiliar dates are neither guessed nor discarded`() {
        val entries = EnzonaReadJson.transactions("""{"transaction":[
          {"transaction_uuid":"unknown-a","transaction_created_at":"01/02/2026"},
          {"transaction_uuid":"unknown-b","transaction_created_at":"provider-date"},
          {"transaction_uuid":"known","transaction_created_at":"2026-10-07"}
        ],"pagination":{}}""").transactions
        val ordered = orderedEnzonaTransactions(entries)
        assertEquals(listOf("known", "unknown-a", "unknown-b"), ordered.map { it.transactionUuid })
        assertEquals("01/02/2026", ordered[1].createdAt?.value)
    }

    @Test fun `activity search also filters local timed out payments`() {
        val entry = EnzonaJournalEntry(EnzonaIntent("attempt", "owner", "card", EnzonaIntentType.INVOICE_PAYMENT,
            EnzonaDestination("client-reference"), "125.50", "CUP", description = "Electricidad", preparedAt = 1),
            EnzonaIntentState.UNKNOWN)
        assertTrue(entry.matchesActivityQuery("electricidad"))
        assertTrue(entry.matchesActivityQuery("125.50"))
        assertTrue(entry.matchesActivityQuery("espera agotado"))
        assertFalse(entry.matchesActivityQuery("Nauta"))
    }
}
