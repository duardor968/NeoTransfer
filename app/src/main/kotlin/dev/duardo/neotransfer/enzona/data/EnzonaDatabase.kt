package dev.duardo.neotransfer.enzona.data

import androidx.room.Database
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "read_cache", primaryKeys = ["ownerSubject", "kind", "resourceId"])
internal data class EnzonaCacheRow(@Embedded val value: EnzonaCachedRead)

@Entity(tableName = "card_links", primaryKeys = ["ownerSubject", "cardUuid"])
internal data class EnzonaCardLinkRow(@Embedded val value: EnzonaCardLink)

@Entity(tableName = "intent_journal", primaryKeys = ["id"], indices = [Index("ownerSubject")])
internal data class EnzonaJournalRow(@Embedded val value: EnzonaJournalEntry)

@Entity(tableName = "transaction_history", primaryKeys = ["ownerSubject", "transactionUuid"])
internal data class EnzonaTransactionRow(@Embedded val value: EnzonaStoredTransaction)

@Dao
internal interface EnzonaDao {
    // Backup inventory only; payload reads still require the exact owner.
    @Query("SELECT ownerSubject FROM card_links UNION SELECT ownerSubject FROM intent_journal UNION SELECT ownerSubject FROM transaction_history ORDER BY ownerSubject")
    fun localOwners(): List<String>

    @Query("SELECT * FROM read_cache WHERE ownerSubject = :owner AND kind = :kind AND resourceId = :resourceId")
    fun readCache(owner: String, kind: EnzonaReadKind, resourceId: String): EnzonaCacheRow?

    @Query("SELECT * FROM read_cache WHERE ownerSubject = :owner AND kind = :kind ORDER BY resourceId")
    fun cachedReads(owner: String, kind: EnzonaReadKind): List<EnzonaCacheRow>

    @Upsert fun putCache(row: EnzonaCacheRow)

    @Query("SELECT * FROM transaction_history WHERE ownerSubject = :owner ORDER BY observedAt DESC, transactionUuid")
    fun transactions(owner: String): List<EnzonaTransactionRow>

    @Query("SELECT * FROM transaction_history WHERE ownerSubject = :owner AND transactionUuid = :id")
    fun transaction(owner: String, id: String): EnzonaTransactionRow?

    @Upsert fun putTransaction(row: EnzonaTransactionRow)

    @Query("SELECT * FROM card_links WHERE ownerSubject = :owner ORDER BY cardUuid")
    fun cardLinks(owner: String): List<EnzonaCardLinkRow>

    @Upsert fun putCardLink(row: EnzonaCardLinkRow)

    @Query("SELECT * FROM intent_journal WHERE ownerSubject = :owner AND id = :id")
    fun intent(owner: String, id: String): EnzonaJournalRow?

    @Query("SELECT * FROM intent_journal WHERE ownerSubject = :owner ORDER BY preparedAt DESC, id")
    fun journal(owner: String): List<EnzonaJournalRow>

    @Query("SELECT * FROM intent_journal WHERE ownerSubject = :owner ORDER BY preparedAt DESC, id")
    fun observeJournal(owner: String): Flow<List<EnzonaJournalRow>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertIntent(row: EnzonaJournalRow): Long

    @Query("UPDATE intent_journal SET state = 'SUBMITTING' WHERE ownerSubject = :owner AND id = :id AND state = 'PREPARED' AND restored = 0")
    fun tryBeginSubmission(owner: String, id: String): Int

    @Query("UPDATE intent_journal SET state = 'UNKNOWN' WHERE ownerSubject = :owner AND id = :id AND state = 'SUBMITTING'")
    fun markTimedOut(owner: String, id: String): Int

    // No query can edit reviewed intent fields, replace journal rows, or delete user money data.
    @Query("UPDATE intent_journal SET state = :state, remoteId = :remoteId WHERE ownerSubject = :owner AND id = :id")
    fun recordResult(owner: String, id: String, state: EnzonaIntentState, remoteId: String?)

    @Query("UPDATE intent_journal SET state = :state, remoteId = :remoteId, restored = 1 WHERE ownerSubject = :owner AND id = :id")
    fun restoreState(owner: String, id: String, state: EnzonaIntentState, remoteId: String?)
}

@Database(
    entities = [EnzonaCacheRow::class, EnzonaCardLinkRow::class, EnzonaJournalRow::class, EnzonaTransactionRow::class],
    version = 1,
    exportSchema = true,
)
internal abstract class EnzonaDatabase : RoomDatabase() {
    abstract fun enzona(): EnzonaDao
}
