// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.geometry.Geometry
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/** A detected card: corners in input pixels (TL, TR, BR, BL) and the candidate score. */
data class Detection(val quad: Quad, val score: Double)

/**
 * Classic-CV card detection: edges and contours, best card-shaped quadrilateral, side refinement. A port of
 * `ml/detect.py` (same OpenCV version, same constants), so its results can be compared with the Python prototype.
 * All images are BGR `CV_8UC3` like OpenCV's Python API.
 */
object CardDetector {
    private const val WORK_SIZE = 640.0

    /** Returns the card quad in input pixels, or null when no card-shaped contour is found. */
    fun detect(bgr: Mat, expand: Boolean = true): Detection? {
        val h0 = bgr.rows().toDouble()
        val w0 = bgr.cols().toDouble()
        val s = min(WORK_SIZE / max(h0, w0), 1.0)
        val small = if (s < 1) {
            Mat().also {
                // Python round() rounds half to even, like kotlin.math.round.
                Imgproc.resize(bgr, it, Size(round(w0 * s), round(h0 * s)), 0.0, 0.0, Imgproc.INTER_AREA)
            }
        } else bgr
        try {
            val cands = candidates(small).sortedByDescending { it.first }
            if (cands.isEmpty()) return null
            var (bestScore, best) = cands[0]
            // Prefer a slightly larger candidate that encloses the best one (outer card edge vs. inner art frame).
            var ba = area(best)
            for ((sc, q) in cands.drop(1)) {
                val r = area(q) / ba
                if (r > 1.04 && r < 1.35 && sc > 0.5 * bestScore && contains(q, best)) {
                    best = q
                    bestScore = sc
                    ba = area(best)
                }
            }
            if (expand) best = snapSides(small, best).ordered()
            return Detection(best.scaled(1 / s), bestScore)
        } finally {
            if (small !== bgr) small.release()
        }
    }

