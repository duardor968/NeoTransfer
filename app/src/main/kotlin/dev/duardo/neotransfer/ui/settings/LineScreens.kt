package dev.duardo.neotransfer.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.R as Ds
import dev.duardo.neotransfer.designsystem.component.GroupDivider
import dev.duardo.neotransfer.designsystem.component.IconAvatar
import dev.duardo.neotransfer.designsystem.component.KeypadKey
import dev.duardo.neotransfer.designsystem.component.NeoBottomSheet
import dev.duardo.neotransfer.designsystem.component.NeoButton
import dev.duardo.neotransfer.designsystem.component.NeoButtonStyle
import dev.duardo.neotransfer.designsystem.component.NeoGroup
import dev.duardo.neotransfer.designsystem.component.NeoIconButton
import dev.duardo.neotransfer.designsystem.component.NeoRow
import dev.duardo.neotransfer.designsystem.component.NeoTextButton
import dev.duardo.neotransfer.designsystem.component.NeoTextField
import dev.duardo.neotransfer.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.designsystem.component.PinDots
import dev.duardo.neotransfer.designsystem.component.SecureWindow
import dev.duardo.neotransfer.designsystem.component.StatusTone
import dev.duardo.neotransfer.designsystem.component.content
import dev.duardo.neotransfer.designsystem.component.flushEnd
import dev.duardo.neotransfer.designsystem.component.flushStart
import dev.duardo.neotransfer.designsystem.component.skeleton
import dev.duardo.neotransfer.designsystem.component.spinning
import dev.duardo.neotransfer.designsystem.format.NoBreak
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.designsystem.format.unbroken
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.ui.common.AnswerCaption
import dev.duardo.neotransfer.ui.common.AnswerText
import dev.duardo.neotransfer.ui.common.BankCardThumb
import dev.duardo.neotransfer.ui.common.CardNumberTransformation
import dev.duardo.neotransfer.ui.common.RequestCopy
import dev.duardo.neotransfer.ui.common.RequestStatusScreen
import dev.duardo.neotransfer.ui.common.balanceLabel
import dev.duardo.neotransfer.ui.common.party
import dev.duardo.neotransfer.ui.common.words
import dev.duardo.neotransfer.ui.model.Bank
import dev.duardo.neotransfer.ui.model.BankLineUi
import dev.duardo.neotransfer.ui.model.CardAssociationUi
import dev.duardo.neotransfer.ui.model.KeyToSaveUi
import dev.duardo.neotransfer.ui.model.LineAccountUi
import dev.duardo.neotransfer.ui.model.LineRequestUi
import dev.duardo.neotransfer.ui.model.LineStateUi
import dev.duardo.neotransfer.ui.model.NewKeyUi
import dev.duardo.neotransfer.ui.model.RemovalUi
import dev.duardo.neotransfer.ui.model.issuerOf
import dev.duardo.neotransfer.ui.onboarding.BottomAction
import dev.duardo.neotransfer.ui.onboarding.FormField
import dev.duardo.neotransfer.ui.onboarding.PinKeypad

/**
 * The services of the line whose request has a screen of its own ([LineRequestScreen]); the accounts' answer is their
 * list. The Clásica's association with MiTransfer is its [Removal]; its last operations (`48`), MiTransfer's text, are
 * its [History].
 */
enum class LineService { KeyChange, Association, Removal, History }

/** What a service's row says about its last request instead of what the service is. */
@Immutable
data class LineNote(val text: String, val tone: StatusTone)

/**
 * What the row of a service says about its last request: only that it is on its way, and a row with a note opens it.
 * How it ended is on its own screen and in its notification.
 */
val LineRequestUi.rowNote: LineNote?
    get() = if (state == LineStateUi.Sent) LineNote("Esperando respuesta", StatusTone.Neutral) else null

/** A reason as it goes on after a colon: "Rechazada: esta tarjeta ya está asociada a un celular". */
private fun String.inSentence(): String = trim().trimEnd('.').let { if (it.length > 1 && it[1].isLowerCase()) it.replaceFirstChar(Char::lowercase) else it }

