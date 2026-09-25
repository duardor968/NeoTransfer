package dev.duardo.neotransfer.backup

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.duardo.neotransfer.NeoTheme

/** Small modal surface; the Activity owns BackupFlow and its early SAF registration. */
@Composable
fun BackupUi(flow: BackupFlow) {
    if (flow.state == BackupFlowState.Idle) return
    val context = LocalContext.current
    val appearance = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    val theme = appearance.getString("theme", null)?.let { runCatching { BackupTheme.valueOf(it) }.getOrNull() }
        ?: if (appearance.contains("dark")) if (appearance.getBoolean("dark", true)) BackupTheme.DARK else BackupTheme.LIGHT
        else BackupTheme.SYSTEM
    val dark = when (theme) {
        BackupTheme.SYSTEM -> isSystemInDarkTheme()
        BackupTheme.LIGHT -> false
        BackupTheme.DARK -> true
    }
    NeoTheme(dark) { BackupDialogs(flow) }
}

@Composable
private fun BackupDialogs(flow: BackupFlow) {
    val state = flow.state
    var password by remember(state::class) { mutableStateOf("") }
    var repeated by remember(state::class) { mutableStateOf("") }
    var localError by remember(state::class) { mutableStateOf<String?>(null) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { password = ""; repeated = "" }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); password = ""; repeated = "" }
    }
    fun submit() {
        if (state is BackupFlowState.ExportPassword && password != repeated) {
            localError = "Las contraseñas no coinciden"
            return
        }
        val chars = password.toCharArray()
        password = ""
        repeated = ""
        flow.submitPassword(chars)
    }

    when (state) {
        BackupFlowState.Idle -> Unit
        is BackupFlowState.ExportPassword, is BackupFlowState.ImportPassword -> {
            val exporting = state is BackupFlowState.ExportPassword
            val message = localError ?: if (exporting) (state as BackupFlowState.ExportPassword).error
                else (state as BackupFlowState.ImportPassword).error
            AlertDialog(
                properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
                onDismissRequest = { password = ""; flow.cancel() },
                title = { Text(if (exporting) "Exportar respaldo" else "Abrir respaldo") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(password, { password = it.take(1_024); localError = null }, singleLine = true,
                            label = { Text("Contraseña") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            isError = message != null)
                        if (exporting) OutlinedTextField(repeated, { repeated = it.take(1_024); localError = null },
                            singleLine = true, label = { Text("Repetir contraseña") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            isError = localError != null)
                        if (message != null) Text(message, color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = { TextButton(onClick = ::submit) { Text(if (exporting) "Crear respaldo" else "Revisar datos") } },
                dismissButton = { TextButton(onClick = { password = ""; flow.cancel() }) { Text("Cancelar") } },
            )
        }
        is BackupFlowState.Working -> AlertDialog(
            onDismissRequest = {},
            title = { Text(state.label) },
            text = { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } },
            confirmButton = {},
        )
        is BackupFlowState.OwnPreview -> AlertDialog(
            onDismissRequest = flow::cancel,
            title = { Text("Revisar respaldo") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${state.snapshot.registrations.size} accesos bancarios")
                    Text("${state.snapshot.accounts.size} cuentas · ${state.snapshot.cards.size} tarjetas")
                    Text("${state.snapshot.contacts.size} contactos · ${state.snapshot.services.size} servicios")
                    Text("${state.snapshot.operations.size} operaciones · ${state.snapshot.receipts.size} comprobantes")
                    Text(if (state.hasCredentials) "Claves incluidas" else "Sin claves")
                }
            },
            confirmButton = { TextButton(onClick = flow::confirmPreview) { Text("Importar datos") } },
            dismissButton = { TextButton(onClick = flow::cancel) { Text("Cancelar") } },
        )
        is BackupFlowState.TrmPreviewState -> AlertDialog(
            onDismissRequest = flow::cancel,
            title = { Text("Importar Transfermóvil") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.source.sourceVersion?.let { Text("Transfermóvil $it") }
                    state.source.exportedAt?.let { Text("Exportado: $it") }
                    state.mapping.importedByKind.forEach { (kind, count) -> Text("${kind.label()}: $count") }
                    if (state.mapping.omittedByKind.isNotEmpty()) {
                        Text("No compatibles: ${state.mapping.omittedByKind.values.sum()}")
                    }
                    if (state.source.skippedSensitive.isNotEmpty()) Text("Claves de acceso antiguas excluidas: ${state.source.skippedSensitive.values.sum()}")
                    if (state.source.unsupported.isNotEmpty()) Text("Tablas no reconocidas: ${state.source.unsupported.values.sum()}")
                    if (state.mapping.snapshot.registrations.isNotEmpty()) Text("Los accesos bancarios requieren reasociación")
                }
            },
            confirmButton = { TextButton(onClick = flow::confirmPreview) { Text("Importar datos") } },
            dismissButton = { TextButton(onClick = flow::cancel) { Text("Cancelar") } },
        )
        is BackupFlowState.Exported -> AlertDialog(
            onDismissRequest = flow::cancel,
            title = { Text("Respaldo guardado") },
            confirmButton = { TextButton(onClick = flow::cancel) { Text("Cerrar") } },
        )
        is BackupFlowState.Completed -> AlertDialog(
            onDismissRequest = flow::cancel,
            title = { Text("Datos importados") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${state.imported} registros nuevos")
                    if (state.conflicts.isNotEmpty()) Text("${state.conflicts.size} conflictos sin reemplazar")
                    if (state.pendingAccesses > 0) Text("${state.pendingAccesses} accesos requieren reasociación")
                }
            },
            confirmButton = { TextButton(onClick = flow::cancel) { Text("Cerrar") } },
        )
        is BackupFlowState.AccessesPending -> AlertDialog(
            onDismissRequest = flow::cancel,
            title = { Text("Datos importados; accesos pendientes") },
            text = { Text("${state.pendingAccesses} accesos requieren completar la autorización") },
            confirmButton = { TextButton(onClick = flow::confirmPreview) { Text("Completar accesos") } },
            dismissButton = { TextButton(onClick = flow::cancel) { Text("Cerrar") } },
        )
        is BackupFlowState.Failed -> AlertDialog(
            onDismissRequest = flow::cancel,
            title = { Text("No se pudo completar") },
            text = { Text(state.message) },
            confirmButton = { TextButton(onClick = flow::cancel) { Text("Cerrar") } },
        )
    }
}

private fun TrmRecordKind.label(): String = when (this) {
    TrmRecordKind.CONTACT -> "Contactos"
    TrmRecordKind.RECIPIENT_ACCOUNT -> "Cuentas destinatarias"
    TrmRecordKind.OWN_ACCOUNT -> "Cuentas propias"
    TrmRecordKind.BILL -> "Facturas"
    TrmRecordKind.PREPAID_CARD -> "Tarjetas propias de recarga"
    TrmRecordKind.MOBILE -> "Móviles"
    TrmRecordKind.NAUTA -> "Nauta"
    TrmRecordKind.RECHARGE_CODE -> "Códigos de recarga"
    TrmRecordKind.BANK_MESSAGE -> "Mensajes bancarios"
    TrmRecordKind.PUBLIC_SERVICE -> "Servicios públicos"
    TrmRecordKind.LANDLINE -> "Teléfonos fijos"
    TrmRecordKind.RECEIPT -> "Comprobantes"
}
