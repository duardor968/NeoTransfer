package dev.duardo.neotransfer.core

import java.text.Normalizer
import java.util.Locale

/** Family-specific receipt evidence. Sender, SIM, delivery time and uniqueness are checked by the caller. */
object ServiceReceiptCorrelation {
    /** Service 86 may share the Hogar wording; it can block ambiguity but this does not prove its own completion. */
    fun couldBelongTo(specId: String, identity: ProviderIdentity, authorizedNominal: Money,
                      fields: Map<String, String>, receipt: BankMessage.ServicePaymentCompleted): Boolean =
        matches(if (specId == "service.nauta.debt") "service.nauta.home" else specId, identity, authorizedNominal, fields, receipt)

    fun matches(specId: String, identity: ProviderIdentity, authorizedNominal: Money,
                fields: Map<String, String>, receipt: BankMessage.ServicePaymentCompleted): Boolean {
        if (identity != ProviderIdentity.forBank(receipt.bank) || receipt.bank !in setOf(Bank.BPA, Bank.BANDEC) ||
            !receipt.reference.matches(Regex("[A-Za-z0-9]+")) || authorizedNominal.currency != Currency.CUP ||
            !authorizedNominal.sameValue(receipt.nominal) || fields["amount"]?.toBigDecimalOrNull()?.compareTo(authorizedNominal.amount) != 0) return false
        val paid = receipt.paid ?: return false
        // Keep the stated debit; never calculate a discount or approve a debit above the reviewed value.
        if (paid.currency != authorizedNominal.currency || paid.amount.signum() <= 0 || paid.amount > authorizedNominal.amount) return false
        return when (specId) {
            "service.nauta", "service.nauta.home" -> {
                if (receipt.service != if (specId == "service.nauta") "Recarga Nauta" else "Nauta Hogar") return false
                val username = fields["username"]?.takeIf { it.isNotBlank() && it.none { c -> c == '@' || c.isWhitespace() } } ?: return false
                // PqTzgaEYF6:417-423. A bare user does not identify which of the two domains was paid.
                val domain = when (fields["accountType"]) { "1" -> "nauta.com.cu"; "2" -> "nauta.co.cu"; else -> return false }
                receipt.account == "$username@$domain"
            }
            "service.stamp" -> {
                if (receipt.service != "Sello del timbre") return false
                val details = receipt.stamp ?: return false
                if (details.payerIdentity != fields["taxpayer"] || !details.payerIdentity.matches(Regex("[0-9]+")) ||
                    !details.stampReference.matches(Regex("[A-Za-z0-9]+"))) return false
                // The received alias is demonstrated for this code; do not guess from partial name matches.
                val names = when (fields["entity"]) {
                    "95016" -> setOf("tramites minint", "oficinas tramites minint")
                    "95013" -> setOf("otros tramites")
                    else -> return false
                }
                normalizedName(details.recipientEntity) in names
            }
            else -> false
        }
    }

    private fun normalizedName(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
}
