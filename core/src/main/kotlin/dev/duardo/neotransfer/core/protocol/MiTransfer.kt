package dev.duardo.neotransfer.core.protocol

import dev.duardo.neotransfer.core.model.Cards
import dev.duardo.neotransfer.core.model.Currency
import dev.duardo.neotransfer.core.model.MiTransfer
import dev.duardo.neotransfer.core.model.Money
import dev.duardo.neotransfer.core.model.Phones
import dev.duardo.neotransfer.core.qr.QrPayment
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.time.Instant

/**
 * MiTransfer requests: its session, the line's registration and the Clásica card, never the wallet's own accounts.
 * Clásica operations name the card in their parameters and start with `08` or `1` where banks put their agency.
 * Keyless ones need MiTransfer's session, which takes the line's one slot like a bank's.
 */
@Serializable
sealed interface MiTransferRequest : UssdRequest {
    override val agency: MiTransfer get() = MiTransfer
    override val needsSession: Boolean get() = true
}

/** Session `40` with MiTransfer: `04*<PIN>`. */
@Serializable @SerialName("mitransfer.authenticate")
data object MiTransferAuthenticate : MiTransferRequest {
    override val secret get() = SecretKind.BANK_PIN
    override val needsSession get() = false
    override fun commands(secret: CharArray?, at: Instant, seed: Int?, newKey: CharArray?) =
        listOf(encoded(40, listOf(MiTransfer.code, pin(MiTransfer, secret)), seed))
}

/**
 * Registers the line in MiTransfer, `49`: `04*0000*<CI o pasaporte>*<PIN>`. The person chooses the PIN, the request's
 * `newKey`. Independent of any bank registration.
 */
@Serializable @SerialName("mitransfer.register")
data class MiTransferRegistration(val identity: String) : MiTransferRequest {
    init { require(isField(identity)) { "Carné de identidad o pasaporte no válido" } }
    override val newKeyLength get() = MiTransfer.pinLength
    override val needsSession get() = false
    override fun commands(secret: CharArray?, at: Instant, seed: Int?, newKey: CharArray?) =
        listOf(encoded(49, listOf(MiTransfer.code, "0000", identity, newPin(MiTransfer, newKey)), seed))
}

/**
 * Associates a Clásica with the wallet, `111`: `08*<tarjeta>*<Aut>*<TML>`. [authorization] (Aut, 6) and [terminal]
 * (TML, 8) come from a receipt of that card at a POS.
 */
@Serializable @SerialName("classic.associate")
data class ClassicAssociation(val card: String, val authorization: String, val terminal: String) : MiTransferRequest {
    init {
        requireClassic(card)
        require(authorization.length == 6 && isField(authorization)) { "El número Autorizo tiene 6 caracteres" }
        require(terminal.length == 8 && isField(terminal)) { "El número de terminal tiene 8 caracteres" }
    }
    override fun commands(secret: CharArray?, at: Instant, seed: Int?, newKey: CharArray?) =
        listOf(encoded(111, listOf(CLASSIC, card, authorization, terminal), seed))
}

/** Balance of a Clásica, `46`: `1*<tarjeta>*1`. */
@Serializable @SerialName("classic.balance")
data class ClassicBalanceQuery(val card: String) : MiTransferRequest {
    init { requireClassic(card) }
    override fun commands(secret: CharArray?, at: Instant, seed: Int?, newKey: CharArray?) = listOf(encoded(46, listOf("1", card, "1"), seed))
}

/** Last operations of a Clásica, `48`: `1*<tarjeta>*3`, as Transfermóvil 1.260416 sends it (1.250620 ended in `1`). */
@Serializable @SerialName("classic.history")
data class ClassicHistoryQuery(val card: String) : MiTransferRequest {
    init { requireClassic(card) }
    override fun commands(secret: CharArray?, at: Instant, seed: Int?, newKey: CharArray?) = listOf(encoded(48, listOf("1", card, "3"), seed))
}

/**
 * Transfer between Clásicas `45`, in USD: `1*<origen>*<destino>*<importe>*<enviarMiMóvil>*<móvil|0>*<omitirSaldo>*1`.
 * As in a bank transfer, the sender's phone stays hidden and the receipt keeps the remaining balance.
 */
