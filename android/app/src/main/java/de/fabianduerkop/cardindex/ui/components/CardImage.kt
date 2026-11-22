// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import de.fabianduerkop.cardindex.data.db.CardEntity
import de.fabianduerkop.cardindex.data.images.ImageQuality
import de.fabianduerkop.cardindex.data.images.cardImageUrl
import de.fabianduerkop.cardindex.ui.theme.TabularFigures
import java.io.File

/** Width to height of a card (63 × 88 mm). */
const val CARD_ASPECT = 63f / 88f

private val Grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/**
 * Which image a card shows: the user's own photo when they prefer it or the CDN has none (ADR 0009), else the TCGdex
 * CDN image, else nothing (the numbered placeholder).
 */
fun cardImageModel(card: CardEntity?, ownPhoto: File?, preferOwnPhoto: Boolean, quality: ImageQuality): Any? {
    val cdn = cardImageUrl(card?.imageUrl, quality)
    return when {
        ownPhoto != null && (preferOwnPhoto || cdn == null) -> ownPhoto
        else -> cdn
    }
}

/**
 * A card face from the TCGdex CDN (cached on disk by the app's Coil loader) or the user's own photo. The numbered
 * placeholder sits underneath, so it shows while loading, offline before the first load, and for cards without an
 * image. [missing] cards are drawn in black and white, as in the set grid's Cards view (ADR 0010).
 */
@Composable
fun CardImage(
    card: CardEntity?,
    localId: String,
    printedTotal: Int?,
    modifier: Modifier = Modifier,
    ownPhoto: File? = null,
    preferOwnPhoto: Boolean = false,
    quality: ImageQuality = ImageQuality.LOW,
    missing: Boolean = false,
) {
    val model = cardImageModel(card, ownPhoto, preferOwnPhoto, quality)
    var loaded by remember(model) { mutableStateOf(false) }
    // Fainter on a dark page, where a grey card at the light theme's strength outshone the owned art (ADR 0014, #218).
    val missingAlpha = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) 0.4f else 0.55f
    Box(modifier.aspectRatio(CARD_ASPECT).clip(MaterialTheme.shapes.small)) {
        // Once the image is in, the placeholder goes: under a faded missing card its number showed through the art
        // (#218). The plain card colour stays behind the faded image.
        if (!loaded) {
            CardPlaceholder(card?.localId ?: localId, printedTotal, Modifier.fillMaxSize())
        } else if (missing) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant))
        }
        if (model != null) {
            AsyncImage(
                model = model,
                // The surrounding row or slot describes the card; the image adds nothing for screen readers.
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = if (missing) Grayscale else null,
                onSuccess = { loaded = true },
                modifier = Modifier.fillMaxSize().then(if (missing) Modifier.alpha(missingAlpha) else Modifier),
            )
        }
    }
}

/**
 * The stand-in for a card without an image (ADR 0010): a muted card shape with its collector number, on a quiet
 * outline so it reads as a card in both themes. The number grows with the shape, so a large placeholder (card detail)
 * still says clearly which card it stands for; small ones (lists, slots) keep the label size.
 */
@Composable
fun CardPlaceholder(localId: String, printedTotal: Int?, modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier
            .aspectRatio(CARD_ASPECT)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        val style = when {
            maxWidth >= 140.dp -> MaterialTheme.typography.headlineMedium
            maxWidth >= 80.dp -> MaterialTheme.typography.titleMedium
            else -> MaterialTheme.typography.labelMedium
        }
        Text(
            text = padCollectorNumber(localId, printedTotal),
            style = style.merge(TextStyle(fontFeatureSettings = TabularFigures)),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(2.dp),
        )
    }
}
