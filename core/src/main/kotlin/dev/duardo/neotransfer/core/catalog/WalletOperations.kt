package dev.duardo.neotransfer.core

import dev.duardo.neotransfer.core.miturno.MiTurnoContracts

/** MiTransfer uses USD=1/CUP=2. These codes must never be replaced by bank currency codes. */
object WalletOperations {
    private val personal = setOf(ProviderIdentity(ProviderId.MITRANSFER))
    private val classic = setOf(ProviderIdentity(ProviderId.MITRANSFER, ProfileId.CLASSIC))
    private val amount = contractField("amount", "Importe", FieldKind.AMOUNT)
    private val mobile = contractField("mobile", "Móvil", FieldKind.PHONE)
    private val phone = contractField("phone", "Móvil de confirmación", FieldKind.PHONE, false)
    private val identity = contractField("identity", "Carné de identidad")
    private val holderIdentity = identity.copy(label = "Carné de identidad o pasaporte", kind = FieldKind.TEXT)
    private val pin = contractField("pin", "PIN", FieldKind.SECRET)
    private val accessPin = pin.copy(label = "PIN del monedero MiTransfer", suppliedByAccess = true)
    private val date = contractField("date", "Fecha (dd/MM/aaaa)", FieldKind.DATE)
    private val period = contractField("period", "Período (mes/año)", FieldKind.TEXT)
    private val invoice = contractField("invoice", "Identificador de factura")
    private val account = contractField("account", "Cuenta del servicio", FieldKind.ACCOUNT)
    private val serial = contractField("serial", "Número de serie")
    private val email = contractField("email", "Correo electrónico", FieldKind.EMAIL)
    private val planFields = listOf(mobile, ProviderOptions.planField)
    private val nautaFields = listOf(contractField("username", "Usuario Nauta sin dominio", FieldKind.TEXT),
        contractChoice("accountType", "Tipo de cuenta", "1" to "Navegación internacional", "2" to "Navegación nacional"), amount, phone)
    private fun flag(key: String, label: String) = contractChoice(key, label, "0" to "No", "1" to "Sí").copy(required = false)
    private val sharing = listOf(flag("sharePhone", "Compartir mi móvil"), flag("shareBalance", "Compartir saldo"))
    private fun spec(id: String, title: String, service: Int, source: String, fields: List<OperationField> = emptyList(),
                     category: OperationCategory = OperationCategory.PAYMENTS, effect: OperationEffect = OperationEffect.MONEY,
                     identities: Set<ProviderIdentity> = personal, currencies: List<Currency> = emptyList(),
                     sourcePolicy: SourcePolicy = SourcePolicy.NONE, requiresSession: Boolean = true,
                     transport: OperationTransport = OperationTransport.ENCODED_USSD) = OperationSpec(
        "wallet.$id", title, category, identities, service, fields, effect, transport, requiresSession,
        listOf(ContractReference("APK 1.260416 / $source", "Formulario, selección de bolsa y parámetros de envío directo"), walletNavigationReference(id, category)),
        sourcePolicy = sourcePolicy, currencies = currencies, authenticationIdentity = ProviderIdentity(ProviderId.MITRANSFER))

