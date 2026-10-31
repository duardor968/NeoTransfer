package dev.duardo.neotransfer.data

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import dev.duardo.neotransfer.core.model.Agency
import dev.duardo.neotransfer.core.model.Bank
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

/** A database as version 2 left it opens as version 3: every row stays, and the Clásicas get their table. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NeoDatabaseMigrationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val name = "migration-2-3.db"

    @Test fun `version 2 migrates to 3 keeping its rows`() = runBlocking {
        context.deleteDatabase(name)
        createVersion2()
        val database = Room.databaseBuilder(context, NeoDatabase::class.java, name).build()
        try {
            // Room checks the migrated schema against version 3 as it opens.
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

    /** The tables of the exported version 2 schema, as 1.1 created them, with a row in some of them. */
    private fun createVersion2() {
        val schema = JSONObject(File("schemas/dev.duardo.neotransfer.data.NeoDatabase/2.json").readText()).getJSONObject("database")
        val file = context.getDatabasePath(name).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO card VALUES ('9205000000000001', 'BPA', 'CUP', NULL, 'ANA PRUEBA', '03/29', 0)")
            db.execSQL("INSERT INTO setting VALUES ('key.saved.BPA', '1700000000000')")
            db.execSQL("INSERT INTO sms VALUES (7, 1700000000000, 1700000001000, 'Banco Popular de Ahorro: La consulta de saldo fue completada. Saldo Disponible: CR 1.00 CUP')")
            db.version = 2
        }
    }
}
