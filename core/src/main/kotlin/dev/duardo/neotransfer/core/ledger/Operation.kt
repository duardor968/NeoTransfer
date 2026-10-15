package dev.duardo.neotransfer.core.ledger

import dev.duardo.neotransfer.core.model.InstantMillis
import dev.duardo.neotransfer.core.protocol.UssdRequest
import dev.duardo.neotransfer.core.sms.SmsMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant

/**
 * A request the user made (or an automatic authentication), with the facts of each send.
 * This is what the integration persists; every state is derived by [Ledger.evaluate].
 */
@Serializable
data class Operation(
    val id: String,
    val request: UssdRequest,
    @Serializable(InstantMillis::class) val createdAt: Instant,
    val attempts: List<Attempt> = emptyList(),
    /** For an automatic `40`: the operation it unlocks. */
    val unlocks: String? = null,
) {
    /** Record the hand-off to the modem, right before calling it. */
    fun sending(at: Instant): Operation = copy(attempts = attempts + Attempt(at))

    /** Record the USSD reply (acknowledgement) of the last send. */
    fun replied(text: String, at: Instant): Operation = updateLast { it.copy(repliedAt = at, reply = text) }

    /** Record that the modem refused the last send (no network, radio busy…). */
    fun failed(reason: String, at: Instant): Operation = updateLast { it.copy(repliedAt = at, failure = reason) }

    private fun updateLast(change: (Attempt) -> Attempt): Operation {
        check(attempts.isNotEmpty() && attempts.last().let { it.reply == null && it.failure == null }) { "No hay envío pendiente de respuesta" }
        return copy(attempts = attempts.dropLast(1) + change(attempts.last()))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Stable text form for storage (one column per operation). Contains no secrets. */
        fun encode(operation: Operation): String = json.encodeToString(serializer(), operation)
        fun decode(text: String): Operation = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class Attempt(
    @Serializable(InstantMillis::class) val sentAt: Instant,
    @Serializable(InstantMillis::class) val repliedAt: Instant? = null,
    val reply: String? = null,
    val failure: String? = null,
)

/** What the user sees for an operation. Pending states are [Queued], [Sending], [Sent], [Retrying] and [NoResponse]. */
sealed interface OperationState {
    /** Waiting for its turn on the SIM. */
    data object Queued : OperationState

    /** Being sent, its authentication included. */
    data object Sending : OperationState

    /** Acknowledged ("…siendo procesada…"); the SMS will arrive when it arrives. */
    data class Sent(val at: Instant) : OperationState

    /** The bank was busy; this query goes again automatically at [at]. */
    data class Retrying(val at: Instant) : OperationState

    /** The bank's SMS confirmed it. [result] is the receipt, balance or statement. */
    data class Confirmed(val at: Instant, val result: SmsMessage) : OperationState

    data class Rejected(val at: Instant, val reason: String) : OperationState

    /** Fifteen minutes without SMS. Neutral: a late result still closes it. */
    data class NoResponse(val since: Instant) : OperationState

    /** It never reached the bank (busy, refused, could not be sent or authenticated). Money can be retried by the user. */
    data class NotExecuted(val reason: NotExecutedReason, val detail: String? = null) : OperationState

    /** USSD-only operations (Cubacel, disconnect): the reply text is the answer. */
    data class Answered(val at: Instant, val text: String) : OperationState
}

enum class NotExecutedReason {
    /** "Usted tiene una solicitud en proceso…". */
    BUSY,
    /** The modem could not send it. */
    SEND_FAILED,
    /** The network answered without "siendo procesada". */
    REFUSED,
    /** The automatic authentication could not be sent or was refused. */
    AUTHENTICATION,
    /** The saved key was rejected; nothing is sent for that bank until the key is saved again. */
    WRONG_PIN,
}

val OperationState.isPending: Boolean
    get() = this is OperationState.Queued || this is OperationState.Sending || this is OperationState.Sent ||
        this is OperationState.Retrying || this is OperationState.NoResponse