    val all: List<OperationSpec> = listOf(
        spec("authenticate", "Autenticarse en MiTransfer", 40, "R5fFZkKuVnr:50", listOf(accessPin), OperationCategory.SECURITY, OperationEffect.SECURITY,
            requiresSession = false),
        spec("disconnect", "Desconectar MiTransfer", 70, "H0kVHR7zuu:125", category = OperationCategory.SECURITY, effect = OperationEffect.SECURITY,
            transport = OperationTransport.DIRECT_USSD),
        spec("register", "Registrarse en MiTransfer", 49, "hOQ4ESKEhm.onGetItemClick case1 → eUkRSfpCKs:71; ETECSA FAQ3", listOf(holderIdentity, pin), OperationCategory.SECURITY, OperationEffect.SECURITY, requiresSession = false),
        spec("pin", "Cambiar PIN de MiTransfer", 69, "Cmok7CcIR9:69", listOf(accessPin, contractField("newPin", "Nuevo PIN", FieldKind.SECRET)),
            OperationCategory.SECURITY, OperationEffect.SECURITY),
        spec("remove", "Eliminar registro de MiTransfer", 68, "hOQ4ESKEhm.showDialogEliminarRegistro case0 → Nju5UJgFce:66", listOf(holderIdentity, accessPin), OperationCategory.SECURITY, OperationEffect.SECURITY),
        spec("balance", "Saldo de MiTransfer", 46, "UMVR3qrc3V:223", category = OperationCategory.QUERIES, effect = OperationEffect.QUERY,
            transport = OperationTransport.DIRECT_USSD),
        spec("classic.balance", "Saldo de Clásica", 46, "GvNasrezHU:112", category = OperationCategory.QUERIES, effect = OperationEffect.QUERY,
            identities = classic, sourcePolicy = SourcePolicy.EXPLICIT_ONLY),
        spec("classic.movements", "Últimas operaciones de Clásica", 48, "dI2tctjNbR:134", category = OperationCategory.QUERIES, effect = OperationEffect.QUERY,
            identities = classic, sourcePolicy = SourcePolicy.EXPLICIT_ONLY),
        spec("movements", "Últimas operaciones de MiTransfer", 48, "UMVR3qrc3V.onGetItemClick case2 → dI2tctjNbR:134", category = OperationCategory.QUERIES, effect = OperationEffect.QUERY,
            currencies = listOf(Currency.USD, Currency.CUP)),
        spec("statement", "Estado de cuenta por correo", 26, "qAMfdm9Kls:125-126 (comisión)", listOf(email,
            date.copy(key = "from", label = "Desde (dd/MM/aaaa)"), date.copy(key = "to", label = "Hasta (dd/MM/aaaa)")),
            OperationCategory.QUERIES, currencies = listOf(Currency.USD, Currency.CUP)),
        spec("service.query", "Consultar factura de servicio", 47, "k5fNqiPSlwb:223", listOf(
            contractChoice("serviceType", "Servicio", "1" to "Telecomunicaciones", "2" to "Electricidad", "AGUAHABANA" to "Agua de La Habana", "GASM" to "Gas", "AGUAVARADERO" to "Agua de Varadero"), invoice),
            OperationCategory.QUERIES, OperationEffect.QUERY),
        spec("transfer", "Transferir entre monederos", 45, "LjOlaYSSMH:165", listOf(mobile, amount) + sharing, OperationCategory.TRANSFERS,
            currencies = listOf(Currency.USD, Currency.CUP)),
        spec("bank.transfer", "Transferir a cuenta bancaria", 16, "H0oV6Ot0bS:409", listOf(
            contractChoice("destinationBank", "Banco de destino", "01" to "BPA", "02" to "BANDEC", "03" to "BANMET"),
            contractField("destination", "Tarjeta de destino", FieldKind.ACCOUNT), amount,
            contractChoice("amountCurrency", "Moneda del importe", "CUP" to "CUP", "USD" to "USD"), phone, flag("sharePhone", "Compartir mi móvil")),
            OperationCategory.TRANSFERS, currencies = listOf(Currency.USD, Currency.CUP)),
        spec("classic.transfer", "Transferir desde Clásica", 45, "rR6oImkdEU.showDialogTransferencia → l3GXbXAyUW:295-318 (sesión MiTransfer, sin PIN)", listOf(contractField("destination", "Tarjeta de destino", FieldKind.ACCOUNT),
            amount, phone) + sharing, OperationCategory.TRANSFERS, identities = classic, sourcePolicy = SourcePolicy.EXPLICIT_ONLY),
        spec("classic.associate", "Asociar Clásica", 111, "HTmIvOo9nb:116", listOf(contractField("card", "Número de tarjeta", FieldKind.ACCOUNT),
            contractField("authorization", "Código de autorización").copy(sensitive = true), contractField("terminal", "Terminal")),
            OperationCategory.SECURITY, OperationEffect.SECURITY, identities = personal),
        spec("classic.remove", "Desasociar tarjeta Clásica", 68, "hOQ4ESKEhm.showDialogEliminarRegistro → m44wUnA8d5:116; ETECSA FAQ81 PIN monedero", listOf(holderIdentity, accessPin,
            contractField("card", "Tarjeta Clásica a desasociar", FieldKind.ACCOUNT)), OperationCategory.SECURITY, OperationEffect.SECURITY, identities = personal),
        spec("mobile.cup", "Recarga móvil desde MiTransfer CUP", 54, "zzyNbaqs1U:108-155", listOf(mobile, amount), OperationCategory.RECHARGES, currencies = listOf(Currency.CUP)),
        spec("nauta", "Recarga Nauta", 59, "KtF0PRB8vS:349", nautaFields, OperationCategory.RECHARGES, currencies = listOf(Currency.CUP)),
        spec("nauta.home", "Recarga Nauta Hogar", 84, "KtF0PRB8vS:374", nautaFields, OperationCategory.RECHARGES, currencies = listOf(Currency.CUP)),
        spec("nauta.debt", "Deuda Nauta Hogar", 86, "KtF0PRB8vS:398", nautaFields, currencies = listOf(Currency.CUP)),
        spec("propia", "Recarga Propia", 77, "JrjJL8mtlI:166", listOf(serial, amount), OperationCategory.RECHARGES, currencies = listOf(Currency.CUP)),
        spec("tfa", "Recarga TFA", 108, "b7zSZMAX7cN:128", listOf(mobile, amount), OperationCategory.RECHARGES, currencies = listOf(Currency.CUP)),
        spec("plans", "Compra de planes Cubacel", 6, "WyYHxgzYyW:192-200; ETECSA FAQ69 elige plan después de categoría", planFields, OperationCategory.RECHARGES, currencies = listOf(Currency.CUP), transport = OperationTransport.INTERACTIVE_USSD),
        spec("plans.usd", "Planes Extra USD", 33, "f3Ttlx30sMT:107 mostrar ofertas disponibles", listOf(mobile), OperationCategory.RECHARGES, currencies = listOf(Currency.USD), transport = OperationTransport.INTERACTIVE_USSD),
        spec("nauta.plus", "Nauta Plus · Cuenta existente", 34, "rR6oImkdEU:607-634 → HZgbT8IXPf:251 → FBsQzP3Zfj:144-171 ACTION_CALL", listOf(contractField("username", "Cuenta Nauta internacional sin dominio", FieldKind.TEXT), mobile),
            OperationCategory.RECHARGES, currencies = listOf(Currency.USD), transport = OperationTransport.INTERACTIVE_USSD),
        spec("nauta.plus.new", "Nauta Plus · Sin cuenta", 34, "rR6oImkdEU:607-634 → HZgbT8IXPf:274 → FBsQzP3Zfj:144-171 ACTION_CALL", listOf(mobile), OperationCategory.RECHARGES,
            currencies = listOf(Currency.USD), transport = OperationTransport.INTERACTIVE_USSD),
        spec("cup.recharge", "Recargar monedero CUP", 17, "P8DtkaSW4dR:89", listOf(amount), OperationCategory.RECHARGES),
        spec("withdrawal", "Extracción de efectivo MiTransfer", 95, "f9Reflqt3EZ:216; DEX submitForm 00e9-00ee concatena municipio y bolsa0", listOf(
            ProviderOptions.withdrawalField, amount, contractField("municipality", "Código DPA del municipio", required = false)),
            currencies = listOf(Currency.USD, Currency.CUP)),
        spec("electricity", "Electricidad", 41, "dwdZ5sHcFR:157", listOf(invoice), currencies = listOf(Currency.CUP)),
        spec("telephone", "Telecomunicaciones", 42, "p5B8KozjvpQ:223", listOf(invoice, amount.copy(required = false, label = "Importe parcial (vacío: total)")), currencies = listOf(Currency.CUP)),
        spec("water.havana", "Agua · La Habana", 19, "U1sIOmca96i:224", listOf(account), currencies = listOf(Currency.CUP)),
        spec("water.varadero", "Agua · Varadero", 21, "U1sIOmca96i:224", listOf(account), currencies = listOf(Currency.CUP)),
        spec("gas", "Gas", 67, "XCDkgbw8oZ:179", listOf(account,
            contractChoice("paymentType", "Deuda a pagar", "1" to "Total", "2" to "Mensual")), currencies = listOf(Currency.CUP)),
        spec("ofa.contribution", "Aporte a la Oficina del Historiador", 94, "C6ZYln3x19W:120 / ObligacionesOFA", listOf(identity, contractField("taxpayer", "Número de contribuyente"),
            contractField("taxCode", "Código"), period, amount, ProviderOptions.obligationField), currencies = listOf(Currency.CUP)),
        spec("organization", "Cuota PCC o UJC", 7, "xb7DiohJuX:196", listOf(contractChoice("organization", "Organización", "1" to "PCC", "2" to "UJC"),
            identity, amount.copy(label = "Ingreso declarado en CUP"), phone), currencies = listOf(Currency.CUP)),
        spec("miturno.reserve", "MiTurno · Solicitar turno", 102, "XnVSHGPTkK:345-350", listOf(holderIdentity, contractField("entity", "Código del servicio"),
            contractField("branch", "DPA de la sucursal"), phone), OperationCategory.PROCEDURES, currencies = listOf(Currency.CUP)),
        spec("miturno.change", "MiTurno · Cambiar fecha", 105, "Wwc5crW71D:57-84,163", listOf(holderIdentity, MiTurnoContracts.managementField, date),
            OperationCategory.PROCEDURES, OperationEffect.MANAGE),
        spec("miturno.cancel", "MiTurno · Cancelar solicitud", 106, "QVPgkl8ZGC:156", listOf(holderIdentity, MiTurnoContracts.managementField),
            OperationCategory.PROCEDURES, OperationEffect.MANAGE),
        spec("miturno.query", "MiTurno · Consultar turno", 107, "x12rSjPY6pZ:100", listOf(holderIdentity), OperationCategory.QUERIES, OperationEffect.QUERY),
    )

