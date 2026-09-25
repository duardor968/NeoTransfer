package dev.duardo.neotransfer.core

import dev.duardo.neotransfer.core.miturno.MiTurnoContracts

/** Independently implemented contracts observed in Transfermóvil 1.260416. No network acceptance is implied. */
object ServiceOperations {
    private val banks = setOf(ProviderId.BPA, ProviderId.BANDEC, ProviderId.BANMET)
    private val bankCurrencies = listOf(Currency.CUP, Currency.CUC, Currency.USD)
    private val amount = contractField("amount", "Importe en CUP", FieldKind.AMOUNT)
    private val optionalAmount = amount.copy(required = false, label = "Importe parcial en CUP (vacío: total)")
    private val phone = contractField("phone", "Móvil de confirmación", FieldKind.PHONE, false)
    private val identity = contractField("identity", "Carné de identidad")
    private val turnIdentity = identity.copy(label = "Carné de identidad o pasaporte", kind = FieldKind.TEXT)
    private val invoice = contractField("invoice", "Identificador de factura")
    private val account = contractField("account", "Cuenta del servicio", FieldKind.ACCOUNT)
    private val date = contractField("date", "Fecha (dd/MM/aaaa)", FieldKind.DATE)
    private val nautaFields = listOf(contractField("username", "Usuario Nauta sin dominio", FieldKind.TEXT),
        contractChoice("accountType", "Tipo de cuenta", "1" to "Navegación internacional", "2" to "Navegación nacional"), amount, phone)
    private fun spec(id: String, title: String, service: Int, source: String, fields: List<OperationField>,
                     category: OperationCategory = OperationCategory.PAYMENTS,
                     effect: OperationEffect = OperationEffect.MONEY, providers: Set<ProviderId> = banks,
                     sourcePolicy: SourcePolicy = SourcePolicy.DEFAULT_OR_EXPLICIT,
                     currencies: List<Currency> = bankCurrencies, transport: OperationTransport = OperationTransport.ENCODED_USSD) = OperationSpec(
        "service.$id", title, category, providers.map { ProviderIdentity(it) }.toSet(), service, fields,
        effect, transport, evidence = listOf(ContractReference("APK 1.260416 / $source", "Formulario y orden de parámetros de envío directo")) +
            providers.map { bankNavigationReference(requireNotNull(it.bank), if (category == OperationCategory.QUERIES) "queries" else "operations") },
        sourcePolicy = sourcePolicy, currencies = currencies)

