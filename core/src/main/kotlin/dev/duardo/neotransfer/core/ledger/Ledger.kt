package dev.duardo.neotransfer.core.ledger

import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Cards
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.core.model.Phones
import dev.duardo.neotransfer.core.protocol.Authenticate
import dev.duardo.neotransfer.core.protocol.BalanceQuery
import dev.duardo.neotransfer.core.protocol.BankRequest
import dev.duardo.neotransfer.core.protocol.BankResponse
import dev.duardo.neotransfer.core.protocol.Disconnect
import dev.duardo.neotransfer.core.protocol.ElectricityBill
import dev.duardo.neotransfer.core.protocol.HistoryQuery
import dev.duardo.neotransfer.core.protocol.MobileRecharge
import dev.duardo.neotransfer.core.protocol.NautaPayment
import dev.duardo.neotransfer.core.protocol.NautaService
import dev.duardo.neotransfer.core.protocol.PhoneBill
import dev.duardo.neotransfer.core.protocol.QrPay
import dev.duardo.neotransfer.core.protocol.Transfer
import dev.duardo.neotransfer.core.protocol.UssdRequest
import dev.duardo.neotransfer.core.protocol.WaterBill
import dev.duardo.neotransfer.core.sms.PaidService
import dev.duardo.neotransfer.core.sms.Receipt
import dev.duardo.neotransfer.core.sms.RejectedOperation
import dev.duardo.neotransfer.core.sms.SmsMessage
import dev.duardo.neotransfer.core.sms.SmsRecord
import java.time.Duration
import java.time.Instant

/** The bank session the app believes valid: one bank at a time, 60 minutes from the `40`. */
data class Session(val bank: Bank, val since: Instant) {
    val until: Instant get() = since + Ledger.SESSION_TTL
}

/**
 * The one thing to send now. When [isNew], persist [operation] first (it is an automatic `40`
 * built by the ledger), then call [Operation.sending] and hand its commands to the modem.
 */
data class Dispatch(val operation: Operation, val isNew: Boolean)

data class OperationStatus(val operation: Operation, val state: OperationState)

/** Everything the app shows and does next, derived from the stored facts. */
class LedgerView internal constructor(
    /** User operations (automatic authentications excluded), oldest first. */
    val operations: List<OperationStatus>,
    val next: Dispatch?,
    /** When to evaluate again if nothing else happens (retry, acknowledgement or 15-minute deadline). */
    val wakeAt: Instant?,
    val session: Session?,
    /** Banks whose saved key was rejected; nothing goes out for them until the key is saved again. */
    val blockedBanks: Set<Bank>,
    /** Activity: receipts as movements, newest first. Authentications never appear. */
    val movements: List<Movement>,
    /** Balance per own card number. */
    val balances: Map<String, CardBalance>,
) {
    fun status(id: String): OperationStatus? = operations.firstOrNull { it.operation.id == id }

    /**
     * A still-pending operation that moves the same amount from the same origin to the same
     * destination. When not null, ask the user before sending another one; never block.
     */
    fun pendingDuplicate(request: UssdRequest): OperationStatus? {
        val key = request.duplicateKey ?: return null
        return operations.lastOrNull { it.operation.request.duplicateKey == key && it.state.isPending }
    }
}

/**
 * The operation book: a pure function of what was sent, what the bank answered by SMS, the
 * user's cards and the clock. Nothing waits on an SMS; the result applies whenever it arrives.
 */
object Ledger {
    val ACK_TIMEOUT: Duration = Duration.ofSeconds(45)
    val NO_RESPONSE_AFTER: Duration = Duration.ofMinutes(15)
    val SESSION_TTL: Duration = Duration.ofMinutes(60)
    val BUSY_RETRY_DELAY: Duration = Duration.ofSeconds(15)

    /** Longest gap between a send and the SMSC stamp of its answer that still correlates. */
    private val WINDOW = Duration.ofHours(2)
    /** An answer cannot leave the SMSC much before the request left the phone. */
    private val SKEW = Duration.ofSeconds(30)

    /** Creates a queued operation for a user request. */
    fun submit(id: String, request: UssdRequest, now: Instant): Operation = Operation(id, request, now)

