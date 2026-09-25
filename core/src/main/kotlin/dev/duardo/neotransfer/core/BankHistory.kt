package dev.duardo.neotransfer.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

enum class HistoryDirection { CREDIT, DEBIT }

/** A statement row has a calendar date, not the SMS delivery time or a payment confirmation. */
data class BankHistoryEntry(
    val date: LocalDate, val service: String, val direction: HistoryDirection, val amount: Money,
    val reference: String?, val transactionNumber: String?,
)

data class BankHistory(val bank: Bank, val entries: List<BankHistoryEntry>) : BankMessage

/** BANDEC's observed statement contract; fixture values are synthetic. */
internal fun parseBankHistory(text: String): BankHistory? {
    val header = Regex("^Banco Bandec Ultimas operaciones\\.\\s*", RegexOption.IGNORE_CASE).find(text) ?: return null
    val table = text.substring(header.range.last + 1)
    val columns = Regex("^Fecha\\s*;\\s*Servicio\\s*;\\s*Operacion\\s*;\\s*Monto\\s*;\\s*Moneda\\s*;\\s*NoTransaccion\\s*", RegexOption.IGNORE_CASE)
        .find(table) ?: return null
    val payload = table.substring(columns.range.last + 1)
    val rows = payload.split('|').map(String::trim).filter(String::isNotEmpty)
    if (rows.isEmpty()) return null // No demonstrated empty-result success marker.
    val entries = rows.map { row ->
        val cells = row.split(';').map(String::trim)
        if (cells.size != 6 || cells[1].isEmpty()) return null
        val date = try { LocalDate.parse(cells[0], DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT)) }
        catch (_: java.time.DateTimeException) { return null }
        val direction = when (cells[2].lowercase()) { "cr" -> HistoryDirection.CREDIT; "db" -> HistoryDirection.DEBIT; else -> return null }
        if (!cells[3].matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?"))) return null
        val currency = when (cells[4].uppercase()) { "CUP" -> Currency.CUP; "USD" -> Currency.USD; "CUC" -> Currency.CUC; else -> return null }
        // Observed rows use Ref: in Servicio and an empty final column. Do not assign new meaning to other values.
        if (cells[5].isNotEmpty()) return null
        val references = Regex("(?:^|\\s)Ref:\\s*([A-Za-z0-9]+)(?=\\s|$)", RegexOption.IGNORE_CASE).findAll(cells[1]).toList()
        if (references.size > 1 || (cells[1].contains("Ref:", true) && references.isEmpty())) return null
        BankHistoryEntry(date, cells[1], direction, Money(cells[3].toBigDecimal(), currency),
            references.singleOrNull()?.groupValues?.get(1), null)
    }
    return BankHistory(Bank.BANDEC, entries)
}
