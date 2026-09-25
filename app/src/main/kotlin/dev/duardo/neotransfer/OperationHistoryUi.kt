package dev.duardo.neotransfer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.data.*
import java.time.Instant

internal fun OperationStatus.title(): String = when (this) {
    OperationStatus.PREPARED -> "Preparada"
    OperationStatus.SUBMITTING -> "Enviando"
    OperationStatus.AWAITING_CONFIRMATION -> "Esperando resultado"
    OperationStatus.UNCERTAIN -> "Resultado por comprobar"
    OperationStatus.CONFIRMED -> "Confirmada"
    OperationStatus.REJECTED -> "Rechazada"
    OperationStatus.CANCELLED -> "Sin enviar"
}

internal fun OperationRecord.title(state: AppUiState): String = state.services.firstOrNull { it.id == specId }?.title
    ?: when (kind) {
        "TRANSFER" -> "Transferencia"; "RECHARGE" -> "Recarga móvil"; "ELECTRICITY" -> "Electricidad"
        "TELEPHONE" -> "Telefonía fija"; "QR" -> "Pago QR"
        "bulevar.create_payment" -> "Solicitud de cobro"
        "bulevar.refund_request" -> "Solicitud de devolución"
        else -> "Solicitud bancaria"
    }

internal fun operationBelongsToProduct(operation: OperationRecord, product: WalletProductUi): Boolean {
    if (operation.registrationId != product.registrationId) return false
    operation.parameters["walletProductId"]?.let { return it == product.id }
    if (product.source is dev.duardo.neotransfer.core.SourceSelector.Explicit) return operation.source == product.source.wireValue
    if (operation.source != "0000" || operation.profileId != product.identity.profile.name) return false
    return product.kind != ProductKind.WALLET || product.currency?.name == operation.parameters["sourceCurrency"]
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OperationHistoryDetail(operation: OperationRecord, state: AppUiState, actions: UiActions, dismiss: () -> Unit) {
    var confirmReview by remember { mutableStateOf(false) }
    val spec = state.services.firstOrNull { it.id == operation.specId }
    val registration = state.wallet.registrations.firstOrNull { it.id == operation.registrationId }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(operation.title(state), style = MaterialTheme.typography.headlineSmall)
            Text(operation.status.title(), style = MaterialTheme.typography.titleMedium)
            registration?.let { Detail("Acceso", it.label.ifBlank { it.identity()?.title().orEmpty() }) }
            if (operation.source != "0000") Detail("Desde", readableAccount(operation.source))
            else if (spec?.sourcePolicy != dev.duardo.neotransfer.core.SourcePolicy.NONE) Detail("Desde", "Cuenta predeterminada")
            if (operation.amount != null) Detail("Importe", "${operation.amount} ${operation.currency.orEmpty()}".trim())
            else if (operation.parameters["paymentMode"] == "FULL_INVOICE") Detail("Importe", "Factura completa")
            if (operation.destination.isNotBlank()) Detail("Destino o referencia", readableAccount(operation.destination))
            spec?.fields?.filterNot { it.sensitive }?.forEach { field ->
                operation.parameters[field.key]?.takeIf { it.isNotBlank() && it != operation.destination && field.key != "amount" }?.let { value ->
                    Detail(field.label, field.options.firstOrNull { it.value == value }?.label ?: value)
                }
            }
            Detail("Fecha", dateText(Instant.ofEpochMilli(operation.startedAt)))
            if (operation.status in setOf(OperationStatus.UNCERTAIN, OperationStatus.AWAITING_CONFIRMATION)) {
                Text("El resultado no está confirmado. Comprueba la actividad antes de repetir la operación.")
            }
            if (operation.reviewRequired) TextButton(onClick = { confirmReview = true }, enabled = !state.busy) { Text("Cerrar revisión") }
        }
    }
    if (confirmReview) AlertDialog(onDismissRequest = { confirmReview = false }, title = { Text("Cerrar revisión") },
        text = { Text("La operación conservará su estado y podrás iniciar otra. Cerrar la revisión no cancela, confirma ni repite la solicitud.") },
        confirmButton = { TextButton(onClick = { actions.acknowledgeOperation(operation.id); confirmReview = false; dismiss() }) { Text("He revisado el resultado") } },
        dismissButton = { TextButton(onClick = { confirmReview = false }) { Text("Volver") } })
}
