package dev.duardo.neotransfer.scanner

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal enum class NumberKind { CARD, PHONE }

internal data class NumberHit(val kind: NumberKind, val digits: String, val box: Box)

/** OCR output in image or view pixels: one entry per recognised line, words in reading order. */
internal data class OcrWord(val text: String, val box: Box)
internal data class OcrLine(val words: List<OcrWord>)

/**
 * Finds Cuban card numbers (16 digits, IIN 92, Luhn) and mobiles (8 digits starting with 5 or 6, optional
 * `53`) in OCR text. Groups split across words, ML Kit blocks on the same row, or two stacked lines are joined
 * when the joined digits make a number that neither part makes alone.
 */
internal object NumberReader {
    fun read(lines: List<OcrLine>): List<NumberHit> =
        join(lines.withIndex().flatMap { (index, line) -> fragments(line, index) })
            .flatMap { fragment ->
                candidates(fragment).map { NumberHit(it.kind, it.digits, fragment.box(it.from, it.to)) }
            }
            .distinctBy { it.kind to it.digits }

    fun luhn(digits: String): Boolean {
        if (digits.isEmpty() || !digits.all(Char::isDigit)) return false
        var sum = 0
        digits.reversed().forEachIndexed { index, char ->
            var value = char - '0'
            if (index % 2 == 1) value = (value * 2).let { if (it > 9) it - 9 else it }
            sum += value
        }
        return sum % 10 == 0
    }

    fun isCard(digits: String) = digits.length == 16 && digits.startsWith("92") && luhn(digits)

    fun isPhone(digits: String) = digits.length == 8 && digits.all(Char::isDigit) && digits[0] in "56"

    /** Accepts what a person types for a mobile: spaces, dashes, `+53` or `53`. */
    fun phoneDigits(input: String): String {
        val digits = input.filter(Char::isDigit)
        return if (digits.length == 10 && digits.startsWith("53")) digits.drop(2) else digits
    }

    fun format(digits: String) = digits.chunked(4).joinToString(" ")

    // Common OCR and handwriting confusions, applied only inside mostly-digit runs.
    private val confusions = mapOf(
        'O' to '0', 'o' to '0', 'Q' to '0', 'D' to '0',
        'I' to '1', 'l' to '1', '|' to '1',
        'Z' to '2', 'z' to '2',
        'S' to '5', 's' to '5',
        'G' to '6', 'b' to '6',
        'T' to '7',
        'B' to '8',
        'g' to '9', 'q' to '9',
    )
    private val currencyAfter = Regex("""^\s*(?:cup|mlc|usd|cuc|mn|pesos?)(?![\p{L}\d])""", RegexOption.IGNORE_CASE)
    private val softSeparators = setOf(' ', '-', '–', '—')

    private class Group(val digits: String, val box: Box)

    private class Fragment(
        val groups: List<Group>,
        val lines: Set<Int>,
        val opensLine: Boolean,
        val closesLine: Boolean,
        val amount: Boolean,
        val stacked: Boolean,
    ) {
        val box get() = box(0, groups.size)
        fun box(from: Int, to: Int) = groups.subList(from, to).map { it.box }.reduce(Box::union)
    }

    private class Candidate(val kind: NumberKind, val digits: String, val from: Int, val to: Int)

    private fun fragments(line: OcrLine, lineIndex: Int): List<Fragment> {
        val text = StringBuilder()
        val owner = ArrayList<Int>()
        val offset = ArrayList<Int>()
        line.words.forEachIndexed { index, word ->
            if (index > 0) { text.append(' '); owner += -1; offset += -1 }
            word.text.forEachIndexed { at, char -> text.append(char); owner += index; offset += at }
        }
        val s = text.toString()
        val out = mutableListOf<Fragment>()
        var groups = mutableListOf<Group>()
        var start = 0
        var end = 0
        var amount = false

        fun close() {
            if (groups.isNotEmpty()) {
                val before = s.substring(0, start)
                val after = s.substring(end)
                out += Fragment(
                    groups = groups,
                    lines = setOf(lineIndex),
                    opensLine = before.none(Char::isLetterOrDigit),
                    closesLine = after.none(Char::isLetterOrDigit),
                    amount = amount || before.trimEnd().endsWith('$') || currencyAfter.containsMatchIn(after),
                    stacked = false,
                )
            }
            groups = mutableListOf()
            amount = false
        }

        fun groupBox(from: Int, to: Int): Box {
            val word = line.words[owner[from]]
            val length = word.text.length.toFloat()
            val left = word.box.left + word.box.width * offset[from] / length
            val right = word.box.left + word.box.width * (offset[to - 1] + 1) / length
            return Box(left, word.box.top, right, word.box.bottom)
        }

        var i = 0
        while (i < s.length) {
            val char = s[i]
            when {
                char.isDigit() || char in confusions -> {
                    var j = i
                    while (j < s.length && (s[j].isDigit() || s[j] in confusions)) j++
                    // Letters glued to a word ("Movil51234567") keep their look-alike letters.
                    var a = i
                    var b = j
                    if (a > 0 && s[a - 1].isLetter()) while (a < b && !s[a].isDigit()) a++
                    if (b < s.length && s[b].isLetter()) while (b > a && !s[b - 1].isDigit()) b--
                    val digits = (a until b).count { s[it].isDigit() }
                    if (digits > 0 && digits * 2 >= b - a) {
                        if (a > i) close()
                        if (groups.isEmpty()) start = a
                        groups += Group(s.substring(a, b).map { confusions[it] ?: it }.joinToString(""), groupBox(a, b))
                        end = b
                        if (b < j) close()
                    } else {
                        close()
                    }
                    i = j
                }
                char in softSeparators -> i++
                else -> {
                    if ((char == '.' || char == ',') && groups.isNotEmpty() && end == i && twoDecimals(s, i + 1)) amount = true
                    close()
                    i++
                }
            }
        }
        close()
        return out
    }

