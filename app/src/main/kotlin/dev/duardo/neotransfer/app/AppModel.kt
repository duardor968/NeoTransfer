package dev.duardo.neotransfer.app

import android.Manifest
import android.app.Application
import android.app.UiModeManager
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.duardo.neotransfer.core.ledger.OperationStatus
import dev.duardo.neotransfer.core.model.Bank as CoreBank
import dev.duardo.neotransfer.core.model.Contact
import dev.duardo.neotransfer.core.protocol.UssdRequest
import dev.duardo.neotransfer.core.qr.QrCode
import dev.duardo.neotransfer.core.qr.QrContent
import dev.duardo.neotransfer.data.Settings
import dev.duardo.neotransfer.data.Theme
import dev.duardo.neotransfer.engine.AppController
import dev.duardo.neotransfer.engine.Routes
import dev.duardo.neotransfer.engine.Submission
import dev.duardo.neotransfer.engine.controller
import dev.duardo.neotransfer.platform.BiometricResult
import dev.duardo.neotransfer.ui.model.DuplicateUi
import dev.duardo.neotransfer.ui.model.LockUi
import dev.duardo.neotransfer.ui.model.PermissionKind
import dev.duardo.neotransfer.ui.model.PermissionsUi
import dev.duardo.neotransfer.ui.shell.Tab
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

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
            controller.services, controller.savedKeys, controller.blockedBanks, controller.settings, controller.locked, controller.lastBackup),
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        Domain(
            loaded = values[0] as Boolean,
            cards = values[1] as List<dev.duardo.neotransfer.engine.CardView>,
            operations = values[2] as List<OperationStatus>,
            movements = values[3] as List<dev.duardo.neotransfer.core.ledger.Movement>,
            contacts = values[4] as List<dev.duardo.neotransfer.core.model.Contact>,
            services = values[5] as List<dev.duardo.neotransfer.core.model.ServiceReference>,
            savedKeys = values[6] as Set<CoreBank>,
            blockedBanks = values[7] as Set<CoreBank>,
            settings = values[8] as Settings,
            locked = values[9] as Boolean,
            lastBackup = values[10] as java.time.Instant?,
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
        if (domain.cards.isEmpty()) navigator.reset(BankChoiceDestination(this, setup = true))
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
            is Pending.Item -> navigator.push(OperationDestination(this, next.id))
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
     * Queues [request] and shows its live result in place of the flow that began at [flow]. An identical payment
     * still pending comes back to be asked about; a missing or rejected bank key is asked for first.
     */
    suspend fun submit(
        request: UssdRequest,
        flow: Destination,
        confirmed: Boolean = false,
        transferKey: CharArray? = null,
        onQueued: () -> Unit = {},
    ): DuplicateUi? {
        val result = try {
            controller.submit(request, confirmed, transferKey?.copyOf())
        } catch (e: IllegalArgumentException) {
            message(e.message ?: "No se pudo enviar")
            return null
        } catch (e: IllegalStateException) {
            message(e.message ?: "No se pudo enviar")
            return null
        }
        when (result) {
            is Submission.Queued -> {
                navigator.finish(flow, ResultDestination(this, result.id))
                onQueued()
            }
            is Submission.ConfirmDuplicate -> return duplicate(result.pending)
            is Submission.KeyRequired -> askKey(result.bank) { viewModelScope.launch { submit(request, flow, confirmed, transferKey, onQueued) } }
        }
        return null
    }

    /** Runs a balance or history query; a missing key is asked for and the query goes after it. */
    fun query(run: suspend () -> Submission) {
        viewModelScope.launch {
            when (val result = runCatching { run() }.getOrElse { message(it.message ?: "No se pudo consultar"); return@launch }) {
                is Submission.KeyRequired -> askKey(result.bank) { query(run) }
                else -> Unit
            }
        }
    }

    fun retry(id: String) {
        val request = book.value.operationOf(id)?.operation?.request
        if (request is dev.duardo.neotransfer.core.protocol.CubacelTransfer) {
            navigator.push(ServiceDestination(this, dev.duardo.neotransfer.ui.model.ServiceKind.Cubacel, retry = request))
            return
        }
        viewModelScope.launch {
            when (val result = runCatching { controller.retry(id) }.getOrElse { message(it.message ?: "No se pudo reintentar"); return@launch }) {
                is Submission.KeyRequired -> askKey(result.bank) { retry(id) }
                else -> Unit
            }
        }
    }

    private fun askKey(bank: CoreBank, then: () -> Unit) {
        navigator.push(BankKeyDestination(this, bank.ui(), rejected = bank in domain.blockedBanks) { destination ->
            navigator.remove(destination)
            then()
        })
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

    /** A problem in a few words, at the bottom of the screen. */
    fun message(text: String) {
        viewModelScope.launch { messages.showSnackbar(text) }
    }

    fun launch(block: suspend () -> Unit): Job = viewModelScope.launch { block() }

    /** One contact picked from the phone and kept; null when the person backs out or it fails (already said). */
    suspend fun importPhoneContact(): Contact? {
        val host = host ?: return null
        if (!granted(Manifest.permission.READ_CONTACTS) && !host.requestPermission(Manifest.permission.READ_CONTACTS)) return null
        val uri = host.pickContact() ?: return null
        return runCatching { controller.importPhoneContact(uri.toString()) }
            .onFailure { message(it.message ?: "No se pudo importar el contacto") }
            .getOrNull()
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
