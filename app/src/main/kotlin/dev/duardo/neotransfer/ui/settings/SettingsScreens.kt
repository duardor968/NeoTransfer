package dev.duardo.neotransfer.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.R as Ds
import dev.duardo.neotransfer.designsystem.component.EmptyState
import dev.duardo.neotransfer.designsystem.component.GroupDivider
import dev.duardo.neotransfer.designsystem.component.IconAvatar
import dev.duardo.neotransfer.designsystem.component.NeoButton
import dev.duardo.neotransfer.designsystem.component.NeoConfirmDialog
import dev.duardo.neotransfer.designsystem.component.NeoGroup
import dev.duardo.neotransfer.designsystem.component.NeoIconButton
import dev.duardo.neotransfer.designsystem.component.NeoRow
import dev.duardo.neotransfer.designsystem.component.NeoSegmented
import dev.duardo.neotransfer.designsystem.component.NeoTextField
import dev.duardo.neotransfer.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.designsystem.component.SecureWindow
import dev.duardo.neotransfer.designsystem.component.SectionHeader
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.designsystem.format.formatMoney
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.ui.common.BankCardThumb
import dev.duardo.neotransfer.ui.common.BrandMark
import dev.duardo.neotransfer.ui.common.timeLabels
import dev.duardo.neotransfer.ui.model.Appearance
import dev.duardo.neotransfer.ui.model.BackupUi
import dev.duardo.neotransfer.ui.model.Bank
import dev.duardo.neotransfer.ui.model.BankSettingsUi
import dev.duardo.neotransfer.ui.model.BalanceUi
import dev.duardo.neotransfer.ui.model.SettingsUi
import dev.duardo.neotransfer.ui.model.TransfermovilImportUi
import dev.duardo.neotransfer.ui.onboarding.BottomAction

@Composable
private fun Chevron() {
    Icon(painterResource(Ds.drawable.ic_chevron_right), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SettingsScaffold(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, bottom: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Scaffold(
        modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar(title, onBack = onBack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter).padding(bottom = NeoSpace.xxl),
            ) { content() }
            bottom?.invoke()
        }
    }
}

// ---- Settings home ------------------------------------------------------------------------------------------

interface SettingsActions {
    fun back()
    fun bank(bank: Bank)
    fun appearance(value: Appearance)
    fun notifications()
    fun backup()
    fun importTransfermovil()
    fun about()
}

@Composable
fun SettingsScreen(state: SettingsUi, actions: SettingsActions, modifier: Modifier = Modifier) {
    SettingsScaffold("Ajustes", actions::back, modifier) {
        SectionHeader("Bancos y claves")
        NeoGroup {
            state.banks.forEachIndexed { index, summary ->
                if (index > 0) GroupDivider()
                val configured = summary.cards.isNotEmpty()
                NeoRow(
                    summary.bank.shortName,
                    supporting = if (configured) {
                        listOf(
                            if (summary.cards.size == 1) "1 tarjeta" else "${summary.cards.size} tarjetas",
                            if (summary.keySaved) "Clave guardada" else "Sin clave",
                        ).joinToString(" · ")
                    } else {
                        "Sin configurar"
                    },
                    leading = { BankCardThumb(summary.bank) },
                    trailing = { Chevron() },
                    onClick = { actions.bank(summary.bank) },
                )
            }
        }
        SectionHeader("Apariencia", Modifier.padding(top = NeoSpace.l))
        NeoSegmented(Appearance.entries, state.appearance, { it.label }, actions::appearance)
        SectionHeader("General", Modifier.padding(top = NeoSpace.l))
        NeoGroup {
            NeoRow(
                "Notificaciones",
                supporting = if (state.notifications) "Activadas" else "Desactivadas",
                leading = { IconAvatar(Ds.drawable.ic_notifications) },
                trailing = { Chevron() },
                onClick = actions::notifications,
            )
            GroupDivider()
            NeoRow(
                "Respaldo",
                supporting = state.lastBackup?.let { "Último: ${timeLabels.moment(it)}" } ?: "Sin respaldos",
                leading = { IconAvatar(Ds.drawable.ic_settings_backup_restore) },
                trailing = { Chevron() },
                onClick = actions::backup,
            )
            GroupDivider()
            NeoRow(
                "Importar de Transfermóvil",
                leading = { IconAvatar(Ds.drawable.ic_file_open) },
                trailing = { Chevron() },
                onClick = actions::importTransfermovil,
            )
            GroupDivider()
            NeoRow(
                "Acerca de",
                supporting = "Versión ${state.version}",
                leading = { IconAvatar(Ds.drawable.ic_info) },
                trailing = { Chevron() },
                onClick = actions::about,
            )
        }
    }
}

