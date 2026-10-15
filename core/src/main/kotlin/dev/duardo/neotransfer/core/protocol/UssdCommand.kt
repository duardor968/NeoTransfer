package dev.duardo.neotransfer.core.protocol

/**
 * How the integration hands a command to the modem.
 * - [REQUEST]: `TelephonyManager.sendUssdRequest`; the reply text is the acknowledgement.
 * - [DIAL]: `ACTION_CALL` with `tel:` (encode `#` as `%23`); used for interactive menus whose reply the app never sees.
 */
enum class UssdTransport { REQUEST, DIAL }

/** One USSD string. Never log [valueForTransport]: authentication and payments embed the banking key. */
class UssdCommand internal constructor(val service: Int?, private val value: String, val transport: UssdTransport) {
    fun valueForTransport(): String = value
    override fun toString(): String = "UssdCommand(service=$service, transport=$transport)"
}

internal const val CLIENT_VERSION = "1260416"

/** Longest string a single USSD request can carry (GSM 03.38, 7-bit). */
internal const val USSD_MAX_LENGTH = 182

/** Transfermóvil's partial mode (service 110), reproduced from the DEX: grouping compares before adding '*'. */
internal fun partialEncodedCommands(service: Int, agency: String, payload: String, sequence: String): List<UssdCommand> {
    require(sequence.matches(Regex("(?:[0-9]|[1-5][0-9])[0-5][0-9]"))) { "Secuencia USSD no válida" }
    require(agency.matches(Regex("0[1-48]"))) { "Agencia no válida" }
    val groups = mutableListOf<String>()
    var current = ""
    for (atom in payload.split('*')) {
        require(atom.isNotEmpty()) { "Parámetro codificado vacío" }
        if (current.isNotEmpty() && atom.length + current.length + 32 > 52) {
            groups += current
            current = ""
        }
        current += "*$atom"
    }
    groups += current
    require(groups.size <= 99) { "La solicitud contiene demasiados paquetes" }
    return groups.mapIndexed { index, group ->
        val part = (index + 1).toString().padStart(2, '0') + groups.size.toString().padStart(2, '0')
        UssdCommand(service, "*444*110*$part*$sequence*$service*$agency$group*$CLIENT_VERSION#", UssdTransport.REQUEST)
    }
}
