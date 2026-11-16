// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.ui.theme.CardIndexTheme
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
    val number = padCollectorNumber(localId, printedTotal)
    val total = printedTotal?.let { padCollectorNumber(it.toString(), printedTotal) }
    val description = if (total != null) stringResource(R.string.collector_number_description, number, total) else number
    Text(
        text = numberOverTotal(number, total, totalColor),
        modifier = modifier.semantics { contentDescription = description },
        style = style.merge(TextStyle(fontFeatureSettings = TabularFigures)),
        color = color,
        maxLines = 1,
    )
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
    CardIndexTheme { CollectorNumber(localId = "6", printedTotal = 165) }
}
