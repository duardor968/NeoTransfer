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
import dev.duardo.neotransfer.core.*
import dev.duardo.neotransfer.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal data class MovementItem(val entry: HistoryEntry?, val movement: FinancialMovement,
                                 val storedId: String? = null,
                                 val occurredAt: Instant? = entry?.record?.receivedAt,
                                 val subscriptionId: Int? = entry?.record?.subscriptionId,
                                 val referenceConflict: Boolean = false,
                                 val sourceAccount: String? = null,
                                 val postedOn: LocalDate? = null,
                                 val registrationId: String? = null,
                                 val cardId: String? = null,
                                 val accountId: String? = null) {
    val key: String get() = storedId ?: entry?.record?.id?.toString() ?: "$occurredAt:${movement.reference}:${movement.kind}"
    val date: LocalDate get() = postedOn ?: requireNotNull(occurredAt).atZone(zone).toLocalDate()
    val stateLabel: String get() = if (referenceConflict) "Comprobante por revisar" else if (postedOn != null) "Registrado por el banco" else "Completada"
}

internal fun financialHistory(entries: List<HistoryEntry>): List<MovementItem> {
    val seen = mutableMapOf<Pair<Int?, FinancialMovement>, Instant>()
    return entries.mapNotNull { entry -> FinancialMovement.from(entry.message)?.let { MovementItem(entry, it) } }
        .sortedByDescending { it.occurredAt }.filter { item ->
            if (item.movement.reference == null) return@filter true
            val key = item.subscriptionId to item.movement
            val previous = seen[key]
            val at = requireNotNull(item.occurredAt)
            if (previous != null && java.time.Duration.between(at, previous).seconds in 0..300) false
            else { seen[key] = at; true }
        }
}

