// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import de.fabianduerkop.cardindex.data.collections.CardDetail
import de.fabianduerkop.cardindex.data.db.CopyWithCard
import de.fabianduerkop.cardindex.data.images.ImageQuality
import de.fabianduerkop.cardindex.data.images.OwnPhotos
import de.fabianduerkop.cardindex.data.images.cardImageUrl
import java.io.File

/** One image of a card detail that the card viewer can show (#166). */
internal sealed interface DetailImage {
    /** What Coil loads. */
    val model: Any

    /** The official art from the CDN, in high quality. */
    data class Official(override val model: String) : DetailImage

    /** The user's own photo of one of their copies. */
    data class OwnPhoto(override val model: File, val copy: CopyWithCard) : DetailImage
}

/**
 * The viewer's pages for a card detail and the one the hero shows, which the viewer opens on.
 *
 * @property heroIndex the page of [images] the detail's hero shows; 0 when [images] is empty.
 */
internal data class DetailImages(val images: List<DetailImage>, val heroIndex: Int) {
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
    val official = cardImageUrl(detail.card.imageUrl, ImageQuality.HIGH)?.let(DetailImage::Official)
    val own = heroCopyOrder(detail).mapNotNull { item -> photos.of(item.copy.uuid)?.let { DetailImage.OwnPhoto(it, item) } }
    val images = listOfNotNull(official) + own
    val heroShowsOwn = own.isNotEmpty() && (photos.preferOwn || official == null)
    val heroIndex = if (heroShowsOwn && official != null) 1 else 0
    return DetailImages(images, heroIndex)
}

/** The copies in the order the hero looks for a photo: the shown language first, then the rest, each as listed. */
internal fun heroCopyOrder(detail: CardDetail): List<CopyWithCard> =
    detail.copies.sortedByDescending { it.card.language == detail.card.language }
