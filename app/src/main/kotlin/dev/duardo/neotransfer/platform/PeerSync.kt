package dev.duardo.neotransfer.platform

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import dev.duardo.neotransfer.core.model.ContactRecord
import dev.duardo.neotransfer.core.model.ContactRow
import dev.duardo.neotransfer.core.model.ContactSync
import dev.duardo.neotransfer.engine.PeerContacts
import dev.duardo.neotransfer.engine.controller
import kotlinx.coroutines.runBlocking

/**
 * The stored contacts, tombstones included, for NeoTransfer+ to read: `content://<applicationId>.contacts/contacts`,
 * one [ContactRow] per row. Read-only, and only for apps holding `dev.duardo.neotransfer.permission.SYNC_CONTACTS`, a
 * signature permission: only the author's apps get it.
 */
class ContactsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        if (uri.pathSegments != listOf(PATH)) return null
        val records = runBlocking { requireContext().controller.contactRecords() }
        return MatrixCursor(COLUMNS, records.size).apply {
            records.map(ContactSync::row).forEach { addRow(arrayOf<Any>(it.id, it.name, it.phones, it.cards, it.updated, if (it.deleted) 1 else 0)) }
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}

/** NeoTransfer+'s [ContactsProvider] twin; call off the main thread. */
class AndroidPeerContacts(context: Context) : PeerContacts {
    private val resolver = context.applicationContext.contentResolver

    override fun read(): List<ContactRecord>? = resolver.query(PEER, COLUMNS, null, null, null)?.use { cursor ->
        val columns = COLUMNS.map(cursor::getColumnIndexOrThrow)
        buildList {
            while (cursor.moveToNext()) {
                val row = ContactRow(cursor.getString(columns[0]), cursor.getString(columns[1]).orEmpty(), cursor.getString(columns[2]).orEmpty(),
                    cursor.getString(columns[3]).orEmpty(), cursor.getLong(columns[4]), cursor.getInt(columns[5]) != 0)
                add(ContactSync.record(row))
            }
        }
    }
}

private const val PATH = "contacts"
private val PEER: Uri = Uri.parse("content://dev.duardo.neotransfer.plus.contacts/$PATH")
private val COLUMNS = arrayOf("id", "name", "phones", "cards", "updated", "deleted")
