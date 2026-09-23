package dev.duardo.neotransfer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import dev.duardo.neotransfer.core.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class MovementItem(val entry: HistoryEntry, val movement: FinancialMovement) {
    val key: String get() = entry.record.id?.toString() ?: "${entry.record.receivedAt}:${movement.reference}:${movement.kind}"
}

internal fun financialHistory(entries: List<HistoryEntry>): List<MovementItem> {
    val seen = mutableMapOf<Pair<Int?, FinancialMovement>, Instant>()
    return entries.mapNotNull { entry -> FinancialMovement.from(entry.message)?.let { MovementItem(entry, it) } }
        .sortedByDescending { it.entry.record.receivedAt }.filter { item ->
            if (item.movement.reference == null) return@filter true
            val key = item.entry.record.subscriptionId to item.movement
            val previous = seen[key]
            val at = item.entry.record.receivedAt
            if (previous != null && java.time.Duration.between(at, previous).seconds in 0..300) false
            else { seen[key] = at; true }
        }
}

private val locale = Locale.forLanguageTag("es")
private val zone get() = ZoneId.systemDefault()
private fun clockText(instant: Instant) = DateTimeFormatter.ofPattern("HH:mm", locale).withZone(zone).format(instant)
private fun dayText(date: LocalDate): String = when (date) {
    LocalDate.now() -> "Hoy"
    LocalDate.now().minusDays(1) -> "Ayer"
    else -> date.format(DateTimeFormatter.ofPattern(if (date.year == LocalDate.now().year) "d 'de' MMMM" else "d 'de' MMMM 'de' uuuu", locale))
}
internal fun readableAccount(value: String) = if (value.matches(Regex("[0-9Xx*]{16}"))) value.chunked(4).joinToString(" ") else value
internal fun containsAccessSecret(value: String): Boolean = Regex(
    "(?i)\\b(pin|clave|contrase[ñn]a|otp)\\b|c[oó]digo\\s+(?:de\\s+)?(?:acceso|verificaci[oó]n|autenticaci[oó]n|seguridad)"
).containsMatchIn(value)
private fun MovementKind.label(): String = when (this) {
    MovementKind.SENT -> "Transferencia enviada"
    MovementKind.RECEIVED -> "Transferencia recibida"
    MovementKind.PAYMENT -> "Pago"
    MovementKind.RECHARGE -> "Recarga móvil"
}
private fun MovementKind.icon(): Int = when (this) {
    MovementKind.SENT -> R.drawable.ic_arrow_outward
    MovementKind.RECEIVED -> R.drawable.ic_south_west
    MovementKind.PAYMENT -> R.drawable.ic_shopping_bag
    MovementKind.RECHARGE -> R.drawable.ic_smartphone
}
private fun FinancialMovement.partyLabel() = when (kind) {
    MovementKind.SENT -> "Destino"
    MovementKind.RECEIVED -> "Móvil de origen"
    MovementKind.PAYMENT -> "Comercio"
    MovementKind.RECHARGE -> "Móvil recargado"
}
private fun FinancialMovement.displayAmount() = (if (amountIsNominal) "" else if (incoming) "+" else "−") + formatMoney(amount)
private fun FinancialMovement.contactName(recipients: List<Recipient>): String? = recipients.singleOrNull {
    when (kind) {
        MovementKind.SENT -> it.card == party // Never reconstruct a masked account from an agenda guess.
        MovementKind.RECEIVED, MovementKind.RECHARGE -> it.phone != null && it.phone == party.removePrefix("+53").let { p -> if (p.length == 10 && p.startsWith("53")) p.drop(2) else p }
        else -> false
    }
}?.name

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MovementsScreen(state: AppUiState, actions: UiActions, openMessages: () -> Unit, resolve: (UncertainTransfer) -> Unit, resolvePending: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var bankFilter by rememberSaveable { mutableStateOf("Todos") }
    var kindFilter by rememberSaveable { mutableStateOf("Todos") }
    var from by rememberSaveable { mutableStateOf<String?>(null) }
    var through by rememberSaveable { mutableStateOf<String?>(null) }
    var datePicker by remember { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val all = remember(state.history) { financialHistory(state.history) }
    val filtered = remember(all, query, bankFilter, kindFilter, from, through, state.recipients) {
        all.filter { item ->
            val m = item.movement
            val date = item.entry.record.receivedAt.atZone(zone).toLocalDate()
            (bankFilter == "Todos" || (m.bank?.name ?: "Sin identificar") == bankFilter) &&
                (kindFilter == "Todos" || m.kind.name == kindFilter) &&
                (from == null || date >= LocalDate.parse(from)) && (through == null || date <= LocalDate.parse(through)) &&
                (m.matches(query) || m.contactName(state.recipients)?.contains(query.trim(), true) == true)
        }
    }
    val groups = remember(filtered) { filtered.groupBy { it.entry.record.receivedAt.atZone(zone).toLocalDate() } }
    val filteredActive = query.isNotBlank() || bankFilter != "Todos" || kindFilter != "Todos" || from != null
    fun clearFilters() { query = ""; bankFilter = "Todos"; kindFilter = "Todos"; from = null; through = null }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Movimientos", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = actions.refresh) { Icon(painterResource(R.drawable.ic_refresh), "Actualizar movimientos") }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(painterResource(R.drawable.ic_more_vert), "Más opciones") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Mensajes del banco") }, onClick = { menu = false; openMessages() })
                }
            }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            placeholder = { Text("Buscar movimientos") }, singleLine = true, shape = RoundedCornerShape(14.dp),
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.ic_close), "Borrar búsqueda") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.onSecondaryContainer))
        FlowRow(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterMenu(if (bankFilter == "Todos") "Banco" else bankFilter, bankFilter != "Todos",
                listOf("Todos" to "Todos los bancos", "BPA" to "BPA", "BANDEC" to "BANDEC", "Sin identificar" to "Sin identificar")) { bankFilter = it }
            FilterMenu(if (kindFilter == "Todos") "Tipo" else MovementKind.valueOf(kindFilter).label(), kindFilter != "Todos",
                listOf("Todos" to "Todos los tipos") + MovementKind.entries.map { it.name to it.label() }) { kindFilter = it }
            FilterChip(selected = from != null, onClick = { datePicker = true }, label = { Text(if (from == null) "Fecha" else "Fechas elegidas") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_calendar_month), null, Modifier.size(18.dp)) })
            if (filteredActive) TextButton(onClick = ::clearFilters, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text("Limpiar") }
        }
        if (from != null) Text("${LocalDate.parse(from).format(DateTimeFormatter.ofPattern("d MMM uuuu", locale))} — ${LocalDate.parse(through ?: from).format(DateTimeFormatter.ofPattern("d MMM uuuu", locale))}",
            Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            if (state.pending != null || state.uncertain.isNotEmpty()) item(key = "review") {
                Text("Por revisar", Modifier.padding(top = 16.dp, bottom = 8.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall)
                state.pending?.let { p ->
                    ReviewRow("${p.action.kind.label} pendiente", "${formatMoney(p.action.amount)} · ${readableAccount(p.action.destination)}", "Revisar resultado", resolvePending)
                }
                state.uncertain.forEach { p ->
                    ReviewRow("Transferencia sin comprobante", "${formatMoney(p.request.amount)} · ${readableAccount(p.request.destination)}", "Vincular comprobante") { resolve(p) }
                }
            }
            if (filtered.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(if (filteredActive) "No hay movimientos que coincidan" else "Todavía no hay movimientos", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = if (filteredActive) ::clearFilters else openMessages,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                        Text(if (filteredActive) "Limpiar filtros" else "Ver mensajes del banco")
                    }
                }
            }
            groups.forEach { (date, rows) ->
                stickyHeader(key = "day:$date") {
                    Surface(color = MaterialTheme.colorScheme.surface) {
                        Text(dayText(date), Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp).semantics { heading() },
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    }
                }
                items(rows, key = { it.key }) { item -> MovementRow(item, state.recipients) { selectedId = item.key } }
            }
        }
    }
    val selected = all.firstOrNull { it.key == selectedId }
    if (selected != null) MovementDetail(selected, state.recipients, actions.share) { selectedId = null }
    if (datePicker) {
        val picker = rememberDateRangePickerState(
            initialSelectedStartDateMillis = from?.let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() },
            initialSelectedEndDateMillis = through?.let { LocalDate.parse(it).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() },
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate() <= LocalDate.now()
                override fun isSelectableYear(year: Int) = year <= LocalDate.now().year
            })
        val pickerColors = DatePickerDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        DatePickerDialog(onDismissRequest = { datePicker = false }, colors = pickerColors, confirmButton = {
            TextButton(onClick = {
                from = picker.selectedStartDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                through = picker.selectedEndDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                datePicker = false
            }, enabled = picker.selectedStartDateMillis != null && picker.selectedEndDateMillis != null) { Text("Aplicar") }
        }, dismissButton = { TextButton(onClick = { datePicker = false }) { Text("Cancelar") } }) {
            DateRangePicker(picker, modifier = Modifier.heightIn(max = 540.dp), colors = pickerColors,
                title = { Text("Fechas", Modifier.padding(start = 24.dp, top = 20.dp), style = MaterialTheme.typography.titleSmall) },
                headline = {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 64.dp, top = 16.dp, bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        fun selectedDate(value: Long?, empty: String) = value?.let {
                            Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
                        } ?: empty
                        Text(selectedDate(picker.selectedStartDateMillis, "Desde"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text("–", style = MaterialTheme.typography.titleMedium)
                        Text(selectedDate(picker.selectedEndDateMillis, "Hasta"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    }
                })
        }
    }
}

@Composable
private fun FilterMenu(label: String, selected: Boolean, options: List<Pair<String, String>>, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(selected, { expanded = true }, label = { Text(label) })
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (value, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { choose(value); expanded = false }) }
        }
    }
}

