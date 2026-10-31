package dev.duardo.neotransfer.core.protocol

import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Cards
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.time.Instant
import java.util.Random

// The line's services with a bank: registration `49`, all accounts `58`, card association `60`, removal of the
// registration `68` and key change `69`. Each leaves only as the request the person asked for.

/**
 * Registration `49`, before having a key: the bank answers with it ([dev.duardo.neotransfer.core.sms.SmsMessage.Registration]).
 * - BPA: `01*<tarjeta>*<nombre>*<apellido1>*<apellido2>*<CI>`.
 * - BANDEC: `02*<tarjeta>*<nombre y apellidos>*<AAMM>`, the card's expiry as year and month.
 * - Metropolitano: `03*<Telebanca>*<nombre>*<apellido1>*<apellido2>*<CI>`, with the 10-digit Telebanca card.
 */
@Serializable @SerialName("bank.register")
data class BankRegistration(
    override val bank: Bank,
    /** 16 digits; Metropolitano's Telebanca card has 10 and starts with `95`. */
    val card: String,
    /** Given names; in BANDEC, name and surnames together. */
    val name: String,
    val firstSurname: String? = null,
    val secondSurname: String? = null,
    /** Carné de identidad, BPA and Metropolitano. */
    val identity: String? = null,
    /** The card's expiry as `AAMM`, BANDEC only. */
    val expiry: String? = null,
) : BankRequest {
    init {
        if (bank == Bank.BANMET) require(isTelebanca(card)) { "La tarjeta Telebanca tiene 10 dígitos y empieza por 95" }
        else require(Cards.isNumber(card)) { "La tarjeta debe tener 16 dígitos" }
        require(isField(name)) { "Nombre no válido" }
        if (bank == Bank.BANDEC) {
            require(expiry != null && expiry.matches(Regex("[0-9]{2}(?:0[1-9]|1[0-2])"))) { "Vencimiento no válido: año y mes, AAMM" }
            require(firstSurname == null && secondSurname == null && identity == null) { "BANDEC registra con el nombre completo y el vencimiento" }
        } else {
            require(isField(firstSurname) && isField(secondSurname)) { "Apellidos no válidos" }
            require(isField(identity)) { "Carné de identidad no válido" }
            require(expiry == null) { "Solo BANDEC registra con el vencimiento" }
        }
    }
    override val needsSession get() = false
    override fun commands(secret: CharArray?, at: Instant, seed: Int?, newKey: CharArray?) = listOf(encoded(49,
        if (bank == Bank.BANDEC) listOf(bank.code, card, name, expiry!!)
        else listOf(bank.code, card, name, firstSurname!!, secondSurname!!, identity!!), seed))
}

/** All accounts `58`: `*444*58#`, no parameters. The bank answers with a balance SMS. */
@Serializable @SerialName("bank.accounts")
data class AccountsQuery(override val bank: Bank) : BankRequest {
    override fun commands(secret: CharArray?, at: Instant, seed: Int?, newKey: CharArray?) = listOf(plain(58))
}

/**
 * Associates a card with the line, or changes the one it operates, `60`. BPA and BANDEC send the card alone;
 * Metropolitano `<cuenta>*<celda>*<valor>`, where [coordinate] names a cell of its coordinates card and the cell's value
 * is the [SecretKind.COORDINATE].
 */
@Serializable @SerialName("bank.associate")
data class CardAssociation(override val bank: Bank, val card: String, val coordinate: String? = null) : BankRequest {
    init {
        require(Cards.isNumber(card)) { "La tarjeta debe tener 16 dígitos" }
        require((bank == Bank.BANMET) == (coordinate != null)) { "Solo Metropolitano pide una coordenada" }
        require(coordinate == null || coordinate.matches(CELL)) { "Coordenada no válida" }
    }
    override val secret get() = if (bank == Bank.BANMET) SecretKind.COORDINATE else null
    override fun commands(secret: CharArray?, at: Instant, seed: Int?, newKey: CharArray?): List<UssdCommand> {
        if (bank != Bank.BANMET) return listOf(encoded(60, listOf(card), seed))
        require(secret != null && secret.size == 2 && ParameterCodec().accepts(String(secret))) { "El valor de la coordenada tiene 2 caracteres" }
        return listOf(encoded(60, listOf(card, coordinate!!, String(secret)), seed))
    }

    companion object {
        private val CELL = Regex("[A-J](?:[1-9]|10)")

        /** A cell as Transfermóvil picks it: a letter A–J and a number 1–10. */
        fun randomCoordinate(random: Random = SecureRandom()): String = "${'A' + random.nextInt(10)}${random.nextInt(10) + 1}"
    }
}

/**
 * Removes the line's registration with the bank, `68`; destructive. BPA and BANDEC dial `*444*68*01#` or `*444*68*02#`,
 * plain and without key. Metropolitano sends `03*<CI>*<clave>*<Telebanca>`, with the saved key.
 */
@Serializable @SerialName("bank.unregister")
data class RegistrationRemoval(
    override val bank: Bank,
    /** Carné de identidad, Metropolitano only. */
    val identity: String? = null,
    /** Metropolitano's Telebanca card. */
    val card: String? = null,
) : BankRequest {
    init {
        if (bank == Bank.BANMET) {
            require(isField(identity)) { "Carné de identidad no válido" }
            require(card != null && isTelebanca(card)) { "La tarjeta Telebanca tiene 10 dígitos y empieza por 95" }
        } else {
            require(identity == null && card == null) { "Solo Metropolitano pide el carné y la tarjeta" }
        }
    }
    override val secret get() = if (bank == Bank.BANMET) SecretKind.BANK_PIN else null
    override val needsSession get() = false
    override fun commands(secret: CharArray?, at: Instant, seed: Int?, newKey: CharArray?) = listOf(
        if (bank == Bank.BANMET) encoded(68, listOf(bank.code, identity!!, pin(bank, secret), card!!), seed) else plain(68, bank.code),
    )
}

/**
 * Key change `69`; the new key is the request's `newKey`. BPA and BANDEC send it alone, inside a session opened with the
 * current key. Metropolitano sends `03*<clave actual>*<clave nueva>`, with the saved key and no session.
 */
@Serializable @SerialName("bank.pin")
data class PinChange(override val bank: Bank) : BankRequest {
    override val secret get() = if (bank == Bank.BANMET) SecretKind.BANK_PIN else null
    override val newKeyLength get() = bank.pinLength
    override val needsSession get() = bank != Bank.BANMET
    override fun commands(secret: CharArray?, at: Instant, seed: Int?, newKey: CharArray?) = listOf(encoded(69,
        if (bank == Bank.BANMET) listOf(bank.code, pin(bank, secret), newPin(bank, newKey)) else listOf(newPin(bank, newKey)), seed))
}

private fun isTelebanca(value: String) = value.matches(Regex("95[0-9]{8}"))

/** Free text sent as one parameter: names, identity documents. Trimmed, not blank and in the protocol's dictionary. */
internal fun isField(value: String?): Boolean = value != null && value.isNotBlank() && value == value.trim() && ParameterCodec().accepts(value)
