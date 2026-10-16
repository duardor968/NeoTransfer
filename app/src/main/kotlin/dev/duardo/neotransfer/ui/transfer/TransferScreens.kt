package dev.duardo.neotransfer.ui.transfer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.duardo.neotransfer.designsystem.R as Ds
import dev.duardo.neotransfer.designsystem.component.AmountDisplay
import dev.duardo.neotransfer.designsystem.component.EmptyState
import dev.duardo.neotransfer.designsystem.component.FittedText
import dev.duardo.neotransfer.designsystem.component.GroupDivider
import dev.duardo.neotransfer.designsystem.component.IconAvatar
import dev.duardo.neotransfer.designsystem.component.KeypadKey
import dev.duardo.neotransfer.designsystem.component.NameAvatar
import dev.duardo.neotransfer.designsystem.component.NeoBottomSheet
import dev.duardo.neotransfer.designsystem.component.NeoButton
import dev.duardo.neotransfer.designsystem.component.NeoConfirmSheet
import dev.duardo.neotransfer.designsystem.component.NeoIconButton
import dev.duardo.neotransfer.designsystem.component.NeoKeypad
import dev.duardo.neotransfer.designsystem.component.NeoRow
import dev.duardo.neotransfer.designsystem.component.NeoSearchField
import dev.duardo.neotransfer.designsystem.component.NeoTextButton
import dev.duardo.neotransfer.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.designsystem.component.ReviewLayout
import dev.duardo.neotransfer.designsystem.component.SectionHeader
import dev.duardo.neotransfer.designsystem.component.StatusMark
import dev.duardo.neotransfer.designsystem.component.StatusTone
import dev.duardo.neotransfer.designsystem.component.SummaryLine
import dev.duardo.neotransfer.designsystem.component.groupItem
import dev.duardo.neotransfer.designsystem.format.formatAmount
import dev.duardo.neotransfer.designsystem.format.NoBreak
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.designsystem.format.formatMoney
import dev.duardo.neotransfer.designsystem.format.unbroken
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoMotion
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.ui.common.CardNumberTransformation
import dev.duardo.neotransfer.ui.common.ConfirmedMark
import dev.duardo.neotransfer.ui.common.SourceCardPicker
import dev.duardo.neotransfer.ui.common.SwitchRow
import dev.duardo.neotransfer.ui.common.label
import dev.duardo.neotransfer.ui.common.markIcon
import dev.duardo.neotransfer.ui.common.timeLabels
import dev.duardo.neotransfer.ui.common.tone
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
                        visualTransformation = if (state.query.isNotEmpty() && state.query.all(Char::isDigit)) CardNumberTransformation else VisualTransformation.None,
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
            if (state.query.isBlank() && state.recent.isEmpty() && state.contacts.isEmpty()) item(key = "empty") {
                EmptyState(Ds.drawable.ic_contacts, "Sin contactos con tarjeta")
            }
            val count = state.contacts.size + if (typed != null) 1 else 0
            if (count > 0) {
                item(key = "contacts-title") {
                    if (state.contacts.isNotEmpty()) SectionHeader("Contactos", Modifier.padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.l))
                    else Spacer(Modifier.height(NeoSpace.l))
                }
                if (typed != null) item(key = "typed") {
                    Column(Modifier.padding(horizontal = NeoSpace.gutter).groupItem(0, count)) {
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
                    Column(Modifier.padding(horizontal = NeoSpace.gutter).groupItem(index + offset, count)) {
                        if (index + offset > 0) GroupDivider()
                        NeoRow(
                            contact.name,
                            supporting = contact.cards.firstOrNull()?.let { first ->
                                formatCardNumber(first.number) + if (contact.cards.size > 1) "$NoBreak· " + "${contact.cards.size} tarjetas".unbroken() else ""
                            } ?: "Sin tarjeta guardada",
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
            name?.substringBefore(' ') ?: recipient.card.number.chunked(8).joinToString("\n", transform = ::formatCardNumber),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = MaterialTheme.typography.labelMedium.fontSize),
        )
    }
}

