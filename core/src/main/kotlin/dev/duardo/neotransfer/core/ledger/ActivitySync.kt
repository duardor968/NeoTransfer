package dev.duardo.neotransfer.core.ledger

import dev.duardo.neotransfer.core.model.Cards
import java.time.Duration
import java.time.Instant

/**
 * One row of `content://<applicationId>.sync/activity`, the operations an app originated, as NeoTransfer and NeoTransfer+
 * exchange them. Text as it travels: [kind] a [SharedKind], [status] a [SharedStatus], [direction] `OUT` or `IN`; [at] epoch
 * millis; [amount] a plain absolute decimal (`10.00`), empty when there is none; [card] the own card's 16 digits, [counterpart]
 * a card, an 8-digit phone or a service reference, [contactId] `phone:<lookup key>` or the app's contact id, each empty when
 * unknown.
 */
data class ActivityRow(
    val id: String,
    val kind: String,
    val status: String,
    val title: String,
    val at: Long,
    val amount: String,
    val currency: String,
    val direction: String,
    val card: String,
    val counterpart: String,
    val contactId: String,
) {
    /** The values in [ActivitySync.COLUMNS] order. */
    fun values(): Array<Any> = arrayOf(id, kind, status, title, at, amount, currency, direction, card, counterpart, contactId)
}

enum class SharedKind {
    CARD_TRANSFER, ACCOUNT_TRANSFER, QR_PAYMENT, ELECTRICITY, LANDLINE, WATER, NAUTA, NAUTA_HOME, MOBILE_TOPUP, CUBACEL_TRANSFER,
    SERVICE, INCOMING, OTHER;

    companion object {
        /** Unknown words (a newer peer) read as [OTHER]. */
        fun of(value: String): SharedKind = entries.firstOrNull { it.name == value } ?: OTHER
    }
}

enum class SharedStatus {
    CONFIRMED, PENDING, UNCONFIRMED, REJECTED, NOT_DONE, NOT_SENT;

    companion object {
        /** Unknown words (a newer peer) read as [UNCONFIRMED]: nothing is claimed about them. */
        fun of(value: String): SharedStatus = entries.firstOrNull { it.name == value } ?: UNCONFIRMED
    }
}

/** The shared activity of the two apps: what each one exports, and how the other's rows meet the bank receipts. */
object ActivitySync {
    val COLUMNS = arrayOf("id", "kind", "status", "title", "at", "amount", "currency", "direction", "card", "counterpart", "contactId")

    const val OUT = "OUT"
    const val IN = "IN"

    /** What an app exports: its newest [LIMIT] rows of the last [KEPT]. */
    val KEPT: Duration = Duration.ofDays(120)
    const val LIMIT = 500

    /** A receipt and the other app's operation are the same movement only this close in time. */
    val WINDOW: Duration = Duration.ofMinutes(10)

    /** [values] in [COLUMNS] order, as a cursor gives them back. */
    fun row(values: List<Any?>): ActivityRow {
        fun text(i: Int) = values[i]?.toString().orEmpty()
        return ActivityRow(text(0), text(1), text(2), text(3), (values[4] as? Number)?.toLong() ?: text(4).toLongOrNull() ?: 0L,
            text(5), text(6), text(7), text(8), text(9), text(10))
    }

    /** [rows] as exported: newer than [now] minus [KEPT], newest first, at most [LIMIT]. */
    fun exported(rows: List<ActivityRow>, now: Long): List<ActivityRow> =
        rows.filter { it.at >= now - KEPT.toMillis() }.sortedByDescending { it.at }.take(LIMIT)

    /**
     * The other app's [peer] rows that are the same movement as a receipt of unknown origin, by [Movement.id]: same own card
     * (whole, or by what the receipt shows of it), amount, currency and direction, at most [WINDOW] apart (on the same Cuban
     * day for a row known only by its day), and exactly one candidate on each side. A receipt of an operation of this app
     * ([Movement.operationId]) never takes part: two operations are never one.
     */
    fun match(movements: List<Movement>, peer: List<ActivityRow>): Map<String, ActivityRow> {
        val receipts = movements.filter { it.operationId == null }
        val candidates = peer.associateWith { row -> receipts.filter { same(it, row) } }
        val claimed = receipts.associateWith { receipt -> peer.filter { same(receipt, it) } }
        return candidates.mapNotNull { (row, found) ->
            found.singleOrNull()?.takeIf { claimed.getValue(it).size == 1 }?.let { it.id to row }
        }.toMap()
    }

    private fun same(movement: Movement, row: ActivityRow): Boolean {
        val amount = row.amount.toBigDecimalOrNull() ?: return false
        val incoming = movement.direction == Direction.IN
        if (row.direction != (if (incoming) IN else OUT)) return false
        if (!row.currency.equals(movement.amount.currency.name, ignoreCase = true) || amount.abs().compareTo(movement.amount.amount.abs()) != 0) return false
        // A last-operations row knows only its Cuban day: the operation must fall on it.
        val near = if (movement.dateOnly) movement.at.atZone(Activity.CUBA).toLocalDate() == Instant.ofEpochMilli(row.at).atZone(Activity.CUBA).toLocalDate()
            else Duration.ofMillis(Math.abs(movement.at.toEpochMilli() - row.at)) <= WINDOW
        if (!near) return false
        val seen = (if (incoming) movement.creditCard else movement.debitCard) ?: movement.cardSeen ?: return false
        return sameCard(row.card, seen)
    }

    /**
     * [card] (16 digits, or the last four when that is all ENZONA gives) is the one [seen] names: the same number, or a
     * mask whose shown digits agree with it, its last four included; a receipt that shows only the last four is enough.
     */
    fun sameCard(card: String, seen: String): Boolean {
        if (card.length == 4 && card.all(Char::isDigit)) return seen.takeLastWhile(Char::isDigit).takeLast(4) == card
        if (!Cards.isNumber(card)) return false
        if (Cards.isNumber(seen)) return seen == card
        val last = seen.takeLastWhile(Char::isDigit)
        if (last.length < 4 || !card.endsWith(last.takeLast(4))) return false
        return seen.length != 16 || seen.indices.all { !seen[it].isDigit() || seen[it] == card[it] }
    }
}
