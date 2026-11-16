// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import kotlin.math.max
import kotlin.math.min

/** Integer pixel rectangle (left, top, width, height). */
data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int)

/**
 * The card guide of the live scan: a centred 63:88 box the user places the card in. Detection only searches
 * [searchArea], the guide plus a small margin, so neighbouring cards (binder pages, a pile on the table) and sleeve
 * edges outside it are ignored, and the card fills most of the searched area.
 */
data class ScanGuide(val box: Quad, val searchArea: PixelRect) {
    companion object {
        /** Guide height as a share of the frame height (capped by [MAX_WIDTH] of the frame width). */
        const val HEIGHT = 0.78
        const val MAX_WIDTH = 0.90

        /** Extra search margin around the guide, as a share of the guide's size. */
        const val MARGIN = 0.07

        fun forFrame(width: Int, height: Int): ScanGuide {
            var gh = HEIGHT * height
            var gw = gh * CARD_ASPECT
            if (gw > MAX_WIDTH * width) {
                gw = MAX_WIDTH * width
                gh = gw / CARD_ASPECT
            }
            val x0 = (width - gw) / 2
            val y0 = (height - gh) / 2
            val box = listOf(Pt(x0, y0), Pt(x0 + gw, y0), Pt(x0 + gw, y0 + gh), Pt(x0, y0 + gh))
            val left = max(0, (x0 - MARGIN * gw).toInt())
            val top = max(0, (y0 - MARGIN * gh).toInt())
            val right = min(width, (x0 + gw + MARGIN * gw).toInt())
            val bottom = min(height, (y0 + gh + MARGIN * gh).toInt())
            return ScanGuide(box, PixelRect(left, top, right - left, bottom - top))
        }
    }
}

/**
 * Default minimum cosine similarity of the best match before a card is shown as identified. 0.45 per the real-photo
 * evaluation in ml/REPORT.md (29 photos: same hits as 0.50, a little more room).
 */
const val DEFAULT_MIN_SIMILARITY = 0.45

/**
 * Whether a frame's identification counts as "stable": over the last [windowMs], at least [minFrames] frames were
 * identified (similarity above the minimum) and all of them were the same card. Frames rejected by the gate or
 * below the similarity minimum are ignored instead of resetting the count, so one "hold still" frame does not
 * undo a stable match.
 */
class MatchStability(private val windowMs: Long = 1200, private val minFrames: Int = 3) {
    private val history = ArrayDeque<Pair<Long, String>>()

    /** Records an identified frame at [timeMs]; returns whether the card is stable now. */
    fun add(timeMs: Long, key: String): Boolean {
        history.addLast(timeMs to key)
        return isStable(timeMs)
    }

    /** Whether the frames still inside the window agree (call on rejected frames to let old ones expire). */
    fun isStable(timeMs: Long): Boolean {
        while (history.isNotEmpty() && timeMs - history.first().first > windowMs) history.removeFirst()
        return history.size >= minFrames && history.all { it.second == history.first().second }
    }

    fun clear() = history.clear()
}
