// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.db

import androidx.room.TypeConverter
import de.fabianduerkop.cardindex.model.Printings
import java.time.Instant
import java.time.LocalDate

/** Enums are stored by name by Room itself; the java.time types and [Printings] need converters. */
class Converters {
    @TypeConverter
    fun instantToMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun millisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    /** ISO-8601 (`2026-03-12`) keeps exported data readable and sorts correctly as text. */
    @TypeConverter
    fun localDateToString(value: LocalDate?): String? = value?.toString()

    @TypeConverter
    fun stringToLocalDate(value: String?): LocalDate? = value?.let(LocalDate::parse)

    /** The pack's keys joined with commas (`holo,firstEdition`); null is unknown. */
    @TypeConverter
    fun printingsToString(value: Printings?): String? = value?.toString()

    @TypeConverter
    fun stringToPrintings(value: String?): Printings? = Printings.parse(value)
}
