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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import dev.duardo.neotransfer.core.model.Cards
import dev.duardo.neotransfer.core.model.Contact
import dev.duardo.neotransfer.core.model.Phones
import dev.duardo.neotransfer.core.qr.CardQr
import dev.duardo.neotransfer.core.qr.QrCode
import dev.duardo.neotransfer.core.qr.QrContent
import dev.duardo.neotransfer.designsystem.component.NeoBottomSheet
import dev.duardo.neotransfer.designsystem.component.NeoButton
import dev.duardo.neotransfer.designsystem.component.NeoTextField
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.designsystem.format.formatMoney
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.scanner.QrVerdict
import dev.duardo.neotransfer.scanner.ScanMode
import dev.duardo.neotransfer.scanner.ScanResult
import dev.duardo.neotransfer.ui.activity.OperationActions
import dev.duardo.neotransfer.ui.activity.OperationDetailScreen
import dev.duardo.neotransfer.ui.common.TimeLabels
import dev.duardo.neotransfer.ui.common.label
import dev.duardo.neotransfer.ui.contacts.ContactActions
import dev.duardo.neotransfer.ui.contacts.ContactDetailScreen
import dev.duardo.neotransfer.ui.contacts.ContactEditActions
import dev.duardo.neotransfer.ui.contacts.ContactEditScreen
import dev.duardo.neotransfer.ui.model.ContactEditUi
import dev.duardo.neotransfer.ui.model.CounterpartUi
import dev.duardo.neotransfer.ui.model.EditableCardUi
import dev.duardo.neotransfer.ui.model.EditablePhoneUi
import dev.duardo.neotransfer.ui.model.Money
import dev.duardo.neotransfer.ui.model.OperationKind
import dev.duardo.neotransfer.ui.model.OperationUi
import dev.duardo.neotransfer.ui.model.ReceiveUi
import dev.duardo.neotransfer.ui.model.RecipientUi
import dev.duardo.neotransfer.ui.receive.ReceiveActions
import dev.duardo.neotransfer.ui.receive.ReceiveScreen
import java.time.Clock
import java.time.LocalDate

/** Receipt of one activity item: a bank movement, or an operation the app sent. */
class OperationDestination(private val app: AppModel, private val id: String) : Destination(), OperationActions {
    private var shown: OperationUi? = null

