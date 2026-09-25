package dev.duardo.neotransfer.core

/** Registration and administration contracts, kept separate from balance and transfer commands. */
object BankManagementOperations {
    private val banks = listOf(Bank.BPA, Bank.BANDEC, Bank.BANMET)
    private val identity = contractField("identity", "Carné de identidad")
    private val name = contractField("name", "Nombre", FieldKind.TEXT)
    private val card = contractField("card", "Tarjeta", FieldKind.ACCOUNT)
    private val pin = contractField("pin", "Clave actual", FieldKind.SECRET).copy(suppliedByAccess = true)
    private val newPin = contractField("newPin", "Nueva clave", FieldKind.SECRET)
    private val branch = contractField("branch", "Código de sucursal")
    private val municipality = contractField("municipality", "Código de municipio")
    private val matrix = listOf(contractField("coordinate", "Coordenada de matriz (A1–J10)", FieldKind.TEXT).copy(sensitive = true),
        contractField("matrixValue", "Valor de la coordenada", FieldKind.SECRET))
    private fun spec(bank: Bank, id: String, title: String, service: Int, reference: String,
                     fields: List<OperationField> = emptyList(), effect: OperationEffect = OperationEffect.SECURITY,
                     category: OperationCategory = OperationCategory.SECURITY, source: SourcePolicy = SourcePolicy.NONE,
                     currencies: List<Currency> = emptyList(), requiresSession: Boolean = true, direct: Boolean = false) = OperationSpec(
        "${bank.name.lowercase()}.$id", title, category, setOf(ProviderIdentity.forBank(bank)), service, fields, effect,
        if (direct) OperationTransport.DIRECT_USSD else OperationTransport.ENCODED_USSD, requiresSession,
        listOf(ContractReference("APK 1.260416 / $reference", "Formulario y secuencia de parámetros"),
            bankNavigationReference(bank, if (category == OperationCategory.QUERIES) "queries" else "settings")), sourcePolicy = source, currencies = currencies)

