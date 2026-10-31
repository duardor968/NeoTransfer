package dev.duardo.neotransfer.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.duardo.neotransfer.designsystem.R as Ds
import dev.duardo.neotransfer.designsystem.component.KeypadKey
import dev.duardo.neotransfer.designsystem.component.NeoButton
import dev.duardo.neotransfer.designsystem.component.NeoIconButton
import dev.duardo.neotransfer.designsystem.component.NeoTextButton
import dev.duardo.neotransfer.designsystem.component.NeoTextField
import dev.duardo.neotransfer.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.designsystem.component.PinDots
import dev.duardo.neotransfer.designsystem.component.SecureWindow
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoRadius
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.ui.common.BankCard
import dev.duardo.neotransfer.ui.common.CardNumberTransformation
import dev.duardo.neotransfer.ui.common.ExpiryTransformation
import dev.duardo.neotransfer.ui.common.RequestCopy
import dev.duardo.neotransfer.ui.common.RequestStatusScreen
import dev.duardo.neotransfer.ui.common.party
import dev.duardo.neotransfer.ui.common.words
import dev.duardo.neotransfer.ui.model.Bank
import dev.duardo.neotransfer.ui.model.BankRegistrationUi
import dev.duardo.neotransfer.ui.model.CardFieldUi
import dev.duardo.neotransfer.ui.model.CardUi
import dev.duardo.neotransfer.ui.model.ClassicSetupUi
import dev.duardo.neotransfer.ui.model.FieldInput
import dev.duardo.neotransfer.ui.model.FieldShape
import dev.duardo.neotransfer.ui.model.FieldUi
import dev.duardo.neotransfer.ui.model.KeyToSaveUi
import dev.duardo.neotransfer.ui.model.LineStateUi
import dev.duardo.neotransfer.ui.model.NewKeyUi
import dev.duardo.neotransfer.ui.model.RegistrationField
import dev.duardo.neotransfer.ui.model.issuerOf

// ---- Registering the line with a bank -----------------------------------------------------------------------

interface BankRegistrationActions {
    fun close()
    fun field(id: String, value: String)
    /** The card's number read with the camera, as every card field does. */
    fun scan()
    fun send()
    /** The key the bank sent is in hand: the fingerprint saves it. */
    fun saveKey()
    /** The key is not in hand any more: the person types it from the SMS. */
    fun typeKey()
    /** Back to the form, its values kept. */
    fun edit()
    fun done()
}

/**
 * Registering the line with a bank (`49`): the form that bank asks for, then its answer in place of the form. The key
 * comes by SMS and the fingerprint saves it ([saved] once it is); the card PIN of the same SMS is never read.
 */
@Composable
fun BankRegistrationScreen(
    state: BankRegistrationUi,
    actions: BankRegistrationActions,
    modifier: Modifier = Modifier,
    saved: Boolean = false,
    sending: Boolean = false,
) {
    val request = state.request
    if (request == null) {
        RegistrationForm(state, actions, sending, modifier)
        return
    }
    val name = state.bank.shortName
    val key = state.keyToSave
    val confirmed = request.state is LineStateUi.Confirmed
    val shown = registrationShown(request.state, key, saved)
    val copy = shown.words(
        sent = RequestCopy("Registrando en $name…", "La respuesta llega por SMS, con tu clave."),
        confirmed = RequestCopy(
            "Línea registrada",
            when {
                key == null -> "Tu clave de $name quedó guardada en este teléfono, protegida con tu huella."
                key.inHand -> "La clave llegó por SMS. Guárdala con tu huella."
                else -> "Escribe la clave que llegó por SMS."
            },
        ),
        refused = "$name no registró la línea",
        notExecuted = "No se registró la línea",
        name = "Registro en $name",
    )
    RequestStatusScreen(shown, Ds.drawable.ic_key, copy, state.bank.party, actions::close, modifier) {
        when {
            !confirmed -> RetryActions(request.state, actions::edit)
            key == null -> NeoButton("Continuar", actions::done)
            key.inHand -> NeoButton("Guardar la clave", actions::saveKey, icon = Ds.drawable.ic_fingerprint)
            else -> NeoButton("Escribir la clave", actions::typeKey)
        }
    }
}

