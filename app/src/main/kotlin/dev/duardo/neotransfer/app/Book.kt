package dev.duardo.neotransfer.app

import dev.duardo.neotransfer.core.ledger.Direction
import dev.duardo.neotransfer.core.ledger.Movement
import dev.duardo.neotransfer.core.ledger.MovementKind
import dev.duardo.neotransfer.core.ledger.OperationState
import dev.duardo.neotransfer.core.ledger.OperationStatus as CoreStatus
import dev.duardo.neotransfer.core.model.Agency
import dev.duardo.neotransfer.core.model.Bank as CoreBank
import dev.duardo.neotransfer.core.model.Cards
import dev.duardo.neotransfer.core.model.Contact
import dev.duardo.neotransfer.core.model.Currency
import dev.duardo.neotransfer.core.model.MiTransfer
import dev.duardo.neotransfer.core.model.Money as CoreMoney
import dev.duardo.neotransfer.core.model.Phones
import dev.duardo.neotransfer.core.model.ServiceKind as CoreKind
import dev.duardo.neotransfer.core.model.ServiceReference
import dev.duardo.neotransfer.core.model.identify
import dev.duardo.neotransfer.core.protocol.AccountsQuery
import dev.duardo.neotransfer.core.protocol.BalanceQuery
import dev.duardo.neotransfer.core.protocol.BankRegistration
import dev.duardo.neotransfer.core.protocol.BankRequest
import dev.duardo.neotransfer.core.protocol.CardAssociation
import dev.duardo.neotransfer.core.protocol.ClassicBalanceQuery
import dev.duardo.neotransfer.core.protocol.ClassicHistoryQuery
import dev.duardo.neotransfer.core.protocol.ClassicQrPay
import dev.duardo.neotransfer.core.protocol.ClassicRemoval
import dev.duardo.neotransfer.core.protocol.ClassicTransfer
import dev.duardo.neotransfer.core.protocol.CubacelTransfer
import dev.duardo.neotransfer.core.protocol.ElectricityBill
import dev.duardo.neotransfer.core.protocol.HistoryQuery
import dev.duardo.neotransfer.core.protocol.MiTransferRegistration
import dev.duardo.neotransfer.core.protocol.MobileRecharge
import dev.duardo.neotransfer.core.protocol.NautaPayment
import dev.duardo.neotransfer.core.protocol.NautaService
import dev.duardo.neotransfer.core.protocol.PhoneBill
import dev.duardo.neotransfer.core.protocol.PinChange
import dev.duardo.neotransfer.core.protocol.QrPay
import dev.duardo.neotransfer.core.protocol.RegistrationRemoval
import dev.duardo.neotransfer.core.protocol.Source
import dev.duardo.neotransfer.core.protocol.Transfer
import dev.duardo.neotransfer.core.protocol.UssdRequest
import dev.duardo.neotransfer.core.protocol.WaterBill
import dev.duardo.neotransfer.core.qr.QrPayment
import dev.duardo.neotransfer.core.sms.PaidService
import dev.duardo.neotransfer.core.sms.SmsMessage
import dev.duardo.neotransfer.data.ClassicCard
import dev.duardo.neotransfer.data.Settings
import dev.duardo.neotransfer.data.WalletCard
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.designsystem.format.formatPhone
import dev.duardo.neotransfer.engine.CardView
import dev.duardo.neotransfer.engine.KeyToSave
import dev.duardo.neotransfer.engine.Notices
import dev.duardo.neotransfer.ui.model.BalanceUi
import dev.duardo.neotransfer.ui.model.Bank
import dev.duardo.neotransfer.ui.model.BankLineUi
import dev.duardo.neotransfer.ui.model.CardUi
import dev.duardo.neotransfer.ui.model.ClassicSettingsUi
import dev.duardo.neotransfer.ui.model.ContactUi
import dev.duardo.neotransfer.ui.model.CounterpartUi
import dev.duardo.neotransfer.ui.model.ForeignCardUi
import dev.duardo.neotransfer.ui.model.KeyToSaveUi
import dev.duardo.neotransfer.ui.model.LastMovementUi
import dev.duardo.neotransfer.ui.model.LineAccountUi
import dev.duardo.neotransfer.ui.model.LineRequestUi
import dev.duardo.neotransfer.ui.model.LineStateUi
import dev.duardo.neotransfer.ui.model.Money
import dev.duardo.neotransfer.ui.model.OperationKind
import dev.duardo.neotransfer.ui.model.OperationStatus
import dev.duardo.neotransfer.ui.model.OperationUi
import dev.duardo.neotransfer.ui.model.PhoneTargetUi
import dev.duardo.neotransfer.ui.model.RechargeTargetUi
import dev.duardo.neotransfer.ui.model.RegistrationField
import dev.duardo.neotransfer.ui.model.SavedReferenceUi
import dev.duardo.neotransfer.ui.model.ServiceKind
import dev.duardo.neotransfer.ui.model.WalletCardUi
import java.time.Duration
import java.time.Instant

