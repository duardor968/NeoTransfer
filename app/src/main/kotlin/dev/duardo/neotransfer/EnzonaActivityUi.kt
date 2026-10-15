package dev.duardo.neotransfer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.enzona.EnzonaTransaction
import dev.duardo.neotransfer.enzona.data.EnzonaIntentState
import dev.duardo.neotransfer.enzona.data.EnzonaJournalEntry
import java.math.BigDecimal

internal fun enzonaAmount(amount: BigDecimal?, currency: String?): String =
    listOfNotNull(amount?.toPlainString(), currency).joinToString(" ")

private fun enzonaDate(value: String): String = runCatching { dateText(java.time.Instant.parse(value)) }
    .getOrElse { runCatching { java.time.LocalDate.parse(value).format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.forLanguageTag("es"))) }.getOrDefault(value) }

/** Sort only explicit ISO dates; unfamiliar provider formats remain unchanged and in stable order. */
internal fun orderedEnzonaTransactions(entries: List<EnzonaTransaction>): List<EnzonaTransaction> =
    entries.sortedByDescending { entry ->
        entry.createdAt?.value?.let { value ->
            runCatching { java.time.Instant.parse(value) }.getOrNull()
                ?: runCatching { java.time.LocalDate.parse(value).atStartOfDay(java.time.ZoneOffset.UTC).toInstant() }.getOrNull()
        }
    }

internal fun EnzonaJournalEntry.matchesActivityQuery(query: String): Boolean = query.isBlank() ||
    listOfNotNull(intent.description, intent.destination.username, intent.destination.value,
        intent.amount, intent.currency, state.label()).any { it.contains(query, ignoreCase = true) }

