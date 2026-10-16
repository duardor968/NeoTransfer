package dev.duardo.neotransfer.platform

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import dev.duardo.neotransfer.engine.Documents
import dev.duardo.neotransfer.engine.PhoneBook
import dev.duardo.neotransfer.engine.PhoneContact

/** Documents from `ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`; call off the main thread. */
class ContentDocuments(context: Context) : Documents {
    private val resolver = context.applicationContext.contentResolver

    override fun read(uri: String): ByteArray =
        checkNotNull(resolver.openInputStream(Uri.parse(uri))) { "No se pudo abrir el archivo" }.use { input ->
            val bytes = input.readNBytes(MAX_BYTES + 1)
            require(bytes.size <= MAX_BYTES) { "El archivo es demasiado grande" }
            bytes
        }

    override fun write(uri: String, bytes: ByteArray) =
        checkNotNull(resolver.openOutputStream(Uri.parse(uri), "wt")) { "No se pudo guardar el archivo" }.use { it.write(bytes) }

    private companion object {
        /** Above the largest backup or `.trm` the core accepts. */
        const val MAX_BYTES = 24 * 1024 * 1024
    }
}

/** The address book through the phones table: one query, grouped by contact; call off the main thread. */
class AndroidPhoneBook(context: Context) : PhoneBook {
    private val resolver = context.applicationContext.contentResolver

    override fun read(): List<PhoneContact> {
        val rows = resolver.query(Phone.CONTENT_URI, arrayOf(Phone.LOOKUP_KEY, Phone.CONTACT_ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER),
            null, null, null) ?: return emptyList()
        val people = linkedMapOf<String, PhoneContact>()
        rows.use { cursor ->
            while (cursor.moveToNext()) {
                val phone = cursor.getString(3)?.let(::cubanMobile) ?: continue
                val name = cursor.getString(2)?.trim()?.takeIf(String::isNotEmpty) ?: continue
                val key = cursor.getString(0)?.takeIf(String::isNotBlank) ?: "id-${cursor.getLong(1)}"
                val known = people[key]
                people[key] = if (known == null) PhoneContact(key, name, listOf(phone)) else known.copy(phones = (known.phones + phone).distinct())
            }
        }
        return people.values.toList()
    }
}

/** `5xxxxxxx`, with `+53`, `53` or `0053` tolerated; anything else (landlines, foreign numbers) is not a mobile. */
internal fun cubanMobile(raw: String): String? {
    if (raw.isBlank() || raw.any { !it.isDigit() && it !in "+ -()." }) return null
    val digits = raw.filter(Char::isDigit)
    val local = when {
        digits.length == 8 -> digits
        digits.length == 10 && digits.startsWith("53") -> digits.drop(2)
        digits.length == 12 && digits.startsWith("0053") -> digits.drop(4)
        else -> return null
    }
    return local.takeIf { it[0] == '5' }
}
