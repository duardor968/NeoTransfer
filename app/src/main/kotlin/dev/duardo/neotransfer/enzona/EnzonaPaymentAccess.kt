package dev.duardo.neotransfer.enzona

import java.util.concurrent.atomic.AtomicLong

/** Leaving the reviewed context permanently invalidates its approval, even after returning. */
internal class EnzonaPaymentAccess {
    class Ticket internal constructor(internal val revision: Long, internal val owner: String)
    private val revision = AtomicLong()
    fun capture(owner: String) = Ticket(revision.get(), owner)
    fun invalidate() { revision.incrementAndGet() }
    fun isCurrent(ticket: Ticket, owner: String?, unlocked: Boolean, resumed: Boolean): Boolean =
        ticket.revision == revision.get() && ticket.owner == owner && unlocked && resumed
}
