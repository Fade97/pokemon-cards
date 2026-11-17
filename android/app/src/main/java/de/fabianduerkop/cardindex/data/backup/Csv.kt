// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.backup

/** Minimal RFC 4180 CSV: comma separated, fields quoted when they contain a comma, quote or line break. */
internal object Csv {
    fun line(fields: List<String?>): String = fields.joinToString(",") { quote(it.orEmpty()) }

    private fun quote(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + field.replace("\"", "\"\"") + "\"" else field

    /** All records of [text]; quoted fields may span lines. A leading byte order mark is ignored. */
    fun parse(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var record = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = if (text.startsWith('﻿')) 1 else 0
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
