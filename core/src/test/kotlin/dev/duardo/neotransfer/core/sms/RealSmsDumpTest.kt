package dev.duardo.neotransfer.core.sms

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

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
