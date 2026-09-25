package dev.duardo.neotransfer

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.duardo.neotransfer.core.*
import dev.duardo.neotransfer.data.*
import dev.duardo.neotransfer.platform.QrInput
import dev.duardo.neotransfer.platform.BankSmsRecord
import dev.duardo.neotransfer.platform.TelecomSmsController
import dev.duardo.neotransfer.fuel.FuelController
import java.time.Instant

data class PickedContact(val name: String, val phone: String)

data class AppUiState(
    val unlocked: Boolean, val hasCredentials: Boolean, val bank: Bank,
    val subscription: Int, val sims: List<SimChoice>, val configuredBanks: Set<Bank>, val busy: Boolean,
    val accounts: List<AccountBalance>, val balanceAt: Instant?, val history: List<HistoryEntry>,
    val pending: PendingRecord?, val confirmed: BankMessage.TransferSent?, val recipients: List<Recipient>,
    val notice: String?, val permissions: Boolean, val cameraPermission: Boolean, val contact: PickedContact?,
    val uncertain: List<UncertainTransfer> = emptyList(),
    val wallet: WalletSnapshot = WalletSnapshot(),
    val selectedProductId: String? = null,
    val configuredRegistrationIds: Set<String> = emptySet(),
    val notificationsEnabled: Boolean = true,
    val services: List<OperationSpec> = BankingOperations.all,
    val serviceResult: String? = null,
)

class UiActions(
    val unlock: () -> Unit, val enroll: (Bank, CharArray, () -> Unit) -> Unit,
    val permissions: () -> Unit, val cameraPermission: () -> Unit,
    val selectBank: (Bank) -> Unit, val selectSim: (Int) -> Unit,
    val balance: () -> Unit, val refresh: () -> Unit, val pay: (MoneyAction) -> Unit,
    val saveRecipient: (Recipient) -> Unit, val resolvePending: () -> Unit,
    val dismissNotice: () -> Unit, val pickContact: () -> Unit, val clearContact: () -> Unit, val lock: () -> Unit,
    val resolveUncertain: (String, BankSmsRecord) -> Unit = { _, _ -> },
    val resetCredentials: () -> Unit = {}, val protectScreen: (Boolean) -> Unit = {},
    val share: (String) -> Unit = {}, val disconnectBank: () -> Unit = {},
    val selectProduct: (String) -> Unit = {},
    val balanceProduct: (String) -> Unit = { balance() },
    val selectRegistration: (String) -> Unit = {},
    val saveCard: (CardRecord) -> Unit = {}, val deleteCard: (String) -> Unit = {},
    val saveAccount: (AccountRecord) -> Unit = {}, val deleteAccount: (String) -> Unit = {},
    val saveContact: (ContactRecord) -> Unit = {}, val deleteContact: (String) -> Unit = {},
    val saveService: (SavedServiceRecord) -> Unit = {}, val deleteService: (String) -> Unit = {},
    val acknowledgeOperation: (String) -> Unit = {},
    val removeRegistration: (String) -> Unit = {},
    val reassociateRegistration: (String, Int) -> Unit = { _, _ -> },
    val enrollProvider: (ProviderIdentity, CharArray, () -> Unit) -> Unit = { identity, pin, after ->
        identity.bank?.let { enroll(it, pin, after) } ?: pin.fill('\u0000')
    },
    val setNotifications: (Boolean) -> Unit = {},
    val exportBackup: () -> Unit = {}, val importBackup: () -> Unit = {},
    val importTransfermovil: () -> Unit = {}, val importContacts: () -> Unit = {},
    val runService: (ServiceRequest) -> Unit = {},
    val runQrService: (ServiceRequest, QrPayment) -> Unit = { _, _ -> },
    val validateService: (ServiceRequest) -> List<OperationValidationError> = { emptyList() },
    val clearServiceResult: () -> Unit = {},
    val requestSmsPermission: () -> Unit = {},
    val renameFuel: (String, String) -> Unit = { _, _ -> },
    val archiveFuel: (String, Boolean) -> Unit = { _, _ -> },
    val authorizeFuel: (String, String, (Boolean) -> Unit) -> Unit = { _, _, result -> result(false) },
)