internal fun appFinancialHistory(state: AppUiState): List<MovementItem> {
    fun knownAccount(number: String?, bankCode: String?, subscriptionId: Int?): String? {
        if (number == null || number.all(Char::isDigit) || subscriptionId == null) return number
        val products = state.wallet.cards.map { it.registrationId to it.number } +
            state.wallet.accounts.map { it.registrationId to it.number }
        return state.wallet.registrations.filter { it.subscriptionId == subscriptionId &&
            (bankCode == null || it.bankCode == bankCode) }.flatMap { registration ->
            products.filter { it.first == registration.id && matchesAccount(number, it.second) }
                .map { registration.bankCode to it.second }
        }.distinct().singleOrNull()?.second ?: number
    }
    val receipts = state.wallet.receipts.associateBy { it.id }
    val persisted = state.wallet.movements.mapNotNull { row ->
        val receipt = receipts[row.receiptId] ?: return@mapNotNull null
        val currency = Currency.entries.firstOrNull { it.name == receipt.currency } ?: return@mapNotNull null
        val kind = MovementKind.entries.firstOrNull { it.name == receipt.kind } ?: return@mapNotNull null
        val operation = receipt.operationId?.let { id -> state.wallet.operations.singleOrNull { it.id == id } }
        val party = operation?.destination?.takeIf { kind == MovementKind.SENT && matchesAccount(receipt.party, it) } ?: receipt.party
        MovementItem(null, FinancialMovement(kind, Money(receipt.amount.toBigDecimal(), currency),
            Bank.entries.firstOrNull { it.code == receipt.bankCode }, party, knownAccount(receipt.account, receipt.bankCode, receipt.subscriptionId), receipt.reference,
            receipt.purchaseId, receipt.bankDate, receipt.amountIsNominal, receipt.nominalAmount?.let { Money(it.toBigDecimal(), currency) }),
            storedId = row.id, occurredAt = Instant.ofEpochMilli(row.occurredAt), subscriptionId = receipt.subscriptionId,
            referenceConflict = receipt.referenceConflict,
            sourceAccount = operation?.source, registrationId = operation?.registrationId)
    }
    val legacy = financialHistory(state.history).filter { item ->
        val inboxId = item.entry?.record?.id?.toString()
        val exactEvents = if (inboxId == null) emptySet() else state.wallet.events.filter {
            it.source == dev.duardo.neotransfer.data.EventSource.INBOX && it.sourceId == inboxId && it.subscriptionId == item.subscriptionId
        }.map { it.canonicalEventId }.toSet()
        state.wallet.receipts.none { it.eventId in exactEvents && state.wallet.movements.any { row -> row.receiptId == it.id } }
    }.map { item -> item.copy(movement = item.movement.copy(account =
        knownAccount(item.movement.account, item.movement.bank?.code, item.subscriptionId))) }
    val recovered = state.wallet.histories.filter { it.receiptId == null }.mapNotNull { row ->
        val currency = Currency.entries.firstOrNull { it.name == row.currency } ?: return@mapNotNull null
        val amount = row.amount.toBigDecimalOrNull() ?: return@mapNotNull null
        val date = runCatching { LocalDate.parse(row.postedOn) }.getOrNull() ?: return@mapNotNull null
        MovementItem(null, FinancialMovement(if (row.incoming) MovementKind.LEDGER_CREDIT else MovementKind.LEDGER_DEBIT,
            Money(amount, currency), Bank.entries.firstOrNull { it.code == row.bankCode }, row.service,
            account = knownAccount(row.account, row.bankCode, row.subscriptionId), reference = row.reference ?: row.transactionNumber), storedId = "ledger:${row.id}",
            subscriptionId = row.subscriptionId, referenceConflict = row.referenceConflict || row.ambiguous,
            postedOn = date, registrationId = row.registrationId, cardId = row.cardId, accountId = row.accountId)
    }
    return (persisted + legacy + recovered).sortedWith(compareByDescending<MovementItem> { it.date }.thenByDescending { it.occurredAt })
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
private fun MovementKind.label(): String = when (this) {
    MovementKind.SENT -> "Transferencia enviada"
    MovementKind.RECEIVED -> "Transferencia recibida"
    MovementKind.PAYMENT -> "Pago"
    MovementKind.RECHARGE -> "Recarga móvil"
    MovementKind.LEDGER_CREDIT -> "Abono"
    MovementKind.LEDGER_DEBIT -> "Cargo"
    MovementKind.FUEL_REFUND -> "Abono al cupón"
}
private fun MovementKind.icon(): Int = when (this) {
    MovementKind.SENT -> R.drawable.ic_arrow_outward
    MovementKind.RECEIVED -> R.drawable.ic_south_west
    MovementKind.PAYMENT -> R.drawable.ic_shopping_bag
    MovementKind.RECHARGE -> R.drawable.ic_smartphone
    MovementKind.LEDGER_CREDIT -> R.drawable.ic_south_west
    MovementKind.LEDGER_DEBIT -> R.drawable.ic_arrow_outward
    MovementKind.FUEL_REFUND -> R.drawable.ic_refresh
}
private fun FinancialMovement.partyLabel() = when (kind) {
    MovementKind.SENT -> "Destino"
    MovementKind.RECEIVED -> "Móvil de origen"
    MovementKind.PAYMENT -> "Comercio"
    MovementKind.RECHARGE -> "Móvil recargado"
    MovementKind.LEDGER_CREDIT, MovementKind.LEDGER_DEBIT -> "Concepto"
    MovementKind.FUEL_REFUND -> "Cupón"
}
private fun FinancialMovement.displayAmount() = (if (amountIsNominal) "" else if (incoming) "+" else "−") + formatMoney(amount)
private fun FinancialMovement.contactName(recipients: List<Recipient>): String? = recipients.filter {
    when (kind) {
        MovementKind.SENT -> it.card == party // Never reconstruct a masked account from an agenda guess.
        MovementKind.RECEIVED, MovementKind.RECHARGE -> it.phone != null && it.phone == party.removePrefix("+53").let { p -> if (p.length == 10 && p.startsWith("53")) p.drop(2) else p }
        else -> false
    }
}.map { it.name }.distinct().singleOrNull()

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MovementsScreen(state: AppUiState, actions: UiActions, resolve: (UncertainTransfer) -> Unit, resolvePending: () -> Unit, queryBank: (() -> Unit)?, statistics: () -> Unit = {}) {
    var query by rememberSaveable { mutableStateOf("") }
    var bankFilter by rememberSaveable { mutableStateOf("Todos") }
    var kindFilter by rememberSaveable { mutableStateOf("Todos") }
    var from by rememberSaveable { mutableStateOf<String?>(null) }
    var through by rememberSaveable { mutableStateOf<String?>(null) }
    var datePicker by remember { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedOperationId by rememberSaveable { mutableStateOf<String?>(null) }
    var showRequests by rememberSaveable { mutableStateOf(false) }
    var productFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val products = walletProducts(state)
    val product = products.firstOrNull { it.id == productFilter }
    val listState = rememberLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val all = remember(state.history, state.wallet) { appFinancialHistory(state) }
    val filtered = remember(all, query, bankFilter, kindFilter, from, through, displayRecipients(state), product) {
        all.filter { item ->
            val m = item.movement
            val date = item.date
            (bankFilter == "Todos" || (m.bank?.name ?: "Sin identificar") == bankFilter) &&
                (kindFilter == "Todos" || m.kind.name == kindFilter) &&
                (product == null || item.cardId == product.id || item.accountId == product.id ||
                    (product.number != null && product.number.isNotEmpty() && (item.sourceAccount == product.number ||
                        m.account?.let { matchesAccount(it, product.number) } == true) &&
                        (item.registrationId == null || item.registrationId == product.registrationId))) &&
                (from == null || date >= LocalDate.parse(from)) && (through == null || date <= LocalDate.parse(through)) &&
                (m.matches(query) || m.contactName(displayRecipients(state))?.contains(query.trim(), true) == true)
        }
    }
    val groups = remember(filtered) { filtered.groupBy { it.date } }
    val operations = state.wallet.operations.filterNot { it.parameters["internalStep"] == "true" }.sortedByDescending { it.startedAt }.filter { operation ->
        val date = Instant.ofEpochMilli(operation.startedAt).atZone(zone).toLocalDate()
        (bankFilter == "Todos" || operation.providerId == bankFilter) &&
            (product == null || operationBelongsToProduct(operation, product)) &&
            (from == null || date >= LocalDate.parse(from)) && (through == null || date <= LocalDate.parse(through)) &&
            (query.isBlank() || listOf(operation.title(state), operation.destination, operation.description, operation.source).any { it.contains(query.trim(), true) })
    }
    val filteredActive = query.isNotBlank() || bankFilter != "Todos" || kindFilter != "Todos" || from != null || productFilter != null
    fun clearFilters() { query = ""; bankFilter = "Todos"; kindFilter = "Todos"; from = null; through = null; productFilter = null }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Actividad", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = statistics) { Text("Estadísticas") }
            IconButton(onClick = actions.refresh) { Icon(painterResource(R.drawable.ic_refresh), "Actualizar movimientos") }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
            placeholder = { Text("Buscar movimientos") }, singleLine = true, shape = RoundedCornerShape(14.dp),
            leadingIcon = { Icon(painterResource(R.drawable.ic_search), null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(painterResource(R.drawable.ic_close), "Borrar búsqueda") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.onSecondaryContainer))
        FlowRow(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterMenu(product?.name ?: "Tarjeta o cuenta", product != null,
                listOf("" to "Toda la cartera") + products.map { it.id to "${it.identity.title()} · ${it.name}" }) { productFilter = it.ifBlank { null } }
            FilterMenu(if (bankFilter == "Todos") "Banco" else bankFilter, bankFilter != "Todos",
                listOf("Todos" to "Todos los bancos") + Bank.entries.map { it.name to it.name }) { bankFilter = it }
            if (!showRequests) FilterMenu(if (kindFilter == "Todos") "Tipo" else MovementKind.valueOf(kindFilter).label(), kindFilter != "Todos",
                listOf("Todos" to "Todos los tipos") + MovementKind.entries.map { it.name to it.label() }) { kindFilter = it }
            FilterChip(selected = from != null, onClick = { datePicker = true }, label = { Text(if (from == null) "Fecha" else "Fechas elegidas") },
                leadingIcon = { Icon(painterResource(R.drawable.ic_calendar_month), null, Modifier.size(18.dp)) })
            if (filteredActive) TextButton(onClick = ::clearFilters, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text("Limpiar") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!showRequests, { showRequests = false }, label = { Text("Movimientos") })
            FilterChip(showRequests, { showRequests = true }, label = { Text("Solicitudes") })
        }
        if (queryBank != null) TextButton(onClick = queryBank, modifier = Modifier.padding(horizontal = 16.dp), enabled = !state.busy) { Text("Consultar actividad al banco") }
        if (from != null) Text("${LocalDate.parse(from).format(DateTimeFormatter.ofPattern("d MMM uuuu", locale))} — ${LocalDate.parse(through ?: from).format(DateTimeFormatter.ofPattern("d MMM uuuu", locale))}",
            Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            if (showRequests) {
                if (operations.isEmpty()) item { Text("No hay solicitudes que coincidan", Modifier.padding(vertical = 24.dp)) }
                items(operations, key = { "operation:${it.id}" }) { operation ->
                    ActionRow(operation.title(state), R.drawable.ic_receipt_long,
                        "${operation.statusTitle()} · ${dateText(Instant.ofEpochMilli(operation.startedAt))}") { selectedOperationId = operation.id }
                }
                return@LazyColumn
            }
            val pendingServices = state.wallet.operations.filter { it.reviewRequired && it.timeoutAt == null && it.status in setOf(OperationStatus.UNCERTAIN, OperationStatus.AWAITING_CONFIRMATION) &&
                it.id != state.pending?.id && state.uncertain.none { uncertain -> uncertain.id == it.id } }
            if (pendingServices.isNotEmpty()) item(key = "services-review") {
                Text("Solicitudes por revisar", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleSmall)
                pendingServices.forEach { operation -> ReviewRow(operation.title(state), operation.statusTitle(), "Revisar resultado") { selectedOperationId = operation.id } }
            }
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
                    TextButton(onClick = if (filteredActive) ::clearFilters else actions.refresh,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
                        Text(if (filteredActive) "Limpiar filtros" else "Actualizar actividad")
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
                items(rows, key = { it.key }) { item -> MovementRow(item, displayRecipients(state)) { selectedId = item.key } }
            }
        }
    }
    val selected = all.firstOrNull { it.key == selectedId }
    if (selected != null) MovementDetail(selected, displayRecipients(state), actions.share) { selectedId = null }
    state.wallet.operations.firstOrNull { it.id == selectedOperationId }?.let { OperationHistoryDetail(it, state, actions) { selectedOperationId = null } }
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
            if (item.referenceConflict) Text("Comprobante por revisar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            Text(listOfNotNull(m.bank?.name, item.postedOn?.let(::dayText) ?: item.occurredAt?.let { if (showDate) dateText(it) else clockText(it) }).joinToString(" · "),
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
    val details = buildList {
        m.contactName(recipients)?.let { add("Contacto guardado" to it) }
        if (m.party.isNotBlank()) add(m.partyLabel() to readableAccount(m.party))
        m.account?.let { add((if (item.postedOn != null) "Cuenta" else if (m.incoming) "Cuenta de destino" else "Referencia del servicio") to readableAccount(it)) }
        item.sourceAccount?.let { add("Cuenta de origen" to if (it == "0000") "Cuenta predeterminada" else readableAccount(it)) }
        m.bank?.let { add("Banco" to it.name) }
        m.reference?.let { add("Referencia" to it) }
        m.purchaseId?.let { add("Id de compra" to it) }
        m.bankDate?.let { add("Fecha del banco" to it) }
        if (!m.amountIsNominal) m.nominalAmount?.let { add("Importe del servicio" to formatMoney(it)) }
        add("Fecha" to (item.postedOn?.format(DateTimeFormatter.ofPattern("d MMM uuuu", locale))
            ?: DateTimeFormatter.ofPattern("d MMM uuuu · HH:mm", locale).withZone(zone).format(requireNotNull(item.occurredAt))))
    }
    val receipt = buildString {
        appendLine("NeoTransfer"); appendLine(m.kind.label()); appendLine("${if (m.amountIsNominal) "Importe del servicio" else "Importe"}: ${formatMoney(m.amount)}")
        appendLine("Estado: ${item.stateLabel}")
        details.forEach { (label, value) -> appendLine("$label: $value") }
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
                    Icon(painterResource(if (item.referenceConflict) R.drawable.ic_schedule else R.drawable.ic_check_circle), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(item.stateLabel, color = MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.labelLarge)
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
        }
    }
}
