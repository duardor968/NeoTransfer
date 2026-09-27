package dev.duardo.neotransfer

import dev.duardo.neotransfer.core.*
import dev.duardo.neotransfer.core.miturno.MiTurnoContracts

/** Selector state for the service form. Synthetic keys are removed before building a ServiceRequest. */
internal object ServiceFieldOptions {
    private const val TURN_TYPE = "miturnoType"
    private val provinceField = OperationField("province", "Provincia", FieldKind.CHOICE)
    private val municipalityField = OperationField("municipality", "Municipio", FieldKind.CHOICE)
    private val turnTypeField = OperationField(TURN_TYPE, "Servicio MiTurno", FieldKind.CHOICE)

    private fun bankBranches(spec: OperationSpec, identity: ProviderIdentity): Boolean =
        identity.bank?.let { bank -> spec.id.substringAfter('.') in setOf("open-account", "reprint-card", "fiscal-account") &&
            spec.fields.any { it.key == "branch" } && ServiceCatalogData.bankProvinces(bank).isNotEmpty() } == true

    private fun turnReserve(spec: OperationSpec): Boolean = spec.id in setOf("service.miturno.reserve", "wallet.miturno.reserve")
    private fun turnManagement(spec: OperationSpec): Boolean = spec.id in setOf("service.miturno.change", "service.miturno.cancel",
        "wallet.miturno.change", "wallet.miturno.cancel")
    private fun territory(spec: OperationSpec): Boolean = spec.id in setOf("service.stamp", "service.onat.nit", "wallet.withdrawal")
    private fun turnType(values: Map<String, String>): MiTurnoType? =
        MiTurnoType.entries.firstOrNull { it.name == values[TURN_TYPE] }

    /** Presentation order includes parent selectors absent from the wire contract. */
    fun fields(spec: OperationSpec, identity: ProviderIdentity, values: Map<String, String>): List<OperationField> {
        if (!spec.supports(identity)) return spec.fields
        if (turnReserve(spec)) return buildList {
            addAll(spec.fields.filter { it.key == "identity" })
            add(turnTypeField)
            add(provinceField)
            if (turnType(values)?.let { type -> ServiceCatalogData.miturnoMunicipalities(type, values["province"].orEmpty()).isNotEmpty() } == true)
                add(municipalityField.copy(label = "Zona"))
            addAll(spec.fields.filter { it.key == "branch" })
            addAll(spec.fields.filter { it.key == "entity" })
            addAll(spec.fields.filterNot { it.key in setOf("identity", "branch", "entity") })
        }
        if (bankBranches(spec, identity)) return buildList {
            add(provinceField)
            if (spec.fields.none { it.key == "municipality" }) add(municipalityField)
            addAll(spec.fields)
        }
        if (territory(spec)) return listOf(provinceField.copy(required = spec.id != "wallet.withdrawal")) + spec.fields
        if (spec.id == "service.fine.traffic") return spec.fields.filter { it.key == "province" } +
            spec.fields.filter { it.key == "municipality" } + spec.fields.filterNot { it.key in setOf("province", "municipality") }
        return spec.fields
    }

    /** null means no factual catalog; an empty list means this selector is waiting on its parent. */
    fun options(spec: OperationSpec, field: OperationField, identity: ProviderIdentity,
                values: Map<String, String>): List<FieldOption>? {
        if (!spec.supports(identity)) return null
        val key = field.key
        val province = values["province"].orEmpty()
        val municipality = values["municipality"].orEmpty()
        val bank = identity.bank
        if (turnReserve(spec)) {
            val type = turnType(values)
            return when (key) {
                TURN_TYPE -> MiTurnoContracts.typesFor(identity).map { FieldOption(it.name, when (it) {
                    MiTurnoType.CADECA -> "CADECA"
                    MiTurnoType.GAS -> "Gas"
                    MiTurnoType.BALITA -> "Balita de gas"
                    MiTurnoType.BANK -> "Banco Metropolitano"
                }) }
                "province" -> type?.let { ServiceCatalogData.miturnoProvinces(it).map { p -> FieldOption(p.code, p.name) } } ?: emptyList()
                "municipality" -> type?.let { ServiceCatalogData.miturnoMunicipalities(it, province)
                    .map { area -> FieldOption(area.code, area.name) } } ?: emptyList()
                "branch" -> if (type == null || province.isBlank()) emptyList() else {
                    val hasAreas = ServiceCatalogData.miturnoMunicipalities(type, province).isNotEmpty()
                    ServiceCatalogData.miturnoBranches(type, province, municipality.takeIf { hasAreas && it.isNotBlank() })
                        .takeIf { !hasAreas || municipality.isNotBlank() }.orEmpty()
                        .map { FieldOption(it.code, it.name) }
                }
                "entity" -> if (values["branch"].isNullOrBlank() || type == null) emptyList() else when (type) {
                    MiTurnoType.BANK -> {
                        val available = ServiceCatalogData.miturnoServices(values.getValue("branch")).mapTo(mutableSetOf()) { it.code }
                        MiTurnoContracts.servicesFor(identity).filter { it.type == type && it.code in available }
                            .map { FieldOption(it.code, it.label) }
                    }
                    else -> MiTurnoContracts.servicesFor(identity).filter { it.type == type }
                        .map { FieldOption(it.code, it.label) }
                }
                else -> null
            }
        }
        if (turnManagement(spec) && key == "serviceType") return MiTurnoContracts.servicesFor(identity)
            .map { FieldOption(it.code, it.label) }
        if (bankBranches(spec, identity) && bank != null) return when (key) {
            "province" -> ServiceCatalogData.bankProvinces(bank).map { FieldOption(it.code, it.name) }
            "municipality" -> ServiceCatalogData.bankMunicipalities(bank, province).map { FieldOption(it.code, it.name) }
            "branch" -> ServiceCatalogData.bankBranches(bank, province, municipality).map { FieldOption(it.code, it.name) }
            else -> null
        }
        if (territory(spec) || spec.id == "service.fine.traffic") return when (key) {
            "province" -> ServiceCatalogData.dpaProvinces.map { FieldOption(it.code, it.name) }
            "municipality" -> ServiceCatalogData.dpaMunicipalities(province).map { FieldOption(it.code, it.name) }
            "entity" -> when (spec.id) {
                "service.stamp" -> ServiceCatalogData.stampEntities.map { FieldOption(it.code, it.name) }
                "wallet.withdrawal" -> ServiceCatalogData.withdrawalEntities.map { FieldOption(it.code, "${it.name} · ${it.currency}") }
                else -> null
            }
            "article" -> if (spec.id == "service.fine.traffic") ServiceCatalogData.trafficArticles.map { FieldOption(it.code, "Artículo ${it.code}") } else null
            "section" -> if (spec.id == "service.fine.traffic") ServiceCatalogData.trafficSections(values["article"].orEmpty())
                .map { FieldOption(it.code, "${it.code} · ${it.description}") } else null
            else -> null
        }
        return null
    }

