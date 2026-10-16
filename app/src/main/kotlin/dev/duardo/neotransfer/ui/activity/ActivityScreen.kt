package dev.duardo.neotransfer.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.R as Ds
import dev.duardo.neotransfer.designsystem.component.EmptyState
import dev.duardo.neotransfer.designsystem.component.GroupDivider
import dev.duardo.neotransfer.designsystem.component.NeoBottomSheet
import dev.duardo.neotransfer.designsystem.component.NeoChip
import dev.duardo.neotransfer.designsystem.component.NeoRow
import dev.duardo.neotransfer.designsystem.component.NeoSearchField
import dev.duardo.neotransfer.designsystem.component.TabHeader
import dev.duardo.neotransfer.designsystem.component.groupItem
import dev.duardo.neotransfer.designsystem.component.rim
import dev.duardo.neotransfer.designsystem.component.skeleton
import dev.duardo.neotransfer.designsystem.component.spinning
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.ui.common.BankCardThumb
import dev.duardo.neotransfer.ui.common.OperationRow
import dev.duardo.neotransfer.ui.common.timeLabels
import dev.duardo.neotransfer.ui.model.ActivityContent
import dev.duardo.neotransfer.ui.model.ActivityFilter
import dev.duardo.neotransfer.ui.model.ActivityUi

interface ActivityActions {
    fun query(value: String)
    fun filter(value: ActivityFilter)
    fun card(cardId: String?)
    fun open(id: String)
    fun allowSms()
    /** Last operations of the filtered card, the only one, or one to pick. */
    fun checkWithBank()
    fun checkCard(cardId: String)
    fun closeCheck()
}

