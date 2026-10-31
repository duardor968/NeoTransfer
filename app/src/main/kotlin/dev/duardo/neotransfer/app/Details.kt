package dev.duardo.neotransfer.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.duardo.neotransfer.core.model.Cards
import dev.duardo.neotransfer.core.model.Contact
import dev.duardo.neotransfer.core.model.Phones
import dev.duardo.neotransfer.core.qr.CardQr
import dev.duardo.neotransfer.core.qr.QrCode
import dev.duardo.neotransfer.core.qr.QrContent
import dev.duardo.neotransfer.designsystem.component.NeoAmountField
import dev.duardo.neotransfer.designsystem.component.NeoBottomSheet
import dev.duardo.neotransfer.designsystem.component.NeoButton
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.designsystem.format.formatMoney
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.engine.Directory
import dev.duardo.neotransfer.scanner.QrVerdict
import dev.duardo.neotransfer.scanner.ScanMode
import dev.duardo.neotransfer.scanner.ScanResult
import dev.duardo.neotransfer.ui.activity.OperationActions
import dev.duardo.neotransfer.ui.activity.OperationDetailScreen
import dev.duardo.neotransfer.ui.common.TimeLabels
import dev.duardo.neotransfer.ui.common.label
import dev.duardo.neotransfer.ui.contacts.AllContactsActions
import dev.duardo.neotransfer.ui.contacts.AllContactsScreen
import dev.duardo.neotransfer.ui.contacts.CardEditActions
import dev.duardo.neotransfer.ui.contacts.CardEditScreen
import dev.duardo.neotransfer.ui.contacts.ContactActions
import dev.duardo.neotransfer.ui.contacts.ContactDetailScreen
import dev.duardo.neotransfer.ui.contacts.ContactEditActions
import dev.duardo.neotransfer.ui.contacts.ContactEditScreen
import dev.duardo.neotransfer.ui.model.AllContactsUi
import dev.duardo.neotransfer.ui.model.CardEditUi
import dev.duardo.neotransfer.ui.model.CardFieldUi
import dev.duardo.neotransfer.ui.model.ContactEditUi
import dev.duardo.neotransfer.ui.model.CounterpartUi
import dev.duardo.neotransfer.ui.model.EditablePhoneUi
import dev.duardo.neotransfer.ui.model.Money
import dev.duardo.neotransfer.ui.model.OperationKind
import dev.duardo.neotransfer.ui.model.OperationUi
import dev.duardo.neotransfer.ui.model.ReceiveUi
import dev.duardo.neotransfer.ui.model.RecipientUi
import dev.duardo.neotransfer.ui.receive.ReceiveActions
import dev.duardo.neotransfer.ui.receive.ReceiveScreen
import dev.duardo.neotransfer.ui.shell.Tab
import java.time.Clock
import java.time.LocalDate

/**
 * Receipt of one activity item: a bank movement, or an operation the app sent. One that is not activity, or no longer
 * in the app, gives way to where it is shown ([AppModel.show]).
 */
class OperationDestination(private val app: AppModel, private val id: String) : Destination(), OperationActions, Follows {
    private var shown: OperationUi? = null

    /** Only while it shows the item: what is not one gives way to where it is shown ([AppModel.show]). */
    override fun follows(id: String) = id == this.id && app.book.value.item(id) != null

    @Composable
    override fun Content(app: AppModel, book: Book) {
        val item = book.item(id) ?: shown ?: run {
            LaunchedEffect(Unit) { if (app.book.value.item(id) == null) app.show(id, replacing = this@OperationDestination) }
            return
        }
        shown = item
        val saveable = item.contactId == null && when (val c = item.counterpart) {
            is CounterpartUi.Phone -> item.kind == OperationKind.Incoming
            is CounterpartUi.Card -> Cards.isNumber(c.number)
            else -> false
        }
        OperationDetailScreen(item, this, canSaveContact = saveable)
    }

    override fun back() { app.navigator.remove(this) }

    override fun share() {
        val item = shown ?: return
        val labels = TimeLabels(Clock.systemDefaultZone())
        app.host?.share(listOfNotNull(
            item.kind.label,
            item.title,
            item.amount?.let { formatMoney(it.amount, it.currency) },
            labels.stamp(item.at, item.dateOnly),
            item.transactionId?.let { "Transacción $it" },
        ).joinToString("\n"))
    }