/** A registration's state as shown: confirmed by the SMS a moment before its key is noted, it is still on its way. */
private fun registrationShown(state: LineStateUi, key: KeyToSaveUi?, saved: Boolean): LineStateUi =
    if (state is LineStateUi.Confirmed && key == null && !saved) LineStateUi.Sent else state

@Composable
private fun RegistrationForm(state: BankRegistrationUi, actions: BankRegistrationActions, sending: Boolean, modifier: Modifier) {
    FormScaffold("Registrar la línea en ${state.bank.shortName}", actions::close, modifier, bottom = {
        NeoButton("Registrar", actions::send, enabled = state.canSend, loading = sending, icon = Ds.drawable.ic_fingerprint)
    }) {
        val fields = state.fields
        var index = 0
        while (index < fields.size) {
            val field = fields[index]
            val next = fields.getOrNull(index + 1)
            // Both surnames share a row, as on the identity card.
            if (field.id == RegistrationField.FirstSurname.name && next?.id == RegistrationField.SecondSurname.name) {
                Row(horizontalArrangement = Arrangement.spacedBy(NeoSpace.m)) {
                    RegistrationInput(field, actions, last = false, Modifier.weight(1f))
                    RegistrationInput(next, actions, last = index + 1 == fields.lastIndex, Modifier.weight(1f))
                }
                index += 2
            } else {
                RegistrationInput(field, actions, last = index == fields.lastIndex)
                index++
            }
        }
    }
}

/** A field of the registration, shaped as its [RegistrationField] is; the card's has the camera. */
@Composable
private fun RegistrationInput(field: FieldUi, actions: BankRegistrationActions, last: Boolean, modifier: Modifier = Modifier) {
    val shape = RegistrationField.entries.firstOrNull { it.name == field.id }?.shape
    FormField(
        field.copy(shape = shape), { actions.field(field.id, it) }, modifier, last,
        onScan = if (shape == FieldShape.Card) actions::scan else null,
    )
}

/**
 * A [FieldUi] as a text field, the way every form of the line draws one: with its [FieldUi.shape] (a card grouped as
 * printed, a prefix shown and never typed, an expiry as printed) so a value it cannot take is never typed, and
 * the camera at its end when [onScan] reads it.
 */
@Composable
internal fun FormField(field: FieldUi, onChange: (String) -> Unit, modifier: Modifier = Modifier, last: Boolean = true, onScan: (() -> Unit)? = null) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val shown = remember(field.shape, dim) {
        when (val shape = field.shape) {
            FieldShape.Card -> CardNumberTransformation
            is FieldShape.Prefix -> prefixed(shape.digits, dim)
            FieldShape.Expiry -> ExpiryTransformation
            null -> VisualTransformation.None
        }
    }
    NeoTextField(
        field.value, onChange, field.label, modifier,
        keyboardType = when (field.input) {
            FieldInput.Number -> KeyboardType.Number
            FieldInput.Text -> KeyboardType.Text
            FieldInput.Phone -> KeyboardType.Phone
            FieldInput.Secret -> KeyboardType.NumberPassword
        },
        imeAction = if (last) ImeAction.Done else ImeAction.Next,
        error = field.error,
        suffix = field.suffix,
        trailing = onScan?.let { scan -> { NeoIconButton(Ds.drawable.ic_photo_camera, "Escanear", scan, tint = dim) } },
        visualTransformation = shown,
    )
}

/** [prefix], dimmed, before what is typed; the cursor never goes into it. */
private fun prefixed(prefix: String, dim: Color) = VisualTransformation { text ->
    TransformedText(
        AnnotatedString.Builder().apply {
            withStyle(SpanStyle(color = dim)) { append(prefix) }
            append(text)
        }.toAnnotatedString(),
        object : OffsetMapping {
            override fun originalToTransformed(offset: Int) = offset + prefix.length
            override fun transformedToOriginal(offset: Int) = (offset - prefix.length).coerceIn(0, text.length)
        },
    )
}

