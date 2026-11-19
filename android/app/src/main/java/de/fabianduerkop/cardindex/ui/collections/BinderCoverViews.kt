// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.db.CopyWithCard
import de.fabianduerkop.cardindex.data.images.OwnPhotos
import de.fabianduerkop.cardindex.ui.components.CARD_ASPECT
import de.fabianduerkop.cardindex.ui.components.CardImage
import de.fabianduerkop.cardindex.ui.components.focusRing
import de.fabianduerkop.cardindex.ui.components.padCollectorNumber
import de.fabianduerkop.cardindex.ui.theme.LumifoilTheme
import de.fabianduerkop.cardindex.ui.theme.Spacing

/** Width of the cover in a binder row (#172): a small card, about as tall as the row's two text lines. */
internal val CoverThumbWidth = 36.dp

/**
 * A binder's cover in its row (#172): the card's art, or the user's own photo where they prefer it (ADR 0009), with
 * the numbered placeholder underneath while it loads or offline. An empty binder (and the wishlist, wanted cards not
 * owned) shows a hollow card with [emptyIcon]: hollow is Pearl's shape for "not owned" (ADR 0014). Decorative: the row
 * says what it is.
 */
@Composable
internal fun CoverThumb(
    cover: CopyWithCard?,
    photos: OwnPhotos,
    modifier: Modifier = Modifier,
    width: Dp = CoverThumbWidth,
    @DrawableRes emptyIcon: Int = R.drawable.ic_nav_collections,
) {
    if (cover == null) {
        val shape = MaterialTheme.shapes.small
        Box(
            modifier
                .width(width)
                .aspectRatio(CARD_ASPECT)
                .clip(shape)
                .border(1.5.dp, LumifoilTheme.colors.missingSlotOutline, shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(emptyIcon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    } else {
        CardImage(
            card = cover.card,
            localId = cover.card.localId,
            printedTotal = cover.set.printedTotal,
            modifier = modifier.width(width),
            ownPhoto = photos.of(cover.copy.uuid),
            preferOwnPhoto = photos.preferOwn,
        )
    }
}

/** "006/165 Charizard ex", for TalkBack and the picker. */
private fun CopyWithCard.coverLabel(): String =
    listOfNotNull(
        padCollectorNumber(card.localId, set.printedTotal) + (set.printedTotal.takeIf { it > 0 }?.let { "/$it" } ?: ""),
        card.name.takeIf { it.isNotBlank() },
    ).joinToString(" ")

/**
 * The cover picker of the edit sheet (#172): Automatic first (the most valuable card, which follows the binder as it
 * changes), then one card per print in the binder. A radio group for TalkBack; [selectedUuid] null is Automatic.
 */
@Composable
internal fun CoverPicker(
    automatic: CopyWithCard?,
    choices: List<CopyWithCard>,
    selectedUuid: String?,
    photos: OwnPhotos,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(stringResource(R.string.collection_cover), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.collection_cover_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.xxs),
        )
        val automaticDescription = stringResource(
            R.string.collection_cover_automatic_description,
            automatic?.coverLabel().orEmpty(),
        )
        LazyRow(
            Modifier.fillMaxWidth().padding(top = Spacing.sm).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item(key = "automatic") {
                CoverChoice(
                    cover = automatic,
                    label = stringResource(R.string.collection_cover_automatic),
                    description = automaticDescription,
                    selected = selectedUuid == null,
                    photos = photos,
                    onClick = { onSelect(null) },
                )
            }
            items(choices, key = { it.copy.uuid }) { choice ->
                CoverChoice(
                    cover = choice,
                    label = padCollectorNumber(choice.card.localId, choice.set.printedTotal),
                    description = choice.coverLabel(),
                    selected = selectedUuid == choice.copy.uuid,
                    photos = photos,
                    onClick = { onSelect(choice.copy.uuid) },
                )
            }
        }
    }
}

/** One choice of [CoverPicker]: the card with a 2 dp sea-glass outline when selected, and a short label under it. */
@Composable
private fun CoverChoice(
    cover: CopyWithCard?,
    label: String,
    description: String,
    selected: Boolean,
    photos: OwnPhotos,
    onClick: () -> Unit,
) {
    val shape = MaterialTheme.shapes.small
    Column(
        Modifier
            .width(CoverChoiceWidth)
            .focusRing(shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val outline = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null
        CoverThumb(
            cover = cover,
            photos = photos,
            width = CoverChoiceWidth,
            modifier = if (outline != null) Modifier.border(outline, shape) else Modifier,
        )
        Text(
            label,
            style = numberStyle(MaterialTheme.typography.labelSmall),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Spacing.xs).clearAndSetSemantics {},
        )
    }
}

private val CoverChoiceWidth = 64.dp
