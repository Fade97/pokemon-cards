// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import de.fabianduerkop.cardindex.model.ReviewReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JudgeMatchTest {
    private fun card(localId: String, official: Int, lang: String = "de") =
        RefCard("$lang/x-$localId", "x-$localId", lang, "x", "Set", localId, official, "Card $localId", "https://x", false)

    private fun match(card: RefCard, similarity: Float) = Match(0, card, similarity)

    @Test
    fun confirmedNumberWithEnoughSimilarityIsConfident() {
        val reranked = listOf(match(card("006", 165), 0.62f), match(card("025", 165), 0.50f))
        assertNull(judgeMatch(reranked, NumberReading("6", "165"), minSimilarity = 0.45f))
    }

    @Test
    fun withoutNumberThePictureAloneIsNeverEnough() {
        val reranked = listOf(match(card("006", 165), 0.95f), match(card("025", 165), 0.30f))
        assertEquals(ReviewReason.NUMBER_NOT_READ, judgeMatch(reranked, null, minSimilarity = 0.45f))
    }

    @Test
    fun numberOfAnotherCardOrWeakMatchIsUnsure() {
        val reranked = listOf(match(card("006", 165), 0.80f))
        assertEquals(ReviewReason.NUMBER_DIFFERS, judgeMatch(reranked, NumberReading("7", "165"), 0.45f))
        assertEquals(ReviewReason.NUMBER_DIFFERS, judgeMatch(reranked, NumberReading("6", "182"), 0.45f))
        val weak = listOf(match(card("006", 165), 0.40f))
        assertEquals(ReviewReason.NUMBER_DIFFERS, judgeMatch(weak, NumberReading("6", "165"), 0.45f))
    }

    @Test
    fun sameNumberInAnotherLanguageTooCloseIsUnsure() {
        val de = match(card("006", 165, "de"), 0.700f)
        val en = match(card("006", 165, "en"), 0.690f)
        assertEquals(ReviewReason.SIMILAR_CARDS, judgeMatch(listOf(de, en), NumberReading("6", "165"), 0.45f))
        val farEn = match(card("006", 165, "en"), 0.60f)
        assertNull(judgeMatch(listOf(de, farEn), NumberReading("6", "165"), 0.45f))
    }
}

class LiveScanDeciderTest {
    private val config = DeciderConfig(
        confirmFrames = 2, reviewAfterMs = 2000, minReviewFrames = 3, leaveMs = 800, sameCardCooldownMs = 2500,
        switchFrames = 3,
    )
    private val decider = LiveScanDecider<String>(config)
    private var now = 0L

    private fun sure(key: String, related: Set<String> = emptySet(), similarity: Float = 0.7f) =
        FrameSignal.Identified(key, related, similarity, unsure = null, payload = "$key@$similarity")

    private fun unsure(key: String, related: Set<String> = emptySet(), similarity: Float = 0.6f, reason: ReviewReason = ReviewReason.NUMBER_NOT_READ) =
        FrameSignal.Identified(key, related, similarity, unsure = reason, payload = "$key@$similarity")

    /** Feeds one frame 100 ms after the previous one. */
    private fun frame(signal: FrameSignal<String>): ScanEvent<String>? {
        now += 100
        return decider.onFrame(now, signal)
    }

    private fun frames(n: Int, signal: () -> FrameSignal<String>): List<ScanEvent<String>> =
        (1..n).mapNotNull { frame(signal()) }

    @Test
    fun twoAgreeingFramesEndingConfidentAddOnce() {
        assertNull(frame(unsure("a")))
        val add = frame(sure("a"))
        assertTrue(add is ScanEvent.Add)
        // The card stays in view for a long time: never added again, and no OCR needed for it.
        assertEquals(emptyList<ScanEvent<String>>(), frames(50) { sure("a") })
        assertTrue(decider.isHandled("a"))
    }

    @Test
    fun oneConfidentFrameAloneIsNotEnough() {
        assertNull(frame(sure("a")))
        assertNull(frame(sure("b")))
    }

    @Test
    fun cardThatStaysUnsureGoesToReviewOnceWithTheMostSimilarFrame() {
        var events = frames(10) { unsure("a", similarity = 0.5f) }
        assertEquals(emptyList<ScanEvent<String>>(), events)
        assertNull(frame(unsure("a", similarity = 0.9f, reason = ReviewReason.SIMILAR_CARDS)))
        events = frames(15) { unsure("a", similarity = 0.5f) }
        assertEquals(1, events.size)
        val review = events.single() as ScanEvent.Review
        assertEquals("a@0.9", review.payload)
        assertEquals(ReviewReason.SIMILAR_CARDS, review.reason)
        assertEquals(emptyList<ScanEvent<String>>(), frames(30) { unsure("a") })
    }

    @Test
    fun reviewPicksTheCardMostFramesAgreedOn() {
        frames(4) { unsure("a", related = setOf("b")) }
        frames(2) { unsure("b", related = setOf("a")) }
        val events = frames(20) { FrameSignal.Unreadable }
        assertEquals("a", (events.single() as ScanEvent.Review).payload.substringBefore('@'))
    }

    @Test
    fun unsureCardTakenAwayGoesToReviewButAGlimpseDoesNot() {
        frames(3) { unsure("a") }
        val events = frames(9) { FrameSignal.Empty }
        assertTrue(events.single() is ScanEvent.Review)

        now += 10_000
        frames(2) { unsure("b") }
        assertEquals(emptyList<ScanEvent<String>>(), frames(20) { FrameSignal.Empty })
    }

    @Test
    fun flickerBetweenRelatedCandidatesIsTheSameCard() {
        frame(unsure("a", related = setOf("a-en")))
        frame(sure("a", related = setOf("a-en")))
        assertEquals(emptyList<ScanEvent<String>>(), frames(30) { unsure("a-en", related = setOf("a")) })
    }

    @Test
    fun nextCardAfterTheGuideWasEmptyIsAddedAtOnce() {
        frames(2) { sure("a") }
        frames(9) { FrameSignal.Empty }
        assertNull(frame(sure("b")))
        assertTrue(frame(sure("b")) is ScanEvent.Add)
    }

    @Test
    fun unrelatedCardWithoutGapNeedsARunOfFramesToReplaceTheHandledOne() {
        frames(2) { sure("a") }
        // One misread frame of another card while "a" lies in view changes nothing.
        assertNull(frame(sure("x")))
        frames(3) { sure("a") }
        assertTrue(decider.isHandled("a"))
        // A real swap: "b" in every frame.
        val events = frames(5) { sure("b") }
        assertEquals(listOf("b"), events.map { it.payload.substringBefore('@') })
    }

    @Test
    fun sameCardBackWithinTheCooldownIsNotAddedAgain() {
        frames(2) { sure("a") }
        frames(9) { FrameSignal.Empty } // gone 900 ms, last seen at 200 ms
        assertFalse(decider.isHandled("a"))
        assertEquals(emptyList<ScanEvent<String>>(), frames(5) { sure("a") })
    }

    @Test
    fun secondCopyOfTheSameCardAfterTheCooldownIsAdded() {
        frames(2) { sure("a") }
        frames(30) { FrameSignal.Empty } // 3 s without a card
        val events = frames(2) { sure("a") }
        assertTrue(events.single() is ScanEvent.Add)
    }

    @Test
    fun movingFramesKeepTheCardPresent() {
        frames(2) { sure("a") }
        frames(20) { FrameSignal.Unreadable }
        frames(5) { FrameSignal.Empty } // 500 ms < leaveMs
        assertTrue(decider.isHandled("a"))
    }
}
