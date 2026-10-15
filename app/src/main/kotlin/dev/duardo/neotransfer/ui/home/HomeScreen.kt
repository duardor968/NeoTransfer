package dev.duardo.neotransfer.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.R as Ds
import dev.duardo.neotransfer.designsystem.card.AddCardSlot
import dev.duardo.neotransfer.designsystem.card.CardCarousel
import dev.duardo.neotransfer.designsystem.card.PagerDots
import dev.duardo.neotransfer.designsystem.component.GroupDivider
import dev.duardo.neotransfer.designsystem.component.IconAvatar
import dev.duardo.neotransfer.designsystem.component.MoneyText
import dev.duardo.neotransfer.designsystem.component.NeoBottomSheet
import dev.duardo.neotransfer.designsystem.component.NeoButton
import dev.duardo.neotransfer.designsystem.component.NeoButtonStyle
import dev.duardo.neotransfer.designsystem.component.NeoGroup
import dev.duardo.neotransfer.designsystem.component.NeoIconButton
import dev.duardo.neotransfer.designsystem.component.NeoRow
import dev.duardo.neotransfer.designsystem.component.NeoTextButton
import dev.duardo.neotransfer.designsystem.component.QuickAction
import dev.duardo.neotransfer.designsystem.component.SectionHeader
import dev.duardo.neotransfer.designsystem.component.skeleton
import dev.duardo.neotransfer.designsystem.format.formatAmount
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.ui.common.BankCard
import dev.duardo.neotransfer.ui.common.BrandMark
import dev.duardo.neotransfer.ui.common.OperationAmount
import dev.duardo.neotransfer.ui.common.OperationAvatar
import dev.duardo.neotransfer.ui.common.OperationRow
import dev.duardo.neotransfer.ui.common.icon
import dev.duardo.neotransfer.ui.common.label
import dev.duardo.neotransfer.ui.common.timeLabels
import dev.duardo.neotransfer.ui.model.BalanceUi
import dev.duardo.neotransfer.ui.model.HomeUi
import dev.duardo.neotransfer.ui.model.OperationStatus
import dev.duardo.neotransfer.ui.model.OperationUi
import dev.duardo.neotransfer.ui.model.RechargeTargetUi
import dev.duardo.neotransfer.ui.model.ServiceKind
import dev.duardo.neotransfer.ui.model.WalletCardUi
import kotlinx.coroutines.flow.distinctUntilChanged

interface HomeActions {
    fun selectCard(index: Int)
    fun refreshBalance(cardId: String)
    fun openCard(cardId: String)
    fun addCard()
    fun transfer()
    fun pay()
    fun receive()
    fun recharge()
    fun rechargeTo(targetId: String)
    fun rechargeNew(kind: ServiceKind)
    fun closeRecharge()
    fun openOperation(id: String)
    fun retry(id: String)
    fun allActivity()
    fun settings()
}

private const val RecentLimit = 5

/** Wallet: the card in hand, its balance, the four shortcuts, what is in flight and what just happened. */
@Composable
fun HomeScreen(state: HomeUi, actions: HomeActions, modifier: Modifier = Modifier) {
    val pages = state.cards.size + 1
    val pager = rememberPagerState(initialPage = state.selected.coerceIn(0, pages - 1)) { pages }
    val latest by rememberUpdatedState(state)
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
            if (page < latest.cards.size && page != latest.selected) actions.selectCard(page)
        }
    }
    val current = state.cards.getOrNull(pager.currentPage)
    val labels = timeLabels

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = NeoSpace.xxl)) {
        item(key = "header") {
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(start = NeoSpace.gutter, end = NeoSpace.xs).heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrandMark(36.dp)
                Spacer(Modifier.weight(1f))
                NeoIconButton(Ds.drawable.ic_settings, "Ajustes", actions::settings)
            }
        }
        item(key = "wallet") {
            CardCarousel(pager, key = { if (it < state.cards.size) state.cards[it].card.id else "add" }) { page ->
                val wallet = state.cards.getOrNull(page)
                if (wallet != null) BankCard(wallet.card, onClick = { actions.openCard(wallet.card.id) })
                else AddCardSlot("Añadir tarjeta", actions::addCard)
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PagerDots(pages, pager.currentPage) }
        }
        item(key = "balance") {
            AnimatedContent(
                current,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                contentKey = { it?.card?.id },
                label = "balance",
            ) { wallet ->
                Box(Modifier.fillMaxWidth().heightIn(min = 112.dp), contentAlignment = Alignment.Center) {
                    if (wallet != null) Balance(wallet, labels::ago) { actions.refreshBalance(wallet.card.id) }
                }
            }
        }
        item(key = "actions") {
            Row(Modifier.fillMaxWidth().padding(horizontal = NeoSpace.m, vertical = NeoSpace.s)) {
                QuickAction(Ds.drawable.ic_arrow_outward, "Transferir", actions::transfer, Modifier.weight(1f), primary = true)
                QuickAction(Ds.drawable.ic_qr_code_scanner, "Pagar", actions::pay, Modifier.weight(1f))
                QuickAction(Ds.drawable.ic_south_west, "Cobrar", actions::receive, Modifier.weight(1f))
                QuickAction(Ds.drawable.ic_smartphone, "Recargar", actions::recharge, Modifier.weight(1f))
            }
        }
        if (state.inProgress.isNotEmpty()) item(key = "progress") {
            Column(Modifier.padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.l)) {
                SectionHeader("En curso")
                NeoGroup {
                    state.inProgress.forEachIndexed { index, operation ->
                        if (index > 0) GroupDivider()
                        InProgressRow(operation, labels::ago, { actions.openOperation(operation.id) }, { actions.retry(operation.id) })
                    }
                }
            }
        }
        item(key = "recent") {
            Column(Modifier.padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.l)) {
                SectionHeader("Actividad reciente", action = "Ver todo", onAction = actions::allActivity)
                NeoGroup {
                    val recent = state.recent
                    when {
                        recent == null -> repeat(3) { index ->
                            if (index > 0) GroupDivider()
                            SkeletonRow()
                        }
                        recent.isEmpty() -> NeoRow(
                            "Sin movimientos",
                            leading = { IconAvatar(Ds.drawable.ic_receipt_long, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        )
                        else -> recent.take(RecentLimit).forEachIndexed { index, operation ->
                            if (index > 0) GroupDivider()
                            OperationRow(operation, labels.moment(operation.at), { actions.openOperation(operation.id) })
                        }
                    }
                }
            }
        }
    }

    state.recharge?.let { targets -> RechargeSheet(targets, actions) }
}

