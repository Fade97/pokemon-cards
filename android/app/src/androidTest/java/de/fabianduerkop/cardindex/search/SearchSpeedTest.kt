// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.search

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import de.fabianduerkop.cardindex.data.appContainer
import de.fabianduerkop.cardindex.data.search.CardSearch
import de.fabianduerkop.cardindex.data.search.SearchTerms
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * How long one search takes on the device with the installed language packs (#101, from the #99 comment): the time
 * from a query to its first page of results, per keystroke of "pikachu", "151" and "025/165". It reports, it does
 * not assert, and writes `search-speed.json` to the app's external files dir. Run it like `RealPhotoTest`:
 * `adb install -r` both APKs, then `am instrument --user 0 -w -e class ...SearchSpeedTest`.
 */
@RunWith(AndroidJUnit4::class)
class SearchSpeedTest {
    @Test
    fun searchSpeed() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val container = context.appContainer
        val languages = container.packs.installed.value.packs.keys
        assumeTrue("no language pack installed", languages.isNotEmpty())
        val search = CardSearch(container.database.catalogDao())
        val queries = listOf("pikachu", "151", "025/165").flatMap { word -> (1..word.length).map { word.take(it) } } +
            listOf("pikachu 25", "mewthree")
        // One warm-up so the first query doesn't carry opening the database.
        search.observe(SearchTerms.parse("a")!!, languages).first()
        val rows = JSONArray()
        for (query in queries) {
            val terms = SearchTerms.parse(query) ?: continue
            val times = (1..RUNS).map {
                val start = System.nanoTime()
                val page = search.observe(terms, languages).first()
                (System.nanoTime() - start) / 1_000_000.0 to page.hits.size
            }
            val ms = times.map { it.first }.sorted()
            val row = JSONObject()
                .put("query", query)
                .put("hits", times.first().second)
                .put("medianMs", ms[ms.size / 2])
                .put("maxMs", ms.last())
            Log.i(TAG, row.toString())
            rows.put(row)
        }
        val result = JSONObject().put("languages", JSONArray(languages.sorted())).put("runs", RUNS).put("queries", rows)
        File(context.getExternalFilesDir(null), "search-speed.json").writeText(result.toString(2))
    }

    private companion object {
        const val TAG = "SearchSpeedTest"
        const val RUNS = 5
    }
}
