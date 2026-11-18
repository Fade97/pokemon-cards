// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.packs

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Robolectric for org.json, which the plain JVM only has as a stub. */
@RunWith(AndroidJUnit4::class)
class PackCatalogTest {
    private fun catalog(sha: String) = """
        {"formatVersion":1,"models":{"m1":{"embedder":{"path":"models/m1.tflite","size":5,"sha256":"$sha"},"dim":4}},"packs":{}}
    """.trimIndent()

    @Test
    fun catalogWithAHashThatIsNotHexIsRefused() {
        // #131: the hash becomes part of a download file name.
        assertEquals("ab".repeat(32), PackCatalog.parse(catalog("AB".repeat(32))).models.getValue("m1").embedder.sha256)
        for (bad in listOf("../../databases/x", "ab".repeat(31), "g".repeat(64))) {
            val e = runCatching { PackCatalog.parse(catalog(bad)) }.exceptionOrNull()
            assertTrue("$bad: $e", e is IllegalArgumentException && e.message!!.contains("bad sha256"))
        }
    }
}
