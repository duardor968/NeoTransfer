package dev.duardo.neotransfer.core

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Bank QR contract. Input values are preserved; transaction IDs are never shortened. */
data class QrPayment(
    val transactionId: String,
    val provider: String,
    val amount: Money,
    val auxiliary: String,
    val description: String,
    val descriptionHint: String,
    val qrId: String?,
    val validFrom: LocalDate?,
    val validThrough: LocalDate?,
) {
    val editableAmount: Boolean get() = amount.amount.signum() == 0
    val service: Int get() = if (editableAmount || transactionId.contains("ESTATICO")) 31 else 30

    fun validateDate(today: LocalDate) {
        require(validFrom == null || today >= validFrom) { "Este QR aún no está vigente" }
        require(validThrough == null || today <= validThrough) { "Este QR ha caducado" }
    }

    companion object {
        fun parse(fields: Map<String, String>, today: LocalDate = LocalDate.now()): QrPayment {
            fun required(key: String) = fields[key]?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("El QR no contiene $key")
            val id = required("id_transaccion")
            val provider = required("numero_proveedor")
            val number = required("importe")
            require(number.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?"))) { "Importe del QR no válido" }
            val unit = Currency.entries.find { it.name == required("moneda") }
                ?: throw IllegalArgumentException("Moneda del QR no compatible")
            val extra = fields["extra"]?.let { decryptExtra(it, provider, id) }
            val parts = extra?.split(',')
            require(parts == null || parts.size == 4) { "Información adicional del QR no válida" }
            val dates = parts?.get(2).orEmpty()
            require(dates.isEmpty() || dates.matches(Regex("[0-9]{16}"))) { "Vigencia del QR no válida" }
            fun date(value: String): LocalDate? = if (value == "00000000") null else try {
                LocalDate.parse(value, DateTimeFormatter.ofPattern("ddMMuuuu").withResolverStyle(ResolverStyle.STRICT))
            } catch (e: java.time.DateTimeException) { throw IllegalArgumentException("Vigencia del QR no válida", e) }
            val from = if (dates.isEmpty()) null else date(dates.take(8))
            val through = if (dates.isEmpty()) null else date(dates.takeLast(8))
            require(from == null || through == null || from <= through) { "Vigencia del QR no válida" }
            return QrPayment(id, provider, Money(BigDecimal(number), unit),
                fields["version"]?.takeIf(String::isNotEmpty) ?: "1",
                fields["descripcion"].orEmpty(), parts?.get(0).orEmpty(),
                parts?.get(3)?.takeIf(String::isNotEmpty), from, through).also {
                it.validateDate(today)
                // Validate the protocol alphabet before offering a payment action.
                ParameterCodec().encode(listOf(id, provider, it.auxiliary, it.description.ifEmpty { "0000" }, it.qrId ?: "0000"), 0)
            }
        }

        private fun decryptExtra(value: String, provider: String, id: String): String {
            fun hashPrefix(text: String): ByteArray {
                require(text.all { it.code < 128 }) { "Identificador del QR no válido" }
                return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.US_ASCII))
                    .joinToString("") { "%02x".format(it) }.take(16).toByteArray(Charsets.UTF_8)
            }
            try {
                return Cipher.getInstance("AES/CBC/PKCS5Padding").run {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(hashPrefix(provider), "AES"), IvParameterSpec(hashPrefix(id)))
                    String(doFinal(Base64.getDecoder().decode(value.filterNot { it in "\r\n\t " })), Charsets.UTF_8)
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("No se pudo leer la información adicional del QR", e)
            }
        }
    }
}

/** Reproduces the original Android 8+ grouping, including its comparison before adding '*'. */
internal fun partialCommands(service: Int, bank: Bank, payload: String, sequence: String): List<UssdCommand> {
    require(sequence.matches(Regex("(?:[0-9]|[1-5][0-9])[0-5][0-9]")))
    val groups = mutableListOf<String>()
    var group = ""
    for (atom in payload.split('*')) {
        require(atom.isNotEmpty())
        if (group.isNotEmpty() && atom.length + group.length + 32 > 52) {
            groups += group
            group = ""
        }
        group += "*$atom"
    }
    groups += group
    require(groups.size <= 99) { "El QR contiene demasiados datos" }
    return groups.mapIndexed { index, part ->
        val position = (index + 1).toString().padStart(2, '0') + groups.size.toString().padStart(2, '0')
        UssdCommand(service, "*444*110*$position*$sequence*$service*${bank.code}$part*1260416#")
    }
}
