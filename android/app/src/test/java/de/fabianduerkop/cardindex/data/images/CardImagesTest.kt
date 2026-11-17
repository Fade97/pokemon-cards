// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.images

import de.fabianduerkop.cardindex.testing.card
import de.fabianduerkop.cardindex.ui.components.cardImageModel
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CardImagesTest {
    @Test
    fun cdnUrl_appendsTheQualityToTheTcgdexBase() {
        val base = "https://assets.tcgdex.net/en/sv/sv03.5/025"
        assertEquals("$base/low.webp", cardImageUrl(base, ImageQuality.LOW))
        assertEquals("$base/high.webp", cardImageUrl("$base/", ImageQuality.HIGH))
        assertNull(cardImageUrl(null, ImageQuality.LOW))
        assertNull(cardImageUrl(" ", ImageQuality.LOW))
    }

    @Test
    fun ownPhoto_standsInWhenTheCdnHasNoImage_orWhenPreferred() {
        val withImage = card("sv03.5-025", "sv03.5", "025", "en").copy(imageUrl = "https://assets.tcgdex.net/en/sv/sv03.5/025")
        val withoutImage = card("SV2a-025", "SV2a", "025", "ja")
        val photo = File("/photos/uuid.jpg")

        assertEquals(
            "https://assets.tcgdex.net/en/sv/sv03.5/025/low.webp",
            cardImageModel(withImage, photo, preferOwnPhoto = false, ImageQuality.LOW),
        )
        assertEquals(photo, cardImageModel(withImage, photo, preferOwnPhoto = true, ImageQuality.LOW))
        assertEquals(photo, cardImageModel(withoutImage, photo, preferOwnPhoto = false, ImageQuality.LOW))
        assertNull(cardImageModel(withoutImage, null, preferOwnPhoto = true, ImageQuality.LOW))
    }

    @Test
    fun scanStore_tracksWhichCopiesHaveAPhoto() = runTest {
        val dir = Files.createTempDirectory("scans").toFile()
        File(dir, "old.jpg").writeText("x") // a photo from an earlier run
        val store = ScanImageStore(dir, UnconfinedTestDispatcher(testScheduler))

        assertEquals(setOf("old"), store.copiesWithPhoto.first())

        store.save("new", "jpeg".byteInputStream())
        assertEquals(setOf("old", "new"), store.copiesWithPhoto.first())
        assertEquals("jpeg", store.fileFor("new").readText())
        assertFalse(File(dir, "new.jpg.tmp").exists())

        store.delete("old")
        assertEquals(setOf("new"), store.copiesWithPhoto.first())
        assertFalse(store.fileFor("old").exists())

        val photos = OwnPhotos(setOf("new"), preferOwn = false, fileFor = store::fileFor)
        assertEquals(store.fileFor("new"), photos.firstOf(listOf("old", "new")))
        assertNull(photos.of("old"))
        assertTrue(dir.deleteRecursively())
    }
}
