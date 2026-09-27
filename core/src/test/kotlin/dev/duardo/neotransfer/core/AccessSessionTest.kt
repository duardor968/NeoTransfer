package dev.duardo.neotransfer.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessSessionTest {
    @Test fun cancellationDoesNotReopenPromptWithinSameForeground() {
        val policy = AccessSession({ 100L })
        assertFalse(policy.claimAutomaticPrompt(false))
        policy.enterForeground()
        assertTrue(policy.claimAutomaticPrompt(false))
        assertFalse(policy.claimAutomaticPrompt(false))
        policy.enterForeground() // Duplicate lifecycle signal is not a fresh app entry.
        assertFalse(policy.claimAutomaticPrompt(false))
        policy.leaveForeground()
        policy.enterForeground()
        assertTrue(policy.claimAutomaticPrompt(false))
    }

    @Test fun briefBackgroundKeepsLocalAccessButNeverClaimsAnotherPrompt() {
        var time = 1_000L
        val policy = AccessSession({ time })
        policy.enterForeground()
        policy.leaveForeground()
        time += 59_999
        assertFalse(policy.shouldLockInBackground())
        assertFalse(policy.enterForeground())
        assertFalse(policy.claimAutomaticPrompt(true))
    }

    @Test fun exactMinuteExpiresEvenWhenTheTimerWasNotDelivered() {
        var time = 500L
        val policy = AccessSession({ time })
        policy.enterForeground()
        policy.leaveForeground()
        time += 60_000
        assertTrue(policy.shouldLockInBackground())
        assertTrue(policy.enterForeground())
        assertTrue(policy.claimAutomaticPrompt(false))
        assertFalse(policy.shouldLockInBackground())
    }

    @Test fun duplicateStopDoesNotExtendTheGracePeriod() {
        var time = 0L
        val policy = AccessSession({ time })
        policy.enterForeground()
        policy.leaveForeground()
        time = 45_000
        policy.leaveForeground()
        time = 60_000
        assertTrue(policy.enterForeground())
    }

    @Test fun clockDiscontinuityRequiresLockAndManualPromptConsumesAutomaticAttempt() {
        var time = 100_000L
        val policy = AccessSession({ time })
        policy.enterForeground()
        policy.leaveForeground()
        time = 0
        assertTrue(policy.enterForeground())
        policy.markManualPrompt()
        assertFalse(policy.claimAutomaticPrompt(false))
    }
}
