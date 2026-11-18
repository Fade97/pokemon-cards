// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import de.fabianduerkop.cardindex.model.ReviewReason

/**
 * Default minimum similarity lead of the best card over the next candidate with the same number. Below it the two
 * are too close to call; on the real photos (README) most such cases were the right card in another language.
 * A first guess, to be tuned with RealPhotoTest.
 */
const val DEFAULT_MIN_RIVAL_MARGIN = 0.02f

/** Whether [card] carries the read number and, if read, the printed total. */
fun carriesNumber(card: RefCard, reading: NumberReading): Boolean =
    numberKey(card.localId) == reading.number && (reading.total == null || card.official.toString() == reading.total)

/**
 * Verdict on one identified frame: null when the match may be added without asking, else why it needs review.
 * Confident means the collector number confirms the best candidate (after the rerank), its similarity reaches
 * [minSimilarity], and no other candidate with the same number is about as similar. The picture alone never
 * decides, since lookalikes (reprints, other languages) share the artwork but not the number.
 */
fun judgeMatch(
    reranked: List<Match>,
    reading: NumberReading?,
    minSimilarity: Float,
    minRivalMargin: Float = DEFAULT_MIN_RIVAL_MARGIN,
): ReviewReason? {
    val best = reranked.first()
    if (reading == null) return ReviewReason.NUMBER_NOT_READ
    if (!carriesNumber(best.card, reading) || best.similarity < minSimilarity) return ReviewReason.NUMBER_DIFFERS
    val rival = reranked.drop(1).firstOrNull { carriesNumber(it.card, reading) }
    if (rival != null && best.similarity - rival.similarity < minRivalMargin) return ReviewReason.SIMILAR_CARDS
    return null
}

/** What one analysed live frame showed. */
sealed interface FrameSignal<out T> {
    /** No card in the guide: no outline, or one too weak to be a card. */
    data object Empty : FrameSignal<Nothing>

    /** A card is there but was not identified in this frame (moving, or below the minimum similarity). */
    data object Unreadable : FrameSignal<Nothing>

    /**
     * Identified as [key]. [related] are the other candidates' keys: while a card is in view its best match may
     * flicker between them, and that must not count as a new card. [unsure] is null for a confident frame.
     */
    data class Identified<out T>(
        val key: String,
        val related: Set<String>,
        val similarity: Float,
        val unsure: ReviewReason?,
        val payload: T,
    ) : FrameSignal<T>
}

/** What to do with the card in view; emitted once per physical card. */
sealed interface ScanEvent<out T> {
    val payload: T

    data class Add<out T>(override val payload: T) : ScanEvent<T>

    data class Review<out T>(override val payload: T, val reason: ReviewReason) : ScanEvent<T>
}

/** Timing of [LiveScanDecider]. First guesses for a hand-held scan of one card after the other; tune on devices. */
data class DeciderConfig(
    /** Identified frames in a row that must agree on the card, the last one confident, before it is added. */
    val confirmFrames: Int = 2,
    /** A card that stays unsure this long (with at least [minReviewFrames] identified frames) goes to review. */
    val reviewAfterMs: Long = 2000,
    val minReviewFrames: Int = 3,
    /** No card in the guide for this long means the card was taken away. */
    val leaveMs: Long = 800,
    /** The same card again needs this long since it was last seen, so a shaky hand does not add a second copy. */
    val sameCardCooldownMs: Long = 2500,
    /** Identified frames in a row of an unrelated card that replace the handled one without an empty frame between. */
    val switchFrames: Int = 3,
)

/**
 * Turns the stream of live frames into one decision per physical card: add it (confident), send it to review
 * (still unsure after a while, or taken away unsure), or nothing (a glimpse). After a decision the card is
 * "handled": it is ignored while it stays in view, and for [DeciderConfig.sameCardCooldownMs] after it was last
 * seen, so a card lying in view or wobbling out of the guide is not added twice. Time comes from the caller, so the
 * logic is testable without a clock. Not thread-safe; call it from the frame thread.
 */
class LiveScanDecider<T>(private val config: DeciderConfig = DeciderConfig()) {
    private class Track<T>(val startedAt: Long) {
        /** Keys of the identified frames, oldest first, and whether each was confident. */
        val frames = ArrayList<Pair<String, Boolean>>()
        val related = HashSet<String>()

        /** The most similar frame per key: what a review item shows. */
        val best = HashMap<String, FrameSignal.Identified<T>>()

        /** The latest reason per key, for a review whose most similar frame happened to be confident. */
        val reasons = HashMap<String, ReviewReason>()
    }

    private class Handled(val key: String, val related: Set<String>, var lastSeenAt: Long, var inView: Boolean)

