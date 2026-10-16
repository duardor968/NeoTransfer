package dev.duardo.neotransfer.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.R as Ds
import dev.duardo.neotransfer.designsystem.component.GroupDivider
import dev.duardo.neotransfer.designsystem.component.IconAvatar
import dev.duardo.neotransfer.designsystem.component.KeypadKey
import dev.duardo.neotransfer.designsystem.component.NeoButton
import dev.duardo.neotransfer.designsystem.component.NeoButtonStyle
import dev.duardo.neotransfer.designsystem.component.NeoGroup
import dev.duardo.neotransfer.designsystem.component.NeoIconButton
import dev.duardo.neotransfer.designsystem.component.NeoKeypad
import dev.duardo.neotransfer.designsystem.component.NeoRow
import dev.duardo.neotransfer.designsystem.component.NeoSegmented
import dev.duardo.neotransfer.designsystem.component.NeoTextField
import dev.duardo.neotransfer.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.designsystem.component.PinDots
import dev.duardo.neotransfer.designsystem.component.SecureWindow
import dev.duardo.neotransfer.designsystem.component.StatusChip
import dev.duardo.neotransfer.designsystem.component.StatusTone
import dev.duardo.neotransfer.designsystem.component.flushEnd
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.designsystem.format.unbroken
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.ui.common.BankCard
import dev.duardo.neotransfer.ui.common.BankLogoTile
import dev.duardo.neotransfer.ui.common.BankCardThumb
import dev.duardo.neotransfer.ui.common.CardNumberTransformation
import dev.duardo.neotransfer.ui.common.ExpiryTransformation
import dev.duardo.neotransfer.ui.lock.BrandBackdrop
import dev.duardo.neotransfer.ui.lock.BrandBlock
import dev.duardo.neotransfer.ui.model.Bank
import dev.duardo.neotransfer.ui.model.BankChoiceUi
import dev.duardo.neotransfer.ui.model.BankKeyUi
import dev.duardo.neotransfer.ui.model.CardSetupUi
import dev.duardo.neotransfer.ui.model.CardUi
import dev.duardo.neotransfer.ui.model.PermissionKind
import dev.duardo.neotransfer.ui.model.PermissionsUi

// ---- Permissions --------------------------------------------------------------------------------------------

interface PermissionsActions {
    fun grant()
    fun skip()
}

private val PermissionKind.label: String
    get() = when (this) {
        PermissionKind.Sms -> "SMS"
        PermissionKind.Phone -> "Teléfono"
        PermissionKind.Contacts -> "Contactos"
        PermissionKind.Camera -> "Cámara"
        PermissionKind.Notifications -> "Notificaciones"
    }

private val PermissionKind.purpose: String
    get() = when (this) {
        PermissionKind.Sms -> "Resultados y saldo de tus operaciones"
        PermissionKind.Phone -> "Enviar operaciones al banco"
        PermissionKind.Contacts -> "Nombres de tu agenda en contactos y actividad"
        PermissionKind.Camera -> "Escanear códigos QR y tarjetas"
        PermissionKind.Notifications -> "Avisos de abonos y resultados"
    }

private val PermissionKind.icon: Int
    get() = when (this) {
        PermissionKind.Sms -> Ds.drawable.ic_sms
        PermissionKind.Phone -> Ds.drawable.ic_call
        PermissionKind.Contacts -> Ds.drawable.ic_contacts
        PermissionKind.Camera -> Ds.drawable.ic_photo_camera
        PermissionKind.Notifications -> Ds.drawable.ic_notifications
    }

private val PermissionKind.essential: Boolean get() = this == PermissionKind.Sms || this == PermissionKind.Phone

/**
 * First launch. While [PermissionsUi.denied] is empty the system dialogs are on screen and only the brand shows
 * behind them; afterwards, what is missing and why, with one action.
 */
@Composable
fun PermissionsScreen(state: PermissionsUi, actions: PermissionsActions, modifier: Modifier = Modifier) {
    if (state.denied.isEmpty()) {
        BrandBackdrop(modifier) {
            Spacer(Modifier.weight(0.8f))
            BrandBlock(locked = false)
            Spacer(Modifier.weight(1.2f))
        }
        return
    }
    Scaffold(modifier, containerColor = MaterialTheme.colorScheme.background, topBar = { NeoTopBar() }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = NeoSpace.gutter),
        ) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text("Faltan permisos", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(NeoSpace.xxl))
                NeoGroup {
                    state.denied.forEachIndexed { index, kind ->
                        if (index > 0) GroupDivider()
                        NeoRow(
                            kind.label,
                            supporting = kind.purpose,
                            leading = { IconAvatar(kind.icon) },
                            trailing = if (kind.essential) ({ StatusChip("Necesario", StatusTone.Warning, null) }) else null,
                        )
                    }
                }
            }
            Column(Modifier.padding(vertical = NeoSpace.l), verticalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
                NeoButton("Permitir", actions::grant)
                if (state.denied.none { it.essential }) NeoButton("Continuar sin ellos", actions::skip, style = NeoButtonStyle.Tonal)
            }
        }
    }
}

