package dev.duardo.neotransfer.core.fuel

import java.security.MessageDigest

/** Public coupon metadata. Paid value, a reported balance and a refunded amount have different meanings. */
data class FuelCoupon(
    val id: String,
    val serial: String,
    val currency: String,
    val paidAmount: String? = null,
    val balance: String? = null,
    val bankCode: String? = null,
    val bankReference: String,
    val tmReference: String,
    val observedAt: Long,
    val reportedDate: String? = null,
    val balanceObservedAt: Long? = null,
    val sourceEventId: String,
    val subscriptionId: Int?,
    val secretRevision: String?,
    val evidenceEligible: Boolean,
    val ambiguous: Boolean = false,
    val label: String = "",
    val archived: Boolean = false,
) {
    companion object {
        fun idFor(serial: String): String {
            require(serial.matches(Regex("[0-9]{13}")))
            return "fuel:$serial"
        }
    }
}

enum class FuelUpdateKind { PURCHASE, STATUS, REKEY, REFUND, LIST }

/** Provider data has two encrypted components separated by '|'; neither is a bank access PIN. */
class FuelProviderEnvelope(value: CharArray) : AutoCloseable {
    private val value = value.copyOf()
    fun copyForProtection(): CharArray = value.copyOf().also { require(it.isNotEmpty() && it.any { c -> c != '\u0000' }) }
    fun revision(serial: String, bankReference: String, tmReference: String): String {
        require(value.any { it != '\u0000' })
        return fuelFingerprint(listOf(serial, bankReference, tmReference, String(value)))
    }
    override fun close() { value.fill('\u0000') }
    override fun toString() = "FuelProviderEnvelope"
}

class FuelCouponUpdate(val kind: FuelUpdateKind, val coupon: FuelCoupon, val envelope: FuelProviderEnvelope,
                       val creditedAmount: String? = null) : AutoCloseable {
    override fun close() = envelope.close()
    override fun toString() = "FuelCouponUpdate(kind=$kind, serial=${coupon.serial})"
}

class FuelSmsUpdate(val updates: List<FuelCouponUpdate>, val rejectedRows: Int = 0) : AutoCloseable {
    override fun close() { updates.forEach(FuelCouponUpdate::close) }
    override fun toString() = "FuelSmsUpdate(count=${updates.size}, rejectedRows=$rejectedRows)"
}

class ProtectedFuelEnvelope(val couponId: String, val revision: String, val blob: String) {
    override fun toString() = "ProtectedFuelEnvelope(couponId=$couponId)"
}

fun interface FuelEnvelopeProtector {
    /** Runs off the UI thread during ingestion. It must not prompt, use the network or retain the input. */
    fun protect(coupon: FuelCoupon, envelope: CharArray): ProtectedFuelEnvelope
}

class FuelBackupEnvelope(val couponId: String, val revision: String, providerEnvelope: CharArray) : AutoCloseable {
    private val providerEnvelope = providerEnvelope.copyOf()
    fun copyForEncryptedBackup(): CharArray = providerEnvelope.copyOf().also { require(it.any { c -> c != '\u0000' }) }
    override fun close() { providerEnvelope.fill('\u0000') }
    override fun toString() = "FuelBackupEnvelope(couponId=$couponId)"
}

internal fun fuelFingerprint(values: List<String>): String = MessageDigest.getInstance("SHA-256")
    .digest(values.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