    /**
     * @param operations every stored operation, automatic authentications included
     * @param messages every ingested PAGOxMOVIL record (order does not matter)
     * @param pinSavedAt when each bank key was last saved; clears a [LedgerView.blockedBanks] entry
     */
    fun evaluate(
        operations: List<Operation>, messages: List<SmsRecord>, cards: List<OwnCard>, now: Instant,
        pinSavedAt: Map<Bank, Instant> = emptyMap(),
    ): LedgerView {
        val sms = messages.distinctBy { it.id }.sortedWith(compareBy({ it.sentAt }, { it.id }))
        val book = Book(operations.sortedBy { it.createdAt }, sms, now)
        val blocked = book.blockedBanks(pinSavedAt)
        val session = book.session()
        val statuses = book.ops.filter { it.unlocks == null }.map { OperationStatus(it, book.state(it, pinSavedAt)) }
        val activity = Activity(sms, book.operationOf, book.byId, cards)
        return LedgerView(statuses, book.dispatch(statuses, session), book.wakeAt(statuses), session, blocked,
            activity.movements, activity.balances())
    }

    private sealed interface AuthStatus {
        data object Unsent : AuthStatus
        data object InFlight : AuthStatus
        data class Accepted(val at: Instant) : AuthStatus
        data class Failed(val detail: String?) : AuthStatus
        data object Busy : AuthStatus
        data class Rejected(val at: Instant, val reason: String, val wrongPin: Boolean) : AuthStatus
    }

    private class Book(val ops: List<Operation>, val sms: List<SmsRecord>, val now: Instant) {
        val byId = ops.associateBy { it.id }
        val resolved = mutableMapOf<String, SmsRecord>()
        val busy = mutableMapOf<Pair<String, Int>, SmsRecord>()
        val operationOf = mutableMapOf<Long, String>()
        val foreign = mutableListOf<SmsRecord>()

        init { correlate() }

        private fun refused(op: Operation, attempt: Attempt) =
            op.request is BankRequest && attempt.reply != null && BankResponse.parse(attempt.reply) == BankResponse.OTHER

        private fun usable(op: Operation, attempt: Attempt) = attempt.failure == null && !refused(op, attempt)

        /** An SMS can answer a send that left before it was received, at most [WINDOW] earlier. */
        private fun answers(attempt: Attempt, record: SmsRecord) = attempt.sentAt <= record.receivedAt &&
            record.sentAt >= attempt.sentAt - SKEW && Duration.between(attempt.sentAt, record.sentAt) <= WINDOW

        private fun eligible(op: Operation, record: SmsRecord) =
            op.id !in resolved && op.attempts.any { usable(op, it) && answers(it, record) }

        private fun busyMarked(op: Operation) = op.attempts.indices.any { (op.id to it) in busy }

        private fun correlate() {
            val seenReferences = mutableSetOf<String>()
            for (record in sms) {
                val message = record.message
                if (message is SmsMessage.Busy) {
                    markBusy(record)
                    continue
                }
                // Duplicates of a receipt never confirm a second operation; copies never confirm any.
                val primaryReceipt = message is Receipt && message !is SmsMessage.TransferNotice && message !is SmsMessage.ServiceNotice
                if (primaryReceipt && !seenReferences.add((message as Receipt).reference)) continue
                val candidates = ops.filter { eligible(it, record) && matches(it.request, message) }
                val chosen = when {
                    candidates.isEmpty() -> emptyList()
                    message is SmsMessage.Balance || message is SmsMessage.History || message is SmsMessage.Authenticated -> {
                        // A query answer settles every pending copy of the same query (manual resends included).
                        val first = candidates.minBy { it.attempts.first().sentAt }
                        candidates.filter { it.request == first.request }
                    }
                    message is SmsMessage.Rejected && message.operation == RejectedOperation.UNKNOWN -> listOfNotNull(candidates.singleOrNull())
                    else -> listOf(candidates.minWith(compareBy<Operation>({ !preferred(it.request, message) }, { busyMarked(it) },
                        { it.attempts.first().sentAt })))
                }
                chosen.forEach { resolved[it.id] = record }
                chosen.firstOrNull()?.let { operationOf[record.id] = it.id }
                if (chosen.isEmpty() && (message is SmsMessage.Authenticated || message is SmsMessage.AuthenticationFailed)) foreign += record
            }
        }

        /** "Solicitud en proceso" refers to the last send that left before it. */
        private fun markBusy(record: SmsRecord) {
            ops.flatMap { op -> op.attempts.mapIndexed { index, attempt -> Triple(op, index, attempt) } }
                .filter { (op, index, attempt) ->
                    op.id !in resolved && (op.id to index) !in busy && usable(op, attempt) && attempt.sentAt <= record.receivedAt &&
                        attempt.sentAt <= record.sentAt + Duration.ofSeconds(5) && Duration.between(attempt.sentAt, record.sentAt) <= WINDOW
                }
                .maxByOrNull { it.third.sentAt }
                ?.let { (op, index) -> busy[op.id to index] = record }
        }

