package dev.duardo.neotransfer.core

import java.math.BigDecimal

enum class Bank(val code: String, val pinLength: Int) {
    BPA("01", 4),
    BANDEC("02", 5),
}

enum class Currency { CUP, USD, CUC }

data class Money(val amount: BigDecimal, val currency: Currency) {
    init {
        require(amount.signum() >= 0 && amount.scale() <= 2) { "Importe no válido" }
    }

    fun sameValue(other: Money): Boolean =
        currency == other.currency && amount.compareTo(other.amount) == 0
}

sealed interface BankMessage {
    data class Authenticated(val bank: Bank, val account: String?) : BankMessage

    data class Balance(val bank: Bank, val accounts: List<AccountBalance>) : BankMessage

    data class TransferSent(
        val bank: Bank,
        val beneficiary: String,
        val amount: Money,
        val reference: String,
        val remainingBalance: Money?,
    ) : BankMessage

    data class TransferReceived(
        val account: String,
        val senderPhone: String,
        val amount: Money,
        val reference: String,
    ) : BankMessage

    data class RechargeRejected(val phone: String) : BankMessage

    data class PaymentCompleted(
        val bank: Bank?, val merchant: String?, val amount: Money,
        val reference: String?, val purchaseId: String?, val bankDate: String?,
    ) : BankMessage

    data class RechargeCompleted(
        val bank: Bank?, val phone: String, val amount: Money, val reference: String,
    ) : BankMessage

    data class ServicePaymentCompleted(
        val bank: Bank, val service: String, val account: String?, val nominal: Money,
        val paid: Money?, val reference: String,
    ) : BankMessage

    data object Unrecognized : BankMessage
}

data class AccountBalance(
    val account: String?,
    val ledger: Money?,
    val available: Money,
    val label: String? = null,
)

fun matchesAccount(mask: String, account: String): Boolean =
    mask.length == account.length && mask.count { it in '0'..'9' } >= 8 &&
        mask.zip(account).all { (masked, actual) -> masked == actual || masked in "Xx*" }

fun sourceCurrency(accounts: List<AccountBalance>, source: String): Currency? =
    (if (source == "0000") accounts.singleOrNull() else accounts.filter {
        it.account?.let { account -> matchesAccount(account, source) } == true
    }.singleOrNull())?.available?.currency
