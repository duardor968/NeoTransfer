package dev.duardo.neotransfer.data

import dev.duardo.neotransfer.core.ledger.Operation
import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Contact
import dev.duardo.neotransfer.core.model.Currency
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.core.model.ServiceKind
import dev.duardo.neotransfer.core.model.ServiceReference
import dev.duardo.neotransfer.core.sms.SmsParser
import dev.duardo.neotransfer.core.sms.SmsRecord
import java.time.Instant

/** An own card plus what the wallet shows besides the number. */
data class WalletCard(val card: OwnCard, val holder: String? = null, val expiry: String? = null)

/** One ingested bank SMS. [sentAt] orders it ([SmsRecord]), [receivedAt] is the inbox `date`. */
data class StoredSms(val id: Long, val sentAt: Instant, val receivedAt: Instant, val body: String) {
    fun record(): SmsRecord = SmsRecord(id, sentAt, receivedAt, SmsParser.parse(body))
}

enum class Theme { SYSTEM, LIGHT, DARK }

data class Settings(val theme: Theme = Theme.SYSTEM, val creditNotifications: Boolean = true, val resultNotifications: Boolean = true)

/**
 * User settings plus the engine's own facts: when each bank key was saved, how the inbox was last read in full
 * ([inboxVersion]: 0 never, 1 PAGOxMOVIL only, 2 with BANDEC's alerts), the last backup.
 */
data class Preferences(
    val settings: Settings = Settings(),
    val keySavedAt: Map<Bank, Instant> = emptyMap(),
    val inboxVersion: Int = 0,
    val lastBackup: Instant? = null,
)

data class Snapshot(
    val operations: List<Operation>,
    val sms: List<StoredSms>,
    val cards: List<WalletCard>,
    val contacts: List<Contact>,
    val services: List<ServiceReference>,
    val preferences: Preferences,
)

/** Durable state of the app. Each call is one write; the controller keeps the working copy in memory. */
interface Store {
    suspend fun load(): Snapshot
    suspend fun saveOperation(operation: Operation)
    suspend fun saveSms(rows: List<StoredSms>)
    /** The whole wallet, in display order. */
    suspend fun saveCards(cards: List<WalletCard>)
    suspend fun deleteCard(number: String)
    suspend fun saveContacts(contacts: List<Contact>)
    suspend fun deleteContact(id: String)
    suspend fun saveServices(services: List<ServiceReference>)
    suspend fun deleteService(service: ServiceReference)
    suspend fun savePreferences(preferences: Preferences)
}

class RoomStore(private val dao: NeoDao) : Store {
    override suspend fun load() = Snapshot(
        dao.operations().map { it.toOperation() },
        dao.sms().map { it.toStoredSms() },
        dao.cards().map { it.toWalletCard() },
        dao.contacts().map { it.toContact() },
        dao.services().map { it.toServiceReference() },
        dao.settings().toPreferences(),
    )

    override suspend fun saveOperation(operation: Operation) = dao.upsertOperation(operation.toEntity())
    override suspend fun saveSms(rows: List<StoredSms>) = dao.upsertSms(rows.map { it.toEntity() })
    override suspend fun saveCards(cards: List<WalletCard>) = dao.upsertCards(cards.mapIndexed { index, card -> card.toEntity(index) })
    override suspend fun deleteCard(number: String) = dao.deleteCard(number)
    override suspend fun saveContacts(contacts: List<Contact>) = dao.upsertContacts(contacts.map { it.toEntity() })
    override suspend fun deleteContact(id: String) = dao.deleteContact(id)
    override suspend fun saveServices(services: List<ServiceReference>) = dao.upsertServices(services.map { it.toEntity() })
    override suspend fun deleteService(service: ServiceReference) = dao.deleteService(service.kind.name, service.value)

    override suspend fun savePreferences(preferences: Preferences) {
        val rows = preferences.toRows()
        dao.upsertSettings(rows)
        dao.deleteSettings(Bank.entries.map { KEY_SAVED + it.name } + INBOX_IMPORTED - rows.map { it.key }.toSet())
    }
}

internal fun Operation.toEntity() = OperationEntity(id, createdAt.toEpochMilli(), Operation.encode(this))
internal fun OperationEntity.toOperation() = Operation.decode(body)

internal fun StoredSms.toEntity() = SmsEntity(id, sentAt.toEpochMilli(), receivedAt.toEpochMilli(), body)
internal fun SmsEntity.toStoredSms() = StoredSms(id, Instant.ofEpochMilli(sentAt), Instant.ofEpochMilli(receivedAt), body)

internal fun WalletCard.toEntity(position: Int) =
    CardEntity(card.number, card.bank.name, card.currency.name, card.alias, holder, expiry, position)
internal fun CardEntity.toWalletCard() =
    WalletCard(OwnCard(number, Bank.valueOf(bank), Currency.valueOf(currency), alias), holder, expiry)

internal fun Contact.toEntity() =
    ContactEntity(id, name, phones.joinToString("\n"), cards.joinToString("\n"), labels.entries.joinToString("\n") { "${it.key}\t${it.value}" })
internal fun ContactEntity.toContact() =
    Contact(id, name, lines(phones), lines(cards), lines(labels).associate { it.substringBefore('\t') to it.substringAfter('\t') })
private fun lines(value: String) = value.split('\n').filter(String::isNotEmpty)

internal fun ServiceReference.toEntity() = ServiceEntity(kind.name, value, label)
internal fun ServiceEntity.toServiceReference() = ServiceReference(ServiceKind.valueOf(kind), value, label)

private const val THEME = "theme"
private const val CREDIT_NOTIFICATIONS = "notify.credits"
private const val RESULT_NOTIFICATIONS = "notify.results"
private const val KEY_SAVED = "key.saved."
/** Before [INBOX_VERSION]: `true` meant version 1. */
private const val INBOX_IMPORTED = "inbox.imported"
private const val INBOX_VERSION = "inbox.version"
private const val LAST_BACKUP = "backup.last"

internal fun Preferences.toRows(): List<SettingEntity> = listOf(
    SettingEntity(THEME, settings.theme.name),
    SettingEntity(CREDIT_NOTIFICATIONS, settings.creditNotifications.toString()),
    SettingEntity(RESULT_NOTIFICATIONS, settings.resultNotifications.toString()),
    SettingEntity(INBOX_VERSION, inboxVersion.toString()),
) + listOfNotNull(lastBackup?.let { SettingEntity(LAST_BACKUP, it.toEpochMilli().toString()) }) +
    keySavedAt.map { (bank, at) -> SettingEntity(KEY_SAVED + bank.name, at.toEpochMilli().toString()) }

internal fun List<SettingEntity>.toPreferences(): Preferences {
    val values = associate { it.key to it.value }
    return Preferences(
        Settings(
            values[THEME]?.let(Theme::valueOf) ?: Theme.SYSTEM,
            values[CREDIT_NOTIFICATIONS]?.toBooleanStrict() ?: true,
            values[RESULT_NOTIFICATIONS]?.toBooleanStrict() ?: true,
        ),
        Bank.entries.mapNotNull { bank -> values[KEY_SAVED + bank.name]?.let { bank to Instant.ofEpochMilli(it.toLong()) } }.toMap(),
        values[INBOX_VERSION]?.toInt() ?: if (values[INBOX_IMPORTED]?.toBooleanStrict() == true) 1 else 0,
        values[LAST_BACKUP]?.let { Instant.ofEpochMilli(it.toLong()) },
    )
}
