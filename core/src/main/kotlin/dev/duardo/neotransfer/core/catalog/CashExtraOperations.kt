package dev.duardo.neotransfer.core

import java.math.BigDecimal
import java.time.LocalDate

/** UTk9UWZYxf: Caja Extra accepts only static QR and preserves its merchant identity. */
object CashExtraOperations {
    fun fromQr(identity: ProviderIdentity, source: SourceSelector, sourceCurrency: Currency?, qr: QrPayment,
               amount: Money, description: String = qr.description, phone: String? = null,
               today: LocalDate = LocalDate.now()): ServiceRequest {
        qr.validateDate(today)
        require(qr.service == 31) { "Caja Extra requiere un QR estático" }
        require(amount.currency == qr.amount.currency && amount.amount.signum() > 0) { "Moneda o importe incompatible con el QR" }
        require(qr.editableAmount || qr.amount.sameValue(amount)) { "El importe de este QR no se puede modificar" }
        require(qr.description.isEmpty() || qr.description == description) { "La descripción de este QR no se puede modificar" }
        val request = ServiceRequest("service.cash.extra", identity, source, sourceCurrency, buildMap {
            put("transaction", qr.transactionId)
            put("provider", qr.provider)
            put("auxiliary", qr.auxiliary)
            put("amount", amount.amount.toPlainString())
            put("amountCurrency", CurrencyContract.BANK.code(amount.currency))
            put("description", description)
            phone?.takeIf { it.isNotEmpty() }?.let { put("phone", it) }
        })
        requireValidContract(ServiceOperations.validate(request))
        return request
    }

    /** Call at review and again immediately before sending, with the original scanned QR. */
    fun validateOriginal(request: ServiceRequest, qr: QrPayment, today: LocalDate = LocalDate.now()): List<OperationValidationError> {
        if (request.operationId != "service.cash.extra") return listOf(OperationValidationError("operation", "Operación incompatible con Caja Extra"))
        return try {
            val currency = CurrencyContract.BANK.supported.firstOrNull { CurrencyContract.BANK.code(it) == request.value("amountCurrency") }
                ?: throw IllegalArgumentException("Moneda no admitida por Caja Extra")
            val expected = fromQr(request.identity, request.source, request.currency, qr,
                Money(BigDecimal(request.value("amount")), currency), request.value("description"), request.value("phone"), today)
            val errors = ServiceOperations.validate(request).toMutableList()
            for (key in listOf("transaction", "provider", "auxiliary", "amountCurrency"))
                if (request.value(key) != expected.value(key)) errors += OperationValidationError(key, "Los datos deben coincidir con el QR leído")
            errors
        } catch (e: IllegalArgumentException) {
            listOf(OperationValidationError("qr", e.message ?: "QR de Caja Extra no válido"))
        }
    }
}
