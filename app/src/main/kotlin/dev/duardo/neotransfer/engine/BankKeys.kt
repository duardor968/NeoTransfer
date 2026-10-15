package dev.duardo.neotransfer.engine

import dev.duardo.neotransfer.core.model.Bank

/** Plaintext of the [KeyVault] blob, `BPA=1234\nBANDEC=12345`; keys never pass through strings. */
internal object BankKeys {
    fun encode(keys: Map<Bank, CharArray>): ByteArray {
        val bytes = ByteArray(keys.entries.sumOf { (bank, key) -> bank.name.length + key.size + 2 })
        var index = 0
        for ((bank, key) in keys) {
            for (char in bank.name) bytes[index++] = char.code.toByte()
            bytes[index++] = '='.code.toByte()
            for (char in key) bytes[index++] = char.code.toByte()
            bytes[index++] = '\n'.code.toByte()
        }
        return bytes
    }

    /** @throws IllegalArgumentException when the blob is not a set of well-formed keys */
    fun decode(bytes: ByteArray): MutableMap<Bank, CharArray> {
        val keys = mutableMapOf<Bank, CharArray>()
        var start = 0
        while (start < bytes.size) {
            val end = bytes.indexOf('\n'.code.toByte(), start)
            val equals = bytes.indexOf('='.code.toByte(), start)
            require(end > equals && equals > start) { "Clave guardada no válida" }
            val name = String(bytes, start, equals - start, Charsets.US_ASCII)
            val bank = Bank.entries.firstOrNull { it.name == name }
            val key = CharArray(end - equals - 1) { bytes[equals + 1 + it].toInt().toChar() }
            require(bank != null && key.size == bank.pinLength && key.all { it in '0'..'9' }) { "Clave guardada no válida" }
            keys[bank] = key
            start = end + 1
        }
        return keys
    }

    private fun ByteArray.indexOf(value: Byte, from: Int): Int = (from until size).firstOrNull { this[it] == value } ?: -1
}
