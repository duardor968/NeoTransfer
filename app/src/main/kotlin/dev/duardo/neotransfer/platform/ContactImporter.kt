package dev.duardo.neotransfer.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import dev.duardo.neotransfer.data.ContactPhone
import dev.duardo.neotransfer.data.ContactRecord

/** Reads only the contact the user selected. Call off the main thread after granting READ_CONTACTS. */
object ContactImporter {
    fun importContact(context: Context, uri: Uri): ContactRecord {
        check(context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            "Permite leer contactos para importar todos los móviles del contacto elegido"
        }
        require(uri.scheme == "content" && uri.authority == ContactsContract.AUTHORITY) {
            "El contacto elegido no es válido"
        }

        val resolver = context.contentResolver
        val contact = checkNotNull(resolver.query(uri, arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        ), null, null, null)) { "No se pudo leer el contacto elegido" }.use { cursor ->
            require(cursor.moveToFirst()) { "El contacto elegido ya no está disponible" }
            Triple(cursor.getLong(0), cursor.getString(1), cursor.getString(2))
        }
        val name = contact.third?.trim().orEmpty()
        require(name.isNotEmpty()) { "El contacto elegido no tiene nombre" }
        val id = contact.second?.takeIf(String::isNotBlank)?.let { "android-contact:$it" }
            ?: "android-contact-id:${contact.first}"

        val numbers = checkNotNull(resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE,
                ContactsContract.CommonDataKinds.Phone.LABEL,
            ),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(contact.first.toString()),
            null,
        )) { "No se pudieron leer los móviles del contacto elegido" }.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val number = cursor.getString(0) ?: continue
                    val type = if (cursor.isNull(1)) ContactsContract.CommonDataKinds.Phone.TYPE_OTHER else cursor.getInt(1)
                    val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(
                        context.resources, type, cursor.getString(2),
                    ).toString()
                    add(ContactPhone(number, label))
                }
            }
        }
        return ContactRecord(id, name, phones = validCubanMobiles(numbers))
    }
}

internal fun validCubanMobiles(numbers: List<ContactPhone>): List<ContactPhone> {
    val unique = linkedMapOf<String, ContactPhone>()
    numbers.forEach { phone ->
        val number = normalizeCubanMobile(phone.number) ?: return@forEach
        val normalized = ContactPhone(number, phone.label.trim())
        val previous = unique[number]
        if (previous == null || previous.label.isBlank() && normalized.label.isNotBlank()) unique[number] = normalized
    }
    require(unique.isNotEmpty()) { "El contacto elegido no tiene móviles cubanos válidos" }
    return unique.values.toList()
}

internal fun normalizeCubanMobile(raw: String): String? {
    if (raw.isBlank() || raw.any { !it.isDigit() && it !in "+ -()." }) return null
    val digits = raw.filter(Char::isDigit)
    val local = when {
        digits.length == 8 -> digits
        digits.length == 10 && digits.startsWith("53") -> digits.drop(2)
        digits.length == 12 && digits.startsWith("0053") -> digits.drop(4)
        else -> return null
    }
    return local.takeIf { it.length == 8 && it[0] == '5' }
}
