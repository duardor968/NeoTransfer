package dev.duardo.neotransfer.platform

/** Owns one modem request until its callback or local deadline, whichever arrives first. */
internal class UssdRequestLease(
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
) {
    class Ticket internal constructor(val result: (UssdResult) -> Unit) {
        internal lateinit var timeout: Runnable
    }

    private var active: Ticket? = null

    fun isIdle(): Boolean = active == null

    fun begin(result: (UssdResult) -> Unit): Ticket? {
        if (active != null) return null
        val ticket = Ticket(result)
        ticket.timeout = Runnable { complete(ticket, UssdResult.TimedOut) }
        active = ticket
        schedule(ticket.timeout, 30_000)
        return ticket
    }

    fun complete(ticket: Ticket, result: UssdResult) {
        if (active !== ticket) return
        active = null
        cancel(ticket.timeout)
        ticket.result(result)
    }

    /** Drops only the pending send with this callback; a late modem callback remains tied to its old ticket. */
    fun abandon(result: (UssdResult) -> Unit): Boolean {
        val ticket = active?.takeIf { it.result === result } ?: return false
        active = null
        cancel(ticket.timeout)
        return true
    }
}
