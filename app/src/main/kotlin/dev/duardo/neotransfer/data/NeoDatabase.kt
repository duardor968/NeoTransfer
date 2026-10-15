package dev.duardo.neotransfer.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert

/** One operation as [dev.duardo.neotransfer.core.ledger.Operation.encode] writes it (no secrets). */
@Entity(tableName = "operation")
data class OperationEntity(@PrimaryKey val id: String, val createdAt: Long, val body: String)

/** One ingested PAGOxMOVIL SMS: inbox `_id`, SMSC time, reception time and body. */
@Entity(tableName = "sms")
data class SmsEntity(@PrimaryKey val id: Long, val sentAt: Long, val receivedAt: Long, val body: String)

@Entity(tableName = "card")
data class CardEntity(
    @PrimaryKey val number: String,
    val bank: String,
    val currency: String,
    val alias: String?,
    val holder: String?,
    val expiry: String?,
    val position: Int,
)

/** Phones and cards are digits only, one per line. */
@Entity(tableName = "contact")
data class ContactEntity(@PrimaryKey val id: String, val name: String, val phones: String, val cards: String)

@Entity(tableName = "service", primaryKeys = ["kind", "value"])
data class ServiceEntity(val kind: String, val value: String, val label: String?)

@Entity(tableName = "setting")
data class SettingEntity(@PrimaryKey val key: String, val value: String)

@Dao
interface NeoDao {
    @Query("SELECT * FROM operation") suspend fun operations(): List<OperationEntity>
    @Upsert suspend fun upsertOperation(row: OperationEntity)

    @Query("SELECT * FROM sms") suspend fun sms(): List<SmsEntity>
    @Upsert suspend fun upsertSms(rows: List<SmsEntity>)

    @Query("SELECT * FROM card ORDER BY position") suspend fun cards(): List<CardEntity>
    @Upsert suspend fun upsertCards(rows: List<CardEntity>)
    @Query("DELETE FROM card WHERE number = :number") suspend fun deleteCard(number: String)

    @Query("SELECT * FROM contact") suspend fun contacts(): List<ContactEntity>
    @Upsert suspend fun upsertContacts(rows: List<ContactEntity>)
    @Query("DELETE FROM contact WHERE id = :id") suspend fun deleteContact(id: String)

    @Query("SELECT * FROM service") suspend fun services(): List<ServiceEntity>
    @Upsert suspend fun upsertServices(rows: List<ServiceEntity>)
    @Query("DELETE FROM service WHERE kind = :kind AND value = :value") suspend fun deleteService(kind: String, value: String)

    @Query("SELECT * FROM setting") suspend fun settings(): List<SettingEntity>
    @Upsert suspend fun upsertSettings(rows: List<SettingEntity>)
    @Query("DELETE FROM setting WHERE `key` IN (:keys)") suspend fun deleteSettings(keys: List<String>)
}

@Database(
    entities = [OperationEntity::class, SmsEntity::class, CardEntity::class, ContactEntity::class, ServiceEntity::class, SettingEntity::class],
    version = 1,
)
abstract class NeoDatabase : RoomDatabase() {
    abstract fun dao(): NeoDao

    companion object {
        const val NAME = "neotransfer.db"

        fun open(context: Context): NeoDatabase = Room.databaseBuilder(context, NeoDatabase::class.java, NAME).build()
    }
}
