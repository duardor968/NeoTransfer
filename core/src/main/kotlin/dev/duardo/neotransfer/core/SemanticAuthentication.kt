package dev.duardo.neotransfer.core

import java.text.Normalizer

/** Structural compatibility, not a claim that these additional templates were observed on the network. */
internal object SemanticAuthentication {
    private val bank = Regex("Usted se ha autenticado en la plataforma de pagos moviles,\\s*en el Banco " +
        "(Metropolitano|BFI|Financiero Internacional)(?: con la cuenta ([0-9Xx*]{16}))?,\\s*" +
        "puede comenzar a utilizar nuestros servicios de pagos a traves del movil\\.?", RegexOption.IGNORE_CASE)
    private val wallet = Regex("Usted se ha autenticado en la plataforma de pagos moviles,\\s*en (?:el monedero )?MiTransfer,\\s*" +
        "puede comenzar a utilizar nuestros servicios de pagos a traves del movil\\.?", RegexOption.IGNORE_CASE)

    fun parse(text: String): BankMessage? {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        bank.matchEntire(normalized)?.let { match ->
            return BankMessage.Authenticated(if (match.groupValues[1].equals("Metropolitano", true)) Bank.BANMET else Bank.BFI,
                match.groupValues[2].takeIf(String::isNotBlank))
        }
        if (wallet.matches(normalized)) return BankMessage.ProviderAuthenticated(ProviderIdentity(ProviderId.MITRANSFER))
        return null
    }
}
