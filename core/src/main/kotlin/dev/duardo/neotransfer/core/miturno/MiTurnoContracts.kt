package dev.duardo.neotransfer.core.miturno

import dev.duardo.neotransfer.core.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class MiTurnoAction { REQUEST, CHANGE_DATE, CANCEL, QUERY }

data class MiTurnoServiceOption(val code: String, val label: String, val type: MiTurnoType)

/** Input of a recorded request. This object contains no assigned appointment date, time or ticket number. */
class MiTurnoRequestSelection internal constructor(
    val identity: ProviderIdentity,
    val beneficiaryIdentity: String,
    val service: MiTurnoServiceOption,
    val branchCode: String,
) {
    override fun toString(): String = "MiTurnoRequestSelection(identity=$identity, service=${service.code})"
}

object MiTurnoContracts {
    private val consumerProviders = setOf(ProviderId.BPA, ProviderId.BANDEC, ProviderId.BANMET, ProviderId.MITRANSFER)
    private val generalServices = listOf(
        MiTurnoServiceOption("1", "Compra de divisas en CADECA", MiTurnoType.CADECA),
        MiTurnoServiceOption("2", "Trámite comercial de gas licuado", MiTurnoType.GAS),
        MiTurnoServiceOption("3", "Venta de gas licuado", MiTurnoType.BALITA),
    )
    // array_servicios_bancarios_miturno; rGY5dubJYU/Wwc5crW71D select position + 4.
    private val bankServices = listOf(
        "Apertura o traslado de cuenta de ahorro", "Certificaciones o estados de cuentas",
        "Solicitud o ampliación de créditos", "Solicitud de cheque de gerencia", "Obtención de título de propiedad",
        "Operaciones de caja", "Extracciones por el cajero automático", "Trámite de beneficiarios y herederos",
        "Contratación de Telebanca o reimpresión de PIN", "Traslado de adeudos de créditos y LGV",
        "Certificado de adeudos u operaciones recibidas",
    ).mapIndexed { index, label -> MiTurnoServiceOption((index + 4).toString(), label, MiTurnoType.BANK) }

    val managementField = OperationField("serviceType", "Servicio", FieldKind.CHOICE,
        options = (generalServices + bankServices).map { FieldOption(it.code, it.label) })

    fun servicesFor(identity: ProviderIdentity): List<MiTurnoServiceOption> {
        if (identity.profile != ProfileId.PERSONAL || identity.provider !in consumerProviders) return emptyList()
        return generalServices + if (identity.provider in setOf(ProviderId.BANMET, ProviderId.MITRANSFER)) bankServices else emptyList()
    }

    fun typesFor(identity: ProviderIdentity): List<MiTurnoType> = servicesFor(identity).map { it.type }.distinct()

    fun action(operationId: String): MiTurnoAction? = when (operationId) {
        "service.miturno.reserve", "wallet.miturno.reserve" -> MiTurnoAction.REQUEST
        "service.miturno.change", "wallet.miturno.change" -> MiTurnoAction.CHANGE_DATE
        "service.miturno.cancel", "wallet.miturno.cancel" -> MiTurnoAction.CANCEL
        "service.miturno.query", "wallet.miturno.query" -> MiTurnoAction.QUERY
        else -> null
    }

    fun validate(request: ServiceRequest): List<OperationValidationError> = buildList {
        val action = action(request.operationId) ?: return@buildList
        val services = servicesFor(request.identity)
        val wallet = request.identity.provider == ProviderId.MITRANSFER
        if (services.isEmpty() || request.operationId.startsWith("wallet.") != wallet)
            add(OperationValidationError("operation", "MiTurno no está disponible para este acceso"))
        if (action != MiTurnoAction.QUERY) {
            val key = if (action == MiTurnoAction.REQUEST) "entity" else "serviceType"
            if (services.none { it.code == request.values[key] })
                add(OperationValidationError(key, "Selecciona un servicio disponible para este acceso"))
        }
        if (action == MiTurnoAction.REQUEST && !request.values["phone"].isNullOrEmpty() &&
            !request.values.getValue("phone").matches(Regex("[0-9]{8}")))
            add(OperationValidationError("phone", "El móvil de confirmación debe tener 8 dígitos"))
    }

    /** Both date-change forms use a picker bounded by today and six calendar months later. */
    fun validateForExecution(request: ServiceRequest, today: LocalDate = LocalDate.now()): List<OperationValidationError> = buildList {
        addAll(validate(request))
        if (action(request.operationId) == MiTurnoAction.CHANGE_DATE) {
            val text = request.values["date"].orEmpty()
            val date = if (validContractDate(text)) LocalDate.parse(text, DateTimeFormatter.ofPattern("dd/MM/uuuu")) else null
            if (date == null || date < today || date > today.plusMonths(6))
                add(OperationValidationError("date", "Selecciona una fecha entre hoy y los próximos seis meses"))
        }
    }

    /** The app must verify the journal row and its registration/SIM before calling this projection. */
    fun selectionFromRequest(request: ServiceRequest): MiTurnoRequestSelection? {
        if (action(request.operationId) != MiTurnoAction.REQUEST) return null
        val errors = if (request.identity.provider == ProviderId.MITRANSFER) WalletOperations.validate(request) else ServiceOperations.validate(request)
        if (errors.isNotEmpty()) return null
        val service = servicesFor(request.identity).singleOrNull { it.code == request.values["entity"] } ?: return null
        return MiTurnoRequestSelection(request.identity, request.values.getValue("identity"), service, request.values.getValue("branch"))
    }

    /** Opens a form. A date change deliberately has no date until the user selects one. */
    fun prefill(selection: MiTurnoRequestSelection, action: MiTurnoAction): ServiceRequest {
        require(action != MiTurnoAction.REQUEST)
        val prefix = if (selection.identity.provider == ProviderId.MITRANSFER) "wallet" else "service"
        val suffix = when (action) { MiTurnoAction.CHANGE_DATE -> "change"; MiTurnoAction.CANCEL -> "cancel"; MiTurnoAction.QUERY -> "query"; else -> error("Unsupported action") }
        return ServiceRequest("$prefix.miturno.$suffix", selection.identity, values = buildMap {
            put("identity", selection.beneficiaryIdentity)
            if (action != MiTurnoAction.QUERY) put("serviceType", selection.service.code)
        })
    }
}