@Composable
private fun ContactCardsSheet(contact: ContactUi, actions: RecipientActions) {
    NeoBottomSheet(onDismissRequest = actions::closeCards, title = "Tarjetas de ${contact.name.substringBefore(' ')}") {
        Column(Modifier.padding(horizontal = NeoSpace.xs)) {
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
            // Who on top, the amount in the middle of what is left, the paying card right over the keypad.
            Column(Modifier.weight(1f).padding(horizontal = NeoSpace.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
                RecipientHeader(state.recipient, avatar = 48.dp)
                Spacer(Modifier.weight(1f))
                AmountDisplay(state.input, state.currency)
                Box(Modifier.height(NeoSpace.xxxl), contentAlignment = Alignment.Center) {
                    if (state.overBalance) Text("Más que el saldo de esta tarjeta", style = MaterialTheme.typography.bodyMedium, color = Neo.colors.warning)
                }
                Spacer(Modifier.weight(1f))
                SourceCardPicker(state.sources, state.source, actions::source)
            }
            NeoKeypad(actions::key, Modifier.padding(horizontal = NeoSpace.m).padding(top = NeoSpace.m))
            BottomAction { NeoButton("Continuar", actions::next, enabled = state.canContinue) }
        }
    }
}

/** Who receives, centred over the amount: the contact with the card it goes to, or the bare card and its bank. */
@Composable
private fun RecipientHeader(recipient: RecipientUi, avatar: Dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val name = recipient.name
        if (name != null) NameAvatar(name, size = avatar) else IconAvatar(Ds.drawable.ic_credit_card, size = avatar)
        FittedText(
            name ?: formatCardNumber(recipient.card.number),
            MaterialTheme.typography.titleLarge,
            Modifier.padding(top = NeoSpace.m),
            textAlign = TextAlign.Center,
            minScale = 0.75f,
        )
        val detail = if (name != null) {
            listOfNotNull(formatCardNumber(recipient.card.number), recipient.card.bankName).joinToString(" · ")
        } else {
            recipient.card.bankName
        }
        detail?.let {
            Text(
                it, Modifier.padding(top = NeoSpace.xxs),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
            )
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

/** Last look before the fingerprint, as the receipt it will become: who and how much, torn from where it leaves. */
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
            lines = listOf(SummaryLine("Desde", formatCardNumber(state.source.number), state.source.bank.shortName)),
            confirm = "Transferir ${formatMoney(state.amount.amount, state.amount.currency)}",
            onConfirm = actions::confirm,
            modifier = Modifier.padding(padding),
            confirmIcon = Ds.drawable.ic_fingerprint,
            confirming = state.sending,
            lead = { RecipientHeader(state.recipient, avatar = 56.dp) },
            extra = {
                state.saveContact?.let { save -> SwitchRow("Guardar como contacto", save, actions::saveContact, Modifier.padding(top = NeoSpace.s)) }
            },
        )
    }
    state.duplicate?.let { DuplicateSheet(it, actions::confirmDuplicate, actions::cancelDuplicate) }
}

/** Same card, same destination, same amount, still waiting: ask, never block. */
@Composable
fun DuplicateSheet(duplicate: DuplicateUi, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val amount = "${formatAmount(duplicate.amount.amount).removeSuffix(".00")} ${duplicate.amount.currency}"
    val first = duplicate.recipient.substringBefore(' ')
    NeoConfirmSheet(
        title = "¿Enviar otros $amount?",
        text = "Ya enviaste $amount a $first ${timeLabels.ago(duplicate.sentAt).let { if (it == "ahora") "hace un momento" else it }} y aún no llegó la respuesta.",
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
 * After the bank's acknowledgement. Sent leaves the app free; the same mark turns into the confirmed check or the
 * rejection when the result arrives while it is still open. Mark and title keep their place in every state.
 */
@Composable
fun TransferResultScreen(state: TransferResultUi, actions: ResultActions, modifier: Modifier = Modifier) {
    val operation = state.operation
    Scaffold(modifier, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // Mark at the optical centre of the free space; it and the title never move whatever the state adds.
                val top = maxHeight * 0.26f
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(top))
                    StatusMark(operation.status.tone, operation.status.markIcon(operation.kind), positive = { ConfirmedMark(112.dp) })
                    Spacer(Modifier.height(NeoSpace.xxl))
                    AnimatedContent(
                        operation.status.label,
                        Modifier.fillMaxWidth(),
                        transitionSpec = { fadeIn(tween(NeoMotion.short, delayMillis = 90)) togetherWith fadeOut(tween(90)) },
                        contentAlignment = Alignment.Center,
                        label = "title",
                    ) { Text(it, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center) }
                    operation.amount?.let { money ->
                        Text(
                            "${formatMoney(money.amount, money.currency).unbroken()} a ${operation.title}",
                            Modifier.padding(top = NeoSpace.s),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                        )
                    }
                    val note = when (operation.status) {
                        OperationStatus.Sent -> null
                        OperationStatus.Rejected, OperationStatus.NotExecuted -> operation.reason
                        else -> operation.transactionId?.let { "Transacción $it" }
                    }
                    AnimatedVisibility(note != null, enter = fadeIn(tween(NeoMotion.medium)), exit = fadeOut(tween(NeoMotion.short))) {
                        Text(
                            note.orEmpty(),
                            Modifier.padding(top = NeoSpace.s),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (operation.status.tone == StatusTone.Danger) Neo.colors.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(NeoSpace.xxl))
                }
            }
            BottomAction {
                NeoButton("Volver al inicio", actions::done)
                NeoTextButton("Ver operación", actions::open, Modifier.fillMaxWidth())
            }
        }
    }
}
