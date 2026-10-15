package dev.duardo.neotransfer

import dev.duardo.neotransfer.core.*

object OperationCatalog {
    fun supports(identity: ProviderIdentity): Boolean = identity.profile == ProfileId.PERSONAL &&
        identity.provider in setOf(ProviderId.BPA, ProviderId.BANDEC, ProviderId.CUBACEL)

    private val approvedIds = listOf("bpa", "bandec").flatMap { bank ->
        setOf("authenticate", "disconnect", "register", "remove-registration", "change-key", "change-multibank-pin",
            "balance", "recent-operations", "recent-payments", "payment-status", "all-accounts", "associate-account",
            "card-information", "standard-account", "transfer").map { "$bank.$it" }
    }.toSet() + setOf(
        "service.electricity", "service.telephone.bpa", "service.telephone",
        "service.water.havana", "service.water.other", "service.water.varadero", "service.mobile",
        "service.nauta", "service.nauta.home", "service.nauta.debt",
        "service.query.bpa", "service.query.bandec",
        "cubacel.balance", "cubacel.postpaid", "cubacel.bonus", "cubacel.data", "cubacel.voice", "cubacel.sms",
        "cubacel.friends", "cubacel.plans", "cubacel.transfer", "cubacel.pin", "bandec.card-balance",
    )

    private fun scoped(spec: OperationSpec): OperationSpec? {
        val identities = spec.identities.filter(::supports).toSet()
        if (identities.isEmpty()) return null
        return spec.copy(identities = identities, currencies = spec.currencies - Currency.CUC,
            fields = spec.fields.map { field ->
                if (spec.id in setOf("service.query.bpa", "service.query.bandec") && field.key == "serviceType")
                    field.copy(options = field.options.filterNot { it.value == "gas" }) else field
            })
    }
    private fun bandecQuery(id: String, title: String, service: Int, source: SourcePolicy) = OperationSpec(id, title,
        OperationCategory.QUERIES, setOf(ProviderIdentity(ProviderId.BANDEC)), service, emptyList(),
        if (service == 60) OperationEffect.MANAGE else OperationEffect.QUERY,
        if (service == 60) OperationTransport.ENCODED_USSD else OperationTransport.DIRECT_USSD, evidence = listOf(ContractReference("BANDEC services 60 and 46",
            "Select the explicit PAN, then verify it through the returned balance; never infer selection from an acknowledgement")), sourcePolicy = source)
    private val cardBalance = bandecQuery("bandec.card-balance", "Saldo de una tarjeta · BANDEC", 46, SourcePolicy.EXPLICIT_ONLY)
    private val cardSelect = bandecQuery("bandec.card-select", "Seleccionar tarjeta para consultar", 60, SourcePolicy.EXPLICIT_ONLY)
    private val originBalance = bandecQuery("bandec.origin-balance", "Comprobar moneda de origen", 46, SourcePolicy.DEFAULT_ONLY)
    private val bankQr = OperationSpec("bank.qr", "Pago QR", OperationCategory.PAYMENTS,
        setOf(Bank.BPA, Bank.BANDEC).map(ProviderIdentity::forBank).toSet(), null, emptyList(),
        OperationEffect.MONEY, OperationTransport.ENCODED_USSD, requiresSession = false,
        evidence = listOf(ContractReference("BankCommands.qrPayment", "QR validado con identidad e importe inmutables")),
        sourcePolicy = SourcePolicy.DEFAULT_OR_EXPLICIT, currencies = CurrencyContract.BANK.active)
    val all: List<OperationSpec> = (BankingOperations.all + BankManagementOperations.all +
        ServiceOperations.all + TelecomOperations.all + cardBalance)
        .filter { it.id in approvedIds }
        .mapNotNull(::scoped)

    fun find(id: String): OperationSpec? = listOf(bankQr, cardSelect, originBalance).singleOrNull { it.id == id }
        ?: all.singleOrNull { it.id == id }

    // Historical key changes must still invalidate access after their provider leaves the public catalog.
    private val accessChangeIds = (BankManagementOperations.all + WalletOperations.all)
        .filter { it.service == 69 }.map { it.id }.toSet()

    fun needsUpdatedAccess(operations: List<dev.duardo.neotransfer.data.OperationRecord>, registrationId: String, generation: Long): Boolean =
        operations.any { it.registrationId == registrationId && it.specId in accessChangeIds &&
            it.status in setOf(dev.duardo.neotransfer.data.OperationStatus.SUBMITTING,
                dev.duardo.neotransfer.data.OperationStatus.AWAITING_CONFIRMATION,
                dev.duardo.neotransfer.data.OperationStatus.UNCERTAIN, dev.duardo.neotransfer.data.OperationStatus.CONFIRMED) &&
            (it.parameters["accessGeneration"]?.toLongOrNull() ?: 0) == generation }

    fun validate(request: ServiceRequest): List<OperationValidationError> {
        if (!supports(request.identity))
            return listOf(OperationValidationError("operation", "Este proveedor o perfil no está disponible"))
        val spec = find(request.operationId)
            ?: return listOf(OperationValidationError("operation", "Operación no disponible"))
        val errors = spec.validate(request)
        if (errors.isNotEmpty()) return errors
        return when {
            request.operationId == bankQr.id -> emptyList()
            request.operationId in setOf(cardBalance.id, cardSelect.id, originBalance.id) ->
                if (request.source is SourceSelector.Explicit && request.source.wireValue.length != 16)
                    listOf(OperationValidationError("source", "La tarjeta debe tener 16 dígitos")) else emptyList()
            BankingOperations.all.any { it.id == request.operationId } -> BankingOperations.validate(request)
            BankManagementOperations.all.any { it.id == request.operationId } -> BankManagementOperations.validate(request)
            ServiceOperations.all.any { it.id == request.operationId } -> ServiceOperations.validate(request)
            TelecomOperations.all.any { it.id == request.operationId } -> TelecomOperations.validate(request)
            else -> listOf(OperationValidationError("operation", "Operación no disponible"))
        }
    }

    fun encode(request: ServiceRequest): UssdCommand {
        val errors = validate(request)
        require(errors.isEmpty()) { errors.joinToString("; ") { it.message } }
        return when {
            BankingOperations.all.any { it.id == request.operationId } -> BankingOperations.encode(request)
            BankManagementOperations.all.any { it.id == request.operationId } -> BankManagementOperations.encode(request)
            ServiceOperations.all.any { it.id == request.operationId } -> ServiceOperations.encode(request)
            TelecomOperations.all.any { it.id == request.operationId } -> TelecomOperations.encode(request)
            else -> throw IllegalArgumentException("Operación no disponible")
        }
    }
}
