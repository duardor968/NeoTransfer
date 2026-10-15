package dev.duardo.neotransfer.app

import android.Manifest
import android.app.NotificationManager
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.duardo.neotransfer.core.backup.BackupFailure
import dev.duardo.neotransfer.core.backup.TrmFailure
import dev.duardo.neotransfer.core.backup.TrmImporter
import dev.duardo.neotransfer.data.Theme
import dev.duardo.neotransfer.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.designsystem.component.SectionHeader
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.platform.BiometricResult
import dev.duardo.neotransfer.ui.model.Appearance
import dev.duardo.neotransfer.ui.model.BackupUi
import dev.duardo.neotransfer.ui.model.Bank
import dev.duardo.neotransfer.ui.model.BankSettingsUi
import dev.duardo.neotransfer.ui.model.BankSummaryUi
import dev.duardo.neotransfer.ui.model.SettingsUi
import dev.duardo.neotransfer.ui.model.TransfermovilImportUi
import dev.duardo.neotransfer.ui.settings.AboutActions
import dev.duardo.neotransfer.ui.settings.AboutScreen
import dev.duardo.neotransfer.ui.settings.BackupActions
import dev.duardo.neotransfer.ui.settings.BackupScreen
import dev.duardo.neotransfer.ui.settings.BankSettingsActions
import dev.duardo.neotransfer.ui.settings.BankSettingsScreen
import dev.duardo.neotransfer.ui.settings.SettingsActions
import dev.duardo.neotransfer.ui.settings.SettingsScreen
import dev.duardo.neotransfer.ui.settings.TransfermovilImportActions
import dev.duardo.neotransfer.ui.settings.TransfermovilImportScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

class SettingsDestination(private val app: AppModel) : Destination(), SettingsActions {
    @Composable
    override fun Content(app: AppModel, book: Book) {
        app.resumed // reread what the system settings may have changed
        val domain = book.domain
        SettingsScreen(
            SettingsUi(
                banks = Bank.entries.map { bank -> BankSummaryUi(bank, book.cards.filter { it.bank == bank }, bank.core() in domain.savedKeys) },
                appearance = when (domain.settings.theme) {
                    Theme.SYSTEM -> Appearance.System
                    Theme.LIGHT -> Appearance.Light
                    Theme.DARK -> Appearance.Dark
                },
                notifications = app.granted(Manifest.permission.POST_NOTIFICATIONS) &&
                    LocalContext.current.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
                lastBackup = domain.lastBackup,
                version = app.version,
            ),
            this,
        )
    }

    override fun back() { app.navigator.remove(this) }

    /** A bank without cards is not a list to open: it starts its setup. */
    override fun bank(bank: Bank) {
        if (app.book.value.cards.any { it.bank == bank }) app.navigator.push(BankSettingsDestination(app, bank))
        else setUpBank(app, bank, setup = false, flowStart = null)
    }

    override fun appearance(value: Appearance) = app.setTheme(
        when (value) {
            Appearance.System -> Theme.SYSTEM
            Appearance.Light -> Theme.LIGHT
            Appearance.Dark -> Theme.DARK
        },
    )

    override fun notifications() {
        app.launch {
            val host = app.host ?: return@launch
            if (app.granted(Manifest.permission.POST_NOTIFICATIONS) || !host.requestPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                host.openNotificationSettings()
            }
        }
    }

    override fun backup() = app.navigator.push(BackupDestination(app))
    override fun importTransfermovil() = app.navigator.push(TransfermovilDestination(app))
    override fun about() = app.navigator.push(AboutDestination(app))
}

class BankSettingsDestination(private val app: AppModel, private val bank: Bank) : Destination(), BankSettingsActions {
    private var removing by mutableStateOf(false)

    @Composable
    override fun Content(app: AppModel, book: Book) {
        BankSettingsScreen(
            BankSettingsUi(bank, book.wallet.filter { it.card.bank == bank }, bank.core() in book.domain.savedKeys),
            this,
            confirmingRemoval = removing,
        )
    }

    override fun back() { app.navigator.remove(this) }
    override fun addCard() = app.navigator.push(CardSetupDestination(app, bank, setup = false, flowStart = null).also { it.addAnother() })
    override fun removeCard(cardId: String) {
        app.launch {
            app.controller.deleteCard(cardId)
            if (app.book.value.domain.cards.none { it.card.card.bank == bank.core() && it.card.card.number != cardId }) app.navigator.remove(this)
        }
    }

    override fun changeKey() = app.navigator.push(BankKeyDestination(app, bank) { app.navigator.remove(it) })
    override fun removeBank() { removing = true }
    override fun cancelRemoveBank() { removing = false }

    /** The key leaves the vault only with the fingerprint; then the cards go. Activity stays. */
    override fun confirmRemoveBank() {
        removing = false
        app.launch {
            if (bank.core() in app.book.value.domain.savedKeys) {
                val result = app.host?.biometric("Quitar ${bank.shortName}", cipher = app.controller.keyCipher())
                if (result !is BiometricResult.Passed) return@launch
                app.controller.saveKey(requireNotNull(result.cipher), bank.core(), null)
            }
            app.book.value.cards.filter { it.bank == bank }.forEach { app.controller.deleteCard(it.number) }
            app.navigator.remove(this)
        }
    }
}