    override fun retry() {
        app.retry(id)
        app.navigator.remove(this)
    }

    override fun repeat() {
        val item = shown ?: return
        val card = (item.counterpart as? CounterpartUi.Card)?.number ?: return
        TransferFlow(app, item.card?.id).startWith(recipientFor(app.book.value, card), item.amount?.amount)
    }

    override fun saveContact() {
        val item = shown ?: return
        app.navigator.push(when (val c = item.counterpart) {
            is CounterpartUi.Phone -> ContactEditDestination(app, null, phone = c.number)
            is CounterpartUi.Card -> ContactEditDestination(app, null, card = c.number)
            else -> return
        })
    }

    override fun checkBalance() {
        val card = cardId() ?: return
        app.query { app.controller.refreshBalance(card) }
    }

    override fun checkRecent() {
        val card = cardId() ?: return
        app.query { app.controller.refreshHistory(card) }
    }

    private fun cardId(): String? = shown?.card?.id ?: app.book.value.cards.firstOrNull()?.id
}

/** Everyone, by name: the phone's address book and the contacts kept only in the app. */
class AllContactsDestination(private val app: AppModel) : Destination(), AllContactsActions {
    private var query by mutableStateOf("")

    @Composable
    override fun Content(app: AppModel, book: Book) {
        AllContactsScreen(AllContactsUi(query, book.contacts.filter { it.matches(query) }), this)
    }

    override fun back() { app.navigator.remove(this) }
    override fun query(value: String) { query = value }
    override fun open(id: String) = app.navigator.push(ContactDestination(app, id))
}

class ContactDestination(private val app: AppModel, private val id: String) : Destination(), ContactActions {
    private var deleting by mutableStateOf(false)

    @Composable
    override fun Content(app: AppModel, book: Book) {
        val contact = book.contact(id) ?: return
        ContactDetailScreen(contact, this, confirmingDelete = deleting, movements = book.activity.filter { it.contactId == id }.take(3))
    }

    override fun back() { app.navigator.remove(this) }
    override fun edit() = app.navigator.push(ContactEditDestination(app, id))
    override fun editInContacts() { Directory.key(id)?.let { app.host?.openContact(it) } }
    override fun openMovement(id: String) = app.navigator.push(OperationDestination(app, id))

    override fun allMovements() {
        val contact = app.book.value.contact(id) ?: return
        app.activity.search(contact.name)
        app.navigator.popToRoot()
        app.tab = Tab.Activity
    }

    override fun transfer(cardId: String) {
        val contact = app.book.value.contact(id) ?: return
        val card = contact.cards.firstOrNull { it.id == cardId } ?: return
        TransferFlow(app, app.homeCard).startWith(RecipientUi(card, contact.name, contact.id))
    }

    override fun addCard() = app.navigator.push(CardEditDestination(app, id))
    override fun editCard(cardId: String) = app.navigator.push(CardEditDestination(app, id, cardId))

    /** At once; the message undoes it. */
    override fun removeCard(cardId: String) {
        val before = app.book.value.domain.contacts.firstOrNull { it.id == id } ?: return
        app.launch {
            app.controller.saveContact(before.copy(cards = before.cards - cardId, labels = before.labels - cardId))
            app.message("Tarjeta quitada", action = "Deshacer") { app.launch { app.controller.saveContact(before) } }
        }
    }

    override fun copy(value: String) { app.host?.copy(value) }
    override fun delete() { deleting = true }
    override fun cancelDelete() { deleting = false }
    override fun confirmDelete() {
        deleting = false
        app.launch {
            app.controller.deleteContact(id)
            app.navigator.remove(this)
        }
    }
}