/** Everything the controller publishes, in one value. */
data class Domain(
    val loaded: Boolean = false,
    val cards: List<CardView> = emptyList(),
    val classics: List<ClassicCard> = emptyList(),
    val operations: List<CoreStatus> = emptyList(),
    val movements: List<Movement> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val services: List<ServiceReference> = emptyList(),
    /** Banks and MiTransfer with a saved key. */
    val savedKeys: Set<Agency> = emptySet(),
    /** Banks and MiTransfer whose saved key was rejected. */
    val blocked: Set<Agency> = emptySet(),
    val keysToSave: List<KeyToSave> = emptyList(),
    /** Unlocked without the saved keys ([dev.duardo.neotransfer.engine.AppController.keysLocked]): unlock again to save or send with one. */
    val keysLocked: Boolean = false,
    val settings: Settings = Settings(),
    val locked: Boolean = true,
    val lastBackup: Instant? = null,
    /** Contacts that come from the phone's address book; null while it cannot be read. */
    val addressBook: Set<String>? = null,
) {
    /** A bank or the Clásica is set up once it has a card or a saved key: from then on the app locks. */
    val configured: Boolean get() = cards.isNotEmpty() || classics.isNotEmpty() || savedKeys.isNotEmpty()
}

fun CoreBank.ui(): Bank = Bank.valueOf(name)
/** The core's bank of a bank issuer; [Bank.CLASSIC] has none: use [agency]. */
fun Bank.core(): CoreBank = CoreBank.valueOf(name)
/** Whose key and session an issuer uses: its bank, or MiTransfer for the Clásica. */
fun Bank.agency(): Agency = if (this == Bank.CLASSIC) MiTransfer else core()
/** The fingerprint prompt's title when this issuer's key is saved. */
val Bank.saveKeyTitle: String get() = if (this == Bank.CLASSIC) "Guardar el PIN de MiTransfer" else "Guardar la clave de $shortName"
fun Agency.ui(): Bank = when (this) {
    is CoreBank -> ui()
    MiTransfer -> Bank.CLASSIC
}
fun CoreMoney.ui(): Money = Money(amount, currency.name)

fun WalletCard.ui(): CardUi = CardUi(card.number, card.bank.ui(), card.number, card.currency.name, holder, expiry)
fun ClassicCard.ui(): CardUi = CardUi(number, Bank.CLASSIC, number, Currency.USD.name)

/** The issuer a number, whole or while typed, shows: its bank, or the Clásica for a personal one (`976003…`). */
fun issuerBank(digits: String): Bank? = if (digits.startsWith("976003")) Bank.CLASSIC else Cards.issuer(digits).bank?.ui()

/**
 * The domain as the screens show it: cards with balances, contacts, and one activity list where a bank receipt
 * and the operation that produced it are a single item. Pure: built off the main thread on every change.
 */
class Book(val domain: Domain, private val now: Instant = Instant.now()) {
    private val ownCards = domain.cards.map { it.card.card }

    // The whole address book is in [Domain.contacts]: activity looks people up here instead of scanning it per item.
    private val byPhone = domain.contacts.flatMap { c -> c.phones.mapNotNull(Phones::normalize).distinct().map { it to c } }.groupBy({ it.first }, { it.second })
    private val byCard = domain.contacts.flatMap { c -> c.cards.mapNotNull(Cards::normalize).distinct().map { it to c } }.groupBy({ it.first }, { it.second })

    /** Own cards: the bank cards in wallet order, then the Clásicas. */
    val cards: List<CardUi> = domain.cards.map { it.card.ui() } + domain.classics.map { it.ui() }

