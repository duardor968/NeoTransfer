package dev.duardo.neotransfer.core.ledger

import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Money
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The two apps' shared activity: rows as they travel, and which of NeoTransfer+'s operations are a receipt here. */
class ActivitySyncTest {
    private val at = Instant.parse("2026-10-15T14:00:00Z")
    private val card = "9204069900001234"

    private fun receipt(
        id: String = "ref:R1", amount: String = "250.00", direction: Direction = Direction.OUT, time: Instant = at,
        debit: String? = card, seen: String? = null, operation: String? = null, dateOnly: Boolean = false,
    ) = Movement(id, MovementKind.TRANSFER, null, direction, Money.cup(amount), time, Bank.BANDEC, debit.takeIf { direction != Direction.IN },
        debit.takeIf { direction == Direction.IN }, "9204990000000015", id.removePrefix("ref:"), operation, null, dateOnly = dateOnly, cardSeen = seen)

    private fun row(id: String = "e1", amount: String = "250.00", direction: String = ActivitySync.OUT, time: Instant = at, card: String = this.card) =
        ActivityRow(id, SharedKind.CARD_TRANSFER.name, SharedStatus.CONFIRMED.name, "Ana", time.toEpochMilli(), amount, "CUP", direction, card,
            "9204990000000015", "")

    @Test fun `a row travels as cursor values and comes back the same`() {
        val original = ActivityRow("op-1", "QR_PAYMENT", "PENDING", "Cafetería «La Esquina»", 1_792_000_000_123, "10.00", "CUP", "OUT",
            card, "", "phone:0r1-2A")
        assertEquals(original, ActivitySync.row(original.values().toList()))
        assertEquals(ActivitySync.COLUMNS.size, original.values().size)
        // A cursor may hand the time back as text.
        assertEquals(original, ActivitySync.row(original.values().map { it.toString() }))
        // Words a newer app may add are read as nothing claimed.
        assertEquals(SharedKind.OTHER, SharedKind.of("CRYPTO"))
        assertEquals(SharedStatus.UNCONFIRMED, SharedStatus.of("REFUNDED"))
    }

    @Test fun `exported rows are the newest 500 of the last 120 days`() {
        val now = at.toEpochMilli()
        val old = row("old", time = at - ActivitySync.KEPT - Duration.ofMillis(1))
        val edge = row("edge", time = at - ActivitySync.KEPT)
        val many = (1..600).map { row("r$it", time = at - Duration.ofMinutes(it.toLong())) }
        val exported = ActivitySync.exported(many + old + edge, now)
        assertEquals(ActivitySync.LIMIT, exported.size)
        assertEquals((1..500).map { "r$it" }, exported.map { it.id })
        assertEquals(listOf("edge"), ActivitySync.exported(listOf(old, edge), now).map { it.id })
    }

    @Test fun `the same movement is one, within ten minutes either way and not a millisecond more`() {
        val ten = ActivitySync.WINDOW
        assertEquals(mapOf("ref:R1" to row()), ActivitySync.match(listOf(receipt()), listOf(row())))
        assertEquals(1, ActivitySync.match(listOf(receipt()), listOf(row(time = at + ten))).size)
        assertEquals(1, ActivitySync.match(listOf(receipt()), listOf(row(time = at - ten))).size)
        assertTrue(ActivitySync.match(listOf(receipt()), listOf(row(time = at + ten + Duration.ofMillis(1)))).isEmpty())
        assertTrue(ActivitySync.match(listOf(receipt()), listOf(row(time = at - ten - Duration.ofMillis(1)))).isEmpty())
        // The same amount written otherwise is the same amount.
        assertEquals(1, ActivitySync.match(listOf(receipt()), listOf(row(amount = "250"))).size)
    }

