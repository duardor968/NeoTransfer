package dev.duardo.neotransfer.enzona

import dev.duardo.neotransfer.enzona.data.EnzonaCardLink
import dev.duardo.neotransfer.enzona.data.EnzonaJournalEntry
import dev.duardo.neotransfer.enzona.data.EnzonaReadKind
import dev.duardo.neotransfer.enzona.data.RoomEnzonaRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

data class EnzonaObservedBalance(val value: EnzonaCardBalance, val observedAt: Long)

data class EnzonaWalletState(
    val owner: String? = null,
    val cards: List<EnzonaRemoteCard> = emptyList(),
    val links: List<EnzonaCardLink> = emptyList(),
    val balances: Map<String, EnzonaObservedBalance> = emptyMap(),
    val transactions: List<EnzonaTransaction> = emptyList(),
    val journal: List<EnzonaJournalEntry> = emptyList(),
    val selectedCardId: String? = null,
    val cardsAt: Long? = null,
    val activityAt: Long? = null,
    val busy: Boolean = false,
    val notice: String? = null,
) {
    override fun toString() = "EnzonaWalletState(privateFields=redacted, busy=$busy)"
}

/** Account-scoped cache. Loading and choosing a card never make a network request. */
internal class EnzonaWalletCoordinator(
    private val session: EnzonaSessionCoordinator,
    private val api: EnzonaReadApi,
    private val repository: RoomEnzonaRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(EnzonaWalletState())
    val state: StateFlow<EnzonaWalletState> = mutableState.asStateFlow()
    private var generation = 0L
    private var work: Job? = null
    private var journalWork: Job? = null

    fun activate(owner: String?) {
        if (owner == mutableState.value.owner) return
        generation++
        work?.cancel(); journalWork?.cancel()
        mutableState.value = EnzonaWalletState(owner = owner, busy = owner != null)
        if (owner == null) return
        val expected = generation
        work = scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    val cards = repository.readCache(owner, EnzonaReadKind.CARDS, "cards")
                    val activity = repository.readCache(owner, EnzonaReadKind.HISTORY, "activity")
                    EnzonaWalletState(owner = owner,
                        cards = cards?.let { EnzonaReadJson.cards(it.json) }.orEmpty(),
                        links = repository.cardLinks(owner),
                        balances = repository.cachedReads(owner, EnzonaReadKind.BALANCE).associate {
                            it.resourceId to EnzonaObservedBalance(EnzonaReadJson.balance(it.json), it.observedAt)
                        },
                        transactions = repository.transactions(owner),
                        cardsAt = cards?.observedAt, activityAt = activity?.observedAt)
                }
                if (expected == generation) mutableState.value = saved
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (expected == generation) mutableState.value = mutableState.value.copy(
                busy = false, notice = "No se pudo abrir la cartera de ENZONA") }
            if (expected == generation) journalWork = scope.launch {
                repository.observeJournal(owner).collect { entries ->
                    if (expected == generation) mutableState.value = mutableState.value.copy(journal = entries)
                }
            }
        }
    }

    fun select(cardId: String?) {
        require(cardId == null || mutableState.value.cards.any { it.fundingSourceUuid == cardId })
        mutableState.value = mutableState.value.copy(selectedCardId = cardId)
    }

    fun refreshCards() = read { owner, tokens ->
        val fetched = api.cards(tokens)
        withContext(Dispatchers.IO) { repository.cacheRead(owner, EnzonaReadKind.CARDS, "cards", fetched.json, fetched.observedAt) }
        return@read { previous: EnzonaWalletState -> previous.copy(cards = fetched.value, cardsAt = fetched.observedAt,
            selectedCardId = previous.selectedCardId?.takeIf { id -> fetched.value.any { it.fundingSourceUuid == id } }) }
    }

    fun queryBalance(cardId: String) {
        require(mutableState.value.cards.any { it.fundingSourceUuid == cardId })
        read { owner, tokens ->
            val fetched = api.balance(tokens, cardId)
            withContext(Dispatchers.IO) { repository.cacheRead(owner, EnzonaReadKind.BALANCE, cardId, fetched.json, fetched.observedAt) }
            return@read { previous: EnzonaWalletState -> previous.copy(balances = previous.balances +
                (cardId to EnzonaObservedBalance(fetched.value, fetched.observedAt))) }
        }
    }

    fun refreshActivity(from: LocalDate = LocalDate.now().minusDays(7), through: LocalDate = LocalDate.now()) = read { owner, tokens ->
        val fetched = api.transactions(tokens, from, through)
        val history = withContext(Dispatchers.IO) {
            repository.cacheRead(owner, EnzonaReadKind.HISTORY, "activity", fetched.json, fetched.observedAt)
            repository.transactions(owner)
        }
        return@read { previous: EnzonaWalletState -> previous.copy(transactions = history, activityAt = fetched.observedAt) }
    }

    fun saveLink(cardId: String, pan: String, alias: String?) {
        val owner = mutableState.value.owner ?: return
        val card = mutableState.value.cards.singleOrNull { it.fundingSourceUuid == cardId } ?: return
        require(pan.matches(Regex("[0-9]{16}"))) { "Introduce los 16 dígitos de la tarjeta" }
        require(card.last4.isNullOrBlank() || pan.takeLast(4) == card.last4) { "El número no coincide con esta tarjeta" }
        val expected = generation
        scope.launch {
            try {
                val links = withContext(Dispatchers.IO) {
                    repository.putCardLink(EnzonaCardLink(owner, cardId, pan, alias?.takeIf(String::isNotBlank)))
                    repository.cardLinks(owner)
                }
                if (expected == generation) mutableState.value = mutableState.value.copy(links = links)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (expected == generation) notice("No se pudo guardar la tarjeta") }
        }
    }

    fun clearNotice() { mutableState.value = mutableState.value.copy(notice = null) }

    private fun read(fetch: suspend (String, EnzonaTokens) -> (EnzonaWalletState) -> EnzonaWalletState) {
        val owner = mutableState.value.owner ?: return
        if (mutableState.value.busy || work?.isActive == true) return
        val expected = generation
        mutableState.value = mutableState.value.copy(busy = true, notice = null)
        work = scope.launch {
            try {
                val tokens = session.usableTokens()
                check(tokens.accountSubject() == owner)
                val update = fetch(owner, tokens)
                ensureActive()
                if (expected == generation) mutableState.value = update(mutableState.value)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (expected == generation) notice(when (failure) {
                    is java.io.InterruptedIOException -> "Tiempo de espera agotado"
                    is EnzonaSessionUnavailableException -> failure.message ?: "Vuelve a conectar ENZONA"
                    else -> "No se pudo consultar ENZONA"
                })
            } finally {
                if (expected == generation) mutableState.value = mutableState.value.copy(busy = false)
            }
        }
    }

    private fun notice(message: String) { mutableState.value = mutableState.value.copy(notice = message) }
}
