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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.duardo.neotransfer.core.backup.BackupFailure
import dev.duardo.neotransfer.core.backup.TrmFailure
import dev.duardo.neotransfer.core.backup.TrmImporter
import dev.duardo.neotransfer.core.model.Cards
import dev.duardo.neotransfer.core.model.Currency
import dev.duardo.neotransfer.core.model.MiTransfer
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.core.protocol.AccountsQuery
import dev.duardo.neotransfer.core.protocol.CardAssociation
import dev.duardo.neotransfer.core.protocol.ClassicRemoval
import dev.duardo.neotransfer.core.protocol.PinChange
import dev.duardo.neotransfer.core.protocol.RegistrationRemoval
import dev.duardo.neotransfer.core.protocol.UssdRequest
import dev.duardo.neotransfer.data.Theme
import dev.duardo.neotransfer.data.WalletCard
import dev.duardo.neotransfer.designsystem.component.KeypadKey
import dev.duardo.neotransfer.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.designsystem.component.SectionHeader
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.engine.Submission
import dev.duardo.neotransfer.platform.BiometricResult
import dev.duardo.neotransfer.scanner.ScanMode
import dev.duardo.neotransfer.scanner.ScanResult
import dev.duardo.neotransfer.ui.model.Appearance
import dev.duardo.neotransfer.ui.model.BackupUi
import dev.duardo.neotransfer.ui.model.Bank
import dev.duardo.neotransfer.ui.model.BankSettingsUi
import dev.duardo.neotransfer.ui.model.BankSummaryUi
import dev.duardo.neotransfer.ui.model.CardAssociationUi
import dev.duardo.neotransfer.ui.model.CardFieldUi
import dev.duardo.neotransfer.ui.model.FieldInput
import dev.duardo.neotransfer.ui.model.FieldShape
import dev.duardo.neotransfer.ui.model.FieldUi
import dev.duardo.neotransfer.ui.model.KeyToSaveUi
import dev.duardo.neotransfer.ui.model.LineRequestUi
import dev.duardo.neotransfer.ui.model.LineStateUi
import dev.duardo.neotransfer.ui.model.NewKeyUi
import dev.duardo.neotransfer.ui.model.RemovalUi
import dev.duardo.neotransfer.ui.model.SettingsUi
import dev.duardo.neotransfer.ui.model.TELEBANCA_PREFIX
import dev.duardo.neotransfer.ui.model.TransfermovilImportUi
import dev.duardo.neotransfer.ui.settings.AboutActions
import dev.duardo.neotransfer.ui.settings.AboutScreen
import dev.duardo.neotransfer.ui.settings.BackupActions
import dev.duardo.neotransfer.ui.settings.BackupScreen
import dev.duardo.neotransfer.ui.settings.BankSettingsActions
import dev.duardo.neotransfer.ui.settings.BankSettingsScreen
import dev.duardo.neotransfer.ui.settings.CardAssociationActions
import dev.duardo.neotransfer.ui.settings.CardAssociationScreen
import dev.duardo.neotransfer.ui.settings.ClassicSettingsActions
import dev.duardo.neotransfer.ui.settings.ClassicSettingsScreen
import dev.duardo.neotransfer.ui.settings.LineAccountsActions
import dev.duardo.neotransfer.ui.settings.LineAccountsScreen
import dev.duardo.neotransfer.ui.settings.LineRequestActions
import dev.duardo.neotransfer.ui.settings.LineRequestScreen
import dev.duardo.neotransfer.ui.settings.LineService
import dev.duardo.neotransfer.ui.settings.NewKeyActions
import dev.duardo.neotransfer.ui.settings.NewKeyScreen
import dev.duardo.neotransfer.ui.settings.SettingsActions
import dev.duardo.neotransfer.ui.settings.SettingsScreen
import dev.duardo.neotransfer.ui.settings.TransfermovilImportActions
import dev.duardo.neotransfer.ui.settings.TransfermovilImportScreen
import dev.duardo.neotransfer.ui.settings.rowNote
import dev.duardo.neotransfer.ui.shell.Tab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