    /**
     * [cards] with their balance. A Clásica's is MiTransfer's last answer in its own words, without its signature
     * ([BalanceUi.Text], [Notices.unsigned]): the core reads no figure from it.
     */
    val wallet: List<WalletCardUi> = domain.cards.map { view ->
        val balance = view.balance.value?.let { BalanceUi.Known(it.ui(), view.balance.updatedAt ?: view.balance.observedAt ?: now) } ?: BalanceUi.Unknown
        val query = BalanceQuery(view.card.card.bank, Source.forCard(view.card.card, ownCards))
        val refreshing = domain.operations.any { it.operation.request == query && it.state.inFlight }
        WalletCardUi(view.card.ui(), balance, refreshing)
    } + domain.classics.map { classic ->
        val query = ClassicBalanceQuery(classic.number)
        val answer = domain.operations.firstNotNullOfOrNull { status -> (status.state as? OperationState.Answered)?.takeIf { status.operation.request == query } }
        val refreshing = domain.operations.any { it.operation.request == query && it.state.inFlight }
        WalletCardUi(classic.ui(), answer?.let { BalanceUi.Text(Notices.unsigned(it.text), it.at) } ?: BalanceUi.Unknown, refreshing)
    }

    /**
     * The cards that can send to [destination]: the bank cards; when it is a Clásica, the Clásicas first, since only
     * MiTransfer moves money between them.
     */
    fun transferSources(destination: String): List<WalletCardUi> {
        val (classic, banks) = wallet.partition { it.card.bank == Bank.CLASSIC }
        return if (Cards.isClassic(destination)) classic + banks else banks
    }

    /** The cards that can pay [payment]: those in its currency; a Clásica unless the payment leaves the card out. */
    fun paySources(payment: QrPayment): List<WalletCardUi> {
        val special = payment.service == 30 && payment.auxiliary.contains("11200301")
        return wallet.filter { it.card.currency == payment.amount.currency.name && !(special && it.card.bank == Bank.CLASSIC) }
    }

    /** Keys a registration or a confirmed change brought, waiting for the fingerprint or for the person to type them. */
    val keysToSave: List<KeyToSaveUi> = domain.keysToSave.map { KeyToSaveUi(it.agency.ui(), it.inHand) }

    fun keyToSave(bank: Bank): KeyToSaveUi? = keysToSave.firstOrNull { it.bank == bank }

    private val operationIdsWithReceipt = domain.movements.mapNotNullTo(mutableSetOf()) { it.operationId }
    private val operationsById = domain.operations.associateBy { it.operation.id }

    /** Operations still on their way, and money that did not go through and was not retried yet. */
    val inProgress: List<OperationUi>

    /** Every movement and every money operation, newest first. */
    val activity: List<OperationUi>

    init {
        val fromOperations = domain.operations.filter { it.operation.request.isActivity && it.operation.id !in operationIdsWithReceipt }
            .mapNotNull { status -> operation(status) }
        val all = (fromOperations + domain.movements.map(::movement)).sortedByDescending { it.at }
        activity = all
        val latestByRequest = domain.operations.groupBy { it.operation.request }.mapValues { (_, list) -> list.maxOf { it.operation.createdAt } }
        inProgress = domain.operations.filter { status ->
            val request = status.operation.request
            request.isActivity && status.operation.id !in operationIdsWithReceipt && (status.state.inFlight ||
                (status.state is OperationState.NotExecuted && latestByRequest[request] == status.operation.createdAt &&
                    Duration.between(status.operation.createdAt, now) < RETRY_WINDOW))
        }.mapNotNull { status -> fromOperations.firstOrNull { it.id == status.operation.id } }
    }

    /** Everyone, by name, each with the newest movement with them (money that did not go through left out). */
    val contacts: List<ContactUi> = run {
        val last = activity.filter { it.contactId != null && it.status != OperationStatus.Rejected && it.status != OperationStatus.NotExecuted }
            .groupBy { it.contactId!! }.mapValues { (_, items) -> items.first().let { LastMovementUi(it.kind, it.amount, it.at) } }
        domain.contacts.map { contact ->
            ContactUi(contact.id, contact.name, contact.cards.map { foreignCard(it, contact.labels[it]) }, contact.phones,
                domain.addressBook?.contains(contact.id) == true, last[contact.id])
        }
    }

