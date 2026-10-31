package dev.duardo.neotransfer.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.duardo.neotransfer.core.model.Cards
import dev.duardo.neotransfer.core.qr.QrCode
import dev.duardo.neotransfer.core.qr.QrContent
import dev.duardo.neotransfer.designsystem.component.KeypadKey
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.designsystem.format.formatMoney
import dev.duardo.neotransfer.scanner.QrVerdict
import dev.duardo.neotransfer.scanner.ScanMode
import dev.duardo.neotransfer.scanner.ScanResult
import dev.duardo.neotransfer.ui.model.BalanceUi
import dev.duardo.neotransfer.ui.model.Bank
import dev.duardo.neotransfer.ui.model.ContactUi
import dev.duardo.neotransfer.ui.model.CounterpartUi
import dev.duardo.neotransfer.ui.model.DuplicateUi
import dev.duardo.neotransfer.ui.model.Money
import dev.duardo.neotransfer.ui.model.OperationKind
import dev.duardo.neotransfer.ui.model.RecipientPickUi
import dev.duardo.neotransfer.ui.model.RecipientUi
import dev.duardo.neotransfer.ui.model.TransferAmountUi
import dev.duardo.neotransfer.ui.model.TransferResultUi
import dev.duardo.neotransfer.ui.model.TransferReviewUi
import dev.duardo.neotransfer.ui.model.WalletCardUi
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

    /** The cards that can send to the recipient ([Book.transferSources]). */
    fun sources(book: Book): List<WalletCardUi> = recipient?.let { book.transferSources(it.card.number) }.orEmpty()

    fun sourceIndex(book: Book): Int = sources(book).indexOfFirst { it.card.id == source }.coerceAtLeast(0)

    /** Sending from a Clásica: only another Clásica can receive. */
    fun classicOnly(book: Book): Boolean = (book.wallet.firstOrNull { it.card.id == source } ?: book.wallet.firstOrNull())?.card?.bank == Bank.CLASSIC
}

/** Why a Clásica cannot send to a bank card. */
private const val CLASSIC_ONLY = "La Clásica solo transfiere a otra Clásica"

class RecipientDestination(private val flow: TransferFlow) : Destination(), RecipientActions {
    private val app = flow.app
    private var query by mutableStateOf("")
    private var chooseCardOf by mutableStateOf<String?>(null)

    @Composable
    override fun Content(app: AppModel, book: Book) {
        val classicOnly = flow.classicOnly(book)
        val digits = query.filter(Char::isDigit)
        val numeric = digits.isNotEmpty() && query.all { it.isDigit() || it == ' ' || it == '-' }
        val known = book.contacts.any { contact -> contact.cards.any { it.number == digits } }
        val receives = !classicOnly || Cards.isClassic(digits)
        val typed = if (numeric && digits.length == 16 && luhn(digits) && !known && receives) book.foreignCard(digits) else null
        val error = when {
            !numeric -> null
            digits.length > 16 -> "La tarjeta tiene 16 dígitos"
            digits.length == 16 && !luhn(digits) -> "Revisa el número"
            digits.length == 16 && !receives -> CLASSIC_ONLY
            else -> null
        }
        // Everyone money went to, newest first, whether sent from here or from another app.
        val recent = if (query.isNotBlank()) emptyList() else book.activity.asSequence()
            .filter { it.kind == OperationKind.Transfer }
            .mapNotNull { (it.counterpart as? CounterpartUi.Card)?.number?.takeIf(Cards::isNumber) }
            .filter { !classicOnly || Cards.isClassic(it) }
            .distinct().take(8).map { number -> recipientFor(book, number) }.toList()
        // People with a card; while searching, the rest of the address book too (its card is asked for when chosen).
        // From a Clásica, only their Clásicas: someone with bank cards alone is left out.
        val contacts = book.contacts.mapNotNull { contact -> eligible(contact, classicOnly) }
            .filter { (it.cards.isNotEmpty() || query.isNotBlank()) && it.matchesRecipient(query, digits) }
            .sortedBy { it.cards.isEmpty() }
        RecipientScreen(
            RecipientPickUi(query, typed, error, recent, contacts, chooseCardOf?.let(book::contact)?.let { eligible(it, classicOnly) }),
            this, classicOnly = classicOnly,
        )
    }

