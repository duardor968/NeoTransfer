package dev.duardo.neotransfer.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.R as Ds
import dev.duardo.neotransfer.designsystem.card.AddCardSlot
import dev.duardo.neotransfer.designsystem.card.CardCarousel
import dev.duardo.neotransfer.designsystem.card.PagerDots
import dev.duardo.neotransfer.designsystem.component.AvatarBadge
import dev.duardo.neotransfer.designsystem.component.GroupDivider
import dev.duardo.neotransfer.designsystem.component.IconAvatar
import dev.duardo.neotransfer.designsystem.component.MoneyText
import dev.duardo.neotransfer.designsystem.component.NeoBottomSheet
import dev.duardo.neotransfer.designsystem.component.NeoGroup
import dev.duardo.neotransfer.designsystem.component.NeoIconButton
import dev.duardo.neotransfer.designsystem.component.NeoRow
import dev.duardo.neotransfer.designsystem.component.QuickActionGrid
import dev.duardo.neotransfer.designsystem.component.QuickActionItem
import dev.duardo.neotransfer.designsystem.component.SectionHeader
import dev.duardo.neotransfer.designsystem.component.glow
import dev.duardo.neotransfer.designsystem.component.skeleton
import dev.duardo.neotransfer.designsystem.component.spinning
import dev.duardo.neotransfer.designsystem.format.formatMoney
import dev.duardo.neotransfer.designsystem.format.unbroken
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoMotion
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.ui.common.BankCard
import dev.duardo.neotransfer.ui.common.BrandMark
import dev.duardo.neotransfer.ui.common.OperationAmount
import dev.duardo.neotransfer.ui.common.OperationAvatar
import dev.duardo.neotransfer.ui.common.OperationRow
import dev.duardo.neotransfer.ui.common.artColor
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
        item(key = "wallet") {
            // The card in hand lights the space around it in its own colour, behind the header and the balance too;
            // swiping blends one colour into the next.
            val strength = if (Neo.colors.isDark) 0.22f else 0.12f
            val accent = Neo.colors.accent
            fun tint(page: Int) = (state.cards.getOrNull(page)?.card?.bank?.artColor ?: accent).copy(alpha = strength)
            val fraction = pager.currentPageOffsetFraction
            val next = (pager.currentPage + if (fraction > 0) 1 else -1).coerceIn(0, pages - 1)
            val halo = lerp(tint(pager.currentPage), tint(next), kotlin.math.abs(fraction))
            var height by remember { mutableIntStateOf(0) }
            var cardCenter by remember { mutableFloatStateOf(0f) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { height = it.height }
                    .glow(halo, y = if (height > 0) cardCenter / height else 0.4f, reach = 0.62f),
            ) {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = NeoSpace.gutter, end = NeoSpace.xs).heightIn(min = 64.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrandMark(36.dp)
                    Spacer(Modifier.weight(1f))
                    NeoIconButton(Ds.drawable.ic_settings, "Ajustes", actions::settings)
                }
                CardCarousel(
                    pager,
                    Modifier.onPlaced { cardCenter = it.positionInParent().y + it.size.height / 2f },
                    key = { if (it < state.cards.size) state.cards[it].card.id else "add" },
                ) { page ->
                    val wallet = state.cards.getOrNull(page)
                    if (wallet != null) BankCard(wallet.card, onClick = { actions.openCard(wallet.card.id) })
                    else AddCardSlot("Añadir tarjeta", actions::addCard)
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { PagerDots(pages, pager.currentPage) }
                AnimatedContent(
                    current,
                    transitionSpec = { fadeIn(tween(NeoMotion.medium)) togetherWith fadeOut(tween(NeoMotion.short)) },
                    contentKey = { it?.card?.id },
                    label = "balance",
                ) { wallet ->
                    // A fixed block: swiping between a card with balance and one without moves nothing below.
                    Box(Modifier.fillMaxWidth().padding(top = NeoSpace.l).height(96.dp), contentAlignment = Alignment.Center) {
                        if (wallet != null) Balance(wallet, labels::ago) { actions.refreshBalance(wallet.card.id) }
                    }
                }
            }
        }
        item(key = "actions") {
            QuickActionGrid(
                listOf(
                    QuickActionItem(Ds.drawable.ic_arrow_outward, "Transferir", actions::transfer, primary = true),
                    QuickActionItem(Ds.drawable.ic_qr_code_scanner, "Pagar", actions::pay),
                    QuickActionItem(Ds.drawable.ic_south_west, "Cobrar", actions::receive),
                    QuickActionItem(Ds.drawable.ic_smartphone, "Recargar", actions::recharge),
                ),
                Modifier.padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.xxxl),
            )
        }
        if (state.inProgress.isNotEmpty()) item(key = "progress") {
            Column(Modifier.padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.xxxl)) {
                SectionHeader("Pendientes")
                NeoGroup {
                    state.inProgress.forEachIndexed { index, operation ->
                        if (index > 0) GroupDivider()
                        InProgressRow(operation, labels::ago, { actions.openOperation(operation.id) }, { actions.retry(operation.id) })
                    }
                }
            }
        }
        // Nothing yet: the section stays out until there is something to show; Actividad has the way to ask.
        val recent = state.recent
        if (recent == null || recent.isNotEmpty()) item(key = "recent") {
            Column(Modifier.padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.xxxl)) {
                SectionHeader("Actividad reciente", action = if (recent != null) "Ver todo" else null, onAction = actions::allActivity)
                NeoGroup {
                    if (recent == null) {
                        repeat(3) { index ->
                            if (index > 0) GroupDivider()
                            SkeletonRow()
                        }
                    } else {
                        recent.take(RecentLimit).forEachIndexed { index, operation ->
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
            BalanceUi.Unknown -> RefreshPill(if (wallet.refreshing) "Consultando saldo" else "Consultar saldo", wallet.refreshing, refresh, prominent = true)
        }
    }
}

/**
 * Freshness of the balance; tapping asks the bank again. [prominent] while no balance is known: then asking is the
 * point of the block. While the bank answers, the same glyph turns.
 */
@Composable
private fun RefreshPill(text: String, busy: Boolean, onClick: () -> Unit, prominent: Boolean = false) {
    val content = if (prominent) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (prominent) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer)
            .clickable(enabled = !busy, role = Role.Button, onClickLabel = "Consultar saldo", onClick = onClick)
            .heightIn(min = if (prominent) 52.dp else 36.dp)
            .padding(horizontal = if (prominent) NeoSpace.xxl else NeoSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (prominent) NeoSpace.s + NeoSpace.xxs else NeoSpace.s),
    ) {
        Icon(painterResource(Ds.drawable.ic_refresh), null, Modifier.size(if (prominent) 20.dp else 16.dp).spinning(busy), tint = content)
        Text(text, style = if (prominent) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium, color = content)
    }
}

