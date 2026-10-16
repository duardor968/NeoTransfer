package dev.duardo.neotransfer.engine

import dev.duardo.neotransfer.core.protocol.UssdCommand
import dev.duardo.neotransfer.core.sms.SmsRecord
import javax.crypto.Cipher

/** The modem. Android: `TelephonyManager.sendUssdRequest` and `ACTION_CALL`; tests: a fake. */
interface UssdModem {
    /** Sends a `REQUEST` command and suspends until the network answers; the caller bounds the wait. */
    suspend fun request(command: UssdCommand): UssdReply

    /** Dials a `DIAL` command: the system shows the interactive menu, the app never sees the reply. */
    fun dial(command: UssdCommand)
}

sealed interface UssdReply {
    /** The acknowledgement or answer text. */
    data class Text(val text: String) : UssdReply

    /** The phone or the network refused the request; [reason] is ready to show. */
    data class Failure(val reason: String) : UssdReply
}

/** A bank row of the SMS inbox: `_id`, `date`, `date_sent` (epoch millis), body and sender. */
data class InboxSms(val id: Long, val date: Long, val dateSent: Long, val body: String, val address: String = SmsRecord.SENDER)

interface SmsInbox {
    /** Rows of [SmsRecord.SENDERS] with `_id >= fromId`. Throws [SecurityException] without READ_SMS. */
    fun read(fromId: Long): List<InboxSms>
}

/**
 * Keystore storage of the bank keys, decrypted only through a cipher the fingerprint authorized
 * (strong biometrics, every use). Holds one blob for all banks.
 */
interface KeyVault {
    fun hasKeys(): Boolean
    /** Null when enrolling a new fingerprint invalidated the Keystore key: the vault is then empty. */
    fun decryptCipher(): Cipher?
    fun decrypt(authorized: Cipher): ByteArray
    fun encryptCipher(): Cipher
    fun encrypt(authorized: Cipher, plaintext: ByteArray)
    fun reset()
}

enum class NoticeKind { CREDIT, RESULT }

/**
 * A notification. [key] identifies it (posting the same key again replaces it); [publicTitle]
 * is all the lock screen shows; [route] is the screen it opens ([Routes]).
 */
data class Notice(val key: String, val kind: NoticeKind, val title: String, val text: String?, val publicTitle: String, val route: String)

fun interface Notifier {
    fun post(notice: Notice)
}

/** Files the user picked with the system document picker, by content URI. */
interface Documents {
    fun read(uri: String): ByteArray
    fun write(uri: String, bytes: ByteArray)
}

/** Someone in the phone's address book: [key] is the contact's lookup key, [phones] its Cuban mobiles (8 digits). */
data class PhoneContact(val key: String, val name: String, val phones: List<String>)

/** The phone's address book, read whole: it is the source of names and phones; the app only adds cards. */
fun interface PhoneBook {
    /** Everyone with a name and a Cuban mobile. Throws [SecurityException] without READ_CONTACTS. */
    fun read(): List<PhoneContact>
}
