package dev.duardo.neotransfer

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import dev.duardo.neotransfer.core.*
import dev.duardo.neotransfer.core.fuel.FuelEnvelopeProtector
import dev.duardo.neotransfer.fuel.FuelSecretStore
import dev.duardo.neotransfer.platform.*
import dev.duardo.neotransfer.data.*
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.util.concurrent.Executors

class NeoTransferApplication : Application() {
    val wallet by lazy { WalletCoordinator(this, notificationDelivery = { receipt, state -> notifications.deliver(receipt, state) }) }
    val notifications by lazy { TransferNotifications(this) }
    val fuelSecrets by lazy { FuelSecretStore(this) }
    val controller by lazy { BankController(this, wallet = wallet, fuelProtector = fuelSecrets) }
    val accessSession = AccessSession(android.os.SystemClock::elapsedRealtime)
    private val accessHandler = Handler(Looper.getMainLooper())
    private val expireAccess = Runnable {
        if (accessSession.shouldLockInBackground()) controller.lock()
    }

    fun enterForeground() {
        accessHandler.removeCallbacks(expireAccess)
        if (accessSession.enterForeground()) controller.lock()
        controller.resumeForeground()
    }

    fun leaveForeground() {
        accessSession.leaveForeground()
        controller.suspendForeground()
        if (!accessHandler.hasCallbacks(expireAccess)) accessHandler.postDelayed(expireAccess, 60_000)
    }
}

data class SimChoice(val id: Int, val label: String)
class HistoryEntry(val record: BankSmsRecord, val message: BankMessage)

