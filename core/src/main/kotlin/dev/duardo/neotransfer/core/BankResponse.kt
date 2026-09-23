package dev.duardo.neotransfer.core

import java.text.Normalizer
import java.util.Locale

/** A transport acknowledgement does not identify the active bank or confirm a payment. */
enum class BankResponse {
    PROCESSING, ALREADY_AUTHENTICATED, OTHER;

    companion object {
        fun parse(text: String): BankResponse {
            val normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
            return when {
                Regex("su solicitud esta siendo procesada(?:[,.:].*)?").matches(normalized) -> PROCESSING
                Regex("usted ya se encuentra autenticado en el sistema[.!]?").matches(normalized) -> ALREADY_AUTHENTICATED
                else -> OTHER
            }
        }
    }
}