// ---- The Clásica ----------------------------------------------------------------------------------------------

interface ClassicNumberActions {
    fun back()
    fun number(value: String)
    fun scan()
    fun save()
}

/** «Tu Clásica», for a line already in MiTransfer: its number, with the card filling in as it is typed or scanned. */
@Composable
fun ClassicNumberScreen(state: ClassicSetupUi.Number, actions: ClassicNumberActions, modifier: Modifier = Modifier) {
    Scaffold(
        modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar(onBack = actions::back) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter),
                verticalArrangement = Arrangement.spacedBy(NeoSpace.l),
            ) {
                Text("Tu Clásica", style = MaterialTheme.typography.headlineMedium)
                BankCard(CardUi("draft", Bank.CLASSIC, state.number.number, "USD"), Modifier.padding(vertical = NeoSpace.s))
                ClassicNumberField(state.number, actions::number, last = true, onScan = actions::scan)
            }
            BottomAction { NeoButton("Añadir", actions::save, enabled = state.canSave) }
        }
    }
}

/** A Clásica's number as typed, with what it tells under it («Clásica · USD») or what is wrong with it; [onScan] reads it with the camera. */
@Composable
private fun ClassicNumberField(card: CardFieldUi, onChange: (String) -> Unit, last: Boolean, onScan: (() -> Unit)? = null) {
    NeoTextField(
        card.number, onChange, "Número de tarjeta",
        keyboardType = KeyboardType.Number,
        imeAction = if (last) ImeAction.Done else ImeAction.Next,
        error = card.error,
        supporting = issuerOf(card.bank, card.currency),
        trailing = onScan?.let { scan -> { NeoIconButton(Ds.drawable.ic_photo_camera, "Escanear", scan, tint = MaterialTheme.colorScheme.onSurfaceVariant) } },
        visualTransformation = CardNumberTransformation,
    )
}

interface ClassicRegisterActions {
    fun close()
    fun identity(value: String)
    /** A key of the keypad: it types the new PIN, then its repetition. */
    fun pinKey(key: KeypadKey)
    fun send()
    fun saveKey()
    fun typeKey()
    fun edit()
    fun done()
}

