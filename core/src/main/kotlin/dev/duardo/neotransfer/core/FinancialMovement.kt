package dev.duardo.neotransfer.core

enum class MovementKind { SENT, RECEIVED, PAYMENT, RECHARGE, LEDGER_CREDIT, LEDGER_DEBIT, FUEL_REFUND }

/** Financial receipts only. Administrative messages never become transactions. */
data class FinancialMovement(
    val kind: MovementKind,
    val amount: Money,
    val bank: Bank?,
    val party: String,
    val account: String? = null,
    val reference: String? = null,
    val purchaseId: String? = null,
    val bankDate: String? = null,
    val amountIsNominal: Boolean = false,
    val nominalAmount: Money? = null,
) {
    val incoming get() = kind == MovementKind.RECEIVED || kind == MovementKind.LEDGER_CREDIT || kind == MovementKind.FUEL_REFUND
    fun matches(query: String): Boolean {
        val parts = query.trim().lowercase().split(Regex("\\s+")).filter(String::isNotEmpty)
        val content = listOf(party, account, reference, purchaseId, amount.amount.toPlainString(), amount.currency.name, bank?.name)
            .filterNotNull().joinToString(" ").lowercase()
        return parts.all { it in content }
    }

    companion object {
        fun from(message: BankMessage): FinancialMovement? = when (message) {
            is BankMessage.TransferSent -> FinancialMovement(MovementKind.SENT, message.amount, message.bank,
                message.beneficiary, reference = message.reference)
            is BankMessage.TransferReceived -> FinancialMovement(MovementKind.RECEIVED, message.amount, null,
                message.senderPhone, message.account, message.reference)
            is BankMessage.PaymentCompleted -> FinancialMovement(MovementKind.PAYMENT, message.amount, message.bank,
                message.merchant.orEmpty(), reference = message.reference, purchaseId = message.purchaseId, bankDate = message.bankDate)
            is BankMessage.RechargeCompleted -> FinancialMovement(MovementKind.RECHARGE, message.amount, message.bank,
                message.phone, reference = message.reference)
            is BankMessage.ServicePaymentCompleted -> FinancialMovement(MovementKind.PAYMENT, message.paid ?: message.nominal,
                message.bank, listOfNotNull(message.service, message.account, message.stamp?.payerIdentity, message.stamp?.recipientEntity).joinToString(" · "),
                reference = message.reference, purchaseId = message.stamp?.stampReference,
                amountIsNominal = message.paid == null, nominalAmount = message.nominal)
            is BankMessage.BillPaymentCompleted -> FinancialMovement(MovementKind.PAYMENT, message.paid ?: checkNotNull(message.nominal),
                message.bank, listOfNotNull(message.service.label, message.account).joinToString(" · "),
                account = message.account, reference = message.reference,
                amountIsNominal = message.paid == null, nominalAmount = message.nominal)
            else -> null
        }
    }
}
