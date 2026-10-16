package dev.duardo.neotransfer.app

import dev.duardo.neotransfer.core.ledger.Direction
import dev.duardo.neotransfer.core.ledger.Movement
import dev.duardo.neotransfer.core.ledger.MovementKind
import dev.duardo.neotransfer.core.ledger.OperationState
import dev.duardo.neotransfer.core.ledger.OperationStatus as CoreStatus
import dev.duardo.neotransfer.core.model.Bank as CoreBank
import dev.duardo.neotransfer.core.model.Cards
import dev.duardo.neotransfer.core.model.Contact
import dev.duardo.neotransfer.core.model.Money as CoreMoney
import dev.duardo.neotransfer.core.model.Phones
import dev.duardo.neotransfer.core.model.ServiceKind as CoreKind
import dev.duardo.neotransfer.core.model.ServiceReference
import dev.duardo.neotransfer.core.model.identify
import dev.duardo.neotransfer.core.protocol.BalanceQuery
import dev.duardo.neotransfer.core.protocol.BankRequest
import dev.duardo.neotransfer.core.protocol.CubacelTransfer
import dev.duardo.neotransfer.core.protocol.ElectricityBill
import dev.duardo.neotransfer.core.protocol.HistoryQuery
import dev.duardo.neotransfer.core.protocol.MobileRecharge
import dev.duardo.neotransfer.core.protocol.NautaPayment
import dev.duardo.neotransfer.core.protocol.NautaService
import dev.duardo.neotransfer.core.protocol.PhoneBill
import dev.duardo.neotransfer.core.protocol.QrPay
import dev.duardo.neotransfer.core.protocol.Source
import dev.duardo.neotransfer.core.protocol.Transfer
import dev.duardo.neotransfer.core.protocol.UssdRequest
import dev.duardo.neotransfer.core.protocol.WaterBill
import dev.duardo.neotransfer.core.sms.PaidService
import dev.duardo.neotransfer.data.Settings
import dev.duardo.neotransfer.data.WalletCard
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.designsystem.format.formatPhone
import dev.duardo.neotransfer.engine.CardView
import dev.duardo.neotransfer.engine.Notices
import dev.duardo.neotransfer.ui.model.BalanceUi
import dev.duardo.neotransfer.ui.model.Bank
import dev.duardo.neotransfer.ui.model.CardUi
import dev.duardo.neotransfer.ui.model.ContactUi
import dev.duardo.neotransfer.ui.model.CounterpartUi
import dev.duardo.neotransfer.ui.model.ForeignCardUi
import dev.duardo.neotransfer.ui.model.LastMovementUi
import dev.duardo.neotransfer.ui.model.Money
import dev.duardo.neotransfer.ui.model.OperationKind
import dev.duardo.neotransfer.ui.model.OperationStatus
import dev.duardo.neotransfer.ui.model.OperationUi
import dev.duardo.neotransfer.ui.model.RechargeTargetUi
import dev.duardo.neotransfer.ui.model.SavedReferenceUi
import dev.duardo.neotransfer.ui.model.ServiceKind
import dev.duardo.neotransfer.ui.model.WalletCardUi
import java.time.Duration
import java.time.Instant

/** Everything the controller publishes, in one value. */
data class Domain(
    val loaded: Boolean = false,
    val cards: List<CardView> = emptyList(),
    val operations: List<CoreStatus> = emptyList(),
    val movements: List<Movement> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val services: List<ServiceReference> = emptyList(),
    val savedKeys: Set<CoreBank> = emptySet(),
    val blockedBanks: Set<CoreBank> = emptySet(),
    val settings: Settings = Settings(),
    val locked: Boolean = true,
    val lastBackup: Instant? = null,
    /** Contacts that come from the phone's address book; null while it cannot be read. */
    val addressBook: Set<String>? = null,
) {
    /** A bank is set up once it has a card or a saved key: from then on the app locks. */
    val configured: Boolean get() = cards.isNotEmpty() || savedKeys.isNotEmpty()
}

fun CoreBank.ui(): Bank = Bank.valueOf(name)
fun Bank.core(): CoreBank = CoreBank.valueOf(name)
fun CoreMoney.ui(): Money = Money(amount, currency.name)

fun WalletCard.ui(): CardUi = CardUi(card.number, card.bank.ui(), card.number, card.currency.name, holder, expiry)

/**
 * The domain as the screens show it: cards with balances, contacts, and one activity list where a bank receipt
 * and the operation that produced it are a single item. Pure: built off the main thread on every change.
 */
class Book(val domain: Domain, private val now: Instant = Instant.now()) {
    private val ownCards = domain.cards.map { it.card.card }