@Composable
internal fun EnzonaRecentActivity(state: AppUiState, openActivity: () -> Unit) {
    Column {
        val balance = state.enzonaWallet.selectedCardId?.let { state.enzonaWallet.balances[it] }
        val entries = balance?.value?.operations.orEmpty().take(4)
        if (entries.isEmpty()) EmptyAction("Todavía no hay movimientos", "Ver actividad", openActivity)
        entries.forEach { entry ->
            ActionRow(entry.description?.takeIf(String::isNotBlank) ?: entry.action ?: "Movimiento",
                R.drawable.ic_history,
                listOfNotNull(enzonaAmount(entry.amount, balance?.value?.currency).takeIf(String::isNotBlank),
                    entry.operationDate?.value?.let(::enzonaDate)).joinToString(" · "), click = openActivity)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EnzonaActivityScreen(state: AppUiState, actions: UiActions) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf("") }
    var receipt by remember { mutableStateOf<EnzonaTransaction?>(null) }
    var localReceipt by remember { mutableStateOf<EnzonaJournalEntry?>(null) }
    val selected = state.enzonaWallet.selectedCardId
    val balance = selected?.let { state.enzonaWallet.balances[it] }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp)) {
            Text("Actividad", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            IconButton(enabled = !state.busy, onClick = {
                if (section == 0 && selected != null) actions.balanceProduct("enzona:$selected")
                else actions.refreshEnzonaActivity()
            }) { Icon(painterResource(R.drawable.ic_refresh), "Consultar actividad") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(section == 0, { section = 0 }, label = { Text("Esta tarjeta") })
            FilterChip(section == 1, { section = 1 }, label = { Text("Mi cuenta ENZONA") })
        }
        SearchField(query, { query = it }, "Buscar movimiento")
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 16.dp)) {
            if (section == 0) {
                val entries = balance?.value?.operations.orEmpty().filter {
                    query.isBlank() || listOfNotNull(it.description, it.action, it.alias, it.amount?.toPlainString())
                        .any { value -> value.contains(query, true) }
                }
                if (entries.isEmpty()) item { Text(if (balance == null) "Consulta la actividad de esta tarjeta" else "No hay movimientos que mostrar", Modifier.padding(vertical = 24.dp)) }
                items(entries) { entry ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(entry.description?.takeIf(String::isNotBlank) ?: entry.action ?: "Movimiento", style = MaterialTheme.typography.titleMedium)
                        Text(enzonaAmount(entry.amount, balance?.value?.currency))
                        entry.operationDate?.value?.let { Text(enzonaDate(it), style = MaterialTheme.typography.bodySmall) }
                        entry.alias?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                    HorizontalDivider()
                }
            } else {
                val entries = orderedEnzonaTransactions(state.enzonaWallet.transactions).filter {
                    query.isBlank() || listOfNotNull(it.description, it.transactionDenomination, it.merchantName,
                        it.invoiceNumber, it.amount?.toPlainString()).any { value -> value.contains(query, true) }
                }
                if (entries.isEmpty()) item { Text(if (state.enzonaWallet.activityAt == null) "Consulta la actividad de tu cuenta" else "No hay movimientos que mostrar", Modifier.padding(vertical = 24.dp)) }
                items(entries, key = { it.transactionUuid }) { entry ->
                    ActionRow(entry.description?.takeIf(String::isNotBlank) ?: entry.transactionDenomination ?: "Movimiento",
                        R.drawable.ic_receipt_long,
                        listOfNotNull(enzonaAmount(entry.totalAmount ?: entry.amount, entry.currency),
                            entry.statusDenomination, entry.createdAt?.value?.let(::enzonaDate)).filter(String::isNotBlank).joinToString(" · ")) { receipt = entry }
                    HorizontalDivider()
                }
            }
            val attempts = state.enzonaWallet.journal.filter { row ->
                row.state != EnzonaIntentState.PREPARED &&
                    (section == 1 || row.intent.cardUuid == selected && row.state != EnzonaIntentState.CONFIRMED) &&
                    row.matchesActivityQuery(query) &&
                    (row.remoteId == null || state.enzonaWallet.transactions.none { it.transactionUuid == row.remoteId })
            }
            items(attempts, key = { "local:${it.intent.id}" }) { row ->
                ActionRow(row.state.label(), R.drawable.ic_history,
                    "${row.intent.amount} ${row.intent.currency} · ${row.intent.destination.value}") { localReceipt = row }
            }
        }
    }
    receipt?.let { entry ->
        ModalBottomSheet(onDismissRequest = { receipt = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(entry.transactionDenomination ?: "Comprobante", style = MaterialTheme.typography.headlineSmall)
                Text(enzonaAmount(entry.totalAmount ?: entry.amount, entry.currency), style = MaterialTheme.typography.headlineMedium)
                listOfNotNull(entry.description, entry.statusDenomination, entry.createdAt?.value?.let(::enzonaDate),
                    entry.recipient?.name, entry.recipient?.lastName, entry.recipient?.username,
                    entry.invoiceNumber).filter(String::isNotBlank).forEach { Text(it) }
                TextButton(onClick = { actions.share(listOfNotNull(entry.transactionDenomination,
                    enzonaAmount(entry.totalAmount ?: entry.amount, entry.currency), entry.description,
                    entry.statusDenomination, entry.createdAt?.value, entry.invoiceNumber).joinToString("\n")) }) { Text("Compartir") }
            }
        }
    }
    localReceipt?.let { entry ->
        ModalBottomSheet(onDismissRequest = { localReceipt = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(entry.state.label(), style = MaterialTheme.typography.headlineSmall)
                Text("${entry.intent.amount} ${entry.intent.currency}", style = MaterialTheme.typography.headlineMedium)
                Text(entry.intent.destination.username ?: entry.intent.destination.value)
                entry.intent.description?.takeIf(String::isNotBlank)?.let { Text(it) }
                Text(dateText(java.time.Instant.ofEpochMilli(entry.intent.preparedAt)))
                TextButton(onClick = { actions.share(listOfNotNull(entry.state.label(), "${entry.intent.amount} ${entry.intent.currency}",
                    entry.intent.destination.username ?: entry.intent.destination.value, entry.intent.description,
                    dateText(java.time.Instant.ofEpochMilli(entry.intent.preparedAt))).joinToString("\n")) }) { Text("Compartir") }
            }
        }
    }
}

private fun EnzonaIntentState.label(): String = when (this) {
    EnzonaIntentState.PREPARED -> "Sin enviar"
    EnzonaIntentState.SUBMITTING -> "Solicitud enviada"
    EnzonaIntentState.UNKNOWN -> "Tiempo de espera agotado"
    EnzonaIntentState.CONFIRMED -> "Pago realizado"
    EnzonaIntentState.REJECTED -> "Pago rechazado"
}
