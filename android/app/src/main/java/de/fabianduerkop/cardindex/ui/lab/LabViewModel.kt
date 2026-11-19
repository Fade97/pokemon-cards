// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.lab

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.core.graphics.scale
import androidx.lifecycle.AndroidViewModel
import de.fabianduerkop.cardindex.BuildConfig
import de.fabianduerkop.cardindex.data.appContainer
import de.fabianduerkop.cardindex.data.packs.PackLoader
import de.fabianduerkop.cardindex.recognition.CornerDetector
import de.fabianduerkop.cardindex.recognition.DEFAULT_MIN_OUTLINE_SCORE
import de.fabianduerkop.cardindex.recognition.DEFAULT_MIN_SIMILARITY
import de.fabianduerkop.cardindex.recognition.Detection
import de.fabianduerkop.cardindex.recognition.DetectorKind
import de.fabianduerkop.cardindex.recognition.GateResult
import de.fabianduerkop.cardindex.recognition.MatchStability
import de.fabianduerkop.cardindex.recognition.Quad
import de.fabianduerkop.cardindex.recognition.RecognitionEngine
import de.fabianduerkop.cardindex.recognition.RecognitionResult
import de.fabianduerkop.cardindex.recognition.ScanGuide
import de.fabianduerkop.cardindex.recognition.outlineGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

sealed interface EngineState {
    data object Loading : EngineState
    data class Ready(val model: String, val cards: Int, val unseenCards: Int, val learnedDetector: Boolean) : EngineState
    data class Failed(val message: String) : EngineState
}

/** Live camera recognition: the current outline, the gate, and the last identified card. */
data class LiveState(
    /** The last frame that identified a card (similarity above the minimum); kept while later frames don't. */
    val result: RecognitionResult? = null,
    /** The same card was identified in every identified frame of the last ~1 s (see [MatchStability]). */
    val stable: Boolean = false,
    val framesPerSecond: Float = 0f,
    /** The current frame's outline (null = none) and frame size, drawn even when the gate rejects it. */
    val outline: Detection? = null,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val gate: GateResult = GateResult.NoOutline,
    /** Best similarity of the current frame when it was identified but below the minimum; null otherwise. */
    val noMatchSimilarity: Float? = null,
) {
    /** The shown card is not the current frame's: the gate rejected it or the match was too weak. */
    val outdated: Boolean get() = gate != GateResult.Accepted || noMatchSimilarity != null
}

/** The finish-burst recorder (#144): the labels for the next burst, the one being recorded, the last result. */
data class BurstState(
    val labels: BurstLabels = BurstLabels(),
    /** Frames saved of the burst being recorded, null when none is. */
    val recording: Int? = null,
    val frames: Int = BurstRecorder.DEFAULT_FRAMES,
    /** Card of the burst being recorded or the last saved one. */
    val card: String? = null,
    /** Bursts saved in this Lab session. */
    val saved: Int = 0,
    /** Where the bursts go (`adb pull` it into the data dir). */
    val folder: String? = null,
    val error: String? = null,
)

/** A captured or picked photo and its result. [usedGuide]: only the guide area was searched (shutter photos). */
data class StillState(
    val photo: Bitmap,
    val result: RecognitionResult?,
    val savedTo: String?,
    val error: String?,
    val usedGuide: Boolean,
)

/**
 * Runs the recognition engine on one worker thread: camera frames (latest only), captured photos and gallery
 * images all go through [worker], so the engine is never used concurrently.
 */
