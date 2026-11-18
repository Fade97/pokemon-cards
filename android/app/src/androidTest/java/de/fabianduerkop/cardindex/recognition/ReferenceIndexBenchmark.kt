// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import android.os.Bundle
import android.util.Half
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.random.Random

/**
 * Issue #74: time of [ReferenceIndex.similarities] over a seven-language-sized index (107k × 256, random rows) with
 * the rows in a heap `FloatArray` (before) and memory-mapped per language (after), plus the alternatives that were
 * not taken: element-wise `FloatBuffer.get` on the mapping, and float16 in a `ShortArray`. Reports, and checks that
 * the mapped results are bit-identical to the array. Does not touch the app's data; files go to the test's cache.
 * ```
 * adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
 * adb shell am instrument --user 0 -w -e class de.fabianduerkop.cardindex.recognition.ReferenceIndexBenchmark \
 *   de.fabianduerkop.cardindex.debug.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 * Arguments (`-e name value`): `rows` (default 107000), `runs` timed queries per variant (default 10).
 */
@RunWith(AndroidJUnit4::class)
class ReferenceIndexBenchmark {
    private val args: Bundle = InstrumentationRegistry.getArguments()
    private val dim = 256

    @Test
    fun similarities() {
        val rows = args.getString("rows")?.toInt() ?: 107_000
        val runs = args.getString("runs")?.toInt() ?: 10
        val langs = listOf("de", "en", "es", "fr", "it", "ja", "pt")
        val perLang = rows / langs.size
        val cards = langs.flatMap { lang -> List(perLang) { RefCard("$lang/$it", "$it", lang, "s", "S", "$it", 1, "", "", false) } }
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "bench74").apply { deleteRecursively(); mkdirs() }
        val rnd = Random(74)
        val files = langs.map { lang ->
            File(dir, "$lang.bin").also { f ->
                val buf = ByteBuffer.allocate(perLang * dim * 4).order(ByteOrder.LITTLE_ENDIAN)
                repeat(perLang * dim) { buf.putFloat(rnd.nextFloat() * 2 - 1) }
                f.writeBytes(buf.array())
            }
        }
        val query = FloatArray(dim) { rnd.nextFloat() * 2 - 1 }
        val lines = ArrayList<String>()
        fun time(name: String, block: () -> FloatArray): FloatArray {
            var r = block() // warm-up (and page-in for the mapping)
            val ms = LongArray(runs) { val t = System.nanoTime(); r = block(); (System.nanoTime() - t) / 1_000_000 }.sorted()
            lines += "$name: median ${ms[runs / 2]} ms, min ${ms[0]} ms, max ${ms[runs - 1]} ms"
            return r
        }

        val mappedSegments = files.map { ReferenceIndex.mapVectors(it) }
        val coldStart = System.nanoTime()
        val mapped = ReferenceIndex(cards, mappedSegments, dim)
        mapped.similarities(query)
        lines += "mapped first query (page-in): ${(System.nanoTime() - coldStart) / 1_000_000} ms"
        val after = time("mapped, chunked (after)") { mapped.similarities(query) }
        time("mapped, chunked, lang filter {ja}") { mapped.similarities(query, setOf("ja")) }

        val array = FloatArray(cards.size * dim).also { a -> var at = 0; mappedSegments.forEach { s -> val d = s.duplicate(); val n = d.remaining(); d.get(a, at, n); at += n } }
        val before = time("heap FloatArray (before)") { ReferenceIndex(cards, array, dim).similarities(query) }
        assertArrayEquals(before, after, 0f)

        time("mapped, element-wise get") {
            val out = FloatArray(cards.size); var i = 0
            for (s in mappedSegments) { val b: FloatBuffer = s.duplicate(); val n = b.remaining() / dim
                for (r in 0 until n) { var sum = 0f; val base = r * dim; for (j in 0 until dim) sum += b.get(base + j) * query[j]; out[i++] = sum } }
            out
        }
        val half = ShortArray(array.size) { Half.toHalf(array[it]) }
        val f16 = time("f16 ShortArray (Half.toFloat)") {
            FloatArray(cards.size) { i -> var s = 0f; val base = i * dim; for (j in 0 until dim) s += Half.toFloat(half[base + j]) * query[j]; s }
        }
        lines += "f16 max |diff| vs f32: ${before.indices.maxOf { kotlin.math.abs(before[it] - f16[it]) }}"
        dir.deleteRecursively()
        lines.forEach { Log.i("Bench74", it) }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("stream", lines.joinToString("\n", postfix = "\n")) })
    }
}
