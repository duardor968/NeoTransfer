package dev.duardo.neotransfer.core.qr

import dev.duardo.neotransfer.core.model.Currency
import dev.duardo.neotransfer.core.model.Money
import dev.duardo.neotransfer.core.protocol.ParameterCodec
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A Transfermóvil payment QR (services 30/31). Values are kept as read; the transaction id is
 * only shortened when the wire is built, exactly where Transfermóvil shortens it.
 */
@Serializable
data class QrPayment(
    val transactionId: String,
    val provider: String,
    /** Zero means the payer types the amount (static QR). */
    val amount: Money,
    val auxiliary: String = "1",
    /** Fixed description; empty when the payer may write one. */
    val description: String = "",
    val title: String? = null,
    /** Present when the encrypted `extra` carries a QR id; the payment then also sends a phone and this id. */
    val qrId: String? = null,
    @Serializable(LocalDateText::class) val validFrom: LocalDate? = null,
    @Serializable(LocalDateText::class) val validThrough: LocalDate? = null,
) {
    val editableAmount: Boolean get() = amount.amount.signum() == 0
    val service: Int get() = if (editableAmount || transactionId.contains("ESTATICO")) 31 else 30

    fun validateDate(today: LocalDate) {
        require(validFrom == null || today >= validFrom) { "Este QR aún no está vigente" }
        require(validThrough == null || today <= validThrough) { "Este QR ha caducado" }
    }

    internal companion object {
        /** Builds a payment from the field names shared by JSON, links and plain text. */
        fun fromFields(fields: Map<String, String>, today: LocalDate): QrPayment {
            val id = fields["id_transaccion"]?.trim()?.takeIf(String::isNotEmpty) ?: throw IllegalArgumentException("El QR no tiene identificador de pago")
            val provider = fields["numero_proveedor"]?.trim()?.takeIf(String::isNotEmpty) ?: throw IllegalArgumentException("El QR no tiene proveedor")
            val currency = currency(fields["moneda"] ?: "CUP")
            val amount = Money(amount(fields["importe"] ?: "0"), currency)
            var payment = QrPayment(
                transactionId = id, provider = provider, amount = amount,
                auxiliary = fields["version"]?.trim()?.takeIf(String::isNotEmpty) ?: "1",
                description = fields["descripcion"]?.trim().orEmpty(),
                title = fields["titulo"]?.trim()?.takeIf(String::isNotEmpty)?.let { if (it.length < 40) it else it.take(19) },
            )
            fields["extra"]?.let { extra -> decryptExtra(extra, provider, id)?.let { payment = payment.withExtra(it) } }
            payment.validateDate(today)
            // Reject before review anything the protocol alphabet cannot carry.
            ParameterCodec().encode(listOfNotNull(id, provider, payment.auxiliary, payment.description.ifEmpty { "0000" }, payment.qrId), 0)
            return payment
        }

        /** Plain number, comma decimal or scientific notation; at most two decimals. */
        fun amount(raw: String): BigDecimal {
            val text = raw.trim().replace(',', '.')
            val value = text.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 } ?: throw IllegalArgumentException("Importe del QR no válido")
            val plain = if (text.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?"))) value
                else value.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
            require(plain.scale() <= 2) { "Importe del QR no válido" }
            return plain
        }

        /** Same precedence as Transfermóvil: CUP/1, then CUC/2, then USD/3. */
        fun currency(raw: String): Currency {
            val value = raw.uppercase()
            return when {
                "CUP" in value || "1" in value -> Currency.CUP
                "CUC" in value || "2" in value -> throw IllegalArgumentException("Moneda del QR no disponible")
                "USD" in value || "3" in value -> Currency.USD
                else -> throw IllegalArgumentException("Moneda del QR no disponible")
            }
        }

        /**
         * `extra` = AES(`descHint,regex,dates,idQR`). Like Transfermóvil, an extra that cannot be read
         * or has fewer than four fields is ignored; readable validity dates are enforced.
         */
        private fun QrPayment.withExtra(parts: List<String>): QrPayment {
            if (parts.size < 4) return this
            val dates = parts[2]
            val (from, through) = if (dates.length == 16) date(dates.take(8)) to date(dates.takeLast(8)) else null to null
            require(from == null || through == null || from <= through) { "Vigencia del QR no válida" }
            return copy(qrId = parts[3].trim().takeIf(String::isNotEmpty), validFrom = from, validThrough = through)
        }

        private fun date(value: String): LocalDate? = if (value == "00000000") null else try {
            LocalDate.parse(value, DateTimeFormatter.ofPattern("ddMMuuuu").withResolverStyle(ResolverStyle.STRICT))
        } catch (e: java.time.DateTimeException) {
            throw IllegalArgumentException("Vigencia del QR no válida", e)
        }

        private fun decryptExtra(value: String, provider: String, id: String): List<String>? = try {
            fun hashPrefix(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.take(16).toByteArray(Charsets.UTF_8)
            Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(hashPrefix(provider), "AES"), IvParameterSpec(hashPrefix(id)))
                String(doFinal(Base64.getDecoder().decode(value.filterNot { it in "\r\n\t " })), Charsets.UTF_8).split(',')
            }
        } catch (_: Exception) {
            null
        }
    }
}

internal object LocalDateText : KSerializer<LocalDate> {
    override val descriptor = PrimitiveSerialDescriptor("LocalDate", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: LocalDate) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): LocalDate = LocalDate.parse(decoder.decodeString())
}
