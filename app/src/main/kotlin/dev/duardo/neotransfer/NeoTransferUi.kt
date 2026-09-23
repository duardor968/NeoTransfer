package dev.duardo.neotransfer

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import dev.duardo.neotransfer.core.*
import dev.duardo.neotransfer.platform.QrInput
import dev.duardo.neotransfer.platform.BankSmsRecord
import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class PickedContact(val name: String, val phone: String)

data class AppUiState(
    val unlocked: Boolean, val hasCredentials: Boolean, val bank: Bank,
    val subscription: Int, val sims: List<SimChoice>, val configuredBanks: Set<Bank>, val busy: Boolean,
    val accounts: List<AccountBalance>, val balanceAt: Instant?, val history: List<HistoryEntry>,
    val pending: PendingRecord?, val confirmed: BankMessage.TransferSent?, val recipients: List<Recipient>,
    val notice: String?, val permissions: Boolean, val cameraPermission: Boolean, val contact: PickedContact?,
    val uncertain: List<UncertainTransfer> = emptyList(),
)

class UiActions(
    val unlock: () -> Unit, val enroll: (Bank, CharArray, () -> Unit) -> Unit,
    val permissions: () -> Unit, val cameraPermission: () -> Unit,
    val selectBank: (Bank) -> Unit, val selectSim: (Int) -> Unit,
    val balance: () -> Unit, val refresh: () -> Unit, val pay: (MoneyAction) -> Unit,
    val saveRecipient: (Recipient) -> Unit, val resolvePending: () -> Unit,
    val dismissNotice: () -> Unit, val pickContact: () -> Unit, val clearContact: () -> Unit, val lock: () -> Unit,
    val resolveUncertain: (String, BankSmsRecord) -> Unit = { _, _ -> },
    val resetCredentials: () -> Unit = {},
    val protectScreen: (Boolean) -> Unit = {},
    val share: (String) -> Unit = {},
    val disconnectBank: () -> Unit = {},
)