    // The whole address book is in [Domain.contacts]: activity looks people up here instead of scanning it per item.
    private val byPhone = domain.contacts.flatMap { c -> c.phones.mapNotNull(Phones::normalize).distinct().map { it to c } }.groupBy({ it.first }, { it.second })
    private val byCard = domain.contacts.flatMap { c -> c.cards.mapNotNull(Cards::normalize).distinct().map { it to c } }.groupBy({ it.first }, { it.second })

    val cards: List<CardUi> = domain.cards.map { it.card.ui() }

    val wallet: List<WalletCardUi> = domain.cards.map { view ->
        val balance = view.balance.value?.let { BalanceUi.Known(it.ui(), view.balance.updatedAt ?: view.balance.observedAt ?: now) } ?: BalanceUi.Unknown
        val query = BalanceQuery(view.card.card.bank, Source.forCard(view.card.card, ownCards))
        val refreshing = domain.operations.any { it.operation.request == query && it.state.inFlight }
        WalletCardUi(view.card.ui(), balance, refreshing)
    }

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

    /** Last operations asked of the bank, answer still pending. */
    val checkingHistory: Boolean = domain.operations.any { it.operation.request is HistoryQuery && it.state.inFlight }

    /** Recent activity for Home: what is not already listed as in progress. */
    val recent: List<OperationUi> = inProgress.mapTo(mutableSetOf()) { it.id }.let { shown -> activity.filter { it.id !in shown } }

    val references: List<SavedReferenceUi> = domain.services.map(::reference)

    val rechargeTargets: List<RechargeTargetUi> = references.filter { it.kind == ServiceKind.MobileTopUp || it.kind == ServiceKind.NautaWifi }
        .map { RechargeTargetUi(it.id, it.kind, it.name, it.value.ifEmpty { it.name }) }

    fun card(number: String): CardView? = domain.cards.firstOrNull { it.card.card.number == number }
    fun contact(id: String): ContactUi? = contacts.firstOrNull { it.id == id }
    fun reference(id: String): ServiceReference? = domain.services.firstOrNull { referenceId(it) == id }

    /** An activity item by its own id, or by the operation that produced a receipt. */
    fun item(id: String): OperationUi? = activity.firstOrNull { it.id == id }
        ?: domain.movements.firstOrNull { it.operationId == id }?.let { m -> activity.firstOrNull { it.id == m.id } }

    /** The operation behind an item, when the app sent it. */
    fun operationOf(itemId: String): CoreStatus? = operationsById[itemId]
        ?: domain.movements.firstOrNull { it.id == itemId }?.operationId?.let(operationsById::get)

    /** A card of someone else: its bank only when it shares the issuer prefix with one of the user's cards. */
    fun foreignCard(number: String, label: String? = null): ForeignCardUi =
        ForeignCardUi(number, number, ownCards.firstOrNull { it.number.take(6) == number.take(6) }?.bank?.name, label)

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
            card = (request as? BankRequest)?.let(::sourceCard),
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
            amount = m.amount.ui(),
            card = (if (incoming) m.creditCard else m.debitCard)?.let { card(it)?.card?.ui() },
            counterpart = when {
                request != null -> counterpart(request)
                sender != null -> CounterpartUi.Phone(sender)
                destination != null -> CounterpartUi.Card(destination, if (Cards.isNumber(destination)) foreignCard(destination).bankName else null)
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
        is QrPay -> request.payment.title ?: "Pago con QR"
        is ElectricityBill -> serviceName(request.invoice) ?: request.invoice
        is PhoneBill -> serviceName(request.invoice) ?: request.invoice
        is WaterBill -> serviceName(request.account) ?: request.account
        is NautaPayment -> serviceName(request.account.address) ?: request.account.user
        is MobileRecharge -> serviceName(request.phone) ?: formatPhone(request.phone)
        is CubacelTransfer -> formatPhone(request.phone)
        else -> "Operación"
    }

    private fun counterpart(request: UssdRequest): CounterpartUi? = when (request) {
        is Transfer -> CounterpartUi.Card(request.destination, foreignCard(request.destination).bankName)
        is QrPay -> CounterpartUi.Reference(request.payment.transactionId)
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
        is QrPay -> request.amount
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

    private companion object {
        /** A money operation that did not go through stays in "Pendientes" this long, with Reintentar. */
        val RETRY_WINDOW: Duration = Duration.ofHours(12)
    }
}

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
    is Transfer -> OperationKind.Transfer
    is QrPay -> OperationKind.QrPayment
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
    OperationKind.NautaWifi -> "Nauta Wi‑Fi"
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