        fun authsFor(id: String) = ops.filter { it.unlocks == id }

        /** Driven by the acknowledgement; a late success SMS feeds the session, never revives the chain. */
        fun authStatus(auth: Operation): AuthStatus {
            (resolved[auth.id]?.message as? SmsMessage.AuthenticationFailed)?.let {
                return AuthStatus.Rejected(resolved.getValue(auth.id).sentAt, it.reason, it.wrongPin)
            }
            val last = auth.attempts.lastOrNull() ?: return AuthStatus.Unsent
            return when {
                last.failure != null -> AuthStatus.Failed(last.failure)
                last.reply == null -> if (now < last.sentAt + ACK_TIMEOUT) AuthStatus.InFlight else AuthStatus.Failed(null)
                BankResponse.parse(last.reply) == BankResponse.OTHER -> AuthStatus.Failed(last.reply)
                (auth.id to auth.attempts.lastIndex) in busy -> AuthStatus.Busy
                else -> AuthStatus.Accepted(last.repliedAt ?: last.sentAt)
            }
        }

        /** When the bank rejected the saved key ("Clave incorrecta" answering one of our `40`). */
        val wrongPin: List<Pair<Bank, Instant>> = ops.filter { it.request is Authenticate }.mapNotNull { auth ->
            (authStatus(auth) as? AuthStatus.Rejected)?.takeIf { it.wrongPin }?.let { (auth.request as Authenticate).bank to it.at }
        }

        /** Rejected keys block automatic authentication of that bank until the key is saved again. */
        fun blockedBanks(pinSavedAt: Map<Bank, Instant>): Set<Bank> =
            wrongPin.filter { (bank, at) -> pinSavedAt[bank]?.isAfter(at) != true }.mapTo(mutableSetOf()) { it.first }

        /**
         * An unsent operation of a bank whose key was rejected after it was created, or while it was
         * created, never goes out later on its own: saving the key again does not revive it.
         */
        fun stoppedByWrongPin(op: Operation, bank: Bank, pinSavedAt: Map<Bank, Instant>) = wrongPin.any { (rejected, at) ->
            rejected == bank && (at >= op.createdAt || pinSavedAt[bank]?.let { it.isAfter(at) && it <= op.createdAt } != true)
        }

        /**
         * One active bank: a `40` accepted starts a session; sending a `40` for the other bank, a
         * disconnect or a foreign authentication failure ends it; a foreign successful
         * authentication (made from another app) starts one too.
         */
        fun session(): Session? {
            data class Event(val at: Instant, val bank: Bank?, val kind: Int) // 0 auth sent, 1 start, 2 end
            val events = mutableListOf<Event>()
            for (op in ops) {
                when (val request = op.request) {
                    is Authenticate -> {
                        op.attempts.forEach { events += Event(it.sentAt, request.bank, 0) }
                        (authStatus(op) as? AuthStatus.Accepted)?.let { events += Event(it.at, request.bank, 1) }
                        resolved[op.id]?.takeIf { it.message is SmsMessage.Authenticated }?.let { events += Event(it.sentAt, request.bank, 1) }
                    }
                    is Disconnect -> op.attempts.lastOrNull()?.takeIf { it.reply != null }?.let { events += Event(it.repliedAt ?: it.sentAt, null, 2) }
                    else -> Unit
                }
            }
            for (record in foreign) when (val message = record.message) {
                is SmsMessage.Authenticated -> events += Event(record.sentAt, message.bank, 1)
                is SmsMessage.AuthenticationFailed -> events += Event(record.sentAt, message.bank, 2)
                else -> Unit
            }
            var session: Session? = null
            for (event in events.sortedWith(compareBy({ it.at }, { it.kind }))) {
                session = when (event.kind) {
                    0 -> session?.takeIf { it.bank == event.bank }
                    1 -> Session(event.bank!!, event.at)
                    else -> session?.takeUnless { event.bank == null || it.bank == event.bank }
                }
            }
            return session?.takeIf { now < it.until }
        }

