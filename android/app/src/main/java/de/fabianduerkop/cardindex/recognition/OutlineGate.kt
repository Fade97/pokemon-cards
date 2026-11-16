// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

/** Whether a live frame's card outline is good enough to identify the card. */
sealed interface GateResult {
    /** Identify the card. */
    data object Accepted : GateResult

    /** No card-shaped outline in the frame. */
    data object NoOutline : GateResult

    /** An outline was found, but its detection score is below the minimum. */
    data class LowScore(val score: Double) : GateResult

    /** The outline moved too much since the previous frame (card or phone still moving, or a jumping detection). */
    data object Moving : GateResult
}

/**
 * Live-scan gate in front of identification: a detection must reach [minScore] and stay put between two
 * consecutive frames (each corner moves at most [maxShift] of the outline's diagonal). On synthetic photos a minimum
 * score of 0.3 keeps 73 % of frames and raises top-1 among them from 93.7 % to 98.7 % (`ml/app_parity.py` data).
 * The score grows with the card's share of the frame, so a card that fills the frame passes easily.
 */
fun outlineGate(current: Detection?, previous: Detection?, minScore: Double, maxShift: Double = 0.04): GateResult {
    if (current == null) return GateResult.NoOutline
    if (current.score < minScore) return GateResult.LowScore(current.score)
    if (previous == null) return GateResult.Moving
    val diagonal = (current.quad[2] - current.quad[0]).norm()
    // The detector orders corners by position, so matching corners can be compared directly.
    val shift = current.quad.indices.maxOf { (current.quad[it] - previous.quad[it]).norm() }
    return if (shift <= maxShift * diagonal) GateResult.Accepted else GateResult.Moving
}

/** Default minimum detection score for live identification. */
const val DEFAULT_MIN_OUTLINE_SCORE = 0.30
