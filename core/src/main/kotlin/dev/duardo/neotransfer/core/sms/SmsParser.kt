package dev.duardo.neotransfer.core.sms

import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Currency
import dev.duardo.neotransfer.core.model.Money
import dev.duardo.neotransfer.core.sms.SmsMessage.*
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * Reads PAGOxMOVIL bodies and BANDEC's own alerts. Tolerates accents, case, repeated spaces and line breaks, missing
 * "Fecha", and labels with or without colon. Unknown wording becomes [Unrecognized], never a guess.
 */
object SmsParser {
    fun parse(body: String): SmsMessage {
        val text = Normalizer.normalize(body, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
            .replace(Regex("\\s+"), " ").trim()
        return when {
            text.startsWith("BANDEC >>", ignoreCase = true) -> cardOperation(text)
            has(text, "operacion de registro fue completada") -> Registration
            has(text, "Usted se ha autenticado") -> authenticated(text)
            has(text, "Error de autenticacion") || has(text, "AUTENTICACION HA FALLADO") || has(text, "Clave incorrecta") -> authenticationFailed(text)
            has(text, "solicitud en proceso") -> Busy
            has(text, "Ultimas operaciones") -> history(text)
            has(text, "consulta de saldo fue completada") -> balance(text)
            rejection.containsMatchIn(text) -> rejected(text)
            has(text, "La Transferencia fue completada") -> transferSent(text)
            has(text, "El titular del telefono") -> transferReceived(text)
            has(text, "Se ha realizado una transferencia") -> transferNotice(text)
            has(text, "Pago completado") -> payment(text)
            has(text, "pago externo fue completado") -> externalPayment(text)
            has(text, "Se ha pagado la cuenta") || has(text, "Se ha recargado la cuenta") -> serviceNotice(text)
            has(text, "recibido una recarga de saldo movil") -> mobileBalance(text)
            else -> serviceReceipt(text)
        } ?: Unrecognized
    }

    private const val AMOUNT = "([0-9]+(?:\\.[0-9]{1,2})?)"
    private const val UNIT = "(CUP|USD)"
    private const val REF = "([A-Za-z0-9]+)"
    private val options = setOf(RegexOption.IGNORE_CASE)
    private fun rx(pattern: String) = Regex(pattern, options)
    private fun has(text: String, phrase: String) = text.contains(phrase, ignoreCase = true)

    private val rejection = rx("^(?:Banco [A-Za-z ]+?:? )?(?:Fallo|Error)\\b|vuelva a intentarlo")
    private val bankName = rx("Banco:? (Popular de Ahorros?|BPA|Bandec|Metropolitano)\\b")

    private fun bank(text: String): Bank? = bankName.find(text)?.groupValues?.get(1)?.let(::bankOf)

    private fun bankOf(name: String): Bank = when {
        name.equals("Bandec", true) -> Bank.BANDEC
        name.equals("Metropolitano", true) -> Bank.BANMET
        else -> Bank.BPA
    }

    private fun money(value: String, unit: String?): Money? {
        val amount = value.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 && it.scale() <= 2 } ?: return null
        return Money(amount, if (unit.equals("USD", true)) Currency.USD else Currency.CUP)
    }

    /** `Label: 12.50 CUP` with optional colon and `CR`/`DB` marker; `DB` is not a sign. */
    private fun labeled(text: String, label: String, unitRequired: Boolean = true): Money? =
        rx("(?<![A-Za-z])$label ?:? ?(?:(?:CR|DB) ?)?$AMOUNT ?${if (unitRequired) UNIT else "$UNIT?"}(?![0-9])").find(text)
            ?.let { money(it.groupValues[1], it.groupValues[2].ifEmpty { null }) }

    private fun reference(text: String): String? =
        rx("(?:(?:Nro|No)\\.? ?Transaccion(?: Banco)?|Id ?Transaccion) ?:? ?$REF").find(text)?.groupValues?.get(1)

    private fun token(text: String, label: String): String? = rx("$label ?:? ?([^ ]+)").find(text)?.groupValues?.get(1)?.trimEnd('.', ',')

    /** Alert wordings whose direction is known: label to show and whether it is a credit. */
    private val alertKinds = mapOf(
        // Last operations list the same references as "Extraccion en Linea", debits.
        "operacion en linea sucursal" to ("Extracción en sucursal" to false),
    )