        fun state(op: Operation, pinSavedAt: Map<Bank, Instant>): OperationState {
            resolved[op.id]?.let { record ->
                return when (val message = record.message) {
                    is SmsMessage.Rejected -> OperationState.Rejected(record.sentAt, message.reason)
                    is SmsMessage.AuthenticationFailed -> OperationState.Rejected(record.sentAt, message.reason)
                    else -> OperationState.Confirmed(record.sentAt, message)
                }
            }
            val request = op.request
            val auths = authsFor(op.id)
            val last = op.attempts.lastOrNull()
            if (last == null) {
                val bank = (request as? BankRequest)?.bank
                val needsSession = (request as? BankRequest)?.needsSession == true
                when (val auth = auths.lastOrNull()?.let(::authStatus)) {
                    is AuthStatus.Rejected -> return OperationState.NotExecuted(
                        if (auth.wrongPin) NotExecutedReason.WRONG_PIN else NotExecutedReason.AUTHENTICATION, auth.reason)
                    is AuthStatus.Failed -> return OperationState.NotExecuted(NotExecutedReason.AUTHENTICATION, auth.detail)
                    AuthStatus.Busy -> if (auths.count { authStatus(it) == AuthStatus.Busy } >= 2) return OperationState.NotExecuted(NotExecutedReason.BUSY)
                    AuthStatus.Unsent, AuthStatus.InFlight, is AuthStatus.Accepted -> return OperationState.Sending
                    null -> Unit
                }
                if (needsSession && bank != null && stoppedByWrongPin(op, bank, pinSavedAt)) return OperationState.NotExecuted(NotExecutedReason.WRONG_PIN, "Clave incorrecta")
                return OperationState.Queued
            }
            if (last.failure != null) return OperationState.NotExecuted(NotExecutedReason.SEND_FAILED, last.failure)
            if (last.reply == null && now < last.sentAt + ACK_TIMEOUT) return OperationState.Sending
            if (request !is BankRequest || request is Disconnect) {
                return if (last.reply != null) OperationState.Answered(last.repliedAt ?: last.sentAt, last.reply)
                else OperationState.NoResponse(last.sentAt + ACK_TIMEOUT)
            }
            if (refused(op, last)) return OperationState.NotExecuted(NotExecutedReason.REFUSED, last.reply)
            busy[op.id to op.attempts.lastIndex]?.let { record ->
                val query = request is BalanceQuery || request is HistoryQuery
                if (!query || op.attempts.size >= 2) return OperationState.NotExecuted(NotExecutedReason.BUSY)
                // The single retry may need a new `40`; if that one fails, or the key was rejected meanwhile, stop.
                when (val auth = auths.lastOrNull { it.createdAt >= last.sentAt }?.let(::authStatus)) {
                    is AuthStatus.Rejected -> return OperationState.NotExecuted(
                        if (auth.wrongPin) NotExecutedReason.WRONG_PIN else NotExecutedReason.AUTHENTICATION, auth.reason)
                    is AuthStatus.Failed -> return OperationState.NotExecuted(NotExecutedReason.AUTHENTICATION, auth.detail)
                    AuthStatus.Busy -> return OperationState.NotExecuted(NotExecutedReason.BUSY)
                    else -> Unit
                }
                if (stoppedByWrongPin(op, request.bank, pinSavedAt)) return OperationState.NotExecuted(NotExecutedReason.WRONG_PIN, "Clave incorrecta")
                return OperationState.Retrying(record.receivedAt + BUSY_RETRY_DELAY)
            }
            // The key turned out wrong after the operation already left: report the refusal on it.
            auths.lastOrNull { auth -> auth.attempts.any { it.sentAt <= last.sentAt } }?.let(::authStatus)?.let {
                if (it is AuthStatus.Rejected) return OperationState.Rejected(it.at, it.reason)
            }
            val since = last.repliedAt ?: last.sentAt
            return if (now < since + NO_RESPONSE_AFTER) OperationState.Sent(since) else OperationState.NoResponse(since + NO_RESPONSE_AFTER)
        }

        /** One USSD in flight per SIM; the next leaves after the previous acknowledgement. */
        fun dispatch(statuses: List<OperationStatus>, session: Session?): Dispatch? {
            if (ops.any { op -> op.attempts.lastOrNull()?.let { it.reply == null && it.failure == null && now < it.sentAt + ACK_TIMEOUT } == true }) return null
            val candidate = statuses.firstOrNull { (op, state) ->
                (op.attempts.isEmpty() && (state == OperationState.Queued || state == OperationState.Sending)) ||
                    (state is OperationState.Retrying && state.at <= now)
            }?.operation ?: return null
            val request = candidate.request
            if (request is BankRequest && request.needsSession && session?.bank != request.bank) {
                val auths = authsFor(candidate.id)
                auths.lastOrNull()?.takeIf { it.attempts.isEmpty() }?.let { return Dispatch(it, false) }
                return Dispatch(Operation("${candidate.id}#auth${auths.size + 1}", Authenticate(request.bank), now, unlocks = candidate.id), true)
            }
            return Dispatch(candidate, false)
        }

