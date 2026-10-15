package dev.duardo.neotransfer.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import dev.duardo.neotransfer.engine.Documents
import dev.duardo.neotransfer.engine.PhoneContact
import dev.duardo.neotransfer.engine.PhoneContacts

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

/** Reads only the contact the user picked (`ACTION_PICK` on Contacts); needs READ_CONTACTS for its phones. */
class AndroidPhoneContacts(context: Context) : PhoneContacts {
    private val context = context.applicationContext

    override fun read(uri: String): PhoneContact {
        check(context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            "Permite leer contactos para importar los móviles del contacto"
        }
        val contactUri = Uri.parse(uri)
        require(contactUri.scheme == "content" && contactUri.authority == ContactsContract.AUTHORITY) { "El contacto elegido no es válido" }
        val resolver = context.contentResolver
        val (id, key, name) = checkNotNull(resolver.query(contactUri, arrayOf(
            ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        ), null, null, null)) { "No se pudo leer el contacto" }.use { cursor ->
            require(cursor.moveToFirst()) { "El contacto ya no está disponible" }
            Triple(cursor.getLong(0), cursor.getString(1), cursor.getString(2)?.trim().orEmpty())
        }
        require(name.isNotEmpty()) { "El contacto no tiene nombre" }
        val numbers = checkNotNull(resolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(id.toString()), null)) { "No se pudieron leer los móviles del contacto" }.use { cursor ->
            buildList { while (cursor.moveToNext()) cursor.getString(0)?.let(::add) }
        }
        val phones = numbers.mapNotNull(::cubanMobile).distinct()
        require(phones.isNotEmpty()) { "El contacto no tiene móviles cubanos" }
        return PhoneContact(key?.takeIf(String::isNotBlank) ?: "id-$id", name, phones)
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
