package dev.duardo.neotransfer.core

import java.time.Duration
import java.time.Instant

class TransferRequest(
    val bank: Bank,
    val destination: String,
    val amount: Money,
    val sourceAccount: String = "0000",
    val notificationPhone: String? = null,
) {
    init {
        require(destination.matches(Regex("[0-9]{16}"))) { "La tarjeta debe tener 16 dígitos" }
        require(sourceAccount == "0000" || sourceAccount.matches(Regex("[0-9]{16}"))) { "Cuenta de origen no válida" }
        require(notificationPhone == null || notificationPhone.matches(Regex("[0-9]{8}"))) { "El móvil debe tener 8 dígitos" }
        require(amount.amount.signum() > 0) { "El importe debe ser mayor que cero" }
    }

    override fun toString() = "TransferRequest(bank=$bank, currency=${amount.currency})"
}

/** One transfer remains pending until a matching receipt arrives; timeouts never resend money. */
class PendingTransfer(
    val request: TransferRequest,
    val startedAt: Instant,
    private val subscriptionId: Int,
) {
    private var receipt: BankMessage.TransferSent? = null
    private var balanceRefreshRequested = false

    @Synchronized
    fun confirmation(): BankMessage.TransferSent? = receipt

    @Synchronized
    fun accept(message: BankMessage, receivedAt: Instant, smsSubscriptionId: Int?): Boolean {
        if (receipt != null || receivedAt < startedAt || smsSubscriptionId != subscriptionId) return false
        if (message !is BankMessage.TransferSent) return false
        if (message.bank != request.bank || !message.amount.sameValue(request.amount)) return false
        if (!matchesAccount(message.beneficiary, request.destination)) return false
        receipt = message
        return true
    }

    @Synchronized
    fun takeBalanceRefresh(now: Instant, ussdChannelIdle: Boolean): Boolean {
        if (!ussdChannelIdle || balanceRefreshRequested || receipt?.remainingBalance != null) return false
        val due = receipt != null || Duration.between(startedAt, now) >= Duration.ofSeconds(10)
        if (!due) return false
        balanceRefreshRequested = true
        return true
    }

}
