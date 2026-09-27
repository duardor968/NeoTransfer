package dev.duardo.neotransfer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.core.Money
import dev.duardo.neotransfer.core.MovementKind
import java.math.BigDecimal
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun StatisticsScreen(state: AppUiState, back: () -> Unit) {
    val all = remember(state.history, state.wallet) { appFinancialHistory(state) }
    val months = (all.map { YearMonth.from(it.date) } + YearMonth.now()).distinct().sortedDescending()
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val records = all.filter { YearMonth.from(it.date).toString() == month && !it.movement.amountIsNominal && !it.referenceConflict }
    val omitted = all.count { YearMonth.from(it.date).toString() == month && (it.movement.amountIsNominal || it.referenceConflict) }
    val formatter = DateTimeFormatter.ofPattern("MMMM uuuu", Locale.forLanguageTag("es"))
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item { PageHeader("Estadísticas", back) }
        item { ChoiceField(YearMonth.parse(month).format(formatter), months.map { it.toString() to it.format(formatter) }) { month = it } }
        if (records.isEmpty()) item { Text("No hay movimientos con importe confirmado en este mes.") }
        records.groupBy { it.movement.amount.currency }.forEach { (currency, rows) ->
            item(currency.name) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(currency.name, style = MaterialTheme.typography.titleLarge)
                    val accountRows = rows.filter { it.movement.kind != MovementKind.FUEL_REFUND }
                    val incoming = accountRows.filter { it.movement.incoming }.fold(BigDecimal.ZERO) { sum, item -> sum + item.movement.amount.amount }
                    val outgoing = accountRows.filterNot { it.movement.incoming }.fold(BigDecimal.ZERO) { sum, item -> sum + item.movement.amount.amount }
                    Detail("Entradas en cuentas", formatMoney(Money(incoming, currency)))
                    Detail("Salidas de cuentas", formatMoney(Money(outgoing, currency)))
                    Detail("Movimientos en cuentas", accountRows.size.toString())
                    val sum = incoming + outgoing
                    if (sum.signum() > 0) LinearProgressIndicator(progress = { (incoming.toDouble() / sum.toDouble()).toFloat() }, modifier = Modifier.fillMaxWidth())
                    val couponCredits = rows.filter { it.movement.kind == MovementKind.FUEL_REFUND }
                    if (couponCredits.isNotEmpty()) Detail("Abonos a cupones", formatMoney(Money(couponCredits.fold(BigDecimal.ZERO) { total, item -> total + item.movement.amount.amount }, currency)))
                }
            }
        }
        item { Text("Incluye los movimientos registrados en NeoTransfer, también las transferencias entre tus cuentas. Los totales no sustituyen el saldo bancario.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (omitted > 0) item { Text("$omitted movimientos pendientes de importe definitivo o revisión no se incluyen en los totales.", style = MaterialTheme.typography.bodySmall) }
    }
}
