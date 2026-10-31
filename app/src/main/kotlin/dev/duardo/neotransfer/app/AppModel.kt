package dev.duardo.neotransfer.app

import android.Manifest
import android.app.Application
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.duardo.neotransfer.core.ledger.OperationStatus
import dev.duardo.neotransfer.core.model.Agency
import dev.duardo.neotransfer.core.protocol.AccountsQuery
import dev.duardo.neotransfer.core.protocol.BalanceQuery
import dev.duardo.neotransfer.core.protocol.BankRegistration
import dev.duardo.neotransfer.core.protocol.CardAssociation
import dev.duardo.neotransfer.core.protocol.ClassicAssociation
import dev.duardo.neotransfer.core.protocol.ClassicBalanceQuery
import dev.duardo.neotransfer.core.protocol.ClassicHistoryQuery
import dev.duardo.neotransfer.core.protocol.ClassicRemoval
import dev.duardo.neotransfer.core.protocol.CubacelQuery
import dev.duardo.neotransfer.core.protocol.HistoryQuery
import dev.duardo.neotransfer.core.protocol.MiTransferRegistration
import dev.duardo.neotransfer.core.protocol.PinChange
import dev.duardo.neotransfer.core.protocol.RegistrationRemoval
import dev.duardo.neotransfer.core.protocol.UssdRequest
import dev.duardo.neotransfer.core.qr.QrCode
import dev.duardo.neotransfer.core.qr.QrContent
import dev.duardo.neotransfer.data.Settings
import dev.duardo.neotransfer.data.Theme
import dev.duardo.neotransfer.data.ClassicCard
import dev.duardo.neotransfer.engine.AppController
import dev.duardo.neotransfer.engine.KeyToSave
import dev.duardo.neotransfer.engine.Routes
import dev.duardo.neotransfer.engine.Submission
import dev.duardo.neotransfer.engine.controller
import dev.duardo.neotransfer.platform.BiometricResult
import dev.duardo.neotransfer.ui.model.Bank
import dev.duardo.neotransfer.ui.model.DuplicateUi
import dev.duardo.neotransfer.ui.model.LockUi
import dev.duardo.neotransfer.ui.model.PermissionKind
import dev.duardo.neotransfer.ui.model.PermissionsUi
import dev.duardo.neotransfer.ui.shell.Tab
import java.time.LocalDate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The app's single view model: the controller's state as one [Book], the back stack, the lock and the
 * permission gate. Screens keep their own state in their [Destination]; what outlives them lives here.
 */
