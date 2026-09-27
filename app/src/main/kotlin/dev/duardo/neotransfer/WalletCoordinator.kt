package dev.duardo.neotransfer

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.duardo.neotransfer.core.Bank
import dev.duardo.neotransfer.core.ProviderIdentity
import dev.duardo.neotransfer.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Application-scoped bridge. Database work never runs on the presentation thread. */
class WalletCoordinator(context: Context,
    val repository: WalletRepository = RoomWalletRepository.open(context.applicationContext),
    private val notificationDelivery: ((ReceiptRecord, WalletSnapshot) -> Boolean)? = null,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val initialized = CompletableDeferred<Unit>()
    private val transactions = Mutex()
    private var drainingNotifications = false
    private var notificationDrainRequested = false
    private var closing = false
    private var closed = false
    private val closeCompletions = mutableListOf<() -> Unit>()
    private val notificationCompletions = mutableListOf<() -> Unit>()
    var snapshot by mutableStateOf(WalletSnapshot()); private set
    var ready by mutableStateOf(false); private set
    var notice by mutableStateOf<String?>(null)

    init {
        val legacy = context.getSharedPreferences("bank_state", Context.MODE_PRIVATE).all.toMap()
        scope.launch {
            try {
                transactions.withLock {
                    snapshot = withContext(Dispatchers.IO) {
                        repository.migrateLegacy(legacy)
                        repository.snapshot()
                    }
                    ready = true
                    initialized.complete(Unit)
                }
                drainTransferNotifications()
                repository.observe().collect {
                    // The notification may have queued before a newer write; publish a current snapshot under the same lock.
                    transactions.withLock { snapshot = withContext(Dispatchers.IO) { repository.snapshot() } }
                }
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) {
                notice = "No se pudo abrir la cartera. Tus datos guardados se han conservado."
                if (!initialized.isCompleted) initialized.completeExceptionally(IllegalStateException("Cartera no disponible"))
            }
        }
    }

    fun <T> transact(
        failureMessage: String = "No se pudieron guardar los cambios",
        block: WalletRepository.() -> T,
        onFailure: () -> Unit = {},
        onSuccess: (T) -> Unit = {},
    ) = scope.launch {
        try {
            initialized.await()
            transactions.withLock {
                val (value, updated) = withContext(Dispatchers.IO) { repository.block() to repository.snapshot() }
                snapshot = updated
                onSuccess(value)
            }
        } catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { notice = failureMessage; onFailure() }
    }

    /** One bounded drain at a time. A failed delivery or ACK remains durable for the next wake-up. */
    fun drainTransferNotifications(onFinished: () -> Unit = {}) {
        if (!scope.isActive) { onFinished(); return }
        val deliver = notificationDelivery ?: run { onFinished(); return }
        notificationCompletions += onFinished
        notificationDrainRequested = true
        if (drainingNotifications) return
        drainingNotifications = true
        scope.launch {
            try {
                withTimeoutOrNull(7_000) {
                    initialized.await()
                    val attempted = mutableSetOf<String>()
                    do {
                        notificationDrainRequested = false
                        transactions.withLock {
                            val (queued, current) = withContext(Dispatchers.IO) {
                                repository.pendingTransferNotifications() to repository.snapshot()
                            }
                            snapshot = current
                            for (receipt in queued) {
                                if (!attempted.add(receipt.id)) continue
                                if (runCatching { deliver(receipt, current) }.getOrDefault(false)) {
                                    withContext(Dispatchers.IO) { repository.markTransferNotificationDelivered(receipt.id) }
                                }
                            }
                        }
                    } while (notificationDrainRequested)
                }
            } catch (failure: CancellationException) { throw failure }
            catch (_: Exception) { /* Keep the outbox item; do not turn a delivery failure into a payment result. */ }
            finally {
                drainingNotifications = false
                notificationDrainRequested = false
                val completions = notificationCompletions.toList()
                notificationCompletions.clear()
                completions.forEach { runCatching(it) }
            }
        }
    }

    fun saveCard(value: CardRecord) { transact(block = { putCard(value) }) }
    fun deleteCard(id: String) { transact(block = { deleteCard(id) }) }
    fun saveAccount(value: AccountRecord) { transact(block = { putAccount(value) }) }
    fun deleteAccount(id: String) { transact(block = { deleteAccount(id) }) }
    fun saveContact(value: ContactRecord) { transact(block = { putContact(value) }) }
    fun deleteContact(id: String) { transact(block = { deleteContact(id) }) }

    fun selectProduct(id: String, after: () -> Unit = {}) {
        transact(block = {
            val current = snapshot()
            val card = current.cards.singleOrNull { it.id == id }
            val account = current.accounts.singleOrNull { it.id == id }
            val registration = current.registrations.singleOrNull { it.id == (card?.registrationId ?: account?.registrationId) }
            require(registration != null)
            setSettings(current.settings.copy(
                activeBankCode = registration.bankCode ?: current.settings.activeBankCode,
                subscriptionId = registration.subscriptionId ?: -1,
                selectedRegistrationId = registration.id,
                selectedCardId = card?.id, selectedAccountId = account?.id,
            ))
        }, onSuccess = { after() })
    }

    /** Bind the old unscoped credential only to the line selected in the old installation. */
    fun bindLegacyAccesses(banks: Set<Bank>, subscription: Int) {
        if (subscription < 0) return
        transact(block = {
            val current = snapshot()
            val owner = current.identities.firstOrNull() ?: IdentityRecord("owner", "Mi cartera").also(::putIdentity)
            for (bank in banks) {
                val identity = ProviderIdentity.forBank(bank)
                val candidates = current.registrations.filter {
                    it.providerId == identity.provider.name && it.profileId == identity.profile.name && it.subscriptionId == subscription
                }
                val registration = candidates.singleOrNull() ?: if (candidates.isEmpty()) RegistrationRecord(
                    UUID.randomUUID().toString(), owner.id, identity.provider.name, identity.profile.name,
                    bank.code, subscription, null, bank.name,
                ) else continue
                // An existing scoped access is never replaced by a legacy key.
                if (registration.credentialAlias == null)
                    putRegistration(registration.copy(credentialAlias = bank.name))
            }
        })
    }

    fun registrationFor(identity: ProviderIdentity, subscription: Int): RegistrationRecord {
        require(subscription >= 0)
        val existing = snapshot.registrations.singleOrNull {
            it.providerId == identity.provider.name && it.profileId == identity.profile.name && it.subscriptionId == subscription
        }
        val owner = snapshot.identities.firstOrNull()?.id ?: "owner"
        return existing ?: RegistrationRecord(UUID.randomUUID().toString(), owner, identity.provider.name,
            identity.profile.name, identity.bank?.code, subscription, null, identity.provider.name)
    }

    /** Reserve a stable alias owner before biometric encryption. Cancelling never disables an existing access. */
    fun reserveRegistration(identity: ProviderIdentity, subscription: Int, onFailure: () -> Unit = {},
                            onReserved: (RegistrationRecord) -> Unit) {
        if (subscription < 0 || closing || !scope.isActive || !OperationCatalog.supports(identity)) { onFailure(); return }
        transact("No se pudo preparar el acceso. No se han cambiado las claves.", block = {
            val current = snapshot()
            val candidates = current.registrations.filter { it.providerId == identity.provider.name &&
                it.profileId == identity.profile.name && it.subscriptionId == subscription }
            require(candidates.size <= 1) { "Hay varios accesos asociados a esta línea" }
            candidates.singleOrNull() ?: run {
                val owner = current.identities.firstOrNull() ?: IdentityRecord("owner", "Mi cartera").also(::putIdentity)
                RegistrationRecord(UUID.randomUUID().toString(), owner.id, identity.provider.name, identity.profile.name,
                    identity.bank?.code, subscription, null, identity.provider.name, enabled = false).also(::putRegistration)
            }
        }, onFailure = onFailure, onSuccess = onReserved).invokeOnCompletion { failure ->
            if (failure is CancellationException) android.os.Handler(android.os.Looper.getMainLooper()).post(onFailure)
        }
    }

    fun saveRegistration(value: RegistrationRecord, done: () -> Unit) {
        transact(block = {
            if (snapshot().identities.none { it.id == value.identityId }) putIdentity(IdentityRecord(value.identityId, "Mi cartera"))
            putRegistration(value)
            val current = snapshot().settings
            setSettings(current.copy(selectedRegistrationId = value.id,
                activeBankCode = value.bankCode ?: current.activeBankCode,
                subscriptionId = value.subscriptionId ?: -1, selectedCardId = null, selectedAccountId = null))
        }, onSuccess = { done() })
    }

    /** Completion runs on Main after all cancelled IO has actually left Room. Never block Main waiting for it. */
    fun close(onClosed: () -> Unit) {
        if (closed) { onClosed(); return }
        closeCompletions += onClosed
        if (closing) return
        closing = true
        val jobs = requireNotNull(scope.coroutineContext[Job])
        jobs.cancel()
        CoroutineScope(Dispatchers.IO).launch {
            jobs.join()
            try { repository.close() }
            finally { withContext(Dispatchers.Main.immediate) {
                closed = true
                closeCompletions.toList().also { closeCompletions.clear() }.forEach { it() }
            } }
        }
    }

    override fun close() { close {} }
}
