package dev.duardo.neotransfer.core

import java.time.Duration
import java.time.Instant

/** The platform has one active bank. One hour is the original client's local validity window. */
data class BankSession(val bank: Bank, val subscriptionId: Int, val authenticatedAt: Instant) {
    fun isValidFor(bank: Bank, subscriptionId: Int, now: Instant): Boolean =
        this.bank == bank && this.subscriptionId == subscriptionId &&
            now >= authenticatedAt && Duration.between(authenticatedAt, now) < Duration.ofHours(1)
}

/**
 * Local reuse window for a network authentication receipt, scoped to profile, registration and SIM.
 * The original client's one-hour policy is not proof of the server's remaining session lifetime.
 */
data class ProviderSession(
    val identity: ProviderIdentity,
    val registrationId: String,
    val subscriptionId: Int,
    val authenticatedAt: Instant,
) {
    fun isValidFor(identity: ProviderIdentity, registrationId: String, subscriptionId: Int, now: Instant): Boolean =
        this.identity == identity && this.registrationId == registrationId && this.subscriptionId == subscriptionId &&
            now >= authenticatedAt && Duration.between(authenticatedAt, now) < Duration.ofHours(1)
}
