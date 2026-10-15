package dev.duardo.neotransfer.enzona

import dev.duardo.neotransfer.core.enzona.EnzonaAuthContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant

data class EnzonaSessionUiState(
    val authorizationUrl: String? = null,
    /** A locally cached session; does not assert current server validity or a fresh balance. */
    val connected: Boolean = false,
    val ownerSubject: String? = null,
    val displayName: String? = null,
    val busy: Boolean = false,
    val notice: String? = null,
    /** One card-list read after a committed login; survives Activity recreation, not local lock. */
    val pendingCardRefreshOwner: String? = null,
) {
    override fun toString() = "EnzonaSessionUiState(connected=$connected, busy=$busy, privateFields=redacted)"
}

internal interface EnzonaSessionNetwork {
    suspend fun exchange(exchange: EnzonaAuthContract.CodeExchange): EnzonaTokens
    suspend fun refresh(tokens: EnzonaTokens): EnzonaTokens
    suspend fun recordDevice(tokens: EnzonaTokens, fields: Map<String, String>)
    suspend fun account(tokens: EnzonaTokens): JSONObject
}

private class SessionNetwork(private val api: EnzonaSessionApi) : EnzonaSessionNetwork {
    override suspend fun exchange(exchange: EnzonaAuthContract.CodeExchange) = api.exchange(exchange)
    override suspend fun refresh(tokens: EnzonaTokens) = api.refresh(tokens)
    override suspend fun recordDevice(tokens: EnzonaTokens, fields: Map<String, String>) = api.recordDevice(tokens, fields)
    override suspend fun account(tokens: EnzonaTokens) = api.account(tokens)
}

/** No original exception/cause, URL, code or response body crosses into presentation callbacks. */
internal class EnzonaSessionUnavailableException(message: String) : IllegalStateException(message)

/**
 * App-owned scope; root calls loadSavedSession only AFTER the local unlock and collects uiState.
 * Root disposes the login screen before disconnectLocal + clearEnzonaWebSession. No remote SSO
 * revocation is claimed. close cancels work but preserves encrypted storage for the next unlock.
 */
