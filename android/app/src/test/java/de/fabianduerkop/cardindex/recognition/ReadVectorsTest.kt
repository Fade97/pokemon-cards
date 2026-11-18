// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ReadVectorsTest {
    private fun file(values: FloatArray): File {
        val bytes = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { bytes.putFloat(it) }
        return File.createTempFile("index", ".bin").apply { deleteOnExit(); writeBytes(bytes.array()) }
    }

    @Test
    fun readsLikeParseVectorsAcrossBufferBoundaries() {
        // More than the 1 MB read buffer, and not a multiple of it.
        val values = FloatArray(300_001) { it * 0.5f - 7f }
        val f = file(values)
        val dest = FloatArray(values.size + 5) { -1f }
        ReferenceIndex.readVectors(f, dest, offset = 3)
        assertArrayEquals(ReferenceIndex.parseVectors(f.readBytes()), dest.copyOfRange(3, 3 + values.size), 0f)
        assertEquals(-1f, dest[2]); assertEquals(-1f, dest[values.size + 3])
    }

    @Test
    fun appendsFilesOneAfterTheOther() {
        val a = file(floatArrayOf(1f, 2f)); val b = file(floatArrayOf(3f, 4f, 5f))
        val dest = FloatArray(5)
        ReferenceIndex.readVectors(a, dest, 0)
        ReferenceIndex.readVectors(b, dest, 2)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f), dest, 0f)
    }

    @Test
    fun refusesAFileThatDoesNotFit() {
        val f = file(FloatArray(4))
        assertThrows(IllegalArgumentException::class.java) { ReferenceIndex.readVectors(f, FloatArray(5), 2) }
    }
}