/**
 * Registering the line in MiTransfer (`49`): the identity card and a new PIN typed twice on the app's own keypad, which
 * gives way to the system keyboard while the identity card is typed. Then MiTransfer's answer in place of the form:
 * the PIN chosen is saved with the fingerprint ([saved] once it is). Hidden from screenshots.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ClassicRegisterScreen(
    state: ClassicSetupUi.Register,
    pin: NewKeyUi,
    actions: ClassicRegisterActions,
    modifier: Modifier = Modifier,
    saved: Boolean = false,
) {
    val request = state.request
    if (request != null) {
        val key = state.keyToSave
        val confirmed = request.state is LineStateUi.Confirmed
        val shown = registrationShown(request.state, key, saved)
        val copy = shown.words(
            sent = RequestCopy("Registrando en MiTransfer…", "La respuesta llega por SMS."),
            confirmed = RequestCopy(
                "Línea registrada",
                when {
                    key == null -> "Tu PIN de MiTransfer quedó guardado en este teléfono, protegido con tu huella."
                    key.inHand -> "Guarda tu PIN con la huella."
                    else -> "Escribe el PIN que elegiste."
                },
            ),
            refused = "MiTransfer no registró la línea",
            notExecuted = "No se registró la línea",
            name = "Registro en MiTransfer",
        )
        RequestStatusScreen(shown, Ds.drawable.ic_key, copy, Bank.CLASSIC.party, actions::close, modifier) {
            when {
                request.state is LineStateUi.Answered -> NeoButton("Continuar", actions::done)
                !confirmed -> RetryActions(request.state, actions::edit)
                key == null -> NeoButton("Continuar", actions::done)
                key.inHand -> NeoButton("Guardar el PIN", actions::saveKey, icon = Ds.drawable.ic_fingerprint)
                else -> NeoButton("Escribir el PIN", actions::typeKey)
            }
        }
        return
    }
    SecureWindow()
    val focus = LocalFocusManager.current
    val typing = WindowInsets.isImeVisible
    // One field marked at a time: the identity card while it has the cursor, otherwise the PIN the keypad types.
    var identityFocused by remember { mutableStateOf(false) }
    // The system keyboard put away (back, ✓) takes the cursor with it: the keypad types the PIN.
    LaunchedEffect(typing) { if (!typing) focus.clearFocus() }
    FormScaffold(
        "Registrar en MiTransfer", actions::close, modifier,
        keypad = {
            if (!typing) PinKeypad({ key ->
                focus.clearFocus()
                actions.pinKey(key)
            }, Modifier.padding(top = NeoSpace.s))
        },
        bottom = { NeoButton("Registrar", actions::send, enabled = state.canSend, loading = pin.sending, icon = Ds.drawable.ic_fingerprint) },
    ) {
        NeoTextField(
            state.identity.value, actions::identity, state.identity.label, Modifier.onFocusChanged { identityFocused = it.isFocused },
            keyboardType = KeyboardType.Number, imeAction = ImeAction.Done, error = state.identity.error,
        )
        val error = pin.error != null
        val pinActive = !identityFocused && !typing
        PinBox("PIN nuevo (${pin.length} dígitos)", pin.length, if (pin.repeat) pin.length else pin.entered, active = pinActive && !pin.repeat, error, focus::clearFocus)
        PinBox("Repite el PIN", pin.length, if (pin.repeat) pin.entered else 0, active = pinActive && pin.repeat, error, focus::clearFocus)
        pin.error?.let { Text(it, Modifier.padding(start = NeoSpace.xxs), style = MaterialTheme.typography.bodySmall, color = Neo.colors.danger) }
    }
}

/**
 * A PIN typed on the keypad below, framed like a text field: the label above, the dots inside. [active] takes the
 * next digit and is the only one marked, in red after an [error]; tapping it puts the system keyboard away so the
 * keypad shows.
 */
@Composable
private fun PinBox(label: String, length: Int, filled: Int, active: Boolean, error: Boolean, onClick: () -> Unit) {
    val colors = Neo.colors
    val shape = RoundedCornerShape(NeoRadius.field)
    val tone = when {
        !active -> null
        error -> colors.danger
        else -> colors.accentInk
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, Modifier.padding(start = NeoSpace.xxs), style = MaterialTheme.typography.labelMedium, color = tone ?: MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clip(shape)
                .background(colors.group)
                .border(if (tone != null) 2.dp else 1.dp, tone ?: colors.divider, shape)
                .clickable(role = Role.Button, onClickLabel = "Escribir en el teclado", onClick = onClick)
                .padding(horizontal = NeoSpace.l),
            contentAlignment = Alignment.CenterStart,
        ) { PinDots(length, filled, error = error && active) }
    }
}

interface ClassicAssociateActions {
    fun close()
    fun number(value: String)
    fun authorization(value: String)
    fun terminal(value: String)
    /** The card's number read with the camera, as «Tu Clásica» does. */
    fun scan()
    fun send()
    /** MiTransfer answered in its own words: the Clásica goes into the app anyway. */
    fun add()
    fun edit()
    fun done()
}

/**
 * Associating the Clásica with the line (`111`): its number, and the authorization and terminal numbers of a receipt
 * of a purchase with it, drawn under the fields. Then MiTransfer's answer in place of the form.
 */
