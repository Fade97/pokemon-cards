// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.collections

import de.fabianduerkop.cardindex.data.collections.CardDetail
import de.fabianduerkop.cardindex.data.db.CardEntity
import de.fabianduerkop.cardindex.data.db.CardSetEntity
import de.fabianduerkop.cardindex.data.db.CopyWithCard
import de.fabianduerkop.cardindex.data.db.OwnedCopyEntity
import de.fabianduerkop.cardindex.data.images.OwnPhotos
import de.fabianduerkop.cardindex.model.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/** Which images the card viewer pages through for a card detail, and which one it opens on (#166). */
class CardDetailImagesTest {
    private val set = CardSetEntity("sv03.5", "151", "SV", "en", 165, Region.INTERNATIONAL)
    private val base = "https://assets.tcgdex.net/en/sv/sv03.5/025"

    private fun card(language: String = "en", imageUrl: String? = base) =
        CardEntity("sv03.5-025-$language", "sv03.5", "025", "Pikachu", language, null, null, imageUrl)

    private fun copy(uuid: String, language: String = "en") = CopyWithCard(
        copy = OwnedCopyEntity(uuid = uuid, cardId = "sv03.5-025-$language", cardLanguage = language, createdAt = Instant.EPOCH),
        card = card(language),
        set = set,
    )

    private fun detail(vararg copies: CopyWithCard, imageUrl: String? = base) =
        CardDetail(card = card(imageUrl = imageUrl), set = set, prints = emptyList(), copies = copies.toList())

    private fun photos(vararg uuids: String, preferOwn: Boolean = false) =
        OwnPhotos(uuids.toSet(), preferOwn) { File("/photos/$it.jpg") }

    @Test
    fun `without photos the viewer shows the official art in high quality`() {
        val images = detailImages(detail(copy("a")), photos())

        assertEquals(listOf(DetailImage.Official("$base/high.webp")), images.images)
        assertEquals(0, images.heroIndex)
    }

    @Test
    fun `own photos follow the official art, the shown language first`() {
        val images = detailImages(detail(copy("de1", "de"), copy("en1"), copy("en2")), photos("de1", "en2"))

        assertTrue(images.images[0] is DetailImage.Official)
        assertEquals(
            listOf("en2", "de1"),
            images.images.drop(1).map { (it as DetailImage.OwnPhoto).copy.copy.uuid },
        )
        assertEquals(File("/photos/en2.jpg"), images.images[1].model)
        // The hero shows the official art unless the user prefers their photos.
        assertEquals(0, images.heroIndex)
    }

    @Test
    fun `preferring own photos opens the viewer on the photo the hero shows`() {
        val images = detailImages(detail(copy("de1", "de"), copy("en1")), photos("de1", "en1", preferOwn = true))

        assertEquals(1, images.heroIndex)
        assertEquals("en1", (images.images[images.heroIndex] as DetailImage.OwnPhoto).copy.copy.uuid)
    }

    @Test
    fun `a card without CDN art shows the own photo as the hero`() {
        val images = detailImages(detail(copy("a"), imageUrl = null), photos("a"))

        assertEquals(1, images.images.size)
        assertEquals(0, images.heroIndex)
        assertTrue(images.images[0] is DetailImage.OwnPhoto)
    }

    @Test
    fun `no art and no photo leaves nothing to view`() {
        val images = detailImages(detail(copy("a"), imageUrl = null), photos())

        assertTrue(images.images.isEmpty())
        assertEquals(0, images.heroIndex)
    }

    @Test
    fun `a copy's photo is found by its uuid`() {
        val images = detailImages(detail(copy("a"), copy("b")), photos("b"))

        assertEquals(1, images.indexOfPhoto("b"))
        assertNull(images.indexOfPhoto("a"))
        assertNull(images.indexOfPhoto("missing"))
    }
}
