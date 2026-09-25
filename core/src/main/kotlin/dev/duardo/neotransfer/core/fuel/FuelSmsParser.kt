package dev.duardo.neotransfer.core.fuel

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Base64
import java.util.Locale

/** Recognizes coupon messages only. A coupon or receipt does not identify an outstanding local purchase. */
object FuelSmsParser {
    private const val BANK_PREFIX = "(?:(?:El\\s+)?Banco\\s+(?:Bandec|Metropolitano|Popular\\s+de\\s+Ahorro|BPA|BANDEC|BANMET)\\s*[:.-]?\\s*|(?:BPA|BANDEC|BANMET)\\s*:\\s*)?"
    private val header = Regex("No\\.\\s*Serie;Saldo actual;Importe pagado;Moneda;ID Banco;ID TM;Fecha;Banco;DatosCupon", RegexOption.IGNORE_CASE)
    private val dayFormat = DateTimeFormatter.ofPattern("d/M/uuuu", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)

    fun parse(sender: String, body: String, receivedAt: Long, subscriptionId: Int?, sourceEventId: String = "",
              evidenceEligible: Boolean = true): FuelSmsUpdate? {
        if (!sender.trim().equals("PAGOxMOVIL", true)) return null
        if (starts(body, "Mis cupones de combustible(?:[.:]|\\s|$)")) {
            val match = header.find(body) ?: return null
            val updates = mutableListOf<FuelCouponUpdate>()
            var rejected = 0
            for (row in body.substring(match.range.last + 1).trim().split(Regex("\\s+")).filter(String::isNotBlank)) {
                val cells = row.split(';')
                if (cells.size != 9) { rejected++; continue }
                val serial = serial(cells[0])
                val date = runCatching { LocalDate.parse(cells[6], dayFormat).toString() }.getOrNull()
                if (serial == null || cells[1].toBigDecimalOrNull() == null || cells[2].toBigDecimalOrNull() == null ||
                    cells[3].isBlank() || cells[4].isBlank() || cells[5].isBlank() || !validEnvelope(cells[8])) { rejected++; continue }
                updates += update(FuelUpdateKind.LIST, serial, cells[3], cells[4], cells[5], cells[8], receivedAt, sourceEventId,
                    subscriptionId, evidenceEligible && date != null, paid = cells[2], balance = cells[1], date = date, bank = bankCode(cells[7]))
            }
            return FuelSmsUpdate(updates, rejected)
        }
        val kind = when {
            starts(body, "(?:El\\s+)?pago del cup[oó]n de combustible fue completado\\.") -> FuelUpdateKind.PURCHASE
            starts(body, "Actualizado el token del cup[oó]n de combustible con\\b") -> FuelUpdateKind.REKEY
            starts(body, "Estado del cup[oó]n de combustible con\\b") -> FuelUpdateKind.STATUS
            starts(body, "(?:Se\\s+)?ha realizado una devoluci[oó]n al cup[oó]n de combustible con\\b") -> FuelUpdateKind.REFUND
            else -> return null
        }
        val serial = serial(field(body, "No\\.\\s*Serie")) ?: return null
        val bankRef = reference(field(body, "No\\.\\s*Transacci[oó]n")) ?: return null
        val tmRef = reference(field(body, "ID\\s+TM")) ?: return null
        val data = field(body, "Datos\\s+del\\s+cup[oó]n")?.takeIf(::validEnvelope) ?: return null
        val label = when (kind) {
            FuelUpdateKind.PURCHASE -> "Importe pagado"
            FuelUpdateKind.REFUND -> "Importe acreditado"
            else -> "Saldo actual"
        }
        val money = Regex("$label\\s*:\\s*([-+]?[0-9]+(?:\\.[0-9]+)?(?:[Ee][-+]?[0-9]+)?)\\s+([A-Za-z]{3})\\b", RegexOption.IGNORE_CASE).find(body) ?: return null
        val amount = money.groupValues[1].takeIf { it.toBigDecimalOrNull() != null } ?: return null
        val currency = money.groupValues[2]
        val value = update(kind, serial, currency, bankRef, tmRef, data, receivedAt, sourceEventId, subscriptionId, evidenceEligible,
            paid = amount.takeIf { kind == FuelUpdateKind.PURCHASE }, balance = amount.takeIf { kind == FuelUpdateKind.STATUS || kind == FuelUpdateKind.REKEY },
            credited = amount.takeIf { kind == FuelUpdateKind.REFUND }, bank = bankCode(body.lineSequence().firstOrNull().orEmpty()))
        return FuelSmsUpdate(listOf(value))
    }

    private fun update(kind: FuelUpdateKind, serial: String, currency: String, bankRef: String, tmRef: String, data: String,
                       receivedAt: Long, eventId: String, subscription: Int?, eligible: Boolean, paid: String? = null,
                       balance: String? = null, credited: String? = null, date: String? = null, bank: String? = null): FuelCouponUpdate {
        val chars = data.toCharArray()
        val envelope = try { FuelProviderEnvelope(chars) } finally { chars.fill('\u0000') }
        return FuelCouponUpdate(kind, FuelCoupon(FuelCoupon.idFor(serial), serial, currency, paid, balance, bank, bankRef, tmRef,
            receivedAt, date, receivedAt.takeIf { balance != null }, eventId, subscription,
            envelope.revision(serial, bankRef, tmRef), eligible), envelope, credited)
    }

    private fun starts(body: String, marker: String) = Regex("^\\s*$BANK_PREFIX$marker", RegexOption.IGNORE_CASE).containsMatchIn(body)
    private fun field(body: String, label: String): String? = Regex("$label\\s*:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
    private fun reference(value: String?): String? = value?.trimEnd('.', ',')?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
    private fun serial(value: String?): String? = reference(value)?.takeIf { it.matches(Regex("[0-9]{13}")) }
    private fun validEnvelope(value: String): Boolean {
        val parts = value.split('|')
        if (parts.size != 2) return false
        return parts.all { part ->
            if (!part.matches(Regex("[A-Za-z0-9+/]+={0,2}"))) false else runCatching {
                val bytes = Base64.getDecoder().decode(part)
                bytes.isNotEmpty() && bytes.size % 16 == 0
            }.getOrDefault(false)
        }
    }

    private fun bankCode(value: String): String? = when {
        Regex("\\b(BANDEC|Bandec)\\b", RegexOption.IGNORE_CASE).containsMatchIn(value) -> "02"
        Regex("\\b(BANMET|Metropolitano)\\b", RegexOption.IGNORE_CASE).containsMatchIn(value) -> "03"
        Regex("\\bBPA\\b|Banco Popular de Ahorro", RegexOption.IGNORE_CASE).containsMatchIn(value) -> "01"
        else -> null
    }
}