    @Composable
    override fun Content(app: AppModel, book: Book) {
        val item = book.item(id) ?: shown ?: return
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
            labels.stamp(item.at),
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

class ContactDestination(private val app: AppModel, private val id: String) : Destination(), ContactActions {
    private var deleting by mutableStateOf(false)

    @Composable
    override fun Content(app: AppModel, book: Book) {
        val contact = book.contact(id) ?: return
        ContactDetailScreen(contact, this, confirmingDelete = deleting)
    }

    override fun back() { app.navigator.remove(this) }
    override fun edit() = app.navigator.push(ContactEditDestination(app, id))

    override fun transfer(cardId: String) {
        val contact = app.book.value.contact(id) ?: return
        val card = contact.cards.firstOrNull { it.id == cardId } ?: return
        TransferFlow(app, app.homeCard).startWith(RecipientUi(card, contact.name, contact.id))
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

/** New contact ([id] null), possibly started from a card or phone seen in activity, or an edit. */
class ContactEditDestination(
    private val app: AppModel,
    private val id: String?,
    card: String? = null,
    phone: String? = null,
) : Destination(), ContactEditActions {
    private var name by mutableStateOf("")
    private val cards = mutableStateListOf<EditableCardUi>()
    private val phones = mutableStateListOf<EditablePhoneUi>()
    private var next = 0

    init {
        val contact = id?.let { app.book.value.domain.contacts.firstOrNull { c -> c.id == it } }
        if (contact != null) {
            name = contact.name
            contact.cards.forEach { addCardRow(it, contact.labels[it].orEmpty()) }
            contact.phones.forEach { addPhoneRow(it) }
        }
        card?.let { addCardRow(it) }
        phone?.let { addPhoneRow(Phones.normalize(it) ?: it) }
        if (cards.isEmpty()) addCardRow("")
        if (phones.isEmpty()) addPhoneRow("")
    }

    @Composable
    override fun Content(app: AppModel, book: Book) {
        val shownCards = cards.map { row -> row.copy(bankName = if (Cards.isNumber(row.number)) book.foreignCard(row.number).bankName else null, error = cardError(row.number)) }
        val shownPhones = phones.map { it.copy(error = phoneError(it.number)) }
        val valid = name.isNotBlank() && shownCards.all { it.error == null } && shownPhones.all { it.error == null } &&
            cards.all { it.number.isEmpty() || it.number.length == 16 } && phones.all { it.number.isEmpty() || it.number.length == 8 }
        ContactEditScreen(ContactEditUi(id, name, shownCards, shownPhones, valid), this)
    }

    private fun cardError(number: String) = if (number.length == 16 && !luhn(number)) "Revisa el número" else null
    private fun phoneError(number: String) = if (number.length == 8 && Phones.normalize(number) == null) "Número no válido" else null

    private fun addCardRow(number: String, label: String = "") { cards += EditableCardUi("c${next++}", number, label) }
    private fun addPhoneRow(number: String) { phones += EditablePhoneUi("p${next++}", number) }

    override fun back() { app.navigator.remove(this) }
    override fun name(value: String) { name = value.take(60) }

    override fun cardNumber(id: String, value: String) {
        val index = cards.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        cards[index] = cards[index].copy(number = value.filter(Char::isDigit).take(16))
    }

    override fun cardLabel(id: String, value: String) {
        val index = cards.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        cards[index] = cards[index].copy(label = value.take(30))
    }

    override fun removeCard(id: String) { cards.removeAll { it.id == id } }
    override fun addCard() = addCardRow("")

    override fun scanCard() = app.navigator.push(ScanDestination(app, ScanMode.ANY, { raw ->
        if (runCatching { QrCode.parse(raw, LocalDate.now()) }.getOrNull() is QrContent.Card) QrVerdict.Accepted else QrVerdict.Rejected("Este QR no es de una tarjeta")
    }) { result ->
        when (result) {
            is ScanResult.CardNumber -> fillCard(result.digits)
            is ScanResult.PhoneNumber -> phones.indexOfFirst { it.number.isEmpty() }.let { if (it >= 0) phones[it] = phones[it].copy(number = result.digits) else addPhoneRow(result.digits) }
            is ScanResult.Qr -> (runCatching { QrCode.parse(result.raw, LocalDate.now()) }.getOrNull() as? QrContent.Card)?.let { code ->
                fillCard(code.number)
                code.phone?.let { phone -> if (phones.none { it.number == phone }) addPhoneRow(phone) }
            }
        }
    })

    private fun fillCard(number: String) {
        if (cards.any { it.number == number }) return
        val empty = cards.indexOfFirst { it.number.isEmpty() }
        if (empty >= 0) cards[empty] = cards[empty].copy(number = number) else addCardRow(number)
    }

    override fun phone(id: String, value: String) {
        val index = phones.indexOfFirst { it.id == id }.takeIf { it >= 0 } ?: return
        phones[index] = phones[index].copy(number = (Phones.normalize(value) ?: value.filter(Char::isDigit)).take(8))
    }

    override fun removePhone(id: String) { phones.removeAll { it.id == id } }
    override fun addPhone() = addPhoneRow("")

    override fun save() {
        val filled = cards.filter { it.number.isNotEmpty() }
        val contact = Contact(
            id.orEmpty(), name, phones.map { it.number }.filter(String::isNotEmpty), filled.map { it.number },
            filled.filter { it.label.isNotBlank() }.associate { it.number to it.label },
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
            NeoTextField(text, { text = it.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(12) }, "Importe",
                keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done, suffix = currency)
            NeoButton("Mostrar importe", { value?.let(onDone) }, enabled = value != null)
        }
    }
}