/** One card of contact [contactId]: a new one, or the saved [cardId]. [onSaved] runs with the contact once saved and closed. */
class CardEditDestination(
    private val app: AppModel,
    private val contactId: String,
    private val cardId: String? = null,
    private val onSaved: ((Contact) -> Unit)? = null,
) : Destination(), CardEditActions {
    private var number by mutableStateOf(cardId.orEmpty())
    private var label by mutableStateOf(cardId?.let { app.book.value.domain.contacts.firstOrNull { c -> c.id == contactId }?.labels?.get(it) }.orEmpty())

    @Composable
    override fun Content(app: AppModel, book: Book) {
        val contact = book.domain.contacts.firstOrNull { it.id == contactId } ?: return
        val card = cardField(number, label, taken = contact.cards - listOfNotNull(cardId).toSet())
        CardEditScreen(CardEditUi(contact.name, cardId != null, card, number.length == 16 && card.error == null), this)
    }

    override fun back() { app.navigator.remove(this) }
    override fun number(value: String) { number = value.filter(Char::isDigit).take(16) }
    override fun label(value: String) { label = value.take(30) }
    override fun scan() = app.navigator.push(scanCard(app) { card, _ -> card?.let { number = it } })

    override fun save() {
        val contact = app.book.value.domain.contacts.firstOrNull { it.id == contactId } ?: return
        val cards = if (cardId == null) contact.cards + number else contact.cards.map { if (it == cardId) number else it }
        val labels = (contact.labels - listOfNotNull(cardId).toSet()) + listOfNotNull(label.takeIf(String::isNotBlank)?.let { number to it })
        app.launch {
            try {
                val saved = app.controller.saveContact(contact.copy(cards = cards, labels = labels))
                app.navigator.remove(this)
                onSaved?.invoke(saved)
            } catch (e: IllegalArgumentException) {
                app.message(e.message ?: "Revisa el número")
            }
        }
    }
}

/**
 * A contact kept only in the app: new ([id] null), possibly started from a card or phone seen in activity, with its
 * first card; or its name and phones.
 */
class ContactEditDestination(
    private val app: AppModel,
    private val id: String?,
    card: String? = null,
    phone: String? = null,
) : Destination(), ContactEditActions {
    private var name by mutableStateOf("")
    private var cardNumber by mutableStateOf(card.orEmpty())
    private var cardLabel by mutableStateOf("")
    private val phones = mutableStateListOf<EditablePhoneUi>()
    private var next = 0

    init {
        val contact = id?.let { app.book.value.domain.contacts.firstOrNull { c -> c.id == it } }
        if (contact != null) {
            name = contact.name
            contact.phones.forEach { addPhoneRow(it) }
        }
        phone?.let { addPhoneRow(Phones.normalize(it) ?: it) }
        if (phones.isEmpty()) addPhoneRow("")
    }

    @Composable
    override fun Content(app: AppModel, book: Book) {
        val card = if (id == null) cardField(cardNumber, cardLabel) else null
        val shownPhones = phones.map { it.copy(error = Phones.mobileProblem(it.number)) }
        val valid = name.isNotBlank() && (card == null || card.error == null && cardNumber.length in setOf(0, 16)) &&
            shownPhones.all { it.error == null }
        ContactEditScreen(ContactEditUi(id, name, card, shownPhones, valid), this)
    }

    private fun addPhoneRow(number: String) { phones += EditablePhoneUi("p${next++}", number) }

    override fun back() { app.navigator.remove(this) }
    override fun name(value: String) { name = value.take(60) }
    override fun cardNumber(value: String) { cardNumber = value.filter(Char::isDigit).take(16) }
    override fun cardLabel(value: String) { cardLabel = value.take(30) }

    override fun scanCard() = app.navigator.push(scanCard(app, phones = true) { card, phone ->
        card?.let { cardNumber = it }
        phone?.takeIf { p -> phones.none { it.number == p } }?.let { p ->
            phones.indexOfFirst { it.number.isEmpty() }.let { if (it >= 0) phones[it] = phones[it].copy(number = p) else addPhoneRow(p) }
        }
    })

    override fun phone(id: String, value: String) {
        val index = phones.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        phones[index] = phones[index].copy(number = Phones.input(value))
    }

    override fun removePhone(id: String) { phones.removeAll { it.id == id } }
    override fun addPhone() = addPhoneRow("")

    override fun save() {
        val numbers = phones.map { it.number }.filter(String::isNotEmpty)
        val saved = id?.let { app.book.value.domain.contacts.firstOrNull { c -> c.id == it } }
        val contact = saved?.copy(name = name, phones = numbers) ?: Contact(
            "", name, numbers, listOfNotNull(cardNumber.ifEmpty { null }),
            if (cardNumber.isNotEmpty() && cardLabel.isNotBlank()) mapOf(cardNumber to cardLabel) else emptyMap(),
        )
        app.launch {
            try {
                app.controller.saveContact(contact)
                app.navigator.remove(this)
            } catch (e: IllegalArgumentException) {
                app.message(e.message ?: "Revisa los datos")
            }
        }
    }
}