class BankController(
    private val context: Context,
    private val gateway: UssdTransport = UssdGateway(context),
    private val readInbox: () -> List<BankSmsRecord> = { BankSmsInbox(context).read() },
    private val listSims: () -> List<SimChoice> = {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) emptyList()
        else try {
            context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
                .map { SimChoice(it.subscriptionId, "SIM ${it.simSlotIndex + 1} · ${it.carrierName}") }
        } catch (_: SecurityException) { emptyList() }
    },
    private val now: () -> Instant = Instant::now,
    val wallet: WalletCoordinator? = null,
    private val fuelProtector: FuelEnvelopeProtector? = null,
) {
    val store = BankStore(context)
    val vault = BankPinVault(context)
    private val parser = BankSmsParser()
    private val commands = BankCommands()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val pins = mutableStateMapOf<Bank, CharArray>()
    private val scopedPins = mutableStateMapOf<String, CharArray>()
    private val accessGenerations = mutableStateMapOf<String, Long>()
    private var foreground = true
    private var generation = 0
    private var session: BankSession? = null
    private var latestBankEvidence: BankSession? = null
    private var authWait by mutableStateOf<AuthWait?>(null)
    private var afterAuth by mutableStateOf<AuthWait?>(null)
    private var balanceWait by mutableStateOf<BalanceWait?>(null)
    private var inboxReading = false
    private var closed = false
    private var transportActive by mutableStateOf(false)

    var unlocked by mutableStateOf(false); private set
    var bank by mutableStateOf(store.bank); private set
    var subscription by mutableStateOf(store.subscription); private set
    var sims by mutableStateOf(emptyList<SimChoice>()); private set
    val configuredBanks: Set<Bank> get() = (pins.keys + walletSnapshot.registrations.filter(::hasCredential)
        .mapNotNull { it.bankCode?.let { code -> Bank.entries.singleOrNull { bank -> bank.code == code } } }
        ).filterTo(mutableSetOf()) { it != Bank.BFI }
    val busy: Boolean get() = transportActive || authWait != null || afterAuth != null || balanceWait != null || executor?.busy == true || !gateway.isIdle()
    var notice by mutableStateOf<String?>(null)
    private val restoredBalance = validStoredBalance()
    var accounts by mutableStateOf(restoredBalance.second); private set
    var balanceAt by mutableStateOf(restoredBalance.first); private set
    var history by mutableStateOf(emptyList<HistoryEntry>()); private set
    var pending by mutableStateOf(if (wallet == null) store.pending() else null); private set
    var recipients by mutableStateOf(store.recipients()); private set
    var uncertain by mutableStateOf(if (wallet == null) store.uncertain() else emptyList()); private set
    var confirmed by mutableStateOf<BankMessage.TransferSent?>(null); private set
    var serviceResult by mutableStateOf<String?>(null); private set
    val walletSnapshot: WalletSnapshot get() = wallet?.snapshot ?: WalletSnapshot()
    val selectedProductId: String? get() = walletSnapshot.settings.let {
        it.selectedCardId ?: it.selectedAccountId ?: it.selectedRegistrationId?.let { id -> "registration:$id" }
    }
    val configuredRegistrationIds: Set<String> get() = walletSnapshot.registrations.filter(::hasCredential).map { it.id }.toSet()
    val services: List<OperationSpec> get() = OperationCatalog.all
    var onSystemDial: ((SystemDialRequest) -> Unit)? = null
    var onIngested: ((BankSmsRecord, SmsIngestResult) -> Unit)? = null
    private val executor = wallet?.let { coordinator -> OperationExecutor(coordinator, gateway, now,
        canContinue = { !closed && foreground && (unlocked || !vault.hasCredentials()) }, onResult = { serviceResult = it },
        dial = { request -> onSystemDial?.invoke(request) ?: request.complete(false) }, onConfirmed = { operation, message ->
            if (pending?.id == operation.id) pending = null
            if (message is BankMessage.TransferSent) confirmed = message
        }, accessGeneration = ::currentAccessGeneration) }

    private fun currentAccessGeneration(registrationId: String): Long = accessGenerations[registrationId] ?: store.accessGeneration(registrationId)

    private fun hasCredential(registration: RegistrationRecord): Boolean {
        if (!registration.enabled || registration.subscriptionId == null) return false
        if (OperationCatalog.needsUpdatedAccess(walletSnapshot.operations, registration.id, currentAccessGeneration(registration.id))) return false
        val identity = registration.identity() ?: return false
        if (!OperationCatalog.supports(identity)) return false
        if (identity.bank?.code != registration.bankCode) return false
        val alias = registration.credentialAlias ?: return false
        if (alias == credentialAliasFor(registration) && alias in scopedPins) return true
        val bank = registration.bankCode?.let { code -> Bank.entries.singleOrNull { it.code == code } }
        return bank != null && registration.profileId == ProfileId.PERSONAL.name && alias == bank.name && bank in pins &&
            registration.subscriptionId == store.legacyAccessSubscription
    }

    fun credentialAliasFor(registration: RegistrationRecord): String = "access:${registration.id}"

    /** Called only after biometric encryption and registration persistence both succeeded; this makes no claim about service 69. */
    fun accessSaved(registrationId: String) {
        require(walletSnapshot.registrations.any { it.id == registrationId }) { "No existe el registro del acceso" }
        store.advanceAccessGeneration(registrationId)
        accessGenerations[registrationId] = store.accessGeneration(registrationId)
        executor?.invalidate(); session = null
    }

    private fun credentialFor(registration: RegistrationRecord?): CharArray? {
        registration ?: return null
        if (!hasCredential(registration)) return null
        val alias = requireNotNull(registration.credentialAlias)
        return (scopedPins[alias] ?: registration.bankCode?.let { code -> Bank.entries.singleOrNull { it.code == code } }?.let(pins::get))?.copyOf()
    }

    fun selectProduct(id: String) {
        if (busy) { notice = "Espera a que termine la solicitud"; return }
        if (id.startsWith("registration:")) return selectRegistration(id.removePrefix("registration:"))
        if (id.startsWith("default:")) return selectBank(Bank.valueOf(id.removePrefix("default:")))
        val coordinator = wallet ?: run { notice = "La cartera no está disponible"; return }
        coordinator.selectProduct(id) { syncSelection() }
    }

    fun selectRegistration(id: String) {
        if (busy) { notice = "Espera a que termine la solicitud"; return }
        val coordinator = wallet ?: run { notice = "La cartera no está disponible"; return }
        coordinator.transact(block = {
            val state = snapshot()
            val registration = state.registrations.single { it.id == id }
            setSettings(state.settings.copy(selectedRegistrationId = id, selectedCardId = null, selectedAccountId = null,
                activeBankCode = registration.bankCode ?: state.settings.activeBankCode, subscriptionId = registration.subscriptionId ?: -1))
        }, onSuccess = { syncSelection() })
    }

    fun syncSelection() {
        session = null
        val selected = walletSnapshot.settings
        Bank.entries.singleOrNull { it.code == selected.activeBankCode }?.let { bank = it; store.bank = it }
        subscription = selected.subscriptionId; store.subscription = subscription
        restoreBalance()
    }

    fun saveCard(value: CardRecord) { requireNotNull(wallet) { "La cartera no está disponible" }.saveCard(value) }
    fun deleteCard(id: String) { requireNotNull(wallet) { "La cartera no está disponible" }.deleteCard(id) }
    fun saveAccount(value: AccountRecord) { requireNotNull(wallet) { "La cartera no está disponible" }.saveAccount(value) }
    fun deleteAccount(id: String) { requireNotNull(wallet) { "La cartera no está disponible" }.deleteAccount(id) }
    fun saveContact(value: ContactRecord) { requireNotNull(wallet) { "La cartera no está disponible" }.saveContact(value) }
    fun deleteContact(id: String) { requireNotNull(wallet) { "La cartera no está disponible" }.deleteContact(id) }
    fun removeRegistration(id: String) {
        if (busy) { notice = "Espera a que termine la solicitud"; return }
        requireNotNull(wallet).transact(block = { requireRegistrationReviewed(snapshot(), id); deleteRegistration(id) }, onSuccess = {
            executor?.invalidate()
        })
    }

    private fun requireRegistrationReviewed(snapshot: WalletSnapshot, id: String) {
        require(snapshot.operations.none { it.registrationId == id && (it.status in setOf(OperationStatus.PREPARED,
            OperationStatus.SUBMITTING, OperationStatus.AWAITING_CONFIRMATION) || it.status == OperationStatus.UNCERTAIN && it.reviewRequired) }) {
            "Revisa las operaciones pendientes de este acceso antes de quitarlo o cambiar su línea"
        }
    }

    fun credentialsWithoutRegistration(id: String): ByteArray {
        requireRegistrationReviewed(walletSnapshot, id)
        val registration = walletSnapshot.registrations.single { it.id == id }
        val alias = registration.credentialAlias
        return credentialsCopy().use { credentials ->
            if (alias != null && walletSnapshot.registrations.none { it.id != id && it.credentialAlias == alias }) {
                credentials.scoped.remove(alias)?.fill('\u0000')
                Bank.entries.singleOrNull { it.name == alias }?.let { credentials.legacy.remove(it)?.fill('\u0000') }
            }
            credentials.encode()
        }
    }

    fun reassociateRegistration(id: String, subscriptionId: Int) {
        require(!busy) { "Espera a que termine la solicitud" }
        readSims()
        require(sims.any { it.id == subscriptionId }) { "Selecciona una SIM disponible" }
        requireNotNull(wallet).transact(block = {
            val current = snapshot()
            requireRegistrationReviewed(current, id)
            val registration = current.registrations.single { it.id == id }
            require(current.registrations.none { it.id != id && it.providerId == registration.providerId &&
                it.profileId == registration.profileId && it.subscriptionId == subscriptionId }) { "Ya existe ese acceso en la línea seleccionada" }
            putRegistration(registration.copy(subscriptionId = subscriptionId, enabled = true, linePhone = null,
                previousSubscriptionId = registration.previousSubscriptionId ?: registration.subscriptionId,
                previousLinePhone = registration.previousLinePhone ?: registration.linePhone))
            setSettings(current.settings.copy(selectedRegistrationId = id, subscriptionId = subscriptionId,
                activeBankCode = registration.bankCode ?: current.settings.activeBankCode,
                selectedCardId = null, selectedAccountId = null))
        }, onSuccess = { syncSelection() })
    }

    fun serviceContext(request: ServiceRequest, productId: String? = null): ServiceExecutionContext {
        require(OperationCatalog.supports(request.identity)) { "Este proveedor o perfil no está disponible" }
        val state = walletSnapshot
        val spec = requireNotNull(OperationCatalog.find(request.operationId))
        val authenticationIdentity = spec.authenticationIdentity ?: request.identity
        val identityMatches = state.registrations.filter { it.identity() == authenticationIdentity && it.enabled }
        val requestedRegistrationId = productId?.takeIf { it.startsWith("registration:") }?.removePrefix("registration:")
        val selected = identityMatches.singleOrNull { it.id == (requestedRegistrationId ?: state.settings.selectedRegistrationId) }
        val source = request.source
        data class ProductRef(val id: String, val registrationId: String, val number: String, val currency: String?)
        val products = state.cards.mapNotNull { card -> identityMatches.singleOrNull { it.id == card.registrationId }
            ?.takeIf { it.productIdentity(card.profileId) == request.identity }?.let { ProductRef(card.id, it.id, card.number, card.currency) } } +
            state.accounts.mapNotNull { account -> identityMatches.singleOrNull { it.id == account.registrationId }
                ?.takeIf { it.productIdentity(account.profileId) == request.identity }?.let { ProductRef(account.id, it.id, account.number, account.currency) } }
        val compatibleProducts = products.filter { source !is SourceSelector.Explicit || it.number == source.account }
        val chosenProduct = if (productId != null) {
            if (requestedRegistrationId != null) null else requireNotNull(compatibleProducts.singleOrNull { it.id == productId }) {
                "El producto no corresponde al origen de la operación"
            }
        } else compatibleProducts.singleOrNull { it.id == state.settings.selectedCardId || it.id == state.settings.selectedAccountId }
            ?: compatibleProducts.singleOrNull().takeIf { source is SourceSelector.Explicit }
        val requestCurrency = request.currency
        if (request.identity == ProviderIdentity(ProviderId.MITRANSFER) && chosenProduct != null && requestCurrency != null)
            require(chosenProduct.currency == requestCurrency.name) { "La moneda no corresponde a la cuenta MiTransfer seleccionada" }
        val differentDomain = authenticationIdentity != request.identity
        val registration = if (differentDomain) {
            if (request.identity.profile == ProfileId.CLASSIC) {
                if (chosenProduct == null && spec.sourcePolicy == SourcePolicy.NONE) selected
                else {
                    require(chosenProduct != null) { "Selecciona la tarjeta vinculada a tu registro MiTransfer" }
                    identityMatches.singleOrNull { it.id == chosenProduct.registrationId }
                }
            } else selected
        } else chosenProduct?.let { product -> identityMatches.singleOrNull { it.id == product.registrationId } }
            ?: selected ?: identityMatches.singleOrNull().takeIf { productId == null }
        if (productId != null) require(registration != null) { "El registro del producto no está disponible" }
        require(registration != null || identityMatches.isEmpty()) { "Selecciona el acceso y la línea que deseas usar" }
        require(registration != null || !spec.requiresSession && spec.fields.none { it.suppliedByAccess }) { "Configura el acceso para este origen" }
        val sim = registration?.subscriptionId ?: subscription
        require(sim >= 0) { "Selecciona una SIM disponible" }
        val product = chosenProduct?.id ?: registration?.id?.let { "registration:$it" }
        return ServiceExecutionContext(registration?.id, request.identity, sim, product, registration, authenticationIdentity,
            chosenProduct?.number, registration?.id?.let(::currentAccessGeneration) ?: 0, chosenProduct?.currency)
    }

    fun validateService(request: ServiceRequest): List<OperationValidationError> = OperationCatalog.validate(request) +
        runCatching { serviceContext(request); emptyList<OperationValidationError>() }.getOrElse {
            listOf(OperationValidationError("source", it.message ?: "Selecciona un origen compatible"))
        }

    fun validateService(request: ServiceRequest, executionContext: ServiceExecutionContext): List<OperationValidationError> =
        OperationCatalog.validate(request) + requireNotNull(executor).validateBindings(request, executionContext)

    fun runService(request: ServiceRequest, pin: CharArray? = null,
                   executionContext: ServiceExecutionContext? = null, approved: Boolean = false) {
        var credential: CharArray? = null
        try {
            val target = executionContext ?: serviceContext(request)
            requireOperationPermissions(target.subscriptionId)
            val spec = requireNotNull(OperationCatalog.find(request.operationId))
            credential = pin?.copyOf() ?: credentialFor(target.registration)
            if (spec.requiresSession || spec.fields.any { it.suppliedByAccess }) requireNotNull(credential) { "Configura la clave del acceso seleccionado" }
            requireNotNull(executor) { "La cartera aún no está disponible" }.execute(request, target, credential, approved)
        } catch (failure: Exception) { serviceResult = failure.message ?: "Revisa los datos de la operación" }
        finally { pin?.fill('\u0000'); credential?.fill('\u0000') }
    }

    fun clearServiceResult() { serviceResult = null }

    private fun requireOperationPermissions(sim: Int) {
        readSims()
        require(sims.any { it.id == sim }) { "Selecciona una SIM disponible" }
        require(context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED) { "Permite llamadas y SMS para continuar" }
    }

    private class AuthWait(
        val bank: Bank, val subscription: Int, val started: Instant,
        val balanceOnly: Boolean, val run: () -> Unit,
    ) {
        var accepted = false
        var confirmedAt: Instant? = null
    }
    private class BalanceWait(val bank: Bank, val subscription: Int, val started: Instant, val auth: AuthWait?) {
        var accepted = false
        var confirmedAt: Instant? = null
    }

    fun readSims() {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        sims = listSims()
        if (subscription == -1 && sims.size == 1) selectSim(sims.single().id)
    }

    fun selectSim(id: Int) {
        if (pending != null || busy || !gateway.isIdle()) return
        if (subscription != id) { session = null; latestBankEvidence = null; accounts = emptyList(); balanceAt = null }
        subscription = id; store.subscription = id
        restoreBalance()
    }

    fun selectBank(value: Bank) {
        if (value == Bank.BFI) { notice = "BFI no está disponible"; return }
        if (pending != null || busy || !gateway.isIdle()) return
        if (bank != value) { session = null; accounts = emptyList(); balanceAt = null }
        bank = value; store.bank = value
        restoreBalance()
    }

    private fun validStoredBalance(): Pair<Instant?, List<AccountBalance>> =
        store.balances(bank, subscription).takeIf { it.first?.let { at -> at <= now() } == true } ?: (null to emptyList())
    private fun restoreBalance() { validStoredBalance().let { balanceAt = it.first; accounts = it.second } }
    private fun updateBalance(values: List<AccountBalance>, at: Instant) {
        if (at > now()) return
        store.saveBalances(bank, subscription, at, values)
        accounts = values; balanceAt = at
    }

    fun unlock(bytes: ByteArray) {
        try {
            AccessCredentials.decode(bytes).use { decoded ->
                pins.values.forEach { it.fill('\u0000') }; pins.clear()
                scopedPins.values.forEach { it.fill('\u0000') }; scopedPins.clear()
                decoded.legacy.forEach { (bank, pin) -> pins[bank] = pin.copyOf() }
                decoded.scoped.forEach { (alias, pin) -> scopedPins[alias] = pin.copyOf() }
            }
            unlocked = true
            readSims(); reloadInbox()
            wallet?.bindLegacyAccesses(pins.keys, store.legacyAccessSubscription)
            tick()
        } finally { bytes.fill(0) }
    }

    fun enrollmentBytes(value: Bank, pin: CharArray): ByteArray {
        require(value != Bank.BFI) { "BFI no está disponible" }
        require(pin.size == value.pinLength && pin.all { it in '0'..'9' })
        return credentialsCopy().use { credentials ->
            credentials.legacy.put(value, pin.copyOf())?.fill('\u0000')
            credentials.encode()
        }
    }

    private fun credentialsCopy(): AccessCredentials = AccessCredentials.empty().also { credentials ->
        pins.forEach { (bank, pin) -> credentials.legacy[bank] = pin.copyOf() }
        scopedPins.forEach { (alias, pin) -> credentials.scoped[alias] = pin.copyOf() }
    }

    fun enrollmentBytesProvider(identity: ProviderIdentity, registration: RegistrationRecord, pin: CharArray): ByteArray {
        require(OperationCatalog.supports(identity)) { "Este proveedor o perfil no está disponible" }
        require(identity.provider != ProviderId.MITRANSFER || identity.profile == ProfileId.PERSONAL) {
            "Configura el registro MiTransfer al que pertenece el producto"
        }
        require(registration.identity() == identity && registration.bankCode == identity.bank?.code &&
            registration.subscriptionId != null && registration.enabled) { "Registro de acceso no válido" }
        require(pin.size == (identity.bank?.pinLength ?: 4) && pin.all { it in '0'..'9' }) { "Clave del acceso no válida" }
        return credentialsCopy().use { credentials ->
            credentials.scoped.put(credentialAliasFor(registration), pin.copyOf())?.fill('\u0000')
            credentials.encode()
        }
    }

    fun enrolmentBytesProvider(identity: ProviderIdentity, registration: RegistrationRecord, pin: CharArray): ByteArray =
        enrollmentBytesProvider(identity, registration, pin)

    fun lock() {
        generation++
        pins.values.forEach { it.fill('\u0000') }; pins.clear()
        scopedPins.values.forEach { it.fill('\u0000') }; scopedPins.clear()
        executor?.invalidate()
        unlocked = false; authWait = null; afterAuth = null; balanceWait = null; session = null
        confirmed = null; notice = null
        main.removeCallbacks(ticker)
    }

    /** The Application owns the shared wallet; closing a controller only releases its own jobs. */
    fun close() {
        if (closed) return
        closed = true
        lock(); executor?.close(); main.removeCallbacksAndMessages(null); io.shutdown()
    }

    /** Keep the local grace period separate from permission to continue an outgoing request. */
    fun suspendForeground() {
        foreground = false; executor?.invalidate()
        generation++
        authWait = null; afterAuth = null; balanceWait = null; session = null
        main.removeCallbacks(ticker)
    }

    fun resumeForeground() { foreground = true; if (unlocked) tick() }

    fun pinFrom(bytes: ByteArray, selected: Bank): CharArray = try {
        AccessCredentials.decode(bytes).use { credentials -> requireNotNull(credentials.legacy[selected]) { "Selecciona el acceso bancario" }.copyOf() }
    } finally { bytes.fill(0) }

    fun pinFrom(bytes: ByteArray, registration: RegistrationRecord): CharArray = try {
        AccessCredentials.decode(bytes).use { credentials ->
            require(registration.enabled && registration.subscriptionId != null) { "Vuelve a asociar el acceso a su línea" }
            val alias = requireNotNull(registration.credentialAlias) { "Configura la clave del acceso" }
            val legacyBank = registration.bankCode?.let { code -> Bank.entries.singleOrNull { it.code == code } }
            val value = credentials.scoped[alias]?.takeIf { alias == credentialAliasFor(registration) } ?: legacyBank?.takeIf {
                alias == it.name && registration.profileId == ProfileId.PERSONAL.name && registration.subscriptionId == store.legacyAccessSubscription
            }?.let(credentials.legacy::get)
            requireNotNull(value) { "No hay una clave guardada para este registro" }.copyOf()
        }
    } finally { bytes.fill(0) }

    fun queryBalance() = queryBalance(selectedProductId)

    fun queryBalance(productId: String?) {
        if (wallet != null) {
            try {
                require(unlocked && !busy) { "Desbloquea el acceso y espera a que termine la solicitud" }
                val state = walletSnapshot
                val chosenId = productId ?: selectedProductId
                val card = state.cards.singleOrNull { it.id == chosenId }
                val account = state.accounts.singleOrNull { it.id == chosenId }
                val registrationId = card?.registrationId ?: account?.registrationId ?:
                    chosenId?.takeIf { it.startsWith("registration:") }?.removePrefix("registration:")
                val registration = state.registrations.singleOrNull { it.id == registrationId }
                    ?: state.registrations.singleOrNull { chosenId == null && it.bankCode == bank.code && it.subscriptionId == subscription }
                    ?: throw IllegalArgumentException("Selecciona un acceso de la cartera")
                val identity = requireNotNull(registration.productIdentity(card?.profileId ?: account?.profileId))
                val source = card?.let { SourceSelector.Explicit(it.number) } ?: account?.let {
                    if (it.number.isBlank() || it.number == "0000") SourceSelector.Default else SourceSelector.Explicit(it.number)
                } ?: SourceSelector.Default
                val id = if (identity.bank == Bank.BANDEC && source is SourceSelector.Explicit) "bandec.card-balance" else identity.bank?.let { "${it.name.lowercase()}.balance" }
                    ?: if (identity.profile in setOf(ProfileId.CLASSIC, ProfileId.CLASSIC_BUSINESS)) "wallet.classic.balance" else "wallet.balance"
                val chosenSource = if (id == "wallet.balance") SourceSelector.Default else source
                val currency = if (identity == ProviderIdentity(ProviderId.MITRANSFER) ||
                    identity.bank == Bank.BANMET && chosenSource == SourceSelector.Default) account?.currency?.let(Currency::valueOf) else null
                val request = ServiceRequest(id, identity, chosenSource, currency)
                val target = serviceContext(request, chosenId ?: "registration:${registration.id}")
                requireOperationPermissions(target.subscriptionId)
                val pin = requireNotNull(credentialFor(registration)) { "Configura la clave del acceso" }
                val defaultBpa = identity.bank == Bank.BPA && chosenSource == SourceSelector.Default
                if (id == "bandec.card-balance") requireNotNull(executor).queryBandecCard(target, chosenSource as SourceSelector.Explicit, pin)
                else requireNotNull(executor).execute(request, target, pin, approved = false,
                    wire = if (defaultBpa) listOf(commands.defaultBalance()) else null)
            } catch (failure: Exception) { serviceResult = failure.message ?: "No se pudo consultar el saldo" }
            return
        }
        if (!canOperate() || busy) return
        val pin = pins[bank]?.copyOf() ?: return
        notice = null
        try { withSession(pin, balanceOnly = true) { sendBalance() } }
        finally { pin.fill('\u0000') }
    }

    fun disconnectBank(approved: Boolean = false) {
        if (wallet != null) {
            val registration = walletSnapshot.registrations.singleOrNull { it.id == walletSnapshot.settings.selectedRegistrationId }
            val identity = registration?.identity() ?: ProviderIdentity.forBank(bank)
            val id = identity.bank?.let { "${it.name.lowercase()}.disconnect" } ?: "wallet.disconnect"
            runService(ServiceRequest(id, identity), approved = approved)
            return
        }
        if (!canOperate() || busy || pending != null) return
        session = null
        send(commands.disconnect()) { result -> notice = failure(result) }
    }

    private fun canOperate(): Boolean {
        if (bank == Bank.BFI) { notice = "BFI no está disponible"; return false }
        if (!unlocked) return false
        readSims()
        if (!sims.any { it.id == subscription }) { notice = "Selecciona una SIM disponible"; return false }
        if (!configuredBanks.contains(bank)) { notice = "Configura la clave de ${bank.name}"; return false }
        if (!gateway.isIdle()) { notice = "Hay una solicitud en curso"; return false }
        if (context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            notice = "Permite llamadas y SMS para continuar"; return false
        }
        return true
    }

    private fun withSession(pin: CharArray, balanceOnly: Boolean = false, operation: () -> Unit) {
        if (session?.isValidFor(bank, subscription, now()) == true) return operation()
        val started = now()
        val wait = AuthWait(bank, subscription, started, balanceOnly, operation)
        session = null
        authWait = wait
        send(commands.authenticate(bank, pin)) { result ->
            if (authWait !== wait) return@send
            if (!fresh(wait.started, now())) {
                authWait = null; notice = "No llegó la confirmación de acceso. Consulta los mensajes del banco."
                return@send
            }
            when ((result as? UssdResult.Response)?.let { BankResponse.parse(it.text) }) {
                BankResponse.PROCESSING -> { wait.accepted = true; completeAuthentication(wait) }
                BankResponse.ALREADY_AUTHENTICATED -> {
                    // The platform may still be using a bank selected in another app.
                    authWait = null
                    sendBalance(wait)
                }
                else -> { authWait = null; notice = failure(result) }
            }
        }
        main.postDelayed({
            if (authWait === wait) { authWait = null; notice = "No llegó la confirmación de acceso. Consulta los mensajes del banco." }
        }, 30_000)
    }

    private fun fresh(started: Instant, receivedAt: Instant): Boolean =
        receivedAt >= started && receivedAt <= now() && now() < started.plusSeconds(30)

    private fun completeAuthentication(wait: AuthWait) {
        val at = wait.confirmedAt ?: return
        if (authWait !== wait || !wait.accepted) return
        authWait = null
        if (fresh(wait.started, at)) continueSession(wait, at)
    }

    private fun continueSession(wait: AuthWait, at: Instant, balanceConfirmed: Boolean = false) {
        if (!unlocked || wait.bank != bank || wait.subscription != subscription) return
        val latest = latestBankEvidence?.takeIf { it.authenticatedAt <= now() }
        if (latest != null && latest.subscriptionId == wait.subscription && latest.bank != wait.bank && latest.authenticatedAt >= at) {
            notice = otherBankNotice(latest.bank)
            return
        }
        session = BankSession(wait.bank, wait.subscription, at)
        if (balanceConfirmed && wait.balanceOnly) return
        afterAuth = wait
        fun continueWhenIdle() {
            if (afterAuth !== wait) return
            if (!unlocked || now() >= at.plusSeconds(30)) { afterAuth = null; session = null; return }
            if (gateway.isIdle()) { afterAuth = null; wait.run() } else main.postDelayed({ continueWhenIdle() }, 200)
        }
        continueWhenIdle()
    }

    fun moneyContext(action: MoneyAction): ServiceExecutionContext = serviceContext(requestForMoney(action)).copy(originalQr = action.qr)

    private fun requestForMoney(action: MoneyAction): ServiceRequest {
        val id = when (action.kind) {
            ActionKind.TRANSFER -> "${action.bank.name.lowercase()}.transfer"
            ActionKind.QR -> "bank.qr"
            ActionKind.RECHARGE -> "service.mobile"
            ActionKind.ELECTRICITY -> "service.electricity"
            ActionKind.TELEPHONE -> if (action.bank == Bank.BPA) "service.telephone.bpa" else "service.telephone"
        }
        val values = when (action.kind) {
            ActionKind.TRANSFER -> buildMap {
                put("destination", action.destination); put("amount", action.amount.amount.toPlainString())
                action.phone?.let { put("notificationPhone", it) }
            }
            ActionKind.RECHARGE -> mapOf("mobile" to action.destination, "amount" to action.amount.amount.toPlainString())
            ActionKind.ELECTRICITY -> mapOf("invoice" to action.destination)
            ActionKind.TELEPHONE -> mapOf("invoice" to action.destination) +
                if (action.amount.amount.signum() > 0) mapOf("amount" to action.amount.amount.toPlainString()) else emptyMap()
            ActionKind.QR -> emptyMap()
        }
        return ServiceRequest(id, ProviderIdentity.forBank(action.bank), SourceSelector.fromLegacy(action.source), action.amount.currency, values)
    }

    fun submit(action: MoneyAction, pin: CharArray, executionContext: ServiceExecutionContext? = null) {
        if (wallet != null) {
            try {
                val request = requestForMoney(action)
                val target = executionContext ?: moneyContext(action)
                requireOperationPermissions(target.subscriptionId)
                val time = LocalTime.now()
                val wire = if (action.kind == ActionKind.QR) commands.qrPayment(action.bank, pin, requireNotNull(action.qr), action.amount,
                    action.description, action.source, action.phone, "${time.minute}${time.second.toString().padStart(2, '0')}") else null
                requireNotNull(executor).execute(request, target, pin, approved = true, legacy = action, wire = wire,
                    onPrepared = { operation -> pending = PendingRecord(action, target.subscriptionId, Instant.ofEpochMilli(operation.startedAt),
                        id = operation.id, registrationId = target.registrationId); confirmed = null })
            } catch (failure: Exception) { serviceResult = failure.message ?: "No se pudo preparar la operación" }
            finally { pin.fill('\u0000') }
            return
        }
        try {
            if (!canOperate() || busy || pending != null || action.bank != bank) return
            notice = null
            if (action.kind == ActionKind.TRANSFER && bank == Bank.BANDEC) {
                require(sourceCurrency(accounts, action.source) == action.amount.currency) { "Consulta el saldo para comprobar la moneda de la cuenta de origen" }
            }
            val time = LocalTime.now()
            val wire = when (action.kind) {
                ActionKind.TRANSFER -> listOf(commands.transfer(action.transferRequest()))
                ActionKind.QR -> commands.qrPayment(bank, pin, requireNotNull(action.qr), action.amount,
                    action.description, action.source, action.phone, "${time.minute}${time.second.toString().padStart(2, '0')}")
                ActionKind.RECHARGE -> listOf(commands.recharge(bank, action.destination, action.amount, action.source))
                ActionKind.ELECTRICITY, ActionKind.TELEPHONE -> listOf(commands.bill(bank,
                    action.kind == ActionKind.ELECTRICITY, action.destination, action.amount, action.source))
            }
            val token = generation
            withSession(pin) {
                if (unlocked && generation == token && pending == null) {
                    if (action.kind == ActionKind.TRANSFER && bank == Bank.BANDEC &&
                        sourceCurrency(accounts, action.source) != action.amount.currency) {
                        notice = "El saldo consultado no confirma la moneda de la cuenta de origen"
                        return@withSession
                    }
                    val record = PendingRecord(action, subscription, now())
                    try {
                        store.savePending(record)
                        pending = record; confirmed = null
                        sendParts(wire, 0, record, token)
                    } catch (_: Exception) { notice = "No se pudo guardar la operación. Revisa su estado antes de continuar." }
                }
            }
        } catch (e: IllegalArgumentException) { notice = e.message ?: "Revisa los datos de la operación" }
        finally { pin.fill('\u0000') }
    }

    private fun sendParts(parts: List<UssdCommand>, index: Int, record: PendingRecord, token: Int) {
        if (generation != token || !unlocked) return
        send(parts[index]) { result ->
            if (result is UssdResult.Response && result.text.contains("siendo procesada,")) {
                if (index + 1 < parts.size) sendParts(parts, index + 1, record, token)
                else { notice = "Solicitud procesada. Esperando el comprobante del banco." }
            } else {
                notice = "No se pudo confirmar el resultado. Revisa los mensajes antes de hacer otra operación."
                // Even failure callbacks may occur after the network accepted a debit.
            }
            tick()
        }
    }

    private fun sendBalance(auth: AuthWait? = null) {
        val wait = BalanceWait(bank, subscription, now(), auth)
        balanceWait = wait
        send(commands.defaultBalance()) { result ->
            if (balanceWait !== wait) return@send
            if (!fresh(wait.started, now())) {
                balanceWait = null; session = null; notice = "No llegó el saldo. Puedes volver a consultarlo."
                return@send
            }
            if (result is UssdResult.Response && BankResponse.parse(result.text) == BankResponse.PROCESSING) {
                wait.accepted = true; completeBalance(wait)
            } else {
                balanceWait = null; session = null; notice = failure(result)
            }
        }
        main.postDelayed({
            if (balanceWait === wait) {
                balanceWait = null
                if (wait.auth != null) session = null
                if (unlocked) notice = "No llegó el saldo. Puedes volver a consultarlo."
            }
        }, 30_000)
    }

    private fun completeBalance(wait: BalanceWait) {
        val at = wait.confirmedAt ?: return
        if (balanceWait !== wait || !wait.accepted) return
        balanceWait = null
        if (fresh(wait.started, at)) wait.auth?.let { continueSession(it, at, balanceConfirmed = true) }
    }

    private fun send(command: UssdCommand, callback: (UssdResult) -> Unit) {
        val token = generation
        transportActive = true
        gateway.send(command, subscription) { result ->
            transportActive = false
            if (unlocked && generation == token) callback(result)
        }
    }

    fun receive(record: BankSmsRecord, onFinished: () -> Unit = {}) {
        val completed = java.util.concurrent.atomic.AtomicBoolean(false)
        val finishOnce = { if (completed.compareAndSet(false, true)) onFinished() }
        val message = parser.parse("PAGOxMOVIL", record.body) ?: BankMessage.Unrecognized
        wallet?.let { coordinator ->
            coordinator.transact("No se pudo guardar el mensaje recibido", block = { SmsIngestor(this, parser, now, fuelProtector).ingest(record, message = message) },
                onFailure = { coordinator.drainTransferNotifications(finishOnce) }, onSuccess = { result ->
                    runCatching { processStoredEvidence(record, result); if (unlocked) reloadInbox() }
                        .onFailure { notice = "El mensaje se guardó, pero no se pudo actualizar la vista" }
                    coordinator.drainTransferNotifications(finishOnce)
                })
            return
        }
        processEvidence(record, message)
        if (unlocked) reloadInbox()
        finishOnce()
    }

    private fun otherBankNotice(value: Bank) =
        "La sesión activa es de ${value.name}. Selecciona ese banco o usa Cerrar sesión bancaria en Ajustes."

    private fun observeBank(record: BankSmsRecord, message: BankMessage) {
        if (record.subscriptionId != subscription) return
        val observed = when (message) {
            is BankMessage.Authenticated -> message.bank
            is BankMessage.Balance -> message.bank
            else -> return
        }
        val latest = latestBankEvidence?.takeIf { it.authenticatedAt <= now() }
        if (latest != null && record.receivedAt < latest.authenticatedAt) return
        latestBankEvidence = BankSession(observed, subscription, record.receivedAt)
        if (observed == bank) return
        val changesSession = session?.let { record.receivedAt >= it.authenticatedAt } == true
        val contradictsRequest = authWait?.let { fresh(it.started, record.receivedAt) } == true ||
            balanceWait?.let { fresh(it.started, record.receivedAt) } == true
        if (changesSession || contradictsRequest) {
            session = null; authWait = null; afterAuth = null; balanceWait = null
            if (unlocked) notice = otherBankNotice(observed)
        }
    }

    /** Both delivery paths must apply the same time, bank and SIM checks. */
    private fun processEvidence(record: BankSmsRecord, message: BankMessage) {
        if (record.receivedAt > now()) return
        observeBank(record, message)
        val wait = authWait
        if (message is BankMessage.Authenticated && wait != null && message.bank == wait.bank &&
            record.subscriptionId == wait.subscription && fresh(wait.started, record.receivedAt) && unlocked) {
            wait.confirmedAt = record.receivedAt; completeAuthentication(wait)
        }
        if (message is BankMessage.Balance && message.bank == bank && record.subscriptionId == subscription) {
            if (balanceAt == null || balanceAt!! > now() || record.receivedAt > balanceAt) updateBalance(message.accounts, record.receivedAt)
        }
        val balance = balanceWait
        if (message is BankMessage.Balance && balance != null && message.bank == balance.bank && record.subscriptionId == balance.subscription &&
            fresh(balance.started, record.receivedAt) && unlocked) {
            balance.confirmedAt = record.receivedAt; completeBalance(balance)
        }
        val p = pending
        if (p != null && message is BankMessage.TransferSent && !store.usedReference(message.bank, message.reference) &&
            !store.ambiguousReceipt(message, record.receivedAt, record.subscriptionId) &&
            p.transfer?.accept(message, record.receivedAt, record.subscriptionId) == true) {
            val appliedBalance = applyRemaining(p, message, record.receivedAt)
            store.confirm(message.bank, message.reference, p.subscription, !appliedBalance && !p.refreshTaken, now())
            pending = null; confirmed = message
            tick()
        }
    }

    private fun applyRemaining(pending: PendingRecord, receipt: BankMessage.TransferSent, at: Instant): Boolean {
        if (at > now() || pending.action.bank != bank || pending.subscription != subscription) return false
        val sole = accounts.singleOrNull() ?: return false
        val remaining = receipt.remainingBalance ?: return false
        if (remaining.currency != sole.available.currency) return false
        if (pending.action.source != "0000" && sole.account?.let { matchesAccount(it, pending.action.source) } != true) return false
        if (balanceAt == null || balanceAt!! > now() || at >= balanceAt) updateBalance(listOf(sole.copy(available = remaining)), at)
        return true
    }

    fun reloadInbox() {
        if (closed || !unlocked || inboxReading || context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
        inboxReading = true
        val token = generation
        io.execute {
            val result = runCatching { readInbox().map { HistoryEntry(it, parser.parse("PAGOxMOVIL", it.body) ?: BankMessage.Unrecognized) } }
            main.post {
                inboxReading = false
                if (!unlocked || generation != token) return@post
                result.onSuccess { rows ->
                    history = rows.filterNot { containsSecret(it.record.body) }
                    val coordinator = wallet
                    if (coordinator == null) rows.forEach { row -> processRecovered(row) }
                    else coordinator.transact("No se pudieron guardar los mensajes", block = {
                        val ingestor = SmsIngestor(this, parser, now, fuelProtector)
                        rows.map { row -> row.record to ingestor.ingest(row.record, EventSource.INBOX, row.message) }
                    }, onSuccess = { ingested -> ingested.forEach { (record, value) -> processStoredEvidence(record, value) } })
                }.onFailure { notice = "No se pudieron leer los mensajes del banco" }
            }
        }
    }

    private fun processRecovered(row: HistoryEntry) {
        processEvidence(row.record, row.message)
    }

    private fun processStoredEvidence(record: BankSmsRecord, result: SmsIngestResult) {
        executor?.observed(record, result)
        if (result.evidenceEligible && result.message is BankMessage.Balance && result.message.bank == bank && record.subscriptionId == subscription)
            if (balanceAt == null || record.receivedAt > balanceAt) updateBalance(result.message.accounts, record.receivedAt)
        onIngested?.invoke(record, result)
    }

    fun acknowledgePending() {
        if (!unlocked || !gateway.isIdle() || busy) return
        if (wallet != null) {
            pending?.id?.let(::acknowledgeOperation)
            return
        }
        pending?.let(store::closeUncertain); pending = null
        uncertain = store.uncertain()
    }

    fun acknowledgeOperation(id: String) {
        require(unlocked && !busy && gateway.isIdle()) { "Espera a que termine la solicitud" }
        requireNotNull(wallet).transact(block = { reviewOperation(id) }, onSuccess = { if (pending?.id == id) pending = null })
    }

    /** The caller must show this exact set before asking the user to acknowledge the registration's operations. */
    fun acknowledgeRegistrationOperations(registrationId: String, operationIds: Set<String>, done: () -> Unit = {}) {
        require(unlocked && !busy && gateway.isIdle()) { "Espera a que termine la solicitud" }
        requireNotNull(wallet).transact(block = {
            val current = snapshot().operations.filter { it.registrationId == registrationId &&
                (it.status in setOf(OperationStatus.PREPARED, OperationStatus.SUBMITTING, OperationStatus.AWAITING_CONFIRMATION) ||
                    it.status == OperationStatus.UNCERTAIN && it.reviewRequired) }.map { it.id }.toSet()
            require(current == operationIds) { "Las operaciones han cambiado. Revísalas de nuevo." }
            current.forEach { reviewOperation(it) }
        }, onSuccess = { if (pending?.id in operationIds) pending = null; done() })
    }

    private fun WalletRepository.reviewOperation(id: String) {
        val operation = snapshot().operations.single { it.id == id }
        when (operation.status) {
            OperationStatus.PREPARED -> updateOperationStatus(id, operation.status, OperationStatus.CANCELLED, now().toEpochMilli())
            OperationStatus.SUBMITTING, OperationStatus.AWAITING_CONFIRMATION -> updateOperationStatus(id, operation.status, OperationStatus.UNCERTAIN, now().toEpochMilli())
            else -> Unit
        }
        snapshot().operations.single { it.id == id }.takeIf { it.status == OperationStatus.UNCERTAIN && it.reviewRequired }?.let {
            putOperation(it.copy(reviewRequired = false, updatedAt = now().toEpochMilli()))
        }
    }

    fun resolveUncertain(id: String, record: BankSmsRecord) {
        if (!unlocked) return
        if (record.receivedAt > now()) { notice = "El comprobante tiene una fecha futura"; return }
        val item = uncertain.singleOrNull { it.id == id } ?: return
        val receipt = parser.parse("PAGOxMOVIL", record.body) as? BankMessage.TransferSent ?: return
        if (store.usedReference(receipt.bank, receipt.reference) || !item.matches(receipt, record.receivedAt, record.subscriptionId)) {
            notice = "El comprobante no corresponde a esta operación o ya está vinculado"; return
        }
        store.resolveUncertain(id, receipt.bank, receipt.reference)
        uncertain = store.uncertain(); notice = "Comprobante vinculado"
    }

    fun saveRecipient(value: Recipient) {
        if (wallet != null) {
            val existing = walletSnapshot.contacts.singleOrNull { contact -> contact.cards.any { it.number == value.card } }
            saveContact(ContactRecord(existing?.id ?: java.util.UUID.randomUUID().toString(), value.name,
                existing?.phones.orEmpty() + listOfNotNull(value.phone?.takeIf { phone -> existing?.phones?.none { it.number == phone } != false }?.let { ContactPhone(it) }),
                existing?.cards ?: listOf(ContactCard(value.card))))
        } else { store.saveRecipient(value); recipients = store.recipients() }
    }

    private val ticker = Runnable { tick() }
    private fun tick() {
        main.removeCallbacks(ticker)
        if (!unlocked) return
        if (wallet != null) return // Durable operations never schedule automatic money retries or an unscoped refresh.
        val p = pending
        if (!busy && gateway.isIdle() && store.refreshDue(bank, subscription, now()) && canOperate()) {
            // Persist the latch before sending so a restart cannot repeat the refresh.
            if (p != null) { p.refreshTaken = true; store.savePending(p) }
            store.takeRefresh()
            queryBalance()
        }
        main.postDelayed(ticker, 1_000)
    }

    private fun failure(result: UssdResult): String = when (result) {
        UssdResult.PermissionRequired -> "Permite llamadas para continuar"
        UssdResult.InvalidSubscription -> "La SIM seleccionada no está disponible"
        UssdResult.Busy -> "Hay una solicitud en curso"
        is UssdResult.Response -> result.text
        else -> "No se pudo completar la solicitud. Comprueba la cobertura."
    }
}

class BankSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != android.provider.Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val record = BankSmsInbox.fromBroadcast(intent) ?: return
        val pending = goAsync()
        try { (context.applicationContext as NeoTransferApplication).controller.receive(record) { pending.finish() } }
        catch (_: Exception) { pending.finish() }
    }
}