@DrawableRes
internal fun LineService.icon(bank: Bank): Int = when (this) {
    LineService.KeyChange -> Ds.drawable.ic_lock_reset
    LineService.Association -> Ds.drawable.ic_link_2
    LineService.Removal -> if (bank == Bank.CLASSIC) Ds.drawable.ic_credit_card_off else Ds.drawable.ic_person_remove
    LineService.History -> Ds.drawable.ic_receipt_long
}

/**
 * A row of «Tu línea»: what the service is, or what its last request is doing ([note]). Destructive ones in red, without
 * chevron. Its title is an action, which takes a second line rather than being cut.
 */
@Composable
internal fun LineRow(
    title: String,
    @DrawableRes icon: Int,
    description: String?,
    note: LineNote?,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    NeoRow(
        title,
        supporting = description.takeIf { note == null },
        supportingContent = note?.let { { NoteText(it) } },
        leading = { IconAvatar(icon, tint = if (danger) Neo.colors.danger else MaterialTheme.colorScheme.onSurface) },
        trailing = if (danger) null else ({ Chevron() }),
        onClick = onClick,
        titleColor = if (danger) Neo.colors.danger else Color.Unspecified,
        wrapTitle = true,
    )
}

@Composable
internal fun NoteText(note: LineNote) {
    val color = if (note.tone == StatusTone.Danger || note.tone == StatusTone.Warning) note.tone.content() else MaterialTheme.colorScheme.onSurfaceVariant
    Text(note.text, style = MaterialTheme.typography.bodyMedium, color = color)
}

// ---- Key change ---------------------------------------------------------------------------------------------

interface NewKeyActions {
    fun key(key: KeypadKey)
    fun close()
}

/** The new key of a change (`69`), typed twice on the app's keypad; what it is said once, before the first. Hidden from screenshots. */
@Composable
fun NewKeyScreen(state: NewKeyUi, actions: NewKeyActions, modifier: Modifier = Modifier) {
    SecureWindow()
    Scaffold(
        modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar(onBack = actions::close, backIcon = Ds.drawable.ic_close, backDescription = "Cerrar") },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = NeoSpace.gutter), verticalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
                val name = state.bank.shortName
                Text(if (state.repeat) "Repite la clave nueva de $name" else "Nueva clave de $name", style = MaterialTheme.typography.headlineMedium)
                if (!state.repeat) Text(
                    "${state.length} dígitos. El banco la confirma por SMS y queda guardada aquí.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.weight(1f))
            PinDots(state.length, state.entered, error = state.error != null)
            Box(Modifier.height(56.dp).padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.l), contentAlignment = Alignment.TopCenter) {
                when {
                    state.sending -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Neo.colors.accentInk)
                    state.error != null -> Text(state.error, style = MaterialTheme.typography.bodyMedium, color = Neo.colors.danger)
                }
            }
            Spacer(Modifier.weight(1f))
            PinKeypad(actions::key, Modifier.padding(bottom = NeoSpace.l))
        }
    }
}

// ---- Accounts -----------------------------------------------------------------------------------------------

interface LineAccountsActions {
    fun back()
    fun refresh()
    fun retry()
    /** Adds the account's card, by its whole number, to the wallet. */
    fun add(number: String)
}

/**
 * What the bank listed under the line (`58`), each account with its balance and whether the app has it; the missing ones
 * are added here. After a send that failed, its «Reintentar» is the only way to ask again.
 */
