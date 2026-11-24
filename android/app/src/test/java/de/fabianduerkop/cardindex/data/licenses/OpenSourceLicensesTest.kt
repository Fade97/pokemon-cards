// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.data.licenses

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenSourceLicensesTest {
    @Test
    fun parsesSortsAndKeepsLinksForLicensesWithoutText() {
        val json = """
            {
              "libraries": [
                {"uniqueId": "com.example:zeta", "artifactVersion": "2.0", "name": "zeta", "licenses": ["Apache-2.0"]},
                {"uniqueId": "com.example:alpha", "artifactVersion": "1.0", "name": "Alpha",
                 "website": "https://example.com", "licenses": ["custom"]},
                {"uniqueId": "com.example:nameless", "name": "", "licenses": []}
              ],
              "licenses": {
                "Apache-2.0": {"name": "Apache License 2.0", "url": "https://spdx.org/licenses/Apache-2.0.html",
                               "content": "Apache text", "hash": "Apache-2.0"},
                "custom": {"name": "Example Terms", "url": "https://example.com/terms", "hash": "custom"}
              }
            }
        """.trimIndent()

        val libs = OpenSourceLicenses.parse(json)

        assertEquals(listOf("Alpha", "com.example:nameless", "zeta"), libs.map { it.name })
        val alpha = libs.first()
        assertEquals("1.0", alpha.version)
        assertEquals("https://example.com", alpha.website)
        assertEquals(LicenseInfo("Example Terms", "https://example.com/terms", null), alpha.licenses.single())
        assertEquals("Apache text", libs.last().licenses.single().text)
        assertNull(libs[1].version)
        assertTrue(libs[1].licenses.isEmpty())
    }

    /** The list the build generated for this variant: what the APK ships, with the Apache text it needs (#136). */
    @Test
    fun generatedListCoversTheShippedLibraries() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val libs = OpenSourceLicenses.load(context).associateBy { it.id }

        val okhttp = libs["com.squareup.okhttp3:okhttp"]
        assertNotNull(okhttp)
        assertTrue(okhttp!!.licenses.any { it.name.contains("Apache") && !it.text.isNullOrBlank() })
        listOf("org.opencv:opencv", "com.google.ai.edge.litert:litert", "com.google.mlkit:text-recognition").forEach {
            assertNotNull(it, libs[it])
        }
        // Only the Latin recognizer is used (#138).
        assertNull(libs["com.google.mlkit:text-recognition-japanese"])
        assertTrue(libs.values.all { it.licenses.isNotEmpty() })
    }

    /** What AboutLibraries can't see (#253): the app's GPL notice and the bundled data, font and model, listed first. */
    @Test
    fun bundledEntriesComeFirstWithTheirTexts() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val libs = OpenSourceLicenses.load(context)

        assertEquals(listOf("foilark", "tcgdex", "schibsted-grotesk", "mobilenetv3"), libs.take(4).map { it.id })
        val byId = libs.associateBy { it.id }
        assertTrue(byId.getValue("foilark").licenses.single().text!!.contains("WITHOUT ANY WARRANTY"))
        assertTrue(byId.getValue("tcgdex").licenses.single().text!!.contains("Copyright (c) 2021 TCGdex"))
        assertTrue(byId.getValue("schibsted-grotesk").licenses.single().text!!.contains("SIL OPEN FONT LICENSE"))
        assertEquals("Apache License 2.0", byId.getValue("mobilenetv3").licenses.single().name)
    }
}