    /** The Contacts tab: people with a movement or a saved card, newest movement first. */
    val people: List<ContactUi> = contacts.filter { it.last != null || it.cards.isNotEmpty() }
        .sortedWith(compareByDescending<ContactUi> { it.last?.at }.thenBy { it.name.lowercase() })

    /** Last operations asked of the bank or MiTransfer, answer still pending. */
    val checkingHistory: Boolean = domain.operations.any { (it.operation.request is HistoryQuery || it.operation.request is ClassicHistoryQuery) && it.state.inFlight }

    /** Recent activity for Home: what is not already listed as in progress. */
    val recent: List<OperationUi> = inProgress.mapTo(mutableSetOf()) { it.id }.let { shown -> activity.filter { it.id !in shown } }

    val references: List<SavedReferenceUi> = domain.services.map(::reference)

    val rechargeTargets: List<RechargeTargetUi> = references.filter { it.kind == ServiceKind.MobileTopUp || it.kind == ServiceKind.NautaWifi || it.kind == ServiceKind.NautaHome }
        .map { RechargeTargetUi(it.id, it.kind, it.name, it.value.ifEmpty { it.name }) }

    /** Mobiles recharged or sent balance to, newest first, then the saved ones never paid from here. */
    val recentPhones: List<PhoneTargetUi> = (activity.asSequence()
        .filter { it.kind == OperationKind.MobileTopUp || it.kind == OperationKind.CubacelTransfer }
        .mapNotNull { (it.counterpart as? CounterpartUi.Phone)?.number?.let(Phones::normalize) }
        .distinct().take(8) + domain.services.asSequence().filter { it.kind == CoreKind.MOBILE }.mapNotNull { Phones.normalize(it.value) })
        .distinct().map(::phoneTarget).toList()

    /** Each mobile in the contacts, one per row. */
    val contactPhones: List<PhoneTargetUi> = contacts.flatMap { contact ->
        contact.phones.mapNotNull { Phones.normalize(it)?.takeIf { phone -> phone[0] in "56" } }.distinct().map { PhoneTargetUi(it, contact.name) }
    }

    /** [phone] named as Guardados or the contacts name it. */
    fun phoneTarget(phone: String): PhoneTargetUi = PhoneTargetUi(phone, serviceName(phone) ?: contactName(phone = phone)?.name)

    fun card(number: String): CardView? = domain.cards.firstOrNull { it.card.card.number == number }
    fun classic(number: String): ClassicCard? = domain.classics.firstOrNull { it.number == number }
    /** An own card of either kind, as the screens show it. */
    private fun ownCard(number: String): CardUi? = card(number)?.card?.ui() ?: classic(number)?.ui()
    fun contact(id: String): ContactUi? = contacts.firstOrNull { it.id == id }
    fun reference(id: String): ServiceReference? = domain.services.firstOrNull { referenceId(it) == id }

    /** An activity item by its own id, or by the operation that produced a receipt. */
    fun item(id: String): OperationUi? = activity.firstOrNull { it.id == id }
        ?: domain.movements.firstOrNull { it.operationId == id }?.let { m -> activity.firstOrNull { it.id == m.id } }

    /** The operation behind an item, when the app sent it. */
    fun operationOf(itemId: String): CoreStatus? = operationsById[itemId]
        ?: domain.movements.firstOrNull { it.id == itemId }?.operationId?.let(operationsById::get)

    /** A card of someone else, with the issuer and currency its number tells ([issuerBank], [Cards.issuer]). */
    fun foreignCard(number: String, label: String? = null): ForeignCardUi =
        ForeignCardUi(number, number, issuerBank(number), Cards.issuer(number).currency?.name, label)

    /** The contact that owns the phone or card, only when exactly one does ([identify]). */
    fun contactName(card: String? = null, phone: String? = null): Contact? =
        (phone?.let(Phones::normalize)?.let(byPhone::get).orEmpty() + card?.let(Cards::normalize)?.let(byCard::get).orEmpty()).distinct().singleOrNull()

    // ---- Operations and movements ----

