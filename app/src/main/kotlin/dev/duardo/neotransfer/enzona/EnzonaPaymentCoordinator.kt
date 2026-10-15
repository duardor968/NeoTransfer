package dev.duardo.neotransfer.enzona

import dev.duardo.neotransfer.enzona.data.EnzonaDestination
import dev.duardo.neotransfer.enzona.data.EnzonaIntent
import dev.duardo.neotransfer.enzona.data.EnzonaIntentState
import dev.duardo.neotransfer.enzona.data.EnzonaIntentType
import dev.duardo.neotransfer.enzona.data.EnzonaJournalEntry
import dev.duardo.neotransfer.enzona.data.RoomEnzonaRepository
import java.net.SocketTimeoutException
import java.io.InterruptedIOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal interface EnzonaPaymentJournal {
    suspend fun prepareIntent(intent: EnzonaIntent): EnzonaJournalEntry
    suspend fun intent(owner: String, id: String): EnzonaJournalEntry?
    suspend fun tryBeginSubmission(owner: String, id: String): Boolean
    suspend fun markTimedOut(owner: String, id: String): Boolean
    suspend fun recordResult(owner: String, id: String, state: EnzonaIntentState, remoteId: String?): Boolean
}

private class RoomPaymentJournal(private val repository: RoomEnzonaRepository) : EnzonaPaymentJournal {
    override suspend fun prepareIntent(intent: EnzonaIntent) = withContext(Dispatchers.IO) { repository.prepareIntent(intent) }
    override suspend fun intent(owner: String, id: String) = withContext(Dispatchers.IO) { repository.intent(owner, id) }
    override suspend fun tryBeginSubmission(owner: String, id: String) = withContext(Dispatchers.IO) { repository.tryBeginSubmission(owner, id) }
    override suspend fun markTimedOut(owner: String, id: String) = withContext(Dispatchers.IO) { repository.markTimedOut(owner, id) }
    override suspend fun recordResult(owner: String, id: String, state: EnzonaIntentState, remoteId: String?) =
        withContext(Dispatchers.IO) { repository.recordResult(owner, id, state, remoteId) }
}

internal class EnzonaPaymentUpdate(
    val ownerSubject: String,
    val intentId: String,
    val state: EnzonaIntentState,
    /** A successful local CAS, not a claim that the server received the request. */
    val submissionStarted: Boolean,
    val notice: String? = null,
    val evidence: EnzonaPaymentEvidence? = null,
    val late: Boolean = false,
) {
    override fun toString() = "EnzonaPaymentUpdate(state=$state, late=$late, privateFields=redacted)"
}

internal class EnzonaPaymentPreparationException : IllegalStateException("No se pudo preparar el pago de ENZONA")
internal class EnzonaPaymentAuthorizationExpiredException : CancellationException("La autorización del pago ha caducado")

/**
 * Root reviews the returned intent and performs local biometrics before submit. Reuse that SAME
 * intent ID on duplicate callbacks; preparing again creates a different user-approved operation.
 * Its app-owned scope outlives the caller's wait, retaining late evidence from the original POST.
 */
