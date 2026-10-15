package dev.duardo.neotransfer.ui.services

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.R as Ds
import dev.duardo.neotransfer.designsystem.component.FittedText
import dev.duardo.neotransfer.designsystem.component.GroupDivider
import dev.duardo.neotransfer.designsystem.component.dashedOutline
import dev.duardo.neotransfer.designsystem.component.IconAvatar
import dev.duardo.neotransfer.designsystem.component.MoneyText
import dev.duardo.neotransfer.designsystem.component.NeoButton
import dev.duardo.neotransfer.designsystem.component.NeoGroup
import dev.duardo.neotransfer.designsystem.component.NeoRow
import dev.duardo.neotransfer.designsystem.component.NeoTextField
import dev.duardo.neotransfer.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.designsystem.component.SecureWindow
import dev.duardo.neotransfer.designsystem.component.SectionHeader
import dev.duardo.neotransfer.designsystem.format.formatAmount
import dev.duardo.neotransfer.designsystem.format.formatMoney
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoRadius
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.ui.common.PhoneTransformation
import dev.duardo.neotransfer.ui.common.SourceCardPicker
import dev.duardo.neotransfer.ui.common.SwitchRow
import dev.duardo.neotransfer.ui.common.icon
import dev.duardo.neotransfer.ui.model.CubacelPlansUi
import dev.duardo.neotransfer.ui.model.CubacelQuery
import dev.duardo.neotransfer.ui.model.CubacelUi
import dev.duardo.neotransfer.ui.model.FieldInput
import dev.duardo.neotransfer.ui.model.SavedReferenceUi
import dev.duardo.neotransfer.ui.model.ServiceFormUi
import dev.duardo.neotransfer.ui.model.ServiceKind
import dev.duardo.neotransfer.ui.model.ServicesUi
import dev.duardo.neotransfer.ui.model.Utility
import dev.duardo.neotransfer.ui.onboarding.BottomAction
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---- Catalogue ----------------------------------------------------------------------------------------------

interface ServicesActions {
    fun open(kind: ServiceKind)
    fun pay(referenceId: String)
}

/**
 * Services grouped by what they pay for. Each utility is a row of tiles: saved references first (one tap to pay
 * the usual bill), then the tiles that start a new payment.
 */
