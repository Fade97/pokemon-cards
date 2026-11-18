// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.backup

/** Minimal RFC 4180 CSV: comma separated, fields quoted when they contain a comma, quote or line break. */
internal object Csv {
    fun line(fields: List<String?>): String = fields.joinToString(",") { quote(it.orEmpty()) }

    /** First characters that make a spreadsheet read a cell as a formula (OWASP "CSV injection"). */
    private const val FORMULA_START = "=+-@\t\r"

    /**
     * A field a spreadsheet would run as a formula (a collection named `=HYPERLINK(…)`), with a leading `'` that
     * makes it plain text (#131). [unguard] takes the mark off again on import.
     */
    fun guard(field: String?): String? =
        if (field != null && field.isNotEmpty() && (field[0] in FORMULA_START || field[0] == '\'')) "'$field" else field

    /**
     * Takes off the mark [guard] added. A text that began with `'` itself was marked too, so one `'` comes off
     * whenever a formula character or another `'` follows; a lone `'` before plain text was never a mark.
     */
    fun unguard(field: String): String =
        if (field.length >= 2 && field[0] == '\'' && (field[1] in FORMULA_START || field[1] == '\'')) field.substring(1) else field

    private fun quote(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + field.replace("\"", "\"\"") + "\"" else field

    /** All records of [text]; quoted fields may span lines. A leading byte order mark is ignored. */
    fun parse(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var record = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = if (text.startsWith('\uFEFF')) 1 else 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> quoted = false
                    else -> field.append(c)
                }
            } else {
                when (c) {
                    '"' -> quoted = true
                    ',' -> { record += field.toString(); field.clear() }
                    '\r' -> Unit
                    '\n' -> {
                        record += field.toString(); field.clear()
                        records += record; record = mutableListOf()
                    }
                    else -> field.append(c)
                }
            }
            i++
        }
        if (field.isNotEmpty() || record.isNotEmpty()) {
            record += field.toString()
            records += record
        }
        return records.filterNot { it.size == 1 && it[0].isBlank() }
    }
}
