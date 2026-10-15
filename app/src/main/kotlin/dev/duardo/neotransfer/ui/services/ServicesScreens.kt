package dev.duardo.neotransfer.ui.services

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.R as Ds
import dev.duardo.neotransfer.designsystem.component.GroupDivider
import dev.duardo.neotransfer.designsystem.component.IconAvatar
import dev.duardo.neotransfer.designsystem.component.MoneyText
import dev.duardo.neotransfer.designsystem.component.NeoAmountField
import dev.duardo.neotransfer.designsystem.component.NeoButton
import dev.duardo.neotransfer.designsystem.component.NeoChip
import dev.duardo.neotransfer.designsystem.component.NeoGroup
import dev.duardo.neotransfer.designsystem.component.NeoIconButton
import dev.duardo.neotransfer.designsystem.component.NeoRow
import dev.duardo.neotransfer.designsystem.component.NeoTextField
import dev.duardo.neotransfer.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.designsystem.component.QuickActionGrid
import dev.duardo.neotransfer.designsystem.component.QuickActionItem
import dev.duardo.neotransfer.designsystem.component.SectionHeader
import dev.duardo.neotransfer.designsystem.component.SecureWindow
import dev.duardo.neotransfer.designsystem.component.TabHeader
import dev.duardo.neotransfer.designsystem.component.rim
import dev.duardo.neotransfer.designsystem.format.formatMoney
import dev.duardo.neotransfer.designsystem.format.unbroken
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoRadius
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.ui.common.PhoneTransformation
import dev.duardo.neotransfer.ui.common.SourceCardPicker
import dev.duardo.neotransfer.ui.common.SwitchRow
import dev.duardo.neotransfer.ui.common.icon
import dev.duardo.neotransfer.ui.model.CubacelQuery
import dev.duardo.neotransfer.ui.model.CubacelUi
import dev.duardo.neotransfer.ui.model.FieldInput
import dev.duardo.neotransfer.ui.model.SavedReferenceUi
import dev.duardo.neotransfer.ui.model.ServiceFormUi
import dev.duardo.neotransfer.ui.model.ServiceKind
import dev.duardo.neotransfer.ui.model.ServicesUi
import dev.duardo.neotransfer.ui.onboarding.BottomAction
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---- Catalogue ----------------------------------------------------------------------------------------------

interface ServicesActions {
    fun open(kind: ServiceKind)
    fun pay(referenceId: String)
}

/**
 * What can be paid, by utility. Saved references come first, one tap from paying the usual bill; under them every
 * service as a shortcut, in utility order: electricity, water, landline, internet, mobile.
 */