/** What a typed card number tells, and what is wrong with it: a bad check digit, or a card [taken] already. */
private fun cardField(number: String, label: String, taken: Collection<String> = emptyList()): CardFieldUi {
    val issuer = Cards.issuer(number)
    val error = when {
        number.length < 16 -> null
        !luhn(number) -> "Algún dígito está mal"
        number in taken -> "Ya está guardada"
        else -> null
    }
    return CardFieldUi(number, label, issuerBank(number), issuer.currency?.name, error)
}

/** The camera for a card: its printed number or a Transfermóvil card QR, which may carry a phone; [phones] also takes a printed phone. */
internal fun scanCard(app: AppModel, phones: Boolean = false, onRead: (card: String?, phone: String?) -> Unit) =
    ScanDestination(app, ScanMode.ANY, { raw ->
        if (runCatching { QrCode.parse(raw, LocalDate.now()) }.getOrNull() is QrContent.Card) QrVerdict.Accepted else QrVerdict.Rejected("Este QR no es de una tarjeta")
    }) { result ->
        when (result) {
            is ScanResult.CardNumber -> onRead(result.digits, null)
            is ScanResult.PhoneNumber -> result.digits.takeIf { phones }?.let { onRead(null, it) }
            is ScanResult.Qr -> (runCatching { QrCode.parse(result.raw, LocalDate.now()) }.getOrNull() as? QrContent.Card)?.let { onRead(it.number, it.phone) }
        }
    }

/** QR of one of the user's cards for someone else to pay into it; the amount, when asked, travels in the shared text. */
class ReceiveDestination(private val app: AppModel, card: String?) : Destination(), ReceiveActions {
    private var card by mutableStateOf(card)
    private var amount by mutableStateOf<Money?>(null)
    private var asking by mutableStateOf(false)

    @Composable
    override fun Content(app: AppModel, book: Book) {
        val cards = book.cards
        if (cards.isEmpty()) {
            LaunchedEffect(Unit) { app.navigator.remove(this@ReceiveDestination) }
            return
        }
        val index = cards.indexOfFirst { it.id == card }.coerceAtLeast(0)
        val selected = cards[index]
        ReceiveScreen(ReceiveUi(cards, index, CardQr.encode(selected.number), amount), this)
        if (asking) AmountSheet(selected.currency, { asking = false }) { value ->
            asking = false
            amount = Money(value, selected.currency)
        }
    }

    override fun back() { app.navigator.remove(this) }
    override fun card(index: Int) { card = app.book.value.cards.getOrNull(index)?.id }
    override fun requestAmount() { asking = true }
    override fun clearAmount() { amount = null }

    override fun share() {
        val selected = app.book.value.cards.firstOrNull { it.id == card } ?: app.book.value.cards.firstOrNull() ?: return
        app.host?.share(listOfNotNull(
            amount?.let { "Transfiéreme ${formatMoney(it.amount, it.currency)}" },
            "${selected.bank.shortName} ${selected.currency}: ${formatCardNumber(selected.number)}",
        ).joinToString("\n"))
    }

    override fun copyNumber() {
        val selected = app.book.value.cards.firstOrNull { it.id == card } ?: app.book.value.cards.firstOrNull() ?: return
        app.host?.copy(selected.number)
    }
}

@Composable
private fun AmountSheet(currency: String, onDismiss: () -> Unit, onDone: (java.math.BigDecimal) -> Unit) {
    var text by remember { mutableStateOf("") }
    val value = text.replace(',', '.').toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it.scale() <= 2 }
    NeoBottomSheet(onDismissRequest = onDismiss, title = "Pedir importe") {
        Column(Modifier.padding(horizontal = NeoSpace.gutter), verticalArrangement = Arrangement.spacedBy(NeoSpace.l)) {
            NeoAmountField(text, { text = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(12) }, currency)
            NeoButton("Mostrar importe", { value?.let(onDone) }, enabled = value != null)
        }
    }
}
