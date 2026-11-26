// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints

/** The separator between the facts of a meta line: "TCGplayer market · Holo". */
const val PartSeparator = " · "

/**
 * Facts of a meta line joined with " · " as far as they fit the width, then on the next line, so a "·" never ends or
 * starts a line at any font size or screen width (#250). [groups] are facts that belong together (where a copy is,
 * where it came from): when the whole line does not fit, each group starts a line of its own; with [groupLines] they
 * always do. A single fact wider than the line wraps as text does.
 */
@Composable
fun JoinedText(
    groups: List<List<String>>,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    groupLines: Boolean = false,
    /** Between facts on one line; ", " for a market and its date. */
    separator: String = PartSeparator,
) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier) {
        val max = constraints.maxWidth
        val text = remember(groups, max, style, groupLines, separator) {
            val fits = { line: String ->
                max == Constraints.Infinity ||
                    measurer.measure(line, style, softWrap = false, maxLines = 1).size.width <= max
            }
            joinParts(groups.map { g -> g.filter { it.isNotEmpty() } }.filter { it.isNotEmpty() }, groupLines, fits, separator)
        }
        Text(text, style = style, color = color, textAlign = textAlign)
    }
}

/** [JoinedText] for facts without groups: as many on a line as fit. */
@Composable
fun JoinedText(
    parts: List<String>,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    textAlign: TextAlign? = null,
    separator: String = PartSeparator,
) = JoinedText(listOf(parts), modifier, style, color, textAlign, separator = separator)

/** The lines of [JoinedText]: one line when it [fits], else each group packed greedily from a new line. */
internal fun joinParts(
    groups: List<List<String>>,
    groupLines: Boolean,
    fits: (String) -> Boolean,
    separator: String = PartSeparator,
): String {
    if (!groupLines) {
        val all = groups.flatten().joinToString(separator)
        if (fits(all)) return all
    }
    return groups.flatMap { group ->
        buildList {
            var line = ""
            for (part in group) {
                val longer = if (line.isEmpty()) part else line + separator + part
                if (line.isEmpty() || fits(longer)) {
                    line = longer
                } else {
                    add(line)
                    line = part
                }
            }
            if (line.isNotEmpty()) add(line)
        }
    }.joinToString("\n")
}