internal class EnzonaSessionCoordinator(
    private val persistence: TokenPersistence,
    private val network: EnzonaSessionNetwork,
    private val deviceInfo: () -> Map<String, String>,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val nowEpochSeconds: () -> Long = { Instant.now().epochSecond },
    private val closeOwnedNetwork: () -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    constructor(
        store: EnzonaSessionStore,
        http: EnzonaHttp,
        deviceInfo: () -> Map<String, String>,
        ownsHttp: Boolean = false,
    ) : this(EnzonaTokenPersistence(store), SessionNetwork(EnzonaSessionApi(http)), deviceInfo,
        closeOwnedNetwork = { if (ownsHttp) http.close() })

    private val lock = Any()
    private val storageMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow(EnzonaSessionUiState())
    val uiState: StateFlow<EnzonaSessionUiState> = mutableState.asStateFlow()
    private var tokens: EnzonaTokens? = null
    private var attempt: EnzonaAuthContract.LoginAttempt? = null
    private var loginJob: Job? = null
    private var refreshFlight: Deferred<EnzonaTokens>? = null
    private var generation = 0L
    private var closed = false
    private var disconnecting = false
    private var accessSuspended = false

    /** Local cache only, even when expired. Never performs account/balance/refresh requests. */
    suspend fun loadSavedSession() {
        val expected = synchronized(lock) {
            if (closed || disconnecting) return
            // Activity recreation must preserve the app-owned login/refresh already in progress.
            if (!accessSuspended && (tokens != null || attempt != null || loginJob != null)) return
            invalidateWork()
            accessSuspended = false // Root only calls this after a successful local unlock.
            mutableState.value = mutableState.value.copy(authorizationUrl = null, busy = true, notice = null)
            generation
        }
        try {
            withContext(ioDispatcher) {
                storageMutex.withLock {
                    ensureCurrent(expected)
                    val saved = persistence.load()
                    val subject = saved?.accountSubject()
                    ensureCurrent(expected)
                    synchronized(lock) {
                        checkCurrent(expected)
                        tokens = saved
                        mutableState.value = EnzonaSessionUiState(connected = saved != null, ownerSubject = subject)
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            synchronized(lock) {
                if (isCurrent(expected)) mutableState.value = mutableState.value.copy(
                    notice = "No se pudo recuperar la sesión guardada de ENZONA")
            }
        } finally {
            synchronized(lock) {
                if (isCurrent(expected)) mutableState.value = mutableState.value.copy(
                    busy = loginJob != null || refreshFlight != null)
            }
        }
    }

    fun beginLogin(): Unit = synchronized(lock) {
        if (closed || disconnecting || accessSuspended) return
        invalidateWork()
        attempt = EnzonaAuthContract.newAttempt()
        mutableState.value = mutableState.value.copy(authorizationUrl = attempt!!.authorizationUri.toString(),
            busy = false, notice = null)
    }

    fun cancelLogin(): Unit = synchronized(lock) {
        if (closed || disconnecting) return
        invalidateWork()
        mutableState.value = mutableState.value.copy(authorizationUrl = null, busy = false)
    }

    /** Screen supplies main-frame callbacks only; core additionally verifies exact URI and state. */
    fun acceptCallback(value: String, isMainFrame: Boolean = true): Boolean = synchronized(lock) {
        if (closed || accessSuspended || !isMainFrame) return false
        val pending = attempt ?: return false
        val exchange = try { pending.consumeCallback(value) }
        catch (failure: EnzonaAuthContract.CallbackException) {
            if (failure.reason == EnzonaAuthContract.CallbackFailure.AUTHORIZATION_REJECTED) {
                attempt = null
                mutableState.value = mutableState.value.copy(authorizationUrl = null)
            }
            mutableState.value = mutableState.value.copy(notice = "No se pudo validar el acceso a ENZONA")
            return false
        }
        attempt = null
        val expected = generation
        // Keep the screen mounted until bootstrap finishes so its back action can cancel requests.
        mutableState.value = mutableState.value.copy(busy = true, notice = null)
        loginJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val candidate = network.exchange(exchange)
                ensureCurrent(expected)
                val subject = candidate.accountSubject()
                try {
                    network.recordDevice(candidate, deviceInfo())
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    // APK setAuthenticationInfo discards its result and continues to getAccount.
                    // This does not prove registration is optional on the server; never log metadata.
                }
                ensureCurrent(expected)
                val account = network.account(candidate)
                ensureCurrent(expected)
                require(account.length() > 0 && !account.has("fault") && !account.has("error"))
                // The authenticated request uses the literal sub. Account.username is descriptive;
                // its representation/equivalence to sub (including tenant suffixes) is not proven.
                saveAndCommit(expected, candidate) {
                    // An old account's refresh cannot clear/replace the newly validated account.
                    generation++
                    val oldRefresh = refreshFlight
                    refreshFlight = null
                    loginJob = null
                    oldRefresh?.cancel()
                    // Account display-name keys are not established by the recovered contract.
                    mutableState.value = EnzonaSessionUiState(connected = true, ownerSubject = subject,
                        pendingCardRefreshOwner = subject)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                synchronized(lock) {
                    if (isCurrent(expected)) mutableState.value = mutableState.value.copy(
                        authorizationUrl = null, notice = noticeFor(failure))
                }
            } finally {
                synchronized(lock) {
                    if (isCurrent(expected)) {
                        loginJob = null
                        mutableState.value = mutableState.value.copy(busy = refreshFlight != null)
                    }
                }
            }
        }
        loginJob!!.start()
        true
    }

    /** Consume only when the matching wallet can start its card-list read. Does not perform I/O. */
    fun consumeCardRefresh(owner: String): Boolean = synchronized(lock) {
        val state = mutableState.value
        if (closed || disconnecting || accessSuspended || !state.connected ||
            state.ownerSubject != owner || state.pendingCardRefreshOwner != owner) return false
        mutableState.value = state.copy(pendingCardRefreshOwner = null)
        true
    }

    /** No request retry: callers decide how to use tokens; payments must never be auto-resubmitted. */
    suspend fun usableTokens(): EnzonaTokens {
        currentCoroutineContext().ensureActive()
        val expected: Long
        val flight: Deferred<EnzonaTokens>
        synchronized(lock) {
            if (closed || accessSuspended) throw EnzonaSessionUnavailableException(RECONNECT)
            val previous = tokens ?: throw EnzonaSessionUnavailableException(RECONNECT)
            if (!previous.needsRefresh(nowEpochSeconds())) return previous
            if (previous.refreshToken == null) {
                mutableState.value = mutableState.value.copy(notice = RECONNECT)
                throw EnzonaSessionUnavailableException(RECONNECT)
            }
            expected = generation
            flight = refreshFlight ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    val renewed = network.refresh(previous)
                    ensureCurrent(expected)
                    require(renewed.accountSubject() == previous.accountSubject())
                    saveAndCommit(expected, renewed) {
                        mutableState.value = mutableState.value.copy(notice = null)
                    }
                    renewed
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if ((failure as? EnzonaApiException)?.oauthFailure == EnzonaOAuthFailure.INVALID_GRANT) {
                        clearInvalidSession(expected)
                    }
                    synchronized(lock) {
                        checkCurrent(expected)
                        val notice = noticeFor(failure)
                        mutableState.value = mutableState.value.copy(notice = notice)
                        throw EnzonaSessionUnavailableException(notice)
                    }
                } finally {
                    synchronized(lock) {
                        if (isCurrent(expected)) {
                            refreshFlight = null
                            mutableState.value = mutableState.value.copy(busy = loginJob != null)
                        }
                    }
                }
            }.also {
                refreshFlight = it
                mutableState.value = mutableState.value.copy(busy = true)
            }
            flight.start()
        }
        // Cancelling a reader must not cancel the shared refresh owned by the app scope.
        val usable = flight.await()
        currentCoroutineContext().ensureActive()
        synchronized(lock) { checkCurrent(expected) }
        return usable
    }

    /** Returns false on a local deletion failure; never claims deletion succeeded in that case. */
    suspend fun disconnectLocal(): Boolean {
        synchronized(lock) {
            if (closed || disconnecting) return false
            invalidateWork()
            disconnecting = true
            tokens = null
            mutableState.value = EnzonaSessionUiState(busy = true)
        }
        // Once logout is requested, caller cancellation/close cannot skip the disk deletion.
        return withContext(NonCancellable + ioDispatcher) {
            val cleared = try { storageMutex.withLock { persistence.clear() }; true }
            catch (_: Exception) { false }
            synchronized(lock) {
                disconnecting = false
                if (!closed) mutableState.value = EnzonaSessionUiState(
                    notice = if (cleared) null else LOCAL_CLEAR_FAILED)
            }
            cleared
        }
    }

    fun clearNotice(): Unit = synchronized(lock) {
        mutableState.value = mutableState.value.copy(notice = null)
    }

    /** Root calls on local lock. Re-enabling access requires loadSavedSession after local unlock. */
    fun suspendAccess(): Unit = synchronized(lock) {
        if (closed) return
        invalidateWork()
        accessSuspended = true
        tokens = null
        mutableState.value = EnzonaSessionUiState()
    }

    override fun close(): Unit = synchronized(lock) {
        if (closed) return
        closed = true
        invalidateWork()
        tokens = null
        mutableState.value = EnzonaSessionUiState()
        scope.cancel()
        closeOwnedNetwork()
    }

    /** No disk I/O under the state monitor. A save already in progress is rolled back on cancel;
     * logout's clear waits for this transaction, so late writes cannot outlive completed logout. */
    private suspend fun saveAndCommit(expected: Long, candidate: EnzonaTokens, commit: () -> Unit) {
        ensureCurrent(expected)
        withContext(NonCancellable + ioDispatcher) {
            storageMutex.withLock {
                val previous = synchronized(lock) { checkCurrent(expected); tokens }
                persistence.save(candidate)
                val accepted = synchronized(lock) {
                    if (isCurrent(expected)) {
                        tokens = candidate
                        commit()
                        true
                    } else false
                }
                if (!accepted) {
                    // Preserve the last committed account on cancel/close. An explicit logout
                    // has already queued its clear behind this mutex and will erase it next.
                    if (previous == null) persistence.clear() else persistence.save(previous)
                    throw CancellationException("La operación de ENZONA se canceló")
                }
            }
        }
    }

    private suspend fun clearInvalidSession(expected: Long) = withContext(NonCancellable + ioDispatcher) {
        storageMutex.withLock {
            synchronized(lock) {
                checkCurrent(expected)
                tokens = null
                mutableState.value = mutableState.value.copy(connected = false, ownerSubject = null, displayName = null,
                    pendingCardRefreshOwner = null)
            }
            try { persistence.clear() } catch (_: Exception) {
                synchronized(lock) {
                    if (isCurrent(expected)) mutableState.value = mutableState.value.copy(notice = LOCAL_CLEAR_FAILED)
                }
                throw EnzonaSessionUnavailableException(LOCAL_CLEAR_FAILED)
            }
        }
    }

    private fun invalidateWork() {
        generation++
        attempt = null
        val login = loginJob
        val refresh = refreshFlight
        loginJob = null
        refreshFlight = null
        login?.cancel()
        refresh?.cancel()
    }

    private fun isCurrent(expected: Long) = !closed && generation == expected
    private fun checkCurrent(expected: Long) {
        if (!isCurrent(expected)) throw CancellationException("La operación de ENZONA se canceló")
    }
    private suspend fun ensureCurrent(expected: Long) {
        currentCoroutineContext().ensureActive()
        synchronized(lock) { checkCurrent(expected) }
    }

    private fun noticeFor(failure: Exception): String = when ((failure as? EnzonaApiException)?.oauthFailure) {
        EnzonaOAuthFailure.INVALID_GRANT -> RECONNECT
        EnzonaOAuthFailure.INVALID_CLIENT, EnzonaOAuthFailure.UNAUTHORIZED_CLIENT ->
            "La configuración de acceso a ENZONA no fue aceptada"
        else -> "ENZONA no pudo completar la solicitud. Inténtalo de nuevo"
    }

    private companion object {
        const val RECONNECT = "Vuelve a conectar ENZONA"
        const val LOCAL_CLEAR_FAILED = "No se pudo borrar la sesión guardada de ENZONA"
    }
}
