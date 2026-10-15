package dev.duardo.neotransfer.enzona.data

import android.content.Context
import androidx.room.Room
import dev.duardo.neotransfer.enzona.EnzonaReadJson
import dev.duardo.neotransfer.enzona.EnzonaTransaction
import java.util.concurrent.Callable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Synchronous Room API: call on the instrumentation worker or a background dispatcher. */
class RoomEnzonaRepository private constructor(private val database: EnzonaDatabase) : AutoCloseable {
    private val dao = database.enzona()

    fun cacheRead(owner: String, kind: EnzonaReadKind, resourceId: String, json: String, observedAt: Long) {
        checkOwner(owner)
        require(resourceId.isNotBlank() && observedAt >= 0)
        val safe = EnzonaCachedRead(owner, kind, resourceId, observedAt, EnzonaCacheSanitizer.sanitize(kind, json))
        val history = if (kind == EnzonaReadKind.HISTORY) EnzonaReadJson.transactions(safe.json).transactions.map {
            EnzonaStoredTransaction(owner, it.transactionUuid, observedAt, EnzonaCacheSanitizer.sanitizeTransaction(it.original.toString()))
        } else emptyList()
        transaction {
            val previous = dao.readCache(owner, kind, resourceId)?.value
            if (previous == null || observedAt >= previous.observedAt) dao.putCache(EnzonaCacheRow(safe))
            history.forEach { incoming ->
                val previousTransaction = dao.transaction(owner, incoming.transactionUuid)?.value
                if (previousTransaction == null || incoming.observedAt >= previousTransaction.observedAt) {
                    dao.putTransaction(EnzonaTransactionRow(incoming))
                }
            }
        }
    }

    /** Accumulated remote history across read windows. It never resolves or submits local intents. */
    fun transactions(owner: String): List<EnzonaTransaction> {
        checkOwner(owner)
        return dao.transactions(owner).map { EnzonaReadJson.transaction(it.value.json) }
    }

    fun readCache(owner: String, kind: EnzonaReadKind, resourceId: String): EnzonaCachedRead? {
        checkOwner(owner)
        require(resourceId.isNotBlank())
        return dao.readCache(owner, kind, resourceId)?.value
    }

    fun cachedReads(owner: String, kind: EnzonaReadKind): List<EnzonaCachedRead> {
        checkOwner(owner)
        return dao.cachedReads(owner, kind).map { it.value }
    }

    fun putCardLink(link: EnzonaCardLink) {
        validateEnzonaCardLink(link)
        dao.putCardLink(EnzonaCardLinkRow(link))
    }

    fun cardLinks(owner: String): List<EnzonaCardLink> {
        checkOwner(owner)
        return dao.cardLinks(owner).map { it.value }
    }

    /** Duplicate UUID with the exact same intent is idempotent, including after submission. */
    fun prepareIntent(intent: EnzonaIntent): EnzonaJournalEntry {
        validateEnzonaIntent(intent)
        return transaction {
            dao.insertIntent(EnzonaJournalRow(EnzonaJournalEntry(intent)))
            val stored = dao.intent(intent.ownerSubject, intent.id)?.value
            require(stored?.intent == intent) { "Intent UUID conflict" }
            checkNotNull(stored)
        }
    }

    fun intent(owner: String, id: String): EnzonaJournalEntry? {
        checkOwner(owner)
        require(id.isNotBlank())
        return dao.intent(owner, id)?.value
    }

    fun journal(owner: String): List<EnzonaJournalEntry> {
        checkOwner(owner)
        return dao.journal(owner).map { it.value }
    }

    fun observeJournal(owner: String): Flow<List<EnzonaJournalEntry>> {
        checkOwner(owner)
        return dao.observeJournal(owner).map { rows -> rows.map { it.value } }
    }

    /** A winning CAS grants one dispatch. No timeout, reopen or restore grants another. */
    fun tryBeginSubmission(owner: String, id: String): Boolean {
        checkOwner(owner)
        require(id.isNotBlank())
        return transaction { dao.tryBeginSubmission(owner, id) == 1 }
    }

    fun markTimedOut(owner: String, id: String): Boolean {
        checkOwner(owner)
        require(id.isNotBlank())
        return transaction { dao.markTimedOut(owner, id) == 1 }
    }

    /** Caller supplies a decoded business result and only an actually observed remote ID. */
    fun recordResult(owner: String, id: String, state: EnzonaIntentState, remoteId: String? = null): Boolean {
        checkOwner(owner)
        require(id.isNotBlank() && state.isTerminal())
        require(remoteId == null || remoteId.isNotBlank())
        return transaction {
            val current = dao.intent(owner, id)?.value ?: return@transaction false
            if (current.state == EnzonaIntentState.PREPARED) return@transaction false
            if (current.state.isTerminal() && current.state != state) return@transaction false
            if (current.remoteId != null && remoteId != null && current.remoteId != remoteId) return@transaction false
            dao.recordResult(owner, id, state, current.remoteId ?: remoteId)
            true
        }
    }

    fun exportLocal(owner: String): EnzonaLocalExport {
        checkOwner(owner)
        return transaction { EnzonaLocalExport(owner, cardLinks(owner), journal(owner), transactions = dao.transactions(owner).map { it.value }) }
    }