@Serializable @SerialName("classic.transfer")
data class ClassicTransfer(val card: String, val destination: String, val amount: Money, val notifyPhone: String? = null) : MiTransferRequest {
    init {
        requireClassic(card)
        require(Cards.isClassic(destination)) { "La tarjeta destino debe ser una Clásica: 16 dígitos que empiezan por 976003" }
        require(amount.currency == Currency.USD && amount.amount.signum() > 0) { "El importe debe ser en USD y mayor que cero" }
        require(notifyPhone == null || Phones.normalize(notifyPhone) == notifyPhone) { "El móvil debe tener 8 dígitos" }
    }
    override val duplicateKey get() = "45|$card|$destination|${amount.amount.stripTrailingZeros().toPlainString()}"
    override fun commands(secret: CharArray?, at: Instant, seed: Int?, newKey: CharArray?) = listOf(encoded(45, listOf(
        "1", card, destination, amount.plain(),
        "0", // do not reveal the sender's phone
        notifyPhone ?: "0",
        "0", // keep the remaining balance in the receipt
        "1", // the wallet's only choice here, "-MIS CUENTAS-"
    ), seed))
}

/**
 * Online or QR payment with a Clásica, `30`/`31`. It is the bank form with `04*<PIN>`, `3` (the wallet's "-MIS CUENTAS-")
 * where banks put the card currency, `0000` as origin and the card at the end. Unlike the banks', the id is never shortened.
 * The special payment (`aux` with `11200301`) leaves the card out in Transfermóvil, so it is refused: the card that pays
 * would not be the one chosen.
 */
@Serializable @SerialName("classic.qr")
data class ClassicQrPay(
    val card: String, val payment: QrPayment, val amount: Money, val description: String = "", val notifyPhone: String? = null,
) : MiTransferRequest {
    init {
        requireClassic(card)
        require(amount.amount.signum() > 0 && amount.currency == payment.amount.currency) { "Importe no válido" }
        require(payment.editableAmount || amount == payment.amount) { "No se puede modificar el importe de este QR" }
        require(payment.description.isEmpty() || description == payment.description) { "No se puede modificar la descripción" }
        require(notifyPhone == null || Phones.normalize(notifyPhone) == notifyPhone) { "El móvil debe tener 8 dígitos" }
        require(!(payment.service == 30 && payment.auxiliary.contains("11200301"))) { "Este pago no se puede hacer con la Clásica" }
    }
    override val secret get() = SecretKind.BANK_PIN
    override val needsSession get() = false
    override val duplicateKey get() =
        "${payment.service}:${payment.provider}|$card|${payment.transactionId}|${amount.amount.stripTrailingZeros().toPlainString()}"

    override fun commands(secret: CharArray?, at: Instant, seed: Int?, newKey: CharArray?): List<UssdCommand> {
        val service = payment.service
        val auxData = payment.qrId != null || notifyPhone != null
        val parameters = buildList {
            addAll(listOf(MiTransfer.code, pin(MiTransfer, secret), payment.transactionId, amount.plain(), amount.currency.wireCode, "3",
                payment.provider, payment.auxiliary))
            if (service == 31) {
                add(description.ifEmpty { "0000" })
                add("0000")
                if (auxData) addAll(listOf(notifyPhone ?: "0000", payment.qrId ?: "0000"))
            } else {
                add("0000")
                add(if (auxData) notifyPhone ?: "0000" else "0")
            }
            add(card)
        }
        val payload = ParameterCodec().encode(parameters, seed ?: SecureRandom().nextInt(100))
        return fitted(service, MiTransfer.code, payload, at)
    }
}

/** Removes a Clásica from the wallet, `68`: `08*<CI>*<PIN>*<tarjeta>`; destructive. Sent with MiTransfer's PIN. */
@Serializable @SerialName("classic.remove")
data class ClassicRemoval(val card: String, val identity: String) : MiTransferRequest {
    init {
        requireClassic(card)
        require(isField(identity)) { "Carné de identidad no válido" }
    }
    override val secret get() = SecretKind.BANK_PIN
    override val needsSession get() = false
    override fun commands(secret: CharArray?, at: Instant, seed: Int?, newKey: CharArray?) =
        listOf(encoded(68, listOf(CLASSIC, identity, pin(MiTransfer, secret), card), seed))
}

/** The Clásica card itself, inside the parameters of its association and removal. */
private const val CLASSIC = "08"

private fun requireClassic(card: String) = require(Cards.isClassic(card)) { "La Clásica tiene 16 dígitos y empieza por 976003" }
