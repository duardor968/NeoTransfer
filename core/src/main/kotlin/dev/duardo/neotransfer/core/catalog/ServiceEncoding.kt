package dev.duardo.neotransfer.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

internal fun contractField(key: String, label: String, kind: FieldKind = FieldKind.DIGITS, required: Boolean = true) =
    OperationField(key, label, kind, required)

internal fun contractChoice(key: String, label: String, vararg options: Pair<String, String>) =
    OperationField(key, label, FieldKind.CHOICE, options = options.map { FieldOption(it.first, it.second) })

internal fun bankNavigationReference(bank: Bank, section: String): ContractReference {
    val (pager, queries, operations, settings) = when (bank) {
        Bank.BPA -> listOf("kChOHNVmzz", "kmO9Md6JHr", "voNSkP3P8q", "f5AFyaj0rtx")
        Bank.BANDEC -> listOf("s684VE6Dm0", "R7PmVxXLJZC", "bLlRBTTLue", "X7obECfkKf")
        Bank.BANMET -> listOf("Q7htFIAC0SE", "PEi17BvMam", "Vdv5nYP39d", "RC71RyOpsk")
        Bank.BFI -> error("BFI no está disponible")
    }
    val tab = when (section) { "queries" -> queries; "settings" -> settings; else -> operations }
    return ContractReference("Navegación APK 1.260416", "S9ycHcoxAzy → $pager.getItem → $tab.initData/onGetItemClick → formulario citado")
}

internal fun walletNavigationReference(id: String, category: OperationCategory): ContractReference {
    val entry = when {
        id in setOf("authenticate", "disconnect") -> "ofOuyCSKWI.getItem(0) → H0kVHR7zuu"
        id in setOf("register", "pin", "remove", "classic.associate", "classic.remove") -> "ofOuyCSKWI.getItem(3) → hOQ4ESKEhm"
        category == OperationCategory.QUERIES -> "ofOuyCSKWI.getItem(1) → UMVR3qrc3V"
        else -> "ofOuyCSKWI.getItem(2) → rR6oImkdEU"
    }
    return ContractReference("Navegación APK 1.260416", "S9ycHcoxAzy → $entry.initData/onGetItemClick y diálogos → formulario citado")
}

internal fun contractEncoded(service: Int, parameters: List<String>, seed: Int?,
                             partialParameters: List<String>? = null, partialAgency: String? = null): UssdCommand {
    val codec = ParameterCodec()
    val payload = if (seed == null) codec.encode(parameters) else codec.encode(parameters, seed)
    val alternate = partialParameters?.let { codec.encode(it, payload.take(2).toInt()) }
    return UssdCommand(service, "*444*$service*$payload*1260416#", alternate, partialAgency)
}

internal fun ServiceRequest.value(key: String, empty: String = ""): String = values[key].orEmpty().ifEmpty { empty }

internal fun bankDebitCurrency(request: ServiceRequest): String =
    if (request.identity.provider == ProviderId.BANDEC || request.source is SourceSelector.Explicit) "0"
    else CurrencyContract.BANK.code(requireNotNull(request.currency))

internal fun contractValidation(spec: OperationSpec, request: ServiceRequest): MutableList<OperationValidationError> {
    val errors = spec.validate(request).toMutableList()
    if (spec.sourcePolicy == SourcePolicy.DEFAULT_OR_EXPLICIT && request.source == SourceSelector.Default &&
        request.identity.provider in setOf(ProviderId.BPA, ProviderId.BANMET) && request.currency == null)
        errors += OperationValidationError("currency", "Selecciona la moneda de la cuenta de origen")
    for (field in spec.fields) {
        val value = request.value(field.key)
        if (value.isEmpty()) continue
        when {
            field.kind == FieldKind.AMOUNT && !value.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?")) ->
                errors += OperationValidationError(field.key, "Indica el importe sin signo, separadores de miles ni notación exponencial")
            field.kind == FieldKind.PHONE && !value.matches(Regex("[0-9]{8}|53[0-9]{8}")) ->
                errors += OperationValidationError(field.key, "Indica un teléfono cubano de 8 dígitos, con prefijo 53 opcional")
            field.kind == FieldKind.DIGITS && field.key in setOf("identity", "senderIdentity", "recipientIdentity") && !value.matches(Regex("[0-9]{11}")) ->
                errors += OperationValidationError(field.key, "El carné debe tener 11 dígitos")
            field.kind == FieldKind.DATE && !validContractDate(value) ->
                errors += OperationValidationError(field.key, "Indica una fecha válida en formato dd/MM/aaaa")
        }
    }
    return errors
}

internal fun validContractDate(value: String): Boolean = runCatching {
    LocalDate.parse(value, DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT))
}.isSuccess

internal fun requireValidContract(errors: List<OperationValidationError>) {
    require(errors.isEmpty()) { errors.joinToString("; ") { it.message } }
}