class LabViewModel(application: Application) : AndroidViewModel(application) {
    val worker: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "recognition") }
    private var engine: RecognitionEngine? = null

    private val _engineState = MutableStateFlow<EngineState>(EngineState.Loading)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()
    private val _live = MutableStateFlow(LiveState())
    val live: StateFlow<LiveState> = _live.asStateFlow()
    private val _still = MutableStateFlow<StillState?>(null)
    val still: StateFlow<StillState?> = _still.asStateFlow()
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()
    private val _readNumber = MutableStateFlow(true)
    val readNumber: StateFlow<Boolean> = _readNumber.asStateFlow()
    private val _saveCaptures = MutableStateFlow(false)
    val saveCaptures: StateFlow<Boolean> = _saveCaptures.asStateFlow()
    /** Two-pass detection for photos (issue #78, [RecognitionEngine.identifyTwoPass]); learned outline only, never live. */
    private val _twoPass = MutableStateFlow(true)
    val twoPass: StateFlow<Boolean> = _twoPass.asStateFlow()

    private val _detector = MutableStateFlow(DetectorKind.LEARNED)
    val detector: StateFlow<DetectorKind> = _detector.asStateFlow()
    private val _minScore = MutableStateFlow(CornerDetector.DEFAULT_MIN_SCORE.toFloat())
    val minScore: StateFlow<Float> = _minScore.asStateFlow()
    /** Languages searched (empty until the engine loads = all). */
    private val _languages = MutableStateFlow<Set<String>>(emptySet())
    val languages: StateFlow<Set<String>> = _languages.asStateFlow()
    private val _availableLanguages = MutableStateFlow<List<String>>(emptyList())
    val availableLanguages: StateFlow<List<String>> = _availableLanguages.asStateFlow()
    private val _minSimilarity = MutableStateFlow(DEFAULT_MIN_SIMILARITY.toFloat())
    val minSimilarity: StateFlow<Float> = _minSimilarity.asStateFlow()

    private val _burst = MutableStateFlow(BurstState())
    val burst: StateFlow<BurstState> = _burst.asStateFlow()
    private var recorder: BurstRecorder? = null
    private var burstDir: File? = null
    @Volatile private var torchOn = false

    private val stability = MatchStability()
    private var lastFrameAt = 0L
    private var previousDetection: Detection? = null

    init {
        worker.execute {
            _engineState.value = try {
                loadEngine(application).also { engine = it }.let { e ->
                    if (!e.hasLearnedDetector) setDetector(DetectorKind.CLASSIC)
                    _availableLanguages.value = e.index.languages
                    _languages.value = e.index.languages.toSet()
                    EngineState.Ready(e.info.model, e.index.size, e.index.cards.count { it.unseen }, e.hasLearnedDetector)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "recognition engine failed to load", t)
                EngineState.Failed(t.message ?: t.javaClass.simpleName)
            }
        }
    }

    /**
     * The lab compares the app with the Python pipeline, so it prefers the bundled assets that ml/app_assets.py built
     * from the same model; without them it tests the installed language packs.
     */
    private fun loadEngine(application: Application): RecognitionEngine {
        val numberReader = application.appContainer::newNumberReader
        if (RecognitionEngine.hasBundledAssets(application)) return RecognitionEngine.load(application, numberReader)
        val packs = application.appContainer.packs
        val load = PackLoader.load(packs.installed.value)
        packs.reportLoad(load)
        return RecognitionEngine.load(
            load.loaded ?: throw FileNotFoundException("no lab assets and no usable language pack"),
            numberReader,
        )
    }

    fun setPaused(value: Boolean) = _paused.update { value }
    fun setTorch(value: Boolean) {
        torchOn = value
    }
    fun setBurstLabels(labels: BurstLabels) = _burst.update { it.copy(labels = labels) }

    /**
     * Starts a finish burst of the card the live view shows (#144): the next frames of that card are saved while the
     * user tilts it. Does nothing without a stable live card.
     */
    fun startBurst() = worker.execute {
        val live = _live.value
        val card = live.result?.best?.card
        if (card == null || !live.stable || live.outdated || recorder != null) return@execute
        val root = getApplication<Application>().getExternalFilesDir(BURST_DIR)
        if (root == null) {
            _burst.update { it.copy(error = "no external files dir") }
            return@execute
        }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val dir = File(root, "$stamp-${card.lang}-${card.cardId.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        if (!dir.mkdirs()) {
            _burst.update { it.copy(error = "could not create ${dir.name}") }
            return@execute
        }
        burstDir = dir
        recorder = BurstRecorder(card.key, _burst.value.labels)
        _burst.update { it.copy(recording = 0, card = card.key, folder = root.absolutePath, error = null) }
    }

    /** Drops the burst being recorded and its files. */
    fun cancelBurst() = worker.execute {
        recorder = null
        burstDir?.deleteRecursively()
        burstDir = null
        _burst.update { it.copy(recording = null) }
    }

    /** Called on [worker] for every identified live frame while a burst is recorded. */
    private fun recordBurstFrame(frame: Bitmap, result: RecognitionResult, now: Long) {
        val r = recorder ?: return
        val dir = burstDir ?: return
        if (!r.accept(result.best.card.key, now)) return
        try {
            r.add(now) { atMs, index ->
                writeJpeg(frame, File(dir, BurstRecorder.frameName(index)))
                val crop = rectifiedCrop(frame, result.quad)
                try {
                    writeJpeg(crop, File(dir, BurstRecorder.cropName(index)))
                } finally {
                    crop.recycle()
                }
                BurstFrame(
                    BurstRecorder.frameName(index), BurstRecorder.cropName(index), result.quad,
                    frame.width, frame.height, result.best.similarity, atMs,
                )
            }
            if (r.done) finishBurst(r, dir, result) else _burst.update { it.copy(recording = r.saved.size) }
        } catch (t: Throwable) {
            Log.e(TAG, "burst frame failed", t)
            recorder = null
            dir.deleteRecursively()
            burstDir = null
            _burst.update { it.copy(recording = null, error = t.message ?: t.javaClass.simpleName) }
        }
    }

    private fun finishBurst(r: BurstRecorder, dir: java.io.File, result: RecognitionResult) {
        val card = result.best.card
        val json = burstJson(
            id = dir.name,
            cardId = card.cardId,
            lang = card.lang,
            labels = r.labels,
            torch = torchOn,
            frames = r.saved,
            createdAt = Instant.now().toString(),
            device = Build.MODEL,
            app = BuildConfig.VERSION_NAME,
        )
        File(dir, "burst.json").writeText(json.toString(1))
        recorder = null
        burstDir = null
        _burst.update { it.copy(recording = null, saved = it.saved + 1, card = card.key) }
    }
    fun setReadNumber(value: Boolean) = _readNumber.update { value }
    fun setSaveCaptures(value: Boolean) = _saveCaptures.update { value }
    fun setTwoPass(value: Boolean) = _twoPass.update { value }
    fun setMinScore(value: Float) = _minScore.update { value }

    /** Turns a language on or off; at least one stays on. */
    fun toggleLanguage(lang: String) = _languages.update { if (lang in it) (it - lang).ifEmpty { it } else it + lang }
    private fun languageFilter(): Set<String>? = _languages.value.takeIf { it.size < _availableLanguages.value.size }

    /** Switches the outline detector; the minimum outline score resets to that detector's default (different scales). */
    fun setDetector(kind: DetectorKind) {
        _detector.value = kind
        _minScore.value = when (kind) {
            DetectorKind.LEARNED -> CornerDetector.DEFAULT_MIN_SCORE
            DetectorKind.CLASSIC -> DEFAULT_MIN_OUTLINE_SCORE
        }.toFloat()
        previousDetection = null
    }
    fun setMinSimilarity(value: Float) = _minSimilarity.update { value }
    fun backToLive() = _still.update { null }

    /** CameraX analyzer, called on [worker]. */
    fun analyze(image: ImageProxy) {
        val e = engine
        if (e == null || _paused.value || _still.value != null) {
            image.close()
            return
        }
        val bitmap = image.use { upright(it.toBitmap(), it.imageInfo.rotationDegrees) }
        val bgr = RecognitionEngine.toBgr(bitmap)
        try {
            val w = bgr.cols()
            val h = bgr.rows()
            val t0 = SystemClock.elapsedRealtime()
            val detection = e.detect(bgr, ScanGuide.forFrame(w, h).searchArea, _detector.value)
            val detectMs = SystemClock.elapsedRealtime() - t0
            val gate = outlineGate(detection, previousDetection, _minScore.value.toDouble())
            previousDetection = detection
            val now = SystemClock.elapsedRealtime()
            val fps = if (lastFrameAt == 0L) 0f else 1000f / max(1L, now - lastFrameAt)
            lastFrameAt = now
            val before = _live.value
            if (gate != GateResult.Accepted) {
                _live.value = before.copy(
                    stable = stability.isStable(now), framesPerSecond = fps, outline = detection,
                    frameWidth = w, frameHeight = h, gate = gate, noMatchSimilarity = null,
                )
                return
            }
            val result = runCatching { e.identify(bgr, detection, _readNumber.value, detectMs, languageFilter()) }
                .onFailure { Log.e(TAG, "frame failed", it) }
                .getOrNull() ?: return
            if (result.best.similarity < _minSimilarity.value) {
                _live.value = before.copy(
                    stable = stability.isStable(now), framesPerSecond = fps, outline = detection,
                    frameWidth = w, frameHeight = h, gate = gate, noMatchSimilarity = result.best.similarity,
                )
                return
            }
            val stable = stability.add(now, result.best.card.key)
            _live.value = LiveState(result, stable, fps, detection, w, h, gate, null)
            recordBurstFrame(bitmap, result, now)
        } finally {
            bgr.release()
        }
    }

    /**
     * A full-resolution photo from the shutter button, called on [worker]. Searches the guide area like live; unlike
     * live, photos get two-pass detection ([twoPass]).
     */
    fun onCaptured(image: ImageProxy) {
        val bitmap = image.use { upright(it.toBitmap(), it.imageInfo.rotationDegrees) }
        val savedTo = if (_saveCaptures.value) save(bitmap) else null
        recognizeStill(downscale(bitmap), savedTo, useGuide = true)
    }

    fun onCaptureFailed(message: String) {
        _still.value = null
        Log.w(TAG, "capture failed: $message")
    }

    /** A photo picked from the gallery. */
    fun recognizeUri(uri: Uri) = worker.execute {
        val bitmap = runCatching { decode(uri) }.getOrElse {
            Log.e(TAG, "decode failed", it)
            return@execute
        }
        recognizeStill(downscale(bitmap), null, useGuide = false)
    }

    private fun recognizeStill(photo: Bitmap, savedTo: String?, useGuide: Boolean) {
        val e = engine ?: return
        _still.value = StillState(photo, null, savedTo, null, useGuide)
        _still.value = runCatching {
            val bgr = RecognitionEngine.toBgr(photo)
            try {
                val t0 = SystemClock.elapsedRealtime()
                val area = if (useGuide) ScanGuide.forFrame(bgr.cols(), bgr.rows()).searchArea else null
                val detection = e.detect(bgr, area, _detector.value)
                val detectMs = SystemClock.elapsedRealtime() - t0
                // The second pass runs the learned detector, so with the classic outline chosen the photo stays one pass.
                if (_twoPass.value && _detector.value == DetectorKind.LEARNED) {
                    e.identifyTwoPass(bgr, detection, _readNumber.value, detectMs, languageFilter())
                } else {
                    e.identify(bgr, detection, _readNumber.value, detectMs, languageFilter())
                }
            } finally {
                bgr.release()
            }
        }.fold(
            onSuccess = { StillState(photo, it, savedTo, null, useGuide) },
            onFailure = { StillState(photo, null, savedTo, it.message ?: it.javaClass.simpleName, useGuide) },
        )
    }

    /** Decodes a gallery photo already shrunk towards [STILL_MAX_SIDE], so a 50 MP photo never sits in memory whole. */
    private fun decode(uri: Uri): Bitmap {
        val resolver = getApplication<Application>().contentResolver
        return if (Build.VERSION.SDK_INT >= 28) {
            val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // OpenCV needs CPU-readable pixels
                val side = max(info.size.width, info.size.height)
                if (side > STILL_MAX_SIDE) {
                    val s = STILL_MAX_SIDE.toFloat() / side
                    decoder.setTargetSize((info.size.width * s).roundToInt(), (info.size.height * s).roundToInt())
                }
            }
            if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
            else bitmap.copy(Bitmap.Config.ARGB_8888, false).also { bitmap.recycle() }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= STILL_MAX_SIDE) sample *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            resolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, options) }
                ?: throw IllegalArgumentException("not an image: $uri")
        }
    }

    /** Saves the original photo to Pictures/CardIndexLab, so it can join the real-photo evaluation on the PC. */
    private fun save(bitmap: Bitmap): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        val resolver = getApplication<Application>().contentResolver
        val name = "card-${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CardIndexLab")
        }
        return runCatching {
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it!!) }
            "Pictures/CardIndexLab/$name"
        }.onFailure { Log.e(TAG, "save failed", it) }.getOrNull()
    }

    override fun onCleared() {
        worker.execute { engine?.close(); engine = null }
        worker.shutdown()
    }

    companion object {
        private const val TAG = "LabViewModel"
        /** Finish bursts (#144) below the app's external files dir; ml/README.md has the `adb pull` line. */
        const val BURST_DIR = "finish-bursts"
        /** Burst crops at twice the embedder's crop size: foil sparkle is fine detail. */
        private const val BURST_CROP_W = 504
        private const val BURST_CROP_H = 704

        private fun writeJpeg(bitmap: Bitmap, file: java.io.File) {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        }

        /** The card in [frame] warped to an upright [BURST_CROP_W] × [BURST_CROP_H] crop; [quad] is TL, TR, BR, BL. */
        private fun rectifiedCrop(frame: Bitmap, quad: Quad): Bitmap {
            val src = quad.flatMap { listOf(it.x.toFloat(), it.y.toFloat()) }.toFloatArray()
            val w = BURST_CROP_W.toFloat()
            val h = BURST_CROP_H.toFloat()
            val dst = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
            val m = Matrix().apply { setPolyToPoly(src, 0, dst, 0, 4) }
            val out = Bitmap.createBitmap(BURST_CROP_W, BURST_CROP_H, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(frame, m, Paint(Paint.FILTER_BITMAP_FLAG))
            return out
        }
        /** Longest side for photos; detection works at 640 px, the number OCR profits from more. */
        private const val STILL_MAX_SIDE = 2048

        fun upright(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
            if (rotationDegrees == 0) return bitmap
            val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }

        fun downscale(bitmap: Bitmap): Bitmap {
            val side = max(bitmap.width, bitmap.height)
            if (side <= STILL_MAX_SIDE) return bitmap
            val s = STILL_MAX_SIDE.toFloat() / side
            return bitmap.scale((bitmap.width * s).roundToInt(), (bitmap.height * s).roundToInt())
        }
    }
}
