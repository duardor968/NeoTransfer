package dev.duardo.neotransfer.core

private const val SERVICE_MONEY = "([0-9]+(?:\\.[0-9]{1,2})?)\\s+(CUP|USD|CUC)"
private val nautaReceipt = Regex("^Banco\\s+(Bandec|BPA|Popular de Ahorro):\\s+La cuenta\\s*(Nauta Hogar)?\\s*:\\s*(\\S+)\\s+ha sido (pagada|recargada) con\\s+$SERVICE_MONEY\\.", RegexOption.IGNORE_CASE)
private val stampReceipt = Regex("^Banco\\s+(Bandec|BPA|Popular de Ahorro)\\s+El pago del impuesto sobre el documento \\(sello del timbre\\) fue completado\\.", RegexOption.IGNORE_CASE)

/** Nominal service credit is not a bank debit. Missing paid amounts remain unknown. */
internal fun parseServiceReceipt(text: String): BankMessage.ServicePaymentCompleted? {
    fun bank(value: String) = if (value.equals("Bandec", true)) Bank.BANDEC else Bank.BPA
    fun money(value: String, currency: String) = Money(value.toBigDecimal(), Currency.valueOf(currency.uppercase()))
    fun amount(label: String) = Regex("$label:\\s*$SERVICE_MONEY(?:\\.|\\s|$)", RegexOption.IGNORE_CASE)
        .findAll(text).singleOrNull()?.let { money(it.groupValues[1], it.groupValues[2]) }
    fun reference(label: String) = Regex("$label:\\s*([A-Za-z0-9]+)(?:\\.|\\s|$)", RegexOption.IGNORE_CASE)
        .findAll(text).singleOrNull()?.groupValues?.get(1)
    nautaReceipt.find(text)?.let { match ->
        val home = match.groupValues[2].isNotEmpty()
        if (home != match.groupValues[4].equals("pagada", true)) return null
        val nominal = money(match.groupValues[5], match.groupValues[6])
        val paid = amount("Monto Pagado")
        if ((text.contains("Monto Pagado:", true) && paid == null) || (paid != null && paid.currency != nominal.currency)) return null
        return BankMessage.ServicePaymentCompleted(bank(match.groupValues[1]), if (home) "Nauta Hogar" else "Recarga Nauta",
            match.groupValues[3], nominal, paid, reference("Id Transaccion") ?: return null)
    }
    stampReceipt.find(text)?.let { match ->
        val nominal = amount("Valor del sello") ?: return null
        val paid = amount("Importe Pagado") ?: return null
        if (paid.currency != nominal.currency) return null
        val payer = reference("\\bCI")?.takeIf { it.all(Char::isDigit) }
        val entity = Regex("^\\s*Entidad:[ \\t]*([^\\r\\n]+)", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
            .findAll(text).singleOrNull()?.groupValues?.get(1)?.trim()?.takeIf(String::isNotEmpty)
        val stampReference = reference("\\bIdSello")
        val details = if (payer != null && entity != null && stampReference != null) StampReceiptDetails(payer, entity, stampReference) else null
        return BankMessage.ServicePaymentCompleted(bank(match.groupValues[1]), "Sello del timbre", null,
            nominal, paid, reference("Nro\\. Transaccion Banco") ?: return null, details)
    }
    return null
}