@Composable
fun LineAccountsScreen(line: BankLineUi, actions: LineAccountsActions, modifier: Modifier = Modifier) {
    val request = line.accounts
    val sending = request?.state == LineStateUi.Sent
    Scaffold(
        modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            NeoTopBar(
                "Cuentas en el banco",
                onBack = actions::back,
                actions = {
                    if (request?.state !is LineStateUi.NotExecuted) {
                        NeoIconButton(Ds.drawable.ic_refresh, "Consultar otra vez", actions::refresh, Modifier.spinning(sending))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter).padding(bottom = NeoSpace.xxl),
            verticalArrangement = Arrangement.spacedBy(NeoSpace.l),
        ) {
            request?.let { AccountsAnswer(line.bank, it, actions::retry) }
            when {
                line.accountList.isNotEmpty() -> NeoGroup {
                    line.accountList.forEachIndexed { index, account ->
                        if (index > 0) GroupDivider()
                        AccountRow(line.bank, account, actions::add)
                    }
                }
                sending -> AccountsSkeleton()
            }
        }
    }
}

/** Where the last `58` stands: when the bank answered, or why it did not. */
@Composable
private fun AccountsAnswer(bank: Bank, request: LineRequestUi, retry: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.bodyMedium
    when (val state = request.state) {
        LineStateUi.Sent -> Text("Esperando la respuesta de ${bank.shortName}…", style = style, color = muted)
        is LineStateUi.Confirmed -> AnswerCaption(bank.shortName, state.at)
        is LineStateUi.Rejected -> Text("Rechazada: ${state.reason.inSentence()}", style = style, color = Neo.colors.danger)
        is LineStateUi.NotExecuted -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("No se ejecutó: ${state.reason.inSentence()}", Modifier.weight(1f), style = style, color = StatusTone.Warning.content())
            NeoTextButton("Reintentar", retry, Modifier.flushEnd())
        }
        LineStateUi.NoResponse -> Text("Sin respuesta de ${bank.shortName}", style = style, color = muted)
        is LineStateUi.Answered -> AnswerText(bank.shortName, state)
    }
}

/** An account as the bank listed it: «Saldo 12 480.50 CUP · En la app». A card by its art; an account without a whole card number, by an icon. */
@Composable
private fun AccountRow(bank: Bank, account: LineAccountUi, add: (String) -> Unit) {
    val number = account.card?.number ?: account.addable
    val title = number?.let(::formatCardNumber) ?: account.account?.let(::masked) ?: account.label ?: "Cuenta"
    // NeoRow puts the trailing element under the text from 1.3× font: there «Añadir» lines up with the text.
    val below = LocalDensity.current.fontScale >= 1.3f
    NeoRow(
        title,
        supporting = listOfNotNull(
            account.label.takeIf { it != title }, balanceLabel(account.balance), "En la app".unbroken().takeIf { account.card != null },
        ).joinToString(" · "),
        leading = { if (number != null) BankCardThumb(bank) else IconAvatar(Ds.drawable.ic_account_balance_wallet) },
        trailing = account.addable?.let { addable -> { NeoTextButton("Añadir", { add(addable) }, if (below) Modifier.flushStart() else Modifier.flushEnd()) } },
        trailingBelowWhenLarge = true,
    )
}

/** The first answer on its way: the rows it will fill. */
@Composable
private fun AccountsSkeleton() {
    NeoGroup {
        repeat(2) { index ->
            if (index > 0) GroupDivider()
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = NeoSpace.l, vertical = NeoSpace.m),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NeoSpace.m),
            ) {
                Box(Modifier.width(44.dp).height(28.dp).skeleton())
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth(0.6f).height(14.dp).skeleton())
                    Box(Modifier.fillMaxWidth(0.4f).height(12.dp).skeleton())
                }
            }
        }
    }
}

/** A card number as the bank masked it, grouped like a whole one: `9204 06•• •••• 0001`. */
private fun masked(account: String): String = account.replace(Regex("[Xx*]"), "•").chunked(4).joinToString(NoBreak.toString())

// ---- Card association ---------------------------------------------------------------------------------------

interface CardAssociationActions {
    fun close()
    fun number(value: String)
    fun scan()
    /** The value of the coordinates card's cell, Metropolitano only. */
    fun value(value: String)
    fun send()
}

/**
 * The card the line operates with (`60`). Metropolitano also asks the value of one cell of its coordinates card,
 * typed for this send only and hidden from screenshots.
 */