    val all: List<OperationSpec> = listOf(
        spec("electricity", "Electricidad", 41, "xSwGh0MTOg:102; Q3Nvf439ab:102; PliSaYhOg0:135", listOf(invoice)),
        spec("telephone.bpa", "Telecomunicaciones", 42, "voNSkP3P8q case3 → u4QMc0S0ZHo:154-187 (importe obligatorio)", listOf(invoice, amount), providers = setOf(ProviderId.BPA)),
        spec("telephone", "Telecomunicaciones", 42, "bLlRBTTLue/Vdv5nYP39d → iQte15zdZs:168; pnl2Kga8j7:179 (total o parcial)", listOf(invoice, optionalAmount), providers = setOf(ProviderId.BANDEC, ProviderId.BANMET)),
        spec("water.havana", "Agua · La Habana", 19, "zOs55UzH6z:210; T4RQ5wDJDt:205; BoMBck6OXT:210", listOf(account)),
        spec("water.other", "Agua · Resto del país", 51, "zOs55UzH6z:210; T4RQ5wDJDt:215; BoMBck6OXT:210", listOf(account)),
        spec("water.varadero", "Agua · Varadero", 21, "zOs55UzH6z:210; T4RQ5wDJDt:224; BoMBck6OXT:210", listOf(account)),
        spec("gas", "Gas", 67, "FgpBLXKm6t:128; aiwIHVBe0c:128; yiqT29TYTb:128", listOf(account,
            contractChoice("paymentType", "Deuda a pagar", "1" to "Total", "2" to "Mensual"))),
        spec("onat.rc", "ONAT · Pago con RC", 43, "ROgpLrCtHa:391-435", listOf(
            contractField("taxpayer", "RC05"), contractField("taxCode", "RC04"), amount)),
        spec("onat.nit", "ONAT · Pago con NIT", 43, "ROgpLrCtHa:391-435", listOf(
            contractField("municipality", "Código DPA del municipio"), contractField("taxpayer", "RC05 o NIT"),
            contractField("taxCode", "RC04 o código del tributo"), contractField("period", "Período (mes/año)", FieldKind.TEXT), amount)),
        spec("ofa.contribution", "Aporte a la Oficina del Historiador", 94, "SZARzeWeX5:121; lQox6tgMlH:97 / ObligacionesOFA", listOf(identity,
            contractField("taxpayer", "Número de contribuyente"), contractField("taxCode", "Código"), contractField("period", "Período (mes/año)", FieldKind.TEXT), amount,
            ProviderOptions.obligationField), providers = setOf(ProviderId.BANDEC, ProviderId.BANMET)),
        spec("stamp", "Sellos del timbre", 43, "TGMSi5UDp7:238-267 (directo)", listOf(
            contractField("municipality", "Código DPA del municipio"), contractField("taxpayer", "NIT o carné"),
            contractField("taxCode", "Código del tributo"), contractField("period", "Período (mes/año)", FieldKind.TEXT), amount,
            contractField("entity", "Código de entidad receptora"))),
        spec("fine.contravention", "Multa de contravención", 96, "j9EpxD26FF:217", listOf(
            contractField("fine", "Número de multa"), identity, amount,
            contractChoice("amountCurrency", "Moneda de la multa", "1" to "CUP", "2" to "CUC"),
            contractField("decree", "Decreto"), contractField("article", "Artículo"), contractField("section", "Inciso"), date)),
        spec("fine.traffic", "Multa de tránsito", 97, "cLvoxQAY8E:386", listOf(contractField("fine", "Número de multa"),
            contractField("article", "Artículo"), contractField("section", "Inciso"), contractField("municipality", "Código de municipio"),
            contractField("province", "Código de provincia"), date)),
        spec("mobile", "Recarga móvil", 54, "SjZHfPJPwX:269-283 / GVEefrRz76", listOf(contractField("mobile", "Móvil a recargar", FieldKind.PHONE), amount), OperationCategory.RECHARGES),
        spec("nauta", "Recarga Nauta", 59, "PqTzgaEYF6:228", nautaFields, OperationCategory.RECHARGES),
        spec("nauta.home", "Recarga Nauta Hogar", 84, "PqTzgaEYF6:253", nautaFields, OperationCategory.RECHARGES),
        spec("nauta.debt", "Deuda Nauta Hogar", 86, "PqTzgaEYF6:277", nautaFields),
        spec("propia", "Recarga Propia", 77, "hqPCcU1E8u:219", listOf(contractField("serial", "Serie de tarjeta Propia"), amount), OperationCategory.RECHARGES),
        spec("tfa", "Recarga TFA", 108, "kq0rlPklFL:163", listOf(contractField("mobile", "Número TFA", FieldKind.PHONE), amount), OperationCategory.RECHARGES),
        spec("jovenclub", "Recarga Joven Club", 93, "w1lXYiCfJyk:122", listOf(contractField("username", "Usuario", FieldKind.TEXT), identity, amount), OperationCategory.RECHARGES),
        spec("plans", "Compra de planes Cubacel", 6, "n6dAtto0KFy:285", listOf(contractField("mobile", "Móvil", FieldKind.PHONE),
            ProviderOptions.planField), OperationCategory.RECHARGES, transport = OperationTransport.INTERACTIVE_USSD),
        spec("fuel", "Cupón de combustible", 35, "h97aDnMCap:128", listOf(amount)),
        spec("wallet.recharge", "Recargar MiTransfer CUP", 29, "uKzekWA2jf:229", listOf(contractField("mobile", "Móvil del monedero", FieldKind.PHONE), amount), OperationCategory.RECHARGES),
        spec("organization", "Cuota PCC o UJC", 7, "PTsHXOZ1gC:214-252", listOf(
            contractChoice("organization", "Organización", "1" to "PCC", "2" to "UJC"), identity, amount.copy(label = "Ingreso declarado en CUP"), phone)),
        spec("postal", "Giro postal", 64, "o6kycf55Gw:93; ASLdujtVtr:93; mhElzRz7zH:93", listOf(
            contractField("senderIdentity", "Carné del remitente"), contractField("recipientIdentity", "Carné del destinatario"), amount,
            contractChoice("purpose", "Concepto del giro", "1" to "Ayuda estudiante", "2" to "Pensión alimenticia", "3" to "Ayuda económica", "4" to "Solidario", "5" to "Otros"),
            contractField("mobile", "Móvil del destinatario", FieldKind.PHONE)), OperationCategory.TRANSFERS),
        spec("loan", "Amortizar crédito", 55, "Dm6u4PA5eg:68; q8aBmCOCwuI:63", listOf(account,
            contractField("installments", "Mensualidades")), providers = setOf(ProviderId.BPA, ProviderId.BANMET)),
        spec("loan.bandec", "Amortizar crédito", 55, "LZaBQezxPC:68", listOf(account, amount,
            contractField("installments", "Mensualidades"), contractField("name", "Nombre", FieldKind.TEXT)), providers = setOf(ProviderId.BANDEC)),
        spec("loan.installment", "Cambiar mensualidad", 25, "m0X6Kxdhmv:46; k2rtzJwkcQ:46", listOf(amount), OperationCategory.ACCOUNTS,
            OperationEffect.MANAGE, setOf(ProviderId.BANDEC, ProviderId.BANMET), SourcePolicy.EXPLICIT_ONLY),
        spec("deposit.bpa", "Depósito a plazo fijo", 81, "Hg2ath75ih:115-164", listOf(amount,
            contractChoice("term", "Plazo", "12M" to "12 meses", "24M" to "24 meses", "36M" to "36 meses", "60M" to "60 meses", "72M" to "72 meses"),
            contractChoice("interest", "Cobro de intereses", "1M" to "Mensual", "V" to "Al vencimiento", "12M" to "Anual")),
            OperationCategory.ACCOUNTS, providers = setOf(ProviderId.BPA), currencies = listOf(Currency.CUP)),
        spec("deposit.bandec", "Depósito a plazo fijo", 81, "eGLVt6hhNF:132-174", listOf(amount,
            contractChoice("term", "Plazo", "03" to "3 meses", "06" to "6 meses", "12" to "12 meses", "24" to "24 meses", "36" to "36 meses", "48" to "48 meses", "60" to "60 meses", "72" to "72 meses"),
            contractChoice("mode", "Modalidad", "05" to "Sin pago adelantado", "16" to "Con pago adelantado")), OperationCategory.ACCOUNTS,
            providers = setOf(ProviderId.BANDEC), currencies = listOf(Currency.CUP)),
        spec("deposit.banmet", "Depósito a plazo fijo", 81, "N4kvcsGpCN:87-108", listOf(amount,
            contractChoice("term", "Plazo", "12" to "12 meses", "24" to "24 meses", "36" to "36 meses", "60" to "60 meses")), OperationCategory.ACCOUNTS,
            providers = setOf(ProviderId.BANMET), currencies = listOf(Currency.CUP)),
        spec("deposit.close", "Cerrar depósito a plazo fijo", 82, "auVvnTnpCQ:69; StppiHhx0a:50; TlJMpg47gS:50", emptyList(),
            OperationCategory.ACCOUNTS, sourcePolicy = SourcePolicy.EXPLICIT_ONLY),
        spec("miturno.reserve", "MiTurno · Solicitar turno", 102, "L0JjVr9EwEF:715", listOf(turnIdentity,
            contractField("entity", "Código del servicio"), contractField("branch", "DPA de la sucursal"), phone), OperationCategory.PROCEDURES),
        spec("miturno.change", "MiTurno · Cambiar fecha", 105, "rGY5dubJYU:57-81,211", listOf(turnIdentity, MiTurnoContracts.managementField, date),
            OperationCategory.PROCEDURES, OperationEffect.MANAGE, sourcePolicy = SourcePolicy.NONE),
        spec("miturno.cancel", "MiTurno · Cancelar solicitud", 106, "GPsckxvkPu:51-74,204", listOf(turnIdentity, MiTurnoContracts.managementField),
            OperationCategory.PROCEDURES, OperationEffect.MANAGE, sourcePolicy = SourcePolicy.NONE),
        spec("miturno.query", "MiTurno · Consultar turno", 107, "V0zQxQBN2i:136", listOf(turnIdentity), OperationCategory.QUERIES,
            OperationEffect.QUERY, sourcePolicy = SourcePolicy.NONE),
        spec("cash.extra", "Caja Extra", 32, "UTk9UWZYxf:363", listOf(contractField("pin", "Clave bancaria", FieldKind.SECRET).copy(suppliedByAccess = true),
            contractField("transaction", "Identificador del pago", FieldKind.TEXT), amount,
            contractChoice("amountCurrency", "Moneda del importe", "1" to "CUP", "2" to "CUC", "3" to "USD"),
            contractField("provider", "Número del proveedor"), contractField("auxiliary", "Referencia auxiliar", FieldKind.TEXT),
            contractField("description", "Descripción", FieldKind.TEXT, false), phone)),
    ) + ServiceQueryOperations.all

