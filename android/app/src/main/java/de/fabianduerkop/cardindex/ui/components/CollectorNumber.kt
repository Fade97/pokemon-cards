// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.ui.theme.LumifoilTheme
import de.fabianduerkop.cardindex.ui.theme.TabularFigures

/**
 * The collector number as the main type, e.g. `006/165`: the number in the style's weight, the printed total
 * muted and lighter. Figures are tabular so numbers line up. [localId] is padded to the width of the total
 * (at least three digits) when it is purely numeric; ids like `TG05` or `SV-P 001` stay as they are.
 */
@Composable
fun CollectorNumber(
    localId: String,
    printedTotal: Int?,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.displayLarge,
    color: Color = Color.Unspecified,
    totalColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val description = collectorNumberDescription(localId, printedTotal)
    // A printed total of 0 (promos) means none: "022", not "022/000".
    val printed = printedTotal?.takeIf { it > 0 }
    val number = padCollectorNumber(localId, printed)
    val total = printed?.let { padCollectorNumber(it.toString(), it) }
    // Spoken as text, not as a content description: in a row that merges its texts for TalkBack, a child's description
    // replaced all the other texts, so only "Number 006 of 165" was read, not the name, set or price (#218).
    Box(modifier.clearAndSetSemantics { text = AnnotatedString(description) }) {
        Text(
            text = numberOverTotal(number, total, totalColor),
            style = style.merge(TextStyle(fontFeatureSettings = TabularFigures)),
            color = color,
            maxLines = 1,
        )
    }
}

/**
 * What TalkBack says for a collector number: "Number 006 of 165", or just the number without a total. Also for rows
 * that build one spoken label of their own.
 */
@Composable
fun collectorNumberDescription(localId: String, printedTotal: Int?): String {
    val printed = printedTotal?.takeIf { it > 0 }
    val number = padCollectorNumber(localId, printed)
    val total = printed?.let { padCollectorNumber(it.toString(), it) }
    return if (total != null) stringResource(R.string.collector_number_description, number, total) else number
}

/** "006/165" as plain text, or just "022" for a promo without a printed total. */
fun numberWithTotal(localId: String, printedTotal: Int?): String {
    val printed = printedTotal?.takeIf { it > 0 }
    return padCollectorNumber(localId, printed) + (printed?.let { "/" + padCollectorNumber(it.toString(), it) } ?: "")
}

/** "87/165" with the total muted; shared by [CollectorNumber] and count displays like set completion. */
fun numberOverTotal(number: String, total: String?, totalColor: Color): AnnotatedString = buildAnnotatedString {
    append(number)
    if (total != null) {
        withStyle(SpanStyle(color = totalColor, fontWeight = FontWeight.Medium)) {
            append('/')
            append(total)
        }
    }
}

/** Pads a numeric collector number with zeros to the width of [printedTotal], at least three digits. */
fun padCollectorNumber(localId: String, printedTotal: Int?): String {
    if (localId.isEmpty() || !localId.all(Char::isDigit)) return localId
    val width = maxOf(3, printedTotal?.toString()?.length ?: 0)
    return localId.trimStart('0').ifEmpty { "0" }.padStart(width, '0')
}

@Preview(showBackground = true)
@Composable
private fun CollectorNumberPreview() {
    LumifoilTheme { CollectorNumber(localId = "6", printedTotal = 165) }
}
