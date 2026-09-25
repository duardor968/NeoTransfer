package dev.duardo.neotransfer.core

import java.time.LocalDate

/** Wallet QR differs from bank QR: wallet type and optional Clásica account have their own positions. */
object WalletQrOperations {
    private val pin = contractField("pin", "PIN del monedero MiTransfer", FieldKind.SECRET).copy(suppliedByAccess = true)
    private val baseFields = listOf(pin, contractField("transaction", "Identificador del pago", FieldKind.TEXT),
        contractField("amount", "Importe", FieldKind.AMOUNT),
        contractChoice("amountCurrency", "Moneda del importe", "1" to "CUP", "2" to "CUC", "3" to "USD"),
        contractField("provider", "Número del proveedor", FieldKind.TEXT), contractField("auxiliary", "Referencia auxiliar", FieldKind.TEXT),
        contractField("phone", "Móvil de confirmación", FieldKind.PHONE, false))
    private fun route(profile: ProfileId): String = when (profile) {
        ProfileId.CLASSIC -> "classic."
        else -> ""
    }
    val all: List<OperationSpec> = buildList {
        for (profile in listOf(ProfileId.PERSONAL, ProfileId.CLASSIC)) for (static in listOf(false, true)) add(OperationSpec(
            "wallet.${route(profile)}qr.${if (static) "static" else "dynamic"}",
            "Pago QR ${if (profile == ProfileId.CLASSIC) "Clásica" else "MiTransfer"} · ${if (static) "estático" else "dinámico"}", OperationCategory.PAYMENTS,
            setOf(ProviderIdentity(ProviderId.MITRANSFER, profile)), if (static) 31 else 30,
            baseFields + if (static) listOf(contractField("description", "Descripción", FieldKind.TEXT, false), contractField("qrId", "Identificador QR", FieldKind.TEXT, false)) else emptyList(),
            OperationEffect.MONEY, OperationTransport.ENCODED_USSD, requiresSession = false,
            evidence = listOf(ContractReference("APK 1.260416 / rR6oImkdEU.onGetItemClick case6 → s9CT6pjRyD:232-357", "Agencia04, bolsa USD=1/CUP=2/Clásica=3; tarjeta después de la cola QR")),
            sourcePolicy = if (profile != ProfileId.PERSONAL) SourcePolicy.EXPLICIT_ONLY else SourcePolicy.NONE,
            currencies = if (profile != ProfileId.PERSONAL) emptyList() else listOf(Currency.USD, Currency.CUP),
            authenticationIdentity = ProviderIdentity(ProviderId.MITRANSFER)))
        val normal = first { it.id == "wallet.classic.qr.dynamic" }
        add(normal.copy(id = "wallet.classic.qr.special", title = "Pago QR · Tarjeta Clásica vinculada",
                sourcePolicy = SourcePolicy.NONE,
                evidence = normal.evidence + ContractReference("s9CT6pjRyD:316-338",
                    "Auxiliar 11200301 sustituido por 0; esta rama no añade miCuentaSeleccion")))
    }

    fun fromQr(identity: ProviderIdentity, source: SourceSelector, walletCurrency: Currency?, qr: QrPayment,
               amount: Money, description: String = qr.description, phone: String? = null,
               today: LocalDate = LocalDate.now()): ServiceRequest {
        qr.validateDate(today)
        require(amount.currency == qr.amount.currency && amount.amount.signum() > 0) { "Moneda o importe incompatible con el QR" }
        require(qr.editableAmount || qr.amount.sameValue(amount)) { "El importe de este QR no se puede modificar" }
        require(qr.description.isEmpty() || description == qr.description) { "La descripción de este QR no se puede modificar" }
        val fields = mutableMapOf("transaction" to qr.transactionId, "amount" to amount.amount.toPlainString(),
            "amountCurrency" to CurrencyContract.BANK.code(amount.currency), "provider" to qr.provider, "auxiliary" to qr.auxiliary)
        phone?.let { fields["phone"] = it }
        if (qr.service == 31) {
            fields["description"] = description
            qr.qrId?.let { fields["qrId"] = it }
        }
        val variant = if (qr.service == 31) "static" else if (identity.profile != ProfileId.PERSONAL && qr.auxiliary.contains("11200301")) "special" else "dynamic"
        val request = ServiceRequest("wallet.${route(identity.profile)}qr.$variant",
            identity, source, walletCurrency, fields)
        requireValidContract(validate(request))
        return request
    }

    fun validate(request: ServiceRequest): List<OperationValidationError> {
        val spec = all.find { it.id == request.operationId } ?: return listOf(OperationValidationError("operation", "Pago QR no disponible para este perfil"))
        val errors = contractValidation(spec, request)
        if (request.identity.profile == ProfileId.CLASSIC) {
            if (spec.sourcePolicy == SourcePolicy.EXPLICIT_ONLY && request.source.wireValue.length != 16) errors += OperationValidationError("source", "Clásica requiere una tarjeta de 16 dígitos")
            if (spec.sourcePolicy == SourcePolicy.EXPLICIT_ONLY && spec.service == 30 && request.value("auxiliary").contains("11200301"))
                errors += OperationValidationError("operation", "Esta variante del QR no identifica la cuenta Clásica de origen")
        } else if (request.currency !in setOf(Currency.USD, Currency.CUP)) errors += OperationValidationError("currency", "Selecciona la cuenta MiTransfer CUP o USD")
        if (request.operationId.endsWith(".special") && !request.value("auxiliary").contains("11200301"))
            errors += OperationValidationError("auxiliary", "El QR no corresponde a esta variante")
        if (request.value("pin").isNotEmpty() && request.value("pin").length != 4)
            errors += OperationValidationError("pin", "PIN no válido")
        return errors.distinct()
    }

    fun encode(request: ServiceRequest, seed: Int? = null): UssdCommand {
        requireValidContract(validate(request))
        require(request.value("pin").length == 4) { "Falta el PIN del monedero autorizado" }
        return contractEncoded(requireNotNull(all.first { it.id == request.operationId }.service), parameters(request), seed,
            partialAgency = "04")
    }

    internal fun parameters(r: ServiceRequest): List<String> {
        val static = r.operationId.endsWith(".static")
        val classic = r.identity.profile == ProfileId.CLASSIC
        val special = !static && r.value("auxiliary").contains("11200301")
        val tail = r.value("phone").isNotEmpty() || r.value("qrId").isNotEmpty()
        return buildList {
            addAll(listOf("04", r.value("pin"), r.value("transaction"), r.value("amount"), r.value("amountCurrency"),
                if (classic) "3" else if (r.currency == Currency.USD) "1" else "2", r.value("provider"), if (special) "0" else r.value("auxiliary")))
            if (static) add(r.value("description", "0000"))
            add("0000")
            if (tail) {
                add(r.value("phone", "0000"))
                if (static) add(r.value("qrId", "0000"))
            }
            if (classic && !special) {
                if (!static && !tail) add("0")
                add(r.source.wireValue)
            }
        }
    }
}
