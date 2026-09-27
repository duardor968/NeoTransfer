package dev.duardo.neotransfer.core

/** Active bank service inquiries and BANDEC's contracted-service tab. */
internal object ServiceQueryOperations {
    private val banks = setOf(Bank.BPA, Bank.BANDEC, Bank.BANMET)
    private val serial = contractField("serial", "Número de serie del cupón")
    private val invoice = contractField("invoice", "Identificador de factura")
    private val serviceType = contractChoice("serviceType", "Servicio", "1" to "Telecomunicaciones", "2" to "Electricidad")
    private val billType = contractChoice("serviceType", "Servicio", "telephone" to "Telecomunicaciones", "electricity" to "Electricidad",
        "water.havana" to "Agua de La Habana", "gas" to "Gas", "water.other" to "Agua del resto del país", "water.varadero" to "Agua de Varadero")
    private fun spec(id: String, title: String, service: Int, source: String, fields: List<OperationField>,
                     effect: OperationEffect = OperationEffect.QUERY, providers: Set<Bank> = banks,
                     sourcePolicy: SourcePolicy = SourcePolicy.NONE): OperationSpec = OperationSpec(
        "service.$id", title, if (effect == OperationEffect.QUERY) OperationCategory.QUERIES else if (effect == OperationEffect.SECURITY) OperationCategory.SECURITY else OperationCategory.PAYMENTS,
        providers.map { ProviderIdentity.forBank(it) }.toSet(), service, fields, effect, OperationTransport.ENCODED_USSD,
        evidence = listOf(ContractReference("APK 1.260416 / $source", "Ruta activa, formulario y orden de parámetros")) +
            if (id.startsWith("contracted.")) listOf(ContractReference("Navegación APK 1.260416", "s684VE6Dm0.getItem(3) → Kgii4NH4Ce.onItemClick: 0/1/3 → q9DNC6bnexm; 2 → g7yG4Frdxy"))
            else providers.map { bankNavigationReference(it, "queries") },
        sourcePolicy = sourcePolicy)

    val all: List<OperationSpec> = listOf(
        spec("fuel.movements", "Combustible · Últimas operaciones del cupón", 36, "showConsultaCombustible case0 → iappKl5Vl3:58-61; UceJvUZMUO.validateSerie", listOf(serial)),
        spec("fuel.list", "Combustible · Consultar cupones por fecha", 37, "showConsultaCombustible case1 → y4hizMsUIU:89,119-120", listOf(contractField("date", "Fecha (dd/MM/aaaa)", FieldKind.DATE))),
        spec("fuel.status", "Combustible · Estado del cupón", 38, "showConsultaCombustible case2 → T8uOynS2ba:58-61", listOf(serial)),
        spec("fuel.refresh-key", "Combustible · Actualizar clave del cupón", 39, "showConsultaCombustible case3 → p8fd3aGf9lK:58-61", listOf(serial), OperationEffect.SECURITY),
        spec("fine.contravention.query", "Consultar multa contravencional", 98, "showDialogMultasConsulta → ULFE6hmQU6:153-170", listOf(contractField("fine", "Identificador de multa"), contractField("identity", "Carné de identidad"))),
        spec("fine.traffic.query", "Consultar multa de tránsito", 98, "showDialogMultasConsulta → ULFE6hmQU6:153-170", listOf(contractField("fine", "Identificador de multa"), contractField("license", "Licencia de conducción"))),
        spec("contracted.add", "BANDEC · Agregar servicio contratado", 52, "q9DNC6bnexm:152-178", listOf(serviceType, invoice), OperationEffect.MANAGE, setOf(Bank.BANDEC)),
        spec("contracted.query", "BANDEC · Consultar servicios contratados", 52, "q9DNC6bnexm:152-178", listOf(serviceType), providers = setOf(Bank.BANDEC)),
        spec("contracted.remove", "BANDEC · Eliminar servicio contratado", 52, "q9DNC6bnexm:152-178", listOf(serviceType, invoice), OperationEffect.MANAGE, setOf(Bank.BANDEC)),
        spec("contracted.pay", "BANDEC · Pagar servicios contratados", 52, "g7yG4Frdxy:156-189", listOf(serviceType), OperationEffect.MONEY, setOf(Bank.BANDEC), SourcePolicy.DEFAULT_OR_EXPLICIT),
        spec("query.bpa", "Consultar factura de servicio · BPA", 47, "xoZnbyoSLT:187-208; DEX submitForm0011-0029 conserva aguaHabana11", listOf(billType, invoice), providers = setOf(Bank.BPA)),
        spec("query.bandec", "Consultar factura de servicio · BANDEC", 47, "idsoBKS8L3:225-254; array_servicios_banco_bandec", listOf(billType, invoice), providers = setOf(Bank.BANDEC)),
        spec("query.banmet", "Consultar factura de servicio · BANMET", 47, "hJhf3aumcr:210-246; array_servicios_banco_metro", listOf(billType, invoice), providers = setOf(Bank.BANMET)),
        spec("onat.query.complete", "ONAT · Consultar servicio completo", 56, "idsoBKS8L3:257-264", listOf(contractField("rc05", "RC05")), providers = setOf(Bank.BANDEC)),
        spec("onat.query.fiscal", "ONAT · Consultar vector fiscal", 56, "idsoBKS8L3:266-274; hJhf3aumcr:249-256", listOf(contractField("rc05", "RC05"), contractField("rc04", "RC04A")), providers = setOf(Bank.BANDEC, Bank.BANMET)),
        spec("stamp.query", "Consultar sellos del timbre", 22, "xoZnbyoSLT:297-300; idsoBKS8L3:376-379; hJhf3aumcr:273-276", listOf(contractField("holderIdentity", "Carné de identidad"))),
    )