/** Encrypted backup with a mandatory password; bank keys never leave the phone. */
class BackupDestination(private val app: AppModel) : Destination(), BackupActions {
    private var export by mutableStateOf(true)
    private var password by mutableStateOf("")
    private var confirm by mutableStateOf("")
    private var error by mutableStateOf<String?>(null)
    private var busy by mutableStateOf(false)
    private var file by mutableStateOf<Uri?>(null)
    private var fileName by mutableStateOf<String?>(null)

    @Composable
    override fun Content(app: AppModel, book: Book) {
        val mismatch = confirm.isNotEmpty() && confirm.length >= password.length && confirm != password
        BackupScreen(
            if (export) BackupUi.Export(password, confirm, error ?: if (mismatch) "Las contraseñas no coinciden" else null,
                password.isNotEmpty() && password == confirm && !busy, busy)
            else BackupUi.Import(fileName, password, error, busy),
            this,
        )
    }

    override fun back() {
        password = ""
        confirm = ""
        app.navigator.remove(this)
    }

    override fun mode(export: Boolean) {
        this.export = export
        password = ""
        confirm = ""
        error = null
    }

    override fun password(value: String) { password = value; error = null }
    override fun confirm(value: String) { confirm = value; error = null }

    override fun pickFile() {
        app.launch {
            val host = app.host ?: return@launch
            val uri = host.openDocument() ?: return@launch
            file = uri
            fileName = host.displayName(uri) ?: uri.lastPathSegment
            error = null
        }
    }

    override fun run() {
        busy = true
        app.launch {
            try {
                if (export) {
                    val uri = app.host?.createDocument("neotransfer-${LocalDate.now()}.ntbackup") ?: return@launch
                    app.controller.exportBackup(uri.toString(), password.toCharArray())
                } else {
                    val uri = file ?: return@launch
                    val summary = app.controller.importBackup(uri.toString(), password.toCharArray())
                    app.message(imported(summary.cards, summary.contacts, summary.services))
                }
                back()
            } catch (e: BackupFailure) {
                error = e.message
            } catch (e: Exception) {
                error = e.message ?: "No se pudo completar"
            } finally {
                busy = false
            }
        }
    }
}

class TransfermovilDestination(private val app: AppModel) : Destination(), TransfermovilImportActions {
    private var state by mutableStateOf<TransfermovilImportUi>(TransfermovilImportUi.Pick)
    private var file: Uri? = null

    @Composable
    override fun Content(app: AppModel, book: Book) = TransfermovilImportScreen(state, this)

    override fun back() { app.navigator.remove(this) }

    override fun pickFile() {
        app.launch {
            val host = app.host ?: return@launch
            val uri = host.openDocument() ?: return@launch
            file = uri
            val name = host.displayName(uri) ?: uri.lastPathSegment.orEmpty()
            state = try {
                val content = withContext(Dispatchers.IO) {
                    val bytes = checkNotNull(app.getApplication<android.app.Application>().contentResolver.openInputStream(uri)) { "No se pudo abrir el archivo" }
                        .use { it.readNBytes(TrmImporter.MAX_INPUT_BYTES + 1) }
                    TrmImporter.import(bytes).content
                }
                TransfermovilImportUi.Preview(name, content.contacts.size, content.contacts.sumOf { it.cards.size }, content.services.size)
            } catch (e: TrmFailure) {
                TransfermovilImportUi.Failed(e.message ?: "El archivo no es válido")
            } catch (e: Exception) {
                TransfermovilImportUi.Failed(e.message ?: "No se pudo leer el archivo")
            }
        }
    }

    override fun import() {
        val preview = state as? TransfermovilImportUi.Preview ?: return
        val uri = file ?: return
        state = preview.copy(busy = true)
        app.launch {
            state = try {
                val summary = app.controller.importTrm(uri.toString())
                TransfermovilImportUi.Done(summary.contacts, preview.cards, summary.services)
            } catch (e: Exception) {
                TransfermovilImportUi.Failed(e.message ?: "No se pudo importar")
            }
        }
    }
}

class AboutDestination(private val app: AppModel) : Destination(), AboutActions {
    @Composable
    override fun Content(app: AppModel, book: Book) = AboutScreen(app.version, this)

    override fun back() { app.navigator.remove(this) }
    override fun source() { app.host?.open("https://github.com/duardor968/NeoTransfer") }
    override fun licenses() = app.navigator.push(LicensesDestination(app))
}

/** The bundled fonts' and icons' licenses, as shipped in the app's assets. */
class LicensesDestination(private val app: AppModel) : Destination() {
    @Composable
    override fun Content(app: AppModel, book: Book) {
        val context = LocalContext.current
        val licenses = remember {
            listOf("Inter" to "Inter-OFL.txt", "Arimo" to "Arimo-OFL.txt", "Material Symbols" to "Material-Icons-LICENSE.txt").map { (name, file) ->
                name to context.assets.open("licenses/$file").bufferedReader().use { it.readText() }
            }
        }
        Scaffold(containerColor = MaterialTheme.colorScheme.background, topBar = { NeoTopBar("Licencias", onBack = { app.navigator.remove(this) }) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter).padding(bottom = NeoSpace.xxl)) {
                licenses.forEach { (name, text) ->
                    SectionHeader(name, Modifier.padding(top = NeoSpace.l))
                    Text(text.trim(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun imported(cards: Int, contacts: Int, services: Int): String {
    fun count(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
    return "Importado: " + listOf(count(cards, "tarjeta", "tarjetas"), count(contacts, "contacto", "contactos"), count(services, "referencia", "referencias")).joinToString(", ")
}
