// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import kotlin.math.max
import kotlin.math.min

/**
 * Two-pass detection for single photos (issue #78), a port of `guide_crop`, `refine`, `keep_higher_sim` and
 * `two_pass` in ml/detect.py: detect, cut the guide box around the first quad, run the learned corner detector again
 * on that box, recognise both crops and keep the second only when its top-1 similarity is strictly higher. The second
 * pass never decides alone. Pure arithmetic, so the unit tests pin it against the Python values.
 *
 * Only the photo/lab path uses it; live scan already frames the card with the guide, where ml/REPORT.md measured no
 * gain ("Two-pass corner detection (#78)").
 */
object TwoPass {
    /** The card fills this share of the guide box height, like the live-scan guide ([ScanGuide.HEIGHT]). */
    const val GUIDE_CARD_HEIGHT = 0.78

    /** Pixels: a guide box narrower or lower than this is not worth a second detector call. */
    const val MIN_REFINE_BOX = 24

    /**
     * The box the user would frame around [quad] (`guide_crop`): card height = 78 % of the box, 63:88 box, 7 %
     * margin, clipped to the [width] × [height] image. The card height is the mean of the two longest sides, so the
     * corner order does not matter. Python computes in float32 and this in double; the int truncation is the same.
     */
    fun guideBox(quad: Quad, width: Int, height: Int): PixelRect {
        val c = quad.mean()
        val sides = List(4) { (quad[it] - quad[(it + 3) % 4]).norm() }.sorted()
        val cardH = (sides[2] + sides[3]) / 2
        val gh = cardH / GUIDE_CARD_HEIGHT
        val gw = gh * 63 / 88
        val l = max(0.0, c.x - gw / 2 * 1.07).toInt()
        val t = max(0.0, c.y - gh / 2 * 1.07).toInt()
        val r = min(width.toDouble(), c.x + gw / 2 * 1.07).toInt()
        val b = min(height.toDouble(), c.y + gh / 2 * 1.07).toInt()
        return PixelRect(l, t, r - l, b - t)
    }

    /** The box of the second pass (`refine`), or null when [guideBox] is under [MIN_REFINE_BOX] on either side. */
    fun refineBox(quad: Quad, width: Int, height: Int): PixelRect? =
        guideBox(quad, width, height).takeIf { it.width >= MIN_REFINE_BOX && it.height >= MIN_REFINE_BOX }

    /**
     * The selection rule (`keep_higher_sim`): [first] and [second] are (top-1 similarity, value) of the two passes.
     * The second wins only with a strictly higher similarity; a tie or a missing second pass keeps the first.
     * Returns (value, usedSecond).
     */
    fun <T> keepHigherSim(first: Pair<Float, T>, second: Pair<Float, T>?): Pair<T, Boolean> =
        if (second == null || !(second.first > first.first)) first.second to false else second.second to true
}