@Composable
private fun Balance(wallet: WalletCardUi, ago: (java.time.Instant) -> String, refresh: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
        when (val balance = wallet.balance) {
            is BalanceUi.Known -> {
                MoneyText(balance.money.amount, balance.money.currency, style = MaterialTheme.typography.displayMedium, hero = true)
                RefreshPill(if (wallet.refreshing) "Consultando saldo" else "Actualizado ${ago(balance.updatedAt)}", wallet.refreshing, refresh)
            }
            BalanceUi.Unknown -> NeoButton(
                if (wallet.refreshing) "Consultando saldo" else "Consultar saldo", refresh, style = NeoButtonStyle.Tonal, icon = Ds.drawable.ic_refresh,
                loading = wallet.refreshing, fill = false,
            )
        }
    }
}

/** Freshness of the balance; tapping asks the bank again. */
@Composable
private fun RefreshPill(text: String, busy: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = !busy, role = Role.Button, onClickLabel = "Consultar saldo", onClick = onClick)
            .heightIn(min = 36.dp)
            .padding(horizontal = NeoSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NeoSpace.s),
    ) {
        if (busy) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.75.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Icon(painterResource(Ds.drawable.ic_refresh), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun InProgressRow(operation: OperationUi, ago: (java.time.Instant) -> String, open: () -> Unit, retry: () -> Unit) {
    val notExecuted = operation.status == OperationStatus.NotExecuted
    NeoRow(
        operation.title,
        leading = { OperationAvatar(operation) },
        supportingContent = {
            val text = if (notExecuted) {
                listOfNotNull(operation.amount?.let { "${formatAmount(it.amount)} ${it.currency}" }, operation.reason ?: operation.status.label).joinToString(" · ")
            } else {
                "${operation.status.label} ${ago(operation.at)}"
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (notExecuted) Neo.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailing = { if (notExecuted) NeoTextButton("Reintentar", retry) else OperationAmount(operation) },
        onClick = open,
        trailingBelowWhenLarge = true,
    )
}

@Composable
private fun SkeletonRow() {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = NeoSpace.l, vertical = NeoSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NeoSpace.m),
    ) {
        Box(Modifier.size(44.dp).skeleton(CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(0.55f).height(14.dp).skeleton())
            Box(Modifier.fillMaxWidth(0.3f).height(12.dp).skeleton())
        }
        Box(Modifier.width(72.dp).height(14.dp).skeleton())
    }
}

/** Saved numbers and Nauta accounts, one tap from Home. */
@Composable
private fun RechargeSheet(targets: List<RechargeTargetUi>, actions: HomeActions) {
    NeoBottomSheet(onDismissRequest = actions::closeRecharge, title = "Recargar") {
        Column(Modifier.padding(horizontal = NeoSpace.s)) {
            targets.forEach { target ->
                val kind = if (target.kind == ServiceKind.MobileTopUp) "Móvil" else target.kind.label
                NeoRow(target.name, supporting = "$kind · ${target.value}", leading = { IconAvatar(target.kind.icon) }, onClick = { actions.rechargeTo(target.id) })
            }
            val saved = targets.isNotEmpty()
            NeoRow(if (saved) "Otro móvil" else "Móvil", leading = { IconAvatar(Ds.drawable.ic_add) }, onClick = { actions.rechargeNew(ServiceKind.MobileTopUp) })
            NeoRow(if (saved) "Otra cuenta Nauta" else "Cuenta Nauta", leading = { IconAvatar(Ds.drawable.ic_add) }, onClick = { actions.rechargeNew(ServiceKind.NautaWifi) })
        }
    }
}
