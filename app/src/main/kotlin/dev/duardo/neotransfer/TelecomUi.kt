package dev.duardo.neotransfer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.platform.TelecomActionKind
import dev.duardo.neotransfer.platform.TelecomActions
import dev.duardo.neotransfer.platform.TelecomSmsController
import dev.duardo.neotransfer.platform.TelecomSmsDraft
import dev.duardo.neotransfer.platform.TelecomSmsPrepare
import dev.duardo.neotransfer.platform.TelecomSmsRecord
import dev.duardo.neotransfer.platform.TelecomSmsStatus
import dev.duardo.neotransfer.platform.TelecomSmsSubmit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TelecomScreen(state: AppUiState, controller: TelecomSmsController,
                           requestSmsPermission: () -> Unit, smsPermission: Boolean, back: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("telecom_info_sms", Context.MODE_PRIVATE) }
    var records by remember(controller) { mutableStateOf(controller.records()) }
    val main = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(prefs, controller) {
        var listening = true
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            main.post { if (listening) records = controller.records() }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { listening = false; prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var selectedSim by remember { mutableStateOf<Int?>(null) }
    var review by remember { mutableStateOf<TelecomSmsDraft?>(null) }
    var permissionRequestedFor by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.sims) {
        if (selectedSim != null && state.sims.none { it.id == selectedSim }) selectedSim = null
    }

    fun openExternal(id: String): Boolean {
        val intent = TelecomActions.intentFor(id) ?: return false
        return try { context.startActivity(intent); true }
        catch (_: ActivityNotFoundException) { notice = "No hay una aplicación disponible para esta acción"; false }
        catch (_: SecurityException) { notice = "No se pudo abrir la aplicación para esta acción"; false }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PageHeader("Cubacel", back)
        SectionTitle("Información por SMS")
        if (state.sims.isEmpty()) Text("No hay líneas disponibles para SMS", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else ChoiceField(state.sims.firstOrNull { it.id == selectedSim }?.label ?: "Elegir línea para SMS",
            state.sims.map { it.id.toString() to it.label }) { selectedSim = it.toIntOrNull() }
        TelecomActions.all.filter { it.kind == TelecomActionKind.SMS_INFO }.forEach { action ->
            ActionRow(action.title, R.drawable.ic_help, "SMS al ${action.destination} · ${action.body}") {
                val sim = selectedSim
                if (sim == null) { notice = "Elige la línea desde la que enviarás el SMS"; return@ActionRow }
                when (val prepared = controller.prepare(action.id, sim)) {
                    is TelecomSmsPrepare.Ready -> { review = prepared.draft; permissionRequestedFor = null; notice = null; records = controller.records() }
                    TelecomSmsPrepare.InactiveSim -> notice = "La línea seleccionada ya no está activa"
                    TelecomSmsPrepare.StorageFailed -> notice = "No se pudo guardar la solicitud. Inténtalo de nuevo"
                    TelecomSmsPrepare.InvalidAction -> notice = "Consulta no disponible"
                }
            }
        }
        HorizontalDivider()
        SectionTitle("Emergencias")
        TelecomActions.all.filter { it.kind == TelecomActionKind.DIALER }.forEach { action ->
            ActionRow(action.title, R.drawable.ic_smartphone, action.destination) { openExternal(action.id) }
        }
        notice?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        if (records.isNotEmpty()) {
            HorizontalDivider()
            SectionTitle("Consultas recientes")
            records.take(10).forEach { record ->
                val action = TelecomActions.find(record.draft.actionId)
                ListItem(headlineContent = { Text(action?.title ?: record.draft.body) },
                    supportingContent = { Text("${record.smsStatus()} · ${record.draft.destination} · SIM ${record.draft.subscriptionId}") },
                    leadingContent = { Icon(painterResource(R.drawable.ic_history), null) },
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent))
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    review?.let { draft ->
        ModalBottomSheet(onDismissRequest = { review = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(TelecomActions.find(draft.actionId)?.title ?: "Consulta por SMS", style = MaterialTheme.typography.headlineSmall)
                Detail("Desde", state.sims.firstOrNull { it.id == draft.subscriptionId }?.label ?: "SIM ${draft.subscriptionId}")
                Detail("Destino", draft.destination)
                Detail("Mensaje", draft.body)
                if (smsPermission) Primary("Enviar SMS") {
                    notice = when (controller.sendAfterReview(draft)) {
                        TelecomSmsSubmit.UNKNOWN -> null
                        TelecomSmsSubmit.PERMISSION_REQUIRED -> "Falta permiso para enviar SMS"
                        TelecomSmsSubmit.INACTIVE_SIM -> "La línea seleccionada ya no está activa"
                        TelecomSmsSubmit.STALE_REVIEW -> "Esta solicitud ya se intentó. Crea otra para volver a consultar"
                        TelecomSmsSubmit.STORAGE_FAILED -> "No se pudo guardar el intento. No se envió desde NeoTransfer"
                    }
                    records = controller.records()
                    if (controller.record(draft.id)?.status != TelecomSmsStatus.DRAFT) review = null
                } else {
                    Primary("Dar permiso para SMS") {
                        permissionRequestedFor = draft.id
                        requestSmsPermission()
                    }
                    if (permissionRequestedFor == draft.id) TextButton(onClick = {
                        if (openExternal(draft.actionId)) {
                            // The external messaging app owns sending and SIM choice; this draft has no send result here.
                            notice = null
                            review = null
                        }
                    }) { Text("Abrir en Mensajes") }
                }
                TextButton(onClick = { review = null }) { Text("Cancelar") }
            }
        }
    }
}

private fun TelecomSmsRecord.smsStatus(): String = when (status) {
    TelecomSmsStatus.DRAFT -> "Sin envío confirmado"
    TelecomSmsStatus.UNKNOWN -> "Resultado desconocido · sin reintento"
    TelecomSmsStatus.SENT -> "SMS enviado · respuesta pendiente"
    TelecomSmsStatus.FAILED -> "Falló el envío"
}