@Composable
fun CardAssociationScreen(state: CardAssociationUi, actions: CardAssociationActions, modifier: Modifier = Modifier, sending: Boolean = false) {
    if (state.cell != null) SecureWindow()
    Scaffold(
        modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar(onBack = actions::close, backIcon = Ds.drawable.ic_close, backDescription = "Cerrar") },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter),
                verticalArrangement = Arrangement.spacedBy(NeoSpace.l),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
                    Text("Asociar una tarjeta a la línea", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "La línea pasa a operar con esta tarjeta en ${state.bank.shortName}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                NeoTextField(
                    state.card.number, actions::number, "Número de tarjeta",
                    keyboardType = KeyboardType.Number,
                    imeAction = if (state.cell != null) ImeAction.Next else ImeAction.Done,
                    error = state.card.error,
                    supporting = issuerOf(state.card.bank, state.card.currency).orEmpty(),
                    visualTransformation = CardNumberTransformation,
                    trailing = { NeoIconButton(Ds.drawable.ic_photo_camera, "Escanear", actions::scan, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
                state.cell?.let { cell ->
                    NeoTextField(
                        state.value, actions::value, "Valor de la celda $cell",
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        supporting = "De tu tarjeta de coordenadas",
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            }
            BottomAction { NeoButton("Asociar", actions::send, enabled = state.canSend, loading = sending) }
        }
    }
}

// ---- Removing the registration ------------------------------------------------------------------------------

/**
 * Confirmation of `68`, stating what it does: the line's registration with a bank, or the Clásica's association with
 * MiTransfer, with what each asks ([RemovalUi.fields]).
 */
@Composable
internal fun RemovalSheet(state: RemovalUi, sending: Boolean, onField: (String, String) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val classic = state.bank == Bank.CLASSIC
    val name = state.bank.shortName
    NeoBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = NeoSpace.gutter)) {
            Text(if (classic) "¿Quitar la asociación de la Clásica?" else "¿Eliminar tu registro en $name?", style = MaterialTheme.typography.headlineSmall)
            Text(
                if (classic) "MiTransfer deja de operar esta tarjeta desde tu línea hasta que la asocies otra vez. La tarjeta y su actividad se quedan en la app."
                else "Esta línea deja de operar con $name hasta que la registres otra vez. Tus tarjetas y tu actividad se quedan en la app.",
                Modifier.padding(top = NeoSpace.s),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.fields.isNotEmpty()) Column(Modifier.padding(top = NeoSpace.xl), verticalArrangement = Arrangement.spacedBy(NeoSpace.m)) {
                state.fields.forEachIndexed { index, field ->
                    FormField(field, { onField(field.id, it) }, last = index == state.fields.lastIndex)
                }
            }
            NeoButton(
                if (classic) "Quitar asociación" else "Eliminar registro", onConfirm, Modifier.padding(top = NeoSpace.xxl),
                style = NeoButtonStyle.Danger, enabled = state.canSend, loading = sending,
            )
            NeoTextButton("Cancelar", onDismiss, Modifier.fillMaxWidth().padding(top = NeoSpace.xs), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Where a request stands ---------------------------------------------------------------------------------

interface LineRequestActions {
    /** ✕: back to the screen under this one. */
    fun close()
    /** The closing button, named by where it goes ([LineRequestScreen]'s `back`). */
    fun back()
    /** «Reintentar», after a send that did not go out. */
    fun retry()
    /** After a refusal: another key, or the form again with what was sent. */
    fun fix()
    /** The key the bank took: with the fingerprint when the app has it, typed otherwise; unlocking first when it must. */
    fun saveKey()
    /** The person read the bank's answer and the key did not change. */
    fun discardKey()
}

/**
 * The last request of a service of the line and where it stands, live: sent, confirmed, refused with the bank's reason,
 * not executed, without an answer, or answered in the bank's or MiTransfer's own words. A key the bank took
 * ([keyToSave]) is saved here; when the answer is in words the app does not know, the person reads it and decides.
 * A refusal offers what corrects it, if anything does; a send that did not go out, «Reintentar»; the rest, [back]
 * («Volver a BPA»). [card]: the card an association was sent with.
 */
@Composable
fun LineRequestScreen(
    bank: Bank,
    service: LineService,
    request: LineRequestUi,
    keyToSave: KeyToSaveUi?,
    back: String,
    actions: LineRequestActions,
    modifier: Modifier = Modifier,
    card: String? = null,
) {
    val state = request.state
    val fix = fixLabel(bank, service).takeIf { state is LineStateUi.Rejected }
    RequestStatusScreen(state, service.icon(bank), state.lineCopy(bank, service, keyToSave, card), bank.party, actions::close, modifier) {
        when {
            keyToSave != null && (state is LineStateUi.Confirmed || state is LineStateUi.Answered) -> {
                if (keyToSave.inHand) NeoButton("Guardar la clave nueva", actions::saveKey, icon = Ds.drawable.ic_fingerprint)
                else NeoButton("Escribir la clave nueva", actions::saveKey)
                if (state is LineStateUi.Answered) NeoTextButton("Descartar", actions::discardKey, Modifier.fillMaxWidth())
            }
            fix != null -> NeoButton(fix, actions::fix)
            state is LineStateUi.NotExecuted -> NeoButton("Reintentar", actions::retry, icon = Ds.drawable.ic_replay)
            else -> NeoButton(back, actions::back)
        }
    }
}

/** What corrects a refusal: another key, or the data sent; none when the request asks for none (BPA's and BANDEC's `68`, a query), which closes like the rest. */
private fun fixLabel(bank: Bank, service: LineService): String? = when (service) {
    LineService.KeyChange -> "Elegir otra clave"
    LineService.Association -> "Revisar los datos"
    LineService.Removal -> "Revisar los datos".takeIf { bank == Bank.BANMET || bank == Bank.CLASSIC }
    LineService.History -> null
}

private fun LineStateUi.lineCopy(bank: Bank, service: LineService, keyToSave: KeyToSaveUi?, card: String?): RequestCopy {
    val name = bank.shortName
    val classic = bank == Bank.CLASSIC
    val history = "Últimas operaciones de la Clásica"
    val sent = RequestCopy(
        when (service) {
            LineService.KeyChange -> "Cambiando la clave en $name…"
            LineService.Association -> "Asociando la tarjeta en $name…"
            LineService.Removal -> if (classic) "Quitando la asociación de la Clásica…" else "Eliminando el registro en $name…"
            LineService.History -> history
        },
        "La respuesta llega por SMS.",
    )
    val confirmed = when (service) {
        LineService.KeyChange -> when {
            keyToSave == null -> RequestCopy("Clave cambiada", "La clave nueva quedó guardada en este teléfono, protegida con tu huella.")
            keyToSave.inHand -> RequestCopy("$name cambió tu clave", "Guárdala en este teléfono para seguir operando.")
            else -> RequestCopy("$name cambió tu clave", "Escríbela para guardarla en este teléfono.")
        }
        LineService.Association -> RequestCopy("Tarjeta asociada", "Tu línea opera ahora con ${card?.let(::formatCardNumber) ?: "esa tarjeta"} en $name.")
        LineService.Removal ->
            if (classic) RequestCopy("Asociación quitada", "MiTransfer ya no opera esta Clásica desde tu línea. La tarjeta sigue en la app.")
            else RequestCopy("Registro eliminado", "Esta línea ya no opera con $name. Tus tarjetas y tu actividad siguen en la app.")
        LineService.History -> RequestCopy(history, null)
    }
    val refused = when (service) {
        LineService.KeyChange -> "$name no cambió la clave"
        LineService.Association -> "$name no asoció la tarjeta"
        LineService.Removal -> if (classic) "MiTransfer no quitó la asociación" else "$name no eliminó el registro"
        LineService.History -> "MiTransfer no envió las últimas operaciones"
    }
    val notExecuted = when (service) {
        LineService.KeyChange -> "No se cambió la clave"
        LineService.Association -> "No se asoció la tarjeta"
        LineService.Removal -> if (classic) "No se quitó la asociación" else "No se eliminó el registro"
        LineService.History -> "No se pidieron las últimas operaciones"
    }
    val requestName = when (service) {
        LineService.KeyChange -> "Cambio de clave en $name"
        LineService.Association -> "Asociación de tarjeta en $name"
        LineService.Removal -> if (classic) "Quitar la asociación de la Clásica" else "Eliminar el registro en $name"
        LineService.History -> history
    }
    return words(sent, confirmed, refused, notExecuted, requestName)
}