    fun validate(request: ServiceRequest): List<OperationValidationError> {
        val spec = all.find { it.id == request.operationId } ?: return listOf(OperationValidationError("operation", "Operación desconocida"))
        val errors = contractValidation(spec, request)
        errors += CatalogValidation.validate(request)
        fun invalid(key: String, condition: Boolean, message: String) { if (condition) errors += OperationValidationError(key, message) }
        val id = request.operationId.removePrefix("wallet.")
        if (spec.currencies.size > 1) invalid("currency", request.currency == null, "Selecciona la cuenta CUP o USD")
        if (spec.sourcePolicy == SourcePolicy.EXPLICIT_ONLY) invalid("source", request.source.wireValue.length != 16, "Clásica requiere una tarjeta de 16 dígitos")
        for (field in spec.fields.filter { it.key in setOf("pin", "newPin") })
            if (!field.suppliedByAccess || request.value(field.key).isNotEmpty()) invalid(field.key, request.value(field.key).length != 4, "El PIN debe tener 4 dígitos")
        for (key in listOf("destination", "card")) if (spec.fields.any { it.key == key }) invalid(key, request.value(key).length != 16, "La tarjeta debe tener 16 dígitos")
        if (id == "transfer") invalid("mobile", request.value("mobile").length != 8, "El monedero de destino debe tener 8 dígitos")
        if (id == "withdrawal") invalid("currency", (request.value("entity") == "22001" && request.currency != Currency.CUP) ||
            (request.value("entity") == "22002" && request.currency != Currency.USD), "La moneda no corresponde a la entidad de extracción")
        if (id.contains("nauta") && id !in setOf("nauta.plus", "nauta.plus.new")) invalid("username", !request.value("username").matches(Regex("[A-Za-z0-9_.-]{1,20}")), "Usuario Nauta sin dominio, de hasta 20 caracteres")
        if (id in setOf("nauta.plus", "nauta.plus.new")) {
            invalid("mobile", !request.value("mobile").matches(Regex("[56][0-9]{7}")), "El móvil debe tener 8 dígitos y comenzar por 5 o 6")
            if (id == "nauta.plus") invalid("username", request.value("username").any { it.isWhitespace() || it == '@' } || request.value("username").contains("ERROR"), "Escribe el usuario Nauta sin dominio ni espacios")
        }
        if (id == "electricity") invalid("invoice", request.value("invoice").length != 13, "La factura eléctrica de MiTransfer debe tener 13 dígitos")
        if (id == "telephone") invalid("invoice", request.value("invoice").length !in 14..15, "La factura debe tener 14 o 15 dígitos")
        if (id == "gas") invalid("account", request.value("account").length != 12, "La cuenta de gas debe tener 12 dígitos")
        if (id == "propia") invalid("serial", request.value("serial").length != 12, "La serie Propia debe tener 12 dígitos")
        if (id == "ofa.contribution") invalid("period", !request.value("period").matches(Regex("(0?[1-9]|1[0-2])/[0-9]{4}")), "Período no válido")
        if (request.value("email").isNotEmpty()) invalid("email", !request.value("email").matches(Regex("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")), "Correo no válido")
        if (id == "statement") invalid("email", !request.value("email").lowercase().endsWith(".cu"), "El servicio requiere un correo nacional .cu")
        if (id == "statement" && validContractDate(request.value("from")) && validContractDate(request.value("to"))) {
            val format = java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu")
            invalid("to", java.time.LocalDate.parse(request.value("to"), format).isBefore(java.time.LocalDate.parse(request.value("from"), format)), "La fecha final no puede preceder a la inicial")
        }
        return errors.distinct()
    }