    fun contains(id: String): Boolean = all.any { it.id == id }

    fun validate(request: ServiceRequest): List<OperationValidationError> {
        val spec = all.first { it.id == request.operationId }
        val errors = contractValidation(spec, request)
        if (spec.fields.any { it.key == "serial" } && request.value("serial").length != 13)
            errors += OperationValidationError("serial", "La serie del cupón debe tener 13 dígitos")
        if (request.operationId.startsWith("service.contracted.") && spec.fields.any { it.key == "invoice" }) {
            val invoice = request.value("invoice")
            val valid = if (request.value("serviceType") == "1") invoice.length == 15 && invoice.startsWith("01") else invoice.length == 13
            if (!valid) errors += OperationValidationError("invoice", "La factura telefónica debe comenzar por 01 y tener 15 dígitos; la eléctrica requiere 13")
        }
        if (spec.service == 47 && request.identity.provider != ProviderId.BANDEC) {
            val value = request.value("invoice")
            val type = request.value("serviceType")
            val valid = when {
                type == "telephone" -> value.length in 14..15 && value.startsWith("01")
                type == "electricity" -> value.length == 13
                request.identity.provider == ProviderId.BANMET && type == "water.havana" -> value.length == 11
                request.identity.provider == ProviderId.BANMET && type == "gas" -> value.length == 12
                else -> value.length >= 8
            }
            if (!valid) errors += OperationValidationError("invoice", "Identificador incompatible con el servicio y banco seleccionados")
        }
        if (spec.service == 56) {
            if (request.value("rc05").length !in 11..16) errors += OperationValidationError("rc05", "El RC05 debe tener entre 11 y 16 dígitos")
            if (spec.fields.any { it.key == "rc04" } && request.value("rc04").length != 5) errors += OperationValidationError("rc04", "El RC04A debe tener 5 dígitos")
        }
        return errors.distinct()
    }

    fun encode(request: ServiceRequest, seed: Int?): UssdCommand {
        requireValidContract(validate(request))
        val spec = all.first { it.id == request.operationId }
        // ULFE6hmQU6 explicitly supplies agency 02 to its partial sender for every bank.
        return contractEncoded(requireNotNull(spec.service), parameters(request), seed,
            partialAgency = if (spec.service == 98) "02" else null)
    }

    fun parameters(r: ServiceRequest): List<String> = when (r.operationId.removePrefix("service.")) {
        "fuel.movements", "fuel.status", "fuel.refresh-key" -> listOf(r.value("serial"))
        "fuel.list" -> listOf(r.value("date"))
        "fine.contravention.query" -> listOf("1", r.value("fine"), r.value("identity"), "0000")
        "fine.traffic.query" -> listOf("2", r.value("fine"), "0000", r.value("license"))
        "contracted.add" -> listOf("1", r.value("serviceType"), r.value("invoice"))
        "contracted.remove" -> listOf("2", r.value("serviceType"), r.value("invoice"))
        "contracted.query" -> listOf("3", r.value("serviceType"), "000")
        "contracted.pay" -> listOf("4", r.value("serviceType"), "000", "0", r.source.wireValue)
        "query.bpa", "query.bandec", "query.banmet" -> listOf(when (r.value("serviceType")) {
            "telephone" -> "1"
            "electricity" -> "2"
            "water.havana" -> if (r.identity.provider == ProviderId.BPA) "11" else "AGUAHABANA"
            "gas" -> when (r.identity.provider) { ProviderId.BPA -> "4"; ProviderId.BANDEC -> "5"; else -> "11" }
            "water.other" -> "AGUAPROVINCIAS"
            "water.varadero" -> "AGUAVARADERO"
            else -> error("Servicio desconocido")
        }, r.value("invoice"))
        "onat.query.complete" -> listOf(r.value("rc05"), "00000")
        "onat.query.fiscal" -> listOf(r.value("rc05"), r.value("rc04"))
        "stamp.query" -> listOf(r.value("holderIdentity"), "1")
        else -> error("Operación desconocida")
    }
}
