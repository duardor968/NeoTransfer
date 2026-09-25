package dev.duardo.neotransfer.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import dev.duardo.neotransfer.core.fuel.FuelCoupon
import dev.duardo.neotransfer.core.fuel.ProtectedFuelEnvelope

@Entity(tableName = "identities", primaryKeys = ["id"])
internal data class IdentityRow(@Embedded val value: IdentityRecord)

@Entity(tableName = "registrations", primaryKeys = ["id"],
    foreignKeys = [ForeignKey(entity = IdentityRow::class, parentColumns = ["id"], childColumns = ["identityId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("identityId")])
internal data class RegistrationRow(@Embedded val value: RegistrationRecord)

@Entity(tableName = "accounts", primaryKeys = ["id"],
    foreignKeys = [ForeignKey(entity = RegistrationRow::class, parentColumns = ["id"], childColumns = ["registrationId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("registrationId")])
internal data class AccountRow(@Embedded val value: AccountRecord)

@Entity(tableName = "cards", primaryKeys = ["id"],
    foreignKeys = [ForeignKey(entity = RegistrationRow::class, parentColumns = ["id"], childColumns = ["registrationId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = AccountRow::class, parentColumns = ["id"], childColumns = ["accountId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("registrationId"), Index("accountId")])
internal data class CardRow(@Embedded val value: CardRecord)

@Entity(tableName = "contacts")
internal data class ContactRow(@PrimaryKey val id: String, val name: String, val favorite: Boolean)

@Entity(tableName = "contact_phones", primaryKeys = ["contactId", "position"],
    foreignKeys = [ForeignKey(entity = ContactRow::class, parentColumns = ["id"], childColumns = ["contactId"], onDelete = ForeignKey.CASCADE)])
internal data class PhoneRow(val contactId: String, val position: Int, val number: String, val label: String)

@Entity(tableName = "contact_cards", primaryKeys = ["contactId", "position"],
    foreignKeys = [ForeignKey(entity = ContactRow::class, parentColumns = ["id"], childColumns = ["contactId"], onDelete = ForeignKey.CASCADE)])
internal data class ContactCardRow(val contactId: String, val position: Int, val number: String, val label: String, val bankCode: String?)

@Entity(tableName = "saved_services", primaryKeys = ["id"],
    foreignKeys = [ForeignKey(entity = RegistrationRow::class, parentColumns = ["id"], childColumns = ["registrationId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("registrationId")])
internal data class ServiceRow(@Embedded val value: SavedServiceRecord)

// Operations retain their historical registration ID when the user removes an access.
@Entity(tableName = "operations", primaryKeys = ["id"], indices = [Index("status"), Index("registrationId")])
internal data class OperationRow(@Embedded val value: OperationRecord)

@Entity(tableName = "events", primaryKeys = ["id"], indices = [Index("canonicalEventId"), Index("bodyHash"), Index(value = ["source", "sourceId"], unique = true)])
internal data class EventRow(@Embedded val value: EventRecord, val bodyHash: String)

@Entity(tableName = "receipts", primaryKeys = ["id"],
    foreignKeys = [ForeignKey(entity = EventRow::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["eventId"], unique = true), Index("normalizedReference"), Index("operationId")])
internal data class ReceiptRow(@Embedded val value: ReceiptRecord, val normalizedReference: String?)

@Entity(tableName = "movements", primaryKeys = ["id"],
    foreignKeys = [ForeignKey(entity = ReceiptRow::class, parentColumns = ["id"], childColumns = ["receiptId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index(value = ["receiptId"], unique = true)])
internal data class MovementRow(@Embedded val value: MovementRecord)

@Entity(tableName = "notification_outbox",
    foreignKeys = [ForeignKey(entity = ReceiptRow::class, parentColumns = ["id"], childColumns = ["receiptId"], onDelete = ForeignKey.CASCADE)])
internal data class NotificationOutboxRow(@PrimaryKey val receiptId: String, val queuedAt: Long)

@Entity(tableName = "history_entries", primaryKeys = ["id"],
    foreignKeys = [ForeignKey(entity = ReceiptRow::class, parentColumns = ["id"], childColumns = ["receiptId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("normalizedReference"), Index("receiptId")])
internal data class HistoryRow(@Embedded val value: HistoryEntryRecord, val normalizedReference: String?)

@Entity(tableName = "history_observations", primaryKeys = ["eventId", "position"],
    foreignKeys = [ForeignKey(entity = EventRow::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = HistoryRow::class, parentColumns = ["id"], childColumns = ["entryId"], onDelete = ForeignKey.RESTRICT)],
    indices = [Index("entryId")])
internal data class HistoryObservationRow(@Embedded val value: HistoryObservationRecord)

@Entity(tableName = "balances", primaryKeys = ["id"], indices = [Index(value = ["bankCode", "subscriptionId"])])
internal data class BalanceRow(@Embedded val value: BalanceRecord)

@Entity(tableName = "used_references", primaryKeys = ["bankCode", "normalizedReference"])
internal data class UsedReferenceRow(@Embedded val value: UsedReference, val normalizedReference: String)

@Entity(tableName = "wallet_settings")
internal data class SettingsRow(@PrimaryKey val singleton: Int = 1, @Embedded val value: WalletSettings)

@Entity(tableName = "refresh_request")
internal data class RefreshRow(@PrimaryKey val singleton: Int = 1, @Embedded val value: RefreshRequest)

@Entity(tableName = "metadata")
internal data class MetadataRow(@PrimaryKey val key: String, val value: String)

@Entity(tableName = "legacy_archive")
internal data class LegacyArchiveRow(@PrimaryKey val key: String, val safeValue: String, val issue: String?)

@Entity(tableName = "fuel_coupons", primaryKeys = ["id"], indices = [Index(value = ["serial"], unique = true), Index("sourceEventId")],
    foreignKeys = [ForeignKey(entity = EventRow::class, parentColumns = ["id"], childColumns = ["sourceEventId"], onDelete = ForeignKey.RESTRICT)])
internal data class FuelCouponRow(@Embedded val value: FuelCoupon)

@Entity(tableName = "fuel_secrets", primaryKeys = ["couponId"],
    foreignKeys = [ForeignKey(entity = FuelCouponRow::class, parentColumns = ["id"], childColumns = ["couponId"], onDelete = ForeignKey.CASCADE)])
internal data class FuelSecretRow(@Embedded val value: ProtectedFuelEnvelope)

@Entity(tableName = "fuel_observations", primaryKeys = ["eventId", "position"], indices = [Index("couponId"), Index("receiptId")],
    foreignKeys = [ForeignKey(entity = EventRow::class, parentColumns = ["id"], childColumns = ["eventId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = FuelCouponRow::class, parentColumns = ["id"], childColumns = ["couponId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ReceiptRow::class, parentColumns = ["id"], childColumns = ["receiptId"], onDelete = ForeignKey.RESTRICT)])
internal data class FuelObservationRow(@Embedded val value: FuelObservation)

internal class WalletConverters {
    @TypeConverter fun fuelEvidenceToJson(value: FuelPurchaseEvidence?): String? = value?.let { fuelEvidenceJson(it).toString() }
    @TypeConverter fun jsonToFuelEvidence(value: String?): FuelPurchaseEvidence? = value?.let { readFuelEvidence(JSONObject(it)) }
    @TypeConverter fun bulevarEvidenceToJson(value: BulevarHttpEvidence?): String? = value?.let { evidence -> JSONObject().apply {
        put("requestId", evidence.requestId); put("specId", evidence.specId); put("httpStatus", evidence.httpStatus)
        put("responseCode", evidence.responseCode); put("receivedAt", evidence.receivedAt); put("reference", evidence.reference)
    }.toString() }
    @TypeConverter fun jsonToBulevarEvidence(value: String?): BulevarHttpEvidence? = value?.let { JSONObject(it).run {
        BulevarHttpEvidence(getString("requestId"), getString("specId"), getInt("httpStatus"), getString("responseCode"),
            getLong("receivedAt"), nullableString("reference"))
    } }

    @TypeConverter fun parametersToJson(value: Map<String, String>): String = JSONObject(value).toString()
    @TypeConverter fun jsonToParameters(value: String): Map<String, String> = JSONObject(value).run {
        keys().asSequence().associateWith(::getString)
    }

    @TypeConverter fun qrToJson(value: QrDetails?): String? = value?.let { q -> JSONObject().apply {
        put("transactionId", q.transactionId); put("provider", q.provider); put("amount", q.amount)
        put("currency", q.currency); put("auxiliary", q.auxiliary); put("description", q.description)
        put("descriptionHint", q.descriptionHint); put("qrId", q.qrId)
        put("validFrom", q.validFrom); put("validThrough", q.validThrough)
    }.toString() }

    @TypeConverter fun jsonToQr(value: String?): QrDetails? = value?.let { JSONObject(it).run {
        QrDetails(getString("transactionId"), getString("provider"), getString("amount"), getString("currency"),
            getString("auxiliary"), getString("description"), getString("descriptionHint"),
            nullableString("qrId"), nullableString("validFrom"), nullableString("validThrough"))
    } }
}

internal fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key).takeIf(String::isNotEmpty)

@Dao
internal interface WalletDao {
    @Query("SELECT * FROM identities ORDER BY id") fun identities(): List<IdentityRow>
    @Query("SELECT * FROM registrations ORDER BY id") fun registrations(): List<RegistrationRow>
    @Query("SELECT * FROM accounts ORDER BY id") fun accounts(): List<AccountRow>
    @Query("SELECT * FROM cards ORDER BY id") fun cards(): List<CardRow>
    @Query("SELECT * FROM contacts ORDER BY name COLLATE NOCASE, id") fun contacts(): List<ContactRow>
    @Query("SELECT * FROM contact_phones ORDER BY contactId, position") fun phones(): List<PhoneRow>
    @Query("SELECT * FROM contact_cards ORDER BY contactId, position") fun contactCards(): List<ContactCardRow>
    @Query("SELECT * FROM saved_services ORDER BY label COLLATE NOCASE, id") fun services(): List<ServiceRow>
    @Query("SELECT * FROM operations ORDER BY startedAt DESC, id") fun operations(): List<OperationRow>
    @Query("SELECT * FROM operations WHERE id = :id") fun operation(id: String): OperationRow?
    @Query("SELECT * FROM events ORDER BY receivedAt DESC, id") fun events(): List<EventRow>
    @Query("SELECT * FROM events WHERE id = :id") fun event(id: String): EventRow?
    @Query("SELECT EXISTS(SELECT 1 FROM events WHERE canonicalEventId = :canonicalId AND source = :source)")
    fun hasDeliverySource(canonicalId: String, source: EventSource): Boolean
    @Query("SELECT * FROM events WHERE source = :source AND sourceId = :sourceId LIMIT 1") fun delivery(source: EventSource, sourceId: String): EventRow?
    @Query("SELECT * FROM events WHERE bodyHash = :hash AND receivedAt BETWEEN :after AND :before ORDER BY receivedAt DESC")
    fun matchingEvents(hash: String, after: Long, before: Long): List<EventRow>
    @Query("SELECT * FROM receipts ORDER BY id") fun receipts(): List<ReceiptRow>
    @Query("SELECT * FROM receipts WHERE id = :id") fun receipt(id: String): ReceiptRow?
    @Query("SELECT * FROM receipts WHERE eventId = :eventId") fun receiptForEvent(eventId: String): ReceiptRow?
    @Query("SELECT * FROM receipts WHERE normalizedReference = :reference") fun receiptsWithReference(reference: String): List<ReceiptRow>
    @Query("SELECT * FROM movements ORDER BY occurredAt DESC, id") fun movements(): List<MovementRow>
    @Query("SELECT receipts.* FROM receipts INNER JOIN notification_outbox ON receipts.id = notification_outbox.receiptId WHERE receipts.referenceConflict = 0 ORDER BY notification_outbox.queuedAt, receipts.id")
    fun pendingTransferNotifications(): List<ReceiptRow>
    @Query("DELETE FROM notification_outbox WHERE receiptId = :receiptId") fun acknowledgeTransferNotification(receiptId: String)
    @Query("SELECT * FROM history_entries ORDER BY postedOn DESC, id") fun histories(): List<HistoryRow>
    @Query("SELECT * FROM history_entries WHERE id = :id") fun history(id: String): HistoryRow?
    @Query("SELECT * FROM history_entries WHERE normalizedReference = :reference") fun historiesWithReference(reference: String): List<HistoryRow>
    @Query("SELECT * FROM history_observations ORDER BY eventId, position") fun historyObservations(): List<HistoryObservationRow>
    @Query("SELECT * FROM history_observations WHERE eventId = :eventId ORDER BY position") fun historyObservations(eventId: String): List<HistoryObservationRow>
    @Query("SELECT * FROM history_observations WHERE entryId = :entryId") fun observationsOfHistory(entryId: String): List<HistoryObservationRow>
    @Query("SELECT * FROM balances ORDER BY bankCode, subscriptionId, id") fun balances(): List<BalanceRow>
    @Query("SELECT * FROM used_references ORDER BY bankCode, normalizedReference") fun usedReferences(): List<UsedReferenceRow>
    @Query("SELECT * FROM used_references WHERE bankCode = :bankCode AND normalizedReference = :reference") fun usedReference(bankCode: String, reference: String): UsedReferenceRow?
    @Query("SELECT * FROM wallet_settings WHERE singleton = 1") fun settings(): SettingsRow?
    @Query("SELECT * FROM refresh_request WHERE singleton = 1") fun refresh(): RefreshRow?
    @Query("SELECT value FROM metadata WHERE `key` = :key") fun metadata(key: String): String?
    @Query("SELECT value FROM metadata WHERE `key` = 'revision'") fun observeRevision(): Flow<String?>
    @Query("SELECT * FROM legacy_archive ORDER BY `key`") fun legacyArchive(): List<LegacyArchiveRow>
    @Query("SELECT * FROM fuel_coupons ORDER BY observedAt DESC, id") fun fuelCoupons(): List<FuelCouponRow>
    @Query("SELECT * FROM fuel_coupons WHERE id = :id") fun fuelCoupon(id: String): FuelCouponRow?
    @Query("SELECT * FROM fuel_secrets WHERE couponId = :id") fun fuelSecret(id: String): FuelSecretRow?
    @Query("SELECT * FROM fuel_observations ORDER BY eventId, position") fun fuelObservations(): List<FuelObservationRow>
    @Query("SELECT * FROM fuel_observations WHERE eventId = :eventId ORDER BY position") fun fuelObservations(eventId: String): List<FuelObservationRow>
    @Upsert fun put(value: IdentityRow)
    @Upsert fun put(value: RegistrationRow)
    @Upsert fun put(value: AccountRow)
    @Upsert fun put(value: CardRow)
    @Upsert fun put(value: ContactRow)
    @Insert fun put(value: PhoneRow)
    @Insert fun put(value: ContactCardRow)
    @Upsert fun put(value: ServiceRow)
    @Upsert fun put(value: OperationRow)
    @Insert fun put(value: EventRow)
    @Upsert fun put(value: ReceiptRow)
    @Insert fun put(value: MovementRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun enqueue(value: NotificationOutboxRow): Long
    @Upsert fun put(value: HistoryRow)
    @Upsert fun put(value: HistoryObservationRow)
    @Upsert fun put(value: BalanceRow)
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun claim(value: UsedReferenceRow): Long
    @Upsert fun put(value: SettingsRow)
    @Upsert fun put(value: RefreshRow)
    @Upsert fun put(value: MetadataRow)
    @Insert fun put(value: LegacyArchiveRow)
    @Upsert fun put(value: FuelCouponRow)
    @Upsert fun put(value: FuelSecretRow)
    @Upsert fun put(value: FuelObservationRow)

    @Query("DELETE FROM identities WHERE id = :id") fun deleteIdentity(id: String)
    @Query("DELETE FROM registrations WHERE id = :id") fun deleteRegistration(id: String)
    @Query("DELETE FROM accounts WHERE id = :id") fun deleteAccount(id: String)
    @Query("DELETE FROM cards WHERE id = :id") fun deleteCard(id: String)
    @Query("DELETE FROM contacts WHERE id = :id") fun deleteContact(id: String)
    @Query("DELETE FROM contact_phones WHERE contactId = :id") fun deletePhones(id: String)
    @Query("DELETE FROM contact_cards WHERE contactId = :id") fun deleteContactCards(id: String)
    @Query("DELETE FROM saved_services WHERE id = :id") fun deleteService(id: String)
    @Query("DELETE FROM balances WHERE bankCode = :bankCode AND subscriptionId = :subscriptionId") fun deleteBalances(bankCode: String, subscriptionId: Int)
    @Query("DELETE FROM refresh_request") fun deleteRefresh()
}

@Database(entities = [IdentityRow::class, RegistrationRow::class, AccountRow::class, CardRow::class,
    ContactRow::class, PhoneRow::class, ContactCardRow::class, ServiceRow::class, OperationRow::class,
    EventRow::class, ReceiptRow::class, MovementRow::class, BalanceRow::class, UsedReferenceRow::class,
    SettingsRow::class, RefreshRow::class, MetadataRow::class, LegacyArchiveRow::class,
    HistoryRow::class, HistoryObservationRow::class, NotificationOutboxRow::class, FuelCouponRow::class, FuelSecretRow::class, FuelObservationRow::class],
    version = 3, exportSchema = true, autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)])
@TypeConverters(WalletConverters::class)
internal abstract class WalletDatabase : RoomDatabase() {
    abstract fun wallet(): WalletDao
}
