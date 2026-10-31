package dev.duardo.neotransfer.core.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal
import java.time.Instant

/**
 * Who a `40` authenticates with: a [Bank] or [MiTransfer]. The line holds one session at a time among all of them,
 * and each has its own saved key. [code] is the agency on the wire; [pinLength] the length of its key.
 */
sealed interface Agency {
    val code: String
    val pinLength: Int
}

/** Banks in scope. Metropolitano's key is the 4-digit Telebanca card PIN. */
enum class Bank(override val code: String, override val pinLength: Int) : Agency { BPA("01", 4), BANDEC("02", 5), BANMET("03", 4) }

/**
 * Fincimex's MiTransfer wallet, agency `04` with a 4-digit PIN. NeoTransfer uses it only for the Clásica card, never for
 * the wallet's own USD and CUP accounts; bank requests never take it.
 */
data object MiTransfer : Agency {
    override val code = "04"
    override val pinLength = 4
}

/** Currencies the green app operates with. CUC is gone on purpose. */
enum class Currency(val wireCode: String) { CUP("1"), USD("3") }

/**
 * An amount as written by the user or the bank. The original scale is kept for the wire;
 * equality and hashing are by value, so `10.5 CUP == 10.50 CUP`.
 */
@Serializable
class Money(@Serializable(BigDecimalText::class) val amount: BigDecimal, val currency: Currency = Currency.CUP) {
    init { require(amount.signum() >= 0 && amount.scale() <= 2) { "Importe no válido" } }

    fun plain(): String = amount.toPlainString()

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