    private fun cardOperation(text: String): SmsMessage? {
        val match = rx("^BANDEC >> (.+?) por $AMOUNT ?$UNIT? Tarjeta ([0-9]{16})\\. Referencia $REF\\b").find(text) ?: return null
        val (label, credit) = alertKinds[match.groupValues[1].lowercase()] ?: return null
        val amount = money(match.groupValues[2], match.groupValues[3].ifEmpty { null }) ?: return null
        return CardOperation(Bank.BANDEC, match.groupValues[4], label, credit, amount, match.groupValues[5])
    }

    private fun authenticated(text: String): SmsMessage? {
        val match = rx("en el Banco (Bandec|Popular de Ahorros?|BPA|Metropolitano)(?: con la cuenta ?([0-9Xx*]{16})?)? ?,").find(text) ?: return null
        return Authenticated(bankOf(match.groupValues[1]), match.groupValues[2].ifEmpty { null })
    }

    private fun authenticationFailed(text: String): SmsMessage {
        val wrongPin = has(text, "Clave incorrecta")
        return AuthenticationFailed(bank(text), wrongPin, if (wrongPin) "Clave incorrecta" else "No se pudo autenticar")
    }

    private fun balance(text: String): SmsMessage? {
        val bank = bank(text) ?: return null
        val bandecRows = rx("([0-9Xx*]{16}) ?; ?(?:(?:CR|DB) ?)?$AMOUNT ?; ?(?:(?:CR|DB) ?)?$AMOUNT ?; ?$UNIT").findAll(text).toList().map {
            BalanceRow(it.groupValues[1], null, money(it.groupValues[2], it.groupValues[4]), money(it.groupValues[3], it.groupValues[4]) ?: return null)
        }
        if (bandecRows.isNotEmpty()) return Balance(bank, bandecRows)
        labeled(text, "Saldo Disponible")?.let { return Balance(bank, listOf(BalanceRow(null, null, null, it))) }
        val tableRows = rx("; ?([A-Za-z][A-Za-z.-]*) ?; ?$AMOUNT ?; ?$UNIT").findAll(text).toList().map {
            BalanceRow(null, it.groupValues[1], null, money(it.groupValues[2], it.groupValues[3]) ?: return null)
        }
        return if (tableRows.isNotEmpty()) Balance(bank, tableRows) else null
    }

