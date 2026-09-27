package dev.duardo.neotransfer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.core.*
import dev.duardo.neotransfer.data.ContactCard
import dev.duardo.neotransfer.data.ContactRecord
import java.math.BigDecimal

internal enum class PaymentPage { TRANSFER, RECHARGE, BILLS }
internal data class PaymentDraft(val destination: String = "", val amount: String = "", val phone: String = "", val name: String = "", val electricity: Boolean = true)

@Composable
internal fun MoneyForm(page: PaymentPage, state: AppUiState, actions: UiActions, draft: PaymentDraft,
                       change: (PaymentDraft) -> Unit, back: () -> Unit, review: (MoneyAction) -> Unit,
                       configure: (ProviderIdentity) -> Unit, addSource: () -> Unit,
                       scan: (Boolean, (String) -> Unit) -> Unit) {
    val products = walletProducts(state).filter { it.currency != Currency.CUC && it.identity.bank != null && it.identity.bank != Bank.BFI &&
        (page == PaymentPage.TRANSFER || it.identity.bank in listOf(Bank.BPA, Bank.BANDEC)) &&
        (it.source == SourceSelector.Default || it.source.wireValue.matches(Regex("[0-9]{16}"))) }
    var productId by rememberSaveable { mutableStateOf(initialCompatibleProduct(state, products)?.id) }
    var currency by rememberSaveable { mutableStateOf<String?>(null) }
    var agenda by remember { mutableStateOf(false) }
    var phoneAgenda by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val product = products.firstOrNull { it.id == productId }
    val bank = product?.identity?.bank
    val bankCurrency = (product?.currency ?: currency?.let { Currency.valueOf(it) })
        ?.takeIf { it in CurrencyContract.BANK.active }
    val transfer = page == PaymentPage.TRANSFER
    val recharge = page == PaymentPage.RECHARGE
    val fullBill = page == PaymentPage.BILLS && draft.electricity
    val title = if (transfer) "Transferir" else if (recharge) "Recargar móvil" else "Pagar factura"
    val validDestination = draft.destination.matches(when {
        transfer -> Regex("[0-9]{16}")
        recharge -> Regex("[0-9]{8}|[0-9]{10}")
        draft.electricity -> Regex("[0-9]{11}|[0-9]{13}")
        else -> Regex("[0-9]{14,15}")
    })
    val ownCards = state.wallet.cards.map { ContactCard(it.number, it.label) }
    val destinations = state.wallet.contacts + if (ownCards.isEmpty()) emptyList() else listOf(ContactRecord("own-wallet", "Mi cartera", cards = ownCards))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PageHeader(title, back)
        Text("Desde", style = MaterialTheme.typography.titleMedium)
        SourcePicker(products, product, { value -> productId = value.id; actions.selectProduct(value.id); currency = null }, addSource)
        if (product != null && !hasProductAccess(state, product)) Primary("Configurar ${product.identity.title()}", !state.busy) { configure(requireNotNull(product).identity) }
        if (page == PaymentPage.BILLS) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(draft.electricity, { change(draft.copy(electricity = true, destination = "")) }, label = { Text("Electricidad") })
            FilterChip(!draft.electricity, { change(draft.copy(electricity = false, destination = "")) }, label = { Text("Teléfono") })
        }
        Text(if (page == PaymentPage.BILLS) "Factura" else "Para", style = MaterialTheme.typography.titleMedium)
        if (transfer || recharge) TextButton(onClick = { agenda = true }) { Icon(painterResource(R.drawable.ic_contacts), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (transfer) "Elegir tarjeta guardada" else "Elegir teléfono guardado") }
        Field(if (transfer) "Tarjeta de destino" else if (recharge) "Número móvil" else "Identificador de factura", draft.destination,
            { change(draft.copy(destination = digits(it, if (transfer) 16 else if (recharge) 10 else 15), name = "")) }, KeyboardType.Number,
            trailing = if (transfer || recharge) { { IconButton(onClick = { scan(recharge) { change(draft.copy(destination = it, name = "")) } }) { Icon(painterResource(R.drawable.ic_qr_code_scanner), "Leer ${if (transfer) "tarjeta" else "móvil"}") } } } else null)
        if (draft.name.isNotBlank()) Text(draft.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (transfer && bankCurrency == null) ChoiceField("Moneda del origen", CurrencyContract.BANK.active.map { it.name to it.name }) { currency = it }
        if (!fullBill) Field("Importe${if (transfer) bankCurrency?.let { " · $it" }.orEmpty() else " · CUP"}", draft.amount,
            { change(draft.copy(amount = decimalInput(it, draft.amount))) }, KeyboardType.Decimal)
        else Detail("Importe", "Factura completa")
        if (transfer) {
            Field("Móvil a notificar (opcional)", draft.phone, { change(draft.copy(phone = digits(it, 8))) }, KeyboardType.Phone)
            TextButton(onClick = { phoneAgenda = true }) { Text("Elegir móvil de contacto") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.pending != null) Text("Hay una operación pendiente de resultado", color = MaterialTheme.colorScheme.error)
        Primary("Revisar ${if (transfer) "transferencia" else "pago"}", enabled = product != null && hasProductAccess(state, product) && !state.busy && state.pending == null && validDestination &&
            (fullBill || validAmount(draft.amount)) && (draft.phone.isEmpty() || draft.phone.matches(Regex("[0-9]{8}"))) && (!transfer || bankCurrency != null)) {
            runCatching {
                require(!transfer || bankCurrency in CurrencyContract.BANK.active) { "CUC ya no está disponible" }
                val amount = Money(if (fullBill) BigDecimal.ZERO else draft.amount.toBigDecimal(), if (transfer) requireNotNull(bankCurrency) else Currency.CUP)
                val kind = if (transfer) ActionKind.TRANSFER else if (recharge) ActionKind.RECHARGE else if (draft.electricity) ActionKind.ELECTRICITY else ActionKind.TELEPHONE
                val action = MoneyAction(kind, requireNotNull(bank), draft.destination, amount, requireNotNull(product).source.wireValue, draft.phone.takeIf(String::isNotEmpty))
                if (transfer) BankCommands().transfer(action.transferRequest(), 0)
                if (recharge) BankCommands().recharge(action.bank, action.destination, action.amount, action.source)
                if (page == PaymentPage.BILLS) BankCommands().bill(action.bank, draft.electricity, action.destination, action.amount, action.source)
                review(action)
            }.onFailure { error = it.message ?: "Revisa los datos" }
        }
    }
    if (agenda) ContactDestinationPicker(if (recharge) state.wallet.contacts else destinations, recharge, { agenda = false }) { value, name -> change(draft.copy(destination = value, name = name)); agenda = false }
    if (phoneAgenda) ContactDestinationPicker(state.wallet.contacts, true, { phoneAgenda = false }) { value, _ -> change(draft.copy(phone = value)); phoneAgenda = false }
}