    /** [contact] with the cards that can receive; null when it has cards and none can. */
    private fun eligible(contact: ContactUi, classicOnly: Boolean): ContactUi? {
        if (!classicOnly) return contact
        val classics = contact.cards.filter { Cards.isClassic(it.number) }
        return if (classics.isEmpty() && contact.cards.isNotEmpty()) null else contact.copy(cards = classics)
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
        val book = app.book.value
        val contact = book.contact(contactId)?.let { eligible(it, flow.classicOnly(book)) } ?: return
        when (contact.cards.size) {
            0 -> app.navigator.push(CardEditDestination(app, contactId) { saved ->
                saved.cards.lastOrNull()?.let { number -> choose(RecipientUi(app.book.value.foreignCard(number, saved.labels[number]), saved.name, saved.id)) }
            })
            1 -> choose(RecipientUi(contact.cards.single(), contact.name, contact.id))
            else -> chooseCardOf = contactId
        }
    }

    override fun pickContactCard(contactId: String, cardId: String) {
        val book = app.book.value
        val contact = book.contact(contactId)?.let { eligible(it, flow.classicOnly(book)) } ?: return
        chooseCardOf = null
        contact.cards.firstOrNull { it.id == cardId }?.let { choose(RecipientUi(it, contact.name, contact.id)) }
    }

    override fun closeCards() { chooseCardOf = null }

    private fun choose(recipient: RecipientUi) {
        if (flow.classicOnly(app.book.value) && !Cards.isClassic(recipient.card.number)) return app.message(CLASSIC_ONLY)
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

private fun ContactUi.matchesRecipient(query: String, digits: String): Boolean =
    query.isBlank() || name.contains(query.trim(), ignoreCase = true) || (digits.length >= 3 && cards.any { digits in it.number })

class AmountDestination(private val flow: TransferFlow) : Destination(), AmountActions {
    private val app = flow.app

    @Composable
    override fun Content(app: AppModel, book: Book) {
        val recipient = flow.recipient ?: return
        val sources = flow.sources(book)
        if (sources.isEmpty()) {
            // A bank card chosen elsewhere (a contact's page) with only Clásicas to send from.
            LaunchedEffect(Unit) {
                app.message(CLASSIC_ONLY)
                app.navigator.remove(this@AmountDestination)
            }
            return
        }
        val index = flow.sourceIndex(book)
        val wallet = sources[index]
        val amount = flow.amount()
        val balance = (wallet.balance as? BalanceUi.Known)?.money?.amount
        TransferAmountScreen(
            TransferAmountUi(recipient, flow.input, wallet.card.currency, sources, index, amount != null && balance != null && amount > balance, amount != null),
            this,
        )
    }

    override fun back() { app.navigator.remove(this) }
    override fun key(key: KeypadKey) { flow.input = typeAmount(flow.input, key) }
    override fun source(index: Int) { flow.source = flow.sources(app.book.value).getOrNull(index)?.card?.id }
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
        val card = flow.sources(book).getOrNull(flow.sourceIndex(book))?.card ?: return
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

    private fun currency(): String = app.book.value.let { book -> flow.sources(book).getOrNull(flow.sourceIndex(book))?.card?.currency } ?: "CUP"

    /** The bank's transfer, or MiTransfer's between Clásicas when the card is one: the controller's `transferRequest`. */
    private suspend fun send(confirmed: Boolean) {
        val book = app.book.value
        val recipient = flow.recipient ?: return
        val card = flow.sources(book).getOrNull(flow.sourceIndex(book))?.card ?: return
        val request = try {
            app.controller.transferRequest(card.number, recipient.card.number, flow.amount() ?: return, flow.notifyPhone)
        } catch (e: IllegalArgumentException) {
            return app.message(e.message ?: "No se pudo enviar")
        }
        val save = recipient.contactId == null && flow.saveContact
        duplicate = app.submit(request, flow.first, confirmed) {
            if (save) app.navigator.push(ContactEditDestination(app, null, card = recipient.card.number))
        }
    }
}

/**
 * The operation after the bank's acknowledgement, live: Enviada until the SMS turns it into its result. One that is not
 * money, or no longer in the app, gives way to where it is shown ([AppModel.show]).
 */
class ResultDestination(private val app: AppModel, private val id: String) : Destination(), ResultActions, Follows {
    /** Only while it shows the item: what is not one gives way to where it is shown ([AppModel.show]). */
    override fun follows(id: String) = id == this.id && app.book.value.item(id) != null

    @Composable
    override fun Content(app: AppModel, book: Book) {
        val item = book.item(id)
        if (item == null) {
            LaunchedEffect(Unit) { if (app.book.value.item(id) == null) app.show(id, live = true, replacing = this@ResultDestination) }
            return
        }
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