    private val historyDate = DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT)

    private fun history(text: String): SmsMessage? {
        val bank = bank(text) ?: return null
        val table = rx("NoTransaccion").find(text)?.let { text.substring(it.range.last + 1) } ?: return null
        val entries = table.split('|').map(String::trim).filter(String::isNotEmpty).map { row ->
            val cells = row.split(';').map(String::trim)
            if (cells.size < 5) return null
            val date = try { LocalDate.parse(cells[0], historyDate) } catch (_: Exception) { return null }
            val credit = when (cells[2].lowercase()) { "cr" -> true; "db" -> false; else -> return null }
            val amount = money(cells[3], cells[4]) ?: return null
            val ref = cells.getOrNull(5)?.takeIf(String::isNotEmpty) ?: rx("Ref: ?$REF").find(cells[1])?.groupValues?.get(1)
            HistoryEntry(date, cells[1].replace(rx(" ?Ref: ?[A-Za-z0-9]*"), "").trim().trimEnd('.', ':'), credit, amount, ref)
        }
        return History(bank, entries)
    }

    private fun transferSent(text: String): SmsMessage? {
        val bank = bank(text) ?: return null
        val beneficiary = token(text, "Beneficiario")?.takeIf { it.matches(Regex("[0-9Xx*]{16}")) } ?: return null
        val source = token(text, "Ordenante")?.takeIf { it.matches(Regex("[0-9Xx*]{16}")) }
        val amount = labeled(text, "Monto") ?: return null
        return TransferSent(bank, beneficiary, source, amount, reference(text) ?: return null,
            labeled(text, "Saldo restante", unitRequired = false)?.let { Money(it.amount, amount.currency) })
    }

    private fun transferReceived(text: String): SmsMessage? {
        val match = rx("telefono (\\+?[0-9]+) le ha realizado una transferencia a la cuenta ([0-9]{16}) de $AMOUNT ?$UNIT").find(text) ?: return null
        return TransferReceived(match.groupValues[2], match.groupValues[1], money(match.groupValues[3], match.groupValues[4]) ?: return null,
            reference(text) ?: return null)
    }

    private fun transferNotice(text: String): SmsMessage? {
        val match = rx("transferencia a la cuenta ([0-9]{16}) de $AMOUNT ?$UNIT").find(text) ?: return null
        return TransferNotice(match.groupValues[1], money(match.groupValues[2], match.groupValues[3]) ?: return null, reference(text) ?: return null)
    }

    private fun payment(text: String): SmsMessage? {
        val bank = bank(text) ?: return null
        val paid = labeled(text, "Importe pagado") ?: labeled(text, "Importe") ?: return null
        return PaymentCompleted(
            bank, rx("Entidad ?: ?(.+?) (?:Id Compra|Importe)").find(text)?.groupValues?.get(1)?.trim(),
            token(text, "Id Compra"), labeled(text, "Importe"), paid, reference(text) ?: return null,
            labeled(text, "Saldo (?:disponible|restante)", unitRequired = false)?.let { Money(it.amount, paid.currency) },
        )
    }

    /**
     * "El pago externo fue completado": an online payment. `Nro. Transaccion` is the merchant's id, the bank's
     * reference comes after `Nro. Transaccion Banco`; no balance.
     */
    private fun externalPayment(text: String): SmsMessage? {
        val bank = bank(text) ?: return null
        val paid = labeled(text, "Monto Pagado") ?: return null
        val reference = rx("Nro\\.? ?Transaccion Banco ?:? ?$REF").find(text)?.groupValues?.get(1) ?: return null
        val purchase = rx("Nro\\.? ?Transaccion ?:? ?([0-9]+)").find(text)?.groupValues?.get(1)
        val merchant = rx("Entidad ?: ?(.+?) Nro").find(text)?.groupValues?.get(1)?.trim()
        return PaymentCompleted(bank, merchant, purchase, null, paid, reference, null)
    }

    private fun serviceNotice(text: String): SmsMessage? {
        val match = rx("Se ha (pagado|recargado) la cuenta (?:nauta hogar )?([^ ]+) con $AMOUNT ?$UNIT").find(text) ?: return null
        val service = if (match.groupValues[1].equals("pagado", true)) PaidService.NAUTA_HOME else PaidService.NAUTA_RECHARGE
        return ServiceNotice(service, match.groupValues[2], money(match.groupValues[3], match.groupValues[4]) ?: return null, reference(text) ?: return null)
    }

    private fun mobileBalance(text: String): SmsMessage? {
        val match = rx("por un monto de $AMOUNT ?$UNIT del numero (\\+?[0-9]+)").find(text) ?: return null
        return MobileBalanceReceived(money(match.groupValues[1], match.groupValues[2]) ?: return null, match.groupValues[3])
    }

    private fun serviceReceipt(text: String): SmsMessage? {
        val bank = bank(text) ?: return null
        val reference = reference(text) ?: return null
        val nauta = rx("La cuenta ?(Nauta Hogar)? ?: ?([^ ]+) ha sido (pagada|recargada) con $AMOUNT ?$UNIT").find(text)
        val (service, account, nominal) = when {
            nauta != null -> Triple(
                if (nauta.groupValues[1].isNotEmpty()) PaidService.NAUTA_HOME else PaidService.NAUTA_RECHARGE,
                nauta.groupValues[2], money(nauta.groupValues[4], nauta.groupValues[5]),
            )
            has(text, "La recarga se realizo con exito") ->
                Triple(PaidService.MOBILE_RECHARGE, token(text, "Telefono"), labeled(text, "Saldo a acreditar") ?: labeled(text, "Saldo acreditado"))
            has(text, "factura de electricidad fue completado") -> Triple(PaidService.ELECTRICITY, token(text, "Nro\\. Factura Pagada"), null)
            has(text, "factura telefonica fue completado") ->
                Triple(PaidService.PHONE, token(text, "Nro\\. Factura Pagada"), labeled(text, "Importe Factura"))
            has(text, "factura del agua fue completado") -> Triple(PaidService.WATER, token(text, "(?:Nro\\.? )?(?:de )?Cuenta"), null)
            has(text, "sello del timbre") -> Triple(PaidService.STAMP, null, labeled(text, "Valor del sello"))
            else -> return null
        }
        val paid = labeled(text, "Monto Pagado") ?: labeled(text, "Importe Pagado") ?: nominal
            ?: rx("$AMOUNT ?$UNIT").find(text.substringAfter("completado"))?.let { money(it.groupValues[1], it.groupValues[2]) }
            ?: return null
        val balance = labeled(text, "Saldo Restante", unitRequired = false)?.let { Money(it.amount, paid.currency) }
        return ServicePaid(bank, service, account, nominal?.takeIf { it != paid }, paid, reference, balance)
    }

    private fun rejected(text: String): SmsMessage {
        val bank = bank(text)
        fun r(operation: RejectedOperation, reason: String, detail: String? = null) = Rejected(bank, operation, reason, detail)
        rx("Fallo la recarga del movil ([0-9]+) alcanzo el monto limite de recarga permitido en ([0-9]+) dias \\($AMOUNT ?$UNIT\\),? puede recargar posterior al dia ([0-9-/]+)")
            .find(text)?.let {
                return r(RejectedOperation.MOBILE_RECHARGE, "El móvil llegó al límite de recarga de ${it.groupValues[2]} días " +
                    "(${it.groupValues[3]} ${it.groupValues[4].uppercase()}). Se podrá recargar después del ${it.groupValues[5].trimEnd('.')}.", it.groupValues[1])
            }
        return when {
            has(text, "consulta de saldo") -> r(RejectedOperation.BALANCE, "El banco no pudo consultar el saldo.")
            has(text, "asociacion de tarjeta") -> r(RejectedOperation.CARD_ASSOCIATION, humanize(text))
            has(text, "deuda de la cuenta Nauta Hogar") ->
                r(RejectedOperation.NAUTA_HOME_DEBT, if (has(text, "no tiene deuda")) "La cuenta Nauta Hogar no tiene deuda." else humanize(text))
            has(text, "cuenta Nauta Hogar") -> {
                val wrong = rx("la cuenta ([^ ]+) es incorrecta").find(text)
                val minimum = rx("mayor que $AMOUNT").find(text)
                when {
                    wrong != null -> r(RejectedOperation.NAUTA_HOME, "La cuenta Nauta Hogar ${wrong.groupValues[1]} no es correcta.", wrong.groupValues[1])
                    minimum != null -> r(RejectedOperation.NAUTA_HOME, "El importe tiene que ser mayor que ${minimum.groupValues[1]} CUP.")
                    else -> r(RejectedOperation.NAUTA_HOME, humanize(text))
                }
            }
            has(text, "Fallo la transferencia") -> r(RejectedOperation.TRANSFER, humanize(text))
            has(text, "recarga del movil") -> r(RejectedOperation.MOBILE_RECHARGE, humanize(text))
            has(text, "nauta") -> r(RejectedOperation.NAUTA_RECHARGE, humanize(text))
            has(text, "electric") -> r(RejectedOperation.ELECTRICITY, humanize(text))
            has(text, "telefon") -> r(RejectedOperation.PHONE, humanize(text))
            has(text, "agua") -> r(RejectedOperation.WATER, humanize(text))
            has(text, "plazo fijo") -> r(RejectedOperation.OTHER, "No se pudo cerrar el depósito a plazo fijo.")
            has(text, "Fallo el pago") || has(text, "Fallo la compra") -> r(RejectedOperation.PAYMENT, humanize(text))
            else -> r(RejectedOperation.UNKNOWN, humanize(text))
        }
    }

    private val known = mapOf(
        "la cuenta no esta asociada a una tarjeta magnetica" to "La cuenta no está asociada a una tarjeta magnética.",
        "esta tarjeta ya esta asociada a un celular" to "Esta tarjeta ya está asociada a un celular.",
    )

    /** The bank's own explanation in sentence case, without bank names and contact boilerplate. */
    private fun humanize(text: String): String {
        val core = text
            .replace(rx("vuelva a intentarlo,? si persiste el problema,? contacte con (?:su proveedor de servicios|ETECSA)\\.?"), " ")
            .replace(rx("^(?:Fallo|Error) [^.:]*?[.:] ?(?=Banco )"), "")
            .replace(bankName, " ").replace(Regex("^[ :.]+"), "")
            .replace(Regex("\\s+"), " ").trim().trimEnd('.')
        known[core.lowercase()]?.let { return it }
        val reason = core.ifEmpty { "El banco no pudo completar la operación" }
        return reason.lowercase().replaceFirstChar { it.uppercase() } + "."
    }
}
