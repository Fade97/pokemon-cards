// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TesterFeedbackTest {
    @Test
    fun `url opens the form with the fields filled in`() {
        val url = TesterFeedback.url(appVersion = "0.1.0 (1)", device = "samsung SM-S938B", android = "16 (API 36)")
        assertEquals(
            "https://github.com/Fade97/pokemon-cards/issues/new?template=tester-feedback.yml" +
                "&app-version=0.1.0%20%281%29&device=samsung%20SM-S938B&android=16%20%28API%2036%29",
            url,
        )
    }

    @Test
    fun `url escapes characters that would end the parameter`() {
        val url = TesterFeedback.url(appVersion = "1&x=2", device = "a+b", android = "ü")
        assertTrue(url, url.endsWith("&app-version=1%26x%3D2&device=a%2Bb&android=%C3%BC"))
    }

    @Test
    fun `mailto fills in subject and details`() {
        val mail = TesterFeedback.mailto("Foilark", appVersion = "0.1.0 (1)", device = "samsung SM-S938B", android = "16 (API 36)")
        assertTrue(mail, mail.startsWith("mailto:${TesterFeedback.EMAIL}?subject=Foilark%20feedback&body="))
        assertTrue(mail, mail.contains("App%20version%3A%200.1.0%20%281%29%0APhone%3A%20samsung%20SM-S938B"))
    }

    @Test
    fun `device does not repeat the maker`() {
        assertEquals("samsung SM-S938B", TesterFeedback.device("samsung", "SM-S938B"))
        assertEquals("Google Pixel 9", TesterFeedback.device("Google", "Google Pixel 9"))
        assertEquals("sdk_gphone64_x86_64", TesterFeedback.device("", "sdk_gphone64_x86_64"))
    }

    @Test
    fun `the template has a field for every parameter the app fills in`() {
        // Unit tests run with android/app as the working directory.
        val template = File("../../.github/ISSUE_TEMPLATE/${TesterFeedback.TEMPLATE}").readText()
        for (id in listOf("app-version", "device", "android")) {
            assertTrue("field id $id missing in the template", Regex("""(?m)^\s+id: $id\s*$""").containsMatchIn(template))
        }
    }
}
