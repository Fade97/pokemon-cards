// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** A 2D point in image pixels. */
data class Pt(val x: Double, val y: Double) {
    operator fun plus(o: Pt) = Pt(x + o.x, y + o.y)
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    operator fun times(s: Double) = Pt(x * s, y * s)
    operator fun div(s: Double) = Pt(x / s, y / s)
    infix fun dot(o: Pt) = x * o.x + y * o.y
    fun norm() = hypot(x, y)
}

/** Card aspect ratio, 63 x 88 mm. */
const val CARD_ASPECT = 63.0 / 88.0

/** Four corners, ordered top-left, top-right, bottom-right, bottom-left once [ordered]. */
typealias Quad = List<Pt>

fun Quad.mean(): Pt = Pt(sumOf { it.x } / size, sumOf { it.y } / size)

fun Quad.scaled(s: Double): Quad = map { it * s }

/** Rotates the corner list so that corner [k] comes first (numpy `np.roll(q, -k, 0)`). */
fun Quad.roll(k: Int): Quad = List(size) { this[((it + k) % size + size) % size] }

fun Quad.widthHeight(): Pair<Double, Double> {
    val w = ((this[1] - this[0]).norm() + (this[2] - this[3]).norm()) / 2
    val h = ((this[3] - this[0]).norm() + (this[2] - this[1]).norm()) / 2
    return w to h
}

/** Max absolute coordinate difference between two quads (numpy `np.abs(a - b).max()`). */
fun Quad.maxAbsDiff(o: Quad): Double = indices.maxOf { maxOf(abs(this[it].x - o[it].x), abs(this[it].y - o[it].y)) }

/**
 * Orders 4 points as TL, TR, BR, BL with the long side vertical (portrait). Port of `order_quad` in ml/detect.py.
 */
fun Quad.ordered(): Quad {
    val c = mean()
    var q = sortedBy { atan2(it.y - c.y, it.x - c.x) }
    val s = q.indices.minBy { q[it].x + q[it].y }
    q = q.roll(s)
    val (w, h) = q.widthHeight()
    if (w > h) q = q.roll(1)
    return q
}

/** Solves p1 + d1·t = p2 + d2·u for the intersection of two lines; null if they are parallel. */
internal fun intersectLines(p1: Pt, d1: Pt, p2: Pt, d2: Pt): Pt? {
    // A = [d1, -d2] as columns; A·(t, u) = p2 - p1.
    val det = d1.x * (-d2.y) - (-d2.x) * d1.y
    if (abs(det) < 1e-6) return null
    val r = p2 - p1
    val t = (r.x * (-d2.y) - (-d2.x) * r.y) / det
    return p1 + d1 * t
}

/** Corner i is the intersection of line i-1 and line i (lines given as point + direction). */
internal fun intersectAll(lines: List<Pair<Pt, Pt>>): Quad? = List(4) { i ->
    val (p1, d1) = lines[(i + 3) % 4]
    val (p2, d2) = lines[i]
    intersectLines(p1, d1, p2, d2) ?: return null
}
