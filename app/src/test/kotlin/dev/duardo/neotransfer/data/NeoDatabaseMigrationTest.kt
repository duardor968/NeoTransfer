package dev.duardo.neotransfer.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import dev.duardo.neotransfer.core.model.Agency
import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Contact
import dev.duardo.neotransfer.core.model.ContactRecord
import dev.duardo.neotransfer.core.model.ContactSync
import dev.duardo.neotransfer.core.model.MiTransfer
import dev.duardo.neotransfer.core.model.OwnCard
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Older databases open as the current version: every row stays; 2 → 3 gives the Clásicas their table, 3 → 4 the contacts
 * the sync's columns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NeoDatabaseMigrationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val name = "migration.db"

    @Test fun `version 2 migrates keeping its rows`() = runBlocking {
        context.deleteDatabase(name)
        create(2,
            "INSERT INTO card VALUES ('9205000000000001', 'BPA', 'CUP', NULL, 'ANA PRUEBA', '03/29', 0)",
            "INSERT INTO setting VALUES ('key.saved.BPA', '1700000000000')",
            "INSERT INTO sms VALUES (7, 1700000000000, 1700000001000, 'Banco Popular de Ahorro: La consulta de saldo fue completada. Saldo Disponible: CR 1.00 CUP')",
        )
        val database = Room.databaseBuilder(context, NeoDatabase::class.java, name).build()
        try {
            // Room checks the migrated schema against the current one as it opens.
            val store = RoomStore(database.dao())
            val snapshot = store.load()
            assertEquals(listOf(WalletCard(OwnCard("9205000000000001", Bank.BPA), "ANA PRUEBA", "03/29")), snapshot.cards)
            assertEquals(mapOf<Agency, Instant>(Bank.BPA to Instant.ofEpochMilli(1_700_000_000_000)), snapshot.preferences.keySavedAt)
            assertEquals(listOf(7L), snapshot.sms.map { it.id })
            assertTrue(snapshot.classics.isEmpty())
            store.saveClassic(ClassicCard("9760030000000001"))
            store.savePreferences(snapshot.preferences.copy(
                keySavedAt = snapshot.preferences.keySavedAt + (MiTransfer to Instant.ofEpochMilli(1_700_000_100_000)),
                pendingKeys = mapOf(Bank.BANDEC to PendingKey(Instant.ofEpochMilli(1_700_000_200_000), 9)),
            ))
            val again = store.load()
            assertEquals(listOf(ClassicCard("9760030000000001")), again.classics)
            assertEquals(setOf(Bank.BPA, MiTransfer), again.preferences.keySavedAt.keys)
            assertEquals(PendingKey(Instant.ofEpochMilli(1_700_000_200_000), 9), again.preferences.pendingKeys[Bank.BANDEC])
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun `version 3 contacts migrate as never changed and not deleted`() = runBlocking {
        context.deleteDatabase(name)
        create(3, "INSERT INTO contact VALUES ('c1', 'Juan', '50000009', '9204990000000007', '9204990000000007\tSalario')")
        val database = Room.databaseBuilder(context, NeoDatabase::class.java, name).build()
        try {
            val store = RoomStore(database.dao())
            val juan = Contact("c1", "Juan", listOf("50000009"), listOf("9204990000000007"), mapOf("9204990000000007" to "Salario"))
            assertEquals(listOf(ContactRecord(juan, 0)), store.load().contacts)
            store.saveContacts(listOf(ContactSync.tombstone(juan, 1_792_000_000_000)))
            assertEquals(listOf(ContactRecord(Contact("c1", "Juan"), 1_792_000_000_000, deleted = true)), store.load().contacts)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    /** The tables of an exported schema [version], as that release created them, with [rows] inserted. */
    private fun create(version: Int, vararg rows: String) {
        val schema = JSONObject(File("schemas/dev.duardo.neotransfer.data.NeoDatabase/$version.json").readText()).getJSONObject("database")
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            rows.forEach(db::execSQL)
            db.version = version
        }
    }
}