    val all: List<OperationSpec> = buildList {
        add(spec(Bank.BPA, "register", "Registrarse en BPA", 49, "IixhA1yamp:84-98", listOf(card, name,
            contractField("surname", "Primer apellido", FieldKind.TEXT), contractField("secondSurname", "Segundo apellido", FieldKind.TEXT), identity), requiresSession = false))
        add(spec(Bank.BANDEC, "register", "Registrarse en BANDEC", 49, "wLHKkaC4Ha:68,90-102", listOf(card, name.copy(label = "Nombre y apellidos"),
            contractField("expiry", "Vencimiento de tarjeta (MMAA)")), requiresSession = false))
        add(spec(Bank.BANMET, "register", "Registrarse en BANMET", 49, "RFBEnXW0Xr:90-115", listOf(contractField("telebankCard", "Tarjeta Telebanca (10 dígitos)"), name,
            contractField("surname", "Primer apellido", FieldKind.TEXT), contractField("secondSurname", "Segundo apellido", FieldKind.TEXT), identity), requiresSession = false))
        for (bank in banks) {
            add(spec(bank, "change-key", "Cambiar clave · ${bank.name}", 69,
                when (bank) { Bank.BPA -> "PQmKwgxZd1:49"; Bank.BANDEC -> "FtAKxvNjrD:51"; Bank.BANMET -> "FLftkSrl5R:60"; Bank.BFI -> error("BFI no está disponible") },
                if (bank in setOf(Bank.BPA, Bank.BANDEC)) listOf(newPin) else listOf(pin, newPin)))
            add(spec(bank, "limits", "Consultar límites · ${bank.name}", 62, "e8WK1Uajbq:51; ikSs6fqL5Q:49; gkzzwVYksz:53",
                effect = OperationEffect.QUERY, category = OperationCategory.QUERIES,
                source = SourcePolicy.DEFAULT_OR_EXPLICIT, currencies = CurrencyContract.BANK.supported))
            add(spec(bank, "change-limits", "Cambiar límites · ${bank.name}", 61, "qmcwKV1fzU:55-66; uWkWt7AEZ5:59-70; xR6zA9CmCE:59-70",
                listOf(contractField("atm", "Límite de cajero"), contractField("pos", "Límite de terminal de venta")),
                OperationEffect.MANAGE, OperationCategory.ACCOUNTS, SourcePolicy.DEFAULT_OR_EXPLICIT, CurrencyContract.BANK.supported))
            add(spec(bank, "open-account", "Apertura de cuenta MLC · ${bank.name}", 76, "T7bvG7kpeae:120; KsDQL9cTTl:120; kD9wg15jwt:88",
                if (bank in setOf(Bank.BPA, Bank.BANDEC)) listOf(branch) else listOf(municipality, branch),
                OperationEffect.MANAGE, OperationCategory.ACCOUNTS))
            add(spec(bank, "exchange-rate", "Consultar tipo de cambio · ${bank.name}", 85,
                "kmO9Md6JHr:358-375; R7PmVxXLJZC:310; PEi17BvMam:407", effect = OperationEffect.QUERY, category = OperationCategory.QUERIES, direct = true))
            add(spec(bank, "postal-query", "Consultar giros pendientes · ${bank.name}", 65,
                "XvOK7VM4qL:53; gsthr0APCT:53; jyBbMyVMOV:53", listOf(identity), OperationEffect.QUERY, OperationCategory.QUERIES))
        }
        add(spec(Bank.BPA, "all-accounts", "Consultar todas las cuentas · BPA", 58,
            "kmO9Md6JHr:507-524", effect = OperationEffect.QUERY, category = OperationCategory.QUERIES, direct = true))
        for (bank in listOf(Bank.BPA, Bank.BANMET)) add(spec(bank, "card-information", "Información de tarjeta Red · ${bank.name}", 78,
            "kmO9Md6JHr:291-308; PEi17BvMam:377", effect = OperationEffect.QUERY, category = OperationCategory.QUERIES, direct = true))
        add(spec(Bank.BPA, "loan-query", "Consultar crédito BPA", 72, "kmO9Md6JHr:388-405", effect = OperationEffect.QUERY, category = OperationCategory.QUERIES, direct = true))
        add(spec(Bank.BANMET, "loan-query", "Consultar créditos BANMET", 72, "PEi17BvMam:530-547", effect = OperationEffect.QUERY, category = OperationCategory.QUERIES, direct = true))
        add(spec(Bank.BANMET, "locate-transfers", "Localizar transferencias pendientes BANMET", 73, "PEi17BvMam:427-444", effect = OperationEffect.QUERY, category = OperationCategory.QUERIES, direct = true))
        add(spec(Bank.BANDEC, "card-information", "Información de tarjeta Red · BANDEC", 78, "R7PmVxXLJZC case8 → HgJHxigegS:61-73", listOf(card), OperationEffect.QUERY, OperationCategory.QUERIES))
        add(spec(Bank.BANMET, "update-account", "Actualizar cuenta BANMET", 53, "RC71RyOpsk case5 → yvWHEB2qa4:45-54", listOf(
            contractField("holderIdentity", "Carné de identidad"), contractField("telebankCard", "Tarjeta Telebanca (10 dígitos)"))))
        add(spec(Bank.BPA, "standard-account", "Obtener cuenta estándar BPA", 24, "kmO9Md6JHr:86-91; MAvaCEUqwa:47",
            effect = OperationEffect.QUERY, category = OperationCategory.QUERIES, source = SourcePolicy.EXPLICIT_ONLY))
        add(spec(Bank.BPA, "remove-registration", "Eliminar registro BPA", 68, "f5AFyaj0rtx:334-351", direct = true))
        add(spec(Bank.BANDEC, "remove-registration", "Eliminar registro BANDEC", 68, "g9VybPHHUE:62-66", listOf(pin)))
        add(spec(Bank.BANMET, "remove-registration", "Eliminar registro BANMET", 68, "F5QGj1YaNB:54-59", listOf(identity, pin,
            contractField("telebankCard", "Tarjeta Telebanca (10 dígitos)"))))
        add(spec(Bank.BANDEC, "change-multibank-pin", "Cambiar PIN Multibanca", 75, "RRLfqlrfwP:53", listOf(
            contractField("multibankPin", "PIN actual de Multibanca", FieldKind.SECRET), newPin)))
        add(spec(Bank.BPA, "digital-pin", "Generar PIN no impreso", 79, "uLySWsCVTv:53", source = SourcePolicy.DEFAULT_OR_EXPLICIT,
            currencies = CurrencyContract.BANK.supported))
        add(spec(Bank.BPA, "recover-pin", "Recuperar PIN de tarjeta", 83, "Yhi9DhKkMz:53", source = SourcePolicy.DEFAULT_OR_EXPLICIT,
            currencies = CurrencyContract.BANK.supported))
        add(spec(Bank.BANDEC, "digital-pin", "Generar PIN de tarjeta", 79, "RYCvQK4nkF:82-88", listOf(card) + matrix + listOf(
            contractField("coordinate2", "Segunda coordenada de matriz (A1–J10)", FieldKind.TEXT).copy(sensitive = true),
            contractField("matrixValue2", "Valor de la segunda coordenada", FieldKind.SECRET))))
        for (bank in listOf(Bank.BANMET)) {
            add(spec(bank, "digital-pin", "Generar PIN de tarjeta · ${bank.name}", 79, "dYe9oOuW7h:80-86", listOf(card) + matrix))
            add(spec(bank, "associate-account", "Asociar cuenta · ${bank.name}", 60, "RC71RyOpsk case4 → uurdBwh7ub:87", listOf(card) + matrix))
            add(spec(bank, "payment-priority", "Modificar prelación de pago · ${bank.name}", 27, "TFU20KJsT9:65",
                listOf(ProviderOptions.priorityField), OperationEffect.MANAGE, OperationCategory.ACCOUNTS, SourcePolicy.EXPLICIT_ONLY))
            add(spec(bank, "payment-priority-query", "Consultar prelación de pago · ${bank.name}", 28, "m3eQg75J6k0:43",
                effect = OperationEffect.QUERY, category = OperationCategory.QUERIES, source = SourcePolicy.EXPLICIT_ONLY))
            add(spec(bank, "standard-account", "Obtener cuenta estándar · ${bank.name}", 24, "MAvaCEUqwa:47",
                effect = OperationEffect.QUERY, category = OperationCategory.QUERIES, source = SourcePolicy.EXPLICIT_ONLY))
        }
        add(spec(Bank.BPA, "reprint-card", "Reimprimir tarjeta BPA", 74, "kWnV7nzNWl:53", effect = OperationEffect.MONEY,
            category = OperationCategory.ACCOUNTS, source = SourcePolicy.DEFAULT_OR_EXPLICIT, currencies = CurrencyContract.BANK.supported))
        add(spec(Bank.BANDEC, "reprint-card", "Reimprimir tarjeta BANDEC", 74, "vVF87n7MnK:168", listOf(branch,
            contractChoice("reason", "Motivo", "1" to "Extravío", "2" to "Deterioro", "3" to "Olvido de PIN")),
            OperationEffect.MONEY, OperationCategory.ACCOUNTS, SourcePolicy.EXPLICIT_ONLY))
        for (bank in listOf(Bank.BANMET)) add(spec(bank, "reprint-card", "Reimprimir tarjeta · ${bank.name}", 74,
            "tYCC0hiRcm:100", listOf(contractField("commissionAccount", "Cuenta para el cobro de comisión", FieldKind.ACCOUNT), municipality, branch),
            OperationEffect.MONEY, OperationCategory.ACCOUNTS, SourcePolicy.EXPLICIT_ONLY))
        for (bank in listOf(Bank.BANDEC, Bank.BANMET)) add(spec(bank, "statement", "Estado de cuenta por correo · ${bank.name}", 26,
            "VEBwCeuUli:67; aCwLXhT3MF:67 (comisión)", listOf(contractField("email", "Correo electrónico", FieldKind.EMAIL),
                contractField("from", "Desde (dd/MM/aaaa)", FieldKind.DATE), contractField("to", "Hasta (dd/MM/aaaa)", FieldKind.DATE)),
            OperationEffect.MONEY, OperationCategory.QUERIES, SourcePolicy.EXPLICIT_ONLY))
        for (bank in listOf(Bank.BPA, Bank.BANDEC, Bank.BANMET)) add(spec(bank, "fiscal-account", "Apertura de cuenta fiscal · ${bank.name}", 5,
            "EyKrF4UJ9B:164", listOf(identity, contractField("municipality", "Código DPA del municipio"), branch),
            OperationEffect.MANAGE, OperationCategory.ACCOUNTS))
    }

