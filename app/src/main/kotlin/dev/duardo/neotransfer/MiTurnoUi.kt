package dev.duardo.neotransfer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.core.ServiceRequest
import dev.duardo.neotransfer.core.miturno.MiTurnoAction
import dev.duardo.neotransfer.data.OperationStatus
import java.time.Instant

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MiTurnoScreen(state: AppUiState, back: () -> Unit, open: (ServiceRequest, String) -> Unit,
                          newRequest: (() -> Unit)? = null, openReceipt: ((String) -> Unit)? = null) {
    val activeSubscriptions = state.sims.mapTo(mutableSetOf()) { it.id }
    val rows = remember(state.wallet, state.configuredRegistrationIds, state.sims) {
        miTurnoRequestRows(state.wallet, state.configuredRegistrationIds, activeSubscriptions)
    }
    val occupied = state.busy || state.pending != null
    var selectedId by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val selected = rows.singleOrNull { it.operation.id == selectedId }

    fun openAction(action: MiTurnoAction) {
        val id = selectedId ?: return
        val prepared = miTurnoPrefill(state.wallet, state.configuredRegistrationIds, activeSubscriptions, id, action, occupied)
        if (prepared == null) { notice = "La solicitud o su acceso cambió. Revísala de nuevo"; selectedId = null }
        else { selectedId = null; notice = null; open(prepared.first, prepared.second) }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { PageHeader("MiTurno", back) }
        item { SectionTitle("Solicitudes", if (rows.isNotEmpty() && newRequest != null) "Nueva solicitud" else null, newRequest ?: {}) }
        if (rows.isEmpty()) item {
            Text("No hay solicitudes MiTurno registradas en este dispositivo.",
                Modifier.padding(vertical = 20.dp), style = MaterialTheme.typography.bodyLarge)
            if (newRequest != null) TextButton(onClick = newRequest) { Text("Solicitar MiTurno") }
        }
        items(rows, key = { it.operation.id }) { row ->
            ActionRow(row.selection.service.label, R.drawable.ic_schedule,
                "${row.selection.identity.title()} · ${row.operation.status.turnRequestStatus()}") { selectedId = row.operation.id; notice = null }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        notice?.let { item { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 12.dp)) } }
    }

    if (selected != null) ModalBottomSheet(onDismissRequest = { selectedId = null },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Solicitud MiTurno", style = MaterialTheme.typography.headlineSmall)
            Detail("Proveedor", selected.selection.identity.title())
            Detail("Beneficiario", selected.selection.beneficiaryIdentity)
            Detail("Servicio", selected.selection.service.label)
            Detail("Sucursal", selected.branchName ?: selected.selection.branchCode)
            Detail("Fecha de solicitud", dateText(Instant.ofEpochMilli(selected.operation.startedAt)))
            Detail("Estado", selected.operation.status.turnRequestStatus())
            selected.receipt?.let { receipt ->
                Detail("Comprobante", receipt.reference ?: receipt.id)
                if (openReceipt != null) TextButton(onClick = { selectedId = null; openReceipt(receipt.id) }) { Text("Ver comprobante") }
            }
            selected.accessBlockedReason?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (selected.operation.status != OperationStatus.CONFIRMED && selected.accessBlockedReason == null)
                Text("Esta solicitud aún no tiene confirmación para cambiarla o cancelarla.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (selected.canManage) {
                Primary("Cambiar fecha", enabled = !occupied) { openAction(MiTurnoAction.CHANGE_DATE) }
                TextButton(onClick = { openAction(MiTurnoAction.QUERY) }, enabled = !occupied) { Text("Consultar") }
            } else Primary("Consultar", enabled = selected.canQuery && !occupied) { openAction(MiTurnoAction.QUERY) }
            TextButton(onClick = { openAction(MiTurnoAction.CANCEL) }, enabled = selected.canManage && !occupied) {
                Text("Cancelar solicitud")
            }
        }
    }
}

private fun OperationStatus.turnRequestStatus(): String = when (this) {
    OperationStatus.SUBMITTING -> "Envío en curso"
    OperationStatus.AWAITING_CONFIRMATION -> "Esperando respuesta"
    OperationStatus.UNCERTAIN -> "Resultado incierto"
    OperationStatus.CONFIRMED -> "Solicitud confirmada"
    else -> "Solicitud sin confirmar"
}
