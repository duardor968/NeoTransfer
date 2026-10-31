package dev.duardo.neotransfer.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import dev.duardo.neotransfer.app.Book
import dev.duardo.neotransfer.app.Domain
import dev.duardo.neotransfer.core.ledger.ActivityRow
import dev.duardo.neotransfer.core.ledger.ActivitySync
import dev.duardo.neotransfer.core.model.ContactRecord
import dev.duardo.neotransfer.core.model.ContactRow
import dev.duardo.neotransfer.core.model.ContactSync
import dev.duardo.neotransfer.engine.AppController
import dev.duardo.neotransfer.engine.PeerActivity
import dev.duardo.neotransfer.engine.PeerContacts
import dev.duardo.neotransfer.engine.controller
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * What NeoTransfer+ reads of this app, at `content://<applicationId>.sync`: `/contacts`, the stored contacts with their
 * tombstones, one [ContactRow] per row; `/activity`, the money operations this app sent ([Book.shared]), one [ActivityRow]
 * per row. Read-only, and only for apps holding `dev.duardo.neotransfer.permission.SYNC_CONTACTS`, a signature permission:
 * only the author's apps get it.
 */
class SyncProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val controller = requireContext().controller
        return when (uri.pathSegments) {
            listOf(CONTACTS) -> {
                val records = runBlocking { controller.contactRecords() }
                MatrixCursor(CONTACT_COLUMNS, records.size).apply {
                    records.map(ContactSync::row).forEach { addRow(arrayOf<Any>(it.id, it.name, it.phones, it.cards, it.updated, if (it.deleted) 1 else 0)) }
                }
            }
            listOf(ACTIVITY) -> activityCursor(runBlocking { originated(controller) })
            else -> null
        }
    }

    /** This app's own activity, once the stored state is in memory; never NeoTransfer+'s rows. */
    private suspend fun originated(controller: AppController): List<ActivityRow> {
        controller.loaded.first { it }
        return Book(Domain(loaded = true, cards = controller.cards.value, classics = controller.classics.value, operations = controller.operations.value,
            movements = controller.movements.value, contacts = controller.contacts.value, services = controller.services.value)).shared
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}

/** NeoTransfer+'s `/contacts`; call off the main thread. */
class AndroidPeerContacts(context: Context) : PeerContacts {
    private val resolver = context.applicationContext.contentResolver

    override fun read(): List<ContactRecord>? = resolver.query(peer(CONTACTS), CONTACT_COLUMNS, null, null, null)?.use { cursor ->
        val columns = CONTACT_COLUMNS.map(cursor::getColumnIndexOrThrow)
        buildList {
            while (cursor.moveToNext()) {
                val row = ContactRow(cursor.getString(columns[0]), cursor.getString(columns[1]).orEmpty(), cursor.getString(columns[2]).orEmpty(),
                    cursor.getString(columns[3]).orEmpty(), cursor.getLong(columns[4]), cursor.getInt(columns[5]) != 0)
                add(ContactSync.record(row))
            }
        }
    }
}

/** NeoTransfer+'s `/activity`; call off the main thread. */
class AndroidPeerActivity(context: Context) : PeerActivity {
    private val resolver = context.applicationContext.contentResolver

    override fun read(): List<ActivityRow>? = resolver.query(peer(ACTIVITY), ActivitySync.COLUMNS, null, null, null)?.use(::activityRows)
}

/** [rows] as `/activity` serves them. */
internal fun activityCursor(rows: List<ActivityRow>): Cursor =
    MatrixCursor(ActivitySync.COLUMNS, rows.size).apply { rows.forEach { addRow(it.values()) } }

/** The rows of an `/activity` cursor. */
internal fun activityRows(cursor: Cursor): List<ActivityRow> {
    val columns = ActivitySync.COLUMNS.map(cursor::getColumnIndexOrThrow)
    val at = ActivitySync.COLUMNS.indexOf("at")
    return buildList {
        while (cursor.moveToNext()) {
            add(ActivitySync.row(columns.mapIndexed { i, column -> if (i == at) cursor.getLong(column) else cursor.getString(column) }))
        }
    }
}

private const val CONTACTS = "contacts"
private const val ACTIVITY = "activity"
private fun peer(path: String): Uri = Uri.parse("content://dev.duardo.neotransfer.plus.sync/$path")
private val CONTACT_COLUMNS = arrayOf("id", "name", "phones", "cards", "updated", "deleted")
