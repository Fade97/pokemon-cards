// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class RecognitionLogicTest {
    private fun card(localId: String, official: Int, lang: String = "en") =
        RefCard("$lang/x-$localId", "x-$localId", lang, "x", "Set", localId, official, "Card $localId", "https://x", false)

    @Test
    fun thumbnailUrlIsNullForCardsWithoutImage() {
        assertEquals("https://x/low.webp", card("1", 10).thumbnailUrl)
        assertNull(card("1", 10).copy(imageUrl = "").thumbnailUrl)
    }

    @Test
    fun numberKeyStripsLeadingZerosOfNumericIdsOnly() {
        assertEquals("6", numberKey("006"))
        assertEquals("0", numberKey("000"))
        assertEquals("TG01", numberKey("tg01"))
    }

    @Test
    fun parsesCollectorNumberWithTotal() {
        assertEquals(NumberReading("6", "165"), parseCollectorNumber(listOf("SV2a JP", "006/165")))
        assertEquals(NumberReading("25", "165"), parseCollectorNumber(listOf("025 / 165 ★")))
        assertNull(parseCollectorNumber(listOf("Illus. Someone", "165")))
    }

    @Test
    fun rerankMovesNumberAndTotalMatchesFirstAndKeepsOrderInsideTiers() {
        val a = Match(0, card("12", 165), 0.9f)
        val b = Match(1, card("6", 182), 0.8f)
        val c = Match(2, card("6", 165), 0.7f)
        assertEquals(listOf(c, b, a), rerank(listOf(a, b, c), NumberReading("6", "165")))
        assertEquals(listOf(b, c, a), rerank(listOf(a, b, c), NumberReading("6", null)))
        assertEquals(listOf(a, b, c), rerank(listOf(a, b, c), null))
    }

    @Test
    fun topKIsDescendingAndStableOnTies() {
        assertEquals(listOf(2, 0, 3), topKIndices(floatArrayOf(0.5f, 0.1f, 0.9f, 0.5f), 3))
    }

    @Test
    fun orderedQuadIsTopLeftClockwiseAndPortrait() {
        val portrait = listOf(Pt(10.0, 100.0), Pt(80.0, 0.0), Pt(10.0, 0.0), Pt(80.0, 100.0)).ordered()
        assertEquals(listOf(Pt(10.0, 0.0), Pt(80.0, 0.0), Pt(80.0, 100.0), Pt(10.0, 100.0)), portrait)
        // A landscape card: the long side becomes vertical, so the first corner is not the top-left pixel.
        val landscape = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 70.0), Pt(0.0, 70.0)).ordered()
        assertEquals(Pt(100.0, 0.0), landscape[0])
    }
}

class OutlineGateTest {
    private fun det(dx: Double = 0.0, score: Double = 0.5) = Detection(
        listOf(Pt(100.0 + dx, 100.0), Pt(730.0 + dx, 100.0), Pt(730.0 + dx, 980.0), Pt(100.0 + dx, 980.0)),
        score,
    )

    @Test
    fun rejectsMissingAndLowScoreOutlines() {
        assertEquals(GateResult.NoOutline, outlineGate(null, det(), 0.3))
        assertEquals(GateResult.LowScore(0.2), outlineGate(det(score = 0.2), det(), 0.3))
    }

    @Test
    fun rejectsOutlinesThatAreNotCardShaped() {
        // Twisted (two corners swapped), as the corner model returns for an empty room.
        val twisted = Detection(listOf(Pt(100.0, 100.0), Pt(730.0, 980.0), Pt(730.0, 100.0), Pt(100.0, 980.0)), 0.5)
        assertEquals(GateResult.BadShape, outlineGate(twisted, det(), 0.3))
        // Two cards of a binder page merged: twice as wide as a card.
        val wide = Detection(listOf(Pt(100.0, 100.0), Pt(1360.0, 100.0), Pt(1360.0, 980.0), Pt(100.0, 980.0)), 0.5)
        assertEquals(GateResult.BadShape, outlineGate(wide, det(), 0.3))
        // Sheared: convex and roughly card-sized, but the corners are about 40 degrees off square.
        val sheared = Detection(listOf(Pt(100.0, 100.0), Pt(730.0, 100.0), Pt(1430.0, 980.0), Pt(800.0, 980.0)), 0.5)
        assertEquals(GateResult.BadShape, outlineGate(sheared, det(), 0.3))
    }

    @Test
    fun cardShapeAllowsPerspective() {
        // A card tilted away from the phone: the top edge is shorter (trapezoid), angles about 10 degrees off.
        val tilted = listOf(Pt(180.0, 100.0), Pt(650.0, 100.0), Pt(730.0, 980.0), Pt(100.0, 980.0))
        assertTrue(tilted.looksLikeCard())
        assertTrue(det().quad.looksLikeCard())
    }

    @Test
    fun needsTheSameOutlineInTwoFrames() {
        assertEquals(GateResult.Moving, outlineGate(det(), null, 0.3))
        assertEquals(GateResult.Accepted, outlineGate(det(dx = 10.0), det(), 0.3)) // 10 px of a ~1,080 px diagonal
        assertEquals(GateResult.Moving, outlineGate(det(dx = 80.0), det(), 0.3))
    }
}

class ScanGuideTest {
    @Test
    fun guideIsCentredCardShapedAndSearchAreaStaysInFrame() {
        val g = ScanGuide.forFrame(1080, 1440)
        val (w, h) = g.box.widthHeight()
        assertEquals(CARD_ASPECT, w / h, 1e-9)
        assertEquals(0.78 * 1440, h, 1e-9)
        assertEquals(540.0, g.box.mean().x, 1e-9)
        val a = g.searchArea
        assert(a.x >= 0 && a.y >= 0 && a.x + a.width <= 1080 && a.y + a.height <= 1440)
        assert(a.width > w && a.height < 1440)
    }

    @Test
    fun wideGuideIsCappedByFrameWidth() {
        val (w, _) = ScanGuide.forFrame(600, 1440).box.widthHeight()
        assertEquals(0.9 * 600, w, 1e-9)
    }
}

class MatchStabilityTest {
    @Test
    fun stableAfterThreeAgreeingFramesAndToleratesGaps() {
        val s = MatchStability(windowMs = 1200, minFrames = 3)
        assertEquals(false, s.add(0, "a"))
        assertEquals(false, s.add(100, "a"))
        assertEquals(false, s.isStable(300)) // a rejected frame in between only lets old frames expire
        assertEquals(true, s.add(500, "a"))
        assertEquals(false, s.add(600, "b"))
        assertEquals(false, s.isStable(5000)) // everything expired
    }
}

class LanguageFilterTest {
    @Test
    fun excludedLanguagesNeverWin() {
        fun c(key: String, lang: String) = RefCard(key, key, lang, "s", "S", "1", 10, key, "u", false)
        val index = ReferenceIndex(listOf(c("en/a", "en"), c("de/a", "de")), floatArrayOf(1f, 0f, 0.8f, 0.6f), 2)
        val q = floatArrayOf(1f, 0f)
        assertEquals("en/a", index.topK(index.similarities(q), 1)[0].card.key)
        assertEquals("de/a", index.topK(index.similarities(q, setOf("de")), 1)[0].card.key)
        assertEquals(listOf("de", "en"), index.languages)
    }
}
