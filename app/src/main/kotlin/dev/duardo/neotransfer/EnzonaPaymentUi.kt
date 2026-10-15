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
import dev.duardo.neotransfer.enzona.EnzonaCardTransferDraft
import dev.duardo.neotransfer.enzona.EnzonaPaymentDraft
import dev.duardo.neotransfer.enzona.EnzonaAccountTransferDraft
import dev.duardo.neotransfer.enzona.EnzonaQrMerchantPaymentDraft
import dev.duardo.neotransfer.enzona.EnzonaInvoicePaymentDraft

@Composable
internal fun EnzonaTransferScreen(state: AppUiState, actions: UiActions, back: () -> Unit,
                                  initialDestination: String = "", initialPhone: String = "",
                                  scan: (Boolean, (String) -> Unit) -> Unit) {
    val products = walletProducts(state).filter { it.enzonaCardId != null }
    var origin by rememberSaveable { mutableStateOf(state.enzonaWallet.selectedCardId) }
    var destination by rememberSaveable(initialDestination) { mutableStateOf(initialDestination) }
    var phone by rememberSaveable(initialPhone) { mutableStateOf(initialPhone) }
    var amount by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var review by remember { mutableStateOf<EnzonaPaymentDraft?>(null) }
    val product = products.singleOrNull { it.enzonaCardId == origin }
    val remote = state.enzonaWallet.cards.singleOrNull { it.fundingSourceUuid == origin }
    val recipients = state.wallet.contacts.flatMap { contact -> contact.cards.map { contact.name to it.number } }
    val recipientName = state.wallet.contacts.singleOrNull { contact -> contact.cards.any { it.number == destination } }?.name
    val amountValue = amount.replace(',', '.').toBigDecimalOrNull()
    val fieldsComplete = destination.matches(Regex("[0-9]{16}")) && amountValue?.signum() == 1 &&
        (phone.isEmpty() || phone.matches(Regex("[0-9]{8}")))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader("Transferir", back)
        ChoiceField(product?.let { listOfNotNull(it.name, it.numberLabel()).joinToString(" · ") } ?: "Tarjeta de origen",
            products.map { requireNotNull(it.enzonaCardId) to listOfNotNull(it.name, it.numberLabel()).joinToString(" · ") }) { origin = it }
        if (recipients.isNotEmpty()) ChoiceField(recipientName ?: "Elegir contacto", recipients.map { it.second to "${it.first} · ${readableAccount(it.second)}" }) { destination = it }
        Field("Tarjeta de destino", destination, { destination = it.filter(Char::isDigit).take(16) }, KeyboardType.Number)
        TextButton(onClick = { scan(false) { destination = it } }) { Text("Escanear tarjeta") }
        Field("Importe${remote?.currency?.let { " ($it)" }.orEmpty()}", amount, { amount = it }, KeyboardType.Decimal)
        Field("Móvil del destinatario (opcional)", phone, { phone = it.filter(Char::isDigit).take(8) }, KeyboardType.Phone)
        Field("Concepto (opcional)", description, { description = it })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Primary("Continuar", enabled = !state.busy && product != null && fieldsComplete) {
            val number = amount.replace(',', '.').toBigDecimalOrNull()
            error = when {
                remote?.currency.isNullOrBlank() -> "Consulta la moneda de la tarjeta"
                !destination.matches(Regex("[0-9]{16}")) -> "Introduce los 16 dígitos de destino"
                number == null || number.signum() <= 0 -> "Introduce un importe mayor que cero"
                phone.isNotEmpty() && !phone.matches(Regex("[0-9]{8}")) -> "Introduce un móvil de 8 dígitos"
                else -> null
            }
            if (error == null) review = EnzonaCardTransferDraft(requireNotNull(origin), destination,
                requireNotNull(number), requireNotNull(remote?.currency), description, phone)
        }
    }
    review?.let { reviewed -> EnzonaPaymentReview(reviewed, product?.let { listOfNotNull(it.name, it.numberLabel()).joinToString(" · ") }.orEmpty(), actions,
        dismiss = { review = null }, completed = { review = null; back() },
        destinationLabel = recipientName?.let { "$it · ${readableAccount(destination)}" }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EnzonaPaymentReview(draft: EnzonaPaymentDraft, origin: String, actions: UiActions,
                                 dismiss: () -> Unit, completed: () -> Unit, destinationLabel: String? = null) {
    var password by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) { actions.protectScreen(true); onDispose { active = false; password = "" } }
    ModalBottomSheet(onDismissRequest = { if (!submitting) dismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Revisar pago", style = MaterialTheme.typography.headlineSmall)
            Text(enzonaAmount(draft.amount, draft.currency), style = MaterialTheme.typography.headlineLarge)
            Text("Desde $origin")
            if (destinationLabel != null) Text("A $destinationLabel") else when (draft) {
                is EnzonaCardTransferDraft -> Text("A ${readableAccount(draft.recipientPan)}")
                is EnzonaAccountTransferDraft -> Text("A ${draft.fundingRecipientUsername}")
                is EnzonaQrMerchantPaymentDraft -> Text("Destino: ${draft.vendorIdentityCode}")
                is EnzonaInvoicePaymentDraft -> listOf(draft.owner, draft.clientId).distinct().forEach { Text(it) }
            }
            draft.description?.takeIf(String::isNotBlank)?.let { Text(it) }
            Field("Contraseña de pago de ENZONA", password, { password = it }, KeyboardType.Password, secret = true)
            result?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Primary(if (submitting) "Autorizando" else if (draft is EnzonaCardTransferDraft || draft is EnzonaAccountTransferDraft) "Transferir" else "Pagar", enabled = !submitting && password.isNotEmpty()) {
                submitting = true
                result = null
                val credential = password.toCharArray()
                password = ""
                actions.payEnzona(draft, credential) { outcome ->
                    if (active) {
                        submitting = false
                        if (outcome == null) completed() else result = outcome
                    }
                }
            }
            TextButton(onClick = dismiss, enabled = !submitting) { Text("Cancelar") }
        }
    }
}
