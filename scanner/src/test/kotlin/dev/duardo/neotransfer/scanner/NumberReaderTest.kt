package dev.duardo.neotransfer.scanner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Synthetic numbers only: 9204129951234568 and 9205990012345670 are Luhn-valid in the Cuban 92 range.
private const val CARD = "9204129951234568"
private const val CARD_2 = "9205990012345670"
private const val CHAR = 12f
private const val HEIGHT = 24f

/** Lays words out like ML Kit would: one character is 12 px wide, one line 24 px high. */
private fun line(text: String, x: Float = 0f, y: Float = 0f): OcrLine {
    var cursor = x
    return OcrLine(text.split(' ').filter(String::isNotEmpty).map { word ->
        OcrWord(word, Box(cursor, y, cursor + word.length * CHAR, y + HEIGHT)).also { cursor += (word.length + 1) * CHAR }
    })
}

private fun read(vararg lines: OcrLine) = NumberReader.read(lines.toList())
private fun cards(vararg lines: OcrLine) = read(*lines).filter { it.kind == NumberKind.CARD }.map { it.digits }
private fun phones(vararg lines: OcrLine) = read(*lines).filter { it.kind == NumberKind.PHONE }.map { it.digits }

class NumberReaderTest {
    @Test
    fun luhnAcceptsCubanCardsAndRejectsNearMisses() {
        assertTrue(NumberReader.luhn(CARD))
        assertTrue(NumberReader.luhn(CARD_2))
        assertFalse(NumberReader.luhn("9204129951234567"))
        assertTrue(NumberReader.isCard(CARD))
        assertFalse(NumberReader.isCard("4539578763621486"), "Luhn-valid but outside the Cuban 92 range")
    }

    @Test
    fun printedCardWithLabelAndAnyGrouping() {
        assertEquals(listOf(CARD), cards(line("Tarjeta: 9204 1299 5123 4568")))
        assertEquals(listOf(CARD), cards(line(CARD)))
        assertEquals(listOf(CARD), cards(line("9204-1299-5123-4568")))
        assertEquals(listOf(CARD), cards(line("92041299 51234568")))
        assertEquals(listOf(CARD), cards(line("92 04 1299 5123 4568 01/29")))
    }

    @Test
    fun cardHitBoxCoversTheDigitsNotTheLabel() {
        val hit = read(line("Tarjeta: 9204 1299 5123 4568")).single()
        assertEquals(9 * CHAR, hit.box.left)
        assertEquals(28 * CHAR, hit.box.right)
    }

    @Test
    fun cardSplitIntoBlocksOnOneRowOrIntoTwoLines() {
        // Embossed cards: ML Kit often returns each group as its own block.
        val blocks = listOf("9204", "1299", "5123", "4568").mapIndexed { i, group -> line(group, x = i * 90f, y = 100f) }
        assertEquals(listOf(CARD), cards(*blocks.reversed().toTypedArray()))
        val stacked = read(line("9204 1299", y = 0f), line("5123 4568", y = 30f))
        assertEquals(listOf(NumberHit(NumberKind.CARD, CARD, Box(0f, 0f, 9 * CHAR, 54f))), stacked)
    }

    @Test
    fun misreadCardProposesNothingInsteadOfAPhone() {
        assertEquals(emptyList(), read(line("9204 1299 5123 4567")))
        assertEquals(emptyList(), read(line("8204 1299 5123 4568")), "4×4 layout on one row stays a card")
        assertEquals(emptyList(), read(line("9204 1299", y = 0f), line("5123 4567", y = 30f)))
    }

    @Test
    fun transactionNumbersAndIdsAreNotCards() {
        assertEquals(emptyList(), read(line("Transaccion 1234567890123456")))
        assertEquals(emptyList(), read(line("4539578763621486")))
        assertEquals(emptyList(), read(line("Carnet 85010112345")))
    }

    @Test
    fun cubanMobileFormats() {
        listOf(
            "51234567", "5123 4567", "5 123 4567", "5123-4567", "+53 5123 4567", "53 51234567", "5351234567",
            "+5351234567", "Móvil: 51234567", "Movil51234567", "Tel:51234567",
        ).forEach { text -> assertEquals(listOf("51234567"), phones(line(text)), text) }
        assertEquals(listOf("63456789"), phones(line("6345 6789")))
    }

    @Test
    fun landlinesAndShortNumbersAreNotMobiles() {
        assertEquals(emptyList(), read(line("Fijo 72345678")))
        assertEquals(emptyList(), read(line("5123456")))
        assertEquals(emptyList(), read(line("512345678")))
    }

    @Test
    fun severalPhonesOnOneLineOrStacked() {
        assertEquals(listOf("51234567", "59876543"), phones(line("51234567 / 59876543")))
        assertEquals(listOf("51234567", "59876543"), phones(line("51234567 59876543")))
        val list = read(line("5123 4567", y = 0f), line("5987 6543", y = 30f))
        assertEquals(listOf("51234567", "59876543"), list.map { it.digits })
        assertTrue(list.all { it.kind == NumberKind.PHONE })
    }

    @Test
    fun handwrittenConfusionsAreCorrectedInsideNumbers() {
        assertEquals(listOf("51234567"), phones(line("5l23 4S67")))
        assertEquals(listOf("51234567"), phones(line("SI23 4567")))
        assertEquals(listOf(CARD), cards(line("92O4 l299 5l23 4S6B")))
        assertEquals(listOf("51234567"), phones(line("5123", y = 0f), line("4567", y = 30f)), "split over two lines")
    }

    @Test
    fun wordsAreNotReadAsDigits() {
        assertEquals(emptyList(), read(line("SOS BOLSO lOGO Saldo Disponible")))
        assertEquals(emptyList(), read(line("Bloqueo de tarjeta: SI")))
    }

    @Test
    fun datesAndTimesAreNotNumbers() {
        listOf("12/05/2025", "2025-05-12", "15-06-2025", "31.12.2025 23:59", "Fecha 20251231").forEach { text ->
            assertEquals(emptyList(), read(line(text)), text)
        }
        assertEquals(listOf("51234567"), phones(line("12/05/2025 Móvil 51234567")))
    }

    @Test
    fun amountsAreNotPhones() {
        listOf(
            "51 234 567,00 CUP", "Importe 51234567 CUP", "51234567.50", "$51234567", "5 123 456", "51 234 567",
            "Saldo: 61234567,89 MLC",
        ).forEach { text -> assertEquals(emptyList(), read(line(text)), text) }
    }

    @Test
    fun cardAndPhoneTogether() {
        val hits = read(line("Juan 9205 9900 1234 5670"), line("51234567", y = 30f))
        assertEquals(listOf(NumberKind.CARD to CARD_2, NumberKind.PHONE to "51234567"), hits.map { it.kind to it.digits })
    }

    @Test
    fun unrelatedLinesFarApartStaySeparate() {
        assertEquals(emptyList(), read(line("5123", y = 0f), line("4567", y = 400f)))
        assertEquals(emptyList(), cards(line("9204 1299", x = 0f, y = 0f), line("5123 4568", x = 900f, y = 30f)))
    }

    @Test
    fun formatGroupsDigitsByFour() {
        assertEquals("9204 1299 5123 4568", NumberReader.format(CARD))
        assertEquals("5123 4567", NumberReader.format("51234567"))
        assertEquals("51234567", NumberReader.phoneDigits("+53 5123-4567"))
    }
}