    private fun operation(status: CoreStatus): OperationUi? {
        val request = status.operation.request
        val (state, reason) = when (val s = status.state) {
            OperationState.Queued, OperationState.Sending, is OperationState.Sent, is OperationState.Retrying -> OperationStatus.Sent to null
            is OperationState.Confirmed -> OperationStatus.Confirmed to null
            is OperationState.Rejected -> OperationStatus.Rejected to s.reason.trimEnd('.')
            is OperationState.NoResponse -> OperationStatus.NoResponse to null
            is OperationState.NotExecuted -> OperationStatus.NotExecuted to Notices.notExecuted(s).replaceFirstChar(Char::uppercase)
            is OperationState.Answered -> cubacelAnswer(s.text)
        }
        val contact = when (request) {
            is Transfer -> contactName(card = request.destination)
            is ClassicTransfer -> contactName(card = request.destination)
            is MobileRecharge -> contactName(phone = request.phone)
            is CubacelTransfer -> contactName(phone = request.phone)
            else -> null
        }
        return OperationUi(
            id = status.operation.id,
            kind = request.kind() ?: return null,
            title = contact?.name ?: title(request),
            status = state,
            at = status.operation.createdAt,
            amount = amount(request)?.ui(),
            card = when (request) {
                is BankRequest -> sourceCard(request)
                is ClassicTransfer -> ownCard(request.card)
                is ClassicQrPay -> ownCard(request.card)
                else -> null
            },
            counterpart = counterpart(request),
            contactId = contact?.id,
            reason = reason,
        )
    }

    private fun movement(m: Movement): OperationUi {
        val request = m.operationId?.let(operationsById::get)?.operation?.request
        val incoming = m.direction == Direction.IN
        val kind = request?.kind() ?: when (m.kind) {
            MovementKind.TRANSFER, MovementKind.OTHER -> if (incoming) OperationKind.Incoming else OperationKind.Transfer
            MovementKind.QR_PAYMENT -> OperationKind.QrPayment
            MovementKind.SERVICE -> when (m.service) {
                PaidService.ELECTRICITY, PaidService.STAMP, null -> OperationKind.Electricity
                PaidService.PHONE -> OperationKind.Landline
                PaidService.WATER -> OperationKind.Water
                PaidService.NAUTA_RECHARGE -> OperationKind.NautaWifi
                PaidService.NAUTA_HOME -> OperationKind.NautaHome
                PaidService.MOBILE_RECHARGE -> OperationKind.MobileTopUp
            }
        }
        val counterparty = m.counterparty
        val sender = if (incoming && m.kind == MovementKind.TRANSFER) counterparty?.let(Phones::normalize) else null
        val destination = if (!incoming && m.kind == MovementKind.TRANSFER) counterparty else null
        val contact = when {
            request is Transfer -> contactName(card = request.destination)
            request is ClassicTransfer -> contactName(card = request.destination)
            sender != null -> contactName(phone = sender)
            destination != null && Cards.isNumber(destination) -> contactName(card = destination)
            else -> null
        }
        val title = contact?.name ?: request?.let(::title) ?: when {
            m.kind == MovementKind.OTHER -> m.label?.replaceFirstChar(Char::uppercase) ?: kindLabel(kind)
            sender != null -> formatPhone(sender)
            destination != null -> formatCardNumber(destination)
            m.kind == MovementKind.SERVICE -> counterparty?.let { serviceName(it) ?: it } ?: kindLabel(kind)
            else -> counterparty ?: kindLabel(kind)
        }
        return OperationUi(
            id = m.id,
            kind = kind,
            title = title,
            status = OperationStatus.Confirmed,
            at = m.at,
            dateOnly = m.dateOnly,
            amount = m.amount.ui(),
            card = (if (incoming) m.creditCard else m.debitCard)?.let(::ownCard),
            counterpart = when {
                request != null -> counterpart(request)
                sender != null -> CounterpartUi.Phone(sender)
                destination != null -> CounterpartUi.Card(destination, if (Cards.isNumber(destination)) foreignCard(destination).issuer else null)
                counterparty != null && m.kind != MovementKind.OTHER -> CounterpartUi.Reference(counterparty)
                else -> null
            },
            contactId = contact?.id,
            transactionId = m.reference,
            balanceAfter = m.balanceAfter?.ui(),
        )
    }

