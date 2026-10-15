package dev.duardo.neotransfer.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.R as Ds
import dev.duardo.neotransfer.designsystem.component.FittedText
import dev.duardo.neotransfer.designsystem.component.IconAvatar
import dev.duardo.neotransfer.designsystem.component.MoneySign
import dev.duardo.neotransfer.designsystem.component.MoneyText
import dev.duardo.neotransfer.designsystem.component.NameAvatar
import dev.duardo.neotransfer.designsystem.component.NeoButton
import dev.duardo.neotransfer.designsystem.component.NeoButtonStyle
import dev.duardo.neotransfer.designsystem.component.NeoTextButton
import dev.duardo.neotransfer.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.designsystem.component.ReceiptTicket
import dev.duardo.neotransfer.designsystem.component.SummaryLine
import dev.duardo.neotransfer.designsystem.component.SummaryLines
import dev.duardo.neotransfer.designsystem.component.content
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.designsystem.format.formatMoney
import dev.duardo.neotransfer.designsystem.format.formatPhone
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.ui.common.ConfirmedMark
import dev.duardo.neotransfer.ui.common.OperationStatusChip
import dev.duardo.neotransfer.ui.common.icon
import dev.duardo.neotransfer.ui.common.label
import dev.duardo.neotransfer.ui.common.moneyMoved
import dev.duardo.neotransfer.ui.common.timeLabels
import dev.duardo.neotransfer.ui.common.tone
import dev.duardo.neotransfer.ui.model.CounterpartUi
import dev.duardo.neotransfer.ui.model.OperationKind
import dev.duardo.neotransfer.ui.model.OperationStatus
import dev.duardo.neotransfer.ui.model.OperationUi
import dev.duardo.neotransfer.ui.model.ServiceKind

interface OperationActions {
    fun back()
    fun share()
    fun retry()
    fun repeat()
    fun saveContact()
    fun checkBalance()
    fun checkRecent()
}

/**
 * Receipt of one operation. The textured check appears only once the bank confirmed it and stands for the state;
 * otherwise who it was, the state chip and, when it failed, the bank's reason in plain words.
 */
@Composable
fun OperationDetailScreen(operation: OperationUi, actions: OperationActions, modifier: Modifier = Modifier, canSaveContact: Boolean = false) {
    Scaffold(
        modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar(operation.kind.label, onBack = actions::back) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.s, bottom = NeoSpace.xxl),
            ) {
                ReceiptTicket(
                    header = { TicketHeader(operation) },
                    body = { SummaryLines(detailLines(operation), Modifier.padding(vertical = NeoSpace.s)) },
                )
            }
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = NeoSpace.gutter, vertical = NeoSpace.l),
                verticalArrangement = Arrangement.spacedBy(NeoSpace.s),
            ) { DetailActions(operation, actions, canSaveContact) }
        }
    }
}

@Composable
private fun TicketHeader(operation: OperationUi) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = NeoSpace.xl, vertical = NeoSpace.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NeoSpace.m),
    ) {
        when {
            operation.status == OperationStatus.Confirmed -> ConfirmedMark(80.dp)
            operation.contactId != null -> NameAvatar(operation.title, size = 64.dp)
            else -> IconAvatar(operation.kind.icon, size = 64.dp)
        }
        FittedText(operation.title, MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, minScale = 0.75f)
        operation.amount?.let { money ->
            MoneyText(
                money.amount, money.currency,
                style = MaterialTheme.typography.displaySmall.copy(textDecoration = if (operation.status.moneyMoved) null else TextDecoration.LineThrough),
                sign = if (operation.kind.incoming) MoneySign.Plus else MoneySign.None,
                hero = true,
            )
        }
        // The textured check already says it was confirmed; every other state names itself.
        if (operation.status != OperationStatus.Confirmed) OperationStatusChip(operation.status)
        operation.reason?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = operation.status.tone.content(), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun detailLines(operation: OperationUi): List<SummaryLine> {
    val labels = timeLabels

    val counterpart = when (val c = operation.counterpart) {
        is CounterpartUi.Card -> SummaryLine("Tarjeta destino", formatCardNumber(c.number), c.bankName)
        is CounterpartUi.Phone -> SummaryLine("Teléfono", formatPhone(c.number))
        is CounterpartUi.Reference -> SummaryLine(referenceLabel(operation.kind), c.value)
        null -> null
    }?.takeIf { it.value != operation.title }
    return listOfNotNull(
        counterpart,
        operation.card?.let { SummaryLine(if (operation.kind.incoming) "A tu tarjeta" else "Desde", formatCardNumber(it.number), it.bank.shortName) },
        SummaryLine("Fecha", labels.stamp(operation.at)),
        operation.transactionId?.let { SummaryLine("Transacción", it) },
        operation.balanceAfter?.let { SummaryLine("Saldo restante", formatMoney(it.amount, it.currency)) },
    )
}

private fun referenceLabel(kind: OperationKind): String = when (kind) {
    OperationKind.QrPayment -> "ID de pago"
    OperationKind.Electricity -> ServiceKind.Electricity.referenceLabel
    OperationKind.Water -> ServiceKind.Water.referenceLabel
    OperationKind.Landline -> ServiceKind.Landline.referenceLabel
    OperationKind.NautaHome, OperationKind.NautaWifi -> "Cuenta"
    OperationKind.MobileTopUp, OperationKind.CubacelPlan, OperationKind.CubacelTransfer -> "Móvil"
    OperationKind.Transfer, OperationKind.Incoming -> "Referencia"
}

@Composable
private fun DetailActions(operation: OperationUi, actions: OperationActions, canSaveContact: Boolean) {
    when (operation.status) {
        OperationStatus.NotExecuted -> NeoButton("Reintentar", actions::retry, icon = Ds.drawable.ic_replay)
        OperationStatus.NoResponse -> {
            NeoButton("Consultar saldo", actions::checkBalance, style = NeoButtonStyle.Tonal)
            NeoButton("Consultar últimas operaciones", actions::checkRecent, style = NeoButtonStyle.Tonal)
        }
        OperationStatus.Confirmed -> {
            NeoButton("Compartir comprobante", actions::share, icon = Ds.drawable.ic_share)
            if (canSaveContact) NeoTextButton("Guardar contacto", actions::saveContact, Modifier.fillMaxWidth(), icon = Ds.drawable.ic_person_add)
        }
        OperationStatus.Rejected -> if (operation.kind == OperationKind.Transfer) NeoButton("Repetir transferencia", actions::repeat, style = NeoButtonStyle.Tonal)
        OperationStatus.Sent -> Unit
    }
}
