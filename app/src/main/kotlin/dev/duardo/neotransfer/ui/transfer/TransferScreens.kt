package dev.duardo.neotransfer.ui.transfer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.R as Ds
import dev.duardo.neotransfer.designsystem.component.AmountDisplay
import dev.duardo.neotransfer.designsystem.component.GroupDivider
import dev.duardo.neotransfer.designsystem.component.IconAvatar
import dev.duardo.neotransfer.designsystem.component.KeypadKey
import dev.duardo.neotransfer.designsystem.component.NameAvatar
import dev.duardo.neotransfer.designsystem.component.NeoBottomSheet
import dev.duardo.neotransfer.designsystem.component.NeoButton
import dev.duardo.neotransfer.designsystem.component.NeoButtonStyle
import dev.duardo.neotransfer.designsystem.component.NeoConfirmDialog
import dev.duardo.neotransfer.designsystem.component.NeoKeypad
import dev.duardo.neotransfer.designsystem.component.NeoRow
import dev.duardo.neotransfer.designsystem.component.NeoSearchField
import dev.duardo.neotransfer.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.designsystem.component.ReviewLayout
import dev.duardo.neotransfer.designsystem.component.SectionHeader
import dev.duardo.neotransfer.designsystem.component.SummaryLine
import dev.duardo.neotransfer.designsystem.component.groupItem
import dev.duardo.neotransfer.designsystem.format.formatAmount
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.designsystem.format.formatMoney
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.ui.common.ConfirmedMark
import dev.duardo.neotransfer.ui.common.SourceCardPicker
import dev.duardo.neotransfer.ui.common.SwitchRow
import dev.duardo.neotransfer.designsystem.component.NeoIconButton
import dev.duardo.neotransfer.ui.common.label
import dev.duardo.neotransfer.ui.common.timeLabels
import dev.duardo.neotransfer.ui.model.ContactUi
import dev.duardo.neotransfer.ui.model.DuplicateUi
import dev.duardo.neotransfer.ui.model.OperationStatus
import dev.duardo.neotransfer.ui.model.RecipientPickUi
import dev.duardo.neotransfer.ui.model.RecipientUi
import dev.duardo.neotransfer.ui.model.TransferAmountUi
import dev.duardo.neotransfer.ui.model.TransferResultUi
import dev.duardo.neotransfer.ui.model.TransferReviewUi
import dev.duardo.neotransfer.ui.onboarding.BottomAction

// ---- Recipient ----------------------------------------------------------------------------------------------

interface RecipientActions {
    fun back()
    fun query(value: String)
    fun scan()
    fun pickTyped()
    fun pick(recipient: RecipientUi)
    fun pickContact(contactId: String)
    fun pickContactCard(contactId: String, cardId: String)
    fun closeCards()
}

/** Who receives: a contact, a recent recipient, a typed or scanned card number. */
@Composable
fun RecipientScreen(state: RecipientPickUi, actions: RecipientActions, modifier: Modifier = Modifier) {
    Scaffold(
        modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar("Transferir", onBack = actions::back) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = NeoSpace.xxl),
        ) {
            item(key = "search") {
                Column(Modifier.padding(horizontal = NeoSpace.gutter), verticalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
                    NeoSearchField(
                        state.query, actions::query, "Nombre o número de tarjeta",
                        emptyAction = { NeoIconButton(Ds.drawable.ic_photo_camera, "Escanear tarjeta o QR", actions::scan, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    )
                    state.typedError?.let { Text(it, Modifier.padding(start = NeoSpace.l), style = MaterialTheme.typography.bodyMedium, color = Neo.colors.danger) }
                }
            }
            if (state.recent.isNotEmpty()) item(key = "recent") {
                Column(Modifier.padding(top = NeoSpace.l)) {
                    SectionHeader("Recientes", Modifier.padding(horizontal = NeoSpace.gutter))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = NeoSpace.gutter),
                        horizontalArrangement = Arrangement.spacedBy(NeoSpace.s),
                    ) {
                        items(state.recent, key = { it.card.id }) { recipient -> RecentChip(recipient) { actions.pick(recipient) } }
                    }
                }
            }
            val typed = state.typed
            val count = state.contacts.size + if (typed != null) 1 else 0
            if (count > 0) {
                item(key = "contacts-title") {
                    if (state.contacts.isNotEmpty()) SectionHeader("Contactos", Modifier.padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.l))
                    else Spacer(Modifier.height(NeoSpace.l))
                }
                if (typed != null) item(key = "typed") {
                    Column(Modifier.padding(horizontal = NeoSpace.gutter).groupItem(0, count, Neo.colors.group)) {
                        NeoRow(
                            formatCardNumber(typed.number),
                            supporting = typed.bankName ?: "Tarjeta nueva",
                            leading = { IconAvatar(Ds.drawable.ic_credit_card) },
                            onClick = actions::pickTyped,
                        )
                    }
                }
                val offset = if (typed != null) 1 else 0
                itemsIndexed(state.contacts, key = { _, it -> it.id }) { index, contact ->
                    Column(Modifier.padding(horizontal = NeoSpace.gutter).groupItem(index + offset, count, Neo.colors.group)) {
                        if (index + offset > 0) GroupDivider()
                        NeoRow(
                            contact.name,
                            supporting = contact.cards.firstOrNull()?.let { first ->
                                formatCardNumber(first.number) + if (contact.cards.size > 1) " · ${contact.cards.size} tarjetas" else ""
                            },
                            leading = { NameAvatar(contact.name) },
                            onClick = { actions.pickContact(contact.id) },
                        )
                    }
                }
            }
        }
    }
    state.chooseCardOf?.let { contact -> ContactCardsSheet(contact, actions) }
}

