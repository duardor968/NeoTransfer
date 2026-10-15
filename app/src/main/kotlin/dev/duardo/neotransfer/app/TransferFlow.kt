package dev.duardo.neotransfer.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.duardo.neotransfer.core.model.Cards
import dev.duardo.neotransfer.core.model.Money as CoreMoney
import dev.duardo.neotransfer.core.protocol.Transfer
import dev.duardo.neotransfer.core.qr.QrCode
import dev.duardo.neotransfer.core.qr.QrContent
import dev.duardo.neotransfer.designsystem.component.KeypadKey
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.designsystem.format.formatMoney
import dev.duardo.neotransfer.scanner.QrVerdict
import dev.duardo.neotransfer.scanner.ScanMode
import dev.duardo.neotransfer.scanner.ScanResult
import dev.duardo.neotransfer.ui.model.BalanceUi
import dev.duardo.neotransfer.ui.model.CounterpartUi
import dev.duardo.neotransfer.ui.model.DuplicateUi
import dev.duardo.neotransfer.ui.model.Money
import dev.duardo.neotransfer.ui.model.OperationKind
import dev.duardo.neotransfer.ui.model.RecipientPickUi
import dev.duardo.neotransfer.ui.model.RecipientUi
import dev.duardo.neotransfer.ui.model.TransferAmountUi
import dev.duardo.neotransfer.ui.model.TransferResultUi
import dev.duardo.neotransfer.ui.model.TransferReviewUi
import dev.duardo.neotransfer.ui.transfer.AmountActions
import dev.duardo.neotransfer.ui.transfer.RecipientActions
import dev.duardo.neotransfer.ui.transfer.RecipientScreen
import dev.duardo.neotransfer.ui.transfer.ResultActions
import dev.duardo.neotransfer.ui.transfer.ReviewActions
import dev.duardo.neotransfer.ui.transfer.TransferAmountScreen
import dev.duardo.neotransfer.ui.transfer.TransferResultScreen
import dev.duardo.neotransfer.ui.transfer.TransferReviewScreen
import dev.duardo.neotransfer.ui.shell.Tab
import java.math.BigDecimal
import java.time.LocalDate

/** What a transfer carries from screen to screen: recipient, amount and the card it leaves from. */
class TransferFlow(val app: AppModel, sourceCard: String?) {
    var recipient: RecipientUi? = null
    /** The phone a scanned card code carries: the bank texts it the credit. */
    var notifyPhone: String? = null
    var input by mutableStateOf("")
    var source by mutableStateOf(sourceCard)
    var saveContact by mutableStateOf(false)
    /** Where the flow began; the result replaces everything from here up. */
    lateinit var first: Destination

    fun start(destination: Destination) {
        first = destination
        app.navigator.push(destination)
    }

    /** Opens the amount for a recipient chosen elsewhere (a contact, a rejected transfer). */
    fun startWith(recipient: RecipientUi, amount: BigDecimal? = null) {
        this.recipient = recipient
        amount?.let { input = it.stripTrailingZeros().toPlainString() }
        start(AmountDestination(this))
    }

    fun amount(): BigDecimal? = input.toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it.scale() <= 2 }

    fun sourceIndex(book: Book): Int = book.wallet.indexOfFirst { it.card.id == source }.coerceAtLeast(0)
}

class RecipientDestination(private val flow: TransferFlow) : Destination(), RecipientActions {
    private val app = flow.app
    private var query by mutableStateOf("")
    private var chooseCardOf by mutableStateOf<String?>(null)
    private var importing by mutableStateOf(false)

    @Composable
    override fun Content(app: AppModel, book: Book) {
        val digits = query.filter(Char::isDigit)
        val numeric = digits.isNotEmpty() && query.all { it.isDigit() || it == ' ' || it == '-' }
        val known = book.contacts.any { contact -> contact.cards.any { it.number == digits } }
        val typed = if (numeric && digits.length == 16 && luhn(digits) && !known) book.foreignCard(digits) else null
        val error = when {
            !numeric -> null
            digits.length > 16 -> "La tarjeta tiene 16 dígitos"
            digits.length == 16 && !luhn(digits) -> "Revisa el número"
            else -> null
        }
        // Everyone money went to, newest first, whether sent from here or from another app.
        val recent = if (query.isNotBlank()) emptyList() else book.activity.asSequence()
            .filter { it.kind == OperationKind.Transfer }
            .mapNotNull { (it.counterpart as? CounterpartUi.Card)?.number?.takeIf(Cards::isNumber) }
            .distinct().take(8).map { number -> recipientFor(book, number) }.toList()
        val contacts = book.contacts.filter { it.cards.isNotEmpty() && it.matchesRecipient(query, digits) }
        RecipientScreen(
            RecipientPickUi(
                query, typed, error, recent, contacts, chooseCardOf?.let(book::contact),
                canImport = query.isBlank() && book.contacts.none { it.cards.isNotEmpty() }, importing = importing,
            ),
            this,
        )
    }