    fun validate(request: ServiceRequest): List<OperationValidationError> {
        if (ServiceQueryOperations.contains(request.operationId)) return ServiceQueryOperations.validate(request)
        val spec = all.find { it.id == request.operationId } ?: return listOf(OperationValidationError("operation", "Operación desconocida"))
        val errors = contractValidation(spec, request)
        errors += CatalogValidation.validate(request)
        fun invalid(key: String, condition: Boolean, message: String) { if (condition) errors += OperationValidationError(key, message) }
        val id = request.operationId.removePrefix("service.")
        when (id) {
            "electricity" -> invalid("invoice", !request.value("invoice").matches(Regex("[0-9]{11}|[0-9]{13}")), "La factura eléctrica debe tener 11 o 13 dígitos")
            "telephone", "telephone.bpa" -> invalid("invoice", !request.value("invoice").matches(Regex("[0-9]{14,15}")), "La factura telefónica debe tener 14 o 15 dígitos")
            "nauta", "nauta.home", "nauta.debt" -> invalid("username", !request.value("username").matches(Regex("[A-Za-z0-9_.-]{1,20}")), "Usuario Nauta sin dominio, de hasta 20 caracteres")
            "mobile", "tfa", "fuel" -> invalid("amount", request.value("amount").toBigDecimalOrNull()?.let { it < java.math.BigDecimal.ONE } == true, "El importe mínimo es 1 CUP")
            "loan", "loan.bandec" -> {
                invalid("installments", request.value("installments").toIntOrNull()?.let { it > 0 } != true, "Indica mensualidades mayores que cero")
                if (request.identity.provider in setOf(ProviderId.BPA, ProviderId.BANDEC))
                    invalid("account", request.value("account").length != 16, "La cuenta de crédito debe tener 16 dígitos")
            }
            "deposit.bpa" -> invalid("interest", (request.value("term") == "72M" && request.value("interest") == "V") ||
                (request.value("term") != "72M" && request.value("interest") == "12M"), "Interés incompatible con el plazo")
            "deposit.bandec" -> invalid("term", (request.value("mode") == "16") != (request.value("term") == "72"), "El pago adelantado requiere 72 meses")
            "cash.extra" -> {
                invalid("pin", request.value("pin").isNotEmpty() && request.value("pin").length != request.identity.bank?.pinLength, "Longitud de clave bancaria no válida")
                invalid("phone", request.value("phone").isNotEmpty() && request.value("phone").length != 8, "El móvil de confirmación debe tener 8 dígitos")
            }
        }
        if (id in setOf("onat.nit", "ofa.contribution", "stamp")) invalid("period", !request.value("period").matches(Regex("(0?[1-9]|1[0-2])/[0-9]{4}")), "Período no válido")
        return errors.distinct()
    }

