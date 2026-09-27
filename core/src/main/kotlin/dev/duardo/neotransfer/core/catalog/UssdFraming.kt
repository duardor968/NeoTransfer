package dev.duardo.neotransfer.core

/** Splits an already encoded request once; callers must retain this immutable sequence for the operation. */
fun UssdCommand.commandsForTransport(identity: ProviderIdentity, sequence: String, maxLength: Int = 130): List<UssdCommand> {
    require(maxLength > 0)
    require(identity.provider != ProviderId.BFI && identity.profile !in setOf(ProfileId.CLASSIC_BUSINESS, ProfileId.AGENT)) { "Proveedor o perfil no disponible" }
    val wire = valueForTransport()
    if (wire.length <= maxLength) return listOf(this)
    val prefix = "*444*$service*"
    val suffix = "*1260416#"
    require(wire.startsWith(prefix) && wire.endsWith(suffix)) { "No existe contrato de envío parcial para este transporte" }
    val agency = partialAgency ?: identity.bank?.code ?: when (identity.provider) {
        ProviderId.MITRANSFER -> if (identity.profile == ProfileId.CLASSIC && service !in setOf(30, 31)) "08" else "04"
        else -> throw IllegalArgumentException("El proveedor no admite este envío parcial")
    }
    val payload = partialPayload ?: wire.substring(prefix.length, wire.length - suffix.length)
    return partialEncodedCommands(service, agency, payload, sequence).also { commands ->
        require(commands.all { it.valueForTransport().length <= maxLength }) { "La solicitud no cabe en paquetes sin modificar sus datos" }
    }
}

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
        UssdCommand(service, "*444*110*$part*$sequence*$service*$agency$group*1260416#")
    }
}
