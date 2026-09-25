package dev.duardo.neotransfer.core

/** Provider identifiers and labels from the data assets of APK 1.260416, not arbitrary USSD input. */
object ProviderOptions {
    data class Plan(val id: String, val version: String, val label: String)
    val cubacelPlans: List<Plan> = listOf(
        Plan("1", "2", "Datos adicionales"), Plan("2", "2", "Datos Extra"), Plan("3", "2", "Voz"),
        Plan("4", "2", "SMS"), Plan("5", "2", "Todus"), Plan("6", "2", "Datos sectoriales"), Plan("7", "2", "Voz Extra"))
    val planField = OperationField("planId", "Plan", FieldKind.CHOICE, options = cubacelPlans.map { FieldOption(it.id, it.label) })
    val obligationField = contractChoice("obligation", "Obligación", "01" to "Contribución al patrimonio", "02" to "Canon",
        "03" to "Arrendamiento de stand", "04" to "Arrendamiento de local", "06" to "Estacionamiento", "08" to "Recargo",
        "09" to "Provisión de fondos", "10" to "Donación")
    val priorityField = contractChoice("priority", "Prelación de pago", "1" to "Tarjeta, después línea de crédito",
        "2" to "Línea de crédito, después tarjeta", "3" to "Solo línea de crédito")
    val withdrawalField = contractChoice("entity", "Entidad de extracción", "22001" to "CADECA · CUP", "22002" to "CADECA · USD")
}
