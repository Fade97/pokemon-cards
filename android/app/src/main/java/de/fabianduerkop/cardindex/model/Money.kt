// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.model

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Amounts are stored in minor units (cents) with an ISO 4217 code; these helpers convert at the UI edges. */
object Money {
    fun format(minor: Long, currency: String, locale: Locale = Locale.getDefault()): String {
        val format = NumberFormat.getCurrencyInstance(locale)
        runCatching { format.currency = Currency.getInstance(currency) }
        return format.format(BigDecimal.valueOf(minor, 2))
    }

    /** The amount alone, for an input field next to a currency symbol: `24,50` in German. */
    fun formatPlain(minor: Long, locale: Locale = Locale.getDefault()): String {
        val format = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
            isGroupingUsed = false
        }
        return format.format(BigDecimal.valueOf(minor, 2))
    }

    /**
     * Parses what a user types into a price field. Both `24,50` and `24.50` mean 24.50: with at most two digits after
     * the last separator, that separator is the decimal one, whatever the locale. Returns null for anything else.
     */
    fun parseMinor(text: String): Long? {
        val cleaned = text.trim().replace(" ", "").replace(" ", "")
        if (cleaned.isEmpty()) return null
        val lastSeparator = cleaned.indexOfLast { it == ',' || it == '.' }
        val normalized = if (lastSeparator >= 0 && cleaned.length - lastSeparator - 1 in 1..2) {
            cleaned.substring(0, lastSeparator).replace(",", "").replace(".", "") + "." +
                cleaned.substring(lastSeparator + 1)
        } else {
            cleaned.replace(",", "").replace(".", "")
        }
        val value = normalized.toBigDecimalOrNull() ?: return null
        if (value.signum() < 0) return null
        return value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact()
    }

    /** Major-unit decimal from the price API (`1.2`) to minor units. */
    fun toMinor(amount: Double): Long = BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).movePointRight(2).toLong()

    /** The currency of the user's region, for new purchase prices; EUR when the locale has no country. */
    fun localCurrency(locale: Locale = Locale.getDefault()): String =
        runCatching { Currency.getInstance(locale).currencyCode }.getOrNull() ?: "EUR"
}
