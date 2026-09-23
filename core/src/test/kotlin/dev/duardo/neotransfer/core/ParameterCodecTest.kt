package dev.duardo.neotransfer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ParameterCodecTest {
    private val codec = ParameterCodec()

    @Test
    fun `matches every reference vector including every seed`() {
        val stream = requireNotNull(javaClass.getResourceAsStream("/codec-vectors.tsv"))
        val rows = stream.bufferedReader(Charsets.UTF_8).use { it.readLines().drop(1) }
        assertEquals(1400, rows.size)
        rows.forEach { row ->
            val (case, seed, input, expected) = row.split('\t')
            assertEquals(expected, codec.encode(input.split('*'), seed.toInt()), "$case seed=$seed")
        }
    }

    @Test
    fun `text and decimal use zero length prefix and dictionary`() {
        assertEquals("000044593012", codec.encode(listOf("Pago"), 99))
        assertEquals("0000893737150137", codec.encode(listOf("100.50"), 99))
    }

    @Test
    fun `invalid input never becomes a malformed wire payload`() {
        listOf(emptyList(), listOf(""), listOf("1", ""), listOf("1*2"), listOf("€"), listOf("😀"))
            .forEach { parameters -> assertFailsWith<IllegalArgumentException> { codec.encode(parameters) } }
        assertFailsWith<IllegalArgumentException> { codec.encode(listOf("1"), -1) }
        assertFailsWith<IllegalArgumentException> { codec.encode(listOf("1"), 100) }
        assertFailsWith<IllegalArgumentException> { codec.encode(listOf("9".repeat(20))) }
        assertFalse(codec.encode(listOf("Prueba 2")).contains("null"))
    }
}