internal class EnzonaPaymentCoordinator(
    private val usableTokens: suspend () -> EnzonaTokens,
    private val journal: EnzonaPaymentJournal,
    private val network: EnzonaPaymentNetwork,
    private val encoder: EnzonaPaymentRequests,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val responseWaitMillis: Long = 30_000,
    private val now: () -> Long = { Instant.now().toEpochMilli() },
) : AutoCloseable {
    constructor(session: EnzonaSessionCoordinator, repository: RoomEnzonaRepository,
        api: EnzonaPaymentApi, encoder: EnzonaPaymentRequests) :
        this(session::usableTokens, RoomPaymentJournal(repository), api, encoder)

    init { require(responseWaitMillis > 0) }
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableUpdates = MutableSharedFlow<EnzonaPaymentUpdate>(extraBufferCapacity = 16)
    /** Room is authoritative for persisted state. Unresolved late evidence is transient only. */
    val updates: SharedFlow<EnzonaPaymentUpdate> = mutableUpdates.asSharedFlow()

    suspend fun prepare(ownerSubject: String, draft: EnzonaPaymentDraft): EnzonaIntent {
        check(scope.isActive) { "El coordinador de pagos está cerrado" }
        require(ownerSubject.isNotBlank() && ownerSubject.none(Char::isISOControl)) { "Cuenta de ENZONA no válida" }
        val (type, destination) = when (draft) {
            is EnzonaCardTransferDraft -> {
                val pan = draft.recipientPan.replace(" ", "")
                require(pan.length == 16 && pan.all { it in '0'..'9' }) { "Tarjeta destinataria no válida" }
                EnzonaIntentType.CARD_TRANSFER to EnzonaDestination(pan)
            }
            is EnzonaAccountTransferDraft -> EnzonaIntentType.ACCOUNT_TRANSFER to
                EnzonaDestination(draft.fundingRecipientUuid, draft.fundingRecipientUsername)
            is EnzonaQrMerchantPaymentDraft -> EnzonaIntentType.QR_MERCHANT_PAYMENT to EnzonaDestination(draft.vendorIdentityCode)
            is EnzonaInvoicePaymentDraft -> EnzonaIntentType.INVOICE_PAYMENT to EnzonaDestination(draft.clientId)
        }
        val invoice = draft as? EnzonaInvoicePaymentDraft
        val intent = EnzonaIntent(UUID.randomUUID().toString(), ownerSubject, draft.fundingSourceUuid, type,
            destination, draft.amount.toPlainString(), draft.currency, draft.description,
            when (draft) { is EnzonaCardTransferDraft -> draft.phone; is EnzonaQrMerchantPaymentDraft -> draft.phone; else -> null }, now(),
            invoiceService = invoice?.service?.name, invoiceOwner = invoice?.owner,
            invoiceFolio = invoice?.folio, invoiceRoute = invoice?.route, invoicePeriod = invoice?.period)
        try {
            return journal.prepareIntent(intent).also { check(it.intent == intent) }.intent
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw EnzonaPaymentPreparationException() }
    }

    /** Password ownership transfers here, including failures before tokens/encryption/CAS. */
    suspend fun submit(intent: EnzonaIntent, password: CharArray, canDispatch: () -> Boolean = { true }): EnzonaPaymentUpdate {
        val request: EnzonaPaymentRequest
        val tokens: EnzonaTokens
        try {
            currentCoroutineContext().ensureActive()
            check(scope.isActive)
            val stored = requireNotNull(journal.intent(intent.ownerSubject, intent.id))
            require(stored.intent == intent)
            if (stored.state != EnzonaIntentState.PREPARED || stored.restored) return update(stored, false,
                "Este intento no puede volver a enviarse")
            tokens = usableTokens()
            require(tokens.accountSubject() == intent.ownerSubject)
            request = withContext(dispatcher) { encoder.encode(intent.draft(), password) }
            currentCoroutineContext().ensureActive()
            check(scope.isActive)
            if (!canDispatch()) throw EnzonaPaymentAuthorizationExpiredException()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw EnzonaPaymentPreparationException() }
        finally { password.fill('\u0000') }

        val decision = CompletableDeferred<Unit>()
        val won = AtomicBoolean(false)
        val late = AtomicBoolean(false)
        val flight = scope.async {
            var observed: EnzonaPaymentEvidence? = null
            try {
                // Once the database grants this dispatch it must never become eligible again.
                val allowed = withContext(NonCancellable) {
                    if (!scope.isActive || !canDispatch()) throw EnzonaPaymentAuthorizationExpiredException()
                    journal.tryBeginSubmission(intent.ownerSubject, intent.id)
                }
                won.set(allowed)
                decision.complete(Unit)
                if (!allowed) return@async update(requireNotNull(journal.intent(intent.ownerSubject, intent.id)), false,
                    "Este intento no puede volver a enviarse")
                currentCoroutineContext().ensureActive()
                // Room may have waited for a lock while authorization, owner or foreground changed.
                if (!canDispatch()) return@async withContext(NonCancellable) {
                    uncertain(intent, "No se envió el pago: la autorización ha caducado", late = late.get())
                        .also(mutableUpdates::tryEmit)
                }
                val evidence = network.submit(tokens, request)
                observed = evidence
                withContext(NonCancellable) {
                    if (evidence.state == EnzonaIntentState.CONFIRMED || evidence.state == EnzonaIntentState.REJECTED) {
                        val recorded = journal.recordResult(intent.ownerSubject, intent.id, evidence.state, evidence.transactionUuid)
                        val stored = requireNotNull(journal.intent(intent.ownerSubject, intent.id))
                        update(stored, true, if (recorded) null else "La respuesta requiere revisión", evidence, late.get())
                    } else {
                        uncertain(intent, UNKNOWN_NOTICE, evidence, late.get())
                    }.also(mutableUpdates::tryEmit)
                }
            } catch (cancelled: CancellationException) {
                if (won.get()) withContext(NonCancellable) {
                    uncertain(intent, UNKNOWN_NOTICE, observed, late.get()).also(mutableUpdates::tryEmit)
                }
                throw cancelled
            } catch (failure: Exception) {
                if (!won.get()) throw EnzonaPaymentPreparationException()
                withContext(NonCancellable) {
                    uncertain(intent, if (failure is SocketTimeoutException || failure is InterruptedIOException)
                        TIMEOUT_NOTICE else UNKNOWN_NOTICE, observed, late.get()).also(mutableUpdates::tryEmit)
                }
            } finally { decision.complete(Unit) }
        }
        flight.invokeOnCompletion { decision.complete(Unit) }
        try {
            decision.await()
            if (!won.get()) return flight.await()
            val immediate = withTimeoutOrNull(responseWaitMillis) { flight.await() }
            if (immediate != null) return immediate
            late.set(true)
            return withContext(NonCancellable) {
                uncertain(intent, TIMEOUT_NOTICE).also(mutableUpdates::tryEmit)
            }
        } catch (cancelled: CancellationException) {
            if (won.get()) {
                late.set(true)
                withContext(NonCancellable) { uncertain(intent, UNKNOWN_NOTICE).also(mutableUpdates::tryEmit) }
            }
            // Do not cancel the independent original HTTP flight when only its UI waiter leaves.
            throw cancelled
        }
    }

    private suspend fun uncertain(intent: EnzonaIntent, notice: String, evidence: EnzonaPaymentEvidence? = null,
        late: Boolean = false): EnzonaPaymentUpdate {
        return try {
            journal.markTimedOut(intent.ownerSubject, intent.id)
            val entry = requireNotNull(journal.intent(intent.ownerSubject, intent.id))
            update(entry, true, if (entry.state == EnzonaIntentState.UNKNOWN) notice else null, evidence, late)
        } catch (_: Exception) {
            // A database failure does not prove that the server rejected a dispatched operation.
            EnzonaPaymentUpdate(intent.ownerSubject, intent.id, EnzonaIntentState.UNKNOWN, true,
                "No se pudo guardar el resultado. Consulta la actividad de ENZONA", evidence, late)
        }
    }

    private fun update(entry: EnzonaJournalEntry, started: Boolean, notice: String? = null,
        evidence: EnzonaPaymentEvidence? = null, late: Boolean = false) =
        EnzonaPaymentUpdate(entry.intent.ownerSubject, entry.intent.id, entry.state, started, notice, evidence, late)

    private fun EnzonaIntent.draft(): EnzonaPaymentDraft = when (type) {
        EnzonaIntentType.CARD_TRANSFER -> EnzonaCardTransferDraft(cardUuid, destination.value, amount.toBigDecimal(), currency, description, phone.orEmpty())
        EnzonaIntentType.ACCOUNT_TRANSFER -> EnzonaAccountTransferDraft(cardUuid, destination.value, requireNotNull(destination.username), amount.toBigDecimal(), currency, description)
        EnzonaIntentType.QR_MERCHANT_PAYMENT -> EnzonaQrMerchantPaymentDraft(cardUuid, destination.value, amount.toBigDecimal(), currency, description, phone.orEmpty())
        EnzonaIntentType.INVOICE_PAYMENT -> EnzonaInvoicePaymentDraft(
            EnzonaInvoiceService.valueOf(requireNotNull(invoiceService)), destination.value, requireNotNull(invoiceOwner),
            cardUuid, amount.toBigDecimal(), currency, description, invoiceFolio, invoiceRoute, invoicePeriod)
    }

    override fun close() { scope.cancel() }

    private companion object {
        const val TIMEOUT_NOTICE = "Tiempo de espera agotado"
        const val UNKNOWN_NOTICE = "No se pudo confirmar el pago"
    }
}
