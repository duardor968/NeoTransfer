package dev.duardo.neotransfer

import dev.duardo.neotransfer.core.*
import dev.duardo.neotransfer.core.miturno.MiTurnoAction
import dev.duardo.neotransfer.core.miturno.MiTurnoContracts
import dev.duardo.neotransfer.core.miturno.MiTurnoRequestSelection
import dev.duardo.neotransfer.data.*

internal data class MiTurnoRequestRow(
    val operation: OperationRecord,
    val selection: MiTurnoRequestSelection,
    val branchName: String?,
    val receipt: ReceiptRecord?,
    val registrationId: String?,
    val accessBlockedReason: String?,
) {
    val canQuery: Boolean get() = accessBlockedReason == null && operation.status in
        setOf(OperationStatus.AWAITING_CONFIRMATION, OperationStatus.UNCERTAIN, OperationStatus.CONFIRMED)
    val canManage: Boolean get() = accessBlockedReason == null && operation.status == OperationStatus.CONFIRMED
}

/** Only recorded service-102 requests with reconstructable original fields are shown. */
internal fun miTurnoRequestRows(snapshot: WalletSnapshot, configuredRegistrationIds: Set<String>,
                                activeSubscriptionIds: Set<Int>): List<MiTurnoRequestRow> {
    val specs = (ServiceOperations.all + WalletOperations.all).associateBy { it.id }
    return snapshot.operations.asSequence().filter { operation ->
        operation.specId in setOf("service.miturno.reserve", "wallet.miturno.reserve") &&
            operation.status !in setOf(OperationStatus.PREPARED, OperationStatus.CANCELLED, OperationStatus.REJECTED) &&
            !operation.restored && operation.parameters["internalStep"] != "true"
    }.mapNotNull { operation ->
        val spec = specs[operation.specId]?.takeIf { it.service == 102 } ?: return@mapNotNull null
        val identity = runCatching { ProviderIdentity(ProviderId.valueOf(requireNotNull(operation.providerId)),
            ProfileId.valueOf(operation.profileId)) }.getOrNull() ?: return@mapNotNull null
        if (!spec.supports(identity)) return@mapNotNull null
        val source = if (operation.source == "0000") SourceSelector.Default else
            runCatching { SourceSelector.Explicit(operation.source) }.getOrNull() ?: return@mapNotNull null
        val currency = operation.parameters["sourceCurrency"]?.let { runCatching { Currency.valueOf(it) }.getOrNull() }
        val allowed = spec.fields.mapTo(mutableSetOf()) { it.key }
        val request = ServiceRequest(spec.id, identity, source, currency, operation.parameters.filterKeys { it in allowed })
        val selection = MiTurnoContracts.selectionFromRequest(request) ?: return@mapNotNull null
        val registration = operation.registrationId?.let { id -> snapshot.registrations.singleOrNull { it.id == id } }
        val accessBlocked = when {
            registration == null -> "El acceso original ya no está disponible"
            registration.identity() != identity -> "El acceso original cambió de proveedor o perfil"
            !registration.enabled || registration.subscriptionId != operation.subscriptionId ||
                operation.subscriptionId !in activeSubscriptionIds -> "La línea original ya no está activa en este acceso"
            registration.id !in configuredRegistrationIds -> "Configura el acceso original antes de gestionar la solicitud"
            else -> null
        }
        val receipts = snapshot.receipts.filter { it.operationId == operation.id }
        val branches = ServiceCatalogData.miturnoProvinces(selection.service.type).flatMap { province ->
            ServiceCatalogData.miturnoBranches(selection.service.type, province.code) +
                ServiceCatalogData.miturnoMunicipalities(selection.service.type, province.code).flatMap { municipality ->
                    ServiceCatalogData.miturnoBranches(selection.service.type, province.code, municipality.code)
                }
        }
        val branchName = branches.filter { it.code == selection.branchCode }.singleOrNull()?.name
        MiTurnoRequestRow(operation, selection, branchName, receipts.singleOrNull()?.takeUnless { it.referenceConflict },
            operation.registrationId, accessBlocked)
    }.sortedByDescending { it.operation.startedAt }.toList()
}

/** Recheck current journal and access by explicit operation ID before opening another form. */
internal fun miTurnoPrefill(snapshot: WalletSnapshot, configuredRegistrationIds: Set<String>,
                            activeSubscriptionIds: Set<Int>, operationId: String, action: MiTurnoAction,
                            executionBusy: Boolean = false): Pair<ServiceRequest, String>? {
    if (action == MiTurnoAction.REQUEST) return null
    if (executionBusy) return null
    val row = miTurnoRequestRows(snapshot, configuredRegistrationIds, activeSubscriptionIds)
        .singleOrNull { it.operation.id == operationId } ?: return null
    if (action == MiTurnoAction.QUERY && !row.canQuery || action != MiTurnoAction.QUERY && !row.canManage) return null
    return MiTurnoContracts.prefill(row.selection, action) to requireNotNull(row.registrationId)
}
