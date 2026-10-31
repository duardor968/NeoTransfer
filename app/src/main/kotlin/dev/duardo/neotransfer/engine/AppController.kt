package dev.duardo.neotransfer.engine

import dev.duardo.neotransfer.core.backup.BackupCard
import dev.duardo.neotransfer.core.backup.BackupContent
import dev.duardo.neotransfer.core.backup.BackupFile
import dev.duardo.neotransfer.core.backup.TrmImporter
import dev.duardo.neotransfer.core.ledger.ActivityRow
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
import dev.duardo.neotransfer.core.ledger.isPending
import dev.duardo.neotransfer.core.model.Agency
import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Cards
import dev.duardo.neotransfer.core.model.Contact
import dev.duardo.neotransfer.core.model.ContactRecord
import dev.duardo.neotransfer.core.model.ContactSync
import dev.duardo.neotransfer.core.model.Currency
import dev.duardo.neotransfer.core.model.MiTransfer
import dev.duardo.neotransfer.core.model.Money
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.core.model.Phones
import dev.duardo.neotransfer.core.model.ServiceKind
import dev.duardo.neotransfer.core.model.ServiceReference
import dev.duardo.neotransfer.core.protocol.BalanceQuery
import dev.duardo.neotransfer.core.protocol.BankRegistration
import dev.duardo.neotransfer.core.protocol.BankResponse
import dev.duardo.neotransfer.core.protocol.ClassicBalanceQuery
import dev.duardo.neotransfer.core.protocol.ClassicHistoryQuery
import dev.duardo.neotransfer.core.protocol.ClassicQrPay
import dev.duardo.neotransfer.core.protocol.ClassicTransfer
import dev.duardo.neotransfer.core.protocol.Cubacel
import dev.duardo.neotransfer.core.protocol.Disconnect
import dev.duardo.neotransfer.core.protocol.ElectricityBill
import dev.duardo.neotransfer.core.protocol.HistoryQuery
import dev.duardo.neotransfer.core.protocol.MiTransferRegistration
import dev.duardo.neotransfer.core.protocol.MobileRecharge
import dev.duardo.neotransfer.core.protocol.NautaPayment
import dev.duardo.neotransfer.core.protocol.NautaService
import dev.duardo.neotransfer.core.protocol.ParameterCodec
import dev.duardo.neotransfer.core.protocol.PhoneBill
import dev.duardo.neotransfer.core.protocol.PinChange
import dev.duardo.neotransfer.core.protocol.QrPay
import dev.duardo.neotransfer.core.protocol.SecretKind
import dev.duardo.neotransfer.core.protocol.Source
import dev.duardo.neotransfer.core.protocol.Transfer
import dev.duardo.neotransfer.core.protocol.UssdCommand
import dev.duardo.neotransfer.core.protocol.UssdRequest
import dev.duardo.neotransfer.core.protocol.UssdTransport
import dev.duardo.neotransfer.core.protocol.WaterBill
import dev.duardo.neotransfer.core.protocol.WaterRegion
import dev.duardo.neotransfer.core.qr.QrPayment
import dev.duardo.neotransfer.core.sms.SmsMessage
import dev.duardo.neotransfer.core.sms.SmsParser
import dev.duardo.neotransfer.core.sms.SmsRecord
import dev.duardo.neotransfer.data.ClassicCard
import dev.duardo.neotransfer.data.PendingKey
import dev.duardo.neotransfer.data.Preferences
import dev.duardo.neotransfer.data.Settings
import dev.duardo.neotransfer.data.Store
import dev.duardo.neotransfer.data.StoredSms
import dev.duardo.neotransfer.data.WalletCard
import kotlinx.coroutines.CancellationException
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
import java.math.BigDecimal
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

    /**
     * The bank or MiTransfer has no saved key, rejected the saved one, or took a newer one that waits to be saved
     * ([AppController.keysToSave]): the key must be saved first.
     */
    data class KeyRequired(val agency: Agency) : Submission
}

/**
 * A key the bank or MiTransfer took, confirmed [at] that time, that the app has not saved yet: a registration's (`49`)
 * or a key change's (`69`). Until it is saved nothing that needs [agency]'s key goes out, with the old one or without.
 * [inHand]: [AppController.saveReceivedKey] saves it with the fingerprint alone (the registration SMS is read again
 * from the inbox, or the new key the person typed is still in memory); otherwise the person types it
 * ([AppController.saveKey]).
 */
data class KeyToSave(val agency: Agency, val at: Instant, val inHand: Boolean)

/** What an import added; what already existed is kept as it was. [cards] counts the Clásicas too. */
data class ImportSummary(val cards: Int, val contacts: Int, val services: Int)

