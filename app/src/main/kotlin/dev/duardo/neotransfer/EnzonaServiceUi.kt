package dev.duardo.neotransfer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.data.SavedServiceRecord
import dev.duardo.neotransfer.enzona.*
import java.math.BigDecimal
import java.util.UUID

private fun EnzonaInvoiceService.title(): String = when (this) {
    EnzonaInvoiceService.ELECTRICITY -> "Electricidad"
    EnzonaInvoiceService.FIXED_PHONE -> "Teléfono fijo"
    EnzonaInvoiceService.NAUTA -> "Nauta"
    EnzonaInvoiceService.NAUTA_HOME -> "Nauta Hogar"
}

/** Provider amounts and identifiers are immutable; number is never substituted for serviceName. */
internal fun enzonaInvoiceDraft(service: EnzonaInvoiceService, bill: EnzonaElectricityBill?,
                                account: EnzonaEtecsaAccount?, enteredAmount: BigDecimal?,
                                cardId: String, currency: String): EnzonaInvoicePaymentDraft {
    require(cardId.isNotBlank()) { "Selecciona una tarjeta de origen" }
    require(currency.isNotBlank() && currency.none(Char::isISOControl)) { "Falta la moneda de la tarjeta de origen" }
    val incomplete = if (service == EnzonaInvoiceService.ELECTRICITY) "No se pudo obtener la factura completa"
        else "No se pudo consultar la cuenta"
    fun required(value: String?): String = requireNotNull(value?.takeIf {
        it.isNotBlank() && it.none(Char::isISOControl)
    }) { incomplete }
    fun positive(value: BigDecimal?, editable: Boolean = false): BigDecimal = requireNotNull(value?.takeIf { it.signum() > 0 }) {
        if (editable) "El importe debe ser mayor que cero" else incomplete
    }
    if (service == EnzonaInvoiceService.ELECTRICITY) {
        val selected = requireNotNull(bill) { "Selecciona una factura" }
        require(selected.charged != true) { "Esta factura ya está pagada" }
        return EnzonaInvoicePaymentDraft(service, required(selected.clientId),
            required(selected.owner), cardId, positive(selected.amount), currency,
            description = service.title(), folio = required(selected.folio),
            route = required(selected.route), period = required(selected.period))
    }
    val selected = requireNotNull(account) { "Selecciona una cuenta del servicio" }
    require(selected.service == service) { incomplete }
    require(selected.isValid == "true") { if (selected.isValid == "false") "Cuenta no válida" else incomplete }
    val amount = positive(if (service == EnzonaInvoiceService.FIXED_PHONE) selected.toPay else enteredAmount,
        editable = service != EnzonaInvoiceService.FIXED_PHONE)
    if (service != EnzonaInvoiceService.FIXED_PHONE) {
        selected.minimumAmount?.let { require(amount >= it) { "El importe mínimo es ${it.toPlainString()}" } }
        selected.maximumAmount?.let { require(amount <= it) { "El importe máximo es ${it.toPlainString()}" } }
    }
    val identifier = required(selected.serviceName)
    return EnzonaInvoicePaymentDraft(service, identifier, identifier, cardId, amount, currency, description = service.title())
}

private data class InvoiceReview(val draft: EnzonaInvoicePaymentDraft, val origin: String)

