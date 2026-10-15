package dev.duardo.neotransfer.engine

import dev.duardo.neotransfer.core.backup.BackupCard
import dev.duardo.neotransfer.core.backup.BackupContent
import dev.duardo.neotransfer.core.backup.BackupFile
import dev.duardo.neotransfer.core.backup.TrmImporter
import dev.duardo.neotransfer.core.ledger.CardBalance
import dev.duardo.neotransfer.core.ledger.Direction
import dev.duardo.neotransfer.core.ledger.Dispatch
import dev.duardo.neotransfer.core.ledger.Ledger
import dev.duardo.neotransfer.core.ledger.LedgerView
import dev.duardo.neotransfer.core.ledger.Movement
import dev.duardo.neotransfer.core.ledger.Operation
import dev.duardo.neotransfer.core.ledger.OperationState
import dev.duardo.neotransfer.core.ledger.OperationStatus
import dev.duardo.neotransfer.core.ledger.Session
import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Cards
import dev.duardo.neotransfer.core.model.Contact
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.core.model.Phones
import dev.duardo.neotransfer.core.model.ServiceReference
import dev.duardo.neotransfer.core.protocol.BalanceQuery
import dev.duardo.neotransfer.core.protocol.BankRequest
import dev.duardo.neotransfer.core.protocol.BankResponse
import dev.duardo.neotransfer.core.protocol.Cubacel
import dev.duardo.neotransfer.core.protocol.Disconnect
import dev.duardo.neotransfer.core.protocol.HistoryQuery
import dev.duardo.neotransfer.core.protocol.SecretKind
import dev.duardo.neotransfer.core.protocol.Source
import dev.duardo.neotransfer.core.protocol.UssdCommand
import dev.duardo.neotransfer.core.protocol.UssdRequest
import dev.duardo.neotransfer.core.sms.SmsMessage
import dev.duardo.neotransfer.core.sms.SmsRecord
import dev.duardo.neotransfer.data.Preferences
import dev.duardo.neotransfer.data.Settings
import dev.duardo.neotransfer.data.Store
import dev.duardo.neotransfer.data.StoredSms
import dev.duardo.neotransfer.data.WalletCard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher

/** A wallet card and its projected balance: [CardBalance.value], [CardBalance.observedAt] (age), [CardBalance.updatedAt]. */
data class CardView(val card: WalletCard, val balance: CardBalance)

sealed interface Submission {
    data class Queued(val id: String) : Submission

    /** An identical payment is still pending: ask, and submit again with `confirmed = true` to send another one. */
    data class ConfirmDuplicate(val pending: OperationStatus) : Submission

    /** The bank has no saved key, or the bank rejected it: the key must be saved (again) first. */
    data class KeyRequired(val bank: Bank) : Submission
}

/** What an import added; what already existed is kept as it was. */
data class ImportSummary(val cards: Int, val contacts: Int, val services: Int)

/**
 * The green app between the pure core and the UI: domain state as [StateFlow]s and intents as
 * suspend functions. It sends and moves on: a USSD request goes out after the previous one is
 * acknowledged, its SMS answer is applied whenever it arrives, and nothing waits on an SMS.
 *
 * Every intent runs in the controller's own scope, so leaving a screen never cuts one in half.
 * Intents throw [IllegalArgumentException] / [IllegalStateException] with a message ready to show.
 */
