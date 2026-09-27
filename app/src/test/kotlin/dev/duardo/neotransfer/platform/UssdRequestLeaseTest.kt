package dev.duardo.neotransfer.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UssdRequestLeaseTest {
    private val scheduled = mutableSetOf<Runnable>()
    private val delays = mutableListOf<Long>()
    private val lease = UssdRequestLease(
        schedule = { task, delay -> scheduled += task; delays += delay },
        cancel = { scheduled -= it },
    )

    @Test fun timeoutReleasesLeaseWithoutLettingOldCallbackCompleteNewRequest() {
        val firstResults = mutableListOf<UssdResult>()
        val first = requireNotNull(lease.begin { firstResults += it })
        assertFalse(lease.isIdle())
        assertEquals(listOf(30_000L), delays)
        assertEquals(null, lease.begin { firstResults += it })

        val oldTimeout = scheduled.single()
        oldTimeout.run()
        assertEquals(listOf<UssdResult>(UssdResult.TimedOut), firstResults)
        assertTrue(lease.isIdle())

        val secondResults = mutableListOf<UssdResult>()
        val second = requireNotNull(lease.begin { secondResults += it })
        lease.complete(first, UssdResult.Response("late"))
        lease.complete(first, UssdResult.NetworkFailure(1))
        oldTimeout.run()
        assertFalse(lease.isIdle())
        assertEquals(listOf<UssdResult>(UssdResult.TimedOut), firstResults)
        assertTrue(secondResults.isEmpty())

        lease.complete(second, UssdResult.Response("ok"))
        assertEquals(listOf<UssdResult>(UssdResult.Response("ok")), secondResults)
        assertTrue(lease.isIdle())
        assertTrue(scheduled.isEmpty())
    }

    @Test fun normalResponseAndFailureEachCompleteOnlyOnce() {
        val responses = mutableListOf<UssdResult>()
        val response = requireNotNull(lease.begin { responses += it })
        val responseTimeout = scheduled.single()
        lease.complete(response, UssdResult.Response("ok"))
        responseTimeout.run()
        lease.complete(response, UssdResult.NetworkFailure(2))
        assertEquals(listOf<UssdResult>(UssdResult.Response("ok")), responses)

        val failures = mutableListOf<UssdResult>()
        val failure = requireNotNull(lease.begin { failures += it })
        lease.complete(failure, UssdResult.NetworkFailure(3))
        assertEquals(listOf<UssdResult>(UssdResult.NetworkFailure(3)), failures)
        assertTrue(lease.isIdle())
        assertTrue(scheduled.isEmpty())
    }

    @Test fun executionDeadlineAbandonsOnlyItsPendingRequest() {
        val authentication = mutableListOf<UssdResult>()
        val auth = requireNotNull(lease.begin { authentication += it }) // Execution starts at second 0.
        lease.complete(auth, UssdResult.Response("authenticated")) // Authentication completes at second 29.
        assertEquals(listOf<UssdResult>(UssdResult.Response("authenticated")), authentication)
        assertTrue(lease.isIdle())

        val firstResults = mutableListOf<UssdResult>()
        val firstCallback: (UssdResult) -> Unit = { firstResults += it }
        val first = requireNotNull(lease.begin(firstCallback)) // Remote selection starts at second 29.
        val firstTimeout = scheduled.single()
        assertEquals(listOf(30_000L, 30_000L), delays)
        assertFalse(lease.isIdle())

        assertTrue(lease.abandon(firstCallback)) // Execution expires at second 30.
        assertTrue(lease.isIdle())
        assertTrue(scheduled.isEmpty())
        assertTrue(firstResults.isEmpty())

        val nextResults = mutableListOf<UssdResult>()
        val nextCallback: (UssdResult) -> Unit = { nextResults += it }
        val next = requireNotNull(lease.begin(nextCallback))
        assertFalse(lease.abandon(firstCallback))
        lease.complete(first, UssdResult.Response("late"))
        lease.complete(first, UssdResult.NetworkFailure(1))
        firstTimeout.run()
        assertFalse(lease.isIdle())
        assertEquals(1, scheduled.size)
        assertTrue(firstResults.isEmpty())
        assertTrue(nextResults.isEmpty())

        lease.complete(next, UssdResult.Response("new"))
        assertEquals(listOf<UssdResult>(UssdResult.Response("new")), nextResults)
        assertTrue(lease.isIdle())
        assertTrue(scheduled.isEmpty())
    }

    @Test fun abandonedOrCompletedRequestCannotBeAbandonedTwice() {
        val results = mutableListOf<UssdResult>()
        val callback: (UssdResult) -> Unit = { results += it }
        val ticket = requireNotNull(lease.begin(callback))
        lease.complete(ticket, UssdResult.NetworkFailure(7))
        assertFalse(lease.abandon(callback))
        assertEquals(listOf<UssdResult>(UssdResult.NetworkFailure(7)), results)

        val nextCallback: (UssdResult) -> Unit = { results += it }
        requireNotNull(lease.begin(nextCallback))
        assertFalse(lease.abandon(callback))
        assertTrue(lease.abandon(nextCallback))
        assertFalse(lease.abandon(nextCallback))
        assertEquals(listOf<UssdResult>(UssdResult.NetworkFailure(7)), results)
        assertTrue(lease.isIdle())
        assertTrue(scheduled.isEmpty())
    }
}
