// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.contributions

import java.io.ByteArrayOutputStream

/**
 * Removes every metadata segment from a JPEG: APP1–APP15 (EXIF, XMP, ICC, maker notes …) and COM. Only APP0 (JFIF,
 * which says how to read the pixels) and the image segments stay. `Bitmap.compress` writes no EXIF today, but a
 * contributed scan must not carry location or device data even if an encoder starts adding it (issue #14).
 */
object JpegMetadata {
    private const val SOI = 0xD8
    private const val SOS = 0xDA
    private const val COM = 0xFE
    private const val APP0 = 0xE0
    private const val APP15 = 0xEF

    /** The JPEG without metadata; throws [IllegalArgumentException] for bytes that are not a well-formed JPEG. */
    fun strip(jpeg: ByteArray): ByteArray {
        require(jpeg.size >= 4 && u(jpeg, 0) == 0xFF && u(jpeg, 1) == SOI) { "not a JPEG" }
        val out = ByteArrayOutputStream(jpeg.size)
        out.write(jpeg, 0, 2)
        var i = 2
        while (i < jpeg.size) {
            require(u(jpeg, i) == 0xFF) { "broken JPEG segment at $i" }
            var marker = u(jpeg, i + 1)
            // Fill bytes: any number of 0xFF before a marker.
            var start = i
            while (marker == 0xFF) {
                start++
                marker = u(jpeg, start + 1)
            }
            if (marker == SOS) {
                // Entropy-coded data up to the end: no metadata can follow in a baseline or progressive file that
                // matters here, and markers inside it are not segment boundaries.
                out.write(jpeg, start, jpeg.size - start)
                break
            }
            // Markers without a length (RSTn, TEM) don't occur before SOS in a valid file.
            require(start + 3 < jpeg.size) { "truncated JPEG" }
            val length = (u(jpeg, start + 2) shl 8) or u(jpeg, start + 3)
            require(length >= 2 && start + 2 + length <= jpeg.size) { "truncated JPEG segment" }
            val metadata = marker == COM || marker in (APP0 + 1)..APP15
            if (!metadata) out.write(jpeg, start, 2 + length)
            i = start + 2 + length
        }
        return out.toByteArray()
    }

    /** Whether [jpeg] has any segment [strip] would remove. */
    fun hasMetadata(jpeg: ByteArray): Boolean = !strip(jpeg).contentEquals(jpeg)

    private fun u(b: ByteArray, i: Int): Int {
        require(i < b.size) { "truncated JPEG" }
        return b[i].toInt() and 0xFF
    }
}
