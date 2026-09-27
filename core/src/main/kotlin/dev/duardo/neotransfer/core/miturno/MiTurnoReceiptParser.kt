package dev.duardo.neotransfer.core.miturno

import dev.duardo.neotransfer.core.Currency
import dev.duardo.neotransfer.core.Money

/** Evidence of a recorded request and its fee; never evidence of an assigned appointment. */
class MiTurnoRequestReceipt internal constructor(
    val beneficiaryIdentity: String,
    val commissionPaid: Money,
    val bankTransactionReference: String,
) {
    override fun toString(): String = "MiTurnoRequestReceipt(commissionPaid=$commissionPaid)"
}

object MiTurnoReceiptParser {
    private val completion = Regex("\\bLa solicitud de Turno\\b[^.\\r\\n]*\\bfue completada\\.", RegexOption.IGNORE_CASE)

    /**
     * g0xM9kQCsqu:727-741 demonstrates these labels only. No service, branch, ticket,
     * date or time is inferred. Provider/SIM and journal correlation remain separate.
     */
    fun parse(sender: String, body: String): MiTurnoRequestReceipt? {
        if (!sender.equals("PAGOxMOVIL", ignoreCase = true)) return null
        val statement = completion.findAll(body).singleOrNull() ?: return null
        if (Regex("\\b(no|error)\\b", RegexOption.IGNORE_CASE).containsMatchIn(statement.value)) return null

        fun field(label: String, pattern: String): MatchResult? {
            val marker = Regex("\\b$label\\s*:", RegexOption.IGNORE_CASE).findAll(body).singleOrNull() ?: return null
            return Regex("^\\s*$pattern(?=[.\\s]|$)", RegexOption.IGNORE_CASE).find(body.substring(marker.range.last + 1))
        }
        val identity = field("Carnet de Identidad", "([A-Za-z0-9-]+)")?.groupValues?.get(1) ?: return null
        val reference = field("Nro\\. Transacci[oó]n de Banco", "([A-Za-z0-9_-]+)")?.groupValues?.get(1) ?: return null
        val fee = field("Comisi[oó]n Pagada", "([0-9]+(?:\\.[0-9]{1,2})?)\\s+(CUP|USD|CUC)") ?: return null
        return MiTurnoRequestReceipt(identity, Money(fee.groupValues[1].toBigDecimal(), Currency.valueOf(fee.groupValues[2].uppercase())), reference)
    }
}
