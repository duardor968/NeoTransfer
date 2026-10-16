package dev.duardo.neotransfer.core.sms

import dev.duardo.neotransfer.core.ledger.Ledger
import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Cards
import dev.duardo.neotransfer.core.model.OwnCard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Local-only check against the private phone dump (`NEOTRANSFER_SMS_DUMP` = sms_relevant.json).
 * Skipped when the variable is absent; prints counts by type and masked shapes only.
 */
class RealSmsDumpTest {
    @Test fun `every real PAGOxMOVIL message is recognized`() {
        val path = System.getenv("NEOTRANSFER_SMS_DUMP") ?: return
        val rows = Json.parseToJsonElement(File(path).readText()).jsonArray.map { it.jsonObject }
        val records = rows.mapNotNull { row ->
            SmsRecord.from(row["_id"]!!.jsonPrimitive.long, row["address"]!!.jsonPrimitive.content,
                row["date"]!!.jsonPrimitive.long, row["date_sent"]!!.jsonPrimitive.long, row["body"]!!.jsonPrimitive.content)
                ?.let { it to row["body"]!!.jsonPrimitive.content }
        }
        val counts = records.groupingBy { (record, _) -> label(record.message) }.eachCount().toSortedMap()
        println("PAGOxMOVIL: ${records.size}")
        counts.forEach { (type, count) -> println("  $count\t$type") }
        val unknown = records.filter { (record, _) -> record.message == SmsMessage.Unrecognized ||
            (record.message as? SmsMessage.Rejected)?.operation == RejectedOperation.UNKNOWN }
        unknown.forEach { (_, body) -> println("  ? " + body.replace(Regex("[A-Za-z0-9_.-]+@[A-Za-z0-9_.-]+"), "<correo>").replace(Regex("[0-9]"), "9").replace("\n", " ")) }
        assertEquals(0, records.count { it.first.message == SmsMessage.Unrecognized })
        replay(records.map { it.first })
    }

    /**
     * Rebuilds activity from the whole inbox as on a fresh install, then checks the balance
     * projection against every real balance answer: the projection made just before it should equal it.
     * Own BANDEC cards = credited accounts matching a BANDEC balance mask; BPA uses one synthetic card.
     */
    private fun replay(records: List<SmsRecord>) {
        val masks = records.mapNotNull { it.message as? SmsMessage.Balance }.filter { it.bank == Bank.BANDEC }.flatMap { it.rows }.mapNotNull { it.account }.toSet()
        val bandec = records.mapNotNull { (it.message as? SmsMessage.TransferReceived)?.account }.distinct()
            .filter { account -> masks.any { Cards.matches(it, account) } }.map { OwnCard(it, Bank.BANDEC) }
        val cards = bandec + OwnCard("9205000000000000", Bank.BPA)
        val sorted = records.sortedWith(compareBy({ it.sentAt }, { it.id }))
        val now = sorted.last().receivedAt
        val view = Ledger.evaluate(emptyList(), records, cards, now)
        println("Replay: ${bandec.size} BANDEC card(s); movements ${view.movements.size} " +
            view.movements.groupingBy { "${it.kind}/${it.direction}/${if (it.debitCard != null || it.creditCard != null) "attributed" else "unattributed"}" }.eachCount())
        assertEquals(view.movements.size, view.movements.map { it.id }.distinct().size)
        val outcome = mutableMapOf<String, Int>()
        sorted.forEachIndexed { index, record ->
            val balance = record.message as? SmsMessage.Balance ?: return@forEachIndexed
            val known = Ledger.evaluate(emptyList(), sorted.take(index), cards, record.sentAt)
            val before = known.balances
            for (card in cards) {
                val observed = Ledger.evaluate(emptyList(), sorted.take(index + 1), cards, record.sentAt).balances.getValue(card.number)
                if (observed.observedAt != record.sentAt) continue
                val projected = before.getValue(card.number).value
                val key = "${card.bank}/" + when {
                    projected == null -> "no previous base"
                    projected == observed.value -> "projection matched"
                    else -> {
                        val delta = observed.value!!.amount - projected.amount
                        val baseAt = before.getValue(card.number).observedAt!!
                        // Movements known at that point: last-operations rows learned later cannot be applied earlier.
                        val between = known.movements.filter { it.at > baseAt && it.at <= record.sentAt && (it.debitCard == card.number || it.creditCard == card.number) }
                        // Bank alerts are shown but never applied: a gap equal to one is the staleness chosen on purpose.
                        val (applied, alerts) = between.partition { it.movesBalance }
                        val explainsByMovement = applied.any { it.amount.amount.compareTo(delta.abs()) == 0 }
                        val explainsByAlert = alerts.any { it.amount.amount.compareTo(delta.abs()) == 0 }
                        "differed(${if (delta.signum() > 0) "+" else "-"}, movementsBetween=${between.size}, equalsOneMovement=$explainsByMovement, " +
                            "equalsOneAlert=$explainsByAlert, gapHours=${java.time.Duration.between(baseAt, record.sentAt).toHours()})"
                    }
                }
                outcome[key] = (outcome[key] ?: 0) + 1
            }
        }
        println("Balance answers vs projection: ${outcome.toSortedMap()}")
        // A gap equal to one applied movement would mean a double count or a missed one.
        assertTrue(outcome.keys.none { "equalsOneMovement=true" in it })
    }

    private fun label(message: SmsMessage): String = when (message) {
        is SmsMessage.Rejected -> "Rejected/${message.operation}"
        is SmsMessage.ServicePaid -> "ServicePaid/${message.service}"
        is SmsMessage.ServiceNotice -> "ServiceNotice/${message.service}"
        is SmsMessage.Balance -> "Balance/${message.bank}/${if (message.rows.first().account != null) "card" else if (message.rows.first().label != null) "table" else "plain"}"
        is SmsMessage.Authenticated -> "Authenticated/${message.bank}"
        is SmsMessage.AuthenticationFailed -> "AuthenticationFailed/${message.bank}/${if (message.wrongPin) "pin" else "other"}"
        else -> message::class.simpleName!!
    }
}
