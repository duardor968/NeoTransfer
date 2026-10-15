package dev.duardo.neotransfer.core.backup

import dev.duardo.neotransfer.core.model.Contact
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.core.model.ServiceReference
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Backup content, format 4: own cards (with what the wallet prints on them), contacts and saved
 * service references. There is no field for banking keys, transfer keys or tokens.
 */
@Serializable
data class BackupContent(
    val cards: List<BackupCard> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val services: List<ServiceReference> = emptyList(),
)

/** An own card plus the holder and expiry (`MM/AA`) printed on it, when the user gave them. */
@Serializable
data class BackupCard(val card: OwnCard, val holder: String? = null, val expiry: String? = null)

/** Password-protected `.ntbackup` files: [BackupEnvelope] around the JSON of [BackupContent]. */
object BackupFile {
    private const val FORMAT = 4
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val legacyMagic = "NTPAYLD".toByteArray(Charsets.US_ASCII)

    @Serializable
    private data class Document(val format: Int, val content: BackupContent)

    fun export(content: BackupContent, password: CharArray): ByteArray =
        BackupEnvelope.encrypt(json.encodeToString(Document.serializer(), Document(FORMAT, content)).toByteArray(Charsets.UTF_8), password)

    /** @throws BackupFailure wrong password, damaged file or a format this version does not read */
    fun import(file: ByteArray, password: CharArray): BackupContent {
        val payload = BackupEnvelope.decrypt(file, password)
        try {
            if (payload.size >= legacyMagic.size && payload.copyOf(legacyMagic.size).contentEquals(legacyMagic)) throw BackupFailure.UnsupportedVersion()
            val document = try {
                json.decodeFromString(Document.serializer(), payload.toString(Charsets.UTF_8))
            } catch (_: Exception) {
                throw BackupFailure.InvalidInput()
            }
            if (document.format != FORMAT) throw BackupFailure.UnsupportedVersion()
            return document.content
        } finally {
            payload.fill(0)
        }
    }
}
