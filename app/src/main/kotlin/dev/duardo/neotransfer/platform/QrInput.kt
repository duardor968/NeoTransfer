package dev.duardo.neotransfer.platform

import android.net.Uri
import dev.duardo.neotransfer.core.QrPayment
import org.json.JSONObject

sealed interface QrInput {
    data class Card(val card: String, val phone: String?) : QrInput
    data class Payment(val value: QrPayment) : QrInput

    companion object {
        fun parse(text: String): QrInput {
            require(text.length <= 16_384) { "El QR contiene demasiados datos" }
            val value = text.trim()
            if (value.startsWith("TRANSFERMOVIL_ETECSA,TRANSFERENCIA,")) {
                val fields = value.split(',')
                require(fields.size == 5 && fields[4].isEmpty() && fields[2].matches(Regex("[0-9]{16}"))) { "QR de tarjeta no válido" }
                val phone = fields[3].let { if (it.length == 10 && it.startsWith("53")) it.drop(2) else it }
                require(phone.isEmpty() || phone.matches(Regex("[0-9]{8}"))) { "Móvil del QR no válido" }
                return Card(fields[2], phone.takeIf(String::isNotEmpty))
            }
            val map = if (value.startsWith("{")) {
                val json = JSONObject(value)
                listOf("id_transaccion", "numero_proveedor", "importe", "moneda", "version", "descripcion", "extra")
                    .filter { json.has(it) && !json.isNull(it) }.associateWith { key ->
                    require(json.get(key) is String || json.get(key) is Number) { "Campo del QR no válido" }
                    json.get(key).toString()
                }
            } else {
                val uri = Uri.parse(value)
                require((uri.scheme == "transfermovil" && uri.host == "tm_compra_en_linea" && uri.path == "/action") ||
                    (uri.scheme == "https" && uri.host == "transfermovil.app" && uri.path == "/action")) { "QR no compatible con Transfermóvil" }
                uri.queryParameterNames.associateWith { key ->
                    require(uri.getQueryParameters(key).size == 1) { "El enlace contiene campos duplicados" }
                    requireNotNull(uri.getQueryParameter(key))
                }.toMutableMap().apply { this["importe"] = get("importe").orEmpty().replace(',', '.') }
            }
            return Payment(QrPayment.parse(map))
        }
    }
}
