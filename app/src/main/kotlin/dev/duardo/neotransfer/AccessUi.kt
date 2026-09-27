package dev.duardo.neotransfer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.core.*

internal enum class ThemePreference(val title: String) { SYSTEM("Sistema"), LIGHT("Claro"), DARK("Oscuro") }

@Composable
internal fun LockScreen(busy: Boolean, recoveryAvailable: Boolean, unlock: () -> Unit, reset: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            BrandArtwork(R.drawable.nt_brand_textured, 88.dp)
            Text("NeoTransfer bloqueada", style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (recoveryAvailable) TextButton(onClick = reset, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
                Text("Restablecer acceso")
            }
            TextButton(onClick = unlock, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
                Text("Desbloquear")
            }
        }
    }
}

@Composable
internal fun BanksScreen(state: AppUiState, actions: UiActions, back: () -> Unit, configure: (ProviderIdentity) -> Unit) {
    val identities = authenticationProviders()
    var removeId by remember { mutableStateOf<String?>(null) }
    var reactivateId by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
        item { PageHeader("Bancos y accesos", back) { IconButton(onClick = { configure(ProviderIdentity.forBank(state.bank)) }) { Icon(painterResource(R.drawable.ic_add), "Añadir acceso") } } }
        items(identities, key = { it.id }) { identity ->
            val registrations = state.wallet.registrations.filter { it.identity() == identity }
            if (registrations.isEmpty()) {
                ActionRow(identity.title(), R.drawable.ic_account_balance,
                    if (hasAccess(state, identity)) "Acceso guardado" else "Añadir acceso") { configure(identity) }
            } else registrations.forEach { registration ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        val configured = registration.id in state.configuredRegistrationIds
                        ActionRow(registration.label.ifBlank { identity.title() }, R.drawable.ic_account_balance,
                            listOf(registrationLineLabel(registration, state) ?: "Línea sin asignar", if (configured) "Acceso guardado" else "Configurar acceso").joinToString(" · ")) {
                            if (!registration.enabled || registration.subscriptionId == null || state.sims.none { it.id == registration.subscriptionId }) {
                                reactivateId = registration.id
                            } else { actions.selectRegistration(registration.id); configure(identity) }
                        }
                    }
                    IconButton(onClick = { removeId = registration.id }, enabled = !state.busy && state.pending == null) {
                        Icon(painterResource(R.drawable.ic_delete), "Quitar acceso de ${identity.title()}")
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    reactivateId?.let { id -> RegistrationLineDialog(state, actions, id) { reactivateId = null } }
    if (removeId != null) AlertDialog(onDismissRequest = { removeId = null }, title = { Text("Quitar acceso de este teléfono") },
        text = { Text("Se quitarán el acceso y los productos asociados de tu cartera. Su registro bancario no se cancela.") },
        confirmButton = { TextButton(onClick = { actions.removeRegistration(requireNotNull(removeId)); removeId = null }) { Text("Quitar acceso") } },
        dismissButton = { TextButton(onClick = { removeId = null }) { Text("Cancelar") } })
}

@Composable
internal fun RegistrationLineDialog(state: AppUiState, actions: UiActions, id: String, dismiss: () -> Unit) {
    var selectedLine by remember(id) { mutableStateOf<Int?>(null) }
    var reactivating by remember(id) { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    val registration = state.wallet.registrations.firstOrNull { it.id == id }
    val lineAvailable = registration != null && state.sims.any { it.id == selectedLine } && state.wallet.registrations.none {
        it.id != id && it.identity() == registration.identity() && it.subscriptionId == selectedLine
    }
    LaunchedEffect(state.wallet.registrations, reactivating) {
        if (reactivating && registration?.enabled == true && registration.subscriptionId == selectedLine) dismiss()
    }
    LaunchedEffect(state.notice) { if (reactivating && state.notice != null) { reactivating = false; error = state.notice } }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Asignar línea") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            registration?.let { Text(registrationLabel(it, state)) }
            if (!state.permissions) TextButton(onClick = actions.permissions) { Text("Permitir acceso telefónico") }
            else if (state.sims.isEmpty()) Text("No hay una SIM disponible")
            state.sims.forEach { sim ->
                val occupied = registration != null && state.wallet.registrations.any {
                    it.id != id && it.identity() == registration.identity() && it.subscriptionId == sim.id
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selectedLine == sim.id, enabled = !occupied && !reactivating,
                    role = Role.RadioButton, onClick = { selectedLine = sim.id }), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selectedLine == sim.id, null, enabled = !occupied && !reactivating)
                    Text(sim.label + if (occupied) " · Acceso existente" else "", Modifier.padding(start = 12.dp))
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton(onClick = {
            actions.dismissNotice(); reactivating = true; actions.reassociateRegistration(id, requireNotNull(selectedLine))
        }, enabled = !state.busy && !reactivating && lineAvailable && state.permissions) { Text("Reactivar") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } })
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Enrollment(state: AppUiState, actions: UiActions, initial: ProviderIdentity?, back: () -> Unit, registration: (OperationSpec) -> Unit) {
    val identities = authenticationProviders()
    var identity by remember(initial) { mutableStateOf(initial?.accessIdentity()?.takeIf { it in identities } ?: ProviderIdentity.forBank(state.bank)) }
    var pin by remember { mutableStateOf("") }
    val bank = identity.bank
    val pinLength = bank?.pinLength ?: 4
    val registrationSpec = state.services.firstOrNull { it.supports(identity) && it.implementationStatus == ImplementationStatus.IMPLEMENTED &&
        (it.id.endsWith(".register") || it.id.endsWith(".registration")) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (state.hasCredentials) PageHeader("Guardar acceso", back)
        else {
            Spacer(Modifier.height(24.dp)); BrandArtwork(R.drawable.nt_brand_textured, 104.dp)
            Text("NeoTransfer", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text("Añadir acceso", style = MaterialTheme.typography.titleLarge)
        }
        ChoiceField(identity.title(), identities.map { it.id to it.title() }) { id ->
            identity = identities.first { it.id == id }; pin = ""
            identity.bank?.let(actions.selectBank)
        }
        SectionTitle("Línea registrada")
        if (!state.permissions) Primary("Permitir acceso telefónico", onClick = actions.permissions)
        else if (state.sims.isEmpty()) Text("No hay una SIM disponible", color = MaterialTheme.colorScheme.error)
        else state.sims.forEach { sim ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(state.subscription == sim.id, role = Role.RadioButton,
                enabled = !state.busy && state.pending == null, onClick = { actions.selectSim(sim.id) }), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(state.subscription == sim.id, null); Spacer(Modifier.width(12.dp)); Text(sim.label)
            }
        }
        Field("Clave de ${identity.title()}", pin, { pin = digits(it, pinLength) }, KeyboardType.NumberPassword, secret = true)
        Primary("Guardar", enabled = pin.length == pinLength && pin.all(Char::isDigit) &&
            !state.busy && state.permissions && state.sims.any { it.id == state.subscription }) {
            val value = pin.toCharArray(); pin = ""
            actions.enrollProvider(identity, value, back)
        }
        registrationSpec?.let { TextButton(onClick = { registration(it) }) { Text("Crear registro de ${identity.title()}") } }
    }
}

@Composable
internal fun SettingsScreen(state: AppUiState, actions: UiActions, theme: ThemePreference, setTheme: (ThemePreference) -> Unit,
                            back: () -> Unit, banks: () -> Unit) {
    var help by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageHeader("Ajustes", back)
        SectionTitle("Apariencia")
        ThemePreference.entries.forEach { item ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(theme == item, role = Role.RadioButton, onClick = { setTheme(item) }), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(theme == item, null); Spacer(Modifier.width(12.dp)); Text(item.title)
            }
        }
        SectionTitle("Seguridad")
        ActionRow("Bloquear NeoTransfer", R.drawable.ic_security, "Bloqueo automático tras 1 minuto en segundo plano", click = actions.lock)
        ActionRow("Bancos y accesos", R.drawable.ic_account_balance, click = banks)
        ActionRow("Cerrar sesión bancaria", R.drawable.ic_close, enabled = state.pending == null && !state.busy, click = actions.disconnectBank)
        SectionTitle("Notificaciones")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Transferencias recibidas"); Text("Privacidad según los ajustes de Android", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Switch(state.notificationsEnabled, actions.setNotifications)
        }
        if (!state.permissions) ActionRow("Permisos de operaciones", R.drawable.ic_security, click = actions.permissions)
        SectionTitle("Datos")
        ActionRow("Crear respaldo cifrado", R.drawable.ic_upload, click = actions.exportBackup)
        ActionRow("Restaurar respaldo", R.drawable.ic_download, click = actions.importBackup)
        ActionRow("Importar de Transfermóvil", R.drawable.ic_download, click = actions.importTransfermovil)
        ActionRow("Ayuda", R.drawable.ic_help) { help = true }
    }
    if (help) AlertDialog(onDismissRequest = { help = false }, title = { Text("NeoTransfer") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Las operaciones necesitan cobertura de la línea registrada. Los resultados aparecen en Actividad.")
            Text("La clave de un respaldo es necesaria para restaurarlo. NeoTransfer no puede recuperarla.")
        } }, confirmButton = { TextButton(onClick = { help = false }) { Text("Cerrar ayuda") } })
}
