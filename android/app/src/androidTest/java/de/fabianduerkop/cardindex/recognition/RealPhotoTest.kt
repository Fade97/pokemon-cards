// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.graphics.scale
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.fabianduerkop.cardindex.data.appContainer
import de.fabianduerkop.cardindex.data.packs.PackLoader
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Runs the app's photo pipeline (long side 2048 px, guide area, both outline detectors) on labelled real photos and
 * writes `results.json`; it reports, it does not assert. The photos are the user's own and stay off the repository:
 * ```
 * adb push real-photos/. /sdcard/Android/data/de.fabianduerkop.foilark.debug/files/realphotos/
 * adb push real-photos-labels-manual.json /sdcard/Android/data/de.fabianduerkop.foilark.debug/files/realphotos/labels.json
 * ```
 * labels.json maps file name → accepted keys `"<lang>/<set id>:<collector number>"`. Without bundled assets the
 * test uses the installed language packs, so install the debug build with `adb install -r` (Gradle's
 * connectedAndroidTest uninstalls the app afterwards and deletes the packs and photos).
 *
 * `-e twoPass true` runs the learned outline with two-pass detection ([RecognitionEngine.identifyTwoPass], issue
 * #78), the app's photo path, and writes `results-twopass.json` instead; the classic outline stays one pass. Without
 * it (or with `false`) everything is one pass, as before. `totalMs` is detection plus identification without OCR, so
 * the two files compare the latency too.
 */
@RunWith(AndroidJUnit4::class)
class RealPhotoTest {
    @Test
    fun realPhotos() {
        val twoPass = "true".equals(InstrumentationRegistry.getArguments().getString("twoPass"), ignoreCase = true)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = context.getExternalFilesDir("realphotos")
        val labelsFile = File(dir, "labels.json")
        assumeTrue("no real photos in $dir", labelsFile.exists())
        // Like the lab: bundled assets from ml/app_assets.py first, else the installed language packs (what the app
        // runs). Only missing assets skip the test; present but broken ones (bad model, dim mismatch) must fail it.
        val engine = if (RecognitionEngine.hasBundledAssets(context)) {
            RecognitionEngine.load(context)
        } else {
            val loaded = PackLoader.load(context.appContainer.packs.installed.value).loaded
            assumeTrue("no bundled recognition assets and no usable language pack", loaded != null)
            RecognitionEngine.load(loaded!!)
        }
        Log.i("RealPhotoTest", "engine: ${engine.info}, twoPass=$twoPass")
        val labels = JSONObject(labelsFile.readText())
        val kinds = if (engine.hasLearnedDetector) DetectorKind.entries else listOf(DetectorKind.CLASSIC)
        // Each detector once with every language, once German only (the photos are German prints).
        val configs = kinds.flatMap { listOf(it to null, it to setOf("de")) }
        val summary = JSONObject()
        val rows = JSONArray()
        for ((kind, langs) in configs) {
            val twoPassHere = twoPass && kind == DetectorKind.LEARNED
            val config = kind.name + (if (twoPassHere) "+TWOPASS" else "") + if (langs == null) "" else "+" + langs.joinToString("+")
            var right = 0; var shown = 0; var shownWrong = 0; var secondKept = 0
            val detectMs = ArrayList<Long>()
            val totalMs = ArrayList<Long>()
            for (name in labels.keys()) {
                val accepted = labels.getJSONArray(name).let { a -> List(a.length()) { a.getString(it) }.toSet() }
                val full = BitmapFactory.decodeFile(File(dir, name).path) ?: continue
                val side = max(full.width, full.height)
                val bitmap = if (side > 2048) full.scale((full.width * 2048f / side).roundToInt(), (full.height * 2048f / side).roundToInt()) else full
                val bgr = RecognitionEngine.toBgr(bitmap)
                val t0 = System.nanoTime()
                val detection = engine.detect(bgr, ScanGuide.forFrame(bgr.cols(), bgr.rows()).searchArea, kind)
                detectMs += (System.nanoTime() - t0) / 1_000_000
                val r = if (twoPassHere) engine.identifyTwoPass(bgr, detection, readNumber = false, languages = langs)
                else engine.identify(bgr, detection, readNumber = false, languages = langs)
                totalMs += (System.nanoTime() - t0) / 1_000_000
                bgr.release()
                if (r.secondPass) secondKept++
                val best = r.best.card
                val ok = "${best.lang}/${best.setId}:${best.localId}" in accepted
                val isShown = r.best.similarity >= DEFAULT_MIN_SIMILARITY
                if (ok) right++
                if (isShown) { shown++; if (!ok) shownWrong++ }
                rows.put(JSONObject().put("detector", config).put("file", name).put("best", best.key)
                    .put("similarity", r.best.similarity.toDouble()).put("correct", ok).put("score", r.detectionScore)
                    .put("secondPass", r.secondPass).put("totalMs", totalMs.last()))
            }
            summary.put(config, JSONObject().put("n", labels.length()).put("right", right).put("shown", shown)
                .put("shownWrong", shownWrong).put("medianDetectMs", detectMs.sorted()[detectMs.size / 2])
                .put("medianTotalMs", totalMs.sorted()[totalMs.size / 2]).put("secondPassKept", secondKept))
        }
        File(dir, if (twoPass) "results-twopass.json" else "results.json").writeText(JSONObject().put("summary", summary).put("rows", rows).toString(1))
        Log.i("RealPhotoTest", summary.toString())
        engine.close()
    }
}
