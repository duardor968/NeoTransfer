package dev.duardo.neotransfer.enzona

import dev.duardo.neotransfer.core.enzona.EnzonaAuthContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EnzonaSessionCoordinatorTest {
    private class MemoryTokens(var value: EnzonaTokens? = null) : TokenPersistence {
        var loads = 0
        var saves = 0
        var clears = 0
        override fun load(): EnzonaTokens? { loads++; return value }
        override fun save(tokens: EnzonaTokens) { saves++; value = tokens }
        override fun clear() { clears++; value = null }
    }

    private class FakeNetwork : EnzonaSessionNetwork {
        val calls = mutableListOf<String>()
        var exchange: suspend () -> EnzonaTokens = { token("new") }
        var refresh: suspend (EnzonaTokens) -> EnzonaTokens = { token("renewed", it.accountSubject()) }
        var record: suspend () -> Unit = {}
        var account: suspend (EnzonaTokens) -> JSONObject = { JSONObject().put("username", it.accountSubject()) }
        override suspend fun exchange(exchange: EnzonaAuthContract.CodeExchange): EnzonaTokens {
            calls += "exchange"; return this.exchange()
        }
        override suspend fun refresh(tokens: EnzonaTokens): EnzonaTokens {
            calls += "refresh"; return refresh.invoke(tokens)
        }
        override suspend fun recordDevice(tokens: EnzonaTokens, fields: Map<String, String>) {
            calls += "device"; record()
        }
        override suspend fun account(tokens: EnzonaTokens): JSONObject {
            calls += "account"; return account.invoke(tokens)
        }
    }

    private fun coordinator(store: MemoryTokens, network: FakeNetwork, close: () -> Unit = {}) =
        EnzonaSessionCoordinator(store, network, { mapOf("accessType" to "MOBILE") },
            Dispatchers.Unconfined, { 1000 }, close, ioDispatcher = Dispatchers.Unconfined)

    @Test fun expiredCacheLoadsWithoutAnyServerRequests() = runBlocking {
        val cached = token("old", expiry = 900)
        val store = MemoryTokens(cached)
        val network = FakeNetwork()
        coordinator(store, network).use { manager ->
            manager.loadSavedSession()
            assertTrue(manager.uiState.value.connected)
            assertEquals(cached.accountSubject(), manager.uiState.value.ownerSubject)
            assertNull(manager.uiState.value.displayName)
            assertNull(manager.uiState.value.pendingCardRefreshOwner)
            assertFalse(manager.consumeCardRefresh(cached.accountSubject()))
            assertEquals(1, store.loads)
            assertTrue(network.calls.isEmpty())
            assertEquals(0, store.saves)
        }
    }

    @Test fun logoutDuringExchangeRejectsEvenAnUncooperativeLateResult() = runBlocking {
        withTimeout(5000) {
            val store = MemoryTokens(token("old"))
            val network = FakeNetwork()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            network.exchange = { withContext(NonCancellable) {
                entered.complete(Unit); release.await(); token("new", "other@carbon.super")
            } }
            coordinator(store, network).use { manager ->
                manager.loadSavedSession()
                manager.beginLogin()
                assertTrue(manager.acceptCallback(callback(manager.uiState.value.authorizationUrl!!)))
                entered.await()
                assertTrue(manager.disconnectLocal())
                release.complete(Unit)
                yield()
                assertNull(store.value)
                assertEquals(0, store.saves)
                assertFalse(manager.uiState.value.connected)
                assertEquals(listOf("exchange"), network.calls)
            }
        }
    }

    @Test fun twoReadersShareOneRefreshAndItsRotatedTokens() = runBlocking {
        withTimeout(5000) {
            val store = MemoryTokens(token("expired", expiry = 900))
            val network = FakeNetwork()
            val release = CompletableDeferred<Unit>()
            val renewed = token("renewed")
            network.refresh = { release.await(); renewed }
            coordinator(store, network).use { manager ->
                manager.loadSavedSession()
                val first = async(start = CoroutineStart.UNDISPATCHED) { manager.usableTokens() }
                val second = async(start = CoroutineStart.UNDISPATCHED) { manager.usableTokens() }
                assertEquals(listOf("refresh"), network.calls)
                assertTrue(manager.uiState.value.busy)
                release.complete(Unit)
                assertSame(renewed, first.await())
                assertSame(renewed, second.await())
                assertSame(renewed, store.value)
                assertEquals(1, store.saves)
                assertFalse(manager.uiState.value.busy)
                assertSame(renewed, manager.usableTokens())
                assertEquals(listOf("refresh"), network.calls)
                assertNull(manager.uiState.value.pendingCardRefreshOwner)
                assertFalse(manager.consumeCardRefresh(renewed.accountSubject()))
            }
        }
    }

    @Test fun cancellingOneReaderDoesNotCancelRefreshForAnother() = runBlocking {
        withTimeout(5000) {
            val store = MemoryTokens(token("expired", expiry = 900))
            val network = FakeNetwork()
            val release = CompletableDeferred<Unit>()
            network.refresh = { release.await(); token("renewed") }
            coordinator(store, network).use { manager ->
                manager.loadSavedSession()
                val first = async(start = CoroutineStart.UNDISPATCHED) { manager.usableTokens() }
                val second = async(start = CoroutineStart.UNDISPATCHED) { manager.usableTokens() }
                first.cancel()
                release.complete(Unit)
                assertEquals("renewed", second.await().accessToken)
                assertEquals(1, store.saves)
                assertEquals(listOf("refresh"), network.calls)
            }
        }
    }

    @Test fun logoutDuringRefreshPreventsResaving() = runBlocking {
        withTimeout(5000) {
            val store = MemoryTokens(token("expired", expiry = 900))
            val network = FakeNetwork()
            val release = CompletableDeferred<Unit>()
            network.refresh = { withContext(NonCancellable) { release.await(); token("renewed") } }
            coordinator(store, network).use { manager ->
                manager.loadSavedSession()
                val reader = async(start = CoroutineStart.UNDISPATCHED) { manager.usableTokens() }
                manager.disconnectLocal()
                release.complete(Unit)
                assertFailsWith<CancellationException> { reader.await() }
                assertNull(store.value)
                assertEquals(0, store.saves)
                assertNull(manager.uiState.value.ownerSubject)
                assertFalse(manager.uiState.value.busy)
            }
        }
    }

    @Test fun timeoutKeepsSavedSessionAndDoesNotRetry() = runBlocking {
        val old = token("expired", expiry = 900)
        val store = MemoryTokens(old)
        val transportFailure = SocketTimeoutException("PRIVATE_BODY")
        val network = FakeNetwork().apply { refresh = { throw transportFailure } }
        val safeMessage = "ENZONA no pudo completar la solicitud. Inténtalo de nuevo"
        fun assertSanitizedChain(error: Throwable) {
            val visited = mutableSetOf<Throwable>()
            var cause: Throwable? = error
            while (cause != null) {
                assertTrue(visited.add(cause), "Cyclic exception cause chain")
                assertTrue(cause is EnzonaSessionUnavailableException)
                assertEquals(safeMessage, cause.message)
                assertFalse(cause === transportFailure)
                assertFalse(cause.toString().contains("PRIVATE_BODY"))
                assertTrue(cause.suppressed.isEmpty())
                cause = cause.cause
            }
        }
        coordinator(store, network).use { manager ->
            manager.loadSavedSession()
            val failure = assertFailsWith<EnzonaSessionUnavailableException> { manager.usableTokens() }
            // Coroutine stack recovery can copy this sanitized exception and chain the original.
            assertSanitizedChain(failure)
            val leakedOriginal = EnzonaSessionUnavailableException(safeMessage).apply { initCause(transportFailure) }
            assertFailsWith<AssertionError> { assertSanitizedChain(leakedOriginal) }
            assertSame(old, store.value)
            assertTrue(manager.uiState.value.connected)
            assertEquals(0, store.clears)
            assertEquals(0, store.saves)
            assertEquals(listOf("refresh"), network.calls)
        }
    }

    @Test fun invalidGrantRequiresLoginButInvalidClientKeepsTokensForConfigDiagnosis() = runBlocking {
        for (failure in listOf(EnzonaOAuthFailure.INVALID_GRANT, EnzonaOAuthFailure.INVALID_CLIENT)) {
            val old = token("expired", expiry = 900)
            val store = MemoryTokens(old)
            val network = FakeNetwork().apply { refresh = { throw EnzonaApiException(400, failure) } }
            coordinator(store, network).use { manager ->
                manager.loadSavedSession()
                val error = assertFailsWith<EnzonaSessionUnavailableException> { manager.usableTokens() }
                if (failure == EnzonaOAuthFailure.INVALID_GRANT) {
                    assertNull(store.value)
                    assertFalse(manager.uiState.value.connected)
                    assertEquals("Vuelve a conectar ENZONA", error.message)
                    assertEquals(1, store.clears)
                } else {
                    assertSame(old, store.value)
                    assertTrue(manager.uiState.value.connected)
                    assertTrue(error.message!!.contains("configuración"))
                    assertEquals(0, store.clears)
                }
                assertEquals(listOf("refresh"), network.calls)
            }
        }
    }

    @Test fun cancelRejectsOldCallbackWithoutConsumingNewAttempt() = runBlocking {
        val store = MemoryTokens()
        val network = FakeNetwork()
        coordinator(store, network).use { manager ->
            manager.beginLogin()
            val oldCallback = callback(manager.uiState.value.authorizationUrl!!)
            manager.cancelLogin()
            assertFalse(manager.acceptCallback(oldCallback))
            assertFalse(manager.uiState.value.connected)
            manager.beginLogin()
            val current = callback(manager.uiState.value.authorizationUrl!!)
            assertFalse(manager.acceptCallback(oldCallback))
            assertTrue(network.calls.isEmpty())
            assertTrue(manager.acceptCallback(current))
            assertFalse(manager.acceptCallback(current))
            assertTrue(manager.uiState.value.connected)
            assertEquals(listOf("exchange", "device", "account"), network.calls)
            assertEquals(1, store.saves)
        }
    }

    @Test fun cancellationDuringBootstrapPreservesPreviousOwner() = runBlocking {
        withTimeout(5000) {
            val old = token("old")
            val store = MemoryTokens(old)
            val network = FakeNetwork()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            network.exchange = { token("new", "other@carbon.super") }
            network.account = { withContext(NonCancellable) {
                entered.complete(Unit); release.await(); JSONObject().put("username", it.accountSubject())
            } }
            coordinator(store, network).use { manager ->
                manager.loadSavedSession()
                manager.beginLogin()
                manager.acceptCallback(callback(manager.uiState.value.authorizationUrl!!))
                entered.await()
                manager.cancelLogin()
                release.complete(Unit)
                yield()
                assertSame(old, store.value)
                assertEquals(old.accountSubject(), manager.uiState.value.ownerSubject)
                assertEquals(0, store.saves)
                assertNull(manager.uiState.value.authorizationUrl)
                assertFalse(manager.uiState.value.busy)
            }
        }
    }

    @Test fun failedNewLoginNeverAttributesOldTokensToNewOwner() = runBlocking {
        val old = token("old")
        val store = MemoryTokens(old)
        val network = FakeNetwork().apply {
            exchange = { token("new", "other@carbon.super") }
            account = { throw IOException("PRIVATE_ACCOUNT_BODY") }
        }
        coordinator(store, network).use { manager ->
            manager.loadSavedSession()
            manager.beginLogin()
            manager.acceptCallback(callback(manager.uiState.value.authorizationUrl!!))
            assertSame(old, store.value)
            assertSame(old, manager.usableTokens())
            assertEquals(old.accountSubject(), manager.uiState.value.ownerSubject)
            assertFalse(manager.uiState.value.notice!!.contains("PRIVATE_ACCOUNT_BODY"))
            assertEquals(0, store.saves)
        }
    }

    @Test fun registrationFailureStillGetsAccountAndSavesOnlyAfterValidation() = runBlocking {
        val store = MemoryTokens()
        val network = FakeNetwork().apply {
            record = { throw IOException("PRIVATE_METADATA") }
            account = {
                assertNull(store.value)
                JSONObject().put("username", it.accountSubject())
            }
        }
        coordinator(store, network).use { manager ->
            manager.beginLogin()
            manager.acceptCallback(callback(manager.uiState.value.authorizationUrl!!))
            assertEquals(listOf("exchange", "device", "account"), network.calls)
            assertEquals(1, store.saves)
            assertTrue(manager.uiState.value.connected)
            assertNull(manager.uiState.value.notice)
        }
    }

    @Test fun descriptiveAccountUsernameDoesNotOverrideLiteralSubject() = runBlocking {
        val store = MemoryTokens()
        val network = FakeNetwork().apply {
            account = { JSONObject().put("username", "synthetic") }
        }
        coordinator(store, network).use { manager ->
            manager.beginLogin()
            manager.acceptCallback(callback(manager.uiState.value.authorizationUrl!!))
            assertEquals("synthetic@carbon.super", manager.uiState.value.ownerSubject)
            assertEquals("synthetic@carbon.super", store.value!!.accountSubject())
            assertEquals(1, store.saves)
            assertTrue(manager.uiState.value.connected)
        }
    }

    @Test fun differentRefreshSubjectIsRejected() = runBlocking {
        val old = token("old", expiry = 900)
        val store = MemoryTokens(old)
        val network = FakeNetwork().apply {
            refresh = { token("wrong", "wrong@carbon.super") }
        }
        coordinator(store, network).use { manager ->
            manager.loadSavedSession()
            assertFailsWith<EnzonaSessionUnavailableException> { manager.usableTokens() }
            assertSame(old, store.value)
            assertEquals(old.accountSubject(), manager.uiState.value.ownerSubject)
            assertEquals(0, store.saves)
        }
    }

    @Test fun oldRefreshCannotInvalidateASuccessfulNewLogin() = runBlocking {
        withTimeout(5000) {
            val old = token("old", expiry = 900)
            val store = MemoryTokens(old)
            val network = FakeNetwork()
            val exchangeRelease = CompletableDeferred<Unit>()
            val refreshRelease = CompletableDeferred<Unit>()
            network.exchange = { exchangeRelease.await(); token("new", "other@carbon.super") }
            network.refresh = { withContext(NonCancellable) {
                refreshRelease.await(); throw EnzonaApiException(400, EnzonaOAuthFailure.INVALID_GRANT)
            } }
            coordinator(store, network).use { manager ->
                manager.loadSavedSession()
                manager.beginLogin()
                manager.acceptCallback(callback(manager.uiState.value.authorizationUrl!!))
                val reader = async(start = CoroutineStart.UNDISPATCHED) { manager.usableTokens() }
                exchangeRelease.complete(Unit)
                refreshRelease.complete(Unit)
                assertFailsWith<CancellationException> { reader.await() }
                assertEquals("new", store.value!!.accessToken)
                assertEquals("other@carbon.super", manager.uiState.value.ownerSubject)
                assertTrue(manager.uiState.value.connected)
                assertEquals(0, store.clears)
                assertEquals(1, store.saves)
            }
        }
    }

    @Test fun subframeAndInvalidCallbackDoNotConsumePendingAttempt() = runBlocking {
        val store = MemoryTokens()
        val network = FakeNetwork()
        coordinator(store, network).use { manager ->
            manager.beginLogin()
            val valid = callback(manager.uiState.value.authorizationUrl!!)
            assertFalse(manager.acceptCallback(valid, isMainFrame = false))
            assertFalse(manager.acceptCallback(valid.replace("apk-callback", "apk-callback.example")))
            assertFalse(manager.acceptCallback("http://apk-callback/?code=SYNTHETIC"))
            assertTrue(network.calls.isEmpty())
            assertTrue(manager.acceptCallback(valid))
            assertEquals(1, store.saves)
        }
    }

    @Test fun closeIsIdempotentAndPreservesEncryptedCache() = runBlocking {
        val old = token("old")
        val store = MemoryTokens(old)
        var closes = 0
        val network = FakeNetwork()
        val manager = coordinator(store, network) { closes++ }
        manager.loadSavedSession()
        manager.beginLogin()
        val late = callback(manager.uiState.value.authorizationUrl!!)
        manager.close()
        manager.close()
        assertEquals(1, closes)
        assertSame(old, store.value)
        assertFalse(manager.acceptCallback(late))
        assertFailsWith<EnzonaSessionUnavailableException> { manager.usableTokens() }
        assertEquals(0, store.clears)
    }

    @Test fun uiStateToStringRedactsAllPrivateFields() {
        val state = EnzonaSessionUiState("PRIVATE_URL", true, "PRIVATE_OWNER", "PRIVATE_NAME", true, "PRIVATE_NOTICE")
        assertFalse(state.toString().contains("PRIVATE"))
        assertTrue(state.toString().contains("redacted"))
    }

    @Test fun localLockClearsMemoryWithoutErasingCacheAndUnlockReloadsIt() = runBlocking {
        val old = token("old")
        val store = MemoryTokens(old)
        val network = FakeNetwork()
        coordinator(store, network).use { manager ->
            manager.loadSavedSession()
            manager.beginLogin()
            val late = callback(manager.uiState.value.authorizationUrl!!)
            manager.suspendAccess()
            assertSame(old, store.value)
            assertEquals(0, store.clears)
            assertEquals(EnzonaSessionUiState(), manager.uiState.value)
            assertFalse(manager.acceptCallback(late))
            manager.beginLogin()
            assertNull(manager.uiState.value.authorizationUrl)
            assertFailsWith<EnzonaSessionUnavailableException> { manager.usableTokens() }
            manager.loadSavedSession()
            assertSame(old, manager.usableTokens())
            assertTrue(manager.uiState.value.connected)
            assertTrue(network.calls.isEmpty())
        }
    }

    @Test fun activityReloadPreservesPendingLoginBootstrapAndCommittedSessionUntilLocalLock() = runBlocking {
        withTimeout(5000) {
            val store = MemoryTokens()
            val network = FakeNetwork()
            val enteredAccount = CompletableDeferred<Unit>()
            val releaseAccount = CompletableDeferred<Unit>()
            network.account = { tokens ->
                enteredAccount.complete(Unit)
                releaseAccount.await()
                JSONObject().put("username", tokens.accountSubject())
            }
            coordinator(store, network).use { manager ->
                try {
                    manager.loadSavedSession()
                    manager.beginLogin()
                    val authorizationUrl = manager.uiState.value.authorizationUrl!!
                    manager.loadSavedSession()
                    assertEquals(authorizationUrl, manager.uiState.value.authorizationUrl)
                    assertEquals(1, store.loads)
                    assertTrue(network.calls.isEmpty())

                    assertTrue(manager.acceptCallback(callback(authorizationUrl)))
                    enteredAccount.await()
                    manager.loadSavedSession()
                    assertEquals(authorizationUrl, manager.uiState.value.authorizationUrl)
                    assertTrue(manager.uiState.value.busy)
                    assertEquals(1, store.loads)
                    assertEquals(0, store.saves)
                    assertNull(manager.uiState.value.pendingCardRefreshOwner)
                    releaseAccount.complete(Unit)
                    while (!manager.uiState.value.connected) yield()

                    val committed = store.value!!
                    manager.loadSavedSession()
                    assertEquals(1, store.loads)
                    assertSame(committed, manager.usableTokens())
                    assertEquals(listOf("exchange", "device", "account"), network.calls)
                    val owner = committed.accountSubject()
                    assertEquals(owner, manager.uiState.value.pendingCardRefreshOwner)
                    assertFalse(manager.consumeCardRefresh("another-owner"))
                    assertEquals(owner, manager.uiState.value.pendingCardRefreshOwner)
                    assertTrue(manager.consumeCardRefresh(owner))
                    assertFalse(manager.consumeCardRefresh(owner))
                    manager.loadSavedSession()
                    assertNull(manager.uiState.value.pendingCardRefreshOwner)

                    manager.suspendAccess()
                    assertFalse(manager.uiState.value.connected)
                    assertFailsWith<EnzonaSessionUnavailableException> { manager.usableTokens() }
                    manager.loadSavedSession()
                    assertEquals(2, store.loads)
                    assertSame(committed, manager.usableTokens())
                    assertTrue(manager.uiState.value.connected)
                    assertEquals(1, store.saves)
                    assertEquals(0, store.clears)
                    assertEquals(listOf("exchange", "device", "account"), network.calls)
                    assertFalse(manager.consumeCardRefresh(owner))
                } finally { releaseAccount.complete(Unit) }
            }
        }
    }

    @Test fun cardRefreshIsReissuedForSameOwnerLoginButNotTokenRefresh() = runBlocking {
        val network = FakeNetwork().apply { exchange = { token("new", expiry = 900) } }
        coordinator(MemoryTokens(), network).use { manager ->
            repeat(2) {
                manager.beginLogin()
                assertTrue(manager.acceptCallback(callback(manager.uiState.value.authorizationUrl!!)))
                val owner = manager.uiState.value.ownerSubject!!
                manager.usableTokens()
                assertEquals(owner, manager.uiState.value.pendingCardRefreshOwner)
                assertTrue(manager.consumeCardRefresh(owner))
                manager.usableTokens()
                assertNull(manager.uiState.value.pendingCardRefreshOwner)
                assertFalse(manager.consumeCardRefresh(owner))
            }
            assertEquals(2, network.calls.count { it == "refresh" })
        }
    }

    @Test fun cardRefreshFollowsNewOwnerAndOnlyOneConcurrentConsumerWins() = runBlocking {
        val network = FakeNetwork()
        coordinator(MemoryTokens(), network).use { manager ->
            manager.beginLogin()
            assertTrue(manager.acceptCallback(callback(manager.uiState.value.authorizationUrl!!)))
            val previousOwner = manager.uiState.value.ownerSubject!!
            network.exchange = { token("other", "other@carbon.super") }
            manager.beginLogin()
            assertTrue(manager.acceptCallback(callback(manager.uiState.value.authorizationUrl!!)))
            val owner = manager.uiState.value.ownerSubject!!
            assertFalse(manager.consumeCardRefresh(previousOwner))
            assertEquals(owner, manager.uiState.value.pendingCardRefreshOwner)
            val readers = List(8) { async(Dispatchers.Default) { manager.consumeCardRefresh(owner) } }
            assertEquals(1, readers.count { it.await() })
            assertNull(manager.uiState.value.pendingCardRefreshOwner)
        }
    }

    @Test fun invalidatingAccessDropsUnconsumedCardRefreshAndReloadCannotReissueIt() = runBlocking {
        for (invalidation in listOf("lock", "disconnect", "close", "invalid_grant")) {
            val network = FakeNetwork().apply {
                exchange = { token("new", expiry = 900) }
                refresh = { throw EnzonaApiException(400, EnzonaOAuthFailure.INVALID_GRANT) }
            }
            coordinator(MemoryTokens(), network).use { manager ->
                manager.beginLogin()
                assertTrue(manager.acceptCallback(callback(manager.uiState.value.authorizationUrl!!)))
                val owner = manager.uiState.value.ownerSubject!!
                assertEquals(owner, manager.uiState.value.pendingCardRefreshOwner)
                when (invalidation) {
                    "lock" -> manager.suspendAccess()
                    "disconnect" -> assertTrue(manager.disconnectLocal())
                    "close" -> manager.close()
                    "invalid_grant" -> assertFailsWith<EnzonaSessionUnavailableException> { manager.usableTokens() }
                }
                assertNull(manager.uiState.value.pendingCardRefreshOwner)
                assertFalse(manager.consumeCardRefresh(owner))
                manager.loadSavedSession()
                assertNull(manager.uiState.value.pendingCardRefreshOwner)
                assertFalse(manager.consumeCardRefresh(owner))
            }
        }
    }

    @Test fun failedOrCancelledBootstrapDoesNotRequestCardRefresh() = runBlocking {
        withTimeout(5000) {
            val network = FakeNetwork().apply { account = { throw IOException("synthetic") } }
            val store = MemoryTokens()
            coordinator(store, network).use { manager ->
                manager.beginLogin()
                assertTrue(manager.acceptCallback(callback(manager.uiState.value.authorizationUrl!!)))
                assertNull(manager.uiState.value.pendingCardRefreshOwner)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                network.account = { withContext(NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                    JSONObject().put("username", "synthetic")
                } }
                manager.beginLogin()
                assertTrue(manager.acceptCallback(callback(manager.uiState.value.authorizationUrl!!)))
                entered.await()
                manager.cancelLogin()
                release.complete(Unit)
                yield()
                assertNull(manager.uiState.value.pendingCardRefreshOwner)
                assertNull(store.value)
                assertFalse(manager.consumeCardRefresh("synthetic@carbon.super"))
            }
        }
    }

    @Test fun disconnectBeforeAccessResetCannotRestoreTheOldSessionOnNextUnlock() = runBlocking {
        val store = MemoryTokens(token("old"))
        val network = FakeNetwork()
        coordinator(store, network).use { manager ->
            manager.loadSavedSession()
            manager.beginLogin()
            val oldCallback = callback(manager.uiState.value.authorizationUrl!!)
            manager.suspendAccess()
            assertTrue(manager.disconnectLocal())
            assertEquals(1, store.clears)
            assertNull(store.value)

            // Root resets its separate local vault only after disconnectLocal succeeds.
            manager.loadSavedSession()
            assertEquals(2, store.loads)
            assertFalse(manager.uiState.value.connected)
            assertNull(manager.uiState.value.ownerSubject)
            assertFalse(manager.acceptCallback(oldCallback))
            assertFailsWith<EnzonaSessionUnavailableException> { manager.usableTokens() }
            assertEquals(0, store.saves)
            assertTrue(network.calls.isEmpty())

            // Erasure must not leave the next locally unlocked session suspended.
            manager.beginLogin()
            assertTrue(manager.acceptCallback(callback(manager.uiState.value.authorizationUrl!!)))
            assertTrue(manager.uiState.value.connected)
            assertEquals("new", store.value?.accessToken)
        }
    }

    @Test fun allPersistenceRunsOffCallerThreadAndLogoutWaitsForAnInProgressSave() = runBlocking {
        withTimeout(5000) {
            val callerThread = Thread.currentThread()
            val old = token("old")
            val backing = MemoryTokens(old)
            val enteredSave = CompletableDeferred<Unit>()
            val releaseSave = CountDownLatch(1)
            val store = object : TokenPersistence {
                override fun load(): EnzonaTokens? {
                    assertTrue(Thread.currentThread() !== callerThread)
                    return backing.load()
                }
                override fun save(tokens: EnzonaTokens) {
                    assertTrue(Thread.currentThread() !== callerThread)
                    if (tokens.accessToken == "new") {
                        enteredSave.complete(Unit)
                        check(releaseSave.await(5, TimeUnit.SECONDS))
                    }
                    backing.save(tokens)
                }
                override fun clear() {
                    assertTrue(Thread.currentThread() !== callerThread)
                    backing.clear()
                }
            }
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
                EnzonaSessionCoordinator(store, FakeNetwork(), { emptyMap() }, Dispatchers.Unconfined,
                    { 1000 }, ioDispatcher = io).use { manager ->
                    try {
                        manager.loadSavedSession()
                        manager.beginLogin()
                        manager.acceptCallback(callback(manager.uiState.value.authorizationUrl!!))
                        enteredSave.await()
                        val logout = async(start = CoroutineStart.UNDISPATCHED) { manager.disconnectLocal() }
                        // These immediate actions must not block on the worker's disk operation.
                        assertFalse(manager.uiState.value.connected)
                        assertFalse(logout.isCompleted)
                        manager.beginLogin()
                        assertNull(manager.uiState.value.authorizationUrl)
                        releaseSave.countDown()
                        assertTrue(logout.await())
                        assertNull(backing.value)
                        assertFalse(manager.uiState.value.busy)
                    } finally { releaseSave.countDown() }
                }
            }
        }
    }

    @Test fun localLockDuringDiskSaveRestoresOldCacheBeforeUnlockReadsIt() = runBlocking {
        withTimeout(5000) {
            val old = token("old")
            val backing = MemoryTokens(old)
            val enteredSave = CompletableDeferred<Unit>()
            val releaseSave = CountDownLatch(1)
            val store = object : TokenPersistence by backing {
                override fun save(tokens: EnzonaTokens) {
                    if (tokens.accessToken == "new") {
                        enteredSave.complete(Unit)
                        check(releaseSave.await(5, TimeUnit.SECONDS))
                    }
                    backing.save(tokens)
                }
            }
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
                EnzonaSessionCoordinator(store, FakeNetwork(), { emptyMap() }, Dispatchers.Unconfined,
                    { 1000 }, ioDispatcher = io).use { manager ->
                    try {
                        manager.loadSavedSession()
                        manager.beginLogin()
                        manager.acceptCallback(callback(manager.uiState.value.authorizationUrl!!))
                        enteredSave.await()
                        manager.suspendAccess()
                        assertEquals(EnzonaSessionUiState(), manager.uiState.value)
                        releaseSave.countDown()
                        manager.loadSavedSession()
                        assertSame(old, backing.value)
                        assertSame(old, manager.usableTokens())
                        assertEquals(0, backing.clears)
                    } finally { releaseSave.countDown() }
                }
            }
        }
    }

    @Test fun logoutDeletionContinuesWhenItsCallerIsCancelled() = runBlocking {
        withTimeout(5000) {
            val backing = MemoryTokens(token("old"))
            val enteredClear = CompletableDeferred<Unit>()
            val releaseClear = CountDownLatch(1)
            val store = object : TokenPersistence by backing {
                override fun clear() {
                    enteredClear.complete(Unit)
                    check(releaseClear.await(5, TimeUnit.SECONDS))
                    backing.clear()
                }
            }
            Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { io ->
                EnzonaSessionCoordinator(store, FakeNetwork(), { emptyMap() }, Dispatchers.Unconfined,
                    { 1000 }, ioDispatcher = io).use { manager ->
                    try {
                        manager.loadSavedSession()
                        val logout = async(start = CoroutineStart.UNDISPATCHED) { manager.disconnectLocal() }
                        enteredClear.await()
                        logout.cancel()
                        releaseClear.countDown()
                        logout.join()
                        assertNull(backing.value)
                        assertFalse(manager.uiState.value.connected)
                        assertFalse(manager.uiState.value.busy)
                    } finally { releaseClear.countDown() }
                }
            }
        }
    }

    private companion object {
        fun token(access: String, subject: String = "synthetic@carbon.super", expiry: Long = 5000): EnzonaTokens {
            val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                JSONObject().put("sub", subject).toString().toByteArray())
            return EnzonaTokens(access, "refresh-$access", "header.$payload.signature", expiry)
        }
        fun callback(authorizationUrl: String): String {
            val state = URI(authorizationUrl).rawQuery.split('&').map { it.split('=', limit = 2) }
                .single { it[0] == "state" }[1]
            return "http://apk-callback/?code=SYNTHETIC_CODE&state=${URLDecoder.decode(state, "UTF-8")}"
        }
    }
}