/**
 * The green app between the pure core and the UI: domain state as [StateFlow]s and intents as
 * suspend functions. It sends and moves on: a USSD request goes out after the previous one is
 * acknowledged, its SMS answer is applied whenever it arrives, and nothing waits on an SMS.
 *
 * Every intent runs in the controller's own scope, so leaving a screen never cuts one in half.
 * Intents throw [IllegalArgumentException] / [IllegalStateException] with a message ready to show.
 *
 * Keys. Each bank and MiTransfer (the Clásica's PIN) has one saved key, encrypted in the [KeyVault] under the
 * fingerprint and in memory only while the app is unlocked. Values typed for one request (Cubacel's transfer key, the
 * value of a coordinate, the new key of a change or of MiTransfer's registration) are handed to [submit] as `CharArray`:
 * the controller copies them, wipes the arrays it was given at once and wipes its copies as soon as the commands are
 * built. Only a new key outlives the send, in memory while unlocked, until the answer: when it is confirmed it waits in
 * [keysToSave] for the fingerprint.
 *
 * A registration (`49`) answers with the bank's key in an SMS. Neither the SMS body nor the key is stored: when it
 * confirms an operation of the app, [keysToSave] records the SMS `_id` and the time, and [saveReceivedKey] reads the key
 * again from the inbox, saves it like a typed one and wipes it. That holds with the app locked or the process gone: the
 * key stays only in the phone's own SMS inbox until the fingerprint, after unlocking, saves it.
 *
 * A key change (`69`) has no known answer: a bank SMS of unknown wording that answers it ([SmsMessage.BankText]) is
 * shown as it came and the new key waits in [keysToSave]; the person reads it and saves the key or discards it. While a
 * change is pending, every run of digits of that bank's key length in such an SMS is masked before it is kept or
 * evaluated ([maskKeys]), so neither the stored text nor the shown one can hold the new key.
 */
