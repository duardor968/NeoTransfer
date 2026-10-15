package dev.duardo.neotransfer.core.protocol

import dev.duardo.neotransfer.core.model.Phones
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

/** Cubacel codes from the phone balance: plain USSD, no `444`, no encoding, no SMS result. */
@Serializable
sealed interface CubacelRequest : UssdRequest

enum class CubacelQueryKind(val code: String) {
    BALANCE("*222#"), BONUS("*222*266#"), DATA("*222*328#"), VOICE("*222*869#"), SMS("*222*767#"),
}

/** A Cubacel query; the reply text is the answer. */
@Serializable @SerialName("cubacel.query")
data class CubacelQuery(val kind: CubacelQueryKind) : CubacelRequest {
    override fun commands(secret: CharArray?, at: Instant, seed: Int?) = listOf(UssdCommand(null, kind.code, UssdTransport.REQUEST))
}

/** Transfer phone balance: `*234*1*<móvil>*<clave>*<monto>#`, whole CUP, 4-digit transfer key. */
@Serializable @SerialName("cubacel.transfer")
data class CubacelTransfer(val phone: String, val amount: Int) : CubacelRequest {
    init {
        require(phone.matches(Regex("[0-9]{8}"))) { "El móvil debe tener 8 dígitos" }
        require(amount > 0) { "El importe debe ser un entero mayor que cero" }
    }
    override val secret get() = SecretKind.CUBACEL_PIN
    override val duplicateKey get() = "234|${Phones.normalize(phone)}|$amount"
    override fun commands(secret: CharArray?, at: Instant, seed: Int?): List<UssdCommand> {
        require(secret != null && secret.size == 4 && secret.all { it in '0'..'9' }) { "La clave de transferencia tiene 4 dígitos" }
        return listOf(UssdCommand(234, "*234*1*$phone*${String(secret)}*$amount#", UssdTransport.REQUEST))
    }
}

object Cubacel {
    /**
     * Plans and packages menu `*133#`, or a direct path such as `*133*1*4*4*1#`. Interactive:
     * dial it ([UssdTransport.DIAL]); it is not queued in the ledger.
     */
    fun packages(path: List<Int> = emptyList()): UssdCommand {
        require(path.all { it in 0..99 }) { "Ruta de menú no válida" }
        return UssdCommand(133, (listOf("*133") + path.map(Int::toString)).joinToString("*") + "#", UssdTransport.DIAL)
    }
}
