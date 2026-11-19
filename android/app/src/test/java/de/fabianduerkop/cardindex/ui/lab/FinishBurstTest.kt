// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.lab

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.fabianduerkop.cardindex.recognition.Pt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinishBurstTest {
    private val quad = listOf(Pt(10.04, 20.0), Pt(110.0, 20.0), Pt(110.0, 160.0), Pt(10.0, 160.0))

    private fun BurstRecorder.take(now: Long) =
        add(now) { at, i -> BurstFrame(BurstRecorder.frameName(i), BurstRecorder.cropName(i), quad, 1280, 960, 0.8f, at) }

    @Test
    fun takesOnlyTheBurstsCardSpacedOutUntilFull() {
        val r = BurstRecorder("en/sv03.5-006", BurstLabels(), frames = 3, minGapMs = 200)
        assertTrue(r.accept("en/sv03.5-006", 1_000))
        r.take(1_000)
        assertFalse(r.accept("en/sv03.5-006", 1_150)) // same angle, too soon
        assertFalse(r.accept("de/sv03.5-006", 1_300)) // another card (or language) in view
        assertTrue(r.accept("en/sv03.5-006", 1_200))
        r.take(1_200)
        r.take(1_500)
        assertTrue(r.done)
        assertFalse(r.accept("en/sv03.5-006", 5_000))
        assertEquals(listOf(0L, 200L, 500L), r.saved.map { it.atMs })
        assertEquals(listOf("frame-00.jpg", "frame-01.jpg", "frame-02.jpg"), r.saved.map { it.frame })
    }

    @Test
    fun burstJsonCarriesLabelsAndFrames() {
        val r = BurstRecorder("en/sv03.5-006", BurstLabels(BurstFinish.REVERSE, BurstHolder.TOPLOADER, BurstLight.TORCH), frames = 1)
        r.take(0)
        val json = burstJson("20261119-120000-en-sv03.5-006", "sv03.5-006", "en", r.labels, torch = true, r.saved,
            "2026-11-19T12:00:00Z", "SM-S938B", "0.1.0-debug")
        assertEquals(1, json.getInt("format"))
        assertEquals(listOf("reverse", "toploader", "torch"), listOf("finish", "holder", "light").map(json::getString))
        assertTrue(json.getBoolean("torch"))
        val frame = json.getJSONArray("frames").getJSONObject(0)
        assertEquals("crop-00.jpg", frame.getString("crop"))
        assertEquals("[[10,20],[110,20],[110,160],[10,160]]", frame.getJSONArray("quad").toString().replace(".0", ""))
        assertEquals(0.8, frame.getDouble("similarity"), 1e-9)
    }
}
