package dev.duardo.neotransfer.core.ledger

import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Cards
import dev.duardo.neotransfer.core.model.Currency
import dev.duardo.neotransfer.core.model.Money
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.core.protocol.BalanceQuery
import dev.duardo.neotransfer.core.protocol.BankRequest
import dev.duardo.neotransfer.core.protocol.ElectricityBill
import dev.duardo.neotransfer.core.protocol.HistoryQuery
import dev.duardo.neotransfer.core.protocol.MobileRecharge
import dev.duardo.neotransfer.core.protocol.NautaPayment
import dev.duardo.neotransfer.core.protocol.PhoneBill
import dev.duardo.neotransfer.core.protocol.QrPay
import dev.duardo.neotransfer.core.protocol.Source
import dev.duardo.neotransfer.core.protocol.Transfer
import dev.duardo.neotransfer.core.protocol.WaterBill
import dev.duardo.neotransfer.core.sms.PaidService
import dev.duardo.neotransfer.core.sms.Receipt
import dev.duardo.neotransfer.core.sms.SmsMessage
import dev.duardo.neotransfer.core.sms.SmsRecord
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** [OTHER]: a bank movement known only from last operations (card payment, ATM, interest…). */
enum class MovementKind { TRANSFER, QR_PAYMENT, SERVICE, OTHER }
enum class Direction { IN, OUT, BETWEEN_OWN }

/**
 * One money movement for Activity, built from a bank receipt (its copies and duplicates merged by
 * reference) or, when no receipt reported it, from a last-operations row (`48`).
 * Card attribution is computed from the current cards, never stored.
 */
data class Movement(
    /** Stable: `ref:<bank reference>`; `day:…` for a last-operations row without reference. */
    val id: String,
    val kind: MovementKind,
    val service: PaidService?,
    val direction: Direction,
    val amount: Money,
    /** SMSC time of the receipt; start of the day in Cuba when [dateOnly]. */
    val at: Instant,
    val bank: Bank?,
    /** Own card debited, when it can be told without ambiguity. */
    val debitCard: String?,
    /** Own card credited (exact 16-digit match only). */
    val creditCard: String?,
    /** Destination account or mask, sender phone, merchant or service account. */
    val counterparty: String?,
    /** Null only for a last-operations row without reference. */
    val reference: String?,
    /** The app operation that produced it; null when made elsewhere (Transfermóvil, another phone…). */
    val operationId: String?,
    /** Balance stated by the receipt, as written by the bank. */
    val balanceAfter: Money?,
    /** The bank's wording of a last-operations row ("POS Extraccion"); null for receipts. */
    val label: String? = null,
    /** Known only by its date (last operations): there is no time of day. */
    val dateOnly: Boolean = false,
) {
    val external: Boolean get() = operationId == null
}

/**
 * Balance of one own card: last observed balance plus later movements attributed to it.
 * [value] is null until a balance has been observed. [observedAt] is that observation;
 * [updatedAt] the last thing applied (a later credit, for instance).
 */
data class CardBalance(val card: String, val value: Money?, val observedAt: Instant?, val updatedAt: Instant?)