    private fun title(request: UssdRequest): String = when (request) {
        is Transfer -> formatCardNumber(request.destination)
        is ClassicTransfer -> formatCardNumber(request.destination)
        is QrPay -> request.payment.title ?: "Pago con QR"
        is ClassicQrPay -> request.payment.title ?: "Pago con QR"
        is ElectricityBill -> serviceName(request.invoice) ?: request.invoice
        is PhoneBill -> serviceName(request.invoice) ?: request.invoice
        is WaterBill -> serviceName(request.account) ?: request.account
        is NautaPayment -> serviceName(request.account.address) ?: request.account.user
        is MobileRecharge -> serviceName(request.phone) ?: formatPhone(request.phone)
        is CubacelTransfer -> formatPhone(request.phone)
        else -> "Operación"
    }

    private fun counterpart(request: UssdRequest): CounterpartUi? = when (request) {
        is Transfer -> CounterpartUi.Card(request.destination, foreignCard(request.destination).issuer)
        is ClassicTransfer -> CounterpartUi.Card(request.destination, foreignCard(request.destination).issuer)
        is QrPay -> CounterpartUi.Reference(request.payment.transactionId)
        is ClassicQrPay -> CounterpartUi.Reference(request.payment.transactionId)
        is ElectricityBill -> CounterpartUi.Reference(request.invoice)
        is PhoneBill -> CounterpartUi.Reference(request.invoice)
        is WaterBill -> CounterpartUi.Reference(request.account)
        is NautaPayment -> CounterpartUi.Reference(request.account.address)
        is MobileRecharge -> CounterpartUi.Phone(request.phone)
        is CubacelTransfer -> CounterpartUi.Phone(request.phone)
        else -> null
    }

    private fun amount(request: UssdRequest): CoreMoney? = when (request) {
        is Transfer -> request.amount
        is ClassicTransfer -> request.amount
        is QrPay -> request.amount
        is ClassicQrPay -> request.amount
        is PhoneBill -> request.amount
        is NautaPayment -> request.amount
        is MobileRecharge -> request.amount
        is CubacelTransfer -> CoreMoney(request.amount.toBigDecimal())
        else -> null
    }

    /** The own card a request debits: an explicit card, or the bank's only card in that currency. */
    fun sourceCard(request: BankRequest): CardUi? = when (val source = request.source()) {
        is Source.Card -> card(source.number)?.card?.ui()
        is Source.Default -> domain.cards.map { it.card }.singleOrNull { it.card.bank == request.bank && it.card.currency == source.currency }?.ui()
        null -> null
    }

    /** The saved name of a service account, phone or Nauta user. */
    private fun serviceName(value: String): String? = domain.services.firstOrNull { sameAccount(it.value, value) }?.label

    private fun reference(service: ServiceReference): SavedReferenceUi {
        val kind = service.kind.ui()
        val name = service.label ?: (if (service.kind == CoreKind.MOBILE) contactName(phone = service.value)?.name else null)
        val shown = if (service.kind == CoreKind.MOBILE) formatPhone(service.value) else service.value
        val paid = domain.movements.firstOrNull { it.kind == MovementKind.SERVICE && it.counterparty?.let { c -> sameAccount(c, service.value) } == true }
        return SavedReferenceUi(referenceId(service), kind, name ?: shown, if (name == null) "" else shown, paid?.amount?.ui())
    }

    // ---- The line with each bank and the Clásica ----

    /** The operation [id] as a line service shows it. */
    fun lineRequest(id: String): LineRequestUi? = operationsById[id]?.let(::lineRequest)

    /** The newest operation whose request [matches], as a line service shows it; null while none was asked. */
    fun lastRequest(matches: (UssdRequest) -> Boolean): LineRequestUi? =
        domain.operations.firstOrNull { matches(it.operation.request) }?.let(::lineRequest)

    /** «Tu línea en X»: the last request of each service, and the accounts of the last `58` answered. */
    fun bankLine(bank: Bank): BankLineUi {
        val core = bank.core()
        val answer = domain.operations.firstNotNullOfOrNull { status ->
            ((status.state as? OperationState.Confirmed)?.result as? SmsMessage.Balance)?.takeIf { (status.operation.request as? AccountsQuery)?.bank == core }
        }
        return BankLineUi(
            bank = bank,
            keyLength = core.pinLength,
            keyChange = lastRequest { it is PinChange && it.bank == core },
            accounts = lastRequest { it is AccountsQuery && it.bank == core },
            accountList = answer?.let { accounts(core, it) }.orEmpty(),
            association = lastRequest { it is CardAssociation && it.bank == core },
            removal = lastRequest { it is RegistrationRemoval && it.bank == core },
            coordinates = core == CoreBank.BANMET,
            keyToSave = keyToSave(bank),
        )
    }

