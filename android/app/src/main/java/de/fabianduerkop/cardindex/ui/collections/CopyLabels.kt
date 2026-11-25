// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import de.fabianduerkop.cardindex.ui.UiLocale
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.model.Condition
import de.fabianduerkop.cardindex.model.Edition
import de.fabianduerkop.cardindex.model.Finish
import de.fabianduerkop.cardindex.model.Grader
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@StringRes
fun Finish.label(): Int = when (this) {
    Finish.NORMAL -> R.string.finish_normal
    Finish.HOLO -> R.string.finish_holo
    Finish.REVERSE_HOLO -> R.string.finish_reverse_holo
    Finish.OTHER -> R.string.finish_other
}

@StringRes
fun Edition.label(): Int = when (this) {
    Edition.UNLIMITED -> R.string.edition_unlimited
    Edition.FIRST_EDITION -> R.string.edition_first
    Edition.SHADOWLESS -> R.string.edition_shadowless
}

/** In full words everywhere ("Lightly played"), as Review has them: "LP" was read out as letters (#218). */
@StringRes
fun Condition.label(): Int = when (this) {
    Condition.NEAR_MINT -> R.string.review_condition_nm
    Condition.LIGHTLY_PLAYED -> R.string.review_condition_lp
    Condition.MODERATELY_PLAYED -> R.string.review_condition_mp
    Condition.HEAVILY_PLAYED -> R.string.review_condition_hp
    Condition.POOR -> R.string.review_condition_poor
}

@Composable
fun Grader.displayName(): String = if (this == Grader.OTHER) stringResource(R.string.grader_other) else name

/** "1st edition Holo, PSA 9" or "Reverse holo, NM": the print and state of a copy in one line. */
@Composable
fun copyDescription(copy: OwnedCopyEntity): String {
    val parts = buildList {
        val finish = stringResource(copy.finish.label())
        add(if (copy.edition == Edition.UNLIMITED) finish else stringResource(copy.edition.label()) + " " + finish)
        val grader = copy.grader
        when {
            grader != null -> add(listOfNotNull(grader.displayName(), copy.grade).joinToString(" "))
            copy.condition != null -> add(stringResource(copy.condition.label()))
        }
    }
    return parts.joinToString(", ")
}

/** Language code to its name in the UI language: `de` → "German". */
fun languageName(code: String, locale: Locale = UiLocale.current): String =
    Locale.forLanguageTag(code).getDisplayLanguage(locale).ifEmpty { code }
        .replaceFirstChar { it.titlecase(locale) }

/** "English and German" for the set header; uses the UI's list conjunction only for two items, else commas. */
fun languageList(codes: List<String>, and: String): String {
    val names = codes.map { languageName(it) }
    return when (names.size) {
        0 -> ""
        1 -> names.single()
        else -> names.dropLast(1).joinToString(", ") + " $and " + names.last()
    }
}

fun formatDate(date: LocalDate): String = date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