@Composable
internal fun SourcePicker(products: List<WalletProductUi>, selected: WalletProductUi?, choose: (WalletProductUi) -> Unit, add: () -> Unit) {
    ChoiceField(selected?.let { "${it.identity.title()} · ${it.name}" } ?: "Elegir tarjeta o cuenta",
        products.map { it.id to "${it.identity.title()} · ${it.name} · ${it.number?.let(::readableAccount).orEmpty()}" } + ("add" to "Añadir tarjeta o cuenta")) { key ->
        if (key == "add") add() else products.firstOrNull { it.id == key }?.let(choose)
    }
    selected?.number?.let { Text(readableAccount(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoneyReview(action: MoneyAction, state: AppUiState, actions: UiActions, dismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = { if (!state.busy) dismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Revisar ${action.kind.label.lowercase()}", style = MaterialTheme.typography.headlineSmall)
            Text(if (action.amount.amount.signum() == 0) "Factura completa" else formatMoney(action.amount), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
            Detail("Desde · ${action.bank.name}", if (action.source == "0000") "Cuenta predeterminada" else readableAccount(action.source))
            Detail(if (action.kind == ActionKind.TRANSFER) "Para" else if (action.kind == ActionKind.RECHARGE) "Móvil" else "Factura", readableAccount(action.destination))
            action.phone?.let { Detail("Notificar al móvil", it) }
            if (action.amount.currency == Currency.CUC) Text("CUC ya no está disponible", color = MaterialTheme.colorScheme.error)
            Primary(if (action.kind == ActionKind.TRANSFER) "Transferir" else "Pagar",
                !state.busy && state.pending == null && action.amount.currency != Currency.CUC) { actions.pay(action) }
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QrPaymentReview(qr: QrPayment, state: AppUiState, actions: UiActions, dismiss: () -> Unit, addSource: () -> Unit, configure: (ProviderIdentity) -> Unit) {
    val products = walletProducts(state).filter { it.currency != Currency.CUC &&
        when {
            it.identity.provider == ProviderId.MITRANSFER -> it.identity.profile in setOf(ProfileId.PERSONAL, ProfileId.CLASSIC)
            else -> it.identity.bank != null && (it.currency == null || it.currency == qr.amount.currency)
        }
    }
    var productId by rememberSaveable { mutableStateOf(initialCompatibleProduct(state, products)?.id) }
    val product = products.firstOrNull { it.id == productId }
    val bank = product?.identity?.bank
    var amount by rememberSaveable(qr.transactionId) { mutableStateOf(if (qr.editableAmount) "" else qr.amount.amount.toPlainString()) }
    var description by rememberSaveable(qr.transactionId) { mutableStateOf(qr.description) }
    var phone by rememberSaveable(qr.transactionId) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val wallet = product?.identity?.provider == ProviderId.MITRANSFER
    val linkedDefault = product?.identity?.profile == ProfileId.CLASSIC && qr.service == 30 && qr.auxiliary.contains("11200301")
    ModalBottomSheet(onDismissRequest = { if (!state.busy) dismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Pago QR", style = MaterialTheme.typography.headlineSmall)
            if (linkedDefault) {
                ChoiceField("Clásica vinculada", products.map { it.id to if (it.identity.profile == ProfileId.CLASSIC) "Clásica vinculada · ${it.identity.title()}" else "${it.identity.title()} · ${it.name}" }) { id ->
                    products.firstOrNull { it.id == id }?.let { productId = it.id; actions.selectProduct(it.id) }
                }
            } else SourcePicker(products, product, { productId = it.id; actions.selectProduct(it.id) }, addSource)
            Detail("Proveedor", qr.provider); Detail("Referencia", qr.transactionId)
            Field("Importe · ${qr.amount.currency}", amount, { amount = decimalInput(it, amount) }, KeyboardType.Decimal, readOnly = !qr.editableAmount)
            Field(qr.descriptionHint.ifEmpty { "Descripción" }, description, { description = it }, readOnly = qr.description.isNotEmpty())
            Field("Móvil a notificar (opcional)", phone, { phone = digits(it, 8) }, KeyboardType.Phone)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (qr.amount.currency == Currency.CUC) Text("CUC ya no está disponible", color = MaterialTheme.colorScheme.error)
            if (product != null && !hasProductAccess(state, product) && qr.amount.currency != Currency.CUC)
                Primary("Configurar ${product.identity.title()}") { configure(requireNotNull(product).identity) }
            else Primary("Pagar", product != null && selectedProduct(state)?.id == product.id && qr.amount.currency in CurrencyContract.BANK.active &&
                !state.busy && state.pending == null && validAmount(amount) && (phone.isEmpty() || phone.matches(Regex("[0-9]{8}")))) {
                runCatching {
                    require(qr.amount.currency in CurrencyContract.BANK.active) { "CUC ya no está disponible" }
                    require(product != null && selectedProduct(state)?.id == product.id) { "Espera a que se seleccione el origen" }
                    if (wallet) {
                        val chosen = requireNotNull(product)
                        val source = if (linkedDefault || chosen.identity.profile == ProfileId.PERSONAL && wallet) SourceSelector.Default else chosen.source
                        val request = WalletQrOperations.fromQr(chosen.identity, source, chosen.currency, qr,
                            Money(amount.toBigDecimal(), qr.amount.currency), description, phone.takeIf(String::isNotEmpty))
                        val errors = WalletQrOperations.validate(request) + actions.validateService(request)
                        require(errors.isEmpty()) { errors.joinToString("\n") { it.message } }
                        actions.runQrService(request, qr)
                        return@runCatching
                    }
                    val action = MoneyAction(ActionKind.QR, requireNotNull(bank), qr.provider, Money(amount.toBigDecimal(), qr.amount.currency),
                        requireNotNull(product).source.wireValue, phone.takeIf(String::isNotEmpty), qr, description)
                    BankCommands().qrPayment(action.bank, CharArray(action.bank.pinLength) { '0' }, qr, action.amount, description, action.source, action.phone, "000", 0)
                    actions.pay(action)
                }.onFailure { error = it.message }
            }
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
