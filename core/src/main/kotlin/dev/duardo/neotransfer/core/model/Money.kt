package dev.duardo.neotransfer.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal
import java.time.Instant

/** Banks in scope. [code] is the agency sent on the wire; [pinLength] is the banking key length. */
enum class Bank(val code: String, val pinLength: Int) { BPA("01", 4), BANDEC("02", 5) }

/** Currencies the green app operates with. CUC is gone on purpose. */
enum class Currency(val wireCode: String) { CUP("1"), USD("3") }

/**
 * An amount as written by the user or the bank. The original scale is kept for the wire;
 * equality and hashing are by value, so `10.5 CUP == 10.50 CUP`.
 */
@Serializable
class Money(@Serializable(BigDecimalText::class) val amount: BigDecimal, val currency: Currency = Currency.CUP) {
    init { require(amount.signum() >= 0 && amount.scale() <= 2) { "Importe no válido" } }

    operator fun plus(other: Money): Money = same(other).let { Money(amount + other.amount, currency) }

    /** Signed difference; a negative result is reported as null rather than a negative balance. */
    fun minusOrNull(other: Money): Money? = same(other).let { (amount - other.amount).takeIf { it.signum() >= 0 }?.let { Money(it, currency) } }

    fun plain(): String = amount.toPlainString()

    private fun same(other: Money) = require(other.currency == currency) { "Monedas distintas" }

    override fun equals(other: Any?): Boolean = other is Money && other.currency == currency && other.amount.compareTo(amount) == 0
    override fun hashCode(): Int = 31 * amount.stripTrailingZeros().hashCode() + currency.hashCode()
    override fun toString(): String = "${amount.toPlainString()} ${currency.name}"

    companion object {
        fun cup(value: String): Money = Money(BigDecimal(value), Currency.CUP)
    }
}

internal object BigDecimalText : KSerializer<BigDecimal> {
    override val descriptor = PrimitiveSerialDescriptor("BigDecimal", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: BigDecimal) = encoder.encodeString(value.toPlainString())
    override fun deserialize(decoder: Decoder): BigDecimal = BigDecimal(decoder.decodeString())
}

internal object InstantMillis : KSerializer<Instant> {
    override val descriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.LONG)
    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeLong(value.toEpochMilli())
    override fun deserialize(decoder: Decoder): Instant = Instant.ofEpochMilli(decoder.decodeLong())
}