        fun wakeAt(statuses: List<OperationStatus>): Instant? = (
            ops.mapNotNull { op -> op.attempts.lastOrNull()?.takeIf { it.reply == null && it.failure == null }?.let { it.sentAt + ACK_TIMEOUT } } +
                statuses.mapNotNull { (_, state) ->
                    when (state) {
                        is OperationState.Retrying -> state.at
                        is OperationState.Sent -> state.at + NO_RESPONSE_AFTER
                        else -> null
                    }
                }
            ).filter { it > now }.minOrNull()

        /** For QR receipts, an operation whose transaction id agrees with "Id Compra" goes first. */
        private fun preferred(request: UssdRequest, message: SmsMessage): Boolean {
            if (request !is QrPay || message !is SmsMessage.PaymentCompleted) return false
            val purchase = message.purchaseId ?: return false
            val id = request.payment.transactionId
            return purchase == id || id.endsWith(purchase) || purchase.endsWith(id)
        }
    }

    internal fun matches(request: UssdRequest, message: SmsMessage): Boolean = when (message) {
        is SmsMessage.Authenticated -> request is Authenticate && request.bank == message.bank
        is SmsMessage.AuthenticationFailed -> request is Authenticate && (message.bank == null || message.bank == request.bank)
        is SmsMessage.Balance -> request is BalanceQuery && request.bank == message.bank
        is SmsMessage.History -> request is HistoryQuery && request.bank == message.bank
        is SmsMessage.TransferSent -> request is Transfer && request.bank == message.bank && request.amount == message.amount &&
            Cards.matches(message.beneficiaryMask, request.destination)
        is SmsMessage.PaymentCompleted -> request is QrPay && request.bank == message.bank && request.amount == message.amount
        is SmsMessage.ServicePaid -> request is BankRequest && request.bank == message.bank && servicePaid(request, message)
        is SmsMessage.Rejected -> request is BankRequest && (message.bank == null || message.bank == request.bank) && rejection(request, message)
        else -> false
    }

    private fun servicePaid(request: BankRequest, message: SmsMessage.ServicePaid): Boolean {
        val figures = listOfNotNull(message.nominal, message.amount)
        return when (message.service) {
            PaidService.NAUTA_RECHARGE, PaidService.NAUTA_HOME -> request is NautaPayment &&
                (request.service == NautaService.RECHARGE) == (message.service == PaidService.NAUTA_RECHARGE) &&
                (message.account == null || message.account.equals(request.account.address, true) ||
                    message.account.substringBefore('@').equals(request.account.user, true)) &&
                request.amount in figures
            PaidService.MOBILE_RECHARGE -> request is MobileRecharge && request.amount in figures &&
                (message.account == null || Phones.normalize(message.account) == Phones.normalize(request.phone))
            PaidService.ELECTRICITY -> request is ElectricityBill && (message.account == null || message.account == request.invoice)
            PaidService.PHONE -> request is PhoneBill && (message.account == null || message.account == request.invoice) &&
                (request.amount == null || request.amount in figures)
            PaidService.WATER -> request is WaterBill && (message.account == null || message.account == request.account)
            PaidService.STAMP -> false
        }
    }

    private fun rejection(request: BankRequest, message: SmsMessage.Rejected): Boolean = when (message.operation) {
        RejectedOperation.BALANCE -> request is BalanceQuery
        RejectedOperation.TRANSFER -> request is Transfer
        RejectedOperation.PAYMENT -> request is QrPay
        RejectedOperation.MOBILE_RECHARGE -> request is MobileRecharge &&
            (message.detail == null || Phones.normalize(message.detail) == Phones.normalize(request.phone))
        RejectedOperation.NAUTA_RECHARGE -> request is NautaPayment && request.service == NautaService.RECHARGE
        RejectedOperation.NAUTA_HOME -> request is NautaPayment && request.service == NautaService.HOME_RECHARGE
        RejectedOperation.NAUTA_HOME_DEBT -> request is NautaPayment && request.service == NautaService.HOME_DEBT
        RejectedOperation.ELECTRICITY -> request is ElectricityBill
        RejectedOperation.PHONE -> request is PhoneBill
        RejectedOperation.WATER -> request is WaterBill
        RejectedOperation.CARD_ASSOCIATION, RejectedOperation.OTHER -> false
        RejectedOperation.UNKNOWN -> true
    }
}
