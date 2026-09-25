package dev.duardo.neotransfer.platform

/** Transfermóvil's transfer QR carries the destination card and an optional notification phone. */
object OwnCardQr {
    fun encode(card: String, phone: String = ""): String {
        require(card.matches(Regex("[0-9]{16}"))) { "Introduce los 16 dígitos de la tarjeta" }
        require(phone.isEmpty() || phone.matches(Regex("[0-9]{8}"))) { "El móvil debe tener 8 dígitos" }
        return "TRANSFERMOVIL_ETECSA,TRANSFERENCIA,$card,$phone,"
    }
}