// ---- Bank choice --------------------------------------------------------------------------------------------

interface BankChoiceActions {
    fun choose(bank: Bank)
    fun back()
}

/** The two banks by their logos. */
@Composable
fun BankChoiceScreen(state: BankChoiceUi, actions: BankChoiceActions, modifier: Modifier = Modifier, canGoBack: Boolean = false) {
    Scaffold(
        modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar(onBack = if (canGoBack) actions::back else null) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter),
            verticalArrangement = Arrangement.spacedBy(NeoSpace.m),
        ) {
            Text("Elige tu banco", Modifier.padding(bottom = NeoSpace.m), style = MaterialTheme.typography.headlineMedium)
            Bank.entries.forEach { bank -> BankLogoTile(bank, configured = bank in state.configured, onClick = { actions.choose(bank) }) }
            Spacer(Modifier.height(NeoSpace.l))
        }
    }
}

// ---- Bank key -----------------------------------------------------------------------------------------------

interface BankKeyActions {
    fun key(key: KeypadKey)
    fun back()
}

/** The bank's key, typed on our own keypad. Hidden from screenshots. */
@Composable
fun BankKeyScreen(state: BankKeyUi, actions: BankKeyActions, modifier: Modifier = Modifier) {
    SecureWindow()
    Scaffold(
        modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar(onBack = actions::back) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = NeoSpace.gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Text("Clave de ${state.bank.shortName}", style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(Modifier.weight(1f))
            PinDots(state.length, state.entered, error = state.error != null)
            Box(Modifier.height(56.dp).padding(top = NeoSpace.l), contentAlignment = Alignment.TopCenter) {
                when {
                    state.checking -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Neo.colors.accentInk)
                    state.error != null -> Text(state.error, style = MaterialTheme.typography.bodyMedium, color = Neo.colors.danger)
                }
            }
            Spacer(Modifier.weight(1f))
            NeoKeypad(actions::key, Modifier.padding(bottom = NeoSpace.l), decimal = false)
        }
    }
}

// ---- Cards of a bank ----------------------------------------------------------------------------------------

interface CardSetupActions {
    fun number(value: String)
    fun holder(value: String)
    fun expiry(value: String)
    fun currency(value: String)
    fun scan()
    fun save()
    fun addAnother()
    fun remove(cardId: String)
    fun finish()
    fun back()
}

val CardCurrencies = listOf("CUP", "USD")

/**
 * Cards held in a bank. The real card fills in as the number is typed or scanned; holder and expiry are optional
 * and print only when given.
 */
@Composable
fun CardSetupScreen(state: CardSetupUi, actions: CardSetupActions, modifier: Modifier = Modifier) {
    val form = state.form
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
                Text("Tarjetas de ${state.bank.shortName}", style = MaterialTheme.typography.headlineMedium)
                if (form != null) {
                    BankCard(
                        CardUi("draft", state.bank, form.number, form.currency, form.holder, form.expiry.takeIf { it.length == 4 }?.let { it.take(2) + "/" + it.drop(2) }),
                        Modifier.padding(vertical = NeoSpace.s),
                    )
                    NeoTextField(
                        form.number, actions::number, "Número de tarjeta",
                        keyboardType = KeyboardType.Number,
                        error = form.numberError,
                        visualTransformation = CardNumberTransformation,
                        trailing = { NeoIconButton(Ds.drawable.ic_photo_camera, "Escanear tarjeta", actions::scan) },
                    )
                    NeoTextField(form.holder, actions::holder, "Titular (opcional)")
                    NeoTextField(
                        form.expiry, actions::expiry, "Vence (opcional)",
                        keyboardType = KeyboardType.Number, imeAction = ImeAction.Done, error = form.expiryError,
                        visualTransformation = ExpiryTransformation,
                    )
                    NeoSegmented(CardCurrencies, form.currency, { it }, actions::currency)
                } else {
                    NeoGroup {
                        state.cards.forEach { card ->
                            NeoRow(
                                formatCardNumber(card.number),
                                supporting = listOfNotNull(card.currency, card.holder, card.expiry?.let { "Vence $it".unbroken() }).joinToString(" · "),
                                leading = { BankCardThumb(card.bank) },
                                trailing = { NeoIconButton(Ds.drawable.ic_delete, "Quitar tarjeta", { actions.remove(card.id) }, Modifier.flushEnd()) },
                            )
                            GroupDivider()
                        }
                        NeoRow(
                            "Añadir otra tarjeta",
                            leading = { IconAvatar(Ds.drawable.ic_add_card) },
                            onClick = actions::addAnother,
                        )
                    }
                }
            }
            BottomAction {
                if (form != null) NeoButton("Guardar tarjeta", actions::save, enabled = form.canSave)
                else NeoButton("Continuar", actions::finish, enabled = state.cards.isNotEmpty())
            }
        }
    }
}

@Composable
internal fun BottomAction(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = NeoSpace.gutter, vertical = NeoSpace.l),
        verticalArrangement = Arrangement.spacedBy(NeoSpace.s),
        content = content,
    )
}
