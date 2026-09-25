package dev.duardo.neotransfer.core

/** Explicitly labelled table compatibility, not an observed BANMET/BFI network fixture. */
internal object SemanticBalance {
    private val header = Regex("^Banco\\s+(Metropolitano|BFI|Financiero Internacional)\\s*:?\\s+La consulta de saldo fue completada\\.\\s*" +
        "Cuenta\\s*;\\s*Saldo Contable\\s*;\\s*Saldo Disponible\\s*;\\s*Moneda\\s*", RegexOption.IGNORE_CASE)
    private val amount = Regex("CR\\s+([0-9]+(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)

    fun parse(text: String): BankMessage.Balance? {
        val match = header.find(text) ?: return null
        val bank = if (match.groupValues[1].equals("Metropolitano", true)) Bank.BANMET else Bank.BFI
        val units = if (bank == Bank.BFI) CurrencyContract.BFI.supported else CurrencyContract.BANK.supported
        val rows = text.substring(match.range.last + 1).split('|').map(String::trim).filter(String::isNotEmpty)
        if (rows.isEmpty()) return null
        val balances = rows.map { row ->
            val values = row.split(';').map(String::trim)
            if (values.size != 4 || !values[0].matches(Regex("[0-9Xx*]{16}"))) return null
            val currency = units.singleOrNull { it.name.equals(values[3], true) } ?: return null
            val ledger = amount.matchEntire(values[1])?.groupValues?.get(1)?.toBigDecimalOrNull() ?: return null
            val available = amount.matchEntire(values[2])?.groupValues?.get(1)?.toBigDecimalOrNull() ?: return null
            AccountBalance(values[0], Money(ledger, currency), Money(available, currency))
        }
        return BankMessage.Balance(bank, balances)
    }
}