    private var track: Track<T>? = null
    private var handled: Handled? = null
    private var lastPresentAt = Long.MIN_VALUE / 2
    private var switchKey: String? = null
    private var switchCount = 0

    /**
     * Whether [key] is the card already decided and still in view; such frames need no number OCR. Its lookalike
     * candidates still get the OCR: only the number tells another card that resembles it from flicker.
     */
    fun isHandled(key: String): Boolean = handled?.let { it.inView && key == it.key } == true

    fun reset() {
        track = null
        handled = null
        resetSwitch()
    }

    /**
     * Forgets the decision on [key], so the next frames of that card decide it again: its decision could not be
     * saved (#126). Other cards are not touched.
     */
    fun forget(key: String) {
        if (handled?.key == key) handled = null
    }

    fun onFrame(now: Long, signal: FrameSignal<T>): ScanEvent<T>? = when (signal) {
        FrameSignal.Empty -> onEmpty(now)
        FrameSignal.Unreadable -> {
            lastPresentAt = now
            reviewIfOverdue(now)
        }
        is FrameSignal.Identified -> {
            lastPresentAt = now
            onIdentified(now, signal)
        }
    }

    private fun onEmpty(now: Long): ScanEvent<T>? {
        if (now - lastPresentAt < config.leaveMs) return reviewIfOverdue(now)
        // The card was taken away.
        handled?.inView = false
        resetSwitch()
        val t = track ?: return null
        track = null
        // Taken away while still unsure: a review item, unless it was only a glimpse.
        return if (t.frames.size >= config.minReviewFrames) commitReview(t, now, inView = false) else null
    }

    private fun onIdentified(now: Long, f: FrameSignal.Identified<T>): ScanEvent<T>? {
        val h = handled
        if (h != null && (f.key == h.key || f.key in h.related)) {
            if (h.inView || now - h.lastSeenAt < config.sameCardCooldownMs) {
                // A lookalike candidate that the number confirms in a run of frames is another card (say the next
                // one, which resembles the last); flicker between candidates does not keep a confirmed number.
                if (f.key != h.key && f.unsure == null) {
                    if (f.key == switchKey) switchCount++ else { switchKey = f.key; switchCount = 1 }
                } else if (f.key != switchKey) {
                    resetSwitch()
                }
                if (f.key != switchKey || switchCount < config.switchFrames) {
                    h.inView = true
                    h.lastSeenAt = now
                    return null
                }
                // The last card keeps its cooldown, but no longer claims this one.
                handled = Handled(h.key, h.related - f.key, h.lastSeenAt, inView = false)
            }
            // Gone long enough: another copy of the same card, decided like any new card.
        } else if (h != null && h.inView) {
            // An unrelated card while the handled one counts as in view: a binder page or a pile without a gap.
            // Only a run of agreeing frames replaces it, so a single misread frame does not start a new card.
            if (f.key == switchKey) switchCount++ else { switchKey = f.key; switchCount = 1 }
            if (switchCount < config.switchFrames) return null
            h.inView = false
        }
        resetSwitch()
        val t = track ?: Track<T>(now).also { track = it }
        t.frames += f.key to (f.unsure == null)
        t.related += f.related
        t.related += f.key
        if ((t.best[f.key]?.similarity ?: Float.NEGATIVE_INFINITY) < f.similarity) t.best[f.key] = f
        f.unsure?.let { t.reasons[f.key] = it }

        val recent = t.frames.takeLast(config.confirmFrames)
        if (f.unsure == null && recent.size == config.confirmFrames && recent.all { it.first == f.key }) {
            track = null
            handled = Handled(f.key, t.related, now, inView = true)
            return ScanEvent.Add(f.payload)
        }
        return reviewIfOverdue(now)
    }

    private fun reviewIfOverdue(now: Long): ScanEvent<T>? {
        val t = track ?: return null
        if (now - t.startedAt < config.reviewAfterMs || t.frames.size < config.minReviewFrames) return null
        track = null
        return commitReview(t, now, inView = true)
    }

    private fun commitReview(t: Track<T>, now: Long, inView: Boolean): ScanEvent<T> {
        // The card most frames agreed on; on a tie the one seen last.
        val counts = t.frames.groupingBy { it.first }.eachCount()
        val key = counts.keys.maxWith(compareBy<String> { counts.getValue(it) }.thenBy { k -> t.frames.indexOfLast { it.first == k } })
        val best = t.best.getValue(key)
        handled = Handled(key, t.related, now, inView)
        // A confident frame that never got a confirming neighbour: the best match kept changing.
        return ScanEvent.Review(best.payload, best.unsure ?: t.reasons[key] ?: ReviewReason.SIMILAR_CARDS)
    }

    private fun resetSwitch() {
        switchKey = null
        switchCount = 0
    }
}
