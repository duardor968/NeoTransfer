package dev.duardo.neotransfer

import dev.duardo.neotransfer.core.*

object OperationCatalog {
    fun supports(identity: ProviderIdentity): Boolean = identity.provider != ProviderId.BFI &&
        identity.profile !in setOf(ProfileId.CLASSIC_BUSINESS, ProfileId.AGENT)
    private fun bandecQuery(id: String, title: String, service: Int, source: SourcePolicy) = OperationSpec(id, title,
        OperationCategory.QUERIES, setOf(ProviderIdentity(ProviderId.BANDEC)), service, emptyList(),
        if (service == 60) OperationEffect.MANAGE else OperationEffect.QUERY,
        if (service == 60) OperationTransport.ENCODED_USSD else OperationTransport.DIRECT_USSD, evidence = listOf(ContractReference("BANDEC services 60 and 46",
            "Select the explicit PAN, then verify it through the returned balance; never infer selection from an acknowledgement")), sourcePolicy = source)
    private val cardBalance = bandecQuery("bandec.card-balance", "Saldo de una tarjeta · BANDEC", 46, SourcePolicy.EXPLICIT_ONLY)
    private val cardSelect = bandecQuery("bandec.card-select", "Seleccionar tarjeta para consultar", 60, SourcePolicy.EXPLICIT_ONLY)
    private val originBalance = bandecQuery("bandec.origin-balance", "Comprobar moneda de origen", 46, SourcePolicy.DEFAULT_ONLY)
    private val bankQr = OperationSpec("bank.qr", "Pago QR", OperationCategory.PAYMENTS,
        setOf(Bank.BPA, Bank.BANDEC, Bank.BANMET).map(ProviderIdentity::forBank).toSet(), null, emptyList(),
        OperationEffect.MONEY, OperationTransport.ENCODED_USSD, requiresSession = false,
        evidence = listOf(ContractReference("BankCommands.qrPayment", "QR validado con identidad e importe inmutables")),
        sourcePolicy = SourcePolicy.DEFAULT_OR_EXPLICIT, currencies = CurrencyContract.BANK.active)
    val all: List<OperationSpec> = (BankingOperations.all + BankManagementOperations.all +
        ServiceOperations.all + WalletOperations.all + WalletQrOperations.all + TelecomOperations.all + cardBalance)
        .filter { it.identities.all(::supports) }
        .map { spec -> if (Currency.CUC in spec.currencies) spec.copy(currencies = spec.currencies - Currency.CUC) else spec }

    fun find(id: String): OperationSpec? = listOf(bankQr, cardSelect, originBalance).singleOrNull { it.id == id }
        ?: all.singleOrNull { it.id == id }

    fun needsUpdatedAccess(operations: List<dev.duardo.neotransfer.data.OperationRecord>, registrationId: String, generation: Long): Boolean =
        operations.any { it.registrationId == registrationId && find(it.specId)?.service == 69 &&
            it.status in setOf(dev.duardo.neotransfer.data.OperationStatus.SUBMITTING,
                dev.duardo.neotransfer.data.OperationStatus.AWAITING_CONFIRMATION,
                dev.duardo.neotransfer.data.OperationStatus.UNCERTAIN, dev.duardo.neotransfer.data.OperationStatus.CONFIRMED) &&
            (it.parameters["accessGeneration"]?.toLongOrNull() ?: 0) == generation }

    fun validate(request: ServiceRequest): List<OperationValidationError> = when {
        !supports(request.identity) -> listOf(OperationValidationError("operation", "Este proveedor o perfil no está disponible"))
        request.operationId == bankQr.id -> bankQr.validate(request)
        request.operationId in setOf(cardBalance.id, cardSelect.id, originBalance.id) -> requireNotNull(find(request.operationId)).validate(request) +
            if (request.source is SourceSelector.Explicit && request.source.wireValue.length != 16)
                listOf(OperationValidationError("source", "La tarjeta debe tener 16 dígitos")) else emptyList()
        BankingOperations.all.any { it.id == request.operationId } -> BankingOperations.validate(request)
        BankManagementOperations.all.any { it.id == request.operationId } -> BankManagementOperations.validate(request)
        ServiceOperations.all.any { it.id == request.operationId } -> ServiceOperations.validate(request)
        WalletQrOperations.all.any { it.id == request.operationId } -> WalletQrOperations.validate(request)
        WalletOperations.all.any { it.id == request.operationId } -> WalletOperations.validate(request)
        TelecomOperations.all.any { it.id == request.operationId } -> TelecomOperations.validate(request)
        else -> listOf(OperationValidationError("operation", "Operación no disponible"))
    }

    fun encode(request: ServiceRequest): UssdCommand = when {
        !supports(request.identity) -> throw IllegalArgumentException("Este proveedor o perfil no está disponible")
        BankingOperations.all.any { it.id == request.operationId } -> BankingOperations.encode(request)
        BankManagementOperations.all.any { it.id == request.operationId } -> BankManagementOperations.encode(request)
        ServiceOperations.all.any { it.id == request.operationId } -> ServiceOperations.encode(request)
        WalletQrOperations.all.any { it.id == request.operationId } -> WalletQrOperations.encode(request)
        WalletOperations.all.any { it.id == request.operationId } -> WalletOperations.encode(request)
        TelecomOperations.all.any { it.id == request.operationId } -> TelecomOperations.encode(request)
        else -> throw IllegalArgumentException("Operación no disponible")
    }
}
