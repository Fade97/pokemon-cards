// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.lab

import de.fabianduerkop.cardindex.recognition.Quad
import org.json.JSONArray
import org.json.JSONObject

/*
 * Finish bursts (#144): a few live frames of one card while it is tilted, labelled with its finish, holder and light,
 * for measuring finish detection offline (#146). Recorded in the Lab, saved to the app's external files dir
 * (`finish-bursts/<id>/`), pulled to the data dir with adb and read by ml/finish_set.py. The format is
 * documented there and in ml/README.md; keep both in step.
 */

/** The finish the user says the card has. */
enum class BurstFinish(val key: String) { NORMAL("normal"), HOLO("holo"), REVERSE("reverse") }

/** What the card sits in; sleeve glare is the main source of a false reverse holo. */
enum class BurstHolder(val key: String) {
    BARE("bare"), PENNY_SLEEVE("penny"), DOUBLE_SLEEVE("double"), TOPLOADER("toploader"), BINDER("binder"),
}

enum class BurstLight(val key: String) { ROOM("room"), DAYLIGHT("daylight"), TORCH("torch") }

data class BurstLabels(
    val finish: BurstFinish = BurstFinish.NORMAL,
    val holder: BurstHolder = BurstHolder.BARE,
    val light: BurstLight = BurstLight.ROOM,
)

/** One saved frame of a burst: the analysed frame, its rectified crop, and the card corners in frame pixels. */
data class BurstFrame(
    val frame: String,
    val crop: String,
    val quad: Quad,
    val width: Int,
    val height: Int,
    val similarity: Float,
    /** Milliseconds since the burst's first frame. */
    val atMs: Long,
)

/**
 * Collects the frames of one burst. It takes a frame only when it shows [cardKey] and at least [minGapMs] after the
 * previous one, so the frames span the tilt instead of showing the same angle; frames of another card are skipped.
 * Pure, so the rules are unit-tested; the Lab view model saves what [accept] lets through.
 */
class BurstRecorder(
    val cardKey: String,
    val labels: BurstLabels,
    val frames: Int = DEFAULT_FRAMES,
    private val minGapMs: Long = DEFAULT_GAP_MS,
) {
    private val taken = mutableListOf<BurstFrame>()
    private var firstAt = -1L
    private var lastAt = Long.MIN_VALUE

    val saved: List<BurstFrame> get() = taken
    val done: Boolean get() = taken.size >= frames

    /** Whether the frame at [nowMs] showing [key] should be saved; if so, its index is [saved]`.size`. */
    fun accept(key: String, nowMs: Long): Boolean =
        !done && key == cardKey && (lastAt == Long.MIN_VALUE || nowMs - lastAt >= minGapMs)

    /** Records a saved frame; [nowMs] is the same clock as in [accept]. */
    fun add(nowMs: Long, frame: (atMs: Long, index: Int) -> BurstFrame) {
        if (firstAt < 0) firstAt = nowMs
        lastAt = nowMs
        taken += frame(nowMs - firstAt, taken.size)
    }

    companion object {
        const val DEFAULT_FRAMES = 8
        const val DEFAULT_GAP_MS = 200L
        const val FORMAT = 1

        fun frameName(index: Int) = "frame-%02d.jpg".format(index)
        fun cropName(index: Int) = "crop-%02d.jpg".format(index)
    }
}

/** `burst.json` of a finished burst (format [BurstRecorder.FORMAT], read by ml/finish_set.py). */
fun burstJson(
    id: String,
    cardId: String,
    lang: String,
    labels: BurstLabels,
    torch: Boolean,
    frames: List<BurstFrame>,
    createdAt: String,
    device: String,
    app: String,
): JSONObject = JSONObject()
    .put("format", BurstRecorder.FORMAT)
    .put("id", id)
    .put("cardId", cardId)
    .put("lang", lang)
    .put("finish", labels.finish.key)
    .put("holder", labels.holder.key)
    .put("light", labels.light.key)
    .put("torch", torch)
    .put("createdAt", createdAt)
    .put("device", device)
    .put("app", app)
    .put(
        "frames",
        JSONArray(
            frames.map { f ->
                JSONObject()
                    .put("frame", f.frame)
                    .put("crop", f.crop)
                    .put("quad", JSONArray(f.quad.map { JSONArray(listOf(round1(it.x), round1(it.y))) }))
                    .put("width", f.width)
                    .put("height", f.height)
                    .put("similarity", round3(f.similarity.toDouble()))
                    .put("atMs", f.atMs)
            },
        ),
    )

private fun round1(v: Double) = Math.round(v * 10) / 10.0

private fun round3(v: Double) = Math.round(v * 1000) / 1000.0