internal class Activity(
    private val sms: List<SmsRecord>,
    private val operationOf: Map<Long, String>,
    private val operations: Map<String, Operation>,
    private val cards: List<OwnCard>,
) {
    private class Entry(val movement: Movement, val order: Long)

    private val receipts: List<Entry> = sms.filter { it.message is Receipt }
        .groupBy { (it.message as Receipt).reference }.values.mapNotNull(::movement)

    private val entries: List<Entry> = receipts + history()

    val movements: List<Movement> = entries.map { it.movement }.sortedByDescending { it.at }

    private fun own(number: String?): String? = number?.let { n -> cards.firstOrNull { it.number == n }?.number }

    private fun movement(records: List<SmsRecord>): Entry? {
        fun <T : SmsMessage> first(type: Class<T>) = records.firstOrNull { type.isInstance(it.message) }
        val primary = first(SmsMessage.TransferSent::class.java) ?: first(SmsMessage.PaymentCompleted::class.java)
            ?: first(SmsMessage.ServicePaid::class.java) ?: first(SmsMessage.TransferReceived::class.java)
            ?: return null // a copy alone is ambiguous: never attributed
        val messages = records.map { it.message }
        val operationId = operationOf[primary.id]
        val source = (operationId?.let(operations::get)?.request as? BankRequest)?.let(::sourceOf)
        val at = primary.sentAt
        val movement = when (val m = primary.message) {
            is SmsMessage.TransferSent -> {
                val destination = messages.filterIsInstance<SmsMessage.TransferNotice>().firstOrNull()?.account
                    ?: messages.filterIsInstance<SmsMessage.TransferReceived>().firstOrNull()?.account
                val credit = own(destination)
                Movement("ref:${m.reference}", MovementKind.TRANSFER, null, if (credit != null) Direction.BETWEEN_OWN else Direction.OUT,
                    m.amount, at, m.bank, debitCard(m.bank, source, m.sourceMask, m.amount.currency, at), credit,
                    destination ?: m.beneficiaryMask, m.reference, operationId, m.balance)
            }
            is SmsMessage.PaymentCompleted -> Movement("ref:${m.reference}", MovementKind.QR_PAYMENT, null, Direction.OUT, m.amount, at, m.bank,
                debitCard(m.bank, source, null, m.amount.currency, at), null, m.merchant ?: m.purchaseId, m.reference, operationId, m.balance)
            is SmsMessage.ServicePaid -> Movement("ref:${m.reference}", MovementKind.SERVICE, m.service, Direction.OUT, m.amount, at, m.bank,
                debitCard(m.bank, source, null, m.amount.currency, at), null,
                m.account ?: messages.filterIsInstance<SmsMessage.ServiceNotice>().firstOrNull()?.account, m.reference, operationId, m.balance)
            is SmsMessage.TransferReceived -> {
                val credit = own(m.account)
                Movement("ref:${m.reference}", MovementKind.TRANSFER, null, Direction.IN, m.amount, at,
                    cards.firstOrNull { it.number == credit }?.bank, null, credit, m.senderPhone, m.reference, null, null)
            }
            else -> return null
        }
        return Entry(movement, primary.id)
    }

    /**
     * Last operations (`48`) fill in what no receipt reported: a lost SMS, card payments, ATM. A row
     * repeated in later answers counts once (same reference; without one, same day, direction and
     * amount, as many times as one answer lists it), and a row a receipt already reported is skipped
     * (same reference; without one, same bank, day, direction and amount).
     */
    private fun history(): List<Entry> {
        val referenced = receipts.mapNotNullTo(mutableSetOf()) { it.movement.reference }
        val reported = receipts.groupingBy { dayKey(it.movement.bank, it.movement.at.atZone(CUBA).toLocalDate(),
            it.movement.direction == Direction.IN, it.movement.amount) }.eachCount()
        val found = linkedMapOf<String, Entry>()
        for (record in sms) {
            val message = record.message as? SmsMessage.History ?: continue
            val source = (operationOf[record.id]?.let(operations::get)?.request as? HistoryQuery)?.source
            val listed = mutableMapOf<String, Int>()
            for (row in message.entries) {
                val id = row.reference?.let { "ref:$it" } ?: run {
                    val key = dayKey(message.bank, row.date, row.credit, row.amount)
                    val occurrence = listed.merge(key, 1, Int::plus)!!
                    if (occurrence <= (reported[key] ?: 0)) return@run null
                    "day:$key:$occurrence"
                } ?: continue
                if (id in found || row.reference?.let(referenced::contains) == true) continue
                val card = if (source is Source.Card) own(source.number) else defaultCard(message.bank, row.amount.currency, record.sentAt)
                found[id] = Entry(Movement(id, if (row.description.contains("transferencia", true)) MovementKind.TRANSFER else MovementKind.OTHER,
                    null, if (row.credit) Direction.IN else Direction.OUT, row.amount, row.date.atStartOfDay(CUBA).toInstant(), message.bank,
                    card.takeUnless { row.credit }, card.takeIf { row.credit }, null, row.reference, null, null,
                    label = row.description, dateOnly = true), record.id)
            }
        }
        return found.values.toList()
    }

    private fun dayKey(bank: Bank?, day: LocalDate, credit: Boolean, amount: Money) =
        "$bank:$day:${if (credit) "cr" else "db"}:${amount.amount.stripTrailingZeros().toPlainString()}:${amount.currency}"

    private fun sourceOf(request: BankRequest): Source? = when (request) {
        is Transfer -> request.source
        is QrPay -> request.source
        is ElectricityBill -> request.source
        is PhoneBill -> request.source
        is WaterBill -> request.source
        is NautaPayment -> request.source
        is MobileRecharge -> request.source
        is BalanceQuery -> request.source
        else -> null
    }

    /** Explicit card, then the receipt's mask, then the bank's default account when only one card can be it. */
    private fun debitCard(bank: Bank, source: Source?, mask: String?, currency: Currency, at: Instant): String? = when {
        source is Source.Card -> own(source.number)
        mask != null -> cards.filter { it.bank == bank && Cards.matches(mask, it.number) }.singleOrNull()?.number
        else -> defaultCard(bank, currency, at)
    }

    /** BANDEC names the session card in its authentication SMS; with that, several cards stay unambiguous. */
    private fun defaultCard(bank: Bank, currency: Currency, at: Instant): String? {
        val candidates = cards.filter { it.bank == bank && it.currency == currency }
        if (bank != Bank.BANDEC) return candidates.singleOrNull()?.number
        val active = sms.lastOrNull { it.sentAt <= at && (it.message as? SmsMessage.Authenticated)?.let { a -> a.bank == bank && a.cardMask != null } == true }
            ?.let { (it.message as SmsMessage.Authenticated).cardMask }
        return (if (active == null) candidates else candidates.filter { Cards.matches(active, it.number) }).singleOrNull()?.number
    }

    private class Observation(val card: String, val value: Money, val at: Instant, val order: Long)

    private companion object {
        /** Last-operations dates are Cuban calendar days. */
        val CUBA: ZoneId = ZoneId.of("America/Havana")
    }

    /** Balance answers: BANDEC by card mask (available balance); BPA's single figure by the query's origin. */
    private fun observations(): List<Observation> = sms.flatMap { record ->
        val message = record.message as? SmsMessage.Balance ?: return@flatMap emptyList()
        message.rows.mapNotNull { row ->
            val card = when {
                row.account != null -> cards.filter { it.bank == message.bank && Cards.matches(row.account, it.number) }.singleOrNull()?.number
                row.label == null && message.rows.size == 1 -> {
                    val source = (operationOf[record.id]?.let(operations::get)?.request as? BalanceQuery)?.source
                    if (source is Source.Card) own(source.number) else defaultCard(message.bank, row.available.currency, record.sentAt)
                }
                else -> null // BPA account tables name accounts, not cards
            }
            card?.let { Observation(it, row.available, record.sentAt, record.id) }
        }
    }

    fun balances(): Map<String, CardBalance> {
        val observed = observations().groupBy { it.card }
        return cards.associate { card -> card.number to project(card, observed[card.number].orEmpty()) }
    }

    private fun project(card: OwnCard, observations: List<Observation>): CardBalance {
        val events = observations.map { Triple(it.at, it.order, it as Any) } +
            entries.filter { it.movement.debitCard == card.number || it.movement.creditCard == card.number }
                .map { Triple(it.movement.at, it.order, it.movement as Any) }
        var value: BigDecimal? = null
        var observedAt: Instant? = null
        var updatedAt: Instant? = null
        for ((at, _, event) in events.sortedWith(compareBy({ it.first }, { it.second }))) {
            when (event) {
                is Observation -> if (event.value.currency == card.currency) {
                    value = event.value.amount; observedAt = at; updatedAt = at
                }
                is Movement -> if (event.amount.currency == card.currency) {
                    val debit = event.debitCard == card.number
                    val stated = event.balanceAfter?.takeIf { debit && it.currency == card.currency }?.amount
                    value = when {
                        stated == null -> value?.let { if (debit) it - event.amount.amount else it + event.amount.amount }
                        // Some BANDEC receipts state the balance before the debit: it equals the previous one.
                        event.bank == Bank.BANDEC && value != null && stated.compareTo(value) == 0 -> stated - event.amount.amount
                        else -> stated
                    }
                    if (stated != null) observedAt = at
                    updatedAt = at
                }
            }
        }
        return CardBalance(card.number, value?.takeIf { it.signum() >= 0 }?.let { Money(it, card.currency) }, observedAt, updatedAt)
    }
}
