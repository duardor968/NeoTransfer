package dev.duardo.neotransfer.platform

import java.time.Instant
import java.time.temporal.ChronoUnit

/** SCTS describes the SMS service centre timestamp, not when the bank executed an operation. */
fun smsEvidenceTime(receivedAt: Instant, sentAt: Instant?, now: Instant): Instant? =
    sentAt?.takeIf { it > Instant.EPOCH && it <= receivedAt && it <= now && receivedAt <= now }

/** SCTS has second precision. Compare in that precision without granting a wider grace period. */
fun smsEvidenceAfter(receivedAt: Instant, sentAt: Instant?, startedAt: Instant, now: Instant): Boolean =
    smsEvidenceTime(receivedAt, sentAt, now)?.let { it >= startedAt.truncatedTo(ChronoUnit.SECONDS) && receivedAt >= startedAt } == true
