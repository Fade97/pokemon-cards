// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

/** A collector number read from the card, normalized (`"006"` → `"6"`), and the printed set total if read. */
data class NumberReading(val number: String, val total: String?)

private val NUMBER_RE = Regex("""(\d{1,3})\s*[/|\\]\s*(\d{2,3})""")

/** Normalizes a collector number for comparison, like `number_key` in ml/common.py: "006" → "6", "TG01" stays. */
fun numberKey(localId: String): String {
    val s = localId.trim().uppercase()
    return if (s.isNotEmpty() && s.all(Char::isDigit)) s.trimStart('0').ifEmpty { "0" } else s
}

/** Finds the first `number/total` pattern in OCR lines (e.g. "006/165", "6 / 165"). */
fun parseCollectorNumber(lines: List<String>): NumberReading? {
    for (line in lines) {
        // OCR often reads the slash as I, l or 1-like glyphs; only the clear separators count, as in pipeline.py.
        val m = NUMBER_RE.find(line) ?: continue
        return NumberReading(numberKey(m.groupValues[1]), m.groupValues[2])
    }
    return null
}

/**
 * Collector-number rerank (`rerank_one` in ml/pipeline.py): candidates whose number and printed total match move
 * to the front, then those whose number matches; the similarity order is kept inside each tier.
 */
fun rerank(candidates: List<Match>, reading: NumberReading?): List<Match> {
    if (reading == null) return candidates
    fun tier(m: Match): Int {
        if (numberKey(m.card.localId) != reading.number) return 0
        return if (reading.total == null || m.card.official.toString() == reading.total) 2 else 1
    }
    return candidates.sortedByDescending(::tier) // stable
}