    fun encode(request: ServiceRequest, seed: Int? = null): UssdCommand {
        requireValidContract(validate(request))
        val spec = all.first { it.id == request.operationId }
        for (field in spec.fields.filter { it.suppliedByAccess }) require(request.value(field.key).length == 4) { "Falta el PIN del acceso autorizado" }
        val service = requireNotNull(spec.service)
        if (spec.transport == OperationTransport.DIRECT_USSD) return UssdCommand(service, "*444*$service#")
        return contractEncoded(service, parameters(request), seed)
    }

    internal fun parameters(r: ServiceRequest): List<String> {
        fun v(key: String, empty: String = "") = r.value(key, empty)
        val spec = all.first { it.id == r.operationId }
        val currency = r.currency ?: spec.currencies.singleOrNull()
        val wallet = if (currency == Currency.USD) "1" else "2"
        val id = r.operationId.removePrefix("wallet.")
        return when (id) {
            "authenticate" -> listOf("04", v("pin"))
            "register" -> listOf("04", "0000", v("identity"), v("pin"))
            "pin" -> listOf(v("pin"), v("newPin"))
            "remove" -> listOf("08", v("identity"), v("pin"))
            "classic.balance" -> listOf("1", r.source.wireValue, "1")
            "classic.movements" -> listOf("1", r.source.wireValue, "3")
            "movements" -> listOf("1", requireNotNull(currency).name, wallet)
            "statement" -> listOf(v("email"), v("from").replace('/', '-'), v("to").replace('/', '-'), requireNotNull(currency).name)
            "service.query" -> listOf(v("serviceType"), v("invoice"))
            "transfer" -> listOf(v("mobile"), v("amount"), "0", v("sharePhone", "0"), v("shareBalance", "0"), wallet)
            "bank.transfer" -> listOf(v("destinationBank"), v("destination"), v("amount"), v("amountCurrency"), v("phone", "0000"), v("sharePhone", "0"), wallet)
            "classic.transfer" -> listOf("1", r.source.wireValue, v("destination"), v("amount"), v("sharePhone", "0"), v("phone", "0"), v("shareBalance", "0"), "1")
            "classic.associate" -> listOf("08", v("card"), v("authorization"), v("terminal"))
            "classic.remove" -> listOf("08", v("identity"), v("pin"), v("card"))
            "mobile.cup" -> listOf(v("mobile"), v("amount"), "0", wallet)
            "nauta", "nauta.home", "nauta.debt" ->
                listOf(v("username"), v("accountType"), v("amount"), "0", v("phone", "0000000"), wallet,
                    when { id.endsWith(".home") -> "2"; id.endsWith(".debt") -> "3"; else -> "1" })
            "propia" -> listOf(v("serial"), v("amount"), wallet)
            "tfa" -> listOf(v("mobile"), v("amount"), wallet)
            "plans" -> listOf(v("mobile"), v("planId"), ProviderOptions.cubacelPlans.single { it.id == v("planId") }.version, requireNotNull(currency).name, wallet)
            "plans.usd" -> listOf(v("mobile"), "1")
            "nauta.plus", "nauta.plus.new" -> listOf(v("username", "0000"), v("mobile"), "1")
            "cup.recharge" -> listOf(v("amount"), "CUP")
            "withdrawal" -> listOf(v("entity"), v("amount"), requireNotNull(currency).name, v("municipality", "0") + "0")
            "electricity" -> listOf(v("invoice"), "0", wallet)
            "telephone" -> listOf(v("invoice"), v("amount", "0"), "0", wallet)
            "water.havana", "water.varadero" -> listOf(if (id == "water.havana") "1" else "2", v("account"), "0.0", "1", "0000", wallet)
            "gas" -> listOf(v("account"), "0", "0000", v("paymentType"), wallet)
            "ofa.contribution" -> listOf(v("identity"), v("taxpayer"), v("taxCode"), v("period"), v("amount"), v("obligation"), wallet)
            "organization" -> listOf(v("organization"), v("identity"), v("amount"), "0000", v("phone", "00"), wallet)
            "miturno.reserve" -> listOf("4", v("entity"), v("branch"), v("identity"), v("phone", "0"), "2")
            "miturno.change", "miturno.cancel" -> listOf("4", v("serviceType"), v("identity")).let { if (id == "miturno.change") it + v("date").replace('/', '-') else it }
            "miturno.query" -> listOf("4", v("identity"))
            else -> error("Operación desconocida")
        }
    }
}