class AppController(
    private val store: Store,
    private val modem: UssdModem,
    private val inbox: SmsInbox,
    private val vault: KeyVault,
    private val notifier: Notifier,
    private val documents: Documents,
    private val phoneBook: PhoneBook,
    private val peer: PeerContacts,
    private val peerOperations: PeerActivity,
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
    private var classicCards = emptyList<ClassicCard>()
    /** Contacts as stored, by id, tombstones included: address-book people only when the app keeps cards for them. */
    private val storedRecords = linkedMapOf<String, ContactRecord>()
    /** The [storedRecords] not deleted. */
    private val stored: List<Contact> get() = storedRecords.values.filterNot { it.deleted }.map { it.contact }
    /** The phone's address book; null while it cannot be read. */
    private var agenda: List<PhoneContact>? = null
    private var preferences = Preferences()
    private var view: LedgerView? = null
    private var sending = false
    private var foreground = false
    /** Saved keys of the banks and MiTransfer, in memory only while the app is unlocked. */
    private val keys = mutableMapOf<Agency, CharArray>()
    /** [keys] holds every saved key: the fingerprint decrypted the vault, or there is nothing in it. */
    private var keysLoaded = false
    /** What the person typed for an operation not sent yet ([submit]); never stored. */
    private val typed = mutableMapOf<String, Typed>()
    /** New keys of a key change or of MiTransfer's registration already sent, by operation, until their answer. */
    private val sentKeys = mutableMapOf<String, CharArray>()
    /** New keys the bank or MiTransfer confirmed, waiting for the fingerprint ([saveReceivedKey]). */
    private val confirmedKeys = mutableMapOf<Agency, CharArray>()
    private var wake: Job? = null
    private var lockTimer: Job? = null
    private var inboxCheck: Job? = null

    private val _cards = MutableStateFlow(emptyList<CardView>())
    private val _classics = MutableStateFlow(emptyList<ClassicCard>())
    private val _operations = MutableStateFlow(emptyList<OperationStatus>())
    private val _movements = MutableStateFlow(emptyList<Movement>())
    private val _contacts = MutableStateFlow(emptyList<Contact>())
    private val _addressBook = MutableStateFlow<Set<String>?>(null)
    private val _services = MutableStateFlow(emptyList<ServiceReference>())
    private val _session = MutableStateFlow<Session?>(null)
    private val _blocked = MutableStateFlow(emptySet<Agency>())
    private val _savedKeys = MutableStateFlow(emptySet<Agency>())
    private val _keysToSave = MutableStateFlow(emptyList<KeyToSave>())
    private val _keysLocked = MutableStateFlow(false)
    private val _settings = MutableStateFlow(Settings())
    private val _locked = MutableStateFlow(true)
    private val _loaded = MutableStateFlow(false)
    private val _lastBackup = MutableStateFlow<Instant?>(null)
    private val _peerActivity = MutableStateFlow<List<ActivityRow>?>(null)

    /** Own bank cards in wallet order, each with its balance. */
    val cards: StateFlow<List<CardView>> = _cards
    /**
     * Own Clásicas; the wallet shows them after the bank cards. The core reads no figure from MiTransfer's balance
     * answer: what there is to show is the [OperationState.Answered] text of the last [ClassicBalanceQuery].
     */
    val classics: StateFlow<List<ClassicCard>> = _classics
    /** User operations with their state, newest first (automatic authentications never appear). */
    val operations: StateFlow<List<OperationStatus>> = _operations
    /** Activity, newest first. */
    val movements: StateFlow<List<Movement>> = _movements
    /** Everyone in the phone's address book plus the contacts kept only in the app, by name ([Directory]). */
    val contacts: StateFlow<List<Contact>> = _contacts
    /** Ids of the [contacts] that come from the phone's address book; null while it cannot be read. */
    val addressBook: StateFlow<Set<String>?> = _addressBook
    val services: StateFlow<List<ServiceReference>> = _services
    /** The session the app counts on (60 min, one bank or MiTransfer); it survives the UI lock. */
    val session: StateFlow<Session?> = _session
    /** Banks and MiTransfer whose saved key was rejected; nothing that needs it goes out until it is saved again. */
    val blocked: StateFlow<Set<Agency>> = _blocked
    /** Banks and MiTransfer with a saved key (MiTransfer's is the PIN). */
    val savedKeys: StateFlow<Set<Agency>> = _savedKeys
    /** Keys a registration or a key change brought, waiting to be saved ([KeyToSave]). */
    val keysToSave: StateFlow<List<KeyToSave>> = _keysToSave
    /**
     * Unlocked without the saved keys: the fingerprint was confirmed but the vault could not be decrypted (biometrics
     * unavailable, a failed decryption). Nothing is saved over the vault, and nothing that needs a key goes out, until
     * the app is unlocked again with the fingerprint ([unlockCipher] and [unlock]).
     */
    val keysLocked: StateFlow<Boolean> = _keysLocked
    val settings: StateFlow<Settings> = _settings
    /** True until the fingerprint unlocks the app, and again after 1 min in the background. */
    val locked: StateFlow<Boolean> = _locked
    /** False until the stored state is in memory: before that every other flow is still empty. */
    val loaded: StateFlow<Boolean> = _loaded
    /** When the last encrypted backup was written. */
    val lastBackup: StateFlow<Instant?> = _lastBackup
    /**
     * NeoTransfer+'s operations as last read, at start and on every return to the foreground; null while it is not installed
     * or cannot be read, and then nothing of it shows.
     */
    val peerActivity: StateFlow<List<ActivityRow>?> = _peerActivity

    init {
        scope.launch {
            val snapshot = store.load()
            snapshot.operations.forEach { operationsById[it.id] = it }
            snapshot.sms.forEach { sms[it.id] = it; records[it.id] = it.record() }
            wallet = snapshot.cards
            classicCards = snapshot.classics
            snapshot.contacts.forEach { storedRecords[it.contact.id] = it }
            publishContacts()
            _services.value = snapshot.services
            preferences = snapshot.preferences
            startup.complete(Unit)
            publish()
            _loaded.value = true
            pullPeer()
            readAddressBook()
            ingest()
        }
    }

    // ---- Lifecycle and SMS ----

    /** The app came to the foreground: cancel the pending lock, catch up with the inbox and dial what waited for it. */
    fun onForeground() {
        scope.launch {
            foreground = true
            lockTimer?.cancel()
            startup.await()
            pullPeer()
            readAddressBook()
            if (ingest() == 0 && view?.next?.operation?.request?.dialed == true) publish()
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

    /** Permissions changed (READ_SMS or READ_CONTACTS may have just been granted): read what is missing. */
    fun onPermissionsChanged() {
        scope.launch { startup.await(); readAddressBook(); ingest() }
    }

    /** Reads the address book and moves the cards saved for someone onto their entry in it ([Directory.link]). */
    private suspend fun readAddressBook() {
        val people = try {
            withContext(io) { phoneBook.read() }
        } catch (_: SecurityException) {
            null
        }
        agenda = people
        if (people != null) {
            val (save, delete) = Directory.link(stored, people)
            keepContacts(save, delete)
        }
        publishContacts()
    }

    /**
     * Takes what the other app changed in its stored contacts ([ContactSync.merge]). Not installed, not signed like this
     * app or failing: nothing changes, and nothing is logged. Its operations are read too ([peerActivity]).
     */
    private suspend fun pullPeer() {
        _peerActivity.value = try {
            withContext(io) { peerOperations.read() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        val remote = try {
            withContext(io) { peer.read() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        val changes = ContactSync.merge(storedRecords.values, remote)
        if (changes.isEmpty()) return
        store.saveContacts(changes)
        changes.forEach { storedRecords[it.contact.id] = it }
        publishContacts()
    }

    /** Saves [contacts] and turns the [deleted] ids into tombstones, all stamped now for the other app to take. */
    private suspend fun keepContacts(contacts: List<Contact>, deleted: List<String> = emptyList()) {
        val at = clock().toEpochMilli()
        val tombstones = deleted.mapNotNull { id -> storedRecords[id]?.takeUnless { it.deleted } }.map { ContactSync.tombstone(it.contact, at) }
        val rows = contacts.map { ContactRecord(it, at) } + tombstones
        if (rows.isEmpty()) return
        store.saveContacts(rows)
        rows.forEach { storedRecords[it.contact.id] = it }
    }

    private fun publishContacts() {
        _contacts.value = Directory.merge(stored, agenda)
        _addressBook.value = agenda?.mapTo(hashSetOf(), Directory::id)
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

    // ---- Unlock and keys ----

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

    /**
     * After the fingerprint: [authorized] is the cipher from [unlockCipher] (null when it gave none). Without it while
     * the vault holds keys, the app unlocks with [keysLocked].
     */
    suspend fun unlock(authorized: Cipher?) = onState {
        if (authorized != null) {
            val plaintext = vault.decrypt(authorized)
            try {
                wipeKeys()
                keys.putAll(BankKeys.decode(plaintext))
                keysLoaded = true
            } finally {
                plaintext.fill(0)
            }
        } else {
            keysLoaded = !vault.hasKeys()
        }
        _locked.value = false
        publish()
    }

    /** Cipher for the fingerprint prompt before [saveKey] or [saveReceivedKey]. */
    suspend fun keyCipher(): Cipher = onState { vault.encryptCipher() }

    /**
     * Saves the key of a bank or MiTransfer's PIN, or forgets it with `key = null`. Saving again unblocks a rejected
     * one, and settles a key waiting in [keysToSave] (the person typed it instead).
     */
    suspend fun saveKey(authorized: Cipher, agency: Agency, key: CharArray?) = onState { storeKey(authorized, agency, key) }

    /**
     * Saves the key in [keysToSave] for [agency] without typing it: the new key confirmed in memory, or the key of the
     * registration SMS, read again from the inbox and wiped once encrypted.
     * @throws IllegalStateException when it is not in hand any more: the person types it ([saveKey]).
     */
    suspend fun saveReceivedKey(authorized: Cipher, agency: Agency) = onState {
        check(!_locked.value) { "Desbloquea la app primero" }
        check(keysLoaded) { KEYS_LOCKED }
        val pending = checkNotNull(preferences.pendingKeys[agency]) { "No hay ninguna clave que guardar" }
        val key = confirmedKeys.remove(agency) ?: pending.sms?.let { readReceivedKey(it) }
        storeKey(authorized, agency, checkNotNull(key) { "No se encontró la clave: escríbela" })
    }

    /**
     * Drops the key waiting in [keysToSave] for [agency] without saving it: the saved key, if any, goes on being used.
     * Nothing to drop is not an error.
     */
    suspend fun discardReceivedKey(agency: Agency) = onState {
        confirmedKeys.remove(agency)?.fill('\u0000')
        if (agency in preferences.pendingKeys) {
            preferences = preferences.copy(pendingKeys = preferences.pendingKeys - agency)
            store.savePreferences(preferences)
        }
        publish()
    }

    private suspend fun storeKey(authorized: Cipher, agency: Agency, key: CharArray?) {
        try {
            check(!_locked.value) { "Desbloquea la app primero" }
            // The vault is one blob: written from an incomplete set, it would lose the keys not in memory.
            check(keysLoaded) { KEYS_LOCKED }
            require(key == null || (key.size == agency.pinLength && key.all { it in '0'..'9' })) {
                if (agency is Bank) "La clave tiene ${agency.pinLength} dígitos" else "El PIN tiene ${agency.pinLength} dígitos"
            }
            keys.remove(agency)?.fill('\u0000')
            if (key != null) keys[agency] = key.copyOf()
            if (keys.isEmpty()) vault.reset()
            else BankKeys.encode(keys).let { plaintext -> try { vault.encrypt(authorized, plaintext) } finally { plaintext.fill(0) } }
            confirmedKeys.remove(agency)?.fill('\u0000')
            val savedAt = if (key == null) preferences.keySavedAt - agency else preferences.keySavedAt + (agency to clock())
            preferences = preferences.copy(keySavedAt = savedAt, pendingKeys = preferences.pendingKeys - agency)
            store.savePreferences(preferences)
            publish()
        } finally {
            key?.fill('\u0000')
        }
    }

    /** The key in the registration SMS [id], read again from the inbox; null when the SMS or the key is gone. */
    private suspend fun readReceivedKey(id: Long): CharArray? {
        val row = try {
            withContext(io) { inbox.read(id) }.firstOrNull { it.id == id }
        } catch (_: SecurityException) {
            null
        } ?: return null
        val message = SmsRecord.from(row.id, row.address, row.date, row.dateSent, row.body)?.message
        return (message as? SmsMessage.Registration)?.key?.take()
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

    /** Adds a Clásica (`976003…`) to the wallet; MiTransfer's PIN is saved apart ([saveKey] with [MiTransfer]). */
    suspend fun saveClassic(number: String) = onState {
        val card = ClassicCard(number)
        if (card !in classicCards) {
            store.saveClassic(card)
            classicCards = classicCards + card
            publish()
        }
    }

    /** Takes a Clásica out of the app only; its association with MiTransfer is removed by a `ClassicRemoval`. */
    suspend fun deleteClassic(number: String) = onState {
        store.deleteClassic(number)
        classicCards = classicCards.filterNot { it.number == number }
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

    /** Balance of an own card: the bank's `46`, or MiTransfer's for a Clásica. */
    suspend fun refreshBalance(card: String): Submission = onState {
        submit(if (isClassic(card)) ClassicBalanceQuery(card) else BalanceQuery(ownCard(card).bank, sourceFor(card)))
    }

    /** Last operations of an own card: the bank's `48`, or MiTransfer's for a Clásica. */
    suspend fun refreshHistory(card: String): Submission = onState {
        submit(if (isClassic(card)) ClassicHistoryQuery(card) else HistoryQuery(ownCard(card).bank, sourceFor(card)))
    }

    /**
     * The transfer [card] makes: its bank's `45`, or MiTransfer's between Clásicas, in USD, when [card] is a Clásica
     * (then [destination] must be one too).
     */
    fun transferRequest(card: String, destination: String, amount: BigDecimal, notifyPhone: String? = null): UssdRequest =
        if (isClassic(card)) ClassicTransfer(card, destination, Money(amount, Currency.USD), notifyPhone)
        else ownCard(card).let { Transfer(it.bank, sourceFor(card), destination, Money(amount, it.currency), notifyPhone) }

    /** The QR or online payment [card] makes: its bank's `30`/`31`, or MiTransfer's with MiTransfer's PIN for a Clásica. */
    fun qrPaymentRequest(card: String, payment: QrPayment, amount: Money, description: String = "", notifyPhone: String? = null): UssdRequest =
        if (isClassic(card)) ClassicQrPay(card, payment, amount, description, notifyPhone)
        else QrPay(ownCard(card).bank, sourceFor(card), payment, amount, description, notifyPhone)

    /**
     * Queues [request]: it goes out as soon as the SIM is free (after a `40` when its bank or MiTransfer has no
     * session). Money identical to a still-pending payment is not queued unless [confirmed].
     *
     * [secret] is what the request's [UssdRequest.secret] asks besides a saved key: Cubacel's transfer key, or the value
     * of a coordinate. [newKey] is the new key of a key change or of MiTransfer's registration ([UssdRequest.newKeyLength]).
     * Both are copied and the given arrays wiped before this returns; the copies live in memory until the commands are
     * built, and a new key until its answer.
     */
    suspend fun submit(request: UssdRequest, confirmed: Boolean = false, secret: CharArray? = null, newKey: CharArray? = null): Submission = onState {
        try {
            publish()
            val current = checkNotNull(view)
            val agency = request.agency
            val duplicate = if (confirmed) null else current.pendingDuplicate(request)
            when {
                agency != null && needsKey(request) && !keyUsable(agency, current) -> Submission.KeyRequired(agency)
                duplicate != null -> Submission.ConfirmDuplicate(duplicate)
                else -> {
                    when (request.secret) {
                        SecretKind.CUBACEL_PIN -> require(secret?.size == 4 && secret.all { it in '0'..'9' }) { "La clave de transferencia tiene 4 dígitos" }
                        SecretKind.COORDINATE -> require(secret?.size == 2 && ParameterCodec().accepts(String(secret))) { "El valor de la coordenada tiene 2 caracteres" }
                        SecretKind.BANK_PIN, null -> Unit
                    }
                    request.newKeyLength?.let { length ->
                        require(newKey?.size == length && newKey.all { it in '0'..'9' }) { "La clave nueva tiene $length dígitos" }
                    }
                    val operation = Ledger.submit(UUID.randomUUID().toString(), request, clock())
                    // Stored before anything can dispatch it, so a send is never recorded first.
                    store.saveOperation(operation)
                    if (secret != null || newKey != null) typed[operation.id] = Typed(secret?.copyOf(), newKey?.copyOf())
                    operationsById[operation.id] = operation
                    publish()
                    Submission.Queued(operation.id)
                }
            }
        } finally {
            secret?.fill('\u0000')
            newKey?.fill('\u0000')
        }
    }

    /** "Reintentar" of an operation that did not go through: a new operation with the same request, typed values again. */
    suspend fun retry(id: String, secret: CharArray? = null, newKey: CharArray? = null): Submission = onState {
        val status = checkNotNull(view?.status(id)) { "La operación no existe" }
        check(status.state is OperationState.NotExecuted) { "Esta operación no se puede reintentar" }
        submit(status.operation.request, confirmed = true, secret = secret, newKey = newKey)
    }

    /** Cubacel plans and packages menu: dialed, not queued; the system shows the menu. Returns why it was not dialed. */
    fun cubacelPackages(path: List<Int> = emptyList()): String? = modem.dial(Cubacel.packages(path))

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
        keepContacts(listOf(saved))
        publishContacts()
        saved
    }

    /** Every stored contact, tombstones included, as the other app reads them ([dev.duardo.neotransfer.platform.SyncProvider]). */
    suspend fun contactRecords(): List<ContactRecord> = onState { storedRecords.values.toList() }

    /** Keeps a tombstone, so the other app deletes it too; saving the id again brings it back. */
    suspend fun deleteContact(id: String) = onState {
        keepContacts(emptyList(), listOf(id))
        publishContacts()
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

    /** Writes the encrypted backup (format 4: cards, Clásicas, contacts, services; never keys) to [uri]. */
    suspend fun exportBackup(uri: String, password: CharArray) = onState {
        try {
            val content = BackupContent(wallet.map { BackupCard(it.card, it.holder, it.expiry) }, stored, _services.value, classicCards.map { it.number })
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

    /** Values typed for one operation; [wipe] when they are no longer needed. */
    private class Typed(val secret: CharArray?, val newKey: CharArray?) {
        fun wipe() {
            secret?.fill('\u0000')
            newKey?.fill('\u0000')
        }
    }

    private suspend fun <T> onState(block: suspend () -> T): T = scope.async { startup.await(); block() }.await()

    private fun ownCard(number: String): OwnCard =
        requireNotNull(_cards.value.firstOrNull { it.card.card.number == number }) { "Esa tarjeta no está en la cartera" }.card.card

    private fun isClassic(number: String) = _classics.value.any { it.number == number }

    /** A request that takes the saved key, or a session opened with it; a disconnect never does. */
    private fun needsKey(request: UssdRequest) = request !is Disconnect && (request.secret == SecretKind.BANK_PIN || request.needsSession)

    /** Saved, not rejected, and not superseded by a newer key still waiting to be saved. */
    private fun keyUsable(agency: Agency, current: LedgerView) =
        agency in preferences.keySavedAt && agency !in current.blocked && agency !in preferences.pendingKeys

    /** Requests whose new key, once confirmed, is the agency's key. */
    private fun setsKey(request: UssdRequest) = request is PinChange || request is MiTransferRegistration || request is BankRegistration

    /** Evaluates the ledger, publishes it, announces what changed and sends the next request if the SIM is free. */
    private fun publish(fresh: List<SmsRecord> = emptyList()) {
        val previous = view
        val current = Ledger.evaluate(operationsById.values.toList(), records.values.toList(), wallet.map { it.card }, clock(), preferences.keySavedAt)
        view = current
        dropUnconfirmedKeys(current)
        _cards.value = wallet.map { CardView(it, current.balances.getValue(it.card.number)) }
        _classics.value = classicCards
        _operations.value = current.operations.reversed()
        _movements.value = current.movements
        _session.value = current.session
        _blocked.value = current.blocked
        _savedKeys.value = preferences.keySavedAt.keys
        _keysToSave.value = preferences.pendingKeys.map { (agency, pending) -> KeyToSave(agency, pending.at, agency in confirmedKeys || pending.sms != null) }
        _keysLocked.value = !_locked.value && !keysLoaded
        _settings.value = preferences.settings
        _lastBackup.value = preferences.lastBackup
        if (previous != null) {
            announce(previous, current, fresh)
            keepPaidServices(previous, current)
        }
        scheduleWake(listOfNotNull(current.wakeAt, current.session?.until).minOrNull())
        val next = current.next
        if (!sending && next != null && keyAvailable(next.operation) && (foreground || !next.operation.request.dialed)) {
            sending = true
            scope.launch { send(next) }
        }
    }

    /**
     * A request that takes the saved key (an automatic `40`, a QR payment…) waits until it is in memory, which needs
     * the app unlocked, and while a newer key waits to be saved.
     */
    private fun keyAvailable(operation: Operation): Boolean {
        val request = operation.request
        if (request.secret != SecretKind.BANK_PIN) return true
        val agency = request.agency ?: return false
        return agency in keys && agency !in preferences.pendingKeys
    }

    /**
     * A new key whose request was refused or never reached them is not theirs; nor is MiTransfer's PIN answered in
     * unknown words. A key change answered so waits for the person ([noteKeysToSave]).
     */
    private fun dropUnconfirmedKeys(current: LedgerView) {
        sentKeys.entries.removeAll { (id, key) ->
            val status = current.status(id)
            val state = status?.state
            val dropped = state == null || state is OperationState.Rejected || state is OperationState.NotExecuted ||
                (state is OperationState.Answered && status.operation.request !is PinChange)
            if (dropped) key.fill('\u0000')
            dropped
        }
    }

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
            val values = typed.remove(operation.id)
            val secret = when (request.secret) {
                SecretKind.BANK_PIN -> request.agency?.let(keys::get)?.copyOf()
                SecretKind.CUBACEL_PIN, SecretKind.COORDINATE -> values?.secret
                null -> null
            }
            val newKey = values?.newKey
            val commands = try {
                request.commands(secret, at, newKey = newKey)
            } catch (e: IllegalArgumentException) {
                newKey?.fill('\u0000')
                val reason = when {
                    request.secret == SecretKind.CUBACEL_PIN && secret == null -> "Vuelve a escribir la clave de transferencia"
                    request.secret == SecretKind.COORDINATE && secret == null -> "Vuelve a escribir el valor de la coordenada"
                    request.newKeyLength != null && newKey == null -> "Vuelve a escribir la clave nueva"
                    else -> e.message
                }
                keep(operation.failed(reason ?: "No se pudo preparar la solicitud", at))
                return
            } finally {
                secret?.fill('\u0000')
            }
            // A new key stays in memory, while unlocked, until its answer says whether it is the one to save.
            if (newKey != null) {
                if (setsKey(request) && !_locked.value) sentKeys[operation.id] = newKey else newKey.fill('\u0000')
            }
            operationsById[operation.id] = operation
            publish()
            try {
                store.saveOperation(operation)
            } catch (e: Exception) {
                operationsById[operation.id] = operation.failed("No se pudo guardar la operación", clock())
                return
            }
            // The system dialog takes the person's answer to the bank; the SMS settles the operation.
            commands.singleOrNull()?.takeIf { it.transport == UssdTransport.DIAL }?.let { command ->
                modem.dial(command)?.let { reason -> keep(operation.failed(reason, clock())) }
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

    /** A service paid for the first time enters Guardados by itself; one already there keeps its name. */
    private fun keepPaidServices(previous: LedgerView, current: LedgerView) {
        val before = previous.operations.associate { it.operation.id to it.state }
        current.operations.filter { it.state is OperationState.Confirmed && before[it.operation.id] !is OperationState.Confirmed }
            .mapNotNull { paidService(it.operation.request) }
            .filter { paid -> _services.value.none { it.kind == paid.kind && it.value == paid.value } }
            .forEach { paid -> scope.launch { runCatching { saveService(paid) } } }
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
     * money that did not go through included, or an SMS answer in the bank's or MiTransfer's own words.
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
                // Answered by SMS only: an agency's; Cubacel's answer is the USSD reply on screen.
                val result = state is OperationState.Confirmed || state is OperationState.Rejected ||
                    (state is OperationState.Answered && operation.request.agency != null) ||
                    (state is OperationState.NotExecuted && operation.request.duplicateKey != null)
                result && before[operation.id]?.javaClass != state.javaClass
            }.forEach { notifier.post(Notices.result(it, _contacts.value)) }
        }
    }

    /**
     * Reads the inbox from the last stored `_id` (inclusive: Android may reuse the id of a deleted
     * newest SMS). A full read (the first, or one that adds a sender) rebuilds the activity silently;
     * later SMS are announced.
     */
    private suspend fun ingest(): Int {
        val full = preferences.inboxVersion < INBOX_VERSION
        val from = if (full) 0L else sms.keys.maxOrNull() ?: 0L
        val rows = try {
            withContext(io) { inbox.read(from) }
        } catch (_: SecurityException) {
            return 0
        }
        // While a key change is pending, an SMS of unknown wording may answer it carrying the new key.
        val keyLengths = view?.operations.orEmpty().mapNotNullTo(mutableSetOf()) { status ->
            (status.operation.request as? PinChange)?.takeIf { status.state.isPending }?.bank?.pinLength
        }
        val fresh = rows.filter { sms[it.id]?.receivedAt?.toEpochMilli() != it.date }.mapNotNull { row ->
            val record = SmsRecord.from(row.id, row.address, row.date, row.dateSent, row.body) ?: return@mapNotNull null
            if (record.message is SmsMessage.BankText && keyLengths.isNotEmpty()) {
                val masked = maskKeys(row.body, keyLengths)
                row.copy(body = masked) to record.copy(message = SmsParser.parse(masked))
            } else {
                row to record
            }
        }
        if (fresh.isEmpty() && !full) return 0
        // Registration SMS that carried a key: saveReceivedKey reads it there again.
        val withKey = mutableSetOf<Long>()
        val stored = fresh.map { (row, record) ->
            val message = record.message
            val kept = if (message is SmsMessage.Registration) {
                // It carries the key and the card PIN in clear: neither its body nor the key stays in the app.
                message.key?.take()?.let { withKey += row.id; it.fill('\u0000') }
                StoredSms(row.id, record.sentAt, record.receivedAt, registrationStandIn(message.agency))
            } else {
                StoredSms(row.id, record.sentAt, record.receivedAt, row.body)
            }
            sms[row.id] = kept
            records[row.id] = if (message is SmsMessage.Registration) kept.record() else record
            kept
        }
        val ingested = stored.map { records.getValue(it.id) }
        val announce = !full
        preferences = preferences.copy(inboxVersion = INBOX_VERSION)
        publish(if (announce) ingested else emptyList())
        val noted = noteKeysToSave(ingested, withKey)
        if (noted) {
            // Before the SMS rows: if the process dies in between, the SMS is read and noted again.
            store.savePreferences(preferences)
            publish()
        }
        if (stored.isNotEmpty()) store.saveSms(stored)
        if (!announce && !noted) store.savePreferences(preferences)
        return stored.size
    }

    /**
     * An SMS that just confirmed a registration or a key change, or answered a key change in unknown words: its
     * agency's new key waits for the fingerprint. The typed new key moves to [confirmedKeys]; a bank registration's
     * stays in its SMS ([withKey]).
     */
    private fun noteKeysToSave(ingested: List<SmsRecord>, withKey: Set<Long>): Boolean {
        val current = view ?: return false
        var noted = false
        for (record in ingested) {
            val message = record.message
            val status = current.operations.firstOrNull { status ->
                when (val state = status.state) {
                    is OperationState.Confirmed -> state.result === message
                    is OperationState.Answered -> message is SmsMessage.BankText && status.operation.request is PinChange &&
                        state.at == record.sentAt && state.text == message.text
                    else -> false
                }
            } ?: continue
            val request = status.operation.request
            val agency = request.agency
            if (!setsKey(request) || agency == null) continue
            confirmedKeys.remove(agency)?.fill('\u0000')
            sentKeys.remove(status.operation.id)?.let { confirmedKeys[agency] = it }
            val pending = PendingKey(record.sentAt, record.id.takeIf { request is BankRegistration && it in withKey })
            preferences = preferences.copy(pendingKeys = preferences.pendingKeys + (agency to pending))
            noted = true
        }
        return noted
    }

    private fun lock() {
        wipeKeys()
        keysLoaded = false
        sentKeys.values.forEach { it.fill('\u0000') }
        sentKeys.clear()
        confirmedKeys.values.forEach { it.fill('\u0000') }
        confirmedKeys.clear()
        _locked.value = true
        publish()
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
        val newClassics = content.classics.distinct().filter { Cards.isClassic(it) && classicCards.none { c -> c.number == it } }.map(::ClassicCard)
        val existing = stored.associateBy { it.id }
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
        newClassics.forEach { store.saveClassic(it) }
        keepContacts(contacts)
        if (services.isNotEmpty()) store.saveServices(services)
        wallet = wallet + cards
        classicCards = classicCards + newClassics
        readAddressBook()
        _services.value = _services.value + services
        publish()
        return ImportSummary(cards.size + newClassics.size, content.contacts.count { it.id !in existing }, services.size)
    }

    companion object {
        val LOCK_AFTER: Duration = Duration.ofMinutes(1)
        /** Within a receiver's time limit; [onInboxChanged] catches a slower inbox write. */
        private const val INBOX_POLLS = 12
        private val INBOX_POLL_INTERVAL: Duration = Duration.ofMillis(500)
        /** Bumped when the inbox gains a sender: the next read starts over, silently ([Preferences.inboxVersion]). */
        private const val INBOX_VERSION = 2
        private const val KEYS_LOCKED = "Vuelve a desbloquear con la huella para guardar la clave"

        /**
         * [text] with every run of digits exactly as long as one of [lengths] masked: an unknown SMS that may carry a new
         * key. No template is assumed, so dates or amounts of that length are masked too.
         */
        internal fun maskKeys(text: String, lengths: Set<Int>): String =
            Regex("(?<![0-9])[0-9]+(?![0-9])").replace(text) { if (it.value.length in lengths) "•".repeat(it.value.length) else it.value }

        /** Stored instead of a registration SMS: it still reads as one, of the same bank or MiTransfer, without the key. */
        private fun registrationStandIn(agency: Agency?): String = when (agency) {
            Bank.BPA -> "Banco Popular de Ahorro: "
            Bank.BANDEC -> "Banco Bandec: "
            Bank.BANMET -> "Banco Metropolitano: "
            MiTransfer -> "Monedero Mi Transfer: "
            null -> ""
        } + "La operacion de registro fue completada."
    }
}

/** What a paid service leaves in Guardados: the invoice, account, Nauta user or phone it paid. */
internal fun paidService(request: UssdRequest): ServiceReference? = when (request) {
    is ElectricityBill -> ServiceReference(ServiceKind.ELECTRICITY, request.invoice)
    is PhoneBill -> ServiceReference(ServiceKind.PHONE, request.invoice)
    is WaterBill -> ServiceReference(
        when (request.region) {
            WaterRegion.HAVANA -> ServiceKind.WATER_HAVANA
            WaterRegion.VARADERO -> ServiceKind.WATER_VARADERO
            WaterRegion.OTHER -> ServiceKind.WATER_OTHER
        },
        request.account,
    )
    is NautaPayment -> ServiceReference(if (request.service == NautaService.RECHARGE) ServiceKind.NAUTA else ServiceKind.NAUTA_HOME, request.account.address)
    is MobileRecharge -> ServiceReference(ServiceKind.MOBILE, Phones.normalize(request.phone) ?: request.phone)
    else -> null
}