    /** Ajustes › Clásica; null when [number] is not an own Clásica. */
    fun classicSettings(number: String): ClassicSettingsUi? {
        val card = wallet.firstOrNull { it.card.bank == Bank.CLASSIC && it.card.number == number } ?: return null
        return ClassicSettingsUi(card, MiTransfer in domain.savedKeys, lastRequest { it is ClassicRemoval && it.card == number }, keyToSave(Bank.CLASSIC))
    }

    /** The last operations of a Clásica: MiTransfer's answer comes as text ([LineStateUi.Answered]). */
    fun classicHistory(number: String): LineRequestUi? = lastRequest { it == ClassicHistoryQuery(number) }

    /** The rows of a `58` answer, each with the wallet's card it is: by whole number or by the bank's mask. */
    private fun accounts(bank: CoreBank, balance: SmsMessage.Balance): List<LineAccountUi> = balance.rows.map { row ->
        val own = row.account?.let { account ->
            domain.cards.map { it.card }.singleOrNull { it.card.bank == bank && (it.card.number == account || Cards.matches(account, it.card.number)) }
        }
        LineAccountUi(row.account, row.label, row.available.ui(), own?.ui(), row.account?.takeIf { own == null && Cards.isNumber(it) })
    }

    private fun lineRequest(status: CoreStatus): LineRequestUi = LineRequestUi(status.operation.id, status.operation.createdAt,
        when (val state = status.state) {
            OperationState.Queued, OperationState.Sending, is OperationState.Sent, is OperationState.Retrying -> LineStateUi.Sent
            is OperationState.Confirmed -> LineStateUi.Confirmed(state.at)
            is OperationState.Rejected -> LineStateUi.Rejected(state.reason)
            is OperationState.NoResponse -> LineStateUi.NoResponse
            is OperationState.NotExecuted -> LineStateUi.NotExecuted(Notices.notExecuted(state).replaceFirstChar(Char::uppercase))
            is OperationState.Answered -> LineStateUi.Answered(Notices.unsigned(state.text), state.at)
        })

    private companion object {
        /** A money operation that did not go through stays in "Pendientes" this long, with Reintentar. */
        val RETRY_WINDOW: Duration = Duration.ofHours(12)
    }
}

/** What this issuer's registration (`49`) asks, in its order; the Clásica's, MiTransfer's, also asks a new PIN. */
fun Bank.registrationFields(): List<RegistrationField> = when (this) {
    Bank.BPA -> listOf(RegistrationField.Card, RegistrationField.Name, RegistrationField.FirstSurname, RegistrationField.SecondSurname, RegistrationField.Identity)
    Bank.BANDEC -> listOf(RegistrationField.Card, RegistrationField.FullName, RegistrationField.Expiry)
    Bank.BANMET -> listOf(RegistrationField.Telebanca, RegistrationField.Name, RegistrationField.FirstSurname, RegistrationField.SecondSurname,
        RegistrationField.Identity)
    Bank.CLASSIC -> listOf(RegistrationField.Identity)
}

/**
 * The registration request from the values typed for [registrationFields], trimmed.
 * @throws IllegalArgumentException with a message ready to show when a value does not fit
 */
fun Bank.registration(values: Map<RegistrationField, String>): UssdRequest {
    fun value(field: RegistrationField) = values[field]?.trim()?.ifEmpty { null }
    return when (this) {
        Bank.CLASSIC -> MiTransferRegistration(requireNotNull(value(RegistrationField.Identity)) { "Escribe el carné de identidad o el pasaporte" })
        Bank.BANDEC -> BankRegistration(core(), value(RegistrationField.Card).orEmpty(), value(RegistrationField.FullName).orEmpty(),
            expiry = value(RegistrationField.Expiry)?.let(::flipped))
        Bank.BPA, Bank.BANMET -> BankRegistration(core(), value(if (this == Bank.BANMET) RegistrationField.Telebanca else RegistrationField.Card).orEmpty(),
            value(RegistrationField.Name).orEmpty(), value(RegistrationField.FirstSurname), value(RegistrationField.SecondSurname),
            value(RegistrationField.Identity))
    }
}

