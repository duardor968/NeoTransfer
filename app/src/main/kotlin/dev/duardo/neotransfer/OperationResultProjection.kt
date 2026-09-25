package dev.duardo.neotransfer

import dev.duardo.neotransfer.core.*
import dev.duardo.neotransfer.data.*

/** A receipt must identify the operation; matching an amount alone cannot establish a payment. */
internal object OperationResultProjection {
    fun couldBelongTo(operation: OperationRecord, message: BankMessage): Boolean =
        if (message is BankMessage.ServicePaymentCompleted) serviceProof(operation, message, possible = true) else matches(operation, message)

    fun matches(operation: OperationRecord, message: BankMessage): Boolean {
        val amount = operation.amount?.toBigDecimalOrNull()
        fun same(value: Money): Boolean = operation.currency == value.currency.name && amount?.compareTo(value.amount) == 0
        val destination = operation.destination
        return when (message) {
            is BankMessage.TransferSent -> operation.bankCode == message.bank.code &&
                (operation.kind == ActionKind.TRANSFER.name || operation.specId.endsWith(".transfer")) &&
                same(message.amount) && matchesAccount(message.beneficiary, destination)
            is BankMessage.RechargeCompleted -> operation.bankCode != null && operation.bankCode == message.bank?.code &&
                (operation.kind == ActionKind.RECHARGE.name || operation.specId.substringAfter('.').startsWith("mobile")) &&
                same(message.amount) && message.phone == destination
            is BankMessage.PaymentCompleted -> operation.bankCode != null && operation.bankCode == message.bank?.code &&
                operation.kind == ActionKind.QR.name && same(message.amount) &&
                message.purchaseId != null && message.purchaseId == operation.qr?.transactionId
            is BankMessage.BillPaymentCompleted -> {
                val paid = message.paid
                operation.bankCode == message.bank.code && paid != null &&
                (same(paid) || amount == null && operation.parameters["paymentMode"] == "FULL_INVOICE" &&
                    operation.specId in setOf("service.electricity", "service.telephone") && operation.currency == paid.currency.name) &&
                message.account != null && message.account == destination && when (message.service) {
                    BillService.ELECTRICITY -> operation.kind == ActionKind.ELECTRICITY.name || operation.specId.endsWith(".electricity")
                    BillService.TELEPHONE -> operation.kind == ActionKind.TELEPHONE.name ||
                        operation.specId in setOf("service.telephone", "service.telephone.bpa", "wallet.telephone")
                    BillService.GAS -> operation.specId.endsWith(".gas")
                }
            }
            is BankMessage.ServicePaymentCompleted -> serviceProof(operation, message)
            else -> false
        }
    }

    private fun serviceProof(operation: OperationRecord, message: BankMessage.ServicePaymentCompleted, possible: Boolean = false): Boolean {
        val identity = runCatching { ProviderIdentity(ProviderId.valueOf(operation.providerId.orEmpty()), ProfileId.valueOf(operation.profileId)) }.getOrNull()
        val nominal = runCatching { Money(requireNotNull(operation.amount).toBigDecimal(), Currency.valueOf(operation.currency.orEmpty())) }.getOrNull()
        if (operation.kind != operation.specId || operation.bankCode != message.bank.code || identity == null || nominal == null) return false
        return if (possible) ServiceReceiptCorrelation.couldBelongTo(operation.specId, identity, nominal, operation.parameters, message)
            else ServiceReceiptCorrelation.matches(operation.specId, identity, nominal, operation.parameters, message)
    }

    fun safeParameters(spec: OperationSpec, request: ServiceRequest): Map<String, String> =
        request.values.filterKeys { key -> spec.fields.any { it.key == key && !it.sensitive && !it.suppliedByAccess } &&
            !Regex("(?i)pin|clave|password|credential|secret|otp|token|ussd|payload|coord|contrase").containsMatchIn(key) }
}