private enum class Page { HOME, ACTIVITY, CONTACTS, SERVICES, SETTINGS, WALLET, BANKS, PRODUCT_EDIT, CONTACT_DETAIL, CONTACT_EDIT, TRANSFER, RECHARGE, BILLS, SCAN, ENROLL, SERVICE_FORM, RECEIVE, STATISTICS, TELECOM_INFO, MITURNO, FUEL }
private val roots = listOf(Page.HOME, Page.ACTIVITY, Page.CONTACTS, Page.SERVICES)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NeoTransferApp(state: AppUiState, actions: UiActions, initialQr: QrPayment? = null,
                   incomingPayment: String? = null, consumeIncomingPayment: () -> Unit = {},
                   incomingReceiptId: String? = null, consumeIncomingReceipt: () -> Unit = {},
                   telecomController: TelecomSmsController? = null, smsPermission: Boolean = false,
                   fuelController: FuelController? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("appearance", Context.MODE_PRIVATE) }
    val persistedTheme = prefs.getString("theme", null)
    var theme by remember(persistedTheme) { mutableStateOf(persistedTheme?.let { runCatching { ThemePreference.valueOf(it) }.getOrNull() }
        ?: if (prefs.contains("dark")) if (prefs.getBoolean("dark", true)) ThemePreference.DARK else ThemePreference.LIGHT else ThemePreference.SYSTEM) }
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    var stack by rememberSaveable { mutableStateOf(listOf(Page.HOME.name)) }
    val page = Page.valueOf(stack.last())
    val savedState = rememberSaveableStateHolder()
    var selectedReceipt by remember { mutableStateOf<MovementItem?>(null) }
    var draft by remember { mutableStateOf(PaymentDraft()) }
    var review by remember { mutableStateOf<MoneyAction?>(null) }
    var qr by remember { mutableStateOf(initialQr) }
    var resolve by remember { mutableStateOf(false) }
    var resolvingUncertain by remember { mutableStateOf<UncertainTransfer?>(null) }
    var linkingReceipt by remember { mutableStateOf<HistoryEntry?>(null) }
    var resetAccess by remember { mutableStateOf(false) }
    var selectedContactId by rememberSaveable { mutableStateOf<String?>(null) }
    var editedProductId by rememberSaveable { mutableStateOf<String?>(null) }
    var enrollIdentity by remember { mutableStateOf<ProviderIdentity?>(null) }
    var selectedServiceId by rememberSaveable { mutableStateOf<String?>(null) }
    var serviceInitialValues by remember { mutableStateOf(emptyMap<String, String>()) }
    var serviceInitialRegistrationId by remember { mutableStateOf<String?>(null) }
    var fuelSensitive by remember { mutableStateOf(false) }
    var serviceChoices by remember { mutableStateOf(emptyList<OperationSpec>()) }
    var serviceChoiceDestination by remember { mutableStateOf("") }
    var serviceChoicePhone by remember { mutableStateOf("") }
    var scannerMode by remember { mutableStateOf(ScanMode.QR) }
    var scanTarget by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val snackbar = remember { SnackbarHostState() }

    fun back() { if (stack.size > 1) stack = stack.dropLast(1) else stack = listOf(Page.HOME.name) }
    fun go(target: Page) {
        if (target in roots) stack = listOf(target.name)
        else if (target != page) stack = stack + target.name
    }
    fun configure(identity: ProviderIdentity) { enrollIdentity = identity; identity.bank?.let(actions.selectBank); go(Page.ENROLL) }
    fun editProduct(product: WalletProductUi?) { editedProductId = product?.id; go(Page.PRODUCT_EDIT) }
    fun openService(spec: OperationSpec) { selectedServiceId = spec.id; serviceInitialValues = emptyMap(); serviceInitialRegistrationId = null; go(Page.SERVICE_FORM) }
    fun openPaymentService(spec: OperationSpec, destination: String, phone: String = "") {
        openService(spec)
        val field = spec.fields.firstOrNull { it.key in listOf("destination", "mobile") }
        if (field != null && destination.isNotEmpty()) serviceInitialValues = mapOf(field.key to destination)
        if (phone.isNotEmpty() && spec.fields.any { it.key == "phone" }) serviceInitialValues = serviceInitialValues + ("phone" to phone)
    }
    fun newPayment(target: Page, destination: String = "", name: String = "", phone: String = "") {
        val product = selectedProduct(state)
        val identity = product?.identity
        val legacy = identity == null || (target == Page.TRANSFER && identity.bank in listOf(Bank.BPA, Bank.BANDEC, Bank.BANMET)) ||
            (target != Page.TRANSFER && identity.bank in listOf(Bank.BPA, Bank.BANDEC))
        if (!legacy && identity != null) {
            val options = state.services.filter { spec -> spec.supports(identity) && spec.implementationStatus == ImplementationStatus.IMPLEMENTED &&
                (if (target == Page.TRANSFER) spec.category == OperationCategory.TRANSFERS &&
                    (destination.isEmpty() || spec.fields.any { it.key == "destination" })
                else spec.id in listOf("service.mobile", "service.mobile.mlc", "wallet.mobile.cup", "wallet.mobile.usd", "wallet.agent.mobile")) &&
                (product?.currency == null || spec.currencies.isEmpty() || product.currency in spec.currencies) }
            if (options.size == 1) { openPaymentService(options.single(), destination, phone); return }
            if (options.isNotEmpty()) { serviceChoices = options; serviceChoiceDestination = destination; serviceChoicePhone = phone; return }
        }
        draft = PaymentDraft(destination = destination, name = name, phone = phone); go(target)
    }
    fun scanNumber(phone: Boolean, callback: (String) -> Unit) { scannerMode = if (phone) ScanMode.MOBILE else ScanMode.CARD; scanTarget = callback }
    fun readQr(code: String) {
        runCatching { QrInput.parse(code) }.onSuccess { input ->
            when (input) {
                is QrInput.Card -> newPayment(Page.TRANSFER, input.card, phone = input.phone.orEmpty())
                is QrInput.Payment -> { qr = input.value }
            }
        }
    }
    LaunchedEffect(state.unlocked, incomingPayment) {
        if (!state.unlocked || incomingPayment == null) return@LaunchedEffect
        if (state.pending != null) snackbar.showSnackbar("Revisa la operación pendiente antes de abrir otro pago")
        else runCatching { QrInput.parse(incomingPayment) }.onSuccess { input ->
            when (input) {
                is QrInput.Card -> newPayment(Page.TRANSFER, input.card, phone = input.phone.orEmpty())
                is QrInput.Payment -> qr = input.value
            }
        }.onFailure { snackbar.showSnackbar(it.message ?: "No se pudo abrir el enlace de pago") }
        consumeIncomingPayment()
    }
    LaunchedEffect(state.unlocked, incomingReceiptId, state.wallet.receipts, state.wallet.movements) {
        if (!state.unlocked || incomingReceiptId == null) return@LaunchedEffect
        val movementIds = state.wallet.movements.filter { it.receiptId == incomingReceiptId }.map { it.id }.toSet()
        val receipt = appFinancialHistory(state).singleOrNull { it.storedId in movementIds } ?: return@LaunchedEffect
        selectedReceipt = receipt
        stack = listOf(Page.ACTIVITY.name)
        consumeIncomingReceipt()
    }
    LaunchedEffect(state.notice) {
        if (review == null && qr == null) state.notice?.let { snackbar.showSnackbar(it, actionLabel = "Cerrar", duration = SnackbarDuration.Long); actions.dismissNotice() }
    }
    LaunchedEffect(state.pending, state.confirmed) {
        if (state.pending != null || state.confirmed != null) { review = null; qr = null; stack = listOf(Page.HOME.name) }
    }
    BackHandler(enabled = (state.unlocked || !state.hasCredentials) && page != Page.HOME && review == null && qr == null && scanTarget == null) { back() }
    SideEffect { actions.protectScreen(!state.unlocked || !state.hasCredentials || page == Page.ENROLL || (page == Page.FUEL && fuelSensitive) ||
        (page == Page.SERVICE_FORM && state.services.firstOrNull { it.id == selectedServiceId }?.fields?.any { it.sensitive && !it.suppliedByAccess } == true)) }

    NeoTheme(when (theme) { ThemePreference.SYSTEM -> systemDark; ThemePreference.LIGHT -> false; ThemePreference.DARK -> true }) {
        Scaffold(snackbarHost = { if (review == null && qr == null) SnackbarHost(snackbar) }, bottomBar = {
            if (state.unlocked && state.hasCredentials && page in roots) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf(Triple(Page.HOME, "Inicio", R.drawable.ic_home), Triple(Page.ACTIVITY, "Actividad", R.drawable.ic_history),
                    Triple(Page.CONTACTS, "Contactos", R.drawable.ic_contacts), Triple(Page.SERVICES, "Servicios", R.drawable.ic_receipt_long)).forEach { (target, label, icon) ->
                    NavigationBarItem(selected = page == target, onClick = { go(target) }, icon = { Icon(painterResource(icon), null) }, label = { Text(label) })
                }
            }
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
                when {
                    !state.unlocked && state.hasCredentials -> LockScreen(state.busy, actions.unlock) { resetAccess = true }
                    (!state.hasCredentials && page != Page.SERVICE_FORM) || page == Page.ENROLL -> Enrollment(state, actions, enrollIdentity, ::back, ::openService)
                    else -> AnimatedContent(page, label = "Navegación") { destination ->
                        savedState.SaveableStateProvider(destination.name) {
                            when (destination) {
                                Page.HOME -> WalletHome(state, actions, { go(Page.SETTINGS) }, { go(Page.WALLET) }, { go(Page.BANKS) },
                                    { newPayment(Page.TRANSFER) }, { scannerMode = ScanMode.QR; go(Page.SCAN) }, { newPayment(Page.RECHARGE) },
                                    { go(Page.SERVICES) }, { go(Page.ACTIVITY) }, { selectedReceipt = it }, { resolve = true }, ::configure, { go(Page.RECEIVE) })
                                Page.ACTIVITY -> {
                                    val identity = selectedProduct(state)?.identity ?: ProviderIdentity.forBank(state.bank)
                                    val query = state.services.firstOrNull { it.id.endsWith(".recent-operations") && it.supports(identity) }
                                    MovementsScreen(state, actions, { resolvingUncertain = it }, { resolve = true }, query?.let { spec -> { openService(spec) } }, { go(Page.STATISTICS) })
                                }
                                Page.CONTACTS -> ContactsScreen(state, actions, { selectedContactId = null; go(Page.CONTACT_EDIT) }, { selectedContactId = it.id; go(Page.CONTACT_DETAIL) })
                                Page.SERVICES -> ServicesScreen(state, ::openService, { go(Page.TELECOM_INFO) }, { go(Page.MITURNO) }, { go(Page.FUEL) })
                                Page.SETTINGS -> SettingsScreen(state, actions, theme, { theme = it; prefs.edit().putString("theme", it.name).remove("dark").apply() }, ::back, { go(Page.BANKS) })
                                Page.BANKS -> BanksScreen(state, actions, ::back, ::configure)
                                Page.WALLET -> WalletManager(state, ::back, { editProduct(null) }, ::editProduct, { go(Page.BANKS) })
                                Page.PRODUCT_EDIT -> ProductEditor(state, walletProducts(state).firstOrNull { it.id == editedProductId }, actions, ::back,
                                    { go(Page.BANKS) }, { scanNumber(false, it) })
                                Page.CONTACT_DETAIL -> state.wallet.contacts.firstOrNull { it.id == selectedContactId }?.let { contact ->
                                    ContactDetail(contact, actions, ::back, { go(Page.CONTACT_EDIT) }, { newPayment(Page.TRANSFER, it.number, contact.name,
                                        contact.phones.singleOrNull()?.number.orEmpty()) }, { newPayment(Page.RECHARGE, it.number, contact.name) })
                                } ?: EmptyAction("El contacto ya no está en la agenda", "Volver a contactos", { go(Page.CONTACTS) })
                                Page.CONTACT_EDIT -> ContactEditor(state, state.wallet.contacts.firstOrNull { it.id == selectedContactId }, actions, ::back,
                                    { scanNumber(false, it) }, { scanNumber(true, it) })
                                Page.TRANSFER, Page.RECHARGE, Page.BILLS -> MoneyForm(when (destination) { Page.TRANSFER -> PaymentPage.TRANSFER; Page.RECHARGE -> PaymentPage.RECHARGE; else -> PaymentPage.BILLS },
                                    state, actions, draft, { draft = it }, ::back, { review = it }, ::configure, { editProduct(null) }, ::scanNumber)
                                Page.SERVICE_FORM -> state.services.firstOrNull { it.id == selectedServiceId }?.let { spec -> ServiceForm(spec, state, actions, ::back, ::configure, ::scanNumber, serviceInitialValues, { editProduct(null) }, serviceInitialRegistrationId) }
                                    ?: EmptyAction("No se encontró el servicio", "Volver a servicios", { go(Page.SERVICES) })
                                Page.SCAN -> ScannerScreen(state, actions, scannerMode, { scannerMode = it }, ::back, { code -> readQr(code) },
                                    { number -> newPayment(if (scannerMode == ScanMode.MOBILE) Page.RECHARGE else Page.TRANSFER, number) }, paused = qr != null)
                                Page.ENROLL -> Unit
                                Page.RECEIVE -> ReceiveQrScreen(state, actions, ::back)
                                Page.STATISTICS -> StatisticsScreen(state, ::back)
                                Page.TELECOM_INFO -> if (telecomController != null) TelecomScreen(state, telecomController, actions.requestSmsPermission, smsPermission, ::back)
                                    else EmptyAction("Las consultas por SMS no están conectadas en esta sesión de pruebas", "Volver", ::back)
                                Page.MITURNO -> MiTurnoScreen(state, ::back, open = { request, registrationId ->
                                    state.services.firstOrNull { it.id == request.operationId }?.let { spec ->
                                        openService(spec); serviceInitialValues = request.values; serviceInitialRegistrationId = registrationId
                                        actions.selectRegistration(registrationId)
                                    }
                                }, newRequest = {
                                    val wallet = selectedProduct(state)?.identity?.provider == ProviderId.MITRANSFER
                                    state.services.firstOrNull { it.id == if (wallet) "wallet.miturno.reserve" else "service.miturno.reserve" }?.let(::openService)
                                }, openReceipt = { receiptId ->
                                    val ids = state.wallet.movements.filter { it.receiptId == receiptId }.map { it.id }.toSet()
                                    selectedReceipt = appFinancialHistory(state).firstOrNull { it.storedId in ids }
                                })
                                Page.FUEL -> if (fuelController != null) FuelScreen(state.wallet.fuelCoupons, fuelController,
                                    FuelActions(openOperation = { id, values -> state.services.firstOrNull { it.id == id }?.let { spec ->
                                        openService(spec); serviceInitialValues = values
                                    } }, rename = actions.renameFuel, archive = actions.archiveFuel, authorizeSecret = actions.authorizeFuel),
                                    ::back, { fuelSensitive = it })
                                    else EmptyAction("Los cupones no están conectados en esta sesión de pruebas", "Volver", ::back)
                            }
                        }
                    }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            }
        }
        if (state.unlocked || !state.hasCredentials) {
            if (serviceChoices.isNotEmpty()) ModalBottomSheet(onDismissRequest = { serviceChoices = emptyList() }) {
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Text("Elegir operación", style = MaterialTheme.typography.headlineSmall)
                    serviceChoices.forEach { spec -> ActionRow(spec.title, spec.category.icon()) { openPaymentService(spec, serviceChoiceDestination, serviceChoicePhone); serviceChoices = emptyList() } }
                }
            }
            scanTarget?.let { callback ->
                Dialog(onDismissRequest = { scanTarget = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    Surface(Modifier.fillMaxSize()) {
                        ScannerScreen(state, actions, scannerMode, { scannerMode = it }, { scanTarget = null },
                            { code -> runCatching { QrInput.parse(code) }.getOrNull()?.let { if (it is QrInput.Card) { callback(it.card); scanTarget = null } } },
                            { number -> callback(number); scanTarget = null }, fixedMode = true)
                    }
                }
            }
            qr?.let { value -> QrPaymentReview(value, state, actions, { qr = null }, { qr = null; editProduct(null) }, { qr = null; configure(it) }) }
            review?.let { MoneyReview(it, state, actions) { review = null } }
            selectedReceipt?.let { MovementDetail(it, displayRecipients(state), actions.share) { selectedReceipt = null } }
            if (resolvingUncertain != null) {
                val operation = resolvingUncertain!!
                ModalBottomSheet(onDismissRequest = { resolvingUncertain = null; linkingReceipt = null }) {
                    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(24.dp)) {
                        item { Text("Vincular comprobante", style = MaterialTheme.typography.headlineSmall); Text("${formatMoney(operation.request.amount)} · ${readableAccount(operation.request.destination)}", Modifier.padding(top = 16.dp)); Text(dateText(operation.startedAt)) }
                        val compatible = state.history.filter { operation.matches(it.message, it.record.receivedAt, it.record.subscriptionId) }
                        if (compatible.isEmpty()) item { Text("Todavía no hay un comprobante compatible", Modifier.padding(vertical = 24.dp)) }
                        items(compatible) { entry -> FinancialMovement.from(entry.message)?.let { movement -> MovementRow(MovementItem(entry, movement), displayRecipients(state)) { linkingReceipt = entry } } }
                    }
                }
            }
            if (linkingReceipt != null && resolvingUncertain != null) {
                val movement = FinancialMovement.from(linkingReceipt!!.message)
                AlertDialog(onDismissRequest = { linkingReceipt = null }, title = { Text("Vincular este comprobante") },
                    text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { movement?.let { Text(formatMoney(it.amount)); Text(readableAccount(it.party)); it.reference?.let { reference -> Text(reference) } }; Text(dateText(linkingReceipt!!.record.receivedAt)) } },
                    confirmButton = { TextButton(onClick = { actions.resolveUncertain(resolvingUncertain!!.id, linkingReceipt!!.record); linkingReceipt = null; resolvingUncertain = null }) { Text("Vincular") } },
                    dismissButton = { TextButton(onClick = { linkingReceipt = null }) { Text("Volver") } })
            }
            if (resolve && state.pending != null) AlertDialog(onDismissRequest = { resolve = false }, title = { Text("Cerrar revisión de resultado") },
                text = { Text("La operación quedará sin confirmar y podrás iniciar otra. Cerrar la revisión no cancela ni repite la operación.") },
                confirmButton = { TextButton(onClick = { actions.resolvePending(); resolve = false }, enabled = !state.busy) { Text("Cerrar revisión") } },
                dismissButton = { TextButton(onClick = { resolve = false }) { Text("Seguir esperando") } })
            state.serviceResult?.let { result -> AlertDialog(onDismissRequest = actions.clearServiceResult, title = { Text("Resultado") },
                text = { LazyColumn { item { Text(result) } } }, confirmButton = { TextButton(onClick = actions.clearServiceResult) { Text("Cerrar resultado") } }) }
        }
        if (resetAccess) AlertDialog(onDismissRequest = { resetAccess = false }, title = { Text("Restablecer acceso") },
            text = { Text("Se borrarán las claves guardadas en este teléfono. La cartera y la actividad se conservarán.") },
            confirmButton = { TextButton(onClick = { actions.resetCredentials(); resetAccess = false }) { Text("Borrar claves") } },
            dismissButton = { TextButton(onClick = { resetAccess = false }) { Text("Cancelar") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScannerScreen(state: AppUiState, actions: UiActions, mode: ScanMode, setMode: (ScanMode) -> Unit,
                          back: () -> Unit, onCode: (String) -> Unit, onNumber: (String) -> Unit,
                          paused: Boolean = false, fixedMode: Boolean = false, externalError: String? = null) {
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.padding(horizontal = 12.dp)) { PageHeader("Escanear", back) }
        if (!fixedMode) FlowRow(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ScanMode.QR to "QR", ScanMode.CARD to "Tarjeta", ScanMode.MOBILE to "Móvil").forEach { (value, label) ->
                FilterChip(mode == value, { error = null; setMode(value) }, label = { Text(label) })
            }
        }
        if (!state.cameraPermission) TextButton(onClick = actions.cameraPermission, modifier = Modifier.padding(horizontal = 24.dp)) { Text("Permitir cámara") }
        QrCamera(Modifier.weight(1f).fillMaxWidth(), paused = paused, onCode = { code ->
            runCatching { QrInput.parse(code) }.onSuccess { error = null; onCode(code) }.onFailure { error = it.message ?: "QR no compatible" }
        }, onError = { error = it }, mode = mode, onNumber = onNumber)
        (error ?: externalError)?.let { Text(it, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error) }
    }
}