/** The values a sent registration was typed with, by [registrationFields]: to revise them. */
fun BankRegistration.values(): Map<RegistrationField, String> = buildMap {
    put(if (bank == CoreBank.BANMET) RegistrationField.Telebanca else RegistrationField.Card, card)
    put(if (bank == CoreBank.BANDEC) RegistrationField.FullName else RegistrationField.Name, name)
    firstSurname?.let { put(RegistrationField.FirstSurname, it) }
    secondSurname?.let { put(RegistrationField.SecondSurname, it) }
    identity?.let { put(RegistrationField.Identity, it) }
    expiry?.let { put(RegistrationField.Expiry, flipped(it)) }
}

/** An expiry as the card prints it, month and year (`MMAA`), and as BANDEC takes it, year and month (`AAMM`): one is the other turned. */
private fun flipped(expiry: String) = expiry.drop(2) + expiry.take(2)

fun referenceId(service: ServiceReference) = "${service.kind.name}|${service.value}"

/** Same invoice, phone (with or without `53`) or Nauta account (with or without domain). */
fun sameAccount(a: String, b: String): Boolean {
    if (a.equals(b, ignoreCase = true)) return true
    val phoneA = Phones.normalize(a)
    if (phoneA != null && phoneA == Phones.normalize(b)) return true
    return a.contains('@') != b.contains('@') && a.substringBefore('@').equals(b.substringBefore('@'), ignoreCase = true)
}

fun CoreKind.ui(): ServiceKind = when (this) {
    CoreKind.ELECTRICITY -> ServiceKind.Electricity
    CoreKind.PHONE -> ServiceKind.Landline
    CoreKind.WATER_HAVANA, CoreKind.WATER_OTHER, CoreKind.WATER_VARADERO -> ServiceKind.Water
    CoreKind.NAUTA -> ServiceKind.NautaWifi
    CoreKind.NAUTA_HOME -> ServiceKind.NautaHome
    CoreKind.MOBILE -> ServiceKind.MobileTopUp
}

/** Money operations and Cubacel balance transfers show in activity; queries never do. */
val UssdRequest.isActivity: Boolean get() = kind() != null

fun UssdRequest.kind(): OperationKind? = when (this) {
    is Transfer, is ClassicTransfer -> OperationKind.Transfer
    is QrPay, is ClassicQrPay -> OperationKind.QrPayment
    is ElectricityBill -> OperationKind.Electricity
    is PhoneBill -> OperationKind.Landline
    is WaterBill -> OperationKind.Water
    is NautaPayment -> if (service == NautaService.RECHARGE) OperationKind.NautaWifi else OperationKind.NautaHome
    is MobileRecharge -> OperationKind.MobileTopUp
    is CubacelTransfer -> OperationKind.CubacelTransfer
    else -> null
}

fun BankRequest.source(): Source? = when (this) {
    is Transfer -> source
    is QrPay -> source
    is ElectricityBill -> source
    is PhoneBill -> source
    is WaterBill -> source
    is NautaPayment -> source
    is MobileRecharge -> source
    is BalanceQuery -> source
    is HistoryQuery -> source
    else -> null
}

/** Waiting for its turn, being sent or acknowledged and waiting for the bank's SMS. */
val OperationState.inFlight: Boolean
    get() = this == OperationState.Queued || this == OperationState.Sending || this is OperationState.Sent || this is OperationState.Retrying

private fun kindLabel(kind: OperationKind) = when (kind) {
    OperationKind.Incoming -> "Transferencia recibida"
    OperationKind.QrPayment -> "Pago con QR"
    OperationKind.Electricity -> "Electricidad"
    OperationKind.Water -> "Agua"
    OperationKind.Landline -> "Teléfono fijo"
    OperationKind.NautaHome -> "Nauta Hogar"
    OperationKind.NautaWifi -> "Nauta"
    OperationKind.MobileTopUp -> "Recarga móvil"
    else -> "Transferencia"
}

/**
 * Cubacel answers in its own words. A transfer that went through says so ("ha transferido", "exito"); anything
 * else is the operator's refusal, shown as it came.
 */
private fun cubacelAnswer(text: String): Pair<OperationStatus, String?> {
    val plain = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
    return if ("transferido" in plain || "exito" in plain) OperationStatus.Confirmed to null else OperationStatus.Rejected to text.trim()
}
