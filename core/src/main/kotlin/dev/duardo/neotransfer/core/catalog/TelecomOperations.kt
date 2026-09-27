package dev.duardo.neotransfer.core

/** Cubacel contracts are plain USSD or ordinary calls; ParameterCodec must not touch them. */
object TelecomOperations {
    private val identity = setOf(ProviderIdentity(ProviderId.CUBACEL))
    private val mobile = contractField("mobile", "Móvil", FieldKind.PHONE)
    private val pin = contractField("pin", "Clave de transferencia", FieldKind.SECRET)
    private fun spec(id: String, title: String, service: Int, fields: List<OperationField> = emptyList(),
                     effect: OperationEffect = OperationEffect.QUERY, dial: Boolean = false) = OperationSpec(
        "cubacel.$id", title, OperationCategory.TELECOM, identity, service, fields, effect,
        if (id == "plans") OperationTransport.INTERACTIVE_USSD else if (dial) OperationTransport.SYSTEM_DIAL else OperationTransport.DIRECT_USSD, requiresSession = false,
        evidence = listOf(ContractReference("APK 1.260416 / ivq3YYEGPZM", "FNRzUmC7E5, LALCrDPvhM, b0kkzvOCEu, C8l2t8799rL, E1lhoLKryWP, GMFMyKMnO2")))

    val all: List<OperationSpec> = listOf(
        spec("balance", "Saldo prepago", 222), spec("postpaid", "Saldo pospago", 111),
        spec("bonus", "Saldo de bonos", 222), spec("data", "Plan de datos", 222),
        spec("voice", "Plan de voz", 222), spec("sms", "Plan de SMS", 222), spec("friends", "Plan Amigos", 222),
        spec("advance", "Adelanto de saldo", 234, effect = OperationEffect.MONEY),
        spec("plans", "Gestión de planes", 133, effect = OperationEffect.MANAGE),
        spec("voucher", "Recargar con bono", 662, listOf(contractField("voucher", "Clave del bono", FieldKind.SECRET)), OperationEffect.MONEY),
        spec("transfer", "Transferir saldo Cubacel", 234, listOf(mobile, pin, contractField("amount", "Importe", FieldKind.AMOUNT)), OperationEffect.MONEY),
        spec("pin", "Cambiar clave de transferencia", 234, listOf(pin,
            contractField("newPin", "Nueva clave", FieldKind.SECRET)), OperationEffect.SECURITY),
        spec("call", "Realizar llamada", 0, listOf(mobile), OperationEffect.MONEY, true),
        spec("call99", "Llamar con *99", 99, listOf(mobile), OperationEffect.MONEY, true),
    )

    fun validate(request: ServiceRequest): List<OperationValidationError> {
        val spec = all.find { it.id == request.operationId } ?: return listOf(OperationValidationError("operation", "Operación desconocida"))
        val errors = contractValidation(spec, request)
        for (field in spec.fields.filter { it.key in setOf("pin", "newPin") })
            if (request.value(field.key).length != 4) errors += OperationValidationError(field.key, "La clave debe tener 4 dígitos")
        return errors
    }

    fun encode(request: ServiceRequest, seed: Int? = null): UssdCommand {
        requireValidContract(validate(request))
        val spec = all.first { it.id == request.operationId }
        val number = when (request.operationId.removePrefix("cubacel.")) {
            "balance" -> "*222#"
            "postpaid" -> "*111#"
            "bonus" -> "*222*266#"
            "data" -> "*222*328#"
            "voice" -> "*222*869#"
            "sms" -> "*222*767#"
            "friends" -> "*222*264#"
            "advance" -> "*234*3#"
            "plans" -> "*133#"
            "voucher" -> "*662*${request.value("voucher")}#"
            "transfer" -> "*234*1*${request.value("mobile")}*${request.value("pin")}*${request.value("amount")}#"
            "pin" -> "*234*2*${request.value("pin")}*${request.value("newPin")}#"
            "call" -> request.value("mobile")
            "call99" -> "*99${request.value("mobile")}"
            else -> error("Operación desconocida")
        }
        return UssdCommand(requireNotNull(spec.service), number)
    }
}