/**
 * An operation on its way, or money that did not go and waits for one tap: then the row says how much and why on a
 * single line and ends with the retry button.
 */
@Composable
private fun InProgressRow(operation: OperationUi, ago: (java.time.Instant) -> String, open: () -> Unit, retry: () -> Unit) {
    val notExecuted = operation.status == OperationStatus.NotExecuted
    NeoRow(
        operation.title,
        leading = { OperationAvatar(operation) },
        supportingContent = {
            val text = if (notExecuted) {
                listOfNotNull(operation.amount?.let { formatMoney(it.amount, it.currency).unbroken() }, operation.reason ?: operation.status.label).joinToString(" · ")
            } else {
                "${operation.status.label} ${ago(operation.at)}"
            }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (notExecuted) Neo.colors.warning else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailing = {
            if (notExecuted) {
                FilledTonalIconButton(
                    retry,
                    Modifier.size(40.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Neo.colors.accentContainer, contentColor = Neo.colors.onAccentContainer),
                ) { Icon(painterResource(Ds.drawable.ic_replay), "Reintentar", Modifier.size(20.dp)) }
            } else {
                OperationAmount(operation)
            }
        },
        onClick = open,
        trailingBelowWhenLarge = !notExecuted,
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

/** Saved numbers and Nauta accounts, one tap from Home; then a new one of each kind, under its own glyph. */
@Composable
private fun RechargeSheet(targets: List<RechargeTargetUi>, actions: HomeActions) {
    NeoBottomSheet(onDismissRequest = actions::closeRecharge, title = "Recargar") {
        Column(Modifier.padding(horizontal = NeoSpace.xs)) {
            targets.forEach { target ->
                val kind = if (target.kind == ServiceKind.MobileTopUp) "Móvil" else target.kind.label
                NeoRow(target.name, supporting = "$kind · ${target.value}", leading = { IconAvatar(target.kind.icon) }, onClick = { actions.rechargeTo(target.id) })
            }
            val saved = targets.isNotEmpty()
            val added = AvatarBadge(Ds.drawable.ic_add, Neo.colors.accent, Neo.colors.onAccent)
            NeoRow(
                if (saved) "Otro móvil" else "Móvil",
                leading = { IconAvatar(ServiceKind.MobileTopUp.icon, badge = added) },
                onClick = { actions.rechargeNew(ServiceKind.MobileTopUp) },
            )
            NeoRow(
                if (saved) "Otra cuenta Nauta" else "Cuenta Nauta",
                leading = { IconAvatar(ServiceKind.NautaWifi.icon, badge = added) },
                onClick = { actions.rechargeNew(ServiceKind.NautaWifi) },
            )
        }
    }
}
