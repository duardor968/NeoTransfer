package dev.duardo.neotransfer

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dev.duardo.neotransfer.core.fuel.FuelCoupon
import dev.duardo.neotransfer.fuel.FuelController
import dev.duardo.neotransfer.fuel.FuelPresentation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class FuelActions(
    val openOperation: (id: String, initialValues: Map<String, String>) -> Unit,
    val rename: (couponId: String, label: String) -> Unit,
    val archive: (couponId: String, archived: Boolean) -> Unit,
    val authorizeSecret: (title: String, subtitle: String, result: (Boolean) -> Unit) -> Unit,
)

/** A coupon list and one detail surface; existing service forms own bank selection and review. */
@Composable
internal fun FuelScreen(coupons: List<FuelCoupon>, controller: FuelController, actions: FuelActions, back: () -> Unit,
                        onSensitiveChanged: (Boolean) -> Unit = {}) {
    val local by controller.state.collectAsState()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var archived by rememberSaveable { mutableStateOf(false) }
    var editingLabel by remember { mutableStateOf(false) }
    val selected = coupons.singleOrNull { it.id == selectedId }
    val sensitiveCallback by rememberUpdatedState(onSensitiveChanged)
    SideEffect { onSensitiveChanged(local.presentation != null) }
    LaunchedEffect(coupons) { controller.syncCoupons(coupons) }
    DisposableEffect(controller) { onDispose { controller.clearSensitive(); sensitiveCallback(false) } }
    BackHandler(enabled = selected != null && local.presentation == null) { controller.clearSensitive(); selectedId = null }
    fun open(id: String, values: Map<String, String> = emptyMap()) {
        controller.clearSensitive()
        actions.openOperation(id, values)
    }
    if (selected == null) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 24.dp)) {
            item { PageHeader("Combustible", back) }
            item { Primary("Comprar cupón") { open("service.fuel") } }
            item { TextButton(onClick = { open("service.fuel.list") }) { Text("Consultar cupones por fecha") } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilterChip(selected = !archived, onClick = { archived = false }, label = { Text("Mis cupones") })
                    FilterChip(selected = archived, onClick = { archived = true }, label = { Text("Archivados") })
                }
            }
            val visible = coupons.filter { it.archived == archived }.sortedByDescending { it.observedAt }
            if (visible.isEmpty()) item {
                Text(if (archived) "No tienes cupones archivados." else "Tus cupones aparecerán aquí al recibirlos.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
            }
            items(visible, key = { it.id }) { coupon ->
                ListItem(headlineContent = { Text(coupon.label.ifBlank { "Cupón ${coupon.serial}" }) },
                    supportingContent = { Text(if (coupon.label.isBlank()) fuelSummary(coupon) else "${coupon.serial} · ${fuelSummary(coupon)}") },
                    modifier = Modifier.clickable { selectedId = coupon.id; controller.clearSensitive() },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                HorizontalDivider()
            }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            PageHeader(selected.label.ifBlank { "Cupón de combustible" }, { controller.clearSensitive(); selectedId = null })
            Text(selected.serial, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            selected.balance?.let { Detail("Saldo consultado", fuelAmount(it, selected.currency)) }
            selected.balanceObservedAt?.let { Text(dateText(Instant.ofEpochMilli(it)), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            selected.paidAmount?.let { Detail("Importe pagado", fuelAmount(it, selected.currency)) }
            selected.reportedDate?.let { date -> runCatching { LocalDate.parse(date) }.getOrNull()?.let {
                Detail("Fecha del cupón", it.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("es"))))
            } }
            if (selected.ambiguous) Text("Hay datos distintos para este cupón. Consulta su estado para actualizarlo.", color = MaterialTheme.colorScheme.error)
            if (selected.secretRevision == null) Text("Consulta el estado del cupón para recuperar su QR.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Primary(if (local.busy) "Abriendo cupón…" else "Mostrar cupón", !local.busy && !selected.ambiguous && selected.secretRevision != null) {
                controller.showCoupon(selected, actions.authorizeSecret)
            }
            local.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val values = mapOf("serial" to selected.serial)
            Column {
                ActionRow("Consultar estado", R.drawable.ic_search) { open("service.fuel.status", values) }
                ActionRow("Últimas operaciones", R.drawable.ic_receipt_long) { open("service.fuel.movements", values) }
                ActionRow("Actualizar clave del cupón", R.drawable.ic_security) { open("service.fuel.refresh-key", values) }
            }
            HorizontalDivider()
            Detail("Referencia bancaria", selected.bankReference)
            Detail("Referencia de Transfermóvil", selected.tmReference)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { editingLabel = true }) { Text("Cambiar nombre") }
                TextButton(onClick = { actions.archive(selected.id, !selected.archived); controller.clearSensitive(); selectedId = null }) {
                    Text(if (selected.archived) "Desarchivar" else "Archivar")
                }
            }
        }
    }
    if (editingLabel && selected != null) {
        var label by remember(selected.id) { mutableStateOf(selected.label) }
        AlertDialog(onDismissRequest = { editingLabel = false }, title = { Text("Nombre del cupón") },
            text = { Field("Nombre", label, { label = it }) },
            confirmButton = { TextButton(onClick = { actions.rename(selected.id, label.trim()); editingLabel = false }) { Text("Guardar") } },
            dismissButton = { TextButton(onClick = { editingLabel = false }) { Text("Cancelar") } })
    }
    local.presentation?.let { FuelSecretDialog(it, controller::clearSensitive) }
}

private fun fuelAmount(value: String, currency: String): String = value.toBigDecimalOrNull()?.let { "${amountText(it)} $currency" } ?: "$value $currency"
private fun fuelSummary(coupon: FuelCoupon): String {
    val balance = coupon.balance
    val paidAmount = coupon.paidAmount
    return when {
        coupon.ambiguous -> "Requiere una nueva consulta"
        balance != null -> "Saldo: ${fuelAmount(balance, coupon.currency)}"
        paidAmount != null -> "Pagado: ${fuelAmount(paidAmount, coupon.currency)}"
        else -> coupon.currency
    }
}

@Composable
private fun FuelSecretDialog(presentation: FuelPresentation, close: () -> Unit) {
    // These presentation strings are ephemeral UI values; none are saved, logged, shared or put on the clipboard.
    val content = remember(presentation) { runCatching {
        Triple(presentation.qrPayload(), presentation.pinText(), presentation.tokenText())
    }.getOrNull() } ?: return
    val (payload, pin, token) = content
    var failed by remember(presentation) { mutableStateOf(false) }
    val bitmap by produceState<Bitmap?>(null, presentation) {
        value = withContext(Dispatchers.Default) { runCatching {
            val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 640, 640, mapOf(EncodeHintType.MARGIN to 4))
            val pixels = IntArray(matrix.width * matrix.height) { i ->
                if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            }
            Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        }.getOrNull() }
        failed = value == null
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text("Cupón de combustible", style = MaterialTheme.typography.titleLarge)
                Text(presentation.serial, color = MaterialTheme.colorScheme.onSurfaceVariant)
                bitmap?.let { Image(it.asImageBitmap(), "QR del cupón", Modifier.fillMaxWidth().aspectRatio(1f).background(Color.White)) }
                    ?: if (failed) Text("No se pudo generar el QR. Puedes utilizar el PIN y la clave.", color = MaterialTheme.colorScheme.error)
                    else CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                Detail("PIN del cupón", pin)
                Detail("Clave del cupón", token)
                Primary("Cerrar", onClick = close)
            }
        }
    }
}
