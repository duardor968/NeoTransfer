package dev.duardo.neotransfer.core

import dev.duardo.neotransfer.core.miturno.MiTurnoContracts

/** Final wire identifiers must belong to the provider data used by the pickers. */
internal object CatalogValidation {
    private val municipalities by lazy { ServiceCatalogData.dpaProvinces.flatMap { ServiceCatalogData.dpaMunicipalities(it.code) } }
    private val branches by lazy { listOf(Bank.BPA, Bank.BANDEC, Bank.BANMET).flatMap { bank ->
        ServiceCatalogData.bankProvinces(bank).flatMap { province ->
            ServiceCatalogData.bankMunicipalities(bank, province.code).flatMap { municipality ->
                ServiceCatalogData.bankBranches(bank, province.code, municipality.code)
            }
        }
    } }
    private val turnBranches by lazy { MiTurnoType.entries.flatMap { type ->
        ServiceCatalogData.miturnoProvinces(type).flatMap { province ->
            ServiceCatalogData.miturnoBranches(type, province.code) +
                ServiceCatalogData.miturnoMunicipalities(type, province.code).flatMap { municipality ->
                    ServiceCatalogData.miturnoBranches(type, province.code, municipality.code)
                }
        }
    } }

    fun validate(request: ServiceRequest): List<OperationValidationError> = buildList {
        addAll(MiTurnoContracts.validate(request))
        fun invalid(key: String, condition: Boolean, message: String) { if (condition) add(OperationValidationError(key, message)) }
        val id = request.operationId
        val action = id.substringAfter('.')
        if (request.identity.bank != null && action in setOf("open-account", "reprint-card", "fiscal-account") && "branch" in request.values) {
            val choices = branches.filter { it.bank == request.identity.bank && it.code == request.value("branch") }
            invalid("branch", choices.isEmpty(), "Selecciona una sucursal del banco indicado")
            if ("municipality" in request.values) {
                val municipal = request.value("municipality")
                val valid = if (action == "fiscal-account") choices.any { it.municipalityDpa == municipal }
                    else choices.any { it.municipalityDpa.takeLast(2) == municipal }
                invalid("municipality", !valid, "El municipio no corresponde a la sucursal seleccionada")
            }
        }
        if (id in setOf("service.onat.nit", "service.stamp", "wallet.withdrawal") && request.value("municipality").isNotEmpty())
            invalid("municipality", municipalities.none { it.code == request.value("municipality") }, "Selecciona un municipio válido")
        if (id == "service.stamp") invalid("entity", ServiceCatalogData.stampEntity(request.value("entity")) == null, "Selecciona una entidad para los sellos")
        if (id == "service.fine.traffic") {
            invalid("section", ServiceCatalogData.trafficSection(request.value("article"), request.value("section")) == null, "El inciso no corresponde al artículo seleccionado")
            invalid("municipality", ServiceCatalogData.municipalityDpa(request.value("province"), request.value("municipality")) == null ||
                request.value("municipality").length != 2, "Selecciona un municipio de la provincia indicada")
        }
        if (id in setOf("service.miturno.reserve", "wallet.miturno.reserve")) {
            val entity = request.value("entity")
            val type = when (entity) { "1" -> MiTurnoType.CADECA; "2" -> MiTurnoType.GAS; "3" -> MiTurnoType.BALITA; else -> MiTurnoType.BANK }
            val branch = request.value("branch")
            invalid("branch", turnBranches.none { it.type == type && it.code == branch }, "La sucursal no corresponde al servicio MiTurno seleccionado")
            if (type == MiTurnoType.BANK)
                invalid("entity", ServiceCatalogData.miturnoServices(branch).none { it.code == entity }, "El servicio no está disponible en esa sucursal")
        }
    }
}