@Composable
private fun RecentChip(recipient: RecipientUi, onClick: () -> Unit) {
    Column(
        Modifier.width(76.dp).clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button, onClick = onClick).padding(vertical = NeoSpace.s),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(NeoSpace.s),
    ) {
        val name = recipient.name
        if (name != null) NameAvatar(name, size = 52.dp) else IconAvatar(Ds.drawable.ic_credit_card, size = 52.dp)
        Text(
            name?.substringBefore(' ') ?: formatCardNumber(recipient.card.number),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ContactCardsSheet(contact: ContactUi, actions: RecipientActions) {
    NeoBottomSheet(onDismissRequest = actions::closeCards, title = "Tarjetas de ${contact.name.substringBefore(' ')}") {
        Column(Modifier.padding(horizontal = NeoSpace.s)) {
            contact.cards.forEach { card ->
                NeoRow(
                    formatCardNumber(card.number),
                    supporting = listOfNotNull(card.bankName, card.label).joinToString(" · ").ifEmpty { null },
                    leading = { IconAvatar(Ds.drawable.ic_credit_card) },
                    onClick = { actions.pickContactCard(contact.id, card.id) },
                )
            }
        }
    }
}

// ---- Amount -------------------------------------------------------------------------------------------------

interface AmountActions {
    fun back()
    fun key(key: KeypadKey)
    fun source(index: Int)
    fun next()
}

/** How much and from which card. Keypad on screen; the balance warns, it does not block: the bank decides. */
@Composable
fun TransferAmountScreen(state: TransferAmountUi, actions: AmountActions, modifier: Modifier = Modifier) {
    Scaffold(
        modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar(onBack = actions::back) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val name = state.recipient.name
                NeoRow(
                    name ?: formatCardNumber(state.recipient.card.number),
                    supporting = listOfNotNull(if (name != null) formatCardNumber(state.recipient.card.number) else null, state.recipient.card.bankName).joinToString(" · "),
                    leading = { if (name != null) NameAvatar(name) else IconAvatar(Ds.drawable.ic_credit_card) },
                )
                Spacer(Modifier.height(NeoSpace.xl))
                AmountDisplay(state.input, state.currency)
                if (state.overBalance) Text(
                    "Más que el saldo de esta tarjeta",
                    Modifier.padding(top = NeoSpace.xs),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Neo.colors.warning,
                )
                Spacer(Modifier.height(NeoSpace.xl))
                SourceCardPicker(state.sources, state.source, actions::source)
            }
            NeoKeypad(actions::key, Modifier.padding(horizontal = NeoSpace.m, vertical = NeoSpace.s))
            BottomAction { NeoButton("Continuar", actions::next, enabled = state.canContinue) }
        }
    }
}