    override fun back() { app.navigator.remove(this) }
    /** A number keeps digits only; the field groups them as printed. */
    override fun query(value: String) {
        query = if (value.isNotEmpty() && value.all { it.isDigit() || it == ' ' || it == '-' }) value.filter(Char::isDigit) else value
    }

    override fun scan() = app.navigator.push(ScanDestination(app, ScanMode.ANY, ::classify) { result ->
        when (result) {
            is ScanResult.Qr -> (runCatching { QrCode.parse(result.raw, LocalDate.now()) }.getOrNull() as? QrContent.Card)?.let { card ->
                flow.notifyPhone = card.phone
                choose(recipientFor(app.book.value, card.number))
            }
            is ScanResult.CardNumber -> choose(recipientFor(app.book.value, result.digits))
            is ScanResult.PhoneNumber -> query = result.digits
        }
    })

    override fun pickTyped() {
        val digits = query.filter(Char::isDigit)
        if (digits.length == 16) choose(recipientFor(app.book.value, digits))
    }

    override fun pick(recipient: RecipientUi) = choose(recipient)

    override fun pickContact(contactId: String) {
        val contact = app.book.value.contact(contactId) ?: return
        if (contact.cards.size == 1) choose(RecipientUi(contact.cards.single(), contact.name, contact.id)) else chooseCardOf = contactId
    }

    override fun pickContactCard(contactId: String, cardId: String) {
        val contact = app.book.value.contact(contactId) ?: return
        chooseCardOf = null
        contact.cards.firstOrNull { it.id == cardId }?.let { choose(RecipientUi(it, contact.name, contact.id)) }
    }

    override fun closeCards() { chooseCardOf = null }

    /** A phone contact brings name and phones; its card is added right after, then it is listed here. */
    override fun importContacts() {
        app.launch {
            importing = true
            try {
                app.importPhoneContact()?.let { app.navigator.push(ContactEditDestination(app, it.id)) }
            } finally {
                importing = false
            }
        }
    }

    private fun choose(recipient: RecipientUi) {
        flow.recipient = recipient
        app.navigator.push(AmountDestination(flow))
    }

    private fun classify(raw: String): QrVerdict = when (runCatching { QrCode.parse(raw, LocalDate.now()) }.getOrNull()) {
        is QrContent.Card -> QrVerdict.Accepted
        is QrContent.Payment -> QrVerdict.Rejected("Este QR es de un pago")
        null -> QrVerdict.Rejected("Este QR no es de una tarjeta")
    }
}

/** A card as a recipient: named when exactly one contact has it. */
internal fun recipientFor(book: Book, number: String): RecipientUi {
    val contact = book.contactName(card = number)
    val card = contact?.let { c -> book.contact(c.id)?.cards?.firstOrNull { it.number == number } } ?: book.foreignCard(number)
    return RecipientUi(card, contact?.name, contact?.id)
}

private fun dev.duardo.neotransfer.ui.model.ContactUi.matchesRecipient(query: String, digits: String): Boolean =
    query.isBlank() || name.contains(query.trim(), ignoreCase = true) || (digits.length >= 3 && cards.any { digits in it.number })

class AmountDestination(private val flow: TransferFlow) : Destination(), AmountActions {
    private val app = flow.app

    @Composable
    override fun Content(app: AppModel, book: Book) {
        val recipient = flow.recipient ?: return
        val index = flow.sourceIndex(book)
        val wallet = book.wallet.getOrNull(index) ?: return
        val amount = flow.amount()
        val balance = (wallet.balance as? BalanceUi.Known)?.money?.amount
        TransferAmountScreen(
            TransferAmountUi(recipient, flow.input, wallet.card.currency, book.wallet, index, amount != null && balance != null && amount > balance, amount != null),
            this,
        )
    }