@Composable
fun ClassicAssociateScreen(state: ClassicSetupUi.Associate, actions: ClassicAssociateActions, modifier: Modifier = Modifier, sending: Boolean = false) {
    val request = state.request
    if (request != null) {
        val copy = request.state.words(
            sent = RequestCopy("Asociando la Clásica…", "La respuesta llega por SMS."),
            confirmed = RequestCopy("Clásica asociada", null),
            refused = "MiTransfer no asoció la Clásica",
            notExecuted = "No se asoció la Clásica",
            name = "Asociación de la Clásica",
        )
        RequestStatusScreen(request.state, Ds.drawable.ic_credit_card, copy, Bank.CLASSIC.party, actions::close, modifier) {
            when (request.state) {
                is LineStateUi.Confirmed -> NeoButton("Continuar", actions::done)
                is LineStateUi.Answered -> {
                    NeoButton("Añadir la Clásica", actions::add)
                    NeoTextButton("Revisar los datos", actions::edit, Modifier.fillMaxWidth())
                }
                else -> RetryActions(request.state, actions::edit)
            }
        }
        return
    }
    FormScaffold("Asociar la Clásica", actions::close, modifier, bottom = {
        NeoButton("Asociar", actions::send, enabled = state.canSend, loading = sending, icon = Ds.drawable.ic_fingerprint)
    }) {
        ClassicNumberField(state.number, actions::number, last = false, onScan = actions::scan)
        Row(horizontalArrangement = Arrangement.spacedBy(NeoSpace.m)) {
            NeoTextField(
                state.authorization.value, actions::authorization, state.authorization.label, Modifier.weight(1f),
                keyboardType = KeyboardType.Ascii, error = state.authorization.error,
            )
            NeoTextField(
                state.terminal.value, actions::terminal, state.terminal.label, Modifier.weight(1f),
                keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, error = state.terminal.error,
            )
        }
        Text(
            "Están en el comprobante de una compra con esta tarjeta:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PosReceipt(state.number.number)
    }
}

/**
 * A POS receipt, drawn in the theme's colours: where its authorization (AUT) and terminal (TML) numbers are printed, for
 * the card typed in [number] (its last four digits once it is whole).
 */
@Composable
private fun PosReceipt(number: String, modifier: Modifier = Modifier) {
    val colors = Neo.colors
    val highlight = SpanStyle(background = colors.warningContainer, fontWeight = FontWeight.Bold)
    val last = if (number.length == 16) number.takeLast(4) else "••••"
    val text = buildAnnotatedString {
        appendLine("FINCIMEX · PAGO")
        appendLine("TARJETA ****$last")
        appendLine("IMPORTE 12.50 USD")
        append("AUT: ")
        withStyle(highlight) { append("482913") }
        append("   TML: ")
        withStyle(highlight) { append("40217385") }
        appendLine()
        append("17/10/2026 15:31")
    }
    val shape = RoundedCornerShape(10.dp)
    Text(
        text,
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.group)
            .border(1.dp, colors.divider, shape)
            .padding(horizontal = NeoSpace.l, vertical = NeoSpace.m)
            .clearAndSetSemantics { contentDescription = "Comprobante de compra: la autorización va tras AUT y la terminal tras TML" },
        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurface),
    )
}

// ---- Shared -----------------------------------------------------------------------------------------------------

/** A form of the line's services: closed with ✕, its fields scrolling, [keypad] (if any) and [bottom] fixed below. */
@Composable
private fun FormScaffold(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier,
    keypad: @Composable () -> Unit = {},
    bottom: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar(onBack = onClose, backIcon = Ds.drawable.ic_close, backDescription = "Cerrar") },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter).padding(bottom = NeoSpace.s),
                verticalArrangement = Arrangement.spacedBy(NeoSpace.l),
            ) {
                Text(title, style = MaterialTheme.typography.headlineMedium)
                content()
            }
            keypad()
            BottomAction(bottom)
        }
    }
}

/** After a refusal or a failed send the form comes back with its values; without an answer, ✕ closes. */
@Composable
private fun ColumnScope.RetryActions(state: LineStateUi, edit: () -> Unit) {
    when (state) {
        is LineStateUi.Rejected -> NeoButton("Revisar los datos", edit)
        is LineStateUi.NotExecuted -> NeoButton("Reintentar", edit, icon = Ds.drawable.ic_replay)
        else -> Unit
    }
}
