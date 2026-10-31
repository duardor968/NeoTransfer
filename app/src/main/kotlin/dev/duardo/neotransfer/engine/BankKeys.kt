package dev.duardo.neotransfer.engine

import dev.duardo.neotransfer.core.model.Agency
import dev.duardo.neotransfer.data.AGENCIES
import dev.duardo.neotransfer.data.storageName

/** Plaintext of the [KeyVault] blob, `BPA=1234\nBANDEC=12345\nMITRANSFER=1234`; keys never pass through strings. */
internal object BankKeys {
    fun encode(keys: Map<Agency, CharArray>): ByteArray {
        val bytes = ByteArray(keys.entries.sumOf { (agency, key) -> agency.storageName.length + key.size + 2 })
        var index = 0
        for ((agency, key) in keys) {
            for (char in agency.storageName) bytes[index++] = char.code.toByte()
            bytes[index++] = '='.code.toByte()
            for (char in key) bytes[index++] = char.code.toByte()
            bytes[index++] = '\n'.code.toByte()
        }
        return bytes
    }

    /** @throws IllegalArgumentException when the blob is not a set of well-formed keys */
    fun decode(bytes: ByteArray): MutableMap<Agency, CharArray> {
        val keys = mutableMapOf<Agency, CharArray>()
        var start = 0
        while (start < bytes.size) {
            val end = bytes.indexOf('\n'.code.toByte(), start)
            val equals = bytes.indexOf('='.code.toByte(), start)
            require(end > equals && equals > start) { "Clave guardada no válida" }
            val name = String(bytes, start, equals - start, Charsets.US_ASCII)
            val agency = AGENCIES.firstOrNull { it.storageName == name }
            val key = CharArray(end - equals - 1) { bytes[equals + 1 + it].toInt().toChar() }
            require(agency != null && key.size == agency.pinLength && key.all { it in '0'..'9' }) { "Clave guardada no válida" }
            keys[agency] = key
            start = end + 1
        }
        return keys
    }

    private fun ByteArray.indexOf(value: Byte, from: Int): Int = (from until size).firstOrNull { this[it] == value } ?: -1
}
