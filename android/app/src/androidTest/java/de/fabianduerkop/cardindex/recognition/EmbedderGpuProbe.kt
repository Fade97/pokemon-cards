// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.CompiledModel.GpuOptions.Precision
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Issue #54: runs embedding models on the CPU (the app's XNNPACK interpreter) and on the GPU (the app's
 * `CompiledModel` path) and reports the cosine between them and the time per embedding. It reports, it does not
 * assert. One step: `ANDROID_SERIAL=<device> android/gpu-probe.sh f32.tflite f16.tflite w8.tflite [-- images...]`.
 * By hand (models and card images stay off the repository):
 * ```
 * D=/sdcard/Android/data/de.fabianduerkop.lumifoil.debug/files/gpuprobe
 * adb push mnv3_256_real_{w8,wo8,f16,f32}.tflite $D/
 * adb push some-card-crops/. $D/images/                    # optional: *.png / *.jpg card crops
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument --user 0 -w -e class de.fabianduerkop.cardindex.recognition.EmbedderGpuProbe \
 *   de.fabianduerkop.lumifoil.debug.test/androidx.test.runner.AndroidJUnitRunner
 * adb pull $D/results.json
 * ```
 * Arguments (`-e name value`): `models` comma-separated file names in the probe folder or absolute paths (default:
 * every `.tflite` there); `reference` the float model the others are compared with (default: the first name that
 * contains `f32`); `precisions` GPU precisions (default `FP32,FP16`); `runs` timed runs per backend (default 20).
 * Emulator GPU numbers are not representative: the emulator's GPU is a software or host-translated one.
 */
@RunWith(AndroidJUnit4::class)
class EmbedderGpuProbe {
    private val lines = StringBuilder()

    @Test
    fun probe() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val dir = instrumentation.targetContext.getExternalFilesDir("gpuprobe")!!
        check(OpenCVLoader.initLocal()) { "OpenCV native library failed to load" }

        val models = args.getString("models")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.map { if (it.startsWith("/")) File(it) else File(dir, it) }
            ?: dir.listFiles { f -> f.name.endsWith(".tflite") }.orEmpty().sortedBy { it.name }
        assumeTrue("no .tflite models in $dir (or -e models)", models.isNotEmpty())
        models.forEach { require(it.isFile) { "model not found: $it" } }
        val referenceFile = args.getString("reference")?.let { if (it.startsWith("/")) File(it) else File(dir, it) }
            ?: models.firstOrNull { "f32" in it.name }
        val precisions = (args.getString("precisions") ?: "FP32,FP16").split(',').map { Precision.valueOf(it.trim()) }
        val runs = args.getString("runs")?.toInt() ?: 20

        // Input 0 is the app's synthetic probe image (Embedder.create checks it); the rest are card images.
        val imageFiles = File(dir, "images").listFiles { f -> f.extension.lowercase() in setOf("png", "jpg", "jpeg") }
            .orEmpty().sortedBy { it.name }.take(MAX_IMAGES)
        val images: List<Mat> = imageFiles.mapNotNull { f -> BitmapFactory.decodeFile(f.path)?.let { RecognitionEngine.toBgr(it) } }
        val probe = Embedder.probeImage()

        val soc = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        say("device ${Build.MANUFACTURER} ${Build.MODEL}, SoC $soc, Android ${Build.VERSION.RELEASE}")
        say("inputs: synthetic probe + ${images.size} card images; $runs timed runs; reference ${referenceFile?.name ?: "none"}")

        val reference = referenceFile?.let { f -> Embedder.cpu(direct(f)).use { embedAll(it, probe, images) } }
        val results = JSONArray()
        for (model in models) {
            val row = JSONObject().put("model", model.name).put("sizeBytes", model.length())
            val cpu = Embedder.cpu(direct(model)).use { e ->
                row.put("cpuMs", medianMs(e, probe, runs))
                embedAll(e, probe, images)
            }
            row.put("cpu", agreement(cpu, reference))
            say("${model.name} (${model.length() / 1024} KB)")
            say("  CPU            ${"%7.1f".format(row.getDouble("cpuMs"))} ms   vs ref ${fmt(row.getJSONObject("cpu"))}")
            for (precision in precisions) {
                val g = JSONObject()
                try {
                    Embedder.gpu(model, precision).use { e ->
                        g.put("ms", medianMs(e, probe, runs))
                        val gpu = embedAll(e, probe, images)
                        g.put("vsCpu", agreement(gpu, cpu)).put("vsRef", agreement(gpu, reference))
                    }
                    say("  GPU ${precision.name.padEnd(10)} ${"%7.1f".format(g.getDouble("ms"))} ms   vs CPU ${fmt(g.getJSONObject("vsCpu"))}   vs ref ${fmt(g.getJSONObject("vsRef"))}")
                } catch (t: Throwable) {
                    g.put("error", t.toString())
                    say("  GPU ${precision.name.padEnd(10)} failed: $t")
                    Log.w(TAG, "GPU $precision failed for ${model.name}", t)
                }
                row.put("gpu$precision", g)
            }
            results.put(row)
        }
        images.forEach { it.release() }
        File(dir, "results.json").writeText(
            JSONObject().put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("soc", soc)
                .put("reference", referenceFile?.name).put("images", images.size).put("runs", runs)
                .put("models", results).toString(1),
        )
        say("written: ${File(dir, "results.json")}")
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "\n" + lines.toString()) })
    }

    private fun say(line: String) {
        Log.i(TAG, line)
        lines.append(line).append('\n')
    }

    private fun embedAll(e: Embedder, probe: FloatArray, images: List<Mat>): List<FloatArray> =
        listOf(e.embedInput(probe)) + images.map { e.embed(it) }

    private fun medianMs(e: Embedder, input: FloatArray, runs: Int): Double {
        repeat(WARMUP) { e.embedInput(input) }
        val ms = DoubleArray(runs) {
            val t0 = System.nanoTime()
            e.embedInput(input)
            (System.nanoTime() - t0) / 1e6
        }
        ms.sort()
        return ms[runs / 2]
    }

    /** Cosine per input between two embedding lists: the probe's, and min / mean over the card images. */
    private fun agreement(a: List<FloatArray>, b: List<FloatArray>?): JSONObject {
        if (b == null) return JSONObject()
        val cos = a.indices.map { i -> if (a[i].size != b[i].size) -1.0 else a[i].indices.sumOf { (a[i][it] * b[i][it]).toDouble() } }
        val out = JSONObject().put("probe", cos[0])
        if (cos.size > 1) {
            val real = cos.drop(1)
            out.put("imagesMin", real.min()).put("imagesMean", real.average())
        }
        return out
    }

    private fun fmt(o: JSONObject): String {
        if (!o.has("probe")) return "-"
        val s = "probe %.4f".format(o.getDouble("probe"))
        return if (o.has("imagesMin")) "$s, images min %.4f mean %.4f".format(o.getDouble("imagesMin"), o.getDouble("imagesMean")) else s
    }

    private fun direct(f: File): ByteBuffer {
        val bytes = f.readBytes()
        return ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.rewind() }
    }

    private companion object {
        const val TAG = "EmbedderGpuProbe"
        const val MAX_IMAGES = 40
        const val WARMUP = 3
    }
}
