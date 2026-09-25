package dev.duardo.neotransfer.backup

import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

sealed class TrmFailure(message: String) : Exception(message) {
    class InvalidFile : TrmFailure("El archivo Transfermóvil no tiene un formato válido")
    class IntegrityFailed : TrmFailure("No se pudo comprobar el archivo Transfermóvil")
}

enum class TrmRecordKind {
    CONTACT, RECIPIENT_ACCOUNT, OWN_ACCOUNT, BILL, PREPAID_CARD,
    MOBILE, NAUTA, RECHARGE_CODE, BANK_MESSAGE, PUBLIC_SERVICE, LANDLINE, RECEIPT,
}

/** Values are a read-only preview; no record is applied to the wallet here. */
data class TrmRecord(val kind: TrmRecordKind, val legacyTable: String, val fields: Map<String, String?>)

data class TrmPreview(
    val sourceVersion: String?,
    val exportedAt: String?,
    val sourcePhone: String?,
    val records: List<TrmRecord>,
    val skippedSensitive: Map<String, Int>,
    val unsupported: Map<String, Int>,
) {
    fun records(kind: TrmRecordKind): List<TrmRecord> = records.filter { it.kind == kind }
}

/** Reads the historical .trm container. Its embedded key is not a user password. */
object TrmImporter {
    private const val hashHexSize = 128
    private const val headerSize = hashHexSize * 2
    const val maxInputBytes = 24 * 1024 * 1024
    private const val maxJsonBytes = 16 * 1024 * 1024
    private const val maxRows = 50_000
    private const val maxFields = 64
    private const val maxFieldChars = 65_536

    private val known = mapOf(
        "Client" to TrmRecordKind.CONTACT,
        "CuentaBanco" to TrmRecordKind.RECIPIENT_ACCOUNT,
        "MCBank" to TrmRecordKind.OWN_ACCOUNT,
        "Factura" to TrmRecordKind.BILL,
        "TarjetaPropia" to TrmRecordKind.PREPAID_CARD,
        "TeflMovil" to TrmRecordKind.MOBILE,
        "Nauta" to TrmRecordKind.NAUTA,
        "Pin" to TrmRecordKind.RECHARGE_CODE,
        "RecordSMS" to TrmRecordKind.BANK_MESSAGE,
        "ServicioPublico" to TrmRecordKind.PUBLIC_SERVICE,
        "TelfFijo" to TrmRecordKind.LANDLINE,
        "TCTicket" to TrmRecordKind.RECEIPT,
    )

    fun preview(bytes: ByteArray): TrmPreview {
        if (bytes.size <= headerSize || bytes.size > maxInputBytes) throw TrmFailure.InvalidFile()
        val keyHex = ascii(bytes, 0, hashHexSize)
        val expectedHash = decodeHex(ascii(bytes, hashHexSize, hashHexSize))
        if (keyHex.length != hashHexSize || !keyHex.all { it.isHexDigit() }) throw TrmFailure.InvalidFile()
        val ciphertext = try {
            Base64.getDecoder().decode(bytes.copyOfRange(headerSize, bytes.size))
        } catch (_: IllegalArgumentException) {
            throw TrmFailure.InvalidFile()
        }
        if (ciphertext.isEmpty() || ciphertext.size % 16 != 0 || ciphertext.size > maxJsonBytes + 16) {
            throw TrmFailure.InvalidFile()
        }
        val key = MessageDigest.getInstance("SHA-256").digest(keyHex.toByteArray(StandardCharsets.US_ASCII))
        val plaintext = try {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
            try { cipher.doFinal(ciphertext) } catch (_: Exception) { throw TrmFailure.IntegrityFailed() }
        } catch (error: TrmFailure) {
            throw error
        } catch (_: Exception) {
            throw TrmFailure.InvalidFile()
        } finally {
            key.fill(0)
            ciphertext.fill(0)
        }
        try {
            if (plaintext.isEmpty() || plaintext.size > maxJsonBytes) throw TrmFailure.InvalidFile()
            val actualHash = MessageDigest.getInstance("SHA-512").digest(plaintext)
            if (!MessageDigest.isEqual(expectedHash, actualHash)) throw TrmFailure.IntegrityFailed()
            val text = try {
                StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(plaintext)).toString()
            } catch (_: Exception) {
                throw TrmFailure.InvalidFile()
            }
            return parsePreview(text)
        } finally {
            plaintext.fill(0)
            expectedHash.fill(0)
        }
    }

    private fun parsePreview(text: String): TrmPreview {
        try {
            val root = JSONObject(text)
            root.getJSONArray("scheme")
            val tables = root.getJSONArray("datos")
            val records = ArrayList<TrmRecord>()
            val sensitive = linkedMapOf<String, Int>()
            val unsupported = linkedMapOf<String, Int>()
            var totalRows = 0
            for (index in 0 until tables.length()) {
                val table = tables.getJSONObject(index)
                val name = table.getString("tabla")
                val raw = table.get("dataJSON")
                val rows = when (raw) {
                    is JSONArray -> raw
                    is String -> JSONArray(raw)
                    else -> throw TrmFailure.InvalidFile()
                }
                totalRows += rows.length()
                if (totalRows > maxRows) throw TrmFailure.InvalidFile()
                when {
                    name == "AOperKey" -> sensitive[name] = (sensitive[name] ?: 0) + rows.length()
                    name !in known -> unsupported[name] = (unsupported[name] ?: 0) + rows.length()
                    else -> for (rowIndex in 0 until rows.length()) {
                        val row = rows.getJSONObject(rowIndex)
                        if (row.length() > maxFields) throw TrmFailure.InvalidFile()
                        val fields = linkedMapOf<String, String?>()
                        val keys = row.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            val rawValue = row.get(key)
                            val value = if (rawValue == JSONObject.NULL) null else rawValue.toString()
                            if (key.length > 128 || (value?.length ?: 0) > maxFieldChars) throw TrmFailure.InvalidFile()
                            fields[key] = value
                        }
                        records += TrmRecord(requireNotNull(known[name]), name, fields)
                    }
                }
            }
            return TrmPreview(
                sourceVersion = root.optString("version_apk").takeIf(String::isNotEmpty),
                exportedAt = root.optString("fecha_exp").takeIf(String::isNotEmpty),
                sourcePhone = root.optString("movil").takeIf { it.isNotEmpty() && it != "none" },
                records = records,
                skippedSensitive = sensitive,
                unsupported = unsupported,
            )
        } catch (error: TrmFailure) {
            throw error
        } catch (_: Exception) {
            throw TrmFailure.InvalidFile()
        }
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String {
        if (bytes.size < offset + length) throw TrmFailure.InvalidFile()
        val fragment = bytes.copyOfRange(offset, offset + length)
        if (fragment.any { it.toInt() !in 0x30..0x66 }) throw TrmFailure.InvalidFile()
        return String(fragment, StandardCharsets.US_ASCII)
    }

    private fun decodeHex(value: String): ByteArray {
        if (value.length != hashHexSize || !value.all { it.isHexDigit() }) throw TrmFailure.InvalidFile()
        return ByteArray(value.length / 2) { index ->
            ((value[index * 2].digitToInt(16) shl 4) or value[index * 2 + 1].digitToInt(16)).toByte()
        }
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
}