/** Every movement, newest first, grouped by day; search and filters stay on top. */
@Composable
fun ActivityScreen(state: ActivityUi, actions: ActivityActions, modifier: Modifier = Modifier) {
    val labels = timeLabels
    // Search and filters only where there is something to narrow down.
    val searchable = state.content != ActivityContent.Empty && state.content != ActivityContent.NoAccess
    Column(modifier.fillMaxSize()) {
        TabHeader("Actividad") {
            if (state.cards.isNotEmpty() && state.content != ActivityContent.NoAccess) {
                IconButton(actions::checkWithBank, enabled = !state.checking) {
                    Icon(painterResource(Ds.drawable.ic_refresh), "Últimas operaciones", Modifier.spinning(state.checking))
                }
            }
            if (searchable && state.cards.size > 1) CardFilter(state, actions, Modifier.padding(start = NeoSpace.xs, end = NeoSpace.l))
        }
        if (searchable) NeoSearchField(state.query, actions::query, "Buscar nombre, tarjeta o importe", Modifier.padding(horizontal = NeoSpace.gutter))
        if (searchable) Filters(state, actions)
        when (val content = state.content) {
            ActivityContent.Loading -> LoadingList()
            ActivityContent.NoAccess -> EmptyState(
                Ds.drawable.ic_sms, "Sin acceso a los SMS del banco",
                body = "La actividad se construye con los resultados que envía el banco.",
                action = "Permitir", onAction = actions::allowSms,
            )
            ActivityContent.Empty -> EmptyState(
                Ds.drawable.ic_receipt_long, "Sin movimientos",
                action = "Consultar al banco", onAction = actions::checkWithBank,
            )
            ActivityContent.NoResults -> EmptyState(Ds.drawable.ic_search, "Nada coincide con la búsqueda")
            is ActivityContent.Items -> {
                val days = content.operations.groupBy { labels.date(it.at) }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = NeoSpace.gutter, end = NeoSpace.gutter, bottom = NeoSpace.xxl),
                ) {
                    days.forEach { (day, operations) ->
                        item(key = "day-$day") {
                            Text(
                                labels.day(day),
                                Modifier.padding(top = NeoSpace.xl, bottom = NeoSpace.s),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        itemsIndexed(operations, key = { _, it -> it.id }) { index, operation ->
                            Column(Modifier.groupItem(index, operations.size)) {
                                if (index > 0) GroupDivider()
                                OperationRow(operation, labels.clockTime(operation.at), { actions.open(operation.id) })
                            }
                        }
                    }
                }
            }
        }
    }
    if (state.pickCard) NeoBottomSheet(actions::closeCheck, title = "Últimas operaciones") {
        Text(
            "La respuesta llega por SMS.",
            Modifier.padding(horizontal = NeoSpace.gutter, vertical = NeoSpace.s),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(Modifier.padding(horizontal = NeoSpace.xs)) {
            state.cards.forEach { card ->
                NeoRow(
                    formatCardNumber(card.number),
                    supporting = card.bank.shortName,
                    leading = { BankCardThumb(card.bank) },
                    onClick = { actions.checkCard(card.id) },
                )
            }
        }
    }
}

/**
 * Which card's movements, said on the control itself: the chosen card's art with the last group of its number, or
 * "Todas".
 */
@Composable
private fun CardFilter(state: ActivityUi, actions: ActivityActions, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val selected = state.cards.firstOrNull { it.id == state.cardFilter }
    Box(modifier) {
        Row(
            Modifier
                .clip(CircleShape)
                .background(if (selected != null) Neo.colors.accentContainer else Neo.colors.group)
                .then(if (selected != null) Modifier else Modifier.rim(CircleShape))
                .clickable(role = Role.Button, onClickLabel = "Filtrar por tarjeta") { open = true }
                .heightIn(min = 40.dp)
                .padding(start = NeoSpace.m, end = NeoSpace.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(NeoSpace.s),
        ) {
            if (selected != null) BankCardThumb(selected.bank, width = 28.dp)
            else Icon(painterResource(Ds.drawable.ic_credit_card), null, Modifier.size(20.dp))
            Text(
                selected?.number?.takeLast(4) ?: "Todas",
                style = MaterialTheme.typography.labelLarge,
                color = if (selected != null) Neo.colors.onAccentContainer else MaterialTheme.colorScheme.onSurface,
            )
            Icon(painterResource(Ds.drawable.ic_expand_more), null, Modifier.size(20.dp))
        }
        DropdownMenu(open, { open = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            DropdownMenuItem(
                { Text("Todas las tarjetas") },
                { open = false; actions.card(null) },
                leadingIcon = { Icon(painterResource(Ds.drawable.ic_credit_card), null) },
            )
            state.cards.forEach { card ->
                DropdownMenuItem(
                    { Text(formatCardNumber(card.number)) },
                    { open = false; actions.card(card.id) },
                    leadingIcon = { BankCardThumb(card.bank, width = 34.dp) },
                )
            }
        }
    }
}

@Composable
private fun Filters(state: ActivityUi, actions: ActivityActions) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.m),
        horizontalArrangement = Arrangement.spacedBy(NeoSpace.s),
    ) {
        ActivityFilter.entries.forEach { filter -> NeoChip(filter.label, state.filter == filter, { actions.filter(filter) }) }
    }
}

@Composable
private fun LoadingList() {
    Column(Modifier.padding(horizontal = NeoSpace.gutter)) {
        Box(Modifier.padding(top = NeoSpace.xxl, bottom = NeoSpace.m).width(64.dp).height(14.dp).skeleton())
        Column(Modifier.groupItem(0, 1)) {
            repeat(6) { index ->
                if (index > 0) GroupDivider()
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = NeoSpace.l, vertical = NeoSpace.m),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(NeoSpace.m),
                ) {
                    Box(Modifier.size(44.dp).skeleton(CircleShape))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth(0.6f).height(14.dp).skeleton())
                        Box(Modifier.fillMaxWidth(0.25f).height(12.dp).skeleton())
                    }
                    Box(Modifier.width(76.dp).height(14.dp).skeleton())
                }
            }
        }
    }
}
