package dev.duardo.neotransfer.core

import java.math.BigDecimal

/** Banking contracts traced in Transfermóvil 1.260416. Live network acceptance is not implied. */
object BankingOperations {
    private val bankCurrencies = CurrencyContract.BANK.active
    private fun field(key: String, label: String, kind: FieldKind, required: Boolean = true,
                      options: List<FieldOption> = emptyList()) = OperationField(key, label, kind, required, options)

    private fun spec(bank: Bank, suffix: String, title: String, service: Int, source: SourcePolicy = SourcePolicy.NONE,
                     fields: List<OperationField> = emptyList(), currencies: List<Currency> = emptyList(),
                     effect: OperationEffect = OperationEffect.QUERY, category: OperationCategory = OperationCategory.QUERIES,
                     direct: Boolean = false, requiresSession: Boolean = true, reference: String): OperationSpec =
        OperationSpec("${bank.name.lowercase()}.$suffix", title, category, setOf(ProviderIdentity.forBank(bank)), service,
            fields, effect, if (direct) OperationTransport.DIRECT_USSD else OperationTransport.ENCODED_USSD,
            requiresSession, listOf(ContractReference("Transfermóvil 1.260416", reference)),
            sourcePolicy = source, currencies = currencies)

    val all: List<OperationSpec> = buildList {
        for (bank in listOf(Bank.BPA, Bank.BANDEC, Bank.BANMET)) {
            add(spec(bank, "authenticate", "Acceder a ${bank.name}", 40,
                fields = listOf(field("pin", "Clave bancaria", FieldKind.SECRET)),
                effect = OperationEffect.SECURITY, category = OperationCategory.SECURITY, requiresSession = false,
                reference = if (bank == Bank.BANMET) "PAZK2sdsJ5.java:31,49-60" else "docs/protocolo.md:18-30"))
            add(spec(bank, "disconnect", "Cerrar sesión de ${bank.name}", 70, effect = OperationEffect.SECURITY,
                category = OperationCategory.SECURITY, direct = true, reference = "docs/protocolo.md:18-22"))
            val source = when (bank) {
                Bank.BANDEC -> SourcePolicy.DEFAULT_ONLY
                else -> SourcePolicy.DEFAULT_OR_EXPLICIT
            }
            val currencies = if (bank == Bank.BPA || bank == Bank.BANMET) bankCurrencies else emptyList()
            add(spec(bank, "balance", "Consultar saldo · ${bank.name}", 46, source, currencies = currencies,
                direct = bank == Bank.BANDEC,
                reference = when (bank) {
                    Bank.BPA -> "ix4mhKGJty.java:47-58; Agg55uYctq.java:64-99"
                    Bank.BANDEC -> "R7PmVxXLJZC.java:354-372"
                    Bank.BANMET -> "dVxDCMi9cK.java:47-58; Agg55uYctq.java:64-99"
                    else -> error("Banco no disponible")
                }))
            add(spec(bank, "recent-operations", "Últimas operaciones · ${bank.name}", 48,
                SourcePolicy.DEFAULT_OR_EXPLICIT,
                fields = if (bank == Bank.BANMET) listOf(field("filter", "Tipo de operación", FieldKind.CHOICE,
                    options = BankOperationFilter.entries.map { FieldOption(it.code, it.label) })) else emptyList(),
                currencies = currencies, reference = when (bank) {
                    Bank.BPA -> "h3sd43nvje.java:21,47-58"
                    Bank.BANDEC -> "gaJlF6dT3z.java:21,44-52; DQIrxODoMU.java:56-85"
                    Bank.BANMET -> "GhhAuUBVrI.java:45,69-84; resources.txt:281-285"
                    else -> error("Banco no disponible")
                }))
            if (bank == Bank.BANDEC || bank == Bank.BANMET) add(spec(bank, "all-accounts", "Consultar todas las cuentas · ${bank.name}",
                58, direct = true, reference = "R7PmVxXLJZC.java:502-519; PEi17BvMam.java:337-354"))
            if (bank == Bank.BPA || bank == Bank.BANDEC) add(spec(bank, "associate-account", "Asociar cuenta · ${bank.name}", 60,
                fields = listOf(field("account", "Cuenta bancaria", FieldKind.ACCOUNT)), effect = OperationEffect.MANAGE,
                category = OperationCategory.ACCOUNTS, reference = "Ittht2aX4N.java:68-94; o9swVUfDl4q.java:67-90"))
            add(spec(bank, "transfer", "Transferir · ${bank.name}", 45,
                source = SourcePolicy.DEFAULT_OR_EXPLICIT, currencies = bankCurrencies,
                fields = listOf(field("destination", "Tarjeta de destino", FieldKind.ACCOUNT),
                    field("amount", "Importe", FieldKind.AMOUNT), field("notificationPhone", "Móvil de aviso", FieldKind.PHONE, false)),
                effect = OperationEffect.MONEY, category = OperationCategory.TRANSFERS,
                reference = "docs/protocolo.md service 45; BANMET x6Wob3Sa98U.java:617,646"))
        }
        add(spec(Bank.BANDEC, "recent-payments", "Últimos pagos · BANDEC", 63, SourcePolicy.DEFAULT_OR_EXPLICIT,
            fields = listOf(field("filter", "Tipo de pago", FieldKind.CHOICE,
                options = BankPaymentFilter.entries.map { FieldOption(it.code, it.label) })),
            reference = "ZF74c8UCRU.java:25-28,43,62-79; resources.txt:286-290"))
        add(spec(Bank.BANDEC, "payment-status", "Estado de un pago · BANDEC", 89, SourcePolicy.DEFAULT_OR_EXPLICIT,
            fields = listOf(field("reference", "Referencia del pago", FieldKind.TEXT)), reference = "XKpe29TPn0.java:50-59"))
    }

