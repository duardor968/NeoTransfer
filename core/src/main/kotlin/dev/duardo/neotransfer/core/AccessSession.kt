package dev.duardo.neotransfer.core

/** Local presentation access only; never constitutes banking or payment authorization. */
class AccessSession(
    private val elapsedMillis: () -> Long,
    private val backgroundGraceMillis: Long = 60_000L,
) {
    init { require(backgroundGraceMillis > 0) }

    private var foreground = false
    private var backgroundAt: Long? = null
    private var automaticPromptAttempted = false

    /** Returns whether a previously unlocked presentation must be locked before rendering. */
    fun enterForeground(): Boolean {
        val expired = backgroundAt?.let(::hasExpired) == true
        if (!foreground) automaticPromptAttempted = false
        foreground = true
        backgroundAt = null
        return expired
    }

    fun leaveForeground() {
        if (!foreground) return
        foreground = false
        backgroundAt = elapsedMillis()
    }

    fun shouldLockInBackground(): Boolean = !foreground && backgroundAt?.let(::hasExpired) == true

    /** Claim before opening the native prompt, so cancellation cannot start a prompt loop. */
    fun claimAutomaticPrompt(unlocked: Boolean): Boolean {
        if (!foreground || unlocked || automaticPromptAttempted) return false
        automaticPromptAttempted = true
        return true
    }

    fun markManualPrompt() { automaticPromptAttempted = true }

    private fun hasExpired(at: Long): Boolean {
        val elapsed = elapsedMillis() - at
        return elapsed < 0 || elapsed >= backgroundGraceMillis
    }
}