// ---- One bank -----------------------------------------------------------------------------------------------

interface BankSettingsActions {
    fun back()
    fun addCard()
    fun removeCard(cardId: String)
    fun changeKey()
    fun removeBank()
    fun confirmRemoveBank()
    fun cancelRemoveBank()
}

@Composable
fun BankSettingsScreen(state: BankSettingsUi, actions: BankSettingsActions, modifier: Modifier = Modifier, confirmingRemoval: Boolean = false) {
    SettingsScaffold(state.bank.shortName, actions::back, modifier) {
        SectionHeader("Tarjetas")
        NeoGroup {
            state.cards.forEach { wallet ->
                val balance = wallet.balance
                NeoRow(
                    formatCardNumber(wallet.card.number),
                    supporting = listOfNotNull(
                        wallet.card.currency,
                        (balance as? BalanceUi.Known)?.let { "Saldo ${formatMoney(it.money.amount, it.money.currency)}" },
                    ).joinToString(" · "),
                    leading = { BankCardThumb(wallet.card.bank) },
                    trailing = { NeoIconButton(Ds.drawable.ic_delete, "Quitar tarjeta", { actions.removeCard(wallet.card.id) }) },
                )
                GroupDivider()
            }
            NeoRow("Añadir tarjeta", leading = { IconAvatar(Ds.drawable.ic_add_card) }, onClick = actions::addCard)
        }
        NeoGroup(Modifier.padding(top = NeoSpace.xxl)) {
            NeoRow(
                if (state.keySaved) "Cambiar clave" else "Guardar clave",
                supporting = if (state.keySaved) "Guardada en este teléfono" else null,
                leading = { IconAvatar(Ds.drawable.ic_key) },
                trailing = { Chevron() },
                onClick = actions::changeKey,
            )
            GroupDivider()
            NeoRow(
                "Quitar ${state.bank.shortName}",
                leading = { IconAvatar(Ds.drawable.ic_delete, tint = Neo.colors.danger) },
                titleColor = Neo.colors.danger,
                onClick = actions::removeBank,
            )
        }
    }
    if (confirmingRemoval) NeoConfirmDialog(
        title = "¿Quitar ${state.bank.shortName}?",
        text = "Se borran sus tarjetas y la clave guardada. La actividad se conserva.",
        confirm = "Quitar",
        dismiss = "Cancelar",
        onConfirm = actions::confirmRemoveBank,
        onDismiss = actions::cancelRemoveBank,
        destructive = true,
    )
}

// ---- Backup -------------------------------------------------------------------------------------------------

interface BackupActions {
    fun back()
    fun mode(export: Boolean)
    fun password(value: String)
    fun confirm(value: String)
    fun pickFile()
    fun run()
}