@Composable
fun ServicesScreen(state: ServicesUi, actions: ServicesActions, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = NeoSpace.xxl)) {
        item(key = "title") {
            Text(
                "Servicios",
                Modifier.statusBarsPadding().padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.xl, bottom = NeoSpace.s),
                style = MaterialTheme.typography.headlineMedium,
            )
        }
        Utility.entries.forEach { utility ->
            val kinds = ServiceKind.entries.filter { it.utility == utility }
            val references = state.references.filter { it.kind.utility == utility }
            item(key = utility.name) {
                Column(Modifier.padding(top = NeoSpace.m)) {
                    SectionHeader(utility.label, Modifier.padding(horizontal = NeoSpace.gutter))
                    // Saved references first, then one dashed tile per way to start a new payment.
                    val tiles: List<Any> = references + kinds
                    Column(Modifier.padding(horizontal = NeoSpace.gutter), verticalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
                        tiles.chunked(2).forEach { pair ->
                            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
                                pair.forEach { tile ->
                                    val m = Modifier.weight(1f).fillMaxHeight()
                                    when (tile) {
                                        is SavedReferenceUi -> ServiceTile(tile, m) { actions.pay(tile.id) }
                                        is ServiceKind -> NewPaymentTile(tile.startLabel(single = kinds.size == 1), tile.icon, m) { actions.open(tile) }
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Label of the tile that starts a payment; within a one-service utility the header already names it. */
private fun ServiceKind.startLabel(single: Boolean): String = when {
    this == ServiceKind.MobileTopUp -> "Recargar móvil"
    single -> "Pagar"
    else -> label
}

/** Starts a payment: dashed like the wallet's add slot, the system's mark for "new". */
@Composable
private fun NewPaymentTile(title: String, icon: Int, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(NeoRadius.group)
    Column(
        modifier
            .heightIn(min = 96.dp)
            .clip(shape)
            .dashedOutline(MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), NeoRadius.group)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(NeoSpace.l),
        verticalArrangement = Arrangement.spacedBy(NeoSpace.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NeoSpace.xs)) {
            Icon(painterResource(Ds.drawable.ic_add), null, Modifier.size(20.dp), tint = Neo.colors.accentInk)
            Icon(painterResource(icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.weight(1f))
        Text(title, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun ServiceTile(reference: SavedReferenceUi, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .heightIn(min = 96.dp)
            .clip(RoundedCornerShape(NeoRadius.group))
            .background(Neo.colors.group)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(NeoSpace.l),
        verticalArrangement = Arrangement.spacedBy(NeoSpace.xxs),
    ) {
        Icon(painterResource(reference.kind.icon), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(NeoSpace.s))
        Text(reference.name, style = MaterialTheme.typography.titleSmall)
        // References are identifiers: one line, never split mid-number.
        FittedText(reference.value, MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, minScale = 0.5f)
        reference.lastPaid?.let {
            Text(formatMoney(it.amount, it.currency), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- Pay a service ------------------------------------------------------------------------------------------

interface ServiceFormActions {
    fun back()
    fun reference(id: String?)
    fun field(id: String, value: String)
    fun amount(value: String)
    fun source(index: Int)
    fun saveReference(value: Boolean)
    fun submit()
}

/** One form for every service: saved references on top, the fields the bank asks for, the card and the total. */
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
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = NeoSpace.l),
                verticalArrangement = Arrangement.spacedBy(NeoSpace.m),
            ) {
                if (state.references.isNotEmpty()) {
                    FlowRow(
                        Modifier.padding(horizontal = NeoSpace.gutter),
                        horizontalArrangement = Arrangement.spacedBy(NeoSpace.s),
                    ) {
                        ReferenceChip("Nueva", state.selectedReference == null) { actions.reference(null) }
                        state.references.forEach { reference ->
                            ReferenceChip(reference.name, state.selectedReference == reference.id) { actions.reference(reference.id) }
                        }
                    }
                }
                Column(Modifier.padding(horizontal = NeoSpace.gutter), verticalArrangement = Arrangement.spacedBy(NeoSpace.m)) {
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
                        )
                    }
                    state.due?.let { due ->
                        Column(Modifier.fillMaxWidth().padding(vertical = NeoSpace.m), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Factura", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MoneyText(due.amount, due.currency, style = MaterialTheme.typography.displaySmall, hero = true)
                        }
                    }
                    if (state.amount != null) NeoTextField(
                        state.amount, actions::amount, "Importe",
                        keyboardType = KeyboardType.Decimal, suffix = state.currency,
                    )
                    if (state.cards.isNotEmpty()) SourceCardPicker(state.cards, state.source, actions::source)
                    state.saveReference?.let { save -> SwitchRow("Guardar referencia", save, actions::saveReference) }
                }
            }
            BottomAction {
                NeoButton(state.action, actions::submit, enabled = state.canContinue, icon = if (state.cards.isNotEmpty()) Ds.drawable.ic_fingerprint else null)
            }
        }
    }
}

@Composable
private fun ReferenceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        shape = CircleShape,
        border = null,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            selectedContainerColor = Neo.colors.accentContainer,
            selectedLabelColor = Neo.colors.onAccentContainer,
        ),
    )
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
                Column(Modifier.padding(NeoSpace.xl), verticalArrangement = Arrangement.spacedBy(NeoSpace.xs)) {
                    Text(state.line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val balance = state.balance
                    if (balance != null) MoneyText(balance.amount, balance.currency, style = MaterialTheme.typography.displaySmall, hero = true)
                    else Text("Sin consultar", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.expires?.let { Text("Vence el ${expiryFormat.format(it)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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

interface CubacelPlansActions {
    fun back()
    fun select(id: String)
    fun buy()
}

/** Packages bought with phone balance, grouped as ETECSA sells them. */
@Composable
fun CubacelPlansScreen(state: CubacelPlansUi, actions: CubacelPlansActions, modifier: Modifier = Modifier) {
    val selected = state.groups.flatMap { it.plans }.firstOrNull { it.id == state.selected }
    Scaffold(
        modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar("Paquetes", onBack = actions::back) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = NeoSpace.gutter, end = NeoSpace.gutter, bottom = NeoSpace.l)) {
                state.balance?.let { balance ->
                    item(key = "balance") {
                        Text(
                            "Saldo: ${formatMoney(balance.amount, balance.currency)}",
                            Modifier.padding(bottom = NeoSpace.s),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.groups.forEach { group ->
                    item(key = group.name) {
                        Column(Modifier.padding(top = NeoSpace.m)) {
                            SectionHeader(group.name)
                            Column(verticalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
                                group.plans.forEach { plan ->
                                    PlanTile(plan.name, plan.detail, formatAmount(plan.price.amount) + " " + plan.price.currency, plan.id == state.selected) { actions.select(plan.id) }
                                }
                            }
                        }
                    }
                }
            }
            BottomAction {
                NeoButton(selected?.let { "Comprar por ${formatMoney(it.price.amount, it.price.currency)}" } ?: "Comprar", actions::buy, enabled = selected != null)
            }
        }
    }
}

@Composable
private fun PlanTile(name: String, detail: String, price: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(NeoRadius.group)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Neo.colors.accentContainer else Neo.colors.group)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(horizontal = NeoSpace.l, vertical = NeoSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(NeoSpace.m),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = if (selected) Neo.colors.onAccentContainer else MaterialTheme.colorScheme.onSurface)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(price, style = MaterialTheme.typography.titleMedium, color = if (selected) Neo.colors.onAccentContainer else MaterialTheme.colorScheme.onSurface)
        Box(Modifier.size(22.dp).clip(CircleShape).background(if (selected) Neo.colors.accent else MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
            if (selected) Icon(painterResource(Ds.drawable.ic_check), null, Modifier.size(16.dp), tint = Neo.colors.onAccent)
        }
    }
}
