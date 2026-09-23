package dev.duardo.neotransfer.core

import java.security.SecureRandom

/** Wire compatibility with Transfermóvil 1.260416. This is encoding, not secret storage. */
class ParameterCodec {
    fun encode(parameters: List<String>, seed: Int = random.nextInt(100)): String {
        require(parameters.isNotEmpty() && parameters.all { it.isNotEmpty() && '*' !in it }) {
            "Parámetros vacíos o con separadores no permitidos"
        }
        require(seed in 0..99) { "Semilla fuera de rango" }
        val numeric = parameters.map { value -> value.all(Character::isDigit) }
        val prefix = if (numeric.any { it }) seed.toString().padStart(2, '0') else "00"
        return prefix + parameters.mapIndexed { index, value ->
            if (numeric[index]) {
                value.length.toString().padStart(2, '0') + encodeNumber(value, seed)
            } else {
                "00" + value.map { char ->
                    requireNotNull(textCodes[char]) { "Carácter no admitido por el protocolo" }
                }.joinToString("")
            }
        }.joinToString("*")
    }

    private fun encodeNumber(value: String, seed: Int): String {
        val substituted = buildString {
            var index = 0
            while (index + 1 < value.length) {
                append(table[value.substring(index, index + 2).toInt()].toString().padStart(2, '0'))
                index += 2
            }
            if (index < value.length) append(singleDigits[value.substring(index).toInt()])
        }
        var cursor = seed
        val mask = buildString {
            repeat(value.length) {
                // The original XOR sequence skips the final table entry when wrapping.
                if (cursor == table.lastIndex) {
                    append(table[0])
                    cursor = 1
                } else {
                    append(table[cursor++])
                }
            }
        }.take(substituted.length)
        val number = requireNotNull(substituted.toLongOrNull()) { "Parámetro numérico demasiado largo" }
        val key = requireNotNull(mask.toLongOrNull()) { "Parámetro numérico demasiado largo" }
        return (number xor key).toString()
    }

    private companion object {
        val random = SecureRandom()
        const val CODE_VERSION = "372653282298718711"
        const val CRUDE_CODE = "10000110101100011000100100100000110010001100000011100010100110010001010010100010111110001100010000011000010101100000000110001100101010100100011000000101110001100001100010001011110001100010100001000110000001101000101001001101001000001000010001100100011010010111001000110011110010001001100001000100000010000111001000110000101110100001111110011000001000110010000111110001100001111111000110011000101001100101000110001000111100011000101011101011111100111010100010111101000110000110011000110011100011000100111000101000011100010100111000011101100011000010101010000010101000100010100011001010101101000110001011101100011001001101101001111100011000000100010001100100010110101000110110000110000101000100011000000010100000010010001101011011100010110001110001001011100000110110001100001000011001011010100101100010001100100011110100011000011000110001100101010110101011110001100010000101000110001001101010010001001110001000010110110001100100111010001101101000110010010110001000110101000011110110000011101000110001001001100100010010000001111000110010011101110001100000001011000110000100011001111101000110000010010100011000110111100101100101011001100000010011000110000000001000100111100011000101101000100100000100010010101000100011100011000001101110001110011000101001"
        val table: List<Int> = buildList {
            var offset = 0
            var keyIndex = 0
            while (offset < CRUDE_CODE.length) {
                offset += CODE_VERSION[keyIndex].digitToInt()
                add(CRUDE_CODE.substring(offset, offset + 8).toInt(2))
                offset += 8
                // Confirmed in DEX: after the final key digit, jump back to index zero.
                keyIndex = (keyIndex + 1) % CODE_VERSION.length
            }
            check(size == 100 && toSet() == (0..99).toSet()) { "Tabla de protocolo no válida" }
        }
        val singleDigits = table.filter { it < 10 }
        val textCodes = mapOf(
            '.' to "15",
            '-' to "85",
            '_' to "58",
            '0' to "37",
            '/' to "11",
            ',' to "29",
            '%' to "67",
            '&' to "20",
            'a' to "59",
            'b' to "00",
            'c' to "82",
            '1' to "89",
            'A' to "05",
            'B' to "54",
            'C' to "60",
            'á' to "88",
            '@' to "64",
            'd' to "51",
            'e' to "93",
            'h' to "50",
            '2' to "41",
            'D' to "57",
            'E' to "65",
            'F' to "61",
            'é' to "91",
            '#' to "08",
            'g' to "30",
            'f' to "46",
            'i' to "97",
            '3' to "80",
            'G' to "83",
            'H' to "13",
            'I' to "55",
            'í' to "53",
            ';' to "56",
            'j' to "76",
            'k' to "25",
            'l' to "42",
            '4' to "66",
            'J' to "73",
            'K' to "17",
            'L' to "38",
            'ó' to "32",
            'm' to "27",
            'n' to "92",
            'o' to "12",
            'ñ' to "03",
            '5' to "01",
            'M' to "06",
            'N' to "49",
            'O' to "18",
            'Ñ' to "33",
            'p' to "98",
            'q' to "23",
            'r' to "31",
            '6' to "94",
            'P' to "44",
            'Q' to "35",
            'R' to "34",
            'ú' to "87",
            's' to "19",
            't' to "45",
            'u' to "81",
            '7' to "62",
            'S' to "22",
            'T' to "40",
            'U' to "10",
            'v' to "96",
            'w' to "69",
            'x' to "75",
            '8' to "24",
            'V' to "14",
            'W' to "70",
            'X' to "74",
            'y' to "09",
            'z' to "90",
            ' ' to "78",
            '9' to "72",
            'Y' to "71",
            'Z' to "07",
            'Á' to "99",
            'É' to "86",
            'Í' to "47",
            'Ó' to "36",
            'Ú' to "39",
            'ü' to "77",
            'Ü' to "95",
        )
    }
}
