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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.duardo.neotransfer.platform.OwnCardQr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ReceiveQrScreen(state: AppUiState, actions: UiActions, back: () -> Unit) {
    val products = walletProducts(state).filter { it.number?.matches(Regex("[0-9]{16}")) == true }
    var id by rememberSaveable { mutableStateOf(initialCompatibleProduct(state, products)?.id ?: products.firstOrNull()?.id) }
    val selected = products.firstOrNull { it.id == id }
    val registration = state.wallet.registrations.firstOrNull { it.id == selected?.registrationId }
    var phone by rememberSaveable(id) { mutableStateOf(registration?.linePhone?.takeIf { it.matches(Regex("[0-9]{8}")) }.orEmpty()) }
    val encoded = selected?.number?.let { runCatching { OwnCardQr.encode(it, phone) }.getOrNull() }
    val bitmap by produceState<Bitmap?>(null, encoded) {
        value = null
        value = encoded?.let { text -> withContext(Dispatchers.Default) {
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 640, 640, mapOf(EncodeHintType.MARGIN to 4))
            val pixels = IntArray(matrix.width * matrix.height) { index ->
                if (matrix[index % matrix.width, index / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
            Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        } }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        PageHeader("Recibir transferencia", back)
        if (products.isEmpty()) Text("Añade el número de tu tarjeta en la cartera para crear su QR.")
        else {
            ChoiceField(selected?.let { "${it.identity.title()} · ${it.name}" } ?: "Elegir tarjeta", products.map { it.id to "${it.identity.title()} · ${it.name}" }) { id = it }
            selected?.number?.let { Text(readableAccount(it), style = MaterialTheme.typography.titleLarge) }
            Field("Móvil a notificar (opcional)", phone, { phone = digits(it, 8) }, KeyboardType.Phone)
            bitmap?.let { Image(it.asImageBitmap(), "QR para recibir en ${selected?.name.orEmpty()}", Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White)) }
            if (encoded != null) TextButton(onClick = { actions.share(encoded) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Compartir datos de transferencia") }
        }
    }
}