class AppModel @JvmOverloads constructor(
    application: Application,
    val controller: AppController = application.controller,
    /** Where the domain is turned into a [Book]; tests run it with the controller's own dispatcher. */
    compute: CoroutineDispatcher = Dispatchers.Default,
) : AndroidViewModel(application) {

    /** The activity while it exists; system dialogs go through it. */
    var host: Host? = null

    val book: StateFlow<Book> = combine(
        listOf(controller.loaded, controller.cards, controller.operations, controller.movements, controller.contacts,
            controller.services, controller.savedKeys, controller.blocked, controller.settings, controller.locked, controller.lastBackup,
            controller.addressBook, controller.classics, controller.keysToSave, controller.keysLocked, controller.peerActivity),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        Domain(
            loaded = values[0] as Boolean,
            cards = values[1] as List<dev.duardo.neotransfer.engine.CardView>,
            operations = values[2] as List<OperationStatus>,
            movements = values[3] as List<dev.duardo.neotransfer.core.ledger.Movement>,
            contacts = values[4] as List<dev.duardo.neotransfer.core.model.Contact>,
            services = values[5] as List<dev.duardo.neotransfer.core.model.ServiceReference>,
            savedKeys = values[6] as Set<Agency>,
            blocked = values[7] as Set<Agency>,
            settings = values[8] as Settings,
            locked = values[9] as Boolean,
            lastBackup = values[10] as java.time.Instant?,
            addressBook = values[11] as Set<String>?,
            classics = values[12] as List<ClassicCard>,
            keysToSave = values[13] as List<KeyToSave>,
            keysLocked = values[14] as Boolean,
            peer = values[15] as List<dev.duardo.neotransfer.core.ledger.ActivityRow>?,
        )
    }.map { Book(it) }.flowOn(compute).stateIn(viewModelScope, SharingStarted.Eagerly, Book(Domain()))

    private val domain: Domain get() = book.value.domain

    val navigator = Navigator(Main(this))
    var tab by mutableStateOf(Tab.Home)
    /** The card in hand on Home; its number. */
    var homeCard by mutableStateOf<String?>(null)
    val activity = ActivityState(this)
    var contactsQuery by mutableStateOf("")
    val messages = SnackbarHostState()
    /** Counts returns to the foreground: screens that show system state (permissions, notifications) read it again. */
    var resumed by mutableIntStateOf(0)
        private set
    val version: String = application.packageManager.getPackageInfo(application.packageName, 0).versionName.orEmpty()

    var lock by mutableStateOf<LockUi>(LockUi.Prompting)
        private set
    private var prompting: Job? = null

    /** Shown instead of the app while permissions are asked or missing; null once settled. */
    var permissions by mutableStateOf<PermissionsUi?>(null)
        private set
    private var askedPermissions = false

    /** Set once the app shows its screens for the first time in this process. */
    var entered by mutableStateOf(false)
        private set
    private var pending: Pending? = null

    init {
        // Nothing to protect yet (no card, no key): the lock stays out of the way until a bank is set up.
        viewModelScope.launch {
            book.collect { book ->
                val d = book.domain
                if (d.loaded && d.locked && !d.configured) controller.unlock(null)
                if (d.loaded) syncNightMode(d.settings.theme)
            }
        }
    }

    // ---- Lifecycle, lock and permissions ----

    /** The activity came to the foreground: the fingerprint prompt goes up by itself when the app is locked. */
    fun onStart() {
        if (domain.loaded && domain.locked && domain.configured) unlock()
    }

    fun onResume() {
        resumed++
        if (permissions != null) checkPermissions()
    }

    /** The fingerprint prompt that unlocks the app; with [Domain.keysLocked] it is also what brings the saved keys back. */
    fun unlock() {
        if (prompting?.isActive == true) return
        val host = host ?: return
        prompting = viewModelScope.launch {
            lock = LockUi.Prompting
            val cipher = runCatching { controller.unlockCipher() }.getOrNull()
            when (val result = host.biometric("Desbloquear NeoTransfer", cipher = cipher)) {
                is BiometricResult.Passed -> runCatching { controller.unlock(result.cipher) }.onFailure { controller.unlock(null) }
                BiometricResult.Unavailable -> controller.unlock(null)
                BiometricResult.LockedOut -> {
                    for (seconds in LOCKOUT_SECONDS downTo 1) {
                        lock = LockUi.LockedOut(seconds)
                        delay(1_000)
                    }
                    lock = LockUi.Cancelled
                }
                BiometricResult.Cancelled, is BiometricResult.Failed -> lock = LockUi.Cancelled
            }
        }
    }

    /**
     * Runtime permissions: on the first start, or while SMS and phone are missing, everything still missing is
     * asked at once; what stays denied is listed afterwards.
     */
    fun checkPermissions() {
        val missing = missingPermissions()
        val essential = missing.any { it in ESSENTIAL }
        when {
            missing.isEmpty() -> permissions = null
            permissions != null -> permissions = PermissionsUi(missing.kinds())
            !askedPermissions && (essential || !domain.configured) -> {
                askedPermissions = true
                permissions = PermissionsUi(emptyList())
                host?.requestPermissions(missing)
            }
            essential -> permissions = PermissionsUi(missing.kinds())
        }
    }

    /** What to show instead of the app now: the backdrop while the first request is still to go out, or what is missing. */
    fun permissionGate(): PermissionsUi? {
        permissions?.let { return it }
        val missing = missingPermissions()
        val ask = !askedPermissions && missing.isNotEmpty() && (missing.any { it in ESSENTIAL } || !domain.configured)
        return if (ask) PermissionsUi(emptyList()) else null
    }

    fun onPermissionsResult() {
        val missing = missingPermissions()
        permissions = if (missing.isEmpty()) null else PermissionsUi(missing.kinds())
        controller.onPermissionsChanged()
    }

    fun grantPermissions() {
        val host = host ?: return
        val missing = missingPermissions()
        if (missing.any(host::shouldExplain)) host.requestPermissions(missing) else host.openAppSettings()
    }

    fun skipPermissions() {
        if (missingPermissions().none { it in ESSENTIAL }) permissions = null
    }

    fun granted(permission: String) = getApplication<Application>().checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun missingPermissions() = ALL_PERMISSIONS.filterNot(::granted)

    private fun List<String>.kinds() = mapNotNull { permission ->
        when (permission) {
            Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS -> PermissionKind.Sms
            Manifest.permission.CALL_PHONE -> PermissionKind.Phone
            Manifest.permission.READ_CONTACTS -> PermissionKind.Contacts
            Manifest.permission.CAMERA -> PermissionKind.Camera
            Manifest.permission.POST_NOTIFICATIONS -> PermissionKind.Notifications
            else -> null
        }
    }.distinct()

    // ---- Entering the app and links from outside ----

    /** First screens of this process: setting up a bank when the wallet is empty, Home otherwise. */
    fun enter() {
        if (entered) return
        entered = true
        if (domain.cards.isEmpty() && domain.classics.isEmpty()) navigator.reset(BankChoiceDestination(this, setup = true))
        applyPending()
    }

    /** Launcher shortcut, Quick Settings tile, notification or payment link. */
    fun open(intent: Intent) {
        val route = intent.getStringExtra(Routes.EXTRA)
        val link = intent.dataString?.takeIf { intent.action == Intent.ACTION_VIEW }
        pending = when {
            route == Routes.SCANNER -> Pending.Scan
            route != null && route.startsWith("operation/") -> Pending.Item(route.substringAfter('/'))
            route != null && route.startsWith("movement/") -> Pending.Item(route.substringAfter('/'))
            link != null -> Pending.Link(link)
            else -> return
        }
        intent.removeExtra(Routes.EXTRA)
        intent.data = null
        if (entered) applyPending()
    }

    private fun applyPending() {
        val next = pending ?: return
        pending = null
        when (next) {
            Pending.Scan -> navigator.push(PayDestination(this))
            is Pending.Item -> viewModelScope.launch { show(next.id) }
            is Pending.Link -> navigator.push(
                runCatching { QrCode.parse(next.link, LocalDate.now()) }.fold(
                    onSuccess = { content ->
                        when (content) {
                            is QrContent.Payment -> PayDestination(this, content.payment)
                            is QrContent.Card -> PayDestination(this, unsupported = "Este código es de una tarjeta, no de un pago")
                        }
                    },
                    onFailure = { PayDestination(this, unsupported = it.message ?: "Código no compatible") },
                ),
            )
        }
    }

    private sealed interface Pending {
        data object Scan : Pending
        data class Item(val id: String) : Pending
        data class Link(val link: String) : Pending
    }

    // ---- Sending ----

    /** The fingerprint confirms what was reviewed. Without a fingerprint on the phone there is nothing to ask. */
    suspend fun authorize(title: String, subtitle: String?): Boolean = when (val result = host?.biometric(title, subtitle)) {
        is BiometricResult.Passed, BiometricResult.Unavailable -> true
        BiometricResult.LockedOut -> false.also { message("Demasiados intentos con la huella. Prueba en un momento") }
        is BiometricResult.Failed -> false.also { message(result.message) }
        BiometricResult.Cancelled, null -> false
    }

    /**
     * Queues [request] and shows it live in place of the flow that began at [flow], where its notification leads
     * ([show]): money as its result, a service of the line where it stands. An identical payment still pending comes
     * back to be asked about; a missing or rejected key is asked for first. [secret] and [newKey] are what the person
     * typed for this request ([AppController.submit]); they are copied, so the caller wipes its own once [onQueued].
     */
    suspend fun submit(
        request: UssdRequest,
        flow: Destination,
        confirmed: Boolean = false,
        secret: CharArray? = null,
        newKey: CharArray? = null,
        onQueued: () -> Unit = {},
    ): DuplicateUi? {
        val result = try {
            controller.submit(request, confirmed, secret?.copyOf(), newKey?.copyOf())
        } catch (e: IllegalArgumentException) {
            message(e.message ?: "No se pudo enviar")
            return null
        } catch (e: IllegalStateException) {
            message(e.message ?: "No se pudo enviar")
            return null
        }
        when (result) {
            is Submission.Queued -> {
                show(result.id, live = true, replacing = flow)
                onQueued()
            }
            is Submission.ConfirmDuplicate -> return duplicate(result.pending)
            is Submission.KeyRequired -> askKey(result.agency) { viewModelScope.launch { submit(request, flow, confirmed, secret, newKey, onQueued) } }
        }
        return null
    }

    /**
     * Sends without leaving the screen, which follows the answer itself (a balance or history query); a missing key is
     * asked for and [run] goes again after it. [submit] is the send that moves to where the request is shown.
     */
    fun query(run: suspend () -> Submission) {
        viewModelScope.launch {
            when (val result = runCatching { run() }.getOrElse { message(it.message ?: "No se pudo consultar"); return@launch }) {
                is Submission.KeyRequired -> askKey(result.agency) { query(run) }
                else -> Unit
            }
        }
    }

    /**
     * Opens what operation or movement [id] is, where the app shows it, in place of the flow that began at [replacing]
     * or, without one, on top: money as its receipt, or as its live result when [live]; a service of the line where it
     * stands; a registration or a Clásica's association on its own screen with the answer; a balance or history query on
     * Home or in Activity. Waits until the book holds it; a screen already following it ([Follows]) stays; what the app
     * no longer has leaves a message where the person was.
     */
    suspend fun show(id: String, live: Boolean = false, replacing: Destination? = null) {
        val known = controller.operations.value.any { it.operation.id == id } || controller.movements.value.any { it.id == id }
        val held = if (known) book.first { it.item(id) != null || it.operationOf(id) != null } else book.value
        if ((navigator.top as? Follows)?.follows(id) == true) return
        if (held.item(id) != null) return go(if (live) ResultDestination(this, id) else OperationDestination(this, id), replacing)
        when (val request = held.operationOf(id)?.operation?.request) {
            is PinChange -> go(keyChangeRequest(this, request.bank.ui()), replacing)
            is CardAssociation -> go(associationRequest(this, request.bank.ui()), replacing)
            // «Reintentar» goes back to Ajustes › banco, where the removal is confirmed again.
            is RegistrationRemoval -> go(removalRequest(this, request.bank.ui()), replacing)
            is AccountsQuery -> go(LineAccountsDestination(this, request.bank.ui()), replacing)
            is ClassicRemoval -> if (held.classic(request.card) == null) gone("Esa Clásica ya no está en la app", replacing)
                else go(classicRemovalRequest(this, request.card), replacing)
            is BankRegistration, is MiTransferRegistration, is ClassicAssociation -> go(setup(held, id, request), replacing)
            is BalanceQuery -> showTab(Tab.Home, held.sourceCard(request)?.id ?: held.cards.firstOrNull { it.bank == request.bank.ui() }?.id)
            is ClassicBalanceQuery -> showTab(Tab.Home, request.card)
            is HistoryQuery -> showTab(Tab.Activity)
            // MiTransfer answers in text, which has a screen of its own over Activity.
            is ClassicHistoryQuery -> if (held.classic(request.card) == null) gone("Esa Clásica ya no está en la app", replacing) else {
                showTab(Tab.Activity)
                navigator.push(classicHistoryRequest(this, request.card))
            }
            is CubacelQuery -> go(CubacelDestination(this), replacing)
            else -> gone("Esa operación ya no está en la app", replacing)
        }
    }

    /**
     * The screen of a registration (`49`) or a Clásica's association (`111`) [id], with its answer. Going on from there
     * continues the setup as from the setup itself: the first one when nothing is set up yet.
     */
    private fun setup(book: Book, id: String, request: UssdRequest): Destination {
        val first = book.cards.isEmpty()
        return when (request) {
            is BankRegistration -> request.bank.ui().let { bank ->
                BankRegistrationDestination(this, bank, id) { navigator.finish(it, CardSetupDestination(this, bank, first, null)) }
            }
            is MiTransferRegistration -> ClassicRegisterDestination(this, id) { navigator.finish(it, ClassicAssociateDestination(this, first, null)) }
            else -> ClassicAssociateDestination(this, first, null, id)
        }
    }

    private fun go(destination: Destination, replacing: Destination?) =
        if (replacing != null) navigator.finish(replacing, destination) else navigator.push(destination)

    /** [tab] with nothing over it; Home with [card] in hand when given. */
    private fun showTab(tab: Tab, card: String? = null) {
        navigator.popToRoot()
        card?.let { homeCard = it }
        this.tab = tab
    }

    private fun gone(text: String, replacing: Destination?) {
        replacing?.let(navigator::remove)
        message(text)
    }

    fun retry(id: String) {
        val request = book.value.operationOf(id)?.operation?.request
        if (request is dev.duardo.neotransfer.core.protocol.CubacelTransfer) {
            navigator.push(ServiceDestination(this, dev.duardo.neotransfer.ui.model.ServiceKind.Cubacel, retry = request))
            return
        }
        viewModelScope.launch {
            when (val result = runCatching { controller.retry(id) }.getOrElse { message(it.message ?: "No se pudo reintentar"); return@launch }) {
                is Submission.KeyRequired -> askKey(result.agency) { retry(id) }
                else -> Unit
            }
        }
    }

    /** A key that came by itself is saved with the fingerprint alone; any other is typed. Then [then] goes on. */
    private fun askKey(agency: Agency, then: () -> Unit) {
        if (domain.keysToSave.any { it.agency == agency && it.inHand }) {
            viewModelScope.launch { if (saveReceivedKey(agency.ui())) then() }
            return
        }
        navigator.push(BankKeyDestination(this, agency.ui(), rejected = agency in domain.blocked) { destination ->
            navigator.remove(destination)
            then()
        })
    }

    /**
     * Saves with the fingerprint the key a registration or a confirmed change of [bank] brought ([Book.keysToSave]); a
     * [Bank.CLASSIC] is MiTransfer's PIN. False, with a message, when it was not saved: then the person types it.
     */
    suspend fun saveReceivedKey(bank: Bank): Boolean {
        val cipher = runCatching { controller.keyCipher() }.getOrElse { message(it.message ?: "No se pudo guardar la clave"); return false }
        return when (val result = host?.biometric(bank.saveKeyTitle, cipher = cipher)) {
            is BiometricResult.Passed -> runCatching { controller.saveReceivedKey(requireNotNull(result.cipher), bank.agency()) }
                .onFailure { message(it.message ?: "No se pudo guardar la clave") }.isSuccess
            BiometricResult.Unavailable -> false.also { message("Configura una huella en el teléfono para guardar la clave") }
            BiometricResult.LockedOut -> false.also { message("Demasiados intentos con la huella. Prueba en un momento") }
            is BiometricResult.Failed -> false.also { message(result.message) }
            BiometricResult.Cancelled, null -> false
        }
    }

    /**
     * Drops the key in [Book.keysToSave] for [bank] without saving it ("no la guardo"): operations go on with the key
     * saved before, if there is one.
     */
    fun discardReceivedKey(bank: Bank) {
        viewModelScope.launch {
            runCatching { controller.discardReceivedKey(bank.agency()) }.onFailure { message(it.message ?: "No se pudo descartar la clave") }
        }
    }

    private fun duplicate(pending: OperationStatus): DuplicateUi {
        val item = book.value.item(pending.operation.id)
        return DuplicateUi(item?.amount ?: dev.duardo.neotransfer.ui.model.Money(java.math.BigDecimal.ZERO, "CUP"), item?.title.orEmpty(), pending.operation.createdAt)
    }

    // ---- Settings and feedback ----

    fun setTheme(theme: Theme) {
        viewModelScope.launch { controller.updateSettings(domain.settings.copy(theme = theme)) }
    }

    private var syncedTheme: Theme? = null

    /** The system draws the splash and window background from this, so reopening never flashes the other theme. */
    private fun syncNightMode(theme: Theme) {
        if (theme == syncedTheme) return
        syncedTheme = theme
        getApplication<Application>().getSystemService(UiModeManager::class.java).setApplicationNightMode(
            when (theme) {
                Theme.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
                Theme.LIGHT -> UiModeManager.MODE_NIGHT_NO
                Theme.DARK -> UiModeManager.MODE_NIGHT_YES
            },
        )
    }

    /** A few words at the bottom of the screen: a problem, or what just happened with the [action] that undoes it. */
    fun message(text: String, action: String? = null, onAction: () -> Unit = {}) {
        viewModelScope.launch {
            val result = messages.showSnackbar(text, actionLabel = action, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }

    fun launch(block: suspend () -> Unit): Job = viewModelScope.launch { block() }

    /** Asks for the address book; refused for good, the app's settings page is where it is given. */
    fun allowContacts() {
        launch {
            val host = host ?: return@launch
            if (host.shouldExplain(Manifest.permission.READ_CONTACTS) || !granted(Manifest.permission.READ_CONTACTS)) {
                if (host.requestPermission(Manifest.permission.READ_CONTACTS)) controller.onPermissionsChanged() else host.openAppSettings()
            }
        }
    }

    companion object {
        private const val LOCKOUT_SECONDS = 30
        private val ESSENTIAL = setOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.CALL_PHONE)
        val ALL_PERMISSIONS = listOf(
            Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.CALL_PHONE,
            Manifest.permission.CAMERA, Manifest.permission.READ_CONTACTS, Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}
