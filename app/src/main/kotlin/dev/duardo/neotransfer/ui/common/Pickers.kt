package dev.duardo.neotransfer.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.R as Ds
import dev.duardo.neotransfer.designsystem.component.NeoBottomSheet
import dev.duardo.neotransfer.designsystem.component.NeoRow
import dev.duardo.neotransfer.designsystem.component.NeoSwitch
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.designsystem.format.formatMoney
import dev.duardo.neotransfer.designsystem.format.unbroken
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoRadius
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.ui.model.BalanceUi
import dev.duardo.neotransfer.ui.model.CardUi
import dev.duardo.neotransfer.ui.model.WalletCardUi

/**
 * One of the user's cards as a field: art, full number and a detail line, framed like a text field (and with its
 * label above when given) so it reads the same on the background and inside a sheet. With several cards a chevron
 * opens a sheet to change it. Used wherever a card is chosen, so choosing looks the same everywhere.
 */
@Composable
fun CardPicker(
    cards: List<CardUi>,
    selected: Int,
    onSelect: (Int) -> Unit,
    sheetTitle: String,
    detail: (Int) -> String,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    var open by remember { mutableStateOf(false) }
    val current = cards[selected]
    val several = cards.size > 1
    val shape = RoundedCornerShape(NeoRadius.field)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        label?.let { Text(it, Modifier.padding(start = NeoSpace.xxs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Column(Modifier.fillMaxWidth().clip(shape).background(Neo.colors.group).border(1.dp, Neo.colors.divider, shape)) {
            NeoRow(
                formatCardNumber(current.number),
                supporting = detail(selected),
                leading = { BankCardThumb(current.bank, width = 52.dp) },
                trailing = if (several) ({ Icon(painterResource(Ds.drawable.ic_expand_more), "Cambiar tarjeta", tint = MaterialTheme.colorScheme.onSurfaceVariant) }) else null,
                onClick = if (several) ({ open = true }) else null,
            )
        }
    }
    if (open) NeoBottomSheet(onDismissRequest = { open = false }, title = sheetTitle) {
        Column(Modifier.padding(horizontal = NeoSpace.xs)) {
            cards.forEachIndexed { index, card ->
                NeoRow(
                    formatCardNumber(card.number),
                    supporting = detail(index),
                    leading = { BankCardThumb(card.bank, width = 52.dp) },
                    trailing = if (index == selected) ({ Icon(painterResource(Ds.drawable.ic_check), "Seleccionada", tint = Neo.colors.accentInk) }) else null,
                    onClick = { open = false; onSelect(index) },
                )
            }
        }
    }
}

/** Card that pays, with its balance. */
@Composable
fun SourceCardPicker(cards: List<WalletCardUi>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, label: String? = null) {
    CardPicker(cards.map { it.card }, selected, onSelect, "Pagar desde", { cards[it].balanceLine() }, modifier, label)
}

private fun WalletCardUi.balanceLine(): String = when (val b = balance) {
    is BalanceUi.Known -> "${card.bank.shortName} · Saldo ${formatMoney(b.money.amount, b.money.currency).unbroken()}"
    BalanceUi.Unknown -> card.bank.shortName
}

/** On/off choice sitting among fields, its words on the same edge as the fields' labels; the row toggles too. */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NeoRadius.field))
            .toggleable(checked, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 56.dp)
            .padding(start = NeoSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium))
        NeoSwitch(checked, null)
    }
}
