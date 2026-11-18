// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.fabianduerkop.cardindex.data.appContainer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.abs

/**
 * Compares the app pipeline with the Python prototype on the same images.
 *
 * Setup (images are derived from card art and stay off the repository):
 * ```
 * python ml/app_parity.py                      # writes <data>/app-parity/{*.png, expected.json}
 * adb push <data>/app-parity/. /sdcard/Android/data/de.fabianduerkop.foilark.debug/files/parity/
 * ./gradlew :app:connectedDebugAndroidTest
 * adb pull /sdcard/Android/data/de.fabianduerkop.foilark.debug/files/parity/results.json
 * ```
 * Skipped when the folder or the recognition assets are missing.
 */
@RunWith(AndroidJUnit4::class)
class RecognitionParityTest {
    @Test
    fun matchesPythonPipeline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = context.getExternalFilesDir("parity")
        val expectedFile = File(dir, "expected.json")
        assumeTrue("no parity data in $dir", expectedFile.exists())
        // Only missing assets skip the test; present but broken assets (bad model, dim mismatch) must fail it.
        val engine = try {
            RecognitionEngine.load(context, context.appContainer::newNumberReader)
        } catch (e: FileNotFoundException) {
            assumeTrue("recognition assets missing: ${e.message}", false)
            return
        }

        val expected = JSONArray(expectedFile.readText())
        val rows = JSONArray()
        var sameTop1 = 0
        var appCorrect = 0
        var pyCorrect = 0
        var sameFound = 0
        var quadClose = 0
        var foundBoth = 0
        val ms = ArrayList<Long>()
        for (i in 0 until expected.length()) {
            val e = expected.getJSONObject(i)
            val bitmap = BitmapFactory.decodeFile(File(dir, e.getString("file")).path)
            val r = engine.recognize(bitmap, readNumber = false)
            ms += r.timings.total
            val pyTop1 = e.getJSONArray("top5").getString(0)
            val truth = e.getString("truth")
            val appTop1 = r.candidates[0].card.key
            if (appTop1 == pyTop1) sameTop1++
            if (appTop1 == truth) appCorrect++
            if (pyTop1 == truth) pyCorrect++
            if (r.found == e.getBoolean("found")) sameFound++
            // Corner distance to the Python quad, in pixels (both after the 180° check).
            val pq = e.getJSONArray("quad")
            val dist = (0 until 4).maxOf { k ->
                val p = pq.getJSONArray(k)
                maxOf(abs(p.getDouble(0) - r.quad[k].x), abs(p.getDouble(1) - r.quad[k].y))
            }
            if (r.found && e.getBoolean("found")) {
                foundBoth++
                if (dist <= 2.0) quadClose++
            }
            rows.put(
                JSONObject()
                    .put("file", e.getString("file"))
                    .put("truth", truth)
                    .put("pyTop1", pyTop1)
                    .put("appTop1", appTop1)
                    .put("appSim", r.candidates[0].similarity.toDouble())
                    .put("pySim", e.getJSONArray("sims").getDouble(0))
                    .put("found", r.found)
                    .put("maxCornerDiffPx", dist)
                    .put("ms", r.timings.total),
            )
        }
        val n = expected.length()
        val summary = JSONObject()
            .put("n", n)
            .put("sameTop1AsPython", sameTop1.toDouble() / n)
            .put("appTop1", appCorrect.toDouble() / n)
            .put("pythonTop1", pyCorrect.toDouble() / n)
            .put("sameFound", sameFound.toDouble() / n)
            .put("quadWithin2pxWhenBothFound", quadClose.toDouble() / maxOf(foundBoth, 1))
            .put("medianMsWithoutOcr", ms.sorted()[ms.size / 2])
        File(dir, "results.json").writeText(JSONObject().put("summary", summary).put("rows", rows).toString(1))
        Log.i("RecognitionParity", summary.toString())
        engine.close()

        assertTrue("top-1 agrees with Python on only $sameTop1 of $n", sameTop1 >= 0.95 * n)
        assertTrue("app top-1 $appCorrect vs. Python $pyCorrect of $n", appCorrect >= pyCorrect - 0.02 * n)
    }
}