/** Encrypted file with a mandatory password. Bank keys never leave the phone. Secret: no screenshots. */
@Composable
fun BackupScreen(state: BackupUi, actions: BackupActions, modifier: Modifier = Modifier) {
    SecureWindow()
    val export = state is BackupUi.Export
    SettingsScaffold(
        "Respaldo", actions::back, modifier,
        bottom = {
            BottomAction {
                when (state) {
                    is BackupUi.Export -> NeoButton("Exportar respaldo", actions::run, icon = Ds.drawable.ic_upload, enabled = state.canExport, loading = state.busy)
                    is BackupUi.Import -> NeoButton("Importar respaldo", actions::run, icon = Ds.drawable.ic_download, enabled = state.fileName != null && state.password.isNotEmpty(), loading = state.busy)
                }
            }
        },
    ) {
        NeoSegmented(listOf(true, false), export, { if (it) "Exportar" else "Importar" }, actions::mode)
        Spacer(Modifier.height(NeoSpace.xl))
        when (state) {
            is BackupUi.Export -> Column(verticalArrangement = Arrangement.spacedBy(NeoSpace.m)) {
                Text(
                    "Tarjetas, contactos, referencias y actividad, cifrados con tu contraseña. Las claves del banco no se incluyen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PasswordField(state.password, actions::password, "Contraseña", null)
                PasswordField(state.confirm, actions::confirm, "Repite la contraseña", state.error)
            }
            is BackupUi.Import -> Column(verticalArrangement = Arrangement.spacedBy(NeoSpace.m)) {
                NeoGroup {
                    NeoRow(
                        if (state.fileName != null) "Archivo" else "Elegir archivo",
                        supporting = state.fileName,
                        leading = { IconAvatar(Ds.drawable.ic_file_open) },
                        trailing = { Chevron() },
                        onClick = actions::pickFile,
                    )
                }
                PasswordField(state.password, actions::password, "Contraseña", state.error)
            }
        }
    }
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit, label: String, error: String?) {
    var visible by remember { mutableStateOf(false) }
    NeoTextField(
        value, onChange, label,
        keyboardType = KeyboardType.Password,
        imeAction = ImeAction.Next,
        error = error,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailing = {
            NeoIconButton(
                if (visible) Ds.drawable.ic_visibility_off else Ds.drawable.ic_visibility,
                if (visible) "Ocultar contraseña" else "Mostrar contraseña",
                { visible = !visible },
            )
        },
    )
}

// ---- Transfermóvil import -----------------------------------------------------------------------------------

interface TransfermovilImportActions {
    fun back()
    fun pickFile()
    fun import()
}

@Composable
fun TransfermovilImportScreen(state: TransfermovilImportUi, actions: TransfermovilImportActions, modifier: Modifier = Modifier) {
    SettingsScaffold(
        "Importar de Transfermóvil", actions::back, modifier,
        bottom = {
            when (state) {
                is TransfermovilImportUi.Preview -> BottomAction { NeoButton("Importar", actions::import, loading = state.busy) }
                is TransfermovilImportUi.Done -> BottomAction { NeoButton("Volver a Ajustes", actions::back) }
                else -> Unit
            }
        },
    ) {
        when (state) {
            TransfermovilImportUi.Pick -> EmptyState(
                Ds.drawable.ic_file_open, "Respaldo de Transfermóvil",
                body = "Archivo .trm exportado desde Transfermóvil.",
                action = "Elegir archivo", onAction = actions::pickFile,
            )
            is TransfermovilImportUi.Preview -> {
                NeoGroup {
                    NeoRow("Archivo", supporting = state.fileName, leading = { IconAvatar(Ds.drawable.ic_file_open) }, trailing = { Chevron() }, onClick = actions::pickFile)
                }
                SectionHeader("Se importará", Modifier.padding(top = NeoSpace.l))
                Counts(state.contacts, state.cards, state.references)
            }
            is TransfermovilImportUi.Done -> {
                Column(Modifier.fillMaxWidth().padding(vertical = NeoSpace.xxl), horizontalAlignment = Alignment.CenterHorizontally) {
                    IconAvatar(Ds.drawable.ic_check, size = 64.dp, tint = Neo.colors.positive)
                    Text("Importado", Modifier.padding(top = NeoSpace.l), style = MaterialTheme.typography.titleLarge)
                }
                Counts(state.contacts, state.cards, state.references)
            }
            is TransfermovilImportUi.Failed -> EmptyState(
                Ds.drawable.ic_error, state.message,
                action = "Elegir otro archivo", onAction = actions::pickFile,
            )
        }
    }
}

@Composable
private fun Counts(contacts: Int, cards: Int, references: Int) {
    NeoGroup {
        CountRow("Contactos", contacts, Ds.drawable.ic_group)
        GroupDivider()
        CountRow("Tarjetas de contactos", cards, Ds.drawable.ic_credit_card)
        GroupDivider()
        CountRow("Referencias de servicios", references, Ds.drawable.ic_receipt_long)
    }
}

@Composable
private fun CountRow(label: String, count: Int, icon: Int) {
    NeoRow(label, leading = { IconAvatar(icon) }, trailing = { Text("$count", style = MaterialTheme.typography.titleMedium) })
}

// ---- About --------------------------------------------------------------------------------------------------

interface AboutActions {
    fun back()
    fun source()
    fun licenses()
}

@Composable
fun AboutScreen(version: String, actions: AboutActions, modifier: Modifier = Modifier) {
    SettingsScaffold("Acerca de", actions::back, modifier) {
        Column(Modifier.fillMaxWidth().padding(vertical = NeoSpace.xxl), horizontalAlignment = Alignment.CenterHorizontally) {
            BrandMark(96.dp)
            Text("NeoTransfer", Modifier.padding(top = NeoSpace.l), style = MaterialTheme.typography.headlineSmall)
            Text("Versión $version", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        NeoGroup {
            NeoRow("Código fuente", supporting = "github.com/duardor968/NeoTransfer", leading = { IconAvatar(Ds.drawable.ic_code) }, trailing = { Chevron() }, onClick = actions::source)
            GroupDivider()
            NeoRow("Licencias", supporting = "Inter, Arimo, Material Symbols", leading = { IconAvatar(Ds.drawable.ic_description) }, trailing = { Chevron() }, onClick = actions::licenses)
        }
    }
}