    fun validate(request: ServiceRequest): List<OperationValidationError> {
        val spec = all.find { it.id == request.operationId } ?: return listOf(OperationValidationError("operation", "Operación desconocida"))
        val errors = contractValidation(spec, request)
        errors += CatalogValidation.validate(request)
        val bank = request.identity.bank
        val action = request.operationId.substringAfter('.')
        fun invalid(key: String, test: Boolean, message: String) { if (test) errors += OperationValidationError(key, message) }
        for (field in spec.fields) {
            val value = request.value(field.key)
            when (field.key) {
                "card" -> invalid(field.key, value.length != 16, "La tarjeta debe tener 16 dígitos")
                "telebankCard" -> invalid(field.key, value.length != 10 || !value.startsWith("95"), "La tarjeta Telebanca debe tener 10 dígitos y comenzar por 95")
                "holderIdentity" -> invalid(field.key, value.length !in 1..11, "El carné debe tener hasta 11 dígitos")
                "expiry" -> invalid(field.key, !value.matches(Regex("(0[1-9]|1[0-2])[0-9]{2}")), "Vencimiento no válido: MMAA")
                "pin" -> if (value.isNotEmpty()) invalid(field.key, value.length != bank?.pinLength, "Longitud de clave no válida")
                "newPin" -> invalid(field.key, value.length != if (action == "change-multibank-pin") 4 else bank?.pinLength, "Longitud de nueva clave no válida")
                "multibankPin" -> invalid(field.key, value.length != 4, "El PIN debe tener 4 dígitos")
                "coordinate", "coordinate2" -> invalid(field.key, !value.matches(Regex("[A-J]([1-9]|10)")), "Coordenada no válida: A1–J10")
                "matrixValue", "matrixValue2" -> invalid(field.key, value.length != 2, "El valor debe tener 2 dígitos")
                "atm", "pos" -> invalid(field.key, value.toIntOrNull()?.let { it > 0 } != true, "El límite debe ser un entero positivo")
                "email" -> invalid(field.key, !value.matches(Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")), "Correo no válido")
            }
        }
        if (action == "change-limits") invalid("pos", request.value("atm").toLongOrNull()?.let { atm ->
            request.value("pos").toLongOrNull()?.let { pos -> atm + pos > Int.MAX_VALUE }
        } == true, "La suma de los límites es demasiado grande")
        if (action == "statement" && validContractDate(request.value("from")) && validContractDate(request.value("to"))) {
            val format = java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu")
            invalid("to", java.time.LocalDate.parse(request.value("to"), format).isBefore(java.time.LocalDate.parse(request.value("from"), format)), "La fecha final no puede preceder a la inicial")
        }
        return errors.distinct()
    }

    fun encode(request: ServiceRequest, seed: Int? = null): UssdCommand {
        requireValidContract(validate(request))
        val spec = all.first { it.id == request.operationId }
        for (field in spec.fields.filter { it.suppliedByAccess }) require(request.value(field.key).length == request.identity.bank?.pinLength) { "Falta la clave del acceso autorizado" }
        if (spec.transport == OperationTransport.DIRECT_USSD) return UssdCommand(requireNotNull(spec.service),
            if (request.operationId == "bpa.remove-registration") "*444*68*01#" else "*444*${spec.service}#")
        return contractEncoded(requireNotNull(spec.service), parameters(request), seed)
    }

    internal fun parameters(r: ServiceRequest): List<String> {
        fun v(key: String) = r.value(key)
        val bank = requireNotNull(r.identity.bank)
        require(bank in banks) { "Banco no disponible" }
        val source = r.source.wireValue
        val action = r.operationId.substringAfter('.')
        val debit = if (r.source is SourceSelector.Explicit || bank == Bank.BANDEC) "0" else r.currency?.let { CurrencyContract.BANK.code(it) } ?: "0"
        return when (action) {
            "register" -> when (bank) {
                Bank.BPA -> listOf("01", v("card"), v("name"), v("surname"), v("secondSurname"), v("identity"))
                Bank.BANDEC -> listOf("02", v("card"), v("name"), v("expiry"))
                Bank.BANMET -> listOf("03", v("telebankCard"), v("name"), v("surname"), v("secondSurname"), v("identity"))
                Bank.BFI -> error("BFI no está disponible")
            }
            "change-key" -> if (bank in setOf(Bank.BPA, Bank.BANDEC)) listOf(v("newPin")) else listOf(bank.code, v("pin"), v("newPin"))
            "remove-registration" -> when (bank) {
                Bank.BPA -> listOf("01")
                Bank.BANDEC -> listOf("02", v("pin"))
                Bank.BANMET -> listOf("03", v("identity"), v("pin"), v("telebankCard"))
                Bank.BFI -> error("BFI no está disponible")
            }
            "limits" -> listOf(debit, source)
            "change-limits" -> listOf(v("atm"), v("pos"), (v("atm").toInt() + v("pos").toInt()).toString(), if (bank == Bank.BANDEC) "1" else debit, source)
            "change-multibank-pin" -> listOf(v("multibankPin"), v("newPin"), v("newPin"))
            "digital-pin" -> when (bank) {
                Bank.BPA -> listOf(debit, source)
                Bank.BANDEC -> listOf(v("card"), v("coordinate"), v("matrixValue"), v("coordinate2"), v("matrixValue2"))
                else -> listOf(v("card"), v("coordinate"), v("matrixValue"))
            }
            "recover-pin" -> listOf(debit, source)
            "associate-account" -> listOf(v("card"), v("coordinate"), v("matrixValue"))
            "update-account" -> listOf(v("holderIdentity"), v("telebankCard"))
            "card-information" -> listOf(v("card"))
            "reprint-card" -> when (bank) {
                Bank.BPA -> listOf(debit, source)
                Bank.BANDEC -> listOf(v("branch"), source, v("reason"))
                else -> listOf(debit, source, v("commissionAccount"), v("municipality"), v("branch"))
            }
            "payment-priority-query", "standard-account" -> listOf(source)
            "postal-query" -> listOf(v("identity"))
            "open-account" -> if (bank in setOf(Bank.BPA, Bank.BANDEC)) listOf(v("branch")) else listOf(v("municipality"), v("branch"))
            "payment-priority" -> listOf(v("priority"), source)
            "statement" -> listOf(v("email"), v("from").replace('/', '-'), v("to").replace('/', '-'), source)
            "fiscal-account" -> listOf(v("identity"), v("municipality"), v("branch"))
            else -> error("Operación desconocida")
        }
    }
}
