package dev.duardo.neotransfer.core

import java.time.Duration
import java.time.Instant

/** The platform has one active bank. One hour is the original client's local validity window. */
data class BankSession(val bank: Bank, val subscriptionId: Int, val authenticatedAt: Instant) {
    fun isValidFor(bank: Bank, subscriptionId: Int, now: Instant): Boolean =
        this.bank == bank && this.subscriptionId == subscriptionId &&
            now >= authenticatedAt && Duration.between(authenticatedAt, now) < Duration.ofHours(1)
}
