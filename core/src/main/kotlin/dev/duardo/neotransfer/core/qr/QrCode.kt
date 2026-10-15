package dev.duardo.neotransfer.core.qr

import dev.duardo.neotransfer.core.model.Cards
import dev.duardo.neotransfer.core.model.Phones
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URLDecoder
import java.time.LocalDate

/** What a scanned code, shared text or opened link means for the app. */
sealed interface QrContent {
    /** A card to transfer to, with the phone to notify when the code carries one. */
    data class Card(val number: String, val phone: String?) : QrContent
    data class Payment(val payment: QrPayment) : QrContent
}

object QrCode {
    private const val MARKER = "TRANSFERMOVIL_ETECSA"

    /**
     * Accepts the Transfermóvil payment JSON, the plain `PAGO_EXTERNO` text, the
     * `transfermovil://tm_compra_en_linea/action` and `http(s)://transfermovil.app/action` links,
     * the card code `TRANSFERMOVIL_ETECSA,TRANSFERENCIA,<card>,<phone>,` (case, spaces and commas
     * tolerated) and a bare 16-digit card. Throws [IllegalArgumentException] with a user message otherwise.
     */
    fun parse(text: String, today: LocalDate): QrContent {
        require(text.length <= 16_384) { "El código contiene demasiados datos" }
        val value = text.trim()
        val upper = value.uppercase()
        if (MARKER in upper && "TRANSFERENCIA" in upper) return card(value)
        Cards.normalize(value)?.let { return QrContent.Card(it, null) }
        if (MARKER in value && "PAGO_EXTERNO" in value) return QrContent.Payment(QrPayment.fromFields(plainPayment(value), today))
        if (value.startsWith("{")) return QrContent.Payment(QrPayment.fromFields(json(value), today))
        return QrContent.Payment(QrPayment.fromFields(link(value), today))
    }

    /** Field separators vary between generators (commas, semicolons, spaces); digits are read in order. */
    private fun card(value: String): QrContent.Card {
        val tail = value.substring(value.indexOf("TRANSFERENCIA", ignoreCase = true) + "TRANSFERENCIA".length)
        val card = Regex("(?<![0-9])[0-9](?:[ -]?[0-9]){15}(?![0-9])").find(tail) ?: throw IllegalArgumentException("El código no tiene una tarjeta válida")
        val rest = tail.substring(card.range.last + 1)
        val phone = Regex("\\+?[0-9](?:[ -]?[0-9]){7,9}(?![0-9])").find(rest)?.value?.let {
            Phones.normalize(it) ?: throw IllegalArgumentException("El móvil del código no es válido")
        }
        return QrContent.Card(requireNotNull(Cards.normalize(card.value)), phone)
    }

    /** `id,importe,moneda,proveedor,aux`; Transfermóvil sends the amount as a float. */
    private fun plainPayment(value: String): Map<String, String> {
        val parts = value.split(',').dropLastWhile(String::isEmpty)
        require(parts.size == 5) { "Código de pago no compatible" }
        val amount = parts[1].trim().toFloatOrNull()?.toString() ?: throw IllegalArgumentException("Importe del QR no válido")
        return mapOf("id_transaccion" to parts[0], "importe" to amount, "moneda" to parts[2], "numero_proveedor" to parts[3], "version" to parts[4])
    }

    private fun json(value: String): Map<String, String> {
        val root = try { Json.parseToJsonElement(value) } catch (_: Exception) { throw IllegalArgumentException("Código de pago no compatible") }
        require(root is JsonObject) { "Código de pago no compatible" }
        return root.mapNotNull { (key, element) ->
            when (element) {
                JsonNull -> null
                is JsonPrimitive -> key to element.content
                else -> throw IllegalArgumentException("Código de pago no compatible")
            }
        }.toMap()
    }

    private fun link(value: String): Map<String, String> {
        val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException("Código no compatible") }
        // `authority`, not `host`: java.net.URI has no host for names with '_' (tm_compra_en_linea).
        val supported = (uri.scheme == "transfermovil" && uri.authority == "tm_compra_en_linea" && uri.path == "/action") ||
            (uri.scheme in setOf("http", "https") && uri.authority == "transfermovil.app" && uri.path == "/action")
        require(supported && uri.rawQuery != null) { "Código no compatible" }
        val fields = uri.rawQuery.split('&').filter(String::isNotEmpty).map { pair ->
            val key = URLDecoder.decode(pair.substringBefore('='), Charsets.UTF_8)
            key to URLDecoder.decode(pair.substringAfter('=', ""), Charsets.UTF_8)
        }
        require(fields.map { it.first }.toSet().size == fields.size) { "El enlace repite campos" }
        val map = fields.toMap()
        require(map["numero_proveedor"]?.all { it in '0'..'9' } != false) { "Proveedor del enlace no válido" }
        return map
    }
}

/** The code other people scan to transfer to one of the user's cards; readable by Transfermóvil. */
object CardQr {
    fun encode(card: String, phone: String? = null): String {
        val number = requireNotNull(Cards.normalize(card)) { "La tarjeta debe tener 16 dígitos" }
        val mobile = phone?.let { requireNotNull(Phones.normalize(it)) { "El móvil debe tener 8 dígitos" } }.orEmpty()
        return "TRANSFERMOVIL_ETECSA,TRANSFERENCIA,$number,$mobile,"
    }
}