class SettingsDestination(private val app: AppModel) : Destination(), SettingsActions {
    @Composable
    override fun Content(app: AppModel, book: Book) {
        app.resumed // reread what the system settings may have changed
        val domain = book.domain
        // Every bank, set up or not; the Clásica once there is one.
        val summaries = Bank.entries.map { bank -> BankSummaryUi(bank, book.cards.filter { it.bank == bank }, bank.agency() in domain.savedKeys) }
        SettingsScreen(
            SettingsUi(
                banks = summaries.filter { it.bank != Bank.CLASSIC || it.cards.isNotEmpty() },
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

    /** A bank without cards is not a list to open: it starts its setup. The Clásica is listed only once there is one. */
    override fun bank(bank: Bank) {
        val card = app.book.value.cards.firstOrNull { it.bank == bank }
        when {
            card == null -> setUpBank(app, bank, setup = false, flowStart = null)
            bank == Bank.CLASSIC -> app.navigator.push(ClassicSettingsDestination(app, card.number))
            else -> app.navigator.push(BankSettingsDestination(app, bank))
        }
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

/** Ajustes › banco: its cards, the line's services with it, and removing it. */
class BankSettingsDestination(private val app: AppModel, internal val bank: Bank) : Destination(), BankSettingsActions {
    private var removing by mutableStateOf(false)
    private var removingCard by mutableStateOf<String?>(null)
    private val removal = RemovalForm(app, bank)

    @Composable
    override fun Content(app: AppModel, book: Book) {
        BankSettingsScreen(
            BankSettingsUi(bank, book.wallet.filter { it.card.bank == bank }, bank.core() in book.domain.savedKeys),
            this,
            confirmingRemoval = removing,
            line = book.bankLine(bank),
            unregistering = removal.ui(),
            sending = removal.sending,
            removingCard = removingCard,
        )
    }

    override fun back() { app.navigator.remove(this) }
    override fun addCard() = app.navigator.push(CardSetupDestination(app, bank, setup = false, flowStart = null).also { it.addAnother() })
    override fun removeCard(cardId: String) { removingCard = cardId }
    override fun cancelRemoveCard() { removingCard = null }
    override fun confirmRemoveCard() {
        val cardId = removingCard ?: return
        removingCard = null
        app.launch {
            app.controller.deleteCard(cardId)
            if (app.book.value.domain.cards.none { it.card.card.bank == bank.core() && it.card.card.number != cardId }) app.navigator.remove(this)
        }
    }

    /**
     * A key the bank took waits to be saved: the change that brought it shows its answer first, a registration's is saved
     * straight away. A change on its way opens where it stands; otherwise the new key is typed.
     */
    override fun changeKey() {
        val line = app.book.value.bankLine(bank)
        val key = line.keyToSave
        val fromChange = line.keyChange?.state.let { it is LineStateUi.Confirmed || it is LineStateUi.Answered }
        when {
            key != null && !fromChange -> saveKeyToSave(app, bank, key)
            key != null || line.keyChange?.rowNote != null -> app.navigator.push(keyChangeRequest(app, bank))
            else -> app.navigator.push(NewKeyDestination(app, bank))
        }
    }

    override fun savedKey() = typeKey(app, bank)
    override fun accounts() = app.navigator.push(LineAccountsDestination(app, bank))

    override fun associate() {
        if (app.book.value.bankLine(bank).association?.rowNote != null) app.navigator.push(associationRequest(app, bank))
        else app.navigator.push(CardAssociationDestination(app, bank))
    }

    override fun unregister() {
        if (app.book.value.bankLine(bank).removal?.rowNote != null) app.navigator.push(removalRequest(app, bank)) else removal.open = true
    }

    override fun removalField(id: String, value: String) = removal.field(id, value)
    override fun confirmUnregister() = removal.send { app.navigator.push(removalRequest(app, bank)) }
    override fun cancelUnregister() = removal.close()

    /** The confirmation again, filled in with what was [sent]: after a refusal, to correct it. */
    fun reviewRemoval(sent: UssdRequest?) = removal.review(sent)

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

/** Ajustes › Clásica: the card, MiTransfer's PIN, and removing its association or the card itself. */
class ClassicSettingsDestination(private val app: AppModel, internal val number: String) : Destination(), ClassicSettingsActions {
    private var removing by mutableStateOf(false)
    private val removal = RemovalForm(app, Bank.CLASSIC, number)

    @Composable
    override fun Content(app: AppModel, book: Book) {
        val state = book.classicSettings(number) ?: return
        ClassicSettingsScreen(
            state, this,
            unassociating = removal.ui(),
            confirmingRemoval = removing,
            removesPin = state.pinSaved && lastClassic(book),
            sending = removal.sending,
        )
    }

    override fun back() { app.navigator.remove(this) }

    override fun pin() {
        val key = app.book.value.keyToSave(Bank.CLASSIC)
        if (key != null) saveKeyToSave(app, Bank.CLASSIC, key) else typeKey(app, Bank.CLASSIC)
    }

    override fun unassociate() {
        if (app.book.value.classicSettings(number)?.removal?.rowNote != null) app.navigator.push(classicRemovalRequest(app, number)) else removal.open = true
    }

    override fun removalField(id: String, value: String) = removal.field(id, value)
    override fun confirmUnassociate() = removal.send { app.navigator.push(classicRemovalRequest(app, number)) }
    override fun cancelUnassociate() = removal.close()

    /** The confirmation again, filled in with what was [sent]: after a refusal, to correct it. */
    fun reviewRemoval(sent: UssdRequest?) = removal.review(sent)

    override fun remove() { removing = true }
    override fun cancelRemove() { removing = false }

    /** Like a bank's removal: with the last Clásica, MiTransfer's PIN leaves the vault first, with the fingerprint. Activity stays. */
    override fun confirmRemove() {
        removing = false
        app.launch {
            val book = app.book.value
            if (MiTransfer in book.domain.savedKeys && lastClassic(book)) {
                val result = app.host?.biometric("Quitar la Clásica", cipher = app.controller.keyCipher())
                if (result !is BiometricResult.Passed) return@launch
                app.controller.saveKey(requireNotNull(result.cipher), MiTransfer, null)
            }
            app.controller.deleteClassic(number)
            app.navigator.remove(this)
        }
    }

    private fun lastClassic(book: Book) = book.cards.none { it.bank == Bank.CLASSIC && it.number != number }
}

/**
 * The confirmation of a removal (`68`) while it is open: what it asks (Metropolitano the identity card and the Telebanca
 * card, the Clásica the identity card), and the send. [classic]: the Clásica whose association goes.
 */
private class RemovalForm(private val app: AppModel, private val bank: Bank, private val classic: String? = null) {
    var open by mutableStateOf(false)
    var sending by mutableStateOf(false)
        private set
    private var identity by mutableStateOf("")
    private var telebanca by mutableStateOf("")

    fun ui(): RemovalUi? {
        if (!open) return null
        val identityField = FieldUi(IDENTITY, "Carné de identidad", identity, FieldInput.Number)
        // Its 95 is shown and never typed, as in the registration.
        val telebancaField = FieldUi(
            TELEBANCA, "Tarjeta Telebanca", telebanca.removePrefix(TELEBANCA_PREFIX), FieldInput.Number, shape = FieldShape.Prefix(TELEBANCA_PREFIX),
        )
        return when (bank) {
            Bank.BPA, Bank.BANDEC -> RemovalUi(bank, emptyList(), canSend = true)
            Bank.BANMET -> RemovalUi(bank, listOf(identityField, telebancaField), canSend = identity.length == 11 && telebanca.length == 10)
            Bank.CLASSIC -> RemovalUi(bank, listOf(identityField), canSend = identity.length == 11)
        }
    }

    fun field(id: String, value: String) {
        val digits = value.filter(Char::isDigit)
        when (id) {
            IDENTITY -> identity = digits.take(11)
            TELEBANCA -> telebanca = digits.take(8).let { if (it.isEmpty()) it else TELEBANCA_PREFIX + it }
        }
    }

    fun close() {
        open = false
        identity = ""
        telebanca = ""
    }

    /** Open again with what [sent] carried. */
    fun review(sent: UssdRequest?) {
        when (sent) {
            is RegistrationRemoval -> {
                identity = sent.identity.orEmpty()
                telebanca = sent.card.orEmpty()
            }
            is ClassicRemoval -> identity = sent.identity
            else -> Unit
        }
        open = true
    }

    fun send(onQueued: () -> Unit) {
        if (sending || ui()?.canSend != true) return
        val request = try {
            when {
                classic != null -> ClassicRemoval(classic, identity)
                bank == Bank.BANMET -> RegistrationRemoval(bank.core(), identity, telebanca)
                else -> RegistrationRemoval(bank.core())
            }
        } catch (e: IllegalArgumentException) {
            app.message(e.message ?: "Revisa los datos")
            return
        }
        sending = true
        app.sendLine(request, onStopped = { sending = false }) {
            sending = false
            close()
            onQueued()
        }
    }

    private companion object {
        const val IDENTITY = "identity"
        const val TELEBANCA = "telebanca"
    }
}

/** The new key of a change (`69`), typed twice. It goes to the bank; once the bank takes it, the app keeps it. */
class NewKeyDestination(private val app: AppModel, private val bank: Bank) : Destination(), NewKeyActions {
    private val length = bank.core().pinLength
    private val first = CharArray(length)
    private val again = CharArray(length)
    private var entered by mutableIntStateOf(0)
    private var repeat by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)
    private var sending by mutableStateOf(false)

    @Composable
    override fun Content(app: AppModel, book: Book) {
        NewKeyScreen(NewKeyUi(bank, length, repeat, entered, error, sending), this)
    }

    override fun key(key: KeypadKey) {
        if (sending) return
        when (key) {
            KeypadKey.Backspace -> if (entered > 0) entered--
            is KeypadKey.Digit -> if (entered < length) {
                error = null
                (if (repeat) again else first)[entered] = '0' + key.value
                entered++
                if (entered == length) typed()
            }
            KeypadKey.Decimal -> Unit
        }
    }

    private fun typed() {
        when {
            !repeat -> {
                repeat = true
                entered = 0
            }
            !again.contentEquals(first) -> {
                clear()
                error = "Las claves no coinciden"
            }
            else -> {
                sending = true
                app.sendLine(PinChange(bank.core()), newKey = again, onStopped = { sending = false }) {
                    clear()
                    app.navigator.finish(this, keyChangeRequest(app, bank))
                }
            }
        }
    }

    override fun close() {
        clear()
        app.navigator.remove(this)
    }

    private fun clear() {
        first.fill('\u0000')
        again.fill('\u0000')
        entered = 0
        repeat = false
        sending = false
    }
}

/** What the bank lists under the line (`58`). Asked as soon as it opens when it never was. */
class LineAccountsDestination(private val app: AppModel, private val bank: Bank) : Destination(), LineAccountsActions {
    init {
        if (app.book.value.bankLine(bank).accounts == null) refresh()
    }

    @Composable
    override fun Content(app: AppModel, book: Book) = LineAccountsScreen(book.bankLine(bank), this)

    override fun back() { app.navigator.remove(this) }

    override fun refresh() {
        if (app.book.value.bankLine(bank).accounts?.state == LineStateUi.Sent) return
        app.sendLine(AccountsQuery(bank.core())) {}
    }

    override fun retry() {
        app.book.value.bankLine(bank).accounts?.let { app.retry(it.id) }
    }

    override fun add(number: String) {
        val account = app.book.value.bankLine(bank).accountList.firstOrNull { it.addable == number } ?: return
        app.launch {
            try {
                app.controller.saveCard(WalletCard(OwnCard(number, bank.core(), Currency.valueOf(account.balance.currency))))
            } catch (e: IllegalArgumentException) {
                app.message(e.message ?: "No se pudo añadir la tarjeta")
            }
        }
    }
}

/**
 * The card the line operates with (`60`), [sent] already typed when a refused one is corrected. Metropolitano's cell is
 * picked at random, as Transfermóvil does, once per screen; its value is typed for this send only.
 */
class CardAssociationDestination(private val app: AppModel, private val bank: Bank, sent: String = "") : Destination(), CardAssociationActions {
    private val cell = if (bank == Bank.BANMET) CardAssociation.randomCoordinate() else null
    private var number by mutableStateOf(sent)
    private var value by mutableStateOf("")
    private var secret: CharArray? = null
    private var sending by mutableStateOf(false)

    @Composable
    override fun Content(app: AppModel, book: Book) = CardAssociationScreen(ui(), this, sending = sending)

    private fun ui(): CardAssociationUi {
        val card = associationCard(number, bank)
        return CardAssociationUi(bank, card, cell, value, canSend = number.length == 16 && card.error == null && (cell == null || value.length == 2))
    }

    override fun number(value: String) { number = value.filter(Char::isDigit).take(16) }
    override fun value(value: String) { this.value = value.trim().take(2) }

    override fun scan() = app.navigator.push(ScanDestination(app, ScanMode.NUMBERS) { result ->
        if (result is ScanResult.CardNumber) number(result.digits)
    })

    override fun send() {
        if (sending || !ui().canSend) return
        val typed = cell?.let { value.toCharArray() }
        secret?.fill('\u0000')
        secret = typed
        sending = true
        app.sendLine(CardAssociation(bank.core(), number, cell), secret = typed, onStopped = { sending = false }) {
            wipe()
            app.navigator.finish(this, associationRequest(app, bank))
        }
    }

    override fun close() {
        wipe()
        app.navigator.remove(this)
    }

    private fun wipe() {
        secret?.fill('\u0000')
        secret = null
        value = ""
        sending = false
    }
}

/** The card typed for [bank]: what its number tells, a bad check digit, or a card of another bank. */
private fun associationCard(number: String, bank: Bank): CardFieldUi {
    val issuer = issuerBank(number)
    val error = when {
        number.length < 16 -> null
        !luhn(number) -> "Algún dígito está mal"
        issuer != null && issuer != bank -> "No es una tarjeta de ${bank.shortName}"
        else -> null
    }
    return CardFieldUi(number, "", issuer, Cards.issuer(number).currency?.name, error)
}

/** Where the last key change of [bank] stands. */
internal fun keyChangeRequest(app: AppModel, bank: Bank) = LineRequestDestination(app, bank, LineService.KeyChange)

/** Where the last association with [bank] stands. */
internal fun associationRequest(app: AppModel, bank: Bank) = LineRequestDestination(app, bank, LineService.Association)

/** Where the last removal of the line's registration with [bank] stands. */
internal fun removalRequest(app: AppModel, bank: Bank) = LineRequestDestination(app, bank, LineService.Removal)

/** Where the last removal of the Clásica [number]'s association stands. */
internal fun classicRemovalRequest(app: AppModel, number: String) = LineRequestDestination(app, Bank.CLASSIC, LineService.Removal, classic = number)

/** The last operations of the Clásica [number] (`48`): MiTransfer's text once it comes. */
internal fun classicHistoryRequest(app: AppModel, number: String) = LineRequestDestination(app, Bank.CLASSIC, LineService.History, classic = number)

/**
 * Where the last request of a [service] of the line stands, following it live: a retry is the service's newest request.
 * [classic]: the Clásica whose association or last operations are the request. The closing button names where it goes:
 * the bank's or the Clásica's settings, or the tab under the last operations.
 */
class LineRequestDestination(
    private val app: AppModel,
    private val bank: Bank,
    private val service: LineService,
    private val classic: String? = null,
) : Destination(), LineRequestActions, Follows {
    /** «Guardar la clave nueva» while the saved keys were locked: once the fingerprint unlocks them, the key is saved. */
    private var saveWhenUnlocked = false

    override fun follows(id: String) = request(app.book.value)?.id == id

    @Composable
    override fun Content(app: AppModel, book: Book) {
        val request = request(book) ?: return
        val card = (book.operationOf(request.id)?.operation?.request as? CardAssociation)?.card
        LineRequestScreen(bank, service, request, book.keyToSave(bank).takeIf { service == LineService.KeyChange }, backLabel(), this, card = card)
        val locked = book.domain.keysLocked
        LaunchedEffect(locked) {
            if (!locked && saveWhenUnlocked) {
                saveWhenUnlocked = false
                saveKey()
            }
        }
    }

    private fun request(book: Book): LineRequestUi? = when {
        service == LineService.History -> classic?.let(book::classicHistory)
        classic != null -> book.classicSettings(classic)?.removal
        else -> book.bankLine(bank).let { line ->
            when (service) {
                LineService.KeyChange -> line.keyChange
                LineService.Association -> line.association
                LineService.Removal -> line.removal
                LineService.History -> null
            }
        }
    }

    private fun backLabel(): String = when {
        service == LineService.History -> when (app.tab) {
            Tab.Home -> "Volver al inicio"
            Tab.Activity -> "Volver a Actividad"
            Tab.Contacts -> "Volver a Contactos"
            Tab.Services -> "Volver a Servicios"
        }
        classic != null -> "Volver a la Clásica"
        else -> "Volver a ${bank.shortName}"
    }

    override fun close() { app.navigator.remove(this) }

    /** To the settings it names: the screen under this one when it is them, otherwise opened in its place. The last operations sit on a tab. */
    override fun back() {
        if (service == LineService.History || settingsBeneath() != null) app.navigator.remove(this) else app.navigator.finish(this, settings())
    }

    /** What was typed for it (a new key, a coordinate's value) is gone: those are typed again; the rest goes as it was. */
    override fun retry() {
        val request = request(app.book.value) ?: return
        when {
            service == LineService.KeyChange -> app.navigator.finish(this, NewKeyDestination(app, bank))
            service == LineService.Association && bank == Bank.BANMET -> fix()
            else -> app.retry(request.id)
        }
    }

    /** A refused key gives way to another; refused data come back in their form, filled in as they were sent. */
    override fun fix() {
        val request = request(app.book.value) ?: return
        val sent = app.book.value.operationOf(request.id)?.operation?.request
        when (service) {
            LineService.KeyChange -> app.navigator.finish(this, NewKeyDestination(app, bank))
            LineService.Association -> app.navigator.finish(this, CardAssociationDestination(app, bank, (sent as? CardAssociation)?.card.orEmpty()))
            LineService.Removal -> {
                val settings = settingsBeneath()?.also { app.navigator.remove(this) } ?: settings().also { app.navigator.finish(this, it) }
                when (settings) {
                    is BankSettingsDestination -> settings.reviewRemoval(sent)
                    is ClassicSettingsDestination -> settings.reviewRemoval(sent)
                }
            }
            LineService.History -> Unit
        }
    }

    /** Saved, the request is settled: back where it was opened from. Locked keys are unlocked with the fingerprint first. */
    override fun saveKey() {
        if (app.book.value.domain.keysLocked) {
            saveWhenUnlocked = true
            app.unlock()
            return
        }
        app.book.value.keyToSave(bank)?.let { saveKeyToSave(app, bank, it) { app.navigator.remove(this) } }
    }

    override fun discardKey() = app.discardReceivedKey(bank)

    private fun settings(): Destination = if (classic != null) ClassicSettingsDestination(app, classic) else BankSettingsDestination(app, bank)

    private fun settingsBeneath(): Destination? {
        val stack = app.navigator.stack
        return stack.getOrNull(stack.indexOf(this) - 1)?.takeIf {
            if (classic != null) (it as? ClassicSettingsDestination)?.number == classic else (it as? BankSettingsDestination)?.bank == bank
        }
    }
}

/**
 * A key the bank or MiTransfer took: saved with the fingerprint when the app has it in hand, typed otherwise; [saved]
 * then, and a message says so. Unlocked without the saved keys, the fingerprint unlocks the app first.
 */
private fun saveKeyToSave(app: AppModel, bank: Bank, key: KeyToSaveUi, saved: () -> Unit = {}) {
    val done = {
        saved()
        app.message(if (bank == Bank.CLASSIC) "PIN nuevo guardado" else "Clave nueva guardada")
    }
    when {
        app.book.value.domain.keysLocked -> app.unlock()
        key.inHand -> app.launch { if (app.saveReceivedKey(bank)) done() }
        else -> app.navigator.push(BankKeyDestination(app, bank) {
            app.navigator.remove(it)
            done()
        })
    }
}

/** The saved key, or MiTransfer's PIN, typed again; unlocked without the saved keys, the fingerprint unlocks the app first. */
private fun typeKey(app: AppModel, bank: Bank) {
    if (app.book.value.domain.keysLocked) app.unlock()
    else app.navigator.push(BankKeyDestination(app, bank) { app.navigator.remove(it) })
}

/**
 * Queues a service of the line. A missing key is asked for first and the send goes after it, each time with fresh copies
 * of what was typed ([secret], [newKey]), which stay the caller's to wipe. [onQueued] once it is queued; [onStopped]
 * when it is not: refused with a message, or waiting for the key.
 */
private fun AppModel.sendLine(
    request: UssdRequest,
    secret: CharArray? = null,
    newKey: CharArray? = null,
    onStopped: () -> Unit = {},
    onQueued: () -> Unit,
) = query {
    val result = runCatching { controller.submit(request, secret = secret?.copyOf(), newKey = newKey?.copyOf()) }
        .onFailure { onStopped() }
        .getOrThrow()
    if (result is Submission.Queued) onQueued() else onStopped()
    result
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
