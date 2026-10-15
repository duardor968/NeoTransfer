package dev.duardo.neotransfer

import dev.duardo.neotransfer.core.*

internal enum class ServiceUtility(val title: String) {
    ELECTRICITY("Electricidad"), WATER("Agua"), TELEPHONE("Teléfono fijo"),
    NAUTA_HOME("Nauta Hogar"), NAUTA("Nauta"), CUBACEL("Cubacel"),
}

internal data class ServiceVariant(
    val spec: OperationSpec,
    val initialValues: Map<String, String> = emptyMap(),
)

internal data class ServiceAction(val key: String, val title: String, val variants: List<ServiceVariant>) {
    fun resolve(identity: ProviderIdentity?): ServiceVariant? =
        if (variants.all { variant -> variant.spec.identities.all { it.bank == null } }) variants.singleOrNull()
        else identity?.let { selected -> variants.singleOrNull { it.spec.supports(selected) } }
}

internal data class ServiceGroup(val utility: ServiceUtility, val actions: List<ServiceAction>)

/** Presentation only: retain each bank's spec and use only options offered by that spec. */
internal fun serviceGroups(operations: List<OperationSpec>): List<ServiceGroup> {
    val actions = mutableMapOf<ServiceUtility, MutableMap<String, ServiceAction>>()
    fun add(utility: ServiceUtility, spec: OperationSpec, title: String, values: Map<String, String> = emptyMap()) {
        val purpose = when (spec.id) {
            "service.telephone.bpa" -> "service.telephone"
            "service.query.bpa", "service.query.bandec" -> "service.query"
            else -> spec.id
        }
        val key = purpose + ":" + values["serviceType"].orEmpty()
        val group = actions.getOrPut(utility) { linkedMapOf() }
        val variants = group[key]?.variants.orEmpty() + ServiceVariant(spec, values)
        group[key] = ServiceAction(key, title, variants.distinct())
    }
    for (spec in operations.filter { it.implementationStatus == ImplementationStatus.IMPLEMENTED }) {
        when (spec.id) {
            "service.electricity" -> add(ServiceUtility.ELECTRICITY, spec, "Pagar factura")
            "service.telephone", "service.telephone.bpa" -> add(ServiceUtility.TELEPHONE, spec, "Pagar factura")
            "service.water.havana" -> add(ServiceUtility.WATER, spec, "Pagar · La Habana")
            "service.water.other" -> add(ServiceUtility.WATER, spec, "Pagar · Resto del país")
            "service.water.varadero" -> add(ServiceUtility.WATER, spec, "Pagar · Varadero")
            "service.nauta.home" -> add(ServiceUtility.NAUTA_HOME, spec, "Recargar")
            "service.nauta.debt" -> add(ServiceUtility.NAUTA_HOME, spec, "Pagar deuda")
            "service.nauta" -> add(ServiceUtility.NAUTA, spec, "Recargar")
            "service.mobile" -> add(ServiceUtility.CUBACEL, spec, "Recargar desde el banco")
            "cubacel.plans" -> add(ServiceUtility.CUBACEL, spec, "Paquetes con saldo")
            "cubacel.transfer" -> add(ServiceUtility.CUBACEL, spec, "Transferir saldo")
            "cubacel.pin" -> add(ServiceUtility.CUBACEL, spec, "Cambiar clave de transferencia")
            "cubacel.balance", "cubacel.postpaid", "cubacel.bonus", "cubacel.data",
            "cubacel.voice", "cubacel.sms", "cubacel.friends" -> add(ServiceUtility.CUBACEL, spec,
                when (spec.id) {
                    "cubacel.balance" -> "Consultar saldo prepago"
                    "cubacel.postpaid" -> "Consultar saldo pospago"
                    "cubacel.bonus" -> "Consultar bonos"
                    "cubacel.data" -> "Consultar datos"
                    "cubacel.voice" -> "Consultar voz"
                    "cubacel.sms" -> "Consultar mensajes"
                    else -> "Consultar Plan Amigos"
                })
            "service.query.bpa", "service.query.bandec" -> {
                val options = spec.fields.singleOrNull { it.key == "serviceType" }?.options.orEmpty()
                for (option in options) {
                    val utility = when (option.value) {
                        "electricity" -> ServiceUtility.ELECTRICITY
                        "telephone" -> ServiceUtility.TELEPHONE
                        "water.havana", "water.other", "water.varadero" -> ServiceUtility.WATER
                        else -> continue
                    }
                    val title = when (option.value) {
                        "water.havana" -> "Consultar · La Habana"
                        "water.other" -> "Consultar · Resto del país"
                        "water.varadero" -> "Consultar · Varadero"
                        else -> "Consultar factura"
                    }
                    add(utility, spec, title, mapOf("serviceType" to option.value))
                }
            }
        }
    }
    return ServiceUtility.entries.mapNotNull { utility ->
        actions[utility]?.values?.takeIf { it.isNotEmpty() }?.let { ServiceGroup(utility, it.toList()) }
    }
}