    /** The original stamp form also accepts a manual amount; these denominations are suggestions, not validation. */
    fun suggestions(spec: OperationSpec, field: OperationField): List<FieldOption>? =
        if (spec.id == "service.stamp" && field.key == "amount")
            ServiceCatalogData.stampDenominations.map { FieldOption(it.amount, it.name) }
        else null

    /** A parent change clears only related selections. */
    fun change(spec: OperationSpec, identity: ProviderIdentity, values: Map<String, String>,
               key: String, value: String): Map<String, String> {
        if (values[key] == value) return values
        val result = values.toMutableMap()
        if (value.isBlank()) result.remove(key) else result[key] = value
        val descendants = when {
            turnReserve(spec) -> when (key) {
                TURN_TYPE -> setOf("province", "municipality", "branch", "entity")
                "province" -> setOf("municipality", "branch", "entity")
                "municipality" -> setOf("branch", "entity")
                "branch" -> setOf("entity")
                else -> emptySet()
            }
            bankBranches(spec, identity) -> when (key) {
                "province" -> setOf("municipality", "branch")
                "municipality" -> setOf("branch")
                else -> emptySet()
            }
            spec.id == "service.fine.traffic" -> when (key) {
                "province" -> setOf("municipality")
                "article" -> setOf("section")
                else -> emptySet()
            }
            territory(spec) && key == "province" -> setOf("municipality")
            else -> emptySet()
        }
        descendants.forEach { result.remove(it) }
        return result
    }

    /** Check relational choices as well as synthetic required parents, which OperationSpec cannot see. */
    fun validate(spec: OperationSpec, identity: ProviderIdentity, values: Map<String, String>): List<OperationValidationError> =
        fields(spec, identity, values).mapNotNull { field ->
            val choices = options(spec, field, identity, values) ?: return@mapNotNull null
            val value = values[field.key].orEmpty()
            when {
                value.isBlank() && field.required -> OperationValidationError(field.key, "Selecciona ${field.label.lowercase()}")
                value.isNotBlank() && choices.none { it.value == value } -> OperationValidationError(field.key, "${field.label}: opción no válida")
                else -> null
            }
        }

    /** Strip UI-only parents; BANMET 74/76 and traffic fine 97 send a two-digit municipal component. */
    fun requestValues(spec: OperationSpec, values: Map<String, String>): Map<String, String> {
        val wire = values.filterKeys { key -> spec.fields.any { it.key == key } }.toMutableMap()
        if (spec.id in setOf("banmet.open-account", "banmet.reprint-card")) {
            val municipality = wire["municipality"].orEmpty()
            if (municipality.length == 4 && ServiceCatalogData.bankMunicipalities(Bank.BANMET, values["province"].orEmpty())
                    .any { it.code == municipality }) wire["municipality"] = municipality.takeLast(2)
        }
        if (spec.id == "service.fine.traffic") {
            val municipality = wire["municipality"].orEmpty()
            if (municipality.length == 4 && ServiceCatalogData.dpaMunicipalities(values["province"].orEmpty())
                    .any { it.code == municipality }) wire["municipality"] = municipality.takeLast(2)
        }
        return wire
    }
}