@Composable
private fun RecipientHeader(recipient: RecipientUi) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(NeoSpace.xs)) {
        val name = recipient.name
        if (name != null) NameAvatar(name, size = 56.dp) else IconAvatar(Ds.drawable.ic_credit_card, size = 56.dp)
        Spacer(Modifier.height(NeoSpace.xs))
        Text(name ?: formatCardNumber(recipient.card.number), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (name == null) recipient.card.bankName?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

// ---- Review -------------------------------------------------------------------------------------------------

interface ReviewActions {
    fun back()
    fun saveContact(value: Boolean)
    fun confirm()
    fun confirmDuplicate()
    fun cancelDuplicate()
}

/** Last look before the fingerprint: what, to whom, from where. */
@Composable
fun TransferReviewScreen(state: TransferReviewUi, actions: ReviewActions, modifier: Modifier = Modifier) {
    Scaffold(
        modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar("Revisar", onBack = actions::back) },
    ) { padding ->
        ReviewLayout(
            amount = state.amount.amount,
            currency = state.amount.currency,
            lines = listOfNotNull(
                state.recipient.name?.let { SummaryLine("Tarjeta destino", formatCardNumber(state.recipient.card.number), state.recipient.card.bankName) },
                SummaryLine("Desde", formatCardNumber(state.source.number), state.source.bank.shortName),
            ),
            confirm = "Transferir ${formatMoney(state.amount.amount, state.amount.currency)}",
            onConfirm = actions::confirm,
            modifier = Modifier.padding(padding),
            confirmIcon = Ds.drawable.ic_fingerprint,
            confirming = state.sending,
            lead = { RecipientHeader(state.recipient) },
            extra = {
                state.saveContact?.let { save -> SwitchRow("Guardar como contacto", save, actions::saveContact, Modifier.padding(top = NeoSpace.s)) }
            },
        )
    }
    state.duplicate?.let { DuplicateDialog(it, actions::confirmDuplicate, actions::cancelDuplicate) }
}

/** Same card, same destination, same amount, still waiting: ask, never block. */
@Composable
fun DuplicateDialog(duplicate: DuplicateUi, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val amount = "${formatAmount(duplicate.amount.amount).removeSuffix(".00")} ${duplicate.amount.currency}"
    val first = duplicate.recipient.substringBefore(' ')
    NeoConfirmDialog(
        title = "¿Enviar otros $amount?",
        text = "Ya enviaste $amount a $first ${timeLabels.ago(duplicate.sentAt)} y aún no llegó la respuesta.",
        confirm = "Enviar otros $amount",
        dismiss = "Cancelar",
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

// ---- Result -------------------------------------------------------------------------------------------------

interface ResultActions {
    fun done()
    fun open()
}

/**
 * After the bank's acknowledgement. Sent leaves the app free; the screen turns into the confirmed check or the
 * rejection when the result arrives while it is still open.
 */
@Composable
fun TransferResultScreen(state: TransferResultUi, actions: ResultActions, modifier: Modifier = Modifier) {
    val operation = state.operation
    Scaffold(modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Spacer(Modifier.height(NeoSpace.huge))
                when (operation.status) {
                    OperationStatus.Confirmed -> ConfirmedMark(128.dp)
                    OperationStatus.Sent -> SendingMark()
                    else -> Box(Modifier.size(112.dp).background(Neo.colors.dangerContainer, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(painterResource(Ds.drawable.ic_close), null, Modifier.size(48.dp), tint = Neo.colors.danger)
                    }
                }
                Spacer(Modifier.height(NeoSpace.xxl))
                Text(operation.status.label, style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(NeoSpace.s))
                operation.amount?.let { money ->
                    Text(
                        "${formatMoney(money.amount, money.currency)} a ${operation.title}",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                }
                val note = when (operation.status) {
                    OperationStatus.Sent -> null
                    OperationStatus.Rejected -> operation.reason
                    else -> operation.transactionId?.let { "Transacción $it" }
                }
                note?.let {
                    Text(
                        it,
                        Modifier.padding(top = NeoSpace.s),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (operation.status == OperationStatus.Rejected) Neo.colors.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(NeoSpace.huge))
            }
            BottomAction {
                NeoButton("Ver operación", actions::open, style = NeoButtonStyle.Tonal)
                NeoButton("Volver al inicio", actions::done)
            }
        }
    }
}

/** The money is on its way: the arrow inside a slowly turning arc. */
@Composable
private fun SendingMark() {
    val transition = rememberInfiniteTransition(label = "sending")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing), RepeatMode.Restart), label = "arc")
    val arc = Neo.colors.accent
    val track = Neo.colors.accentContainer
    Box(
        Modifier.size(128.dp).drawBehind {
            val stroke = 4.dp.toPx()
            val inset = stroke / 2
            val diameter = size.minDimension - stroke
            drawArc(track, 0f, 360f, false, Offset(inset, inset), androidx.compose.ui.geometry.Size(diameter, diameter), style = Stroke(stroke))
            drawArc(arc, angle - 90f, 70f, false, Offset(inset, inset), androidx.compose.ui.geometry.Size(diameter, diameter), style = Stroke(stroke, cap = StrokeCap.Round))
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(96.dp).background(track, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(Ds.drawable.ic_arrow_outward), null, Modifier.size(44.dp), tint = Neo.colors.onAccentContainer)
        }
    }
}
