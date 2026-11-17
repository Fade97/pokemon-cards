// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.model

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test
    fun parseMinor_acceptsCommaAndDotDecimals() {
        assertEquals(2450L, Money.parseMinor("24,50"))
        assertEquals(2450L, Money.parseMinor("24.50"))
        assertEquals(2450L, Money.parseMinor("24.5"))
        assertEquals(2400L, Money.parseMinor("24"))
        assertEquals(123450L, Money.parseMinor("1.234,50"))
        assertEquals(123450L, Money.parseMinor("1,234.50"))
        assertEquals(123400L, Money.parseMinor("1,234"))
        assertNull(Money.parseMinor(""))
        assertNull(Money.parseMinor("abc"))
        assertNull(Money.parseMinor("-3"))
    }

    @Test
    fun format_usesTheLocaleAndCurrency() {
        assertEquals("38,90 €", Money.format(3890, "EUR", Locale.GERMANY).replace(' ', ' '))
        assertEquals("$44.10", Money.format(4410, "USD", Locale.US))
        assertEquals("24,50", Money.formatPlain(2450, Locale.GERMANY))
    }

    @Test
    fun toMinor_roundsHalfUp() {
        assertEquals(120L, Money.toMinor(1.2))
        assertEquals(101L, Money.toMinor(1.005))
    }
}
