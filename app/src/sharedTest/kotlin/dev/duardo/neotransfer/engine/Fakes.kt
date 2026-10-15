package dev.duardo.neotransfer.engine

import dev.duardo.neotransfer.core.ledger.Operation
import dev.duardo.neotransfer.core.model.Contact
import dev.duardo.neotransfer.core.model.ServiceReference
import dev.duardo.neotransfer.core.protocol.UssdCommand
import dev.duardo.neotransfer.data.Preferences
import dev.duardo.neotransfer.data.Snapshot
import dev.duardo.neotransfer.data.Store
import dev.duardo.neotransfer.data.StoredSms
import dev.duardo.neotransfer.data.WalletCard
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Cipher

const val ACK = "Su solicitud esta siendo procesada, espere un SMS"

/** Modem double: records what it is handed and answers after [latency]; a null answer never comes. */
class FakeModem(var latency: Long = 2_000) : UssdModem {
    val sent = CopyOnWriteArrayList<UssdCommand>()
    val dialed = CopyOnWriteArrayList<UssdCommand>()
    var answer: (UssdCommand) -> UssdReply? = { UssdReply.Text(ACK) }

    /** Service numbers handed to the modem, in order (null for Cubacel codes). */
    val services: List<Int?> get() = sent.map { it.service }

    override suspend fun request(command: UssdCommand): UssdReply {
        sent += command
        delay(latency)
        return answer(command) ?: awaitCancellation()
    }

    override fun dial(command: UssdCommand) { dialed += command }
}

/** Inbox double: PAGOxMOVIL rows with increasing `_id`; remembers where each read started. */
class FakeInbox : SmsInbox {
    private val rows = CopyOnWriteArrayList<InboxSms>()
    val reads = CopyOnWriteArrayList<Long>()
    @Volatile var granted = true
    private var nextId = 100L

    @Synchronized
    fun deliver(body: String, sentAt: Instant, receivedAt: Instant): Long =
        nextId++.also { rows += InboxSms(it, receivedAt.toEpochMilli(), sentAt.toEpochMilli(), body) }

    override fun read(fromId: Long): List<InboxSms> {
        if (!granted) throw SecurityException("READ_SMS")
        reads += fromId
        return rows.filter { it.id >= fromId }
    }
}

/** Keeps the blob in memory; ciphers are never used for real. */
class FakeVault : KeyVault {
    @Volatile private var blob: ByteArray? = null
    override fun hasKeys() = blob != null
    override fun decryptCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding")
    override fun decrypt(authorized: Cipher): ByteArray = checkNotNull(blob).copyOf()
    override fun encryptCipher(): Cipher = Cipher.getInstance("AES/GCM/NoPadding")
    override fun encrypt(authorized: Cipher, plaintext: ByteArray) { blob = plaintext.copyOf() }
    override fun reset() { blob = null }
    fun contains(text: String) = blob?.toString(Charsets.US_ASCII)?.contains(text) == true
}

class FakeNotifier : Notifier {
    val posted = CopyOnWriteArrayList<Notice>()
    override fun post(notice: Notice) { posted += notice }
}

class FakeDocuments : Documents {
    val files = ConcurrentHashMap<String, ByteArray>()
    override fun read(uri: String): ByteArray = checkNotNull(files[uri]) { "No existe $uri" }
    override fun write(uri: String, bytes: ByteArray) { files[uri] = bytes }
}

/** The durable state of one "device", shared by the controllers of successive processes. */
class MemoryStore : Store {
    val operations = ConcurrentHashMap<String, Operation>()
    val sms = ConcurrentHashMap<Long, StoredSms>()
    @Volatile var cards = emptyList<WalletCard>()
    val contacts = ConcurrentHashMap<String, Contact>()
    val services = CopyOnWriteArrayList<ServiceReference>()
    @Volatile var preferences = Preferences()

    override suspend fun load() = Snapshot(operations.values.toList(), sms.values.toList(), cards, contacts.values.toList(), services.toList(), preferences)
    override suspend fun saveOperation(operation: Operation) { operations[operation.id] = operation }
    override suspend fun saveSms(rows: List<StoredSms>) = rows.forEach { sms[it.id] = it }
    override suspend fun saveCards(cards: List<WalletCard>) { this.cards = cards }
    override suspend fun deleteCard(number: String) { cards = cards.filterNot { it.card.number == number } }
    override suspend fun saveContacts(contacts: List<Contact>) = contacts.forEach { this.contacts[it.id] = it }
    override suspend fun deleteContact(id: String) { contacts.remove(id) }
    override suspend fun saveServices(services: List<ServiceReference>) {
        services.forEach { new -> this.services.removeIf { it.kind == new.kind && it.value == new.value }; this.services += new }
    }
    override suspend fun deleteService(service: ServiceReference) { services.removeIf { it.kind == service.kind && it.value == service.value } }
    override suspend fun savePreferences(preferences: Preferences) { this.preferences = preferences }
}

/** One process of the app on a device made of [store], [inbox] and [vault]. */
class Rig(
    val store: Store = MemoryStore(),
    val inbox: FakeInbox = FakeInbox(),
    val vault: FakeVault = FakeVault(),
    clock: () -> Instant,
    state: CoroutineDispatcher,
    io: CoroutineDispatcher,
    val modem: FakeModem = FakeModem(),
) {
    val notifier = FakeNotifier()
    val documents = FakeDocuments()
    val phoneContacts = mutableMapOf<String, PhoneContact>()
    val controller = AppController(store, modem, inbox, vault, notifier, documents, { phoneContacts.getValue(it) }, clock, state, io)

    fun status(id: String) = controller.operations.value.first { it.operation.id == id }
    fun state(id: String) = status(id).state
}

/** Synthetic PAGOxMOVIL bodies with the real templates; numbers and references are made up. */
object BankSms {
    fun busy() = "Usted tiene una solicitud en proceso, en breve recibira respuesta y podra realizar otra operacion. "
    fun balanceBpa(value: String) = "Banco Popular de Ahorro:  La consulta de saldo fue completada. \n Saldo Disponible: CR $value CUP"
    fun balanceBandec(card: String, available: String) =
        "Banco Bandec La consulta de saldo fue completada. \n Cuenta;Saldo Contable;Saldo Disponible;Moneda \n${card.take(6)}XXXXXX${card.takeLast(4)}; CR $available ; CR $available ;CUP | \n"
    fun received(to: String, amount: String, ref: String, from: String = "5350000009") =
        "El titular del telefono $from le ha realizado una transferencia a la cuenta $to de $amount CUP. Nro. Transaccion $ref. Fecha: 15/10/2026."
    fun rejectedTransfer() = "Fallo la transferencia. Banco Bandec: LA CUENTA NO ESTA ASOCIADA A UNA TARJETA MAGNETICA"
    fun registration(key: String) =
        "Banco Bandec:  La operacion de registro fue completada. Bienvenido a Transfermovil.Clave de Autenticacion: $key Guarde esta clave."
}