class AppController(
    private val store: Store,
    private val modem: UssdModem,
    private val inbox: SmsInbox,
    private val vault: KeyVault,
    private val notifier: Notifier,
    private val documents: Documents,
    private val phoneContacts: PhoneContacts,
    private val clock: () -> Instant,
    /** Runs one coroutine at a time: all mutable state below is confined to it. */
    state: CoroutineDispatcher,
    private val io: CoroutineDispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + state)
    private val startup = CompletableDeferred<Unit>()

    private val operationsById = linkedMapOf<String, Operation>()
    private val sms = mutableMapOf<Long, StoredSms>()
    private val records = mutableMapOf<Long, SmsRecord>()
    private var wallet = emptyList<WalletCard>()
    private var preferences = Preferences()
    private var view: LedgerView? = null
    private var sending = false
    private var foreground = false
    /** Bank keys, in memory only while the app is unlocked. */
    private val keys = mutableMapOf<Bank, CharArray>()
    /** Cubacel transfer keys typed for an operation not sent yet; never stored. */
    private val transferKeys = mutableMapOf<String, CharArray>()
    private var wake: Job? = null
    private var lockTimer: Job? = null
    private var inboxCheck: Job? = null

    private val _cards = MutableStateFlow(emptyList<CardView>())
    private val _operations = MutableStateFlow(emptyList<OperationStatus>())
    private val _movements = MutableStateFlow(emptyList<Movement>())
    private val _contacts = MutableStateFlow(emptyList<Contact>())
    private val _services = MutableStateFlow(emptyList<ServiceReference>())
    private val _session = MutableStateFlow<Session?>(null)
    private val _blockedBanks = MutableStateFlow(emptySet<Bank>())
    private val _savedKeys = MutableStateFlow(emptySet<Bank>())
    private val _settings = MutableStateFlow(Settings())
    private val _locked = MutableStateFlow(true)
    private val _loaded = MutableStateFlow(false)
    private val _lastBackup = MutableStateFlow<Instant?>(null)

    /** Own cards in wallet order, each with its balance. */
    val cards: StateFlow<List<CardView>> = _cards
    /** User operations with their state, newest first (automatic authentications never appear). */
    val operations: StateFlow<List<OperationStatus>> = _operations
    /** Activity, newest first. */
    val movements: StateFlow<List<Movement>> = _movements
    val contacts: StateFlow<List<Contact>> = _contacts
    val services: StateFlow<List<ServiceReference>> = _services
    /** The bank session the app counts on (60 min, one bank); it survives the UI lock. */
    val session: StateFlow<Session?> = _session
    /** Banks whose saved key the bank rejected; nothing goes out for them until it is saved again. */
    val blockedBanks: StateFlow<Set<Bank>> = _blockedBanks
    /** Banks with a saved key. */
    val savedKeys: StateFlow<Set<Bank>> = _savedKeys
    val settings: StateFlow<Settings> = _settings
    /** True until the fingerprint unlocks the app, and again after 1 min in the background. */
    val locked: StateFlow<Boolean> = _locked
    /** False until the stored state is in memory: before that every other flow is still empty. */
    val loaded: StateFlow<Boolean> = _loaded
    /** When the last encrypted backup was written. */
    val lastBackup: StateFlow<Instant?> = _lastBackup

    init {
        scope.launch {
            val snapshot = store.load()
            snapshot.operations.forEach { operationsById[it.id] = it }
            snapshot.sms.forEach { sms[it.id] = it; records[it.id] = it.record() }
            wallet = snapshot.cards
            _contacts.value = snapshot.contacts.sortedBy { it.name.lowercase() }
            _services.value = snapshot.services
            preferences = snapshot.preferences
            startup.complete(Unit)
            publish()
            _loaded.value = true
            ingest()
        }
    }

    // ---- Lifecycle and SMS ----

    /** The app came to the foreground: cancel the pending lock and catch up with the inbox. */
    fun onForeground() {
        scope.launch {
            foreground = true
            lockTimer?.cancel()
            startup.await()
            ingest()
        }
    }

    /** The app went to the background: lock the UI after [LOCK_AFTER]; the bank session stays. */
    fun onBackground() {
        scope.launch {
            foreground = false
            lockTimer?.cancel()
            lockTimer = scope.launch {
                delay(LOCK_AFTER.toMillis())
                lock()
            }
        }
    }

    /** Permissions changed (READ_SMS may have just been granted): import what is missing. */
    fun onPermissionsChanged() {
        scope.launch { startup.await(); ingest() }
    }

    /**
     * A PAGOxMOVIL SMS arrived (SMS_RECEIVED). The default SMS app writes it to the inbox a moment
     * later, so the inbox is read from the last `_id` a few times until it shows up.
     */
    fun onBankSms(): Job = scope.launch {
        startup.await()
        repeat(INBOX_POLLS) {
            if (ingest() > 0) return@launch
            delay(INBOX_POLL_INTERVAL.toMillis())
        }
    }

    /** The SMS store changed while the process lives (a late inbox write included): read from the last `_id`. */
    fun onInboxChanged() {
        scope.launch {
            if (inboxCheck?.isActive == true) return@launch
            inboxCheck = scope.launch { startup.await(); ingest() }
        }
    }

    // ---- Unlock and bank keys ----

    /**
     * Cipher for the fingerprint prompt at unlock; null when no key is saved (or a new fingerprint
     * invalidated them), and then the fingerprint is confirmed without crypto.
     */
    suspend fun unlockCipher(): Cipher? = onState {
        if (!vault.hasKeys()) return@onState null
        val cipher = vault.decryptCipher()
        if (cipher == null) forgetKeys()
        cipher
    }

    /** After the fingerprint: [authorized] is the cipher from [unlockCipher] (null when it gave none). */
    suspend fun unlock(authorized: Cipher?) = onState {
        if (authorized != null) {
            val plaintext = vault.decrypt(authorized)
            try {
                wipeKeys()
                keys.putAll(BankKeys.decode(plaintext))
            } finally {
                plaintext.fill(0)
            }
        }
        _locked.value = false
        publish()
    }

    /** Cipher for the fingerprint prompt before [saveKey]. */
    suspend fun keyCipher(): Cipher = onState { vault.encryptCipher() }

    /** Saves a bank key, or forgets it with `key = null`. Saving again unblocks a rejected bank. */
    suspend fun saveKey(authorized: Cipher, bank: Bank, key: CharArray?) = onState {
        try {
            check(!_locked.value) { "Desbloquea la app primero" }
            require(key == null || (key.size == bank.pinLength && key.all { it in '0'..'9' })) { "La clave tiene ${bank.pinLength} dígitos" }
            keys.remove(bank)?.fill('\u0000')
            if (key != null) keys[bank] = key.copyOf()
            if (keys.isEmpty()) vault.reset()
            else BankKeys.encode(keys).let { plaintext -> try { vault.encrypt(authorized, plaintext) } finally { plaintext.fill(0) } }
            val savedAt = if (key == null) preferences.keySavedAt - bank else preferences.keySavedAt + (bank to clock())
            preferences = preferences.copy(keySavedAt = savedAt)
            store.savePreferences(preferences)
            publish()
        } finally {
            key?.fill('\u0000')
        }
    }

    // ---- Cards and settings ----

    /** Adds the card at the end of the wallet, or replaces the one with the same number. */
    suspend fun saveCard(card: WalletCard) = onState {
        val updated = card.copy(holder = card.holder?.trim()?.ifEmpty { null }, expiry = card.expiry?.trim()?.ifEmpty { null })
        val next = if (wallet.any { it.card.number == card.card.number }) wallet.map { if (it.card.number == card.card.number) updated else it } else wallet + updated
        store.saveCards(next)
        wallet = next
        publish()
    }

    suspend fun deleteCard(number: String) = onState {
        store.deleteCard(number)
        wallet = wallet.filterNot { it.card.number == number }
        publish()
    }

    suspend fun updateSettings(settings: Settings) = onState {
        preferences = preferences.copy(settings = settings)
        store.savePreferences(preferences)
        publish()
    }

    // ---- Operations ----

    /** With one card of its bank the default account is enough; with several, the card goes explicitly. */
    fun sourceFor(card: String): Source = ownCard(card).let { own -> Source.forCard(own, _cards.value.map { it.card.card }) }

    suspend fun refreshBalance(card: String): Submission = onState { submit(BalanceQuery(ownCard(card).bank, sourceFor(card))) }

    suspend fun refreshHistory(card: String): Submission = onState { submit(HistoryQuery(ownCard(card).bank, sourceFor(card))) }

    /**
     * Queues [request]: it goes out as soon as the SIM is free (after a `40` when the bank has no
     * session). Money identical to a still-pending payment is not queued unless [confirmed].
     * [transferKey] is the Cubacel transfer key, kept in memory until the request leaves.
     */
    suspend fun submit(request: UssdRequest, confirmed: Boolean = false, transferKey: CharArray? = null): Submission = onState {
        try {
            publish()
            val current = checkNotNull(view)
            val bank = (request as? BankRequest)?.takeUnless { it is Disconnect }?.bank
            val duplicate = if (confirmed) null else current.pendingDuplicate(request)
            when {
                bank != null && (bank !in preferences.keySavedAt || bank in current.blockedBanks) -> Submission.KeyRequired(bank)
                duplicate != null -> Submission.ConfirmDuplicate(duplicate)
                else -> {
                    require(request.secret != SecretKind.CUBACEL_PIN || (transferKey?.size == 4 && transferKey.all { it in '0'..'9' })) {
                        "La clave de transferencia tiene 4 dígitos"
                    }
                    val operation = Ledger.submit(UUID.randomUUID().toString(), request, clock())
                    // Stored before anything can dispatch it, so a send is never recorded first.
                    store.saveOperation(operation)
                    transferKey?.let { transferKeys[operation.id] = it.copyOf() }
                    operationsById[operation.id] = operation
                    publish()
                    Submission.Queued(operation.id)
                }
            }
        } finally {
            transferKey?.fill('\u0000')
        }
    }

    /** "Reintentar" of an operation that did not go through: a new operation with the same request. */
    suspend fun retry(id: String, transferKey: CharArray? = null): Submission = onState {
        val status = checkNotNull(view?.status(id)) { "La operación no existe" }
        check(status.state is OperationState.NotExecuted) { "Esta operación no se puede reintentar" }
        submit(status.operation.request, confirmed = true, transferKey = transferKey)
    }

    /** Cubacel plans and packages menu: dialed, not queued; the system shows the menu. */
    fun cubacelPackages(path: List<Int> = emptyList()) = modem.dial(Cubacel.packages(path))

    // ---- Contacts and saved services ----

    /** Adds or replaces a contact; phones and cards are normalized, a blank id gets a new one. */
    suspend fun saveContact(contact: Contact): Contact = onState {
        val clean = Contact(
            contact.id.ifBlank { UUID.randomUUID().toString() },
            contact.name.trim(),
            contact.phones.map { requireNotNull(Phones.normalize(it)) { "El móvil $it no es válido" } }.distinct(),
            contact.cards.map { requireNotNull(Cards.normalize(it)) { "La tarjeta $it no tiene 16 dígitos" } }.distinct(),
        )
        val labels = contact.labels.mapNotNull { (card, label) ->
            val number = Cards.normalize(card)?.takeIf { it in clean.cards }
            val name = label.split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ")
            if (number != null && name.isNotEmpty()) number to name else null
        }.toMap()
        require(clean.name.isNotEmpty()) { "Escribe un nombre" }
        val saved = clean.copy(labels = labels)
        store.saveContacts(listOf(saved))
        _contacts.value = (_contacts.value.filterNot { it.id == saved.id } + saved).sortedBy { it.name.lowercase() }
        saved
    }

    suspend fun deleteContact(id: String) = onState {
        store.deleteContact(id)
        _contacts.value = _contacts.value.filterNot { it.id == id }
    }

    /** Imports the contact picked in the phone's contacts app; importing it again adds new phones. */
    suspend fun importPhoneContact(uri: String): Contact = onState {
        val picked = withContext(io) { phoneContacts.read(uri) }
        val id = "phone:${picked.key}"
        val existing = _contacts.value.firstOrNull { it.id == id }
        saveContact(Contact(id, existing?.name ?: picked.name, existing?.phones.orEmpty() + picked.phones, existing?.cards.orEmpty(), existing?.labels.orEmpty()))
    }

    suspend fun saveService(service: ServiceReference) = onState {
        val clean = service.copy(value = service.value.trim(), label = service.label?.trim()?.ifEmpty { null })
        require(clean.value.isNotEmpty()) { "Escribe la referencia" }
        store.saveServices(listOf(clean))
        _services.value = _services.value.filterNot { it.kind == clean.kind && it.value == clean.value } + clean
    }

    suspend fun deleteService(service: ServiceReference) = onState {
        store.deleteService(service)
        _services.value = _services.value.filterNot { it.kind == service.kind && it.value == service.value }
    }

    // ---- Backup and imports ----

    /** Writes the encrypted backup (format 4: cards, contacts, services; never keys) to [uri]. */
    suspend fun exportBackup(uri: String, password: CharArray) = onState {
        try {
            val content = BackupContent(wallet.map { BackupCard(it.card, it.holder, it.expiry) }, _contacts.value, _services.value)
            val file = withContext(io) { BackupFile.export(content, password) }
            withContext(io) { documents.write(uri, file) }
            preferences = preferences.copy(lastBackup = clock())
            store.savePreferences(preferences)
            publish()
        } finally {
            password.fill('\u0000')
        }
    }

    /** @throws dev.duardo.neotransfer.core.backup.BackupFailure wrong password, damaged file or unknown format */
    suspend fun importBackup(uri: String, password: CharArray): ImportSummary = onState {
        try {
            val file = withContext(io) { documents.read(uri) }
            merge(withContext(io) { BackupFile.import(file, password) })
        } finally {
            password.fill('\u0000')
        }
    }

    /** Transfermóvil's `.trm`: cards, contacts with their phones and cards, Nauta accounts and invoices. */
    suspend fun importTrm(uri: String): ImportSummary = onState {
        val file = withContext(io) { documents.read(uri) }
        merge(withContext(io) { TrmImporter.import(file) }.content)
    }

    /** Stops the controller (tests). */
    fun close() = scope.cancel()

    // ---- Engine ----

    private suspend fun <T> onState(block: suspend () -> T): T = scope.async { startup.await(); block() }.await()

    private fun ownCard(number: String): OwnCard =
        requireNotNull(_cards.value.firstOrNull { it.card.card.number == number }) { "Esa tarjeta no está en la cartera" }.card.card

    /** Evaluates the ledger, publishes it, announces what changed and sends the next request if the SIM is free. */
    private fun publish(fresh: List<SmsRecord> = emptyList()) {
        val previous = view
        val current = Ledger.evaluate(operationsById.values.toList(), records.values.toList(), wallet.map { it.card }, clock(), preferences.keySavedAt)
        view = current
        _cards.value = wallet.map { CardView(it, current.balances.getValue(it.card.number)) }
        _operations.value = current.operations.reversed()
        _movements.value = current.movements
        _session.value = current.session
        _blockedBanks.value = current.blockedBanks
        _savedKeys.value = preferences.keySavedAt.keys
        _settings.value = preferences.settings
        _lastBackup.value = preferences.lastBackup
        if (previous != null) announce(previous, current, fresh)
        scheduleWake(listOfNotNull(current.wakeAt, current.session?.until).minOrNull())
        val next = current.next
        if (!sending && next != null && keyAvailable(next.operation)) {
            sending = true
            scope.launch { send(next) }
        }
    }

    /** An automatic `40` or a QR payment needs the bank key, in memory only while unlocked: otherwise it waits. */
    private fun keyAvailable(operation: Operation): Boolean =
        operation.request.secret != SecretKind.BANK_PIN || (operation.request as BankRequest).bank in keys

    /**
     * Records the hand-off before calling the modem (a send never goes unrecorded), then records the
     * acknowledgement. No acknowledgement within [Ledger.ACK_TIMEOUT] leaves the attempt open: the
     * ledger decides what that means.
     */
    private suspend fun send(dispatch: Dispatch) {
        try {
            val request = dispatch.operation.request
            val at = clock()
            val operation = dispatch.operation.sending(at)
            val secret = when (request.secret) {
                SecretKind.BANK_PIN -> keys[(request as BankRequest).bank]?.copyOf()
                SecretKind.CUBACEL_PIN -> transferKeys.remove(operation.id)
                null -> null
            }
            val commands = try {
                request.commands(secret, at)
            } catch (e: IllegalArgumentException) {
                val reason = if (request.secret == SecretKind.CUBACEL_PIN && secret == null) "Vuelve a escribir la clave de transferencia" else e.message
                keep(operation.failed(reason ?: "No se pudo preparar la solicitud", at))
                return
            } finally {
                secret?.fill('\u0000')
            }
            operationsById[operation.id] = operation
            publish()
            try {
                store.saveOperation(operation)
            } catch (e: Exception) {
                operationsById[operation.id] = operation.failed("No se pudo guardar la operación", clock())
                return
            }
            val reply = withTimeoutOrNull(Ledger.ACK_TIMEOUT.toMillis()) { exchange(commands) } ?: return
            keep(when (reply) {
                is UssdReply.Text -> operation.replied(reply.text, clock())
                is UssdReply.Failure -> operation.failed(reply.reason, clock())
            })
        } finally {
            sending = false
            publish()
        }
    }

    /** Several commands only for an oversized payment: each part goes after the previous one is "siendo procesada". */
    private suspend fun exchange(commands: List<UssdCommand>): UssdReply {
        for (command in commands) {
            val reply = modem.request(command)
            if (command === commands.last() || reply !is UssdReply.Text || BankResponse.parse(reply.text) != BankResponse.PROCESSING) return reply
        }
        error("Solicitud sin comandos")
    }

    private suspend fun keep(operation: Operation) {
        operationsById[operation.id] = operation
        store.saveOperation(operation)
    }

    private fun scheduleWake(at: Instant?) {
        wake?.cancel()
        wake = at?.let {
            scope.launch {
                delay(Duration.between(clock(), it).toMillis().coerceAtLeast(0))
                wake = null
                publish()
            }
        }
    }

    /**
     * Notifications come from comparing two evaluations: a credit SMS ingested now that adds a new
     * incoming movement, and (with the app not in front) an operation that just got its result,
     * money that did not go through included.
     */
    private fun announce(previous: LedgerView, current: LedgerView, fresh: List<SmsRecord>) {
        val settings = preferences.settings
        if (settings.creditNotifications && fresh.isNotEmpty()) {
            val credited = fresh.mapNotNullTo(mutableSetOf()) { (it.message as? SmsMessage.TransferReceived)?.reference }
            val known = previous.movements.mapTo(mutableSetOf()) { it.id }
            current.movements.filter { it.direction == Direction.IN && it.reference in credited && it.id !in known }
                .forEach { notifier.post(Notices.credit(it, _contacts.value)) }
        }
        if (settings.resultNotifications && !foreground) {
            val before = previous.operations.associate { it.operation.id to it.state }
            current.operations.filter { (operation, state) ->
                val result = state is OperationState.Confirmed || state is OperationState.Rejected ||
                    (state is OperationState.NotExecuted && operation.request.duplicateKey != null)
                result && before[operation.id]?.javaClass != state.javaClass
            }.forEach { notifier.post(Notices.result(it, _contacts.value)) }
        }
    }

    /**
     * Reads the inbox from the last stored `_id` (inclusive: Android may reuse the id of a deleted
     * newest SMS). The first import rebuilds the activity silently; later SMS are announced.
     */
    private suspend fun ingest(): Int {
        val from = sms.keys.maxOrNull() ?: 0L
        val rows = try {
            withContext(io) { inbox.read(from) }
        } catch (_: SecurityException) {
            return 0
        }
        val fresh = rows.filter { sms[it.id]?.receivedAt?.toEpochMilli() != it.date }
            .mapNotNull { row -> SmsRecord.from(row.id, SmsRecord.SENDER, row.date, row.dateSent, row.body)?.let { row to it } }
        if (fresh.isEmpty() && preferences.inboxImported) return 0
        val stored = fresh.map { (row, record) ->
            // The registration SMS carries the bank key and the card PIN in clear: its body is never copied.
            StoredSms(row.id, record.sentAt, record.receivedAt, if (record.message == SmsMessage.Registration) REGISTRATION else row.body)
                .also { sms[row.id] = it; records[row.id] = record }
        }
        val announce = preferences.inboxImported
        preferences = preferences.copy(inboxImported = true)
        publish(if (announce) fresh.map { it.second } else emptyList())
        if (stored.isNotEmpty()) store.saveSms(stored)
        if (!announce) store.savePreferences(preferences)
        return stored.size
    }

    private fun lock() {
        wipeKeys()
        _locked.value = true
    }

    private fun wipeKeys() {
        keys.values.forEach { it.fill('\u0000') }
        keys.clear()
    }

    private suspend fun forgetKeys() {
        wipeKeys()
        vault.reset()
        preferences = preferences.copy(keySavedAt = emptyMap())
        store.savePreferences(preferences)
        publish()
    }

    /** Adds what is missing; a contact already present (same id) gains the new phones and cards. */
    private suspend fun merge(content: BackupContent): ImportSummary {
        val numbers = wallet.mapTo(mutableSetOf()) { it.card.number }
        val cards = content.cards.filter { numbers.add(it.card.number) }.map { WalletCard(it.card, it.holder, it.expiry) }
        val existing = _contacts.value.associateBy { it.id }
        val contacts = content.contacts.mapNotNull { incoming ->
            val current = existing[incoming.id] ?: return@mapNotNull incoming
            current.copy(
                phones = (current.phones + incoming.phones).distinct(),
                cards = (current.cards + incoming.cards).distinct(),
                labels = incoming.labels + current.labels,
            ).takeIf { it != current }
        }
        val services = content.services.distinctBy { it.kind to it.value }
            .filter { service -> _services.value.none { it.kind == service.kind && it.value == service.value } }
        if (cards.isNotEmpty()) store.saveCards(wallet + cards)
        if (contacts.isNotEmpty()) store.saveContacts(contacts)
        if (services.isNotEmpty()) store.saveServices(services)
        wallet = wallet + cards
        _contacts.value = (_contacts.value.filterNot { contact -> contacts.any { it.id == contact.id } } + contacts).sortedBy { it.name.lowercase() }
        _services.value = _services.value + services
        publish()
        return ImportSummary(cards.size, content.contacts.count { it.id !in existing }, services.size)
    }

    companion object {
        val LOCK_AFTER: Duration = Duration.ofMinutes(1)
        /** Within a receiver's time limit; [onInboxChanged] catches a slower inbox write. */
        private const val INBOX_POLLS = 12
        private val INBOX_POLL_INTERVAL: Duration = Duration.ofMillis(500)
        /** Stored instead of the registration SMS; it still reads as one. */
        private const val REGISTRATION = "La operacion de registro fue completada."
    }
}
