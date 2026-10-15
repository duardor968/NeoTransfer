package dev.duardo.neotransfer.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.R as Ds
import dev.duardo.neotransfer.designsystem.component.NeoBottomSheet
import dev.duardo.neotransfer.designsystem.component.NeoGroup
import dev.duardo.neotransfer.designsystem.component.NeoRow
import dev.duardo.neotransfer.designsystem.component.NeoSwitch
import dev.duardo.neotransfer.designsystem.theme.NeoRadius
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.designsystem.format.formatMoney
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.ui.model.BalanceUi
import dev.duardo.neotransfer.ui.model.CardUi
import dev.duardo.neotransfer.ui.model.WalletCardUi

/**
 * One of the user's cards as a field: art, full number and a detail line. With several cards it opens a sheet
 * to change it. Used wherever a card is chosen, so choosing looks the same everywhere.
 */
@Composable
fun CardPicker(
    cards: List<CardUi>,
    selected: Int,
    onSelect: (Int) -> Unit,
    sheetTitle: String,
    detail: (Int) -> String,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val current = cards[selected]
    val several = cards.size > 1
    NeoGroup(modifier) {
        NeoRow(
            formatCardNumber(current.number),
            supporting = detail(selected),
            leading = { BankCardThumb(current.bank, width = 52.dp) },
            trailing = if (several) ({ Icon(painterResource(Ds.drawable.ic_expand_more), "Cambiar tarjeta", tint = MaterialTheme.colorScheme.onSurfaceVariant) }) else null,
            onClick = if (several) ({ open = true }) else null,
        )
    }
    if (open) NeoBottomSheet(onDismissRequest = { open = false }, title = sheetTitle) {
        Column(Modifier.padding(horizontal = NeoSpace.s)) {
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
fun SourceCardPicker(cards: List<WalletCardUi>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    CardPicker(cards.map { it.card }, selected, onSelect, "Pagar desde", { cards[it].balanceLine() }, modifier)
}

private fun WalletCardUi.balanceLine(): String = when (val b = balance) {
    is BalanceUi.Known -> "${card.bank.shortName} · Saldo ${formatMoney(b.money.amount, b.money.currency)}"
    BalanceUi.Unknown -> card.bank.shortName
}

/** On/off choice sitting among fields; the row toggles too. */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    NeoRow(
        label,
        modifier = modifier.clip(RoundedCornerShape(NeoRadius.field)),
        trailing = { NeoSwitch(checked, onChange) },
        onClick = { onChange(!checked) },
    )
}
