package dev.duardo.neotransfer.core

import java.util.Locale

/** Parses only the supported PAGOxMOVIL formats. Keep the original SMS separately. */
class BankSmsParser {
    fun parse(sender: String, body: String): BankMessage? {
        if (!sender.equals("PAGOxMOVIL", ignoreCase = true)) return null
        val text = body.replace("\r\n", "\n").trim()

        authentication.matchEntire(text)?.let { match ->
            val bank = bank(match.groupValues[1]) ?: return BankMessage.Unrecognized
            return BankMessage.Authenticated(bank, match.groupValues[2].takeIf(String::isNotEmpty))
        }
        if (bpaAuthentication.matches(text)) return BankMessage.Authenticated(Bank.BPA, null)

        incoming.matchEntire(text.replace(Regex("\\s+"), " "))?.let { match ->
            val amount = money(match.groupValues[3], match.groupValues[4])
                ?: return BankMessage.Unrecognized
            return BankMessage.TransferReceived(
                account = match.groupValues[2],
                senderPhone = match.groupValues[1],
                amount = amount,
                reference = match.groupValues[5],
            )
        }

        rechargeRejection.matchEntire(text.replace(Regex("\\s+"), " "))?.let { match ->
            return BankMessage.RechargeRejected(match.groupValues[1])
        }

        outgoingHeader.find(text)?.let { header ->
            val bank = bank(header.groupValues[1]) ?: return BankMessage.Unrecognized
            val beneficiary = field(text, "Beneficiario")
                ?.takeIf { accountPattern.matches(it) } ?: return BankMessage.Unrecognized
            val amount = field(text, "Monto")?.let(::parseMoney)
                ?: return BankMessage.Unrecognized
            val reference = field(text, "Nro\\. Transaccion")
                ?.takeIf { referencePattern.matches(it) } ?: return BankMessage.Unrecognized
            val remaining = field(text, "Saldo restante")
                ?.let { creditBalance.matchEntire(it) }
                ?.let { money(it.groupValues[1], it.groupValues[2]) }
                ?.takeIf { it.currency == amount.currency }
            return BankMessage.TransferSent(bank, beneficiary, amount, reference, remaining)
        }

        balanceHeader.find(text)?.let { header ->
            val bank = bank(header.groupValues[1]) ?: return BankMessage.Unrecognized
            if (bank == Bank.BPA) {
                bpaBalance(text.substring(header.range.last + 1).trim())?.let { return it }
            }
            val table = balanceColumns.find(text) ?: return BankMessage.Unrecognized
            val rows = text.substring(table.range.last + 1).split('|').map(String::trim)
                .filter(String::isNotEmpty)
            if (rows.isEmpty()) return BankMessage.Unrecognized
            val accounts = rows.map { row ->
                val cells = row.split(';').map(String::trim)
                if (cells.size != 4 || !accountPattern.matches(cells[0])) {
                    return BankMessage.Unrecognized
                }
                // CR is the marker observed in the real sample; do not guess other signs.
                val ledger = creditAmount.matchEntire(cells[1])
                    ?.let { money(it.groupValues[1], cells[3]) }
                    ?: return BankMessage.Unrecognized
                val available = creditAmount.matchEntire(cells[2])
                    ?.let { money(it.groupValues[1], cells[3]) }
                    ?: return BankMessage.Unrecognized
                AccountBalance(cells[0], ledger, available)
            }
            return BankMessage.Balance(bank, accounts)
        }

        paymentHeader.find(text)?.let {
            val paid = field(text, "Importe pagado")?.let(::parseMoney) ?: return BankMessage.Unrecognized
            val reference = field(text, "(?:No|Nro)\\. Transaccion")?.takeIf(referencePattern::matches)
            val purchase = field(text, "Id Compra")?.takeIf(referencePattern::matches)
            if (reference == null && purchase == null) return BankMessage.Unrecognized
            return BankMessage.PaymentCompleted(headerBank(text), field(text, "Entidad"), paid, reference, purchase, field(text, "Fecha"))
        }

        if (rechargeSuccess.containsMatchIn(text)) {
            val paid = rechargePaid.find(text)?.let { money(it.groupValues[1], it.groupValues[2]) }
                ?: return BankMessage.Unrecognized
            val phone = rechargePhone.find(text)?.groupValues?.get(1) ?: return BankMessage.Unrecognized
            val reference = rechargeReference.find(text)?.groupValues?.get(1) ?: return BankMessage.Unrecognized
            return BankMessage.RechargeCompleted(headerBank(text), phone, paid, reference)
        }

        parseServiceReceipt(text)?.let { return it }
        return BankMessage.Unrecognized
    }

    private fun bpaBalance(text: String): BankMessage.Balance? {
        bpaAvailable.matchEntire(text)?.let { match ->
            val available = money(match.groupValues[1], match.groupValues[2]) ?: return null
            return BankMessage.Balance(Bank.BPA, listOf(AccountBalance(null, null, available)))
        }
        val table = bpaBalanceColumns.find(text) ?: return null
        val rows = text.substring(table.range.last + 1).lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (rows.isEmpty()) return null
        val accounts = rows.map { row ->
            val cells = row.split(';').map(String::trim)
            if (cells.size != 4 || cells[0].isNotEmpty() || !bpaAccountLabel.matches(cells[1]) || !amountOnly.matches(cells[2])) return null
            val available = money(cells[2], cells[3]) ?: return null
            // These rows contain a label in Nro Cuenta, no card number or ledger balance.
            AccountBalance(null, null, available, label = cells[1])
        }
        return BankMessage.Balance(Bank.BPA, accounts)
    }