    /** Local backup inventory, including owners without an active session. Cache is excluded. */
    fun exportAllLocal(): List<EnzonaLocalExport> = transaction {
        dao.localOwners().map { owner -> EnzonaLocalExport(owner, cardLinks(owner), journal(owner), transactions = dao.transactions(owner).map { it.value }) }
    }

    fun restoreLocal(owner: String, data: EnzonaLocalExport) {
        checkOwner(owner)
        require(data.ownerSubject == owner) { "ENZONA owner mismatch" }
        restoreAllLocal(listOf(data))
    }

    /** Returns newly inserted links, journal entries and retained transactions. */
    fun restoreAllLocal(data: List<EnzonaLocalExport>): Int = restoreAllLocal(data) { it }

    /**
     * Validate every owner and conflict before writes. The backup callback runs before this
     * transaction commits; its failure rolls back all ENZONA owners. This is not a cross-DB commit.
     */
    fun <T> restoreAllLocal(data: List<EnzonaLocalExport>, beforeCommit: (Int) -> T): T {
        validateEnzonaExports(data)
        return transaction {
            val current = exportAllLocal()
            val links = current.flatMap { it.cardLinks }.associateBy { it.ownerSubject to it.cardUuid }
            val entries = current.flatMap { it.journal }.associateBy { it.intent.id }
            val history = current.flatMap { it.transactions }.associateBy { it.ownerSubject to it.transactionUuid }
            var imported = 0
            val plan = data.map { owner -> owner.copy(
                cardLinks = owner.cardLinks.map { incoming ->
                    val previous = links[incoming.ownerSubject to incoming.cardUuid]
                    if (previous == null) { imported++; incoming } else {
                        require(previous.pan == null || incoming.pan == null || previous.pan == incoming.pan) { "ENZONA PAN conflict" }
                        require(previous.alias == null || incoming.alias == null || previous.alias == incoming.alias) { "ENZONA alias conflict" }
                        previous.copy(pan = previous.pan ?: incoming.pan, alias = previous.alias ?: incoming.alias)
                    }
                },
                journal = owner.journal.map { incoming ->
                    val previous = entries[incoming.intent.id]
                    if (previous == null) { imported++; incoming.copy(restored = true) } else {
                        require(previous.intent == incoming.intent) { "Intent UUID conflict" }
                        require(previous.remoteId == null || incoming.remoteId == null || previous.remoteId == incoming.remoteId) {
                            "Remote result ID conflict"
                        }
                        require(!previous.state.isTerminal() || !incoming.state.isTerminal() || previous.state == incoming.state) {
                            "Terminal result conflict"
                        }
                        val state = when {
                            previous.state.isTerminal() -> previous.state
                            incoming.state.isTerminal() -> incoming.state
                            previous.state == EnzonaIntentState.UNKNOWN || incoming.state == EnzonaIntentState.UNKNOWN -> EnzonaIntentState.UNKNOWN
                            previous.state == EnzonaIntentState.SUBMITTING || incoming.state == EnzonaIntentState.SUBMITTING -> EnzonaIntentState.SUBMITTING
                            else -> EnzonaIntentState.PREPARED
                        }
                        previous.copy(state = state, remoteId = previous.remoteId ?: incoming.remoteId, restored = true)
                    }
                },
                transactions = owner.transactions.map { incoming ->
                    val previous = history[incoming.ownerSubject to incoming.transactionUuid]
                    if (previous == null) { imported++; incoming }
                    else if (incoming.observedAt > previous.observedAt) incoming else previous
                }
            ) }
            plan.forEach { owner ->
                owner.cardLinks.forEach { dao.putCardLink(EnzonaCardLinkRow(it)) }
                owner.journal.forEach { entry ->
                    if (entry.intent.id in entries) dao.restoreState(owner.ownerSubject, entry.intent.id, entry.state, entry.remoteId)
                    else check(dao.insertIntent(EnzonaJournalRow(entry)) != -1L) { "Intent UUID conflict" }
                }
                owner.transactions.forEach { dao.putTransaction(EnzonaTransactionRow(it)) }
            }
            beforeCommit(imported)
        }
    }

    private fun <T> transaction(body: () -> T): T = database.runInTransaction(Callable { body() })
    override fun close() = database.close()

    companion object {
        const val DATABASE_NAME: String = "enzona.db"

        fun open(context: Context): RoomEnzonaRepository = openNamed(context, DATABASE_NAME)

        // Instrumentation owns only its uniquely named synthetic database.
        internal fun openNamed(context: Context, name: String): RoomEnzonaRepository {
            require(!context.isDeviceProtectedStorage)
            require(name.isNotBlank() && '/' !in name && '\\' !in name && name != "wallet.db")
            return RoomEnzonaRepository(Room.databaseBuilder(context, EnzonaDatabase::class.java, name).build())
        }

        fun openInMemory(context: Context): RoomEnzonaRepository = RoomEnzonaRepository(
            Room.inMemoryDatabaseBuilder(context, EnzonaDatabase::class.java).build(),
        )
    }
}

private fun checkOwner(owner: String) = require(owner.isNotBlank()) { "ENZONA ownerSubject is required" }