    private fun twoDecimals(s: String, from: Int) =
        from + 1 < s.length && s[from].isDigit() && s[from + 1].isDigit() && (from + 2 == s.length || !s[from + 2].isDigit())

    private fun join(input: List<Fragment>): List<Fragment> {
        val fragments = input.toMutableList()
        while (true) {
            var best: Triple<Fragment, Fragment, Fragment>? = null
            var bestCost = Float.MAX_VALUE
            for (a in fragments) for (b in fragments) {
                if (a === b || !a.closesLine || !b.opensLine || a.amount || b.amount) continue
                if (a.lines.any(b.lines::contains)) continue
                val near = adjacency(a.box, b.box) ?: continue
                if (near.cost >= bestCost) continue
                val merged = Fragment(a.groups + b.groups, a.lines + b.lines, a.opensLine, b.closesLine, false,
                    a.stacked || b.stacked || near.stacked)
                if (near.stacked && !spansBoundary(merged, a.groups.size)) continue
                best = Triple(a, b, merged)
                bestCost = near.cost
            }
            val (a, b, merged) = best ?: return fragments
            fragments.removeAll { it === a || it === b }
            fragments += merged
        }
    }

    private class Adjacency(val stacked: Boolean, val cost: Float)

    /** Same row (ML Kit split the blocks) or next line, with similar character height. */
    private fun adjacency(a: Box, b: Box): Adjacency? {
        val small = min(a.height, b.height)
        val big = max(a.height, b.height)
        if (small <= 0f || big > small * 1.7f) return null
        val overlapY = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (overlapY >= small * 0.5f) {
            val gap = b.left - a.right
            return if (gap > -0.3f * small && gap < 3f * big) Adjacency(false, gap / big) else null
        }
        val gap = b.top - a.bottom
        val aligned = abs(a.left - b.left) < 2.5f * big ||
            min(a.right, b.right) - max(a.left, b.left) > 0.5f * min(a.width, b.width)
        return if (gap > -0.3f * small && gap < 1.3f * big && aligned) Adjacency(true, 3f + gap / big) else null
    }

    private fun spansBoundary(merged: Fragment, boundary: Int): Boolean {
        val groups = merged.groups.map { it.digits }
        return cardWindows(groups, !merged.stacked).any { it.first < boundary && it.last >= boundary } ||
            candidates(merged).any { it.from < boundary && it.to > boundary }
    }

    /**
     * Consecutive groups holding exactly 16 digits that look like a card: Cuban IIN `92`, or the printed
     * 4×4 layout on one row. Their digits never become phones, even when Luhn fails (a misread card).
     */
    private fun cardWindows(groups: List<String>, layout: Boolean): List<IntRange> {
        val windows = mutableListOf<IntRange>()
        for (from in groups.indices) {
            var sum = 0
            var to = from
            while (to < groups.size && sum < 16) sum += groups[to++].length
            if (sum != 16) continue
            val window = groups.subList(from, to)
            if (window.joinToString("").startsWith("92") || (layout && window.size == 4 && window.all { it.length == 4 })) {
                windows += from until to
            }
        }
        return windows
    }

    private fun candidates(fragment: Fragment): List<Candidate> {
        val groups = fragment.groups.map { it.digits }
        val out = mutableListOf<Candidate>()
        val blocked = BooleanArray(groups.size)
        for (window in cardWindows(groups, !fragment.stacked)) {
            window.forEach { blocked[it] = true }
            val digits = groups.subList(window.first, window.last + 1).joinToString("")
            if (isCard(digits)) out += Candidate(NumberKind.CARD, digits, window.first, window.last + 1)
        }
        // Amounts ("51 234 567,00 CUP", thousands grouping) never become phones.
        if (fragment.amount || thousands(groups)) return out
        var i = 0
        while (i < groups.size) {
            val phone = if (blocked[i]) null else phoneAt(groups, i, blocked)
            if (phone != null) {
                out += phone
                i = phone.to
            } else {
                i++
            }
        }
        return out
    }

    private fun thousands(groups: List<String>) =
        groups.size >= 3 && groups[0].length in 1..3 && groups.drop(1).all { it.length == 3 }

    private fun phoneAt(groups: List<String>, from: Int, blocked: BooleanArray): Candidate? {
        if (groups[from] == "53") span(groups, from + 1, 8, blocked)?.let { (to, digits) ->
            if (isPhone(digits)) return Candidate(NumberKind.PHONE, digits, from, to)
        }
        span(groups, from, 10, blocked)?.let { (to, digits) ->
            if (digits.startsWith("53") && isPhone(digits.drop(2))) return Candidate(NumberKind.PHONE, digits.drop(2), from, to)
        }
        span(groups, from, 8, blocked)?.let { (to, digits) ->
            if (isPhone(digits)) return Candidate(NumberKind.PHONE, digits, from, to)
        }
        return null
    }

    /** Whole groups from [from] adding up to exactly [length] digits. */
    private fun span(groups: List<String>, from: Int, length: Int, blocked: BooleanArray): Pair<Int, String>? {
        var sum = 0
        var to = from
        while (to < groups.size && !blocked[to] && sum < length) sum += groups[to++].length
        return if (sum == length && to > from) to to groups.subList(from, to).joinToString("") else null
    }
}