    private fun headerBank(text: String): Bank? =
        Regex("^Banco\\s+(Bandec|BPA|Popular de Ahorro)\\b", insensitive).find(text)?.groupValues?.get(1)?.let(::bank)

    private fun field(text: String, label: String): String? =
        Regex("^\\s*$label\\s*:\\s*([^\\r\\n]+)", multilineInsensitive)
            .find(text)?.groupValues?.get(1)?.trim()

    private fun parseMoney(value: String): Money? =
        amountCurrency.matchEntire(value)?.let { money(it.groupValues[1], it.groupValues[2]) }

    private fun money(value: String, currency: String): Money? {
        val amount = value.toBigDecimalOrNull() ?: return null
        if (amount.signum() < 0 || amount.scale() > 2) return null
        val unit = Currency.entries.find { it.name == currency.uppercase(Locale.ROOT) } ?: return null
        return Money(amount, unit)
    }

    private fun bank(value: String): Bank? = when (value.lowercase(Locale.ROOT)) {
        "bandec" -> Bank.BANDEC
        "bpa", "popular de ahorro" -> Bank.BPA
        else -> null
    }

    private companion object {
        const val NUMBER = "([0-9]+(?:\\.[0-9]{1,2})?)"
        const val UNIT = "(CUP|USD|CUC)"
        val insensitive = setOf(RegexOption.IGNORE_CASE)
        val multilineInsensitive = setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        val accountPattern = Regex("[0-9Xx*]{16}")
        val referencePattern = Regex("[A-Za-z0-9]+")
        val paymentHeader = Regex("^(?:Banco\\s+(?:Bandec|BPA|Popular de Ahorro)\\s*:\\s*)?(?:Pago completado|La compra fue completada)\\.", insensitive)
        val rechargeSuccess = Regex("^(?:Banco\\s+(?:Bandec|BPA|Popular de Ahorro)\\s*:\\s*)?La recarga se realizo con exito\\.", insensitive)
        val rechargePaid = Regex("Monto Pagado:\\s*$NUMBER\\s+$UNIT(?:\\.|\\s|$)", insensitive)
        val rechargePhone = Regex("Telefono:\\s*([0-9+]{8,13})(?:\\.|\\s|$)", insensitive)
        val rechargeReference = Regex("Id transaccion:\\s*([A-Za-z0-9]+)(?:\\.|\\s|$)", insensitive)
        val amountCurrency = Regex("$NUMBER\\s+$UNIT", insensitive)
        val creditBalance = Regex("CR\\s+$NUMBER\\s+$UNIT", insensitive)
        val creditAmount = Regex("CR\\s+$NUMBER", insensitive)
        val outgoingHeader = Regex(
            "^Banco\\s+(Bandec|BPA|Popular de Ahorro)\\s*:\\s*La Transferencia fue completada\\.",
            insensitive,
        )
        val balanceHeader = Regex(
            "^Banco\\s+(Bandec|BPA|Popular de Ahorro)\\s*:?\\s+La consulta de saldo fue completada\\.",
            insensitive,
        )
        val balanceColumns = Regex(
            "Cuenta\\s*;\\s*Saldo Contable\\s*;\\s*Saldo Disponible\\s*;\\s*Moneda",
            insensitive,
        )
        val bpaAvailable = Regex("Saldo Disponible:\\s*CR\\s+$NUMBER\\s+$UNIT", insensitive)
        val bpaBalanceColumns = Regex("^Nombre Cuenta\\s*;\\s*Nro Cuenta\\s*;\\s*Saldo Disponible\\s*;\\s*Moneda", insensitive)
        val bpaAccountLabel = Regex("[A-Za-z]+(?:[-.][A-Za-z]+)?")
        val amountOnly = Regex(NUMBER)
        val authentication = Regex(
            "Usted se ha autenticado en la plataforma de pagos moviles,\\s*en el Banco " +
                "(Bandec|BPA|Popular de Ahorro) con la cuenta ([0-9Xx*]{16})?,\\s*" +
                "puede comenzar a utilizar nuestros servicios de pagos a traves del movil\\.?",
            insensitive,
        )
        val bpaAuthentication = Regex(
            "Usted se ha autenticado en la plataforma de pagos moviles,\\s*en el Banco Popular de Ahorro,\\s*" +
                "puede comenzar a utilizar nuestros servicios de pagos a traves del movil\\.?(?:\\s+Informacion:[\\s\\S]*)?",
            insensitive,
        )
        val incoming = Regex(
            "El titular del telefono ([0-9+]+) le ha realizado una transferencia a la cuenta " +
                "([0-9Xx*]{16}) de $NUMBER $UNIT\\. Nro\\. Transaccion:? ([A-Za-z0-9]+)" +
                "(?:\\. Fecha: [0-9]{1,2}/[0-9]{1,2}/[0-9]{4}\\.)?",
            insensitive,
        )
        val rechargeRejection = Regex(
            "Fallo la recarga del movil ([0-9]+) alcanzo el monto limite de recarga permitido " +
                "en 30 dias \\($NUMBER $UNIT\\), puede recargar posterior al dia " +
                "[0-9]{1,2}-[0-9]{1,2}-[0-9]{4}\\.",
            insensitive,
        )
    }
}