@Composable
internal fun EnzonaServiceScreen(service: EnzonaInvoiceService, state: AppUiState, actions: UiActions, back: () -> Unit) {
    val owner = state.enzonaWallet.owner
    val products = walletProducts(state).filter { it.enzonaCardId != null }
    var originId by rememberSaveable(service, owner) { mutableStateOf(state.enzonaWallet.selectedCardId) }
    var identifier by rememberSaveable(service, owner) { mutableStateOf("") }
    var amountText by rememberSaveable(service, owner) { mutableStateOf("") }
    var lookup by remember(service, owner) { mutableStateOf<EnzonaInvoiceLookup?>(null) }
    var queriedIdentifier by remember(service, owner) { mutableStateOf<String?>(null) }
    var selection by remember(service, owner) { mutableStateOf<Int?>(null) }
    var querying by remember(service, owner) { mutableStateOf(false) }
    var requestVersion by remember(service, owner) { mutableLongStateOf(0L) }
    var error by remember(service, owner) { mutableStateOf<String?>(null) }
    var review by remember(service, owner) { mutableStateOf<InvoiceReview?>(null) }
    var namingReference by remember(service, owner) { mutableStateOf(false) }
    var referenceName by rememberSaveable(service, owner) { mutableStateOf("") }
    var savingReference by remember(service, owner) { mutableStateOf<SavedServiceRecord?>(null) }
    var referenceError by remember(service, owner) { mutableStateOf<String?>(null) }
    val product = products.singleOrNull { it.enzonaCardId == originId }
    val card = state.enzonaWallet.cards.singleOrNull { it.fundingSourceUuid == originId }
    val currency = card?.currency
    val topUp = service == EnzonaInvoiceService.NAUTA || service == EnzonaInvoiceService.NAUTA_HOME
    val bills = lookup?.bills.orEmpty()
    val accounts = lookup?.etecsa?.accounts.orEmpty().filter { it.service == service }
    val bill = selection?.let(bills::getOrNull)
    val account = selection?.let(accounts::getOrNull)
    val referenceKind = "enzona.invoice.${service.code}"
    val references = state.wallet.services.filter { it.kind == referenceKind && it.fieldKey == "clientId" }
    val currentOwner by rememberUpdatedState(owner)
    val currentIdentifier by rememberUpdatedState(identifier)
    val queryValid = identifier.isNotBlank() && identifier.none(Char::isISOControl)
    val draftResult = runCatching {
        require(lookup != null && queriedIdentifier == identifier) { "Consulta el servicio antes de continuar" }
        require(product != null && owner != null) { "Selecciona una tarjeta de origen" }
        if (service != EnzonaInvoiceService.ELECTRICITY)
            require(lookup?.etecsa?.success == "true") { "No se pudo consultar la cuenta" }
        enzonaInvoiceDraft(service, bill, account, amountText.replace(',', '.').toBigDecimalOrNull(),
            originId.orEmpty(), currency.orEmpty())
    }
    fun changeIdentifier(value: String) {
        identifier = value; lookup = null; queriedIdentifier = null; selection = null
        amountText = ""; error = null; review = null; requestVersion++
    }
    DisposableEffect(service, owner) { onDispose { requestVersion++ } }
    LaunchedEffect(state.wallet.services, savingReference) {
        savingReference?.let { expected ->
            if (state.wallet.services.any { it == expected }) { savingReference = null; namingReference = false }
        }
    }
    LaunchedEffect(state.notice) {
        if (savingReference != null && state.notice != null) { referenceError = "No se pudo guardar la referencia"; savingReference = null }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(service.title(), back)
        ChoiceField(product?.let { listOfNotNull(it.name, it.numberLabel()).joinToString(" · ") } ?: "Tarjeta de origen",
            products.map { requireNotNull(it.enzonaCardId) to listOfNotNull(it.name, it.numberLabel()).joinToString(" · ") }) {
            originId = it; review = null; error = null; actions.selectProduct("enzona:$it")
        }
        if (owner == null || !state.enzonaSession.connected) {
            Primary("Conectar ENZONA", !state.enzonaSession.busy, actions.connectEnzona)
        } else if (products.isEmpty()) {
            EmptyAction("No hay tarjetas disponibles", "Actualizar tarjetas", actions.refreshEnzonaCards)
        }
        if (card != null && currency.isNullOrBlank()) {
            Text("Falta la moneda de la tarjeta", color = MaterialTheme.colorScheme.error)
            TextButton(onClick = actions.refreshEnzonaCards, enabled = !state.enzonaWallet.busy) { Text("Actualizar tarjetas") }
        } else currency?.let { Detail("Moneda de origen", it) }
        if (references.isNotEmpty()) ChoiceField("Elegir referencia guardada",
            if (querying) emptyList() else references.map { it.id to "${it.label} · ${it.identifier}" }) { id ->
            references.singleOrNull { it.id == id }?.let { changeIdentifier(it.identifier) }
        }
        Field(when (service) {
            EnzonaInvoiceService.ELECTRICITY -> "Identificador del cliente"
            EnzonaInvoiceService.FIXED_PHONE -> "Número de teléfono fijo"
            EnzonaInvoiceService.NAUTA -> "Cuenta Nauta completa"
            EnzonaInvoiceService.NAUTA_HOME -> "Cuenta Nauta Hogar completa"
        }, identifier, ::changeIdentifier, if (topUp) KeyboardType.Email else KeyboardType.Number, readOnly = querying)
        OutlinedButton(onClick = {
            val requested = identifier
            val requestedOwner = owner
            val version = ++requestVersion
            querying = true; error = null; lookup = null; queriedIdentifier = null; selection = null; review = null
            runCatching {
                actions.queryEnzonaService(service, requested) { response ->
                    if (version == requestVersion && requestedOwner == currentOwner && requested == currentIdentifier) {
                        querying = false
                        if (response == null || response.service != service) error = "No se pudo consultar el servicio"
                        else {
                            lookup = response; queriedIdentifier = requested
                            val count = if (service == EnzonaInvoiceService.ELECTRICITY) response.bills.size
                                else response.etecsa?.accounts.orEmpty().count { it.service == service }
                            selection = 0.takeIf { count == 1 }
                        }
                    }
                }
            }.onFailure { querying = false; error = "No se pudo consultar el servicio" }
        }, enabled = queryValid && owner != null && state.enzonaSession.connected && !querying && !state.busy,
            modifier = Modifier.fillMaxWidth()) { Text(if (querying) "Consultando" else "Consultar") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        lookup?.let { response ->
            if (service == EnzonaInvoiceService.ELECTRICITY) {
                if (bills.isEmpty()) Text("No hay facturas disponibles")
                if (bills.size > 1) ChoiceField(bill?.let { "${it.period.orEmpty()} · ${enzonaAmount(it.amount, currency)}" } ?: "Elegir factura",
                    bills.mapIndexed { index, item -> index.toString() to listOfNotNull(item.period, item.owner,
                        enzonaAmount(item.amount, currency), "Pagada".takeIf { item.charged == true }).joinToString(" · ") }) {
                    selection = it.toInt(); error = null
                }
                bill?.let {
                    it.clientId?.let { value -> Detail("Identificador", value) }
                    it.owner?.let { value -> Detail("Titular", value) }
                    it.period?.let { value -> Detail("Período", value) }
                    Detail("Importe", enzonaAmount(it.amount, currency))
                    it.totalAmount?.takeIf { total -> total != it.amount }?.let { total -> Detail("Total informado", enzonaAmount(total, currency)) }
                    if (it.charged == true) Text("Factura pagada")
                }
            } else if (response.etecsa?.success != "true") {
                Text("No se pudo consultar la cuenta", color = MaterialTheme.colorScheme.error)
            } else {
                if (accounts.isEmpty()) Text("No hay cuentas disponibles para este servicio")
                if (accounts.size > 1) ChoiceField(account?.serviceName ?: "Elegir cuenta",
                    accounts.mapIndexed { index, item -> index.toString() to (item.serviceName ?: "Cuenta no disponible") }) {
                    selection = it.toInt(); amountText = ""; error = null
                }
                account?.let {
                    it.serviceName?.takeUnless { value -> value == queriedIdentifier }?.let { value -> Detail("Cuenta", value) }
                    if (it.isValid != "true") Text(if (it.isValid == "false") "Cuenta no válida" else "No se pudo consultar la cuenta",
                        color = MaterialTheme.colorScheme.error)
                    if (topUp) {
                        it.minimumAmount?.let { value -> Detail("Importe mínimo", enzonaAmount(value, currency)) }
                        it.maximumAmount?.let { value -> Detail("Importe máximo", enzonaAmount(value, currency)) }
                        Field("Importe", amountText, { value -> amountText = value; error = null }, KeyboardType.Decimal, readOnly = it.isValid != "true")
                        if (it.isValid == "true" && amountText.isNotBlank()) draftResult.exceptionOrNull()?.message?.let { message -> Text(message, color = MaterialTheme.colorScheme.error) }
                    } else Detail("Importe a pagar", enzonaAmount(it.toPay, currency))
                }
            }
            TextButton(onClick = {
                referenceName = references.singleOrNull { it.identifier == queriedIdentifier }?.label.orEmpty()
                referenceError = null; namingReference = true
            }, enabled = queriedIdentifier == identifier && (bill != null || account?.isValid == "true")) { Text("Guardar referencia") }
            if (selection != null && !topUp && draftResult.isFailure && bill?.charged != true &&
                (service == EnzonaInvoiceService.ELECTRICITY || account?.isValid == "true"))
                draftResult.exceptionOrNull()?.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Primary("Continuar", draftResult.isSuccess && !querying && !state.busy && state.enzonaSession.connected) {
                draftResult.getOrNull()?.let { draft ->
                    review = InvoiceReview(draft, product?.let { listOfNotNull(it.name, it.numberLabel()).joinToString(" · ") }.orEmpty())
                }
            }
        }
    }
    if (namingReference) AlertDialog(onDismissRequest = { if (savingReference == null) namingReference = false },
        title = { Text("Guardar referencia") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(queriedIdentifier.orEmpty())
            Field("Nombre", referenceName, { referenceName = it; referenceError = null }, readOnly = savingReference != null)
            referenceError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = {
            val value = queriedIdentifier ?: return@TextButton
            val existing = references.singleOrNull { it.identifier == value }
            val reference = SavedServiceRecord(existing?.id ?: UUID.randomUUID().toString(), referenceKind,
                referenceName.trim(), value, fieldKey = "clientId")
            actions.dismissNotice(); savingReference = reference
            runCatching { actions.saveService(reference) }.onFailure { referenceError = "No se pudo guardar la referencia"; savingReference = null }
        }, enabled = referenceName.isNotBlank() && savingReference == null) { Text("Guardar") } },
        dismissButton = { TextButton(onClick = { namingReference = false }, enabled = savingReference == null) { Text("Cancelar") } })
    review?.let { captured ->
        EnzonaPaymentReview(captured.draft, captured.origin, actions,
            dismiss = { review = null }, completed = { review = null; back() })
    }
}
