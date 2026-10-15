package dev.duardo.neotransfer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.duardo.neotransfer.enzona.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EnzonaQrReview(qr: EnzonaResolvedQr, state: AppUiState, actions: UiActions, dismiss: () -> Unit) {
    var source by remember { mutableStateOf(state.enzonaWallet.selectedCardId) }
    var amount by remember { mutableStateOf(qr.amount?.toPlainString().orEmpty()) }
    var phone by remember { mutableStateOf("") }
    var review by remember { mutableStateOf<EnzonaPaymentDraft?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val products = walletProducts(state).filter { it.enzonaCardId != null }
    val selected = products.singleOrNull { it.enzonaCardId == source }
    val remote = state.enzonaWallet.cards.singleOrNull { it.fundingSourceUuid == source }
    if (review == null) ModalBottomSheet(onDismissRequest = dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(qr.name ?: qr.username ?: "Pago por QR", style = MaterialTheme.typography.headlineSmall)
            qr.description?.takeIf(String::isNotBlank)?.let { Text(it) }
            ChoiceField(selected?.let { listOfNotNull(it.name, it.numberLabel()).joinToString(" · ") } ?: "Tarjeta de origen", products.map { requireNotNull(it.enzonaCardId) to
                listOfNotNull(it.name, it.numberLabel()).joinToString(" · ") }) { source = it }
            Field("Importe${qr.currency?.let { " ($it)" }.orEmpty()}", amount, { amount = it }, KeyboardType.Decimal,
                readOnly = qr.amount?.signum() == 1)
            if (qr.kind == EnzonaQrKind.MERCHANT_PAYMENT) Field("Móvil (opcional)", phone, { phone = it.filter(Char::isDigit).take(8) }, KeyboardType.Phone)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Primary("Continuar", enabled = selected != null && !state.busy) {
                val number = amount.replace(',', '.').toBigDecimalOrNull()
                val currency = qr.currency ?: remote?.currency
                error = when {
                    qr.ownerSubject != state.enzonaSession.ownerSubject -> "La cuenta de ENZONA cambió"
                    currency == null || currency != remote?.currency -> "Elige una tarjeta en la moneda del pago"
                    number == null || number.signum() <= 0 -> "Introduce un importe mayor que cero"
                    qr.kind == EnzonaQrKind.MERCHANT_INFO -> "Este QR contiene información del comercio"
                    qr.kind == EnzonaQrKind.ACCOUNT && (qr.accountUuid == null || qr.username == null) -> "El QR no contiene un destinatario completo"
                    qr.kind == EnzonaQrKind.MERCHANT_PAYMENT && qr.vendorIdentityCode == null -> "El QR no contiene un código de pago"
                    else -> null
                }
                if (error == null) review = when (qr.kind) {
                    EnzonaQrKind.ACCOUNT -> EnzonaAccountTransferDraft(requireNotNull(source), requireNotNull(qr.accountUuid),
                        requireNotNull(qr.username), requireNotNull(number), requireNotNull(currency), qr.description)
                    EnzonaQrKind.MERCHANT_PAYMENT -> EnzonaQrMerchantPaymentDraft(requireNotNull(source), requireNotNull(qr.vendorIdentityCode),
                        requireNotNull(number), requireNotNull(currency), qr.description, phone)
                    EnzonaQrKind.MERCHANT_INFO -> null
                }
            }
        }
    }
    review?.let { EnzonaPaymentReview(it, selected?.let { product -> listOfNotNull(product.name, product.numberLabel()).joinToString(" · ") }.orEmpty(), actions, { review = null }, dismiss,
        destinationLabel = qr.name ?: qr.username) }
}

@Composable
internal fun EnzonaReceiveScreen(state: AppUiState, actions: UiActions, back: () -> Unit) {
    val card = state.enzonaWallet.cards.singleOrNull { it.fundingSourceUuid == state.enzonaWallet.selectedCardId }
    var amount by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var issued by remember { mutableStateOf<EnzonaOwnQr?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var queryVersion by remember { mutableLongStateOf(0L) }
    val owner = state.enzonaSession.ownerSubject
    val currentOwner by rememberUpdatedState(owner)
    val currentCard by rememberUpdatedState(card?.fundingSourceUuid)
    DisposableEffect(owner, card?.fundingSourceUuid) {
        issued = null; queryVersion++
        onDispose { queryVersion++ }
    }
    val bitmap by produceState<Bitmap?>(null, issued?.code) {
        value = issued?.code?.let { code -> withContext(Dispatchers.Default) {
            val matrix = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, 640, 640, mapOf(EncodeHintType.MARGIN to 4))
            Bitmap.createBitmap(IntArray(640 * 640) { index -> if (matrix[index % 640, index / 640]) android.graphics.Color.BLACK
                else android.graphics.Color.WHITE }, 640, 640, Bitmap.Config.ARGB_8888)
        } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PageHeader("Recibir con ENZONA", back)
        Field("Importe${card?.currency?.let { " ($it)" }.orEmpty()}", amount, { amount = it; issued = null; error = null; queryVersion++ }, KeyboardType.Decimal)
        Field("Concepto (opcional)", description, { description = it; issued = null; error = null; queryVersion++ })
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Primary("Crear QR", enabled = !state.busy && card?.currency != null) {
            val number = amount.replace(',', '.').toBigDecimalOrNull()
            if (number == null || number.signum() <= 0) error = "Introduce un importe mayor que cero"
            else {
                error = null
                val expectedVersion = ++queryVersion
                val expectedCard = card?.fundingSourceUuid
                actions.createEnzonaQr(number, requireNotNull(card?.currency), description) {
                    if (expectedVersion == queryVersion && owner == currentOwner && expectedCard == currentCard) {
                        issued = it
                        if (it == null) error = "No se pudo crear el QR"
                    }
                }
            }
        }
        bitmap?.let { Image(it.asImageBitmap(), "QR para recibir en ENZONA", Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White)) }
        issued?.let { qr -> TextButton(onClick = { actions.share(qr.code) }) { Text("Compartir QR") } }
    }
}
