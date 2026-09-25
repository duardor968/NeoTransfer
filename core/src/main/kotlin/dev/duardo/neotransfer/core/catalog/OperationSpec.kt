package dev.duardo.neotransfer.core

enum class OperationCategory { PAYMENTS, RECHARGES, TRANSFERS, QUERIES, ACCOUNTS, SECURITY, TELECOM, PROCEDURES }
enum class OperationEffect { QUERY, MONEY, MANAGE, SECURITY }
enum class OperationTransport { ENCODED_USSD, DIRECT_USSD, INTERACTIVE_USSD, SYSTEM_DIAL, LOCAL, EXTERNAL_APP, WEB }
enum class ImplementationStatus { IMPLEMENTED, CONTRACT_PENDING }
enum class FieldKind { TEXT, DIGITS, ACCOUNT, PHONE, AMOUNT, CURRENCY, CHOICE, SECRET, DATE, EMAIL }
enum class SourcePolicy { NONE, DEFAULT_ONLY, EXPLICIT_ONLY, DEFAULT_OR_EXPLICIT }

data class FieldOption(val value: String, val label: String)
data class OperationField(
    val key: String,
    val label: String,
    val kind: FieldKind,
    val required: Boolean = true,
    val options: List<FieldOption> = emptyList(),
    val sensitive: Boolean = kind == FieldKind.SECRET,
    val suppliedByAccess: Boolean = false,
)

data class OperationValidationError(val fieldKey: String, val message: String)

/** Locally inspected protocol evidence; this does not claim acceptance by the live network. */
data class ContractReference(val source: String, val detail: String)

data class OperationSpec(
    val id: String,
    val title: String,
    val category: OperationCategory,
    val identities: Set<ProviderIdentity>,
    val service: Int?,
    val fields: List<OperationField>,
    val effect: OperationEffect,
    val transport: OperationTransport,
    val requiresSession: Boolean = true,
    val evidence: List<ContractReference>,
    val implementationStatus: ImplementationStatus = ImplementationStatus.IMPLEMENTED,
    val sourcePolicy: SourcePolicy = SourcePolicy.NONE,
    val currencies: List<Currency> = emptyList(),
    val authenticationIdentity: ProviderIdentity? = null,
) {
    val requiresConfirmation: Boolean get() = effect != OperationEffect.QUERY

    fun supports(identity: ProviderIdentity): Boolean = identity in identities

    fun validate(request: ServiceRequest): List<OperationValidationError> = buildList {
        if (request.operationId != id || !supports(request.identity))
            add(OperationValidationError("operation", "Operación no disponible para este origen"))
        if (implementationStatus != ImplementationStatus.IMPLEMENTED)
            add(OperationValidationError("operation", "El contrato de esta operación está pendiente"))
        if (sourcePolicy == SourcePolicy.EXPLICIT_ONLY && request.source !is SourceSelector.Explicit ||
            sourcePolicy in setOf(SourcePolicy.NONE, SourcePolicy.DEFAULT_ONLY) && request.source is SourceSelector.Explicit)
            add(OperationValidationError("source", "Selecciona un origen compatible"))
        if (request.currency != null && currencies.isNotEmpty() && request.currency !in currencies)
            add(OperationValidationError("currency", "Moneda no admitida por esta operación"))
        for (field in fields) {
            val value = request.values[field.key].orEmpty()
            if (value.isBlank()) {
                if (field.required && !field.suppliedByAccess) add(OperationValidationError(field.key, "Completa ${field.label.lowercase()}"))
                continue
            }
            val valid = when (field.kind) {
                FieldKind.DIGITS, FieldKind.ACCOUNT, FieldKind.PHONE, FieldKind.SECRET -> value.all { it in '0'..'9' }
                FieldKind.AMOUNT -> value.toBigDecimalOrNull()?.let { it.signum() > 0 && it.scale() <= 2 } == true
                FieldKind.CHOICE, FieldKind.CURRENCY -> field.options.any { it.value == value }
                else -> value.none { it == '*' || it == '#' || it.isISOControl() }
            }
            if (!valid) add(OperationValidationError(field.key, "${field.label}: valor no válido"))
        }
        request.values.keys.filter { key -> fields.none { it.key == key } }.forEach {
            add(OperationValidationError(it, "Campo no admitido por esta operación"))
        }
    }
}

/** Semantic form values only. Encoders own parameter order, sentinels and the wire envelope. */
class ServiceRequest(
    val operationId: String,
    val identity: ProviderIdentity,
    val source: SourceSelector = SourceSelector.Default,
    val currency: Currency? = null,
    values: Map<String, String> = emptyMap(),
) {
    val values: Map<String, String> = values.toMap()
    override fun toString(): String = "ServiceRequest(operationId=$operationId, identity=$identity)"
}
