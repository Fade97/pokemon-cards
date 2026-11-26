// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import de.fabianduerkop.cardindex.data.collections.CardDetail
import de.fabianduerkop.cardindex.data.db.CopyWithCard
import de.fabianduerkop.cardindex.data.images.ImageQuality
import de.fabianduerkop.cardindex.data.images.OwnPhotos
import de.fabianduerkop.cardindex.data.images.cardImageUrl
import de.fabianduerkop.cardindex.ui.components.FoilFinish
import java.io.File

/** One image of a card detail that the card viewer can show (#166). */
internal sealed interface DetailImage {
    /** What Coil loads. */
    val model: Any

    /**
     * The official art from the CDN, in high quality.
     *
     * @property foil the simulated tilt light over it (#167, ADR 0015); see [officialFoil].
     */
    data class Official(override val model: String, val foil: FoilFinish? = null) : DetailImage

    /** The user's own photo of one of their copies. */
    data class OwnPhoto(override val model: File, val copy: CopyWithCard) : DetailImage
}

/**
 * The viewer's pages for a card detail and the one the hero shows, which the viewer opens on.
 *
 * @property heroIndex the page of [images] the detail's hero shows; null when the hero shows the numbered placeholder
 *   (no official art and no photo of a copy in the shown language), so it must not be described as any page (#250).
 */
internal data class DetailImages(val images: List<DetailImage>, val heroIndex: Int?) {
    /** The page of the photo of the copy with [copyUuid], if it has one. */
    fun indexOfPhoto(copyUuid: String): Int? =
        images.indexOfFirst { it is DetailImage.OwnPhoto && it.copy.copy.uuid == copyUuid }.takeIf { it >= 0 }
}

/**
 * The official art first, then the photo of each copy that has one: copies in the shown language first, as the hero
 * picks them. The hero shows the first own photo when the user prefers their photos or the CDN has no image, like
 * [de.fabianduerkop.cardindex.ui.components.cardImageModel]; otherwise the official art.
 */
internal fun detailImages(detail: CardDetail, photos: OwnPhotos): DetailImages {
    val official = cardImageUrl(detail.card.imageUrl, ImageQuality.HIGH)?.let { DetailImage.Official(it, officialFoil(detail)) }
    val own = heroCopyOrder(detail).mapNotNull { item -> photos.of(item.copy.uuid)?.let { DetailImage.OwnPhoto(it, item) } }
    val images = listOfNotNull(official) + own
    // Only a photo of a copy in the shown language stands in for the print on the hero (#250); others stay pages.
    val heroShowsOwn = own.firstOrNull()?.copy?.card?.language == detail.card.language && (photos.preferOwn || official == null)
    val heroIndex = when {
        official != null -> if (heroShowsOwn) 1 else 0
        heroShowsOwn -> 0
        else -> null
    }
    return DetailImages(images, heroIndex)
}

/** The copies in the order the hero looks for a photo: the shown language first, then the rest, each as listed. */
internal fun heroCopyOrder(detail: CardDetail): List<CopyWithCard> =
    detail.copies.sortedByDescending { it.card.language == detail.card.language }

/**
 * The tilt light over the official art (#167, ADR 0015): only when the user owns the shown print (same card, same
 * language) as Holo or Reverse Holo, Holo first. Copies in other languages are other prints with their own art.
 */
internal fun officialFoil(detail: CardDetail): FoilFinish? = FoilFinish.of(
    detail.copies
        .filter { it.card.id == detail.card.id && it.card.language == detail.card.language }
        .map { it.copy.finish },
)