    @Test fun `amount, currency, direction and card must all agree`() {
        val receipts = listOf(receipt())
        assertTrue(ActivitySync.match(receipts, listOf(row(amount = "250.01"))).isEmpty())
        assertTrue(ActivitySync.match(receipts, listOf(row(amount = ""))).isEmpty())
        assertTrue(ActivitySync.match(receipts, listOf(row().copy(currency = "USD"))).isEmpty())
        assertTrue(ActivitySync.match(receipts, listOf(row(direction = ActivitySync.IN))).isEmpty())
        assertTrue(ActivitySync.match(receipts, listOf(row(card = "9204069900005678"))).isEmpty())
        // A credit is matched by the card it went to.
        assertEquals(1, ActivitySync.match(listOf(receipt(direction = Direction.IN)), listOf(row(direction = ActivitySync.IN))).size)
    }

    @Test fun `only one candidate on each side merges`() {
        // Two receipts could be the row: neither is.
        assertTrue(ActivitySync.match(listOf(receipt("ref:R1"), receipt("ref:R2", time = at + Duration.ofMinutes(3))), listOf(row())).isEmpty())
        // Two rows could be the receipt: neither is.
        assertTrue(ActivitySync.match(listOf(receipt()), listOf(row("e1"), row("e2", time = at + Duration.ofMinutes(2)))).isEmpty())
        // Two pairs far enough apart: each its own.
        val later = at + Duration.ofMinutes(30)
        val matched = ActivitySync.match(listOf(receipt("ref:R1"), receipt("ref:R2", time = later)), listOf(row("e1"), row("e2", time = later)))
        assertEquals(mapOf("ref:R1" to "e1", "ref:R2" to "e2"), matched.mapValues { it.value.id })
    }

    @Test fun `an operation of this app never merges, a day-only row only on its day`() {
        assertTrue(ActivitySync.match(listOf(receipt(operation = "op-1")), listOf(row())).isEmpty())
        // Last operations know the Cuban day only (its start): the operation must fall on that day.
        val day = Instant.parse("2026-10-15T04:00:00Z")
        assertEquals(1, ActivitySync.match(listOf(receipt(dateOnly = true, time = day)), listOf(row())).size)
        assertTrue(ActivitySync.match(listOf(receipt(dateOnly = true, time = day + Duration.ofDays(1))), listOf(row())).isEmpty())
        // The operation of this app does not count as a candidate either: the receipt of unknown origin still merges.
        val own = receipt("ref:R0", operation = "op-1", time = at + Duration.ofMinutes(1))
        assertEquals(setOf("ref:R1"), ActivitySync.match(listOf(own, receipt()), listOf(row())).keys)
    }

    @Test fun `a receipt that shows only part of the card matches by what it shows`() {
        // Not in this app's wallet: the receipt's mask is all there is.
        assertEquals(1, ActivitySync.match(listOf(receipt(debit = null, seen = "920406XXXXXX1234")), listOf(row())).size)
        assertEquals(1, ActivitySync.match(listOf(receipt(debit = null, seen = "XXXX1234")), listOf(row())).size)
        assertTrue(ActivitySync.match(listOf(receipt(debit = null, seen = "920499XXXXXX1234")), listOf(row())).isEmpty())
        assertTrue(ActivitySync.match(listOf(receipt(debit = null, seen = "XXXX9999")), listOf(row())).isEmpty())
        assertTrue(ActivitySync.match(listOf(receipt(debit = null, seen = null)), listOf(row())).isEmpty())
        assertTrue(ActivitySync.sameCard(card, "1234"))
        assertFalse(ActivitySync.sameCard(card, "234"))
        assertFalse(ActivitySync.sameCard("", "1234"))
        // ENZONA writes the currency in lower case.
        assertEquals(1, ActivitySync.match(listOf(receipt()), listOf(row().copy(currency = "cup"))).size)
        // NeoTransfer+ may know only the last four of the card.
        assertTrue(ActivitySync.sameCard("1234", card))
        assertTrue(ActivitySync.sameCard("1234", "XXXX1234"))
        assertFalse(ActivitySync.sameCard("9999", card))
    }
}