    fun validate(request: ServiceRequest): List<OperationValidationError> {
        val spec = all.singleOrNull { it.id == request.operationId }
            ?: return listOf(OperationValidationError("operation", "Operación bancaria desconocida"))
        return buildList {
            addAll(spec.validate(request))
            val bank = request.identity.bank ?: return@buildList
            if (request.source is SourceSelector.Explicit && request.source.account.length != 16)
                add(OperationValidationError("source", "La cuenta debe tener 16 dígitos"))
            for (key in listOf("account", "destination")) if (key in request.values && request.values[key]?.length != 16)
                add(OperationValidationError(key, "La cuenta debe tener 16 dígitos"))
            request.values["pin"]?.let { if (it.length != bank.pinLength) add(OperationValidationError("pin", "La clave debe tener ${bank.pinLength} dígitos")) }
            request.values["notificationPhone"]?.let { value ->
                if (value.isNotEmpty() && value.length != 8)
                    add(OperationValidationError("notificationPhone", "Móvil no válido"))
            }
            val needsCurrency = request.operationId.contains(".transfer") ||
                request.source == SourceSelector.Default && bank in setOf(Bank.BPA, Bank.BANMET) &&
                request.operationId.substringAfter('.') in setOf("balance", "recent-operations")
            if (needsCurrency && request.currency == null) add(OperationValidationError("currency", "Selecciona la moneda"))
            if (bank == Bank.BANDEC && request.currency != null && request.operationId.substringAfter('.') in setOf("balance", "recent-operations"))
                add(OperationValidationError("currency", "Esta consulta utiliza la cuenta seleccionada"))
        }
    }

    fun encode(request: ServiceRequest, seed: Int? = null): UssdCommand {
        val errors = validate(request)
        require(errors.isEmpty()) { errors.joinToString("; ") { it.message } }
        val bank = requireNotNull(request.identity.bank)
        val values = request.values
        val commands = BankCommands()
        return when (request.operationId.substringAfter('.')) {
            "authenticate" -> commands.authenticate(bank, values.getValue("pin").toCharArray(), seed)
            "disconnect" -> commands.disconnect()
            "balance" -> commands.balance(bank, request.currency, request.source, seed)
            "recent-operations" -> commands.recentOperations(bank, request.currency, request.source,
                values["filter"]?.let { code -> BankOperationFilter.entries.single { it.code == code } }, seed)
            "all-accounts" -> commands.allAccounts(bank)
            "associate-account" -> commands.associateAccount(bank, values.getValue("account"), seed)
            "recent-payments" -> commands.recentPayments(bank,
                BankPaymentFilter.entries.single { it.code == values.getValue("filter") }, request.source, seed)
            "payment-status" -> commands.paymentStatus(bank, values.getValue("reference"), request.source, seed)
            "transfer" -> commands.transfer(TransferRequest(bank, values.getValue("destination"),
                Money(BigDecimal(values.getValue("amount")), requireNotNull(request.currency)), request.source.wireValue,
                values["notificationPhone"]?.takeIf { it.isNotEmpty() }), seed)
            else -> throw IllegalArgumentException("Operación bancaria no disponible")
        }
    }
}
