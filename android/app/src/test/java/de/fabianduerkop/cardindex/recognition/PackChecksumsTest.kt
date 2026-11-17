// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PackChecksumsTest {
    private val bytes = "abc".toByteArray()

    // NIST test vector for SHA-256("abc").
    private val abc = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test
    fun sha256MatchesKnownVector() = assertEquals(abc, sha256Hex(bytes))

    @Test
    fun matchingChecksumPasses() {
        verifyChecksum("index.bin", bytes, abc)
        verifyChecksum("index.bin", bytes, " ${abc.uppercase()} ")
    }

    @Test
    fun mismatchFailsAndNamesTheFile() {
        val e = assertThrows(PackIntegrityException::class.java) {
            verifyChecksum("embedder.tflite", "abd".toByteArray(), abc)
        }
        assertTrue(e.message!!, e.message!!.contains("embedder.tflite"))
    }

    @Test
    fun missingChecksumFails() {
        assertThrows(PackIntegrityException::class.java) { verifyChecksum("index.bin", bytes, null) }
        assertThrows(PackIntegrityException::class.java) { verifyChecksum("index.bin", bytes, "") }
    }
}