    /** Perspective-warps the card to an upright [width] x [height] crop (63:88). */
    fun rectify(bgr: Mat, quad: Quad, width: Int = CARD_W, height: Int = CARD_H): Mat {
        val src = MatOfPoint2f(*quad.map { Point(it.x, it.y) }.toTypedArray())
        val w = width.toDouble()
        val h = height.toDouble()
        val dst = MatOfPoint2f(Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h))
        val m = Geometry.getPerspectiveTransform(src, dst)
        val out = Mat()
        Imgproc.warpPerspective(bgr, out, m, Size(w, h), Imgproc.INTER_AREA, Core.BORDER_REPLICATE)
        src.release(); dst.release(); m.release()
        return out
    }

    /** When detection fails, a centred 63:88 box covering most of the frame. */
    fun centerCropFallback(width: Int, height: Int): Quad {
        val w = width.toDouble()
        val h = height.toDouble()
        var ch = 0.8 * h
        var cw = ch * CARD_ASPECT
        if (cw > 0.95 * w) {
            cw = 0.95 * w
            ch = cw / CARD_ASPECT
        }
        val x0 = (w - cw) / 2
        val y0 = (h - ch) / 2
        return listOf(Pt(x0, y0), Pt(x0 + cw, y0), Pt(x0 + cw, y0 + ch), Pt(x0, y0 + ch))
    }

    // ------------------------------------------------------------------ candidates

    private fun area(q: Quad): Double {
        val m = MatOfPoint2f(*q.map { Point(it.x, it.y) }.toTypedArray())
        return Geometry.contourArea(m).also { m.release() }
    }

    private fun contains(outer: Quad, inner: Quad): Boolean {
        val m = MatOfPoint2f(*outer.map { Point(it.x, it.y) }.toTypedArray())
        return inner.all { Geometry.pointPolygonTest(m, Point(it.x, it.y), false) >= 0 }.also { m.release() }
    }

    /** Median of an 8-bit single-channel image, as numpy computes it (mean of the two middle values). */
    private fun median8u(ch: Mat): Double {
        val n = ch.total().toInt()
        val bytes = ByteArray(n)
        ch.get(0, 0, bytes)
        val hist = IntArray(256)
        for (b in bytes) hist[b.toInt() and 0xFF]++
        fun valueAt(index: Int): Int {
            var acc = 0
            for (v in 0..255) {
                acc += hist[v]
                if (acc > index) return v
            }
            return 255
        }
        return (valueAt((n - 1) / 2) + valueAt(n / 2)) / 2.0
    }

    /** The eight edge maps of `_edge_maps`: gray, HSV saturation, Lab a and b, each with two Canny thresholds. */
    private fun edgeMaps(img: Mat): Sequence<Mat> = sequence {
        val gray = Mat().also { Imgproc.cvtColor(img, it, Imgproc.COLOR_BGR2GRAY) }
        val hsv = Mat().also { Imgproc.cvtColor(img, it, Imgproc.COLOR_BGR2HSV) }
        val lab = Mat().also { Imgproc.cvtColor(img, it, Imgproc.COLOR_BGR2Lab) }
        val channels = listOf(
            gray,
            Mat().also { Core.extractChannel(hsv, it, 1) },
            Mat().also { Core.extractChannel(lab, it, 1) },
            Mat().also { Core.extractChannel(lab, it, 2) },
        )
        hsv.release(); lab.release()
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
        try {
            for (raw in channels) {
                val ch = Mat().also { Imgproc.bilateralFilter(raw, it, 7, 40.0, 7.0) }
                val v = median8u(ch)
                for ((lo, hi) in listOf(0.66 * v to 1.33 * v, 20.0 to 60.0)) {
                    val e = Mat().also { Imgproc.Canny(ch, it, max(5.0, lo), max(20.0, hi)) }
                    val d = Mat().also { Imgproc.dilate(e, it, k) }
                    val closed = Mat().also { Imgproc.morphologyEx(d, it, Imgproc.MORPH_CLOSE, k, Point(-1.0, -1.0), 2) }
                    e.release(); d.release()
                    yield(closed)
                    closed.release()
                }
                ch.release()
            }
        } finally {
            channels.forEach { it.release() }
            k.release()
        }
    }

    private fun candidates(small: Mat): List<Pair<Double, Quad>> {
        val frameArea = small.rows().toDouble() * small.cols()
        val out = ArrayList<Pair<Double, Quad>>()
        for (edges in edgeMaps(small)) {
            val contours = ArrayList<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_NONE)
            hierarchy.release()
            for (c in contours) {
                candidate(c, frameArea)?.let(out::add)
                c.release()
            }
        }
        return out
    }

    private fun candidate(contour: MatOfPoint, frameArea: Double): Pair<Double, Quad>? {
        val idx = MatOfInt()
        Geometry.convexHull(contour, idx)
        val all = contour.toArray()
        val hullPts = idx.toArray().map { all[it] }
        idx.release()
        val hull = MatOfPoint(*hullPts.toTypedArray())
        val hull2f = MatOfPoint2f(*hullPts.toTypedArray())
        try {
            val area = Geometry.contourArea(hull)
            if (area < 0.05 * frameArea || area > 0.98 * frameArea) return null
            val peri = Geometry.arcLength(hull2f, true)
            var quad: Quad? = null
            var rectBonus = 1.0
            for (eps in doubleArrayOf(0.01, 0.02, 0.035, 0.05)) {
                val approx = MatOfPoint2f()
                Geometry.approxPolyDP(hull2f, approx, eps * peri, true)
                val pts = approx.toArray()
                approx.release()
                if (pts.size == 4) {
                    quad = pts.map { Pt(it.x, it.y) }
                    break
                }
            }
            if (quad == null) {
                val box = Mat()
                Geometry.boxPoints(Geometry.minAreaRect(hull2f), box)
                quad = List(4) { Pt(box.get(it, 0)[0], box.get(it, 1)[0]) }
                box.release()
                rectBonus = 0.8
            }
            quad = quad.ordered()
            val qa = area(quad)
            if (qa <= 0) return null
            var fill = area / qa
            fill = min(fill, 1 / fill)
            val (w, hh) = quad.widthHeight()
            val aspectOk = exp(-((w / max(hh, 1e-6) - CARD_ASPECT) / 0.08).pow(2))
            if (aspectOk < 0.2 || fill < 0.8) return null
            val score = sqrt(area / frameArea) * aspectOk * fill.pow(6) * rectBonus
            return score to refine(hullPts.map { Pt(it.x, it.y) }, quad).ordered()
        } finally {
            hull.release()
            hull2f.release()
        }
    }

    /**
     * Fits a line to the contour points of each side and intersects neighbouring lines. Card corners are rounded,
     * so polygon-approximation corners sit slightly inside; line intersection fixes that.
     */
    private fun refine(pts: List<Pt>, quad: Quad): Quad {
        val lines = ArrayList<Pair<Pt, Pt>>(4)
        for (i in 0 until 4) {
            val a = quad[i]
            val d = quad[(i + 1) % 4] - a
            val l = d.norm() + 1e-6
            val n = Pt(-d.y, d.x) / l
            val sel = pts.filter {
                val t = ((it - a) dot d) / (l * l)
                val dist = abs((it - a) dot n)
                t > 0.15 && t < 0.85 && dist < max(4.0, 0.03 * l)
            }
            if (sel.size < 8) return quad
            val m = MatOfPoint2f(*sel.map { Point(it.x, it.y) }.toTypedArray())
            val line = Mat()
            Geometry.fitLine(m, line, Geometry.DIST_HUBER, 0.0, 0.01, 0.01)
            val v = FloatArray(4).also { line.get(0, 0, it) }
            m.release(); line.release()
            lines += Pt(v[2].toDouble(), v[3].toDouble()) to Pt(v[0].toDouble(), v[1].toDouble())
        }
        val out = intersectAll(lines) ?: return quad
        if (out.maxAbsDiff(quad) > 0.08 * (quad[2] - quad[0]).norm()) return quad
        return out
    }

    // ------------------------------------------------------------------ side snapping

    private fun aspectErr(q: Quad): Double {
        val (w, h) = q.widthHeight()
        return abs(w / max(h, 1e-6) - CARD_ASPECT)
    }

    /**
     * Snaps each side of a rough quad to the strongest straight edge nearby (`snap_sides` in ml/detect.py). Contour
     * hulls are biased outward (shadow halos, dilation) or catch the inner art frame of silver-bordered cards.
     */
    private fun snapSides(small: Mat, quad: Quad, band: Double = 0.07): Quad {
        val blurred = Mat().also { Imgproc.GaussianBlur(small, it, Size(3.0, 3.0), 0.0) }
        val lab8 = Mat().also { Imgproc.cvtColor(blurred, it, Imgproc.COLOR_BGR2Lab) }
        val lab = Mat().also { lab8.convertTo(it, CvType.CV_32F) }
        blurred.release(); lab8.release()
        val grads = (0 until 3).map { k ->
            val ch = Mat().also { Core.extractChannel(lab, it, k) }
            val gx = Mat().also { Imgproc.Sobel(ch, it, CvType.CV_32F, 1, 0, 3) }
            val gy = Mat().also { Imgproc.Sobel(ch, it, CvType.CV_32F, 0, 1, 3) }
            ch.release()
            gx to gy
        }
        lab.release()
        try {
            return snapSides(grads, small.cols(), small.rows(), quad, band)
        } finally {
            grads.forEach { (gx, gy) -> gx.release(); gy.release() }
        }
    }

    private fun snapSides(grads: List<Pair<Mat, Mat>>, width: Int, height: Int, quad: Quad, band: Double): Quad {
        val c = quad.mean()
        val h = ((quad[3] - quad[0]).norm() + (quad[2] - quad[1]).norm()) / 2
        val r = max(3, (band * h).toInt())
        val offs = DoubleArray(2 * r + 1) { (it - r).toDouble() }
        val nOff = offs.size
        val nT = 24
        val lines = ArrayList<Pair<Pt, Pt>>(4)
        val mapX = Mat(nOff, nT, CvType.CV_32F)
        val mapY = Mat(nOff, nT, CvType.CV_32F)
        val sampled = Mat()
        val xs = FloatArray(nOff * nT)
        val ys = FloatArray(nOff * nT)
        val buf = FloatArray(nOff * nT)
        try {
            for (i in 0 until 4) {
                val a = quad[i]
                val b = quad[(i + 1) % 4]
                val d = b - a
                var n = Pt(-d.y, d.x) / (d.norm() + 1e-6)
                if (((a + b) / 2.0 - c) dot n < 0) n = n * -1.0
                val pts = ArrayList<Triple<Pt, Pt, Double>>(2)
                for ((t0, t1) in listOf(0.12 to 0.45, 0.55 to 0.88)) {
                    for (o in 0 until nOff) for (k in 0 until nT) {
                        val t = t0 + (t1 - t0) * k / (nT - 1)
                        val px = a.x + d.x * t + offs[o] * n.x
                        val py = a.y + d.y * t + offs[o] * n.y
                        xs[o * nT + k] = px.coerceIn(0.0, width - 1.0).toFloat()
                        ys[o * nT + k] = py.coerceIn(0.0, height - 1.0).toFloat()
                    }
                    mapX.put(0, 0, xs)
                    mapY.put(0, 0, ys)
                    val g = DoubleArray(nOff * nT)
                    for ((gx, gy) in grads) {
                        Imgproc.remap(gx, sampled, mapX, mapY, Imgproc.INTER_LINEAR)
                        sampled.get(0, 0, buf)
                        val rx = buf.copyOf()
                        Imgproc.remap(gy, sampled, mapX, mapY, Imgproc.INTER_LINEAR)
                        sampled.get(0, 0, buf)
                        for (j in g.indices) g[j] += abs(rx[j] * n.x + buf[j] * n.y)
                    }
                    // 40th percentile across the samples of each offset, then a [0.25, 0.5, 0.25] smoothing.
                    val raw = DoubleArray(nOff) { o -> percentile(g.copyOfRange(o * nT, (o + 1) * nT), 0.40) }
                    val prof = DoubleArray(nOff) { k ->
                        0.5 * raw[k] + 0.25 * (if (k > 0) raw[k - 1] else 0.0) + 0.25 * (if (k < nOff - 1) raw[k + 1] else 0.0)
                    }
                    val pMax = prof.max()
                    val off = if (pMax < 8) 0.0 else {
                        val pk = prof.indices.first { prof[it] == pMax }
                        val ok = prof.indices.filter { k ->
                            prof[k] >= 0.6 * pMax &&
                                (k == 0 || prof[k] >= prof[k - 1]) &&
                                (k == nOff - 1 || prof[k] >= prof[k + 1]) &&
                                k > pk && (k - pk) <= 0.05 * h
                        }
                        // Outer edge close to the strongest one (silver border vs. inner frame), else the strongest.
                        if (ok.isNotEmpty()) offs[ok.last()] else offs[pk]
                    }
                    val tm = (t0 + t1) / 2
                    pts += Triple(a + d * tm, n, off)
                }
                var (p0, n0, o0) = pts[0]
                var (p1, n1, o1) = pts[1]
                if (abs(o0 - o1) > max(3.0, 0.02 * h)) { // halves disagree: likely texture, keep the rough side
                    o0 = 0.0
                    o1 = 0.0
                }
                val q0 = p0 + n0 * o0
                val q1 = p1 + n1 * o1
                lines += q0 to (q1 - q0)
            }
        } finally {
            mapX.release(); mapY.release(); sampled.release()
        }
        val out = intersectAll(lines) ?: return quad
        if (out.maxAbsDiff(quad) > 0.15 * h) return quad
        if (aspectErr(out.ordered()) > aspectErr(quad.ordered()) + 0.015) return quad
        return out
    }

    /** numpy `percentile(..., method="linear")`. */
    private fun percentile(values: DoubleArray, q: Double): Double {
        values.sort()
        val pos = q * (values.size - 1)
        val lo = pos.toInt()
        val hi = min(lo + 1, values.size - 1)
        return values[lo] + (pos - lo) * (values[hi] - values[lo])
    }
}

/** Rectified crop size used for embedding (ml/common.py CARD_W x CARD_H). */
const val CARD_W = 252
const val CARD_H = 352