    fun encode(request: ServiceRequest, seed: Int? = null): UssdCommand {
        if (ServiceQueryOperations.contains(request.operationId)) return ServiceQueryOperations.encode(request, seed)
        requireValidContract(validate(request))
        if (request.operationId == "service.cash.extra") require(request.value("pin").length == request.identity.bank?.pinLength) { "Falta la clave del acceso autorizado" }
        val spec = all.first { it.id == request.operationId }
        val parameters = parameters(request)
        val partial = if (request.operationId == "service.stamp") parameters.toMutableList().also {
            // TGMSi5UDp7.submitForm, DEX 010a/0110/0116: partial path sends tribute before NIT.
            val taxpayer = it[2]; it[2] = it[3]; it[3] = taxpayer
        } else null
        return contractEncoded(requireNotNull(spec.service), parameters, seed, partialParameters = partial)
    }

    internal fun parameters(r: ServiceRequest): List<String> {
        if (ServiceQueryOperations.contains(r.operationId)) return ServiceQueryOperations.parameters(r)
        fun v(key: String, empty: String = "") = r.value(key, empty)
        val source = r.source.wireValue
        val bank = r.identity.provider
        val id = r.operationId.removePrefix("service.")
        val needsCurrency = all.first { it.id == r.operationId }.sourcePolicy == SourcePolicy.DEFAULT_OR_EXPLICIT
        val currency = if (needsCurrency) bankDebitCurrency(r) else "0"
        fun optionalSource(values: List<String>): List<String> = if (r.source is SourceSelector.Explicit) values + source else values
        return when (id) {
            "electricity", "telephone", "telephone.bpa" -> listOf(v("invoice"), if (id == "electricity") "0" else v("amount", "0")).let {
                if (bank == ProviderId.BANDEC) optionalSource(it) else it + listOf(currency, source)
            }
            "water.havana", "water.other", "water.varadero" -> {
                val region = when (id) { "water.havana" -> "1"; "water.other" -> "2"; else -> "3" }
                val values = listOf(region, v("account"), if (bank == ProviderId.BANDEC) "0" else "0.0", if (region == "2") "0" else "1")
                values + if (bank == ProviderId.BANDEC && region == "2") listOf(source) else listOf(currency, source)
            }
            "gas" -> if (bank == ProviderId.BANDEC) optionalSource(listOf(v("account"), "0", v("paymentType")))
                else listOf(v("account"), "0", currency, v("paymentType"), source)
            "onat.rc" -> listOf("1", "0000", v("taxpayer"), v("taxCode"), "0000", v("amount"), currency, source)
            "onat.nit" -> listOf("2", v("municipality"), v("taxpayer"), v("taxCode"), v("period"), v("amount"), currency, source)
            "stamp" -> listOf("2", v("municipality"), v("taxpayer"), v("taxCode"), v("period"), v("amount"), v("entity"), currency, source)
            "ofa.contribution" -> listOf(v("identity"), v("taxpayer"), v("taxCode"), v("period"), v("amount"), v("obligation"), currency, source)
            "fine.contravention" -> listOf(v("fine"), v("identity"), v("amount"), v("amountCurrency"), v("decree"), v("article"), v("section"), v("date"), currency, source)
            "fine.traffic" -> listOf(v("fine"), "0000", v("article"), v("section"), v("municipality"), v("province"), "1", v("date"), currency, source)
            "mobile", "tfa" -> listOf(v("mobile"), v("amount"), currency, source)
            "nauta", "nauta.home", "nauta.debt" -> listOf(v("username"), v("accountType"), v("amount"), currency, v("phone", "0000000"), source,
                when (id) { "nauta" -> "1"; "nauta.home" -> "2"; else -> "3" })
            "propia" -> listOf(v("serial"), v("amount"), currency, source)
            "jovenclub" -> listOf(v("username"), v("identity"), v("amount"), currency, source)
            "plans" -> listOf(v("mobile"), v("planId"), ProviderOptions.cubacelPlans.single { it.id == v("planId") }.version, currency, source)
            "fuel" -> listOf("0000", v("amount"), "1", currency, source)
            "wallet.recharge" -> listOf("1", v("mobile"), v("amount"), "1", currency, source)
            "organization" -> listOf(v("organization"), v("identity"), v("amount"), currency, v("phone", "00"), source)
            "miturno.query" -> listOf(requireNotNull(r.identity.bank).code.toInt().toString(), v("identity"))
            "postal" -> listOf(v("senderIdentity"), v("recipientIdentity"), v("amount"), if (bank == ProviderId.BANDEC) "1" else currency,
                v("purpose"), v("mobile")).let { if (bank == ProviderId.BANDEC) optionalSource(it) else it + source }
            "loan" -> listOf(v("account"), v("installments"), currency, source)
            "loan.bandec" -> optionalSource(listOf(v("account"), v("amount"), v("installments"), v("name")))
            "loan.installment" -> listOf(v("amount"), source)
            "deposit.bpa", "deposit.bandec", "deposit.banmet" -> listOf(v("amount"), v("term"),
                when (id) { "deposit.bpa" -> v("interest"); "deposit.bandec" -> v("mode"); else -> "0000" }, "1", source)
            "deposit.close" -> listOf(source)
            "miturno.reserve" -> listOf(requireNotNull(r.identity.bank).code.toInt().toString(), v("entity"), v("branch"), v("identity"), v("phone", "0"), currency, source)
            "miturno.change", "miturno.cancel" -> listOf(requireNotNull(r.identity.bank).code.toInt().toString(), v("serviceType"), v("identity")).let {
                if (id == "miturno.change") it + v("date").replace('/', '-') else it
            }
            "cash.extra" -> listOf(requireNotNull(r.identity.bank).code, v("pin"), v("transaction"), v("amount"), v("amountCurrency"),
                currency, v("provider"), v("auxiliary"), v("description", "0000"), v("phone", "00"), source)
            else -> error("Operación desconocida")
        }
    }
}