    override fun back() { app.navigator.remove(this) }
    override fun key(key: KeypadKey) { flow.input = typeAmount(flow.input, key) }
    override fun source(index: Int) { flow.source = app.book.value.wallet.getOrNull(index)?.card?.id }
    override fun next() {
        if (flow.amount() != null) app.navigator.push(ReviewDestination(flow))
    }
}

/** Keypad typing of an amount: at most two decimals and ten whole digits, no leading zeros. */
internal fun typeAmount(input: String, key: KeypadKey): String = when (key) {
    KeypadKey.Backspace -> input.dropLast(1)
    KeypadKey.Decimal -> when {
        '.' in input -> input
        input.isEmpty() -> "0."
        else -> "$input."
    }
    is KeypadKey.Digit -> when {
        '.' in input && input.substringAfter('.').length >= 2 -> input
        '.' !in input && input.length >= 10 -> input
        input == "0" -> key.value.toString()
        else -> input + key.value
    }
}

class ReviewDestination(private val flow: TransferFlow) : Destination(), ReviewActions {
    private val app = flow.app
    private var sending by mutableStateOf(false)
    private var duplicate by mutableStateOf<DuplicateUi?>(null)

    @Composable
    override fun Content(app: AppModel, book: Book) {
        val recipient = flow.recipient ?: return
        val card = book.wallet.getOrNull(flow.sourceIndex(book))?.card ?: return
        val amount = flow.amount() ?: return
        TransferReviewUi(
            recipient, Money(amount, card.currency), card,
            saveContact = if (recipient.contactId == null) flow.saveContact else null,
            sending = sending, duplicate = duplicate,
        ).let { TransferReviewScreen(it, this) }
    }

    override fun back() { app.navigator.remove(this) }
    override fun saveContact(value: Boolean) { flow.saveContact = value }

    override fun confirm() {
        val recipient = flow.recipient ?: return
        val amount = flow.amount() ?: return
        sending = true
        app.launch {
            val title = "Transferir ${formatMoney(amount, currency())}"
            if (app.authorize(title, "A ${recipient.name ?: formatCardNumber(recipient.card.number)}")) send(confirmed = false)
            sending = false
        }
    }

    override fun confirmDuplicate() {
        duplicate = null
        sending = true
        app.launch {
            send(confirmed = true)
            sending = false
        }
    }

    override fun cancelDuplicate() { duplicate = null }

    private fun currency(): String = app.book.value.wallet.getOrNull(flow.sourceIndex(app.book.value))?.card?.currency ?: "CUP"

    private suspend fun send(confirmed: Boolean) {
        val book = app.book.value
        val recipient = flow.recipient ?: return
        val card = book.card(book.wallet.getOrNull(flow.sourceIndex(book))?.card?.id ?: return)?.card?.card ?: return
        val request = Transfer(card.bank, app.controller.sourceFor(card.number), recipient.card.number,
            CoreMoney(flow.amount() ?: return, card.currency), flow.notifyPhone)
        val save = recipient.contactId == null && flow.saveContact
        duplicate = app.submit(request, flow.first, confirmed) {
            if (save) app.navigator.push(ContactEditDestination(app, null, card = recipient.card.number))
        }
    }
}

/** The operation after the bank's acknowledgement, live: Enviada until the SMS turns it into its result. */
class ResultDestination(private val app: AppModel, private val id: String) : Destination(), ResultActions {
    @Composable
    override fun Content(app: AppModel, book: Book) {
        val item = book.item(id) ?: return
        TransferResultScreen(TransferResultUi(item), this)
    }

    override fun done() {
        app.navigator.popToRoot()
        app.tab = Tab.Home
    }

    override fun open() = app.navigator.push(OperationDestination(app, id))
}

/** ISO/IEC 7812 check digit, which every card number carries. */
internal fun luhn(digits: String): Boolean = Cards.isNumber(digits) && digits.reversed().mapIndexed { index, char ->
    val d = char - '0'
    if (index % 2 == 1) (d * 2).let { if (it > 9) it - 9 else it } else d
}.sum() % 10 == 0