private enum class Page { HOME, PAY, HISTORY, MESSAGES, SETTINGS, TRANSFER, RECHARGE, BILLS, SCAN, ENROLL }
private data class Draft(val destination: String = "", val amount: String = "", val phone: String = "",
                         val name: String = "", val save: Boolean = false, val source: String = "",
                         val currency: Currency = Currency.CUP, val electricity: Boolean = true,
                         val description: String = "")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeoTransferApp(state: AppUiState, actions: UiActions, initialQr: QrPayment? = null,
                   incomingPayment: String? = null, consumeIncomingPayment: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("appearance", Context.MODE_PRIVATE) }
    var darkOverride by remember { mutableStateOf(if (prefs.contains("dark")) prefs.getBoolean("dark", true) else null) }
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    var page by rememberSaveable { mutableStateOf(if (initialQr == null) Page.HOME else Page.SCAN) }
    val pageState = rememberSaveableStateHolder()
    var selectedReceipt by remember { mutableStateOf<MovementItem?>(null) }
    var draft by remember { mutableStateOf(initialQr?.let { Draft(amount = if (it.editableAmount) "" else it.amount.amount.toPlainString(),
        currency = it.amount.currency, description = it.description) } ?: Draft()) }
    var review by remember { mutableStateOf<MoneyAction?>(null) }
    var qr by remember { mutableStateOf(initialQr) }
    var resolve by remember { mutableStateOf(false) }
    var resolvingUncertain by remember { mutableStateOf<UncertainTransfer?>(null) }
    var linkingReceipt by remember { mutableStateOf<HistoryEntry?>(null) }
    var formError by remember { mutableStateOf<String?>(null) }
    var resetAccess by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.unlocked, incomingPayment) {
        if (!state.unlocked || incomingPayment == null) return@LaunchedEffect
        if (state.pending != null) {
            snackbar.showSnackbar("Revisa la operación pendiente antes de abrir otro pago")
        } else {
            runCatching { QrInput.parse(incomingPayment) }.onSuccess { input ->
                when (input) {
                    is QrInput.Card -> { draft = Draft(destination = input.card, phone = input.phone.orEmpty()); page = Page.TRANSFER }
                    is QrInput.Payment -> {
                        qr = input.value; page = Page.PAY
                        draft = Draft(amount = if (input.value.editableAmount) "" else input.value.amount.amount.toPlainString(),
                            currency = input.value.amount.currency, description = input.value.description)
                    }
                }
            }.onFailure { snackbar.showSnackbar("No se pudo abrir el enlace de pago") }
        }
        consumeIncomingPayment()
    }

    LaunchedEffect(state.notice) {
        state.notice?.let { snackbar.showSnackbar(it, actionLabel = "Cerrar", duration = SnackbarDuration.Long); actions.dismissNotice() }
    }
    LaunchedEffect(state.pending, state.confirmed) {
        if (state.pending != null || state.confirmed != null) { review = null; qr = null; page = Page.HOME }
    }
    LaunchedEffect(state.contact) {
        state.contact?.let { draft = draft.copy(name = it.name, phone = it.phone); actions.clearContact() }
    }
    fun go(target: Page) {
        if (target != page && target in listOf(Page.TRANSFER, Page.RECHARGE, Page.BILLS)) draft = Draft()
        page = target; formError = null
    }
    BackHandler(enabled = state.unlocked && page != Page.HOME && review == null && qr == null) { go(if (page == Page.MESSAGES) Page.HISTORY else Page.HOME) }
    SideEffect { actions.protectScreen(!state.unlocked || !state.hasCredentials || page == Page.ENROLL || containsAccessSecret(state.notice.orEmpty())) }

    NeoTheme(darkOverride ?: systemDark) {
        Scaffold(
            snackbarHost = { if (review == null && qr == null) SnackbarHost(snackbar) },
            bottomBar = {
                if (state.unlocked && page in listOf(Page.HOME, Page.PAY, Page.HISTORY)) {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        listOf(Triple(Page.HOME, "Inicio", R.drawable.ic_home), Triple(Page.PAY, "Pagar", R.drawable.ic_qr_code_scanner),
                            Triple(Page.HISTORY, "Movimientos", R.drawable.ic_history)).forEach { (target, text, icon) ->
                            NavigationBarItem(selected = page == target, onClick = { go(target) },
                                icon = { Icon(painterResource(icon), null) }, label = { Text(text) })
                        }
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
                when {
                    !state.unlocked && state.hasCredentials -> LockScreen(state.busy, actions.unlock) { resetAccess = true }
                    !state.hasCredentials || page == Page.ENROLL -> Enrollment(state, actions) { go(Page.HOME) }
                    else -> AnimatedContent(page, label = "Navegación") { destination ->
                        pageState.SaveableStateProvider(destination) {
                        when (destination) {
                            Page.HOME -> HomeScreen(state, actions, ::go, { selectedReceipt = it }, { resolve = true })
                            Page.HISTORY -> MovementsScreen(state, actions, { go(Page.MESSAGES) }, { resolvingUncertain = it }, { resolve = true })
                            Page.MESSAGES -> BankMessagesScreen(state.history, actions.refresh) { go(Page.HISTORY) }
                            Page.PAY -> Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                Text("Pagar", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                                ActionRow("Escanear QR", R.drawable.ic_qr_code_scanner) { go(Page.SCAN) }
                                ActionRow("Transferir", R.drawable.ic_swap_horiz) { draft = Draft(); go(Page.TRANSFER) }
                                ActionRow("Recargar móvil", R.drawable.ic_smartphone) { draft = Draft(); go(Page.RECHARGE) }
                                ActionRow("Pagar factura", R.drawable.ic_receipt_long) { draft = Draft(); go(Page.BILLS) }
                            }
                            Page.SETTINGS -> SettingsScreen(state, actions, darkOverride ?: systemDark,
                                { darkOverride = it; prefs.edit().putBoolean("dark", it).apply() },
                                { go(Page.ENROLL) }, { go(Page.HOME) })
                            Page.TRANSFER, Page.RECHARGE, Page.BILLS -> MoneyForm(destination, state, actions, draft,
                                { draft = it }, { go(Page.HOME) }, { value -> review = value })
                            Page.SCAN -> Column(Modifier.fillMaxSize()) {
                                PageHeader("Escanear QR") { go(Page.HOME) }
                                if (!state.cameraPermission) {
                                    EmptyAction("La cámara necesita permiso", "Permitir cámara", actions.cameraPermission)
                                } else QrCamera(Modifier.weight(1f).fillMaxWidth(), paused = qr != null,
                                    onCode = { code ->
                                        runCatching { QrInput.parse(code) }.onSuccess { result ->
                                            when (result) {
                                                is QrInput.Card -> {
                                                    draft = Draft(destination = result.card, phone = result.phone.orEmpty()); page = Page.TRANSFER
                                                }
                                                is QrInput.Payment -> {
                                                    qr = result.value
                                                    draft = Draft(amount = if (result.value.editableAmount) "" else result.value.amount.amount.toPlainString(),
                                                        currency = result.value.amount.currency, description = result.value.description)
                                                }
                                            }
                                            formError = null
                                        }.onFailure { formError = it.message ?: "QR no compatible" }
                                    }, onError = { formError = it })
                                formError?.let { Text(it, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error) }
                            }
                            Page.ENROLL -> Unit
                        }
                        }
                    }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }

        if (state.unlocked && qr != null) {
            val value = qr!!
            ModalBottomSheet(onDismissRequest = { qr = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Pago QR", style = MaterialTheme.typography.headlineMedium)
                    Detail("Código de proveedor", value.provider)
                    Detail("Referencia", value.transactionId)
                    Detail("Banco", state.bank.name)
                    Field("Importe · ${value.amount.currency}", draft.amount, { draft = draft.copy(amount = decimalInput(it, draft.amount)) }, KeyboardType.Decimal, readOnly = !value.editableAmount)
                    Field(value.descriptionHint.ifEmpty { "Descripción" }, draft.description,
                        { draft = draft.copy(description = it) }, readOnly = value.description.isNotEmpty())
                    Field("Móvil a notificar (opcional)", draft.phone, { draft = draft.copy(phone = digits(it, 8)) }, KeyboardType.Phone)
                    formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Primary("Pagar con huella", enabled = !state.busy && state.pending == null && validAmount(draft.amount) &&
                        (draft.phone.isEmpty() || draft.phone.matches(Regex("[0-9]{8}")))) {
                        val action = MoneyAction(ActionKind.QR, state.bank, value.provider, Money(draft.amount.toBigDecimal(), value.amount.currency),
                            phone = draft.phone.takeIf(String::isNotEmpty), qr = value, description = draft.description)
                        runCatching {
                            // Validate editable text and limits before biometrics, without a real PIN.
                            BankCommands().qrPayment(state.bank, CharArray(state.bank.pinLength) { '0' }, value, action.amount,
                                action.description, phone = action.phone, sequence = "000", seed = 0)
                        }.onSuccess { actions.pay(action) }.onFailure { formError = it.message }
                    }
                    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }

        if (state.unlocked && review != null) {
            val action = review!!
            ModalBottomSheet(onDismissRequest = { if (!state.busy) review = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("Revisar ${action.kind.label.lowercase()}", style = MaterialTheme.typography.headlineMedium)
                    Text(if (action.amount.amount.signum() == 0) "Factura completa" else formatMoney(action.amount),
                        style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                    Detail("${action.bank.name} · Origen", if (action.source == "0000") "Cuenta predeterminada" else action.source.chunked(4).joinToString(" "))
                    Detail(if (action.kind == ActionKind.TRANSFER) "Tarjeta de destino" else if (action.kind == ActionKind.RECHARGE) "Móvil" else "Factura",
                        if (action.kind == ActionKind.TRANSFER) action.destination.chunked(4).joinToString(" ") else action.destination)
                    action.phone?.let { Detail("Notificar al móvil", it) }
                    Primary(if (action.kind == ActionKind.TRANSFER) "Transferir con huella" else "Pagar con huella", !state.busy && state.pending == null) { actions.pay(action) }
                    state.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        if (state.unlocked && selectedReceipt != null) MovementDetail(selectedReceipt!!, state.recipients, actions.share) { selectedReceipt = null }
        if (state.unlocked && resolvingUncertain != null) {
            val operation = resolvingUncertain!!
            ModalBottomSheet(onDismissRequest = { resolvingUncertain = null; linkingReceipt = null }) {
                LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp)) {
                    item {
                        Text("Vincular comprobante", style = MaterialTheme.typography.headlineSmall)
                        Text("${formatMoney(operation.request.amount)} · ${readableAccount(operation.request.destination)}", Modifier.padding(top = 16.dp))
                        Text(dateText(operation.startedAt), style = MaterialTheme.typography.bodySmall)
                    }
                    val compatible = state.history.filter { operation.matches(it.message, it.record.receivedAt, it.record.subscriptionId) }
                    if (compatible.isEmpty()) item { Text("Todavía no hay un comprobante compatible", Modifier.padding(vertical = 24.dp)) }
                    items(compatible) { entry -> HistoryRow(entry) { linkingReceipt = entry } }
                }
            }
        }
        if (state.unlocked && linkingReceipt != null && resolvingUncertain != null) AlertDialog(
            onDismissRequest = { linkingReceipt = null }, title = { Text("¿Corresponde a esta operación?") },
            text = { Text(linkingReceipt!!.record.body, Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = {
                actions.resolveUncertain(resolvingUncertain!!.id, linkingReceipt!!.record)
                linkingReceipt = null; resolvingUncertain = null
            }) { Text("Vincular comprobante") } }, dismissButton = { TextButton(onClick = { linkingReceipt = null }) { Text("Volver") } })
        if (state.unlocked && resolve && state.pending != null) AlertDialog(onDismissRequest = { resolve = false },
            title = { Text("¿Ya comprobaste el resultado?") },
            text = { Text("Comprueba el SMS o el estado de tu cuenta. Cerrar esta revisión habilita nuevas operaciones; no cancela ni repite la anterior.") },
            confirmButton = { TextButton(onClick = { actions.resolvePending(); resolve = false }, enabled = !state.busy) { Text("Ya lo comprobé") } },
            dismissButton = { TextButton(onClick = { resolve = false }) { Text("Seguir esperando") } })
        if (resetAccess) AlertDialog(onDismissRequest = { resetAccess = false }, title = { Text("Restablecer acceso") },
            text = { Text("Se borrarán las claves guardadas en este teléfono. Las operaciones y los mensajes se conservarán.") },
            confirmButton = { TextButton(onClick = { actions.resetCredentials(); resetAccess = false }) { Text("Borrar claves") } },
            dismissButton = { TextButton(onClick = { resetAccess = false }) { Text("Cancelar") } })
    }
}

@Composable
private fun LockScreen(busy: Boolean, unlock: () -> Unit, reset: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
        BrandArtwork(R.drawable.nt_brand_textured, 112.dp)
        Spacer(Modifier.height(28.dp))
        Text("NeoTransfer", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(40.dp))
        Primary("Abrir con huella", !busy, unlock)
        TextButton(onClick = reset, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("Volver a configurar acceso", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Enrollment(state: AppUiState, actions: UiActions, back: () -> Unit) {
    var selected by remember(state.bank) { mutableStateOf(state.bank) }
    var pin by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if (state.unlocked) PageHeader("Configurar acceso", back)
        else {
            Spacer(Modifier.height(36.dp))
            BrandArtwork(R.drawable.nt_brand_textured, 104.dp)
            Text("NeoTransfer", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Text("Configurar acceso", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Bank.entries.forEach { bank -> FilterChip(selected == bank, { selected = bank; pin = ""; actions.selectBank(bank) }, label = { Text(bank.name) },
                enabled = state.pending == null || state.pending.action.bank == bank) }
        }
        if (!state.permissions) Primary("Permitir llamadas y SMS", onClick = actions.permissions)
        else if (state.sims.isEmpty()) Text("No hay una SIM disponible", color = MaterialTheme.colorScheme.error)
        else state.sims.forEach { sim ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(state.subscription == sim.id, role = Role.RadioButton, onClick = { actions.selectSim(sim.id) }), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(state.subscription == sim.id, onClick = null); Spacer(Modifier.width(12.dp)); Text(sim.label)
            }
        }
        OutlinedTextField(pin, { pin = digits(it, selected.pinLength) }, Modifier.fillMaxWidth(), label = { Text("Clave de ${selected.name}") },
            singleLine = true, shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            colors = OutlinedTextFieldDefaults.colors(focusedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                focusedBorderColor = MaterialTheme.colorScheme.onSecondaryContainer, cursorColor = MaterialTheme.colorScheme.onSecondaryContainer),
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(painterResource(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility), if (visible) "Ocultar clave" else "Mostrar clave") } })
        Primary("Guardar con huella", enabled = pin.length == selected.pinLength && pin.all { it in '0'..'9' } && !state.busy && state.permissions && state.sims.any { it.id == state.subscription }) {
            val value = pin.toCharArray(); pin = ""; actions.enroll(selected, value, back)
        }
    }
}

@Composable
private fun HomeScreen(state: AppUiState, actions: UiActions, go: (Page) -> Unit, receipt: (MovementItem) -> Unit, resolve: () -> Unit) {
    var hidden by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                BrandArtwork(R.drawable.nt_brand_textured, 44.dp, 3.dp)
                Spacer(Modifier.width(10.dp))
                Text("NeoTransfer", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { go(Page.SETTINGS) }) { Icon(painterResource(R.drawable.ic_settings), "Ajustes") }
            }
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.bank.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { hidden = !hidden }) { Icon(painterResource(if (hidden) R.drawable.ic_visibility_off else R.drawable.ic_visibility), if (hidden) "Mostrar saldo" else "Ocultar saldo") }
            }
            Text("Saldo disponible", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.accounts.isEmpty()) {
                Text("—", style = MaterialTheme.typography.displayMedium)
            } else state.accounts.forEachIndexed { index, account ->
                Text(if (hidden) "••••••" else amountText(account.available.amount),
                    style = if (index == 0) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(account.available.currency.name, account.label,
                    account.account?.let(::readableAccount) ?: if (account.label == null) "Cuenta predeterminada" else null).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(state.balanceAt?.let { "Actualizado ${dateText(it)}" } ?: "Sin saldo consultado", Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = if (state.permissions) actions.balance else actions.permissions, enabled = !state.busy) {
                    Text(if (state.permissions) "Consultar" else "Dar permiso", color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                QuickAction("QR", R.drawable.ic_qr_code_scanner) { go(Page.SCAN) }
                QuickAction("Transferir", R.drawable.ic_swap_horiz) { go(Page.TRANSFER) }
                QuickAction("Recargar", R.drawable.ic_smartphone) { go(Page.RECHARGE) }
                QuickAction("Facturas", R.drawable.ic_receipt_long) { go(Page.BILLS) }
            }
            Spacer(Modifier.height(32.dp))
        }
        if (state.pending != null) item {
            val pending = state.pending
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${pending.action.kind.label} pendiente", fontWeight = FontWeight.SemiBold)
                    Text(if (pending.action.amount.amount.signum() == 0) "Factura ${pending.action.destination}" else formatMoney(pending.action.amount))
                    Text("Sin comprobante identificado", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = resolve, enabled = !state.busy) { Text("Revisar resultado", color = MaterialTheme.colorScheme.onSurface) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (state.confirmed != null) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BrandArtwork(R.drawable.nt_payment_success_textured, 80.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Transferencia confirmada", style = MaterialTheme.typography.titleMedium)
                    Text("${formatMoney(state.confirmed.amount)} · ${state.confirmed.reference}", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Movimientos", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { go(Page.HISTORY) }) { Text("Ver todos", color = MaterialTheme.colorScheme.onSurface) }
            }
        }
        val movements = financialHistory(state.history).take(5)
        if (movements.isEmpty()) item { EmptyAction("Todavía no hay movimientos", "Mensajes del banco", { go(Page.MESSAGES) }) }
        items(movements, key = { it.key }) { entry -> MovementRow(entry, state.recipients, showDate = true) { receipt(entry) } }
    }
}

@Composable
private fun QuickAction(label: String, icon: Int, click: () -> Unit) {
    Column(Modifier.widthIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = click).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(25.dp), tint = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(Modifier.height(10.dp)); Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun HistoryRow(entry: HistoryEntry, click: () -> Unit) {
    val movement = FinancialMovement.from(entry.message) ?: return
    MovementRow(MovementItem(entry, movement), emptyList(), click = click)
}

@Composable
private fun MoneyForm(page: Page, state: AppUiState, actions: UiActions, draft: Draft, change: (Draft) -> Unit, back: () -> Unit, review: (MoneyAction) -> Unit) {
    var agenda by remember { mutableStateOf(false) }
    var explicitSource by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val transfer = page == Page.TRANSFER
    val recharge = page == Page.RECHARGE
    val title = if (transfer) "Transferir" else if (recharge) "Recargar móvil" else "Pagar factura"
    val fullBill = page == Page.BILLS && draft.electricity
    val bankCurrency = if (state.bank == Bank.BANDEC) sourceCurrency(state.accounts, if (explicitSource) draft.source else "0000") else draft.currency
    val validDestination = draft.destination.matches(when {
        transfer -> Regex("[0-9]{16}")
        recharge -> Regex("[0-9]{8}|[0-9]{10}")
        draft.electricity -> Regex("[0-9]{11}|[0-9]{13}")
        else -> Regex("[0-9]{14,15}")
    })
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PageHeader(title, back)
        Text(state.bank.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (page == Page.BILLS) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilterChip(draft.electricity, { change(draft.copy(electricity = true, destination = "")) }, label = { Text("Electricidad") })
            FilterChip(!draft.electricity, { change(draft.copy(electricity = false, destination = "")) }, label = { Text("Teléfono") })
        }
        if (transfer && state.recipients.isNotEmpty()) TextButton(onClick = { agenda = !agenda }) { Text("Destinatarios guardados", color = MaterialTheme.colorScheme.onSurface) }
        if (agenda) state.recipients.forEach { recipient ->
            ActionRow("${recipient.name} · ${recipient.card.takeLast(4)}", R.drawable.ic_account_balance) {
                change(draft.copy(destination = recipient.card, name = recipient.name, phone = recipient.phone.orEmpty())); agenda = false
            }
        }
        Field(if (transfer) "Tarjeta de destino" else if (recharge) "Móvil" else "Identificador de factura", draft.destination,
            { change(draft.copy(destination = digits(it, if (transfer) 16 else if (recharge) 10 else 15))) }, KeyboardType.Number)
        if (transfer && state.bank == Bank.BPA) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(Currency.CUP, Currency.USD).forEach { currency ->
                FilterChip(draft.currency == currency, { change(draft.copy(currency = currency)) }, label = { Text(currency.name) })
            }
        }
        if (!fullBill) Field(if (transfer && bankCurrency == null) "Importe" else "Importe · ${if (transfer) bankCurrency else Currency.CUP}", draft.amount,
            { change(draft.copy(amount = decimalInput(it, draft.amount))) }, KeyboardType.Decimal)
        else Detail("Importe", "Factura completa")
        if (transfer) {
            Field("Móvil a notificar (opcional)", draft.phone, { change(draft.copy(phone = digits(it, 8))) }, KeyboardType.Phone)
            TextButton(onClick = actions.pickContact) { Text("Elegir contacto", color = MaterialTheme.colorScheme.onSurface) }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(draft.save, role = Role.Checkbox, onValueChange = { change(draft.copy(save = it)) }), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(draft.save, onCheckedChange = null); Spacer(Modifier.width(12.dp)); Text("Guardar destinatario")
            }
            if (draft.save) Field("Nombre del destinatario", draft.name, { change(draft.copy(name = it)) })
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(explicitSource, role = Role.Switch, onValueChange = { explicitSource = it; change(draft.copy(source = "")) }), verticalAlignment = Alignment.CenterVertically) {
            Switch(explicitSource, onCheckedChange = null); Spacer(Modifier.width(12.dp)); Text("Usar otra tarjeta de origen")
        }
        if (explicitSource) Field("Tarjeta de origen", draft.source, { change(draft.copy(source = digits(it, 16))) }, KeyboardType.Number)
        if (transfer && bankCurrency == null) EmptyAction("Falta la moneda de la cuenta de origen", "Consultar saldo", actions.balance)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.pending != null) Text("Hay una operación pendiente. Revisa su resultado en Inicio.", color = MaterialTheme.colorScheme.error)
        Primary("Revisar ${if (transfer) "transferencia" else "pago"}", enabled = !state.busy && state.pending == null && validDestination &&
            (fullBill || validAmount(draft.amount)) && (!explicitSource || draft.source.matches(Regex("[0-9]{16}"))) &&
            (draft.phone.isEmpty() || draft.phone.matches(Regex("[0-9]{8}"))) && (!draft.save || draft.name.isNotBlank()) && (!transfer || bankCurrency != null)) {
            runCatching {
                val amount = Money(if (fullBill) BigDecimal.ZERO else draft.amount.toBigDecimal(), if (transfer) requireNotNull(bankCurrency) else Currency.CUP)
                val kind = if (transfer) ActionKind.TRANSFER else if (recharge) ActionKind.RECHARGE else if (draft.electricity) ActionKind.ELECTRICITY else ActionKind.TELEPHONE
                val action = MoneyAction(kind, state.bank, draft.destination, amount, if (explicitSource) draft.source else "0000", draft.phone.takeIf(String::isNotEmpty))
                if (transfer) BankCommands().transfer(action.transferRequest(), 0)
                if (recharge) require(amount.amount >= BigDecimal.ONE) { "La recarga mínima es 1 CUP" }
                if (transfer && draft.save) actions.saveRecipient(Recipient(draft.name.trim(), draft.destination, action.phone))
                review(action)
            }.onFailure { error = it.message ?: "Revisa los datos" }
        }
    }
}

@Composable
private fun SettingsScreen(state: AppUiState, actions: UiActions, dark: Boolean, setDark: (Boolean) -> Unit, enroll: () -> Unit, back: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PageHeader("Ajustes", back)
        Text("Banco", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Bank.entries.forEach { bank -> FilterChip(state.bank == bank, { actions.selectBank(bank) }, label = { Text(bank.name) }, enabled = state.pending == null && !state.busy) }
        }
        TextButton(onClick = enroll, enabled = state.pending == null && !state.busy) {
            Text(if (state.configuredBanks.contains(state.bank)) "Actualizar clave" else "Configurar ${state.bank.name}", color = MaterialTheme.colorScheme.onSurface)
        }
        Text("SIM", style = MaterialTheme.typography.titleMedium)
        state.sims.forEach { sim -> Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(state.subscription == sim.id,
            enabled = state.pending == null && !state.busy, role = Role.RadioButton, onClick = { actions.selectSim(sim.id) }), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(state.subscription == sim.id, onClick = null, enabled = state.pending == null && !state.busy); Spacer(Modifier.width(12.dp)); Text(sim.label)
        } }
        if (!state.permissions) TextButton(onClick = actions.permissions) { Text("Permitir llamadas y SMS") }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(dark, role = Role.Switch, onValueChange = setDark), verticalAlignment = Alignment.CenterVertically) {
            Text("Tema oscuro", Modifier.weight(1f)); Switch(dark, onCheckedChange = null)
        }
        TextButton(onClick = actions.lock) { Text("Bloquear NeoTransfer", color = MaterialTheme.colorScheme.onSurface) }
        TextButton(onClick = actions.disconnectBank, enabled = state.pending == null && !state.busy) {
            Text("Cerrar sesión bancaria", color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ActionRow(label: String, icon: Int, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = click).padding(vertical = 18.dp, horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(icon), null, Modifier.size(28.dp)); Spacer(Modifier.width(20.dp)); Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PageHeader(title: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_arrow_back), "Volver") }
        Spacer(Modifier.width(8.dp)); Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BrandArtwork(resource: Int, size: Dp, padding: Dp = 10.dp) {
    // Keep the approved pixels intact. Graphite preserves contrast in either theme.
    Box(Modifier.size(size).background(Color(0xFF0E0F12), RoundedCornerShape(if (size < 60.dp) 12.dp else 20.dp)).padding(padding)) {
        Image(painterResource(resource), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}


@Composable
private fun Field(label: String, value: String, change: (String) -> Unit, type: KeyboardType = KeyboardType.Text, readOnly: Boolean = false) {
    OutlinedTextField(value, change, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        readOnly = readOnly, keyboardOptions = KeyboardOptions(keyboardType = type), shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(focusedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            focusedBorderColor = MaterialTheme.colorScheme.onSecondaryContainer, cursorColor = MaterialTheme.colorScheme.onSecondaryContainer))
}

@Composable
private fun TextButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = modifier, enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface), content = content)
}

@Composable
private fun Primary(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().heightIn(min = 54.dp), enabled = enabled, shape = RoundedCornerShape(16.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(vertical = 6.dp))
    }
}

@Composable
private fun EmptyAction(text: String, label: String, click: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = click) { Text(label, color = MaterialTheme.colorScheme.onSurface) }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

// Retain one excess character so an overlong pasted account cannot silently become a valid different account.
private fun digits(text: String, max: Int) = text.filterNot { it.isWhitespace() || it == '-' }.take(max + 1)
private fun decimalInput(text: String, previous: String): String = text.replace(',', '.').let {
    if (it.matches(Regex("[0-9]{0,12}(?:\\.[0-9]{0,2})?"))) it else previous
}
private fun validAmount(text: String) = text.matches(Regex("[0-9]+(?:\\.[0-9]{1,2})?")) && text.toBigDecimalOrNull()?.signum() == 1
internal fun amountText(amount: BigDecimal): String = NumberFormat.getNumberInstance(Locale.forLanguageTag("es-CU")).apply {
    minimumFractionDigits = 2; maximumFractionDigits = 2
}.format(amount)
internal fun formatMoney(money: Money) = "${amountText(money.amount)} ${money.currency}"
internal fun dateText(time: Instant) = DateTimeFormatter.ofPattern("d MMM · HH:mm", Locale.forLanguageTag("es"))
    .withZone(ZoneId.systemDefault()).format(time)
