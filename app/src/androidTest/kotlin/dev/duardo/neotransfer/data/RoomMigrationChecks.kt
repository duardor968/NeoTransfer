package dev.duardo.neotransfer.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/** Opens both installed schemas through the complete migration chain and compares every original cell. */
object RoomMigrationChecks {
    fun run(context: Context, onPassed: (String) -> Unit = {}): Int {
        val testContext = context.createPackageContext("${context.packageName}.test", 0)
        for (sourceVersion in listOf(1, 2)) {
        val bytes = testContext.assets.open("dev.duardo.neotransfer.data.WalletDatabase/$sourceVersion.json").use { it.readBytes() }
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val expectedHash = if (sourceVersion == 1) "dbe7dd1bdcd26cb79573b0e94ec5aec0821ca56847b64fa21043e52be422fb28" else "fcd18804ad5f9f7b03b5163d88cf4687234990b2ac0491d4b9d8d1bd5af4de31"
        check(sha == expectedHash) { "The installed v$sourceVersion schema fixture changed" }
        val schema = JSONObject(String(bytes, Charsets.UTF_8)).getJSONObject("database")
        check(schema.getInt("version") == sourceVersion)
        val entities = schema.getJSONArray("entities").let { array -> List(array.length()) { array.getJSONObject(it) } }
        val columns = entities.associate { entity -> entity.getString("tableName") to entity.getJSONArray("fields").let { fields ->
            List(fields.length()) { fields.getJSONObject(it).getString("columnName") }
        } }
        val databaseName = "room_v${sourceVersion}_migration_${UUID.randomUUID()}.db"
        try {
            val before = context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { old ->
                old.setForeignKeyConstraintsEnabled(true)
                old.beginTransaction()
                try {
                    entities.forEach { entity ->
                        val table = entity.getString("tableName")
                        old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                        val indices = entity.optJSONArray("indices")
                        if (indices != null) for (i in 0 until indices.length())
                            old.execSQL(indices.getJSONObject(i).getString("createSql").replace("\${TABLE_NAME}", table))
                    }
                    val setup = schema.getJSONArray("setupQueries")
                    for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
                    seed(old, entities)
                    old.version = sourceVersion
                    old.setTransactionSuccessful()
                } finally { old.endTransaction() }
                capture(old, columns)
            }
            RoomWalletRepository.open(context, databaseName).use { repository ->
                val state = repository.snapshot()
                check(repository.database.openHelper.readableDatabase.version == 3)
                check(state.identities.single().label == "Perfil original v1")
                check(state.registrations.single().credentialAlias == "original-vault-alias")
                check(state.cards.single().number == "0000000000000001" && state.contacts.single().phones.single().number == "50000001")
                check(state.cards.single().holderName == (if (sourceVersion == 2) "PERSONA ORIGINAL" else null) && state.cards.single().expiry == (if (sourceVersion == 2) "01/20" else null) && state.cards.single().label == "Débito")
                check(state.operations.single().status == OperationStatus.AWAITING_CONFIRMATION && state.operations.single().httpEvidence == null)
                check(state.balances.single().available == "420.25" && state.usedReferences.single().reference == "USED01")
                check(repository.pendingTransferNotifications().single().id == "receipt")
                check(columns.size == 21 && state.fuelCoupons.isEmpty() && state.fuelObservations.isEmpty())
                val migrated = repository.database.openHelper.readableDatabase
                columns.forEach { (table, names) ->
                    migrated.query("SELECT ${names.joinToString(",") { "`$it`" }} FROM `$table` ORDER BY rowid").use { cursor ->
                        val actual = buildList { while (cursor.moveToNext()) add(List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) }) }
                        check(actual == before[table]) { "Opening the installed schema changed original rows in $table" }
                    }
                }
                migrated.query("PRAGMA foreign_key_check").use { check(it.count == 0) }
                migrated.query("PRAGMA integrity_check").use { check(it.moveToFirst() && it.getString(0) == "ok") }
            }
            RoomWalletRepository.open(context, databaseName).use { reopened ->
                check(reopened.snapshot().operations.single().status == OperationStatus.AWAITING_CONFIRMATION)
                check(reopened.database.openHelper.readableDatabase.version == 3)
                check(reopened.snapshot().cards.single().holderName == (if (sourceVersion == 2) "PERSONA ORIGINAL" else null))
            }
            onPassed("Room v$sourceVersion to v3 preserves all 21 original tables, card metadata, vault aliases and queued requests")
        } finally { check(context.deleteDatabase(databaseName)) }
        }
        return 2
    }

    private fun capture(database: SQLiteDatabase, columns: Map<String, List<String>>): Map<String, List<List<String?>>> =
        columns.mapValues { (table, names) -> database.rawQuery("SELECT ${names.joinToString(",") { "`$it`" }} FROM `$table` ORDER BY rowid", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) }) }
        } }

    private fun seed(database: SQLiteDatabase, entities: List<JSONObject>) {
        fun insert(table: String, vararg values: Pair<String, Any?>) {
            val fields = entities.single { it.getString("tableName") == table }.getJSONArray("fields")
            val content = ContentValues()
            for (i in 0 until fields.length()) fields.getJSONObject(i).let { field ->
                if (field.optBoolean("notNull", false)) {
                    if (field.getString("affinity") == "INTEGER") content.put(field.getString("columnName"), 0L)
                    else content.put(field.getString("columnName"), "")
                }
            }
            values.forEach { (key, value) -> when (value) {
                null -> content.putNull(key)
                is Int -> content.put(key, value)
                is Long -> content.put(key, value)
                is Boolean -> content.put(key, value)
                is String -> content.put(key, value)
                else -> error("Unsupported migration fixture field")
            } }
            check(database.insertOrThrow(table, null, content) != -1L)
        }
        insert("identities", "id" to "owner", "label" to "Perfil original v1")
        insert("registrations", "id" to "registration", "identityId" to "owner", "providerId" to "BANDEC", "profileId" to "PERSONAL",
            "bankCode" to "02", "subscriptionId" to 7, "label" to "BANDEC", "enabled" to true, "credentialAlias" to "original-vault-alias")
        insert("accounts", "id" to "account", "registrationId" to "registration", "number" to "00000001", "label" to "Ahorro", "currency" to "CUP")
        insert("cards", "id" to "card", "registrationId" to "registration", "accountId" to "account", "number" to "0000000000000001", "label" to "Débito", "currency" to "CUP", "isDefault" to true)
        if (entities.single { it.getString("tableName") == "cards" }.getJSONArray("fields").toString().contains("holderName")) {
            database.execSQL("UPDATE cards SET holderName = 'PERSONA ORIGINAL', expiry = '01/20' WHERE id = 'card'")
        }
        insert("contacts", "id" to "contact", "name" to "Contacto original", "favorite" to true)
        insert("contact_phones", "contactId" to "contact", "position" to 0, "number" to "50000001", "label" to "Móvil")
        insert("contact_cards", "contactId" to "contact", "position" to 0, "number" to "0000000000000002", "label" to "CUP", "bankCode" to "02")
        insert("saved_services", "id" to "service", "kind" to "service.telephone", "label" to "Teléfono", "identifier" to "00000000000001", "fieldKey" to "invoice")
        insert("operations", "id" to "operation", "kind" to "TRANSFER", "specId" to "bank.transfer", "providerId" to "BANDEC", "profileId" to "PERSONAL",
            "bankCode" to "02", "subscriptionId" to 7, "destination" to "0000000000000002", "amount" to "10.00", "currency" to "CUP",
            "startedAt" to 1000L, "updatedAt" to 1001L, "source" to "0000", "status" to "AWAITING_CONFIRMATION", "parameters" to "{}", "reviewRequired" to true)
        insert("events", "id" to "event", "canonicalEventId" to "event", "bodyHash" to "synthetic-original-hash", "source" to "BROADCAST",
            "sourceId" to "original-pdu", "sender" to "PAGOxMOVIL", "body" to "Synthetic original receipt", "receivedAt" to 1100L, "subscriptionId" to 7, "evidenceEligible" to true)
        insert("receipts", "id" to "receipt", "eventId" to "event", "bankCode" to "02", "subscriptionId" to 7, "kind" to "RECEIVED",
            "reference" to "RECEIVED01", "normalizedReference" to "RECEIVED01", "amount" to "7.50", "currency" to "CUP", "party" to "50000001")
        insert("movements", "id" to "movement", "receiptId" to "receipt", "kind" to "RECEIVED", "amount" to "7.50", "currency" to "CUP", "occurredAt" to 1100L, "incoming" to true)
        insert("balances", "id" to "balance", "bankCode" to "02", "subscriptionId" to 7, "at" to 1200L, "available" to "420.25", "currency" to "CUP", "ledger" to "425.00")
        insert("used_references", "bankCode" to "02", "reference" to "USED01", "normalizedReference" to "USED01")
        insert("wallet_settings", "singleton" to 1, "activeBankCode" to "02", "subscriptionId" to 7, "selectedRegistrationId" to "registration", "selectedCardId" to "card")
        insert("refresh_request", "singleton" to 1, "bankCode" to "02", "subscriptionId" to 7, "dueAt" to 1300L)
        insert("metadata", "key" to "revision", "value" to "3")
        insert("legacy_archive", "key" to "recipients", "safeValue" to "[]")
        insert("history_entries", "id" to "history", "bankCode" to "02", "subscriptionId" to 7, "postedOn" to "2026-09-23", "service" to "Transferencia",
            "incoming" to true, "amount" to "7.50", "currency" to "CUP", "reference" to "RECEIVED01", "normalizedReference" to "RECEIVED01", "receiptId" to "receipt")
        insert("history_observations", "eventId" to "event", "position" to 0, "entryId" to "history")
        insert("notification_outbox", "receiptId" to "receipt", "queuedAt" to 1100L)
    }
}