@Composable
fun ServicesScreen(state: ServicesUi, actions: ServicesActions, modifier: Modifier = Modifier) {
    val saved = state.references.sortedBy { it.kind.ordinal }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = NeoSpace.xxl)) {
        item(key = "title") { TabHeader("Servicios") }
        if (saved.isNotEmpty()) item(key = "saved") {
            Column(Modifier.padding(horizontal = NeoSpace.gutter)) {
                SectionHeader("Guardados")
                Column(verticalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
                    saved.chunked(2).forEach { pair ->
                        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
                            pair.forEach { reference -> SavedTile(reference, Modifier.weight(1f).fillMaxHeight()) { actions.pay(reference.id) } }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        item(key = "services") {
            Column(Modifier.padding(horizontal = NeoSpace.gutter).padding(top = if (saved.isEmpty()) NeoSpace.s else NeoSpace.xxl)) {
                if (saved.isNotEmpty()) SectionHeader("Pagar un servicio")
                QuickActionGrid(ServiceKind.entries.map { kind -> QuickActionItem(kind.icon, kind.shortcut, { actions.open(kind) }) })
            }
        }
    }
}

/** Name of a service as a shortcut: the action when the utility's name would not say it. */
private val ServiceKind.shortcut: String
    get() = if (this == ServiceKind.MobileTopUp) "Recargar móvil" else label

/** A saved reference as the bill it pays: what, whose, and what went last time. */
@Composable
private fun SavedTile(reference: SavedReferenceUi, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(NeoRadius.group)
    Column(
        modifier
            .heightIn(min = 112.dp)
            .clip(shape)
            .background(Neo.colors.group)
            .rim(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(NeoSpace.l),
    ) {
        IconAvatar(reference.kind.icon, size = 40.dp)
        Spacer(Modifier.height(NeoSpace.m).weight(1f, fill = false))
        Text(reference.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        // A Nauta account under a Nauta tile needs no domain; references are identifiers and never wrap.
        reference.value.takeIf { it.isNotEmpty() }?.let {
            Text(
                it.removeSuffix("@nauta.com.cu"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        // The amount never splits; when the line is short it goes under its label.
        reference.lastPaid?.let {
            Text(
                "Último pago ${formatMoney(it.amount, it.currency).unbroken()}", Modifier.padding(top = NeoSpace.xxs),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
            )
        }
    }
}

// ---- Pay a service ------------------------------------------------------------------------------------------

interface ServiceFormActions {
    fun back()
    fun choice(index: Int)
    fun reference(id: String?)
    fun field(id: String, value: String)
    fun scan(fieldId: String)
    fun amount(value: String)
    fun source(index: Int)
    fun saveReference(value: Boolean)
    fun submit()
}

/** One form for every service: saved references on top, the fields the bank asks for, the amount and the card. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ServiceFormScreen(state: ServiceFormUi, actions: ServiceFormActions, modifier: Modifier = Modifier) {
    if (state.fields.any { it.input == FieldInput.Secret }) SecureWindow()
    Scaffold(
        modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar(state.title, onBack = actions::back) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.s, bottom = NeoSpace.l),
                verticalArrangement = Arrangement.spacedBy(NeoSpace.l),
            ) {
                if (state.references.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
                        NeoChip("Nueva", state.selectedReference == null, { actions.reference(null) })
                        state.references.forEach { reference ->
                            NeoChip(reference.name, state.selectedReference == reference.id, { actions.reference(reference.id) })
                        }
                    }
                }
                if (state.choices.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
                    state.choices.forEachIndexed { index, choice -> NeoChip(choice, index == state.choice, { actions.choice(index) }) }
                }
                state.fields.forEach { field ->
                    NeoTextField(
                        field.value, { actions.field(field.id, it) }, field.label,
                        keyboardType = when (field.input) {
                            FieldInput.Number -> KeyboardType.Number
                            FieldInput.Phone -> KeyboardType.Phone
                            FieldInput.Secret -> KeyboardType.NumberPassword
                            FieldInput.Text -> KeyboardType.Text
                        },
                        visualTransformation = when (field.input) {
                            FieldInput.Secret -> PasswordVisualTransformation()
                            FieldInput.Phone -> PhoneTransformation
                            else -> VisualTransformation.None
                        },
                        error = field.error,
                        suffix = field.suffix,
                        trailing = if (field.scannable) ({ NeoIconButton(Ds.drawable.ic_photo_camera, "Escanear móvil", { actions.scan(field.id) }) }) else null,
                    )
                }
                if (state.amount != null) NeoAmountField(state.amount, actions::amount, state.currency) else WholeBill()
                if (state.cards.isNotEmpty()) SourceCardPicker(state.cards, state.source, actions::source, label = "Desde")
                state.saveReference?.let { save -> SwitchRow("Guardar referencia", save, actions::saveReference) }
            }
            BottomAction {
                NeoButton(state.action, actions::submit, enabled = state.canContinue, icon = if (state.cards.isNotEmpty()) Ds.drawable.ic_fingerprint else null)
            }
        }
    }
}

/**
 * Where the amount would be typed, for bills the bank charges whole: there is nothing to ask before paying, and the
 * receipt says how much went. Label and value read like a field's, without a box that suggests typing.
 */
@Composable
private fun WholeBill() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Importe", Modifier.padding(start = NeoSpace.xxs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Factura completa", Modifier.padding(start = NeoSpace.xxs), style = MaterialTheme.typography.titleMedium)
    }
}

// ---- Cubacel ------------------------------------------------------------------------------------------------

interface CubacelActions {
    fun back()
    fun query(kind: CubacelQuery)
    fun plans()
    fun transferBalance()
}

private val expiryFormat = DateTimeFormatter.ofPattern("d 'de' MMMM", Locale.forLanguageTag("es"))

/** The phone line's own balance: queries answered on screen, packages and balance transfers. */
@Composable
fun CubacelScreen(state: CubacelUi, actions: CubacelActions, modifier: Modifier = Modifier) {
    Scaffold(
        modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar("Saldo Cubacel", onBack = actions::back) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter).padding(bottom = NeoSpace.xxl),
            verticalArrangement = Arrangement.spacedBy(NeoSpace.m),
        ) {
            NeoGroup {
                Row(Modifier.padding(start = NeoSpace.xl, top = NeoSpace.s, end = NeoSpace.s, bottom = NeoSpace.xl)) {
                    Column(Modifier.weight(1f).padding(top = NeoSpace.m), verticalArrangement = Arrangement.spacedBy(NeoSpace.xs)) {
                        state.line?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        val balance = state.balance
                        if (balance != null) MoneyText(balance.amount, balance.currency, style = MaterialTheme.typography.displaySmall, hero = true)
                        else Text("Sin consultar", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        state.expires?.let { Text("Vence el ${expiryFormat.format(it)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        if (state.balanceLoading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else NeoIconButton(Ds.drawable.ic_refresh, "Consultar saldo", { actions.query(CubacelQuery.Balance) })
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
                NeoButton("Paquetes", actions::plans, Modifier.weight(1f), icon = Ds.drawable.ic_package_2)
                NeoButton("Transferir", actions::transferBalance, Modifier.weight(1f), style = dev.duardo.neotransfer.designsystem.component.NeoButtonStyle.Tonal, icon = Ds.drawable.ic_swap_horiz)
            }
            SectionHeader("Consultas", Modifier.padding(top = NeoSpace.s))
            NeoGroup {
                state.results.forEachIndexed { index, result ->
                    if (index > 0) GroupDivider()
                    NeoRow(
                        result.query.label,
                        supporting = result.detail,
                        leading = { IconAvatar(result.query.icon) },
                        trailing = {
                            when {
                                result.loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                result.value != null -> Text(result.value, style = MaterialTheme.typography.titleMedium)
                                else -> Text("Consultar", style = MaterialTheme.typography.labelLarge, color = Neo.colors.accentInk)
                            }
                        },
                        onClick = if (result.loading) null else ({ actions.query(result.query) }),
                    )
                }
            }
        }
    }
}

@get:androidx.annotation.DrawableRes
private val CubacelQuery.icon: Int
    get() = when (this) {
        CubacelQuery.Balance -> Ds.drawable.ic_account_balance_wallet
        CubacelQuery.Data -> Ds.drawable.ic_data_usage
        CubacelQuery.Voice -> Ds.drawable.ic_call
        CubacelQuery.Sms -> Ds.drawable.ic_sms
        CubacelQuery.Bonus -> Ds.drawable.ic_redeem
    }
