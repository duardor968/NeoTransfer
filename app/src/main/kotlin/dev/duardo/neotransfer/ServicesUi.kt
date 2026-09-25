package dev.duardo.neotransfer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.duardo.neotransfer.core.*
import dev.duardo.neotransfer.data.*
import dev.duardo.neotransfer.platform.QrInput

internal fun OperationCategory.title(): String = when (this) {
    OperationCategory.PAYMENTS -> "Pagos y facturas"
    OperationCategory.RECHARGES -> "Recargas"
    OperationCategory.TRANSFERS -> "Transferencias"
    OperationCategory.QUERIES -> "Consultas"
    OperationCategory.ACCOUNTS -> "Cuentas y tarjetas"
    OperationCategory.SECURITY -> "Acceso y seguridad"
    OperationCategory.TELECOM -> "Telecomunicaciones"
    OperationCategory.PROCEDURES -> "Gestiones"
}

internal fun OperationCategory.icon(): Int = when (this) {
    OperationCategory.PAYMENTS -> R.drawable.ic_receipt_long
    OperationCategory.RECHARGES, OperationCategory.TELECOM -> R.drawable.ic_smartphone
    OperationCategory.TRANSFERS -> R.drawable.ic_swap_horiz
    OperationCategory.QUERIES -> R.drawable.ic_search
    OperationCategory.ACCOUNTS -> R.drawable.ic_credit_card
    OperationCategory.SECURITY -> R.drawable.ic_security
    OperationCategory.PROCEDURES -> R.drawable.ic_account_balance
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ServicesScreen(state: AppUiState, open: (OperationSpec) -> Unit, telecom: () -> Unit = {}, miTurno: () -> Unit = {}, fuel: () -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var provider by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberSaveable(category, provider, query, saver = LazyListState.Saver) { LazyListState() }
    val all = state.services.filter { it.implementationStatus == ImplementationStatus.IMPLEMENTED }
    val visible = all.filter { operation ->
        (provider == null || operation.identities.any { it.provider.name == provider }) &&
            (category == null || operation.category.name == category) &&
            (query.isBlank() || operation.title.contains(query, true) || operation.category.title().contains(query, true))
    }
    val showTelecom = (provider == null || provider == ProviderId.CUBACEL.name) &&
        (category == OperationCategory.TELECOM.name || query.isNotBlank()) &&
        (query.isBlank() || listOf("Información ETECSA", "Emergencias", "Consultas SMS").any { it.contains(query, true) })
    val showTurns = (category == OperationCategory.PROCEDURES.name || query.contains("turno", true)) &&
        (provider == null || provider != ProviderId.CUBACEL.name)
    val showFuel = (category == OperationCategory.PAYMENTS.name || query.contains("combustible", true) || query.contains("cupón", true)) &&
        (provider == null || provider in setOf(ProviderId.BPA.name, ProviderId.BANDEC.name, ProviderId.BANMET.name))
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Text("Servicios", Modifier.padding(top = 20.dp, bottom = 20.dp), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        SearchField(query, { query = it }, "Buscar servicio")
        Spacer(Modifier.height(12.dp))
        ChoiceField(provider?.let { ProviderId.valueOf(it).title() } ?: "Todos los proveedores",
            listOf("" to "Todos los proveedores") + all.flatMap { it.identities }.map { it.provider }.distinct().map { it.name to it.title() }) { provider = it.ifBlank { null } }
        if (category != null) FlowRow(Modifier.fillMaxWidth().padding(top = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(OperationCategory.valueOf(category!!).title(), Modifier.align(Alignment.CenterVertically), style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { category = null }) { Text("Ver categorías") }
        }
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(vertical = 16.dp)) {
            if (showTelecom) item {
                ActionRow("Información y números útiles", R.drawable.ic_smartphone, "Consultas ETECSA y emergencias", click = telecom)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (showTurns) item {
                ActionRow("Mis solicitudes MiTurno", R.drawable.ic_calendar_month, click = miTurno)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (showFuel) item {
                ActionRow("Mis cupones de combustible", R.drawable.ic_receipt_long, click = fuel)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (query.isBlank() && category == null) {
                if (visible.isEmpty()) item { EmptyAction("No hay servicios que coincidan", "Ver todos") { provider = null } }
                OperationCategory.entries.forEach { group ->
                    val members = visible.filter { it.category == group }
                    if (members.isNotEmpty()) item(group.name) {
                        ActionRow(group.title(), group.icon(), members.map { it.title.substringBefore(" · ") }.distinct().take(3).joinToString(", ")) { category = group.name }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            } else {
                if (visible.isEmpty() && !showTelecom && !showTurns && !showFuel) item { EmptyAction("No hay servicios que coincidan", "Limpiar búsqueda") { query = ""; category = null; provider = null } }
                items(visible, key = { it.id }) { operation ->
                    ActionRow(operation.title, operation.category.icon(), operation.identities.map { it.title() }.distinct().joinToString(" · ")) { open(operation) }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

private data class ServiceOriginUi(
    val key: String, val executionIdentity: ProviderIdentity, val authenticationIdentity: ProviderIdentity,
    val registration: RegistrationRecord?, val product: WalletProductUi? = null, val manual: Boolean = false,
) {
    fun label(state: AppUiState, includeNumber: Boolean = true): String = buildList {
        add(executionIdentity.title())
        product?.let { add(it.name); if (includeNumber) it.number?.let { number -> add(readableAccount(number)) } }
        if (manual) add("Otra cuenta")
        registration?.let { record ->
            val line = record.linePhone ?: state.sims.firstOrNull { it.id == record.subscriptionId }?.label
            line?.let(::add)
        }
    }.filter(String::isNotBlank).joinToString(" · ")
}

private fun ServiceOriginUi.isSelected(state: AppUiState): Boolean {
    val record = registration ?: return true
    val settings = state.wallet.settings
    return settings.selectedRegistrationId == record.id &&
        if (product?.card != null || product?.account != null) {
            settings.selectedCardId == product.id || settings.selectedAccountId == product.id
        } else settings.selectedCardId == null && settings.selectedAccountId == null
}

private fun ServiceOriginUi.sameContext(other: ServiceOriginUi): Boolean = key == other.key &&
    executionIdentity == other.executionIdentity && authenticationIdentity == other.authenticationIdentity &&
    registration?.id == other.registration?.id && registration?.subscriptionId == other.registration?.subscriptionId &&
    registration?.enabled == other.registration?.enabled && registration?.credentialAlias == other.registration?.credentialAlias &&
    product?.id == other.product?.id && product?.identity == other.product?.identity &&
    product?.number == other.product?.number && product?.currency == other.product?.currency && product?.source == other.product?.source

private fun serviceOrigins(spec: OperationSpec, state: AppUiState): List<ServiceOriginUi> = buildList {
    val products = walletProducts(state)
    spec.identities.forEach { execution ->
        val authentication = spec.authenticationIdentity ?: execution
        val registrations = state.wallet.registrations.filter { it.identity() == authentication }
        val linkedCard = execution.profile == ProfileId.CLASSIC && authentication != execution
        val compatible = products.filter { it.identity == execution &&
            (execution.provider != ProviderId.MITRANSFER || spec.currencies.isEmpty() || it.currency == null || it.currency in spec.currencies) &&
            (it.registrationId == null || registrations.any { registration -> registration.id == it.registrationId }) }
        if (linkedCard) {
            compatible.filter { it.source is SourceSelector.Explicit }.forEach { product ->
                add(ServiceOriginUi("${execution.id}:${product.id}", execution, authentication,
                    registrations.singleOrNull { it.id == product.registrationId }, product))
            }
        } else {
            if (spec.sourcePolicy in setOf(SourcePolicy.DEFAULT_OR_EXPLICIT, SourcePolicy.EXPLICIT_ONLY)) {
                compatible.filter { it.source is SourceSelector.Explicit }.forEach { product ->
                    add(ServiceOriginUi("${execution.id}:${product.id}", execution, authentication,
                        registrations.singleOrNull { it.id == product.registrationId }, product))
                }
            }
            val accessChoices: List<RegistrationRecord?> = if (registrations.isEmpty()) listOf(null) else registrations
            accessChoices.forEach { registration ->
                val base = "${execution.id}:${registration?.id.orEmpty()}"
                if (spec.sourcePolicy != SourcePolicy.EXPLICIT_ONLY)
                    add(ServiceOriginUi("$base:default", execution, authentication, registration))
                if (spec.sourcePolicy in setOf(SourcePolicy.DEFAULT_OR_EXPLICIT, SourcePolicy.EXPLICIT_ONLY))
                    add(ServiceOriginUi("$base:manual", execution, authentication, registration, manual = true))
            }
        }
    }
}

private data class ServiceReviewUi(val request: ServiceRequest, val origin: ServiceOriginUi, val subscriptionId: Int, val line: String?,
                                 val selectorValues: Map<String, String>, val qr: QrPayment? = null)
private data class ReferenceDraftUi(val original: SavedServiceRecord?, val fieldKey: String, val identifier: String, val label: String)

private fun referenceFields(spec: OperationSpec): List<OperationField> {
    if (spec.id.contains(".qr.") || spec.service in setOf(30, 31)) return emptyList()
    return spec.fields.filter { field -> !field.sensitive && !field.suppliedByAccess &&
        (field.kind in setOf(FieldKind.ACCOUNT, FieldKind.PHONE, FieldKind.EMAIL) ||
            field.key in setOf("username", "user", "contract", "invoice", "license", "taxpayer", "serial", "subscriber", "customer")) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ServiceForm(spec: OperationSpec, state: AppUiState, actions: UiActions, back: () -> Unit,
                        configure: (ProviderIdentity) -> Unit, scan: (Boolean, (String) -> Unit) -> Unit,
                        initialValues: Map<String, String> = emptyMap(), addSource: () -> Unit = {}, initialRegistrationId: String? = null) {
    val origins = serviceOrigins(spec, state)
    var originKey by rememberSaveable(spec.id, initialValues, initialRegistrationId) { mutableStateOf(
        initialRegistrationId?.let { id -> origins.firstOrNull { it.registration?.id == id && it.product == null && !it.manual }?.key }
            ?: origins.firstOrNull { state.selectedProductId != null && it.product?.id == state.selectedProductId }?.key
            ?: origins.filter { it.registration?.id == state.wallet.settings.selectedRegistrationId && it.product == null && !it.manual }.singleOrNull()?.key
            ?: origins.singleOrNull()?.key) }
    var fallbackIdentityId by rememberSaveable(spec.id) { mutableStateOf(spec.identities.singleOrNull()?.id) }
    val origin = origins.firstOrNull { it.key == originKey }
    val identity = origin?.executionIdentity ?: spec.identities.firstOrNull { it.id == fallbackIdentityId }
    val authentication = origin?.authenticationIdentity ?: spec.authenticationIdentity ?: identity
    var savedValues by rememberSaveable(spec.id, initialValues) { mutableStateOf(initialValues.filterKeys { key -> spec.fields.none { it.key == key && it.sensitive } }) }
    var secretValues by remember(spec.id) { mutableStateOf(emptyMap<String, String>()) }
    val values = savedValues + secretValues
    var currency by rememberSaveable(spec.id) { mutableStateOf(selectedProduct(state)?.takeIf {
        spec.supports(it.identity) && it.currency in spec.currencies
    }?.currency?.name) }
    var manualSource by rememberSaveable(spec.id) { mutableStateOf("") }
    var errors by remember(spec.id) { mutableStateOf(emptyList<OperationValidationError>()) }
    var review by remember { mutableStateOf<ServiceReviewUi?>(null) }
    var referenceDraft by remember { mutableStateOf<ReferenceDraftUi?>(null) }
    var savingReference by remember { mutableStateOf<SavedServiceRecord?>(null) }
    var referenceError by remember { mutableStateOf<String?>(null) }
    var selectedReferenceId by rememberSaveable(spec.id) { mutableStateOf<String?>(null) }
    var reassignRegistrationId by remember { mutableStateOf<String?>(null) }
    val cashExtra = spec.id == "service.cash.extra"
    var cashQr by remember(spec.id) { mutableStateOf<QrPayment?>(null) }
    var scanningCashQr by remember { mutableStateOf(false) }
    var cashQrError by remember { mutableStateOf<String?>(null) }
    val requiresAccess = spec.requiresSession || spec.fields.any { it.suppliedByAccess }
    val needsAccess = requiresAccess && (origin?.registration?.let { it.id !in state.configuredRegistrationIds }
        ?: authentication?.let { !hasAccess(state, it) } ?: true)
    val source = when (spec.sourcePolicy) {
        SourcePolicy.NONE, SourcePolicy.DEFAULT_ONLY -> SourceSelector.Default
        else -> origin?.product?.source ?: manualSource.takeIf { origin?.manual == true && it.isNotEmpty() && it.all(Char::isDigit) }
            ?.let { SourceSelector.Explicit(it) } ?: SourceSelector.Default
    }
    val validManualSource = origin?.manual != true || manualSource.isNotEmpty() && manualSource.all(Char::isDigit)
    val selectedCurrency = serviceCurrency(spec, identity, origin?.product?.currency, currency?.let(Currency::valueOf))
    fun makeRequest(): ServiceRequest? = identity?.let { ServiceRequest(spec.id, it, source, selectedCurrency, ServiceFieldOptions.requestValues(spec, values)) }
    fun validateRequest(value: ServiceRequest, original: QrPayment? = cashQr, selectors: Map<String, String> = savedValues): List<OperationValidationError> =
        spec.validate(value) + actions.validateService(value) + ServiceFieldOptions.validate(spec, value.identity, selectors) + if (cashExtra) {
            original?.let { CashExtraOperations.validateOriginal(value, it) }
                ?: listOf(OperationValidationError("qr", "Lee el QR del comercio"))
        } else emptyList()
    LaunchedEffect(origin?.key) {
        origin?.let { selected ->
            if (!selected.isSelected(state)) {
                if (selected.product != null) actions.selectProduct(selected.product.id)
                else selected.registration?.let { actions.selectRegistration(it.id) }
            }
        }
    }
    LaunchedEffect(state.wallet.services, savingReference) {
        savingReference?.let { expected -> if (state.wallet.services.any { it == expected }) {
            selectedReferenceId = expected.id; savingReference = null; referenceDraft = null
        } }
    }
    LaunchedEffect(state.notice) { if (savingReference != null && state.notice != null) { referenceError = state.notice; savingReference = null } }
    val request = makeRequest()
    val validation = request?.let { validateRequest(it) }.orEmpty().distinct()
    val visibleErrors = (errors + validation.filter { it.fieldKey in values && values[it.fieldKey].orEmpty().isNotBlank() ||
        it.fieldKey == "source" && origin?.manual == true && manualSource.isNotBlank() } +
        if (!validManualSource && manualSource.isNotBlank()) listOf(OperationValidationError("source", "La cuenta de origen debe contener solo dígitos")) else emptyList()).distinct()
    val canContinue = request != null && origin != null && origin.isSelected(state) && !needsAccess &&
        !state.busy && state.pending == null
    val identifierFields = referenceFields(spec)
    fun fieldFor(reference: SavedServiceRecord): OperationField? = reference.fieldKey?.let { key -> identifierFields.singleOrNull { it.key == key } }
        ?: identifierFields.singleOrNull().takeIf { reference.fieldKey == null }
    val references = state.wallet.services.filter { it.kind == spec.id && fieldFor(it) != null &&
        (it.registrationId == null || it.registrationId == origin?.registration?.id) }
    val selectedReference = references.firstOrNull { it.id == selectedReferenceId }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PageHeader(spec.title, back)
        Text(if (spec.sourcePolicy in setOf(SourcePolicy.NONE, SourcePolicy.DEFAULT_ONLY)) "Acceso y línea" else "Desde", style = MaterialTheme.typography.titleMedium)
        if (origins.isNotEmpty()) ChoiceField(origin?.label(state, spec.sourcePolicy != SourcePolicy.NONE) ?: "Elegir acceso", origins.map { it.key to it.label(state, spec.sourcePolicy != SourcePolicy.NONE) }) {
            originKey = it; if (!spec.hasAmountCurrency(identity)) currency = null; manualSource = ""; errors = emptyList()
        } else ChoiceField(identity?.title() ?: "Elegir proveedor", spec.identities.map { it.id to it.title() }) { fallbackIdentityId = it }
        if (origin?.registration == null) {
            if (!state.permissions) TextButton(onClick = actions.permissions) { Text("Permitir acceso telefónico") }
            ChoiceField(state.sims.firstOrNull { it.id == state.subscription }?.label ?: "Elegir línea", state.sims.map { it.id.toString() to it.label }) { actions.selectSim(it.toInt()) }
        }
        if (spec.sourcePolicy == SourcePolicy.DEFAULT_ONLY || spec.sourcePolicy == SourcePolicy.DEFAULT_OR_EXPLICIT && origin?.product == null && origin?.manual == false)
            Detail("Origen", "Cuenta predeterminada")
        if (spec.sourcePolicy == SourcePolicy.NONE && origin?.product != null)
            Detail("Tarjeta vinculada", origin.product.number?.let(::readableAccount).orEmpty())
        if (origin?.manual == true) Field("Cuenta de origen", manualSource, { manualSource = digits(it, 30) }, KeyboardType.Number)
        if (spec.sourcePolicy != SourcePolicy.NONE) TextButton(onClick = addSource) { Text("Añadir tarjeta o cuenta") }
        if (spec.currencies.isNotEmpty()) {
            if (origin?.product?.currency != null && !spec.hasAmountCurrency(identity)) Detail("Moneda", origin.product.currency.name)
            else if (spec.currencies.size == 1) Detail(if (identity?.provider == ProviderId.MITRANSFER) "Monedero" else "Moneda", spec.currencies.single().name)
            else ChoiceField(selectedCurrency?.name ?: if (identity?.provider == ProviderId.MITRANSFER) "Monedero" else "Moneda", spec.currencies.map { it.name to it.name }) { currency = it }
        }
        if (references.isNotEmpty()) {
            ChoiceField(selectedReference?.label ?: "Elegir referencia guardada", references.map { it.id to "${it.label} · ${it.identifier}" }) { id ->
                references.singleOrNull { it.id == id }?.let { reference ->
                    fieldFor(reference)?.let { savedValues = savedValues + (it.key to reference.identifier); selectedReferenceId = id }
                }
            }
            selectedReference?.let { reference -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { referenceDraft = ReferenceDraftUi(reference, requireNotNull(fieldFor(reference)).key, reference.identifier, reference.label); referenceError = null }) { Text("Cambiar nombre") }
                TextButton(onClick = { actions.deleteService(reference.id); selectedReferenceId = null }) { Text("Eliminar referencia") }
            } }
        }
        if (cashExtra) {
            cashQr?.let { Detail("Comercio", it.provider) }
            OutlinedButton(onClick = { cashQrError = null; scanningCashQr = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(R.drawable.ic_qr_code_scanner), null)
                Spacer(Modifier.width(8.dp))
                Text(if (cashQr == null) "Leer QR del comercio" else "Cambiar QR")
            }
        }
        val formFields = identity?.let { ServiceFieldOptions.fields(spec, it, values) } ?: spec.fields
        formFields.filterNot { it.suppliedByAccess || cashExtra && it.key in setOf("transaction", "provider", "auxiliary") }.forEach { field ->
            val value = values[field.key].orEmpty()
            val choices = identity?.let { ServiceFieldOptions.options(spec, field, it, values) }
            fun change(text: String) {
                if (field.sensitive) secretValues = secretValues + (field.key to text) else {
                    val edited = identity?.let { ServiceFieldOptions.change(spec, it, savedValues, field.key, text) }
                        ?: (savedValues + (field.key to text))
                    savedValues = edited
                }
                errors = emptyList()
            }
            val qrFixed = cashExtra && (field.key == "amountCurrency" ||
                field.key == "amount" && cashQr?.editableAmount == false || field.key == "description" && !cashQr?.description.isNullOrEmpty())
            if (qrFixed) {
                if (value.isNotEmpty()) Detail(field.label, field.options.firstOrNull { it.value == value }?.label ?: value)
            } else if (choices != null || field.kind in listOf(FieldKind.CHOICE, FieldKind.CURRENCY)) {
                Text(field.label, style = MaterialTheme.typography.labelLarge)
                val available = (if (field.required) emptyList() else listOf(FieldOption("", "Sin especificar"))) + (choices ?: field.options)
                ChoiceField(available.firstOrNull { it.value == value }?.label ?: "Elegir", available.map { it.value to it.label }, ::change)
            } else if (field.kind == FieldKind.DATE) {
                val dateChange = dev.duardo.neotransfer.core.miturno.MiTurnoContracts.action(spec.id) == dev.duardo.neotransfer.core.miturno.MiTurnoAction.CHANGE_DATE
                val today = java.time.LocalDate.now()
                ServiceDateField(field.label, value, ::change, earliest = today.takeIf { dateChange }, latest = today.plusMonths(6).takeIf { dateChange })
            }
            else Field(field.label + if (field.required) "" else " (opcional)", value, { input ->
                change(if (field.kind == FieldKind.AMOUNT) decimalInput(input, value) else input)
            }, type = if (field.sensitive) {
                if (field.kind == FieldKind.TEXT) KeyboardType.Password else KeyboardType.NumberPassword
            } else when (field.kind) {
                FieldKind.AMOUNT -> KeyboardType.Decimal
                FieldKind.ACCOUNT, FieldKind.DIGITS, FieldKind.SECRET -> KeyboardType.Number
                FieldKind.PHONE -> KeyboardType.Phone
                FieldKind.EMAIL -> KeyboardType.Email
                else -> KeyboardType.Text
            }, secret = field.sensitive, trailing = if (field.kind == FieldKind.ACCOUNT || field.kind == FieldKind.PHONE) {
                { IconButton(onClick = { scan(field.kind == FieldKind.PHONE, ::change) }) { Icon(painterResource(R.drawable.ic_qr_code_scanner), "Leer ${field.label.lowercase()}") } }
            } else null)
            visibleErrors.filter { it.fieldKey == field.key }.forEach { Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
        val saveableFields = identifierFields.filter { values[it.key].orEmpty().isNotBlank() && validation.none { error -> error.fieldKey == it.key } }
        if (identifierFields.isNotEmpty()) TextButton(onClick = {
            val field = saveableFields.first()
            referenceDraft = ReferenceDraftUi(null, field.key, values.getValue(field.key), ""); referenceError = null
        }, enabled = saveableFields.isNotEmpty()) { Text("Guardar referencia") }
        visibleErrors.filter { error -> spec.fields.none { it.key == error.fieldKey } }.forEach { Text(it.message, color = MaterialTheme.colorScheme.error) }
        if (state.pending != null) Text("Hay una operación pendiente de resultado", color = MaterialTheme.colorScheme.error)
        val accessNeedsLine = origin?.registration?.let { !it.enabled || it.subscriptionId == null || state.sims.none { sim -> sim.id == it.subscriptionId } } == true
        if (needsAccess && accessNeedsLine) Primary("Asignar línea", !state.busy) { reassignRegistrationId = origin?.registration?.id }
        else if (needsAccess && authentication != null) Primary("Configurar ${authentication.title()}", !state.busy) { configure(authentication) }
        else if (origin == null && origins.isEmpty() && identity?.profile == ProfileId.CLASSIC)
            EmptyAction("Añade la tarjeta vinculada a MiTransfer", "Añadir tarjeta", addSource)
        else Primary(if (spec.requiresConfirmation) "Revisar ${if (spec.effect == OperationEffect.MONEY) "pago" else "solicitud"}" else "Consultar", canContinue) {
            val value = requireNotNull(makeRequest())
            errors = validateRequest(value) + if (!validManualSource) listOf(OperationValidationError("source", "Introduce la cuenta de origen completa")) else emptyList()
            if (errors.isEmpty()) {
                val selected = requireNotNull(origin)
                val lineId = selected.registration?.subscriptionId ?: state.subscription
                if (spec.requiresConfirmation) review = ServiceReviewUi(value, selected, lineId,
                    selected.registration?.linePhone ?: state.sims.firstOrNull { it.id == lineId }?.label, savedValues.toMap(), cashQr)
                else actions.runService(value)
            }
        }
    }
    if (scanningCashQr) Dialog(onDismissRequest = { scanningCashQr = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            ScannerScreen(state, actions, ScanMode.QR, {}, { scanningCashQr = false }, { text ->
                runCatching {
                    val original = (QrInput.parse(text) as? QrInput.Payment)?.value ?: error("Lee el QR del comercio")
                    original.validateDate(java.time.LocalDate.now())
                    require(original.service == 31) { "Caja Extra requiere un QR estático" }
                    original
                }.onSuccess { original ->
                    cashQr = original
                    savedValues = savedValues + mapOf("transaction" to original.transactionId, "provider" to original.provider,
                        "auxiliary" to original.auxiliary, "amountCurrency" to CurrencyContract.BANK.code(original.amount.currency),
                        "amount" to original.amount.amount.takeIf { it.signum() > 0 }?.toPlainString().orEmpty(), "description" to original.description)
                    errors = emptyList(); cashQrError = null; scanningCashQr = false
                }.onFailure { cashQrError = it.message ?: "QR de Caja Extra no válido" }
            }, {}, fixedMode = true, externalError = cashQrError)
        }
    }
    reassignRegistrationId?.let { id -> RegistrationLineDialog(state, actions, id) { reassignRegistrationId = null } }
    review?.let { reviewed ->
        val value = reviewed.request
        ModalBottomSheet(onDismissRequest = { if (!state.busy) review = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(spec.title, style = MaterialTheme.typography.headlineSmall)
                Detail("Proveedor", value.identity.title())
                reviewed.line?.let { Detail("Línea", it) }
                val source = value.source
                if (source is SourceSelector.Explicit) Detail("Desde", readableAccount(source.account))
                else if (spec.sourcePolicy != SourcePolicy.NONE) Detail("Desde", "Cuenta predeterminada")
                else if (reviewed.origin.product != null) reviewed.origin.product.number?.let { Detail("Tarjeta vinculada", readableAccount(it)) }
                value.currency?.let { Detail(if (value.identity.provider == ProviderId.MITRANSFER) "Monedero" else "Moneda", it.name) }
                spec.fields.filterNot { it.sensitive || cashExtra && it.key in setOf("transaction", "auxiliary") }.forEach { field ->
                    value.values[field.key]?.takeIf(String::isNotBlank)?.let { text ->
                        val choices = ServiceFieldOptions.options(spec, field, value.identity, reviewed.selectorValues) ?: field.options
                        val displayValue = reviewed.selectorValues[field.key] ?: text
                        Detail(if (field.kind == FieldKind.DATE) field.label.substringBefore(" (") else field.label,
                            choices.firstOrNull { it.value == displayValue }?.label ?: text)
                    }
                }
                Primary("Confirmar con huella", !state.busy && state.pending == null) {
                    val currentOrigin = origins.singleOrNull { it.key == reviewed.origin.key }
                    if (currentOrigin == null || !currentOrigin.sameContext(reviewed.origin) || !reviewed.origin.isSelected(state) ||
                        (currentOrigin.registration?.subscriptionId ?: state.subscription) != reviewed.subscriptionId) {
                        errors = listOf(OperationValidationError("source", "El acceso o la cuenta cambió. Revisa la solicitud otra vez.")); review = null
                    } else {
                        errors = validateRequest(value, reviewed.qr, reviewed.selectorValues)
                        if (errors.isEmpty()) {
                            if (cashExtra) actions.runQrService(value, requireNotNull(reviewed.qr)) else actions.runService(value)
                            secretValues = emptyMap()
                        }
                        review = null
                    }
                }
            }
        }
    }
    referenceDraft?.let { draft ->
        AlertDialog(onDismissRequest = { if (savingReference == null) referenceDraft = null }, title = { Text(if (draft.original == null) "Guardar referencia" else "Cambiar nombre") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (draft.original == null) {
                    val candidates = identifierFields.filter { values[it.key].orEmpty().isNotBlank() && validation.none { error -> error.fieldKey == it.key } }
                    ChoiceField(identifierFields.first { it.key == draft.fieldKey }.label, candidates.map { it.key to it.label }) { key ->
                        referenceDraft = draft.copy(fieldKey = key, identifier = values.getValue(key))
                    }
                }
                Detail(identifierFields.first { it.key == draft.fieldKey }.label, draft.identifier)
                Field("Nombre", draft.label, { referenceDraft = draft.copy(label = it) })
                referenceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            } }, confirmButton = { TextButton(onClick = {
                val value = draft.original?.copy(label = draft.label.trim(), fieldKey = draft.fieldKey) ?: SavedServiceRecord(
                    id = java.util.UUID.randomUUID().toString(), kind = spec.id, label = draft.label.trim(), identifier = draft.identifier,
                    registrationId = origin?.registration?.id, fieldKey = draft.fieldKey)
                actions.dismissNotice(); savingReference = value; actions.saveService(value)
            }, enabled = draft.label.isNotBlank() && savingReference == null) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { referenceDraft = null; savingReference = null }) { Text("Cancelar") } })
    }
}