@Composable
private fun ReviewRow(title: String, detail: String, action: String, click: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(painterResource(R.drawable.ic_schedule), null, Modifier.size(20.dp))
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
        Text(detail, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = click, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text(action) }
    }
}

@Composable
internal fun MovementRow(item: MovementItem, recipients: List<Recipient>, showDate: Boolean = false, click: () -> Unit) {
    val m = item.movement
    val name = m.contactName(recipients)
    val amount = m.displayAmount()
    val amountStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val amountWidth = with(density) { textMeasurer.measure(amount, amountStyle, softWrap = false).size.width.toDp() }
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = 15.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(painterResource(m.kind.icon()), null, Modifier.size(22.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val amountColor = if (m.incoming) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface
                if (density.fontScale > 1.2f || amountWidth > maxWidth / 2) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(name ?: m.kind.label(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(amount, style = amountStyle, color = amountColor)
                    }
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(name ?: m.kind.label(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(amount, style = amountStyle, color = amountColor, textAlign = TextAlign.End)
                }
            }
            if (m.amountIsNominal) Text("Importe del servicio", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (m.party.isNotBlank()) Text(readableAccount(m.party), style = MaterialTheme.typography.bodySmall)
            Text(listOfNotNull(m.bank?.name, if (showDate) dateText(item.entry.record.receivedAt) else clockText(item.entry.record.receivedAt)).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MovementDetail(item: MovementItem, recipients: List<Recipient>, share: (String) -> Unit, dismiss: () -> Unit) {
    val m = item.movement
    val clipboard = LocalClipboardManager.current
    var showOriginal by rememberSaveable(item.key) { mutableStateOf(false) }
    val details = buildList {
        m.contactName(recipients)?.let { add("Contacto guardado" to it) }
        if (m.party.isNotBlank()) add(m.partyLabel() to readableAccount(m.party))
        m.account?.let { add("Cuenta de destino" to readableAccount(it)) }
        add("Banco" to (m.bank?.name ?: "No indicado en el mensaje"))
        m.reference?.let { add("Referencia" to it) }
        m.purchaseId?.let { add("Id de compra" to it) }
        m.bankDate?.let { add("Fecha del banco" to it) }
        if (!m.amountIsNominal) m.nominalAmount?.let { add("Importe del servicio" to formatMoney(it)) }
        if (m.amountIsNominal) add("Importe pagado" to "No indicado en el mensaje")
        add("SMS recibido" to DateTimeFormatter.ofPattern("d MMM uuuu · HH:mm", locale).withZone(zone).format(item.entry.record.receivedAt))
    }
    val receipt = buildString {
        appendLine("NeoTransfer"); appendLine(m.kind.label()); appendLine("${if (m.amountIsNominal) "Importe del servicio" else "Importe"}: ${formatMoney(m.amount)}")
        appendLine("Estado: Completada")
        details.forEach { (label, value) -> appendLine("$label: $value") }
        append("Fuente: comprobante recibido de PAGOxMOVIL")
    }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(m.kind.label(), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = dismiss) { Icon(painterResource(R.drawable.ic_close), "Cerrar comprobante") }
                }
                Text(m.displayAmount(), Modifier.padding(vertical = 20.dp),
                    style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                if (m.amountIsNominal) Text("Importe del servicio", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(R.drawable.ic_check_circle), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text("Completada", color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelLarge)
                }
                val largeText = LocalDensity.current.fontScale > 1.2f
                FlowRow(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { share(receipt) }, modifier = (if (largeText) Modifier.fillMaxWidth() else Modifier.weight(1f)).heightIn(min = 48.dp)) {
                        Icon(painterResource(R.drawable.ic_share), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Compartir", color = MaterialTheme.colorScheme.onSurface)
                    }
                    if (m.reference != null) OutlinedButton(onClick = { clipboard.setText(AnnotatedString(requireNotNull(m.reference))) },
                        modifier = (if (largeText) Modifier.fillMaxWidth() else Modifier.weight(1f)).heightIn(min = 48.dp)) { Text("Copiar referencia", color = MaterialTheme.colorScheme.onSurface) }
                }
            }
            items(details) { (label, value) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SelectionContainer { Text(value, style = MaterialTheme.typography.bodyLarge) }
                }
            }
            item {
                TextButton(onClick = { showOriginal = !showOriginal }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                    Text(if (showOriginal) "Ocultar mensaje original" else "Ver mensaje original")
                }
                if (showOriginal) SelectionContainer { Text(item.entry.record.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp)) }
            }
        }
    }
}

@Composable
internal fun BankMessagesScreen(entries: List<HistoryEntry>, refresh: () -> Unit, back: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf<HistoryEntry?>(null) }
    val rows = remember(entries, query) { entries.filter { query.isBlank() || it.record.body.contains(query, true) } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(painterResource(R.drawable.ic_arrow_back), "Volver") }
            Text("Mensajes del banco", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = refresh) { Icon(painterResource(R.drawable.ic_refresh), "Actualizar mensajes") }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            singleLine = true, placeholder = { Text("Buscar mensajes") }, leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) }, shape = RoundedCornerShape(14.dp))
        LazyColumn(contentPadding = PaddingValues(24.dp), modifier = Modifier.weight(1f)) {
            if (rows.isEmpty()) item { Text("No hay mensajes que coincidan") }
            items(rows) { row ->
                Column(Modifier.fillMaxWidth().clickable { selected = row }.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(messageTitle(row.message), style = MaterialTheme.typography.titleSmall)
                    Text(if (containsAccessSecret(row.record.body)) "Mensaje con datos de acceso" else row.record.body.lineSequence().filter { it.isNotBlank() }.joinToString(" "), maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Text(dateText(row.record.receivedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
            }
        }
    }
    selected?.let { row -> AlertDialog(onDismissRequest = { selected = null }, title = { Text(messageTitle(row.message)) },
        properties = DialogProperties(securePolicy = if (containsAccessSecret(row.record.body)) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit),
        text = { LazyColumn { item { SelectionContainer { Text(row.record.body) } } } },
        confirmButton = { TextButton(onClick = { selected = null }) { Text("Cerrar") } }) }
}

private fun messageTitle(message: BankMessage): String = when (message) {
    is BankMessage.Authenticated -> "Acceso a ${message.bank.name}"
    is BankMessage.Balance -> "Consulta de saldo · ${message.bank.name}"
    is BankMessage.RechargeRejected -> "Recarga rechazada"
    else -> FinancialMovement.from(message)?.kind?.label() ?: "Aviso del banco"
}
