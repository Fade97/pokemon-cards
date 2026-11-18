// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.scan

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.fabianduerkop.cardindex.data.appContainer
import de.fabianduerkop.cardindex.data.db.CardIndexDatabase
import de.fabianduerkop.cardindex.data.packs.PackLoader
import de.fabianduerkop.cardindex.data.scan.ReviewQueueRepository
import de.fabianduerkop.cardindex.data.scan.ScanRepository
import de.fabianduerkop.cardindex.model.ReviewReason
import de.fabianduerkop.cardindex.model.needsVariantCheck
import de.fabianduerkop.cardindex.recognition.CornerDetector
import de.fabianduerkop.cardindex.recognition.DEFAULT_MIN_OUTLINE_SCORE
import de.fabianduerkop.cardindex.recognition.DEFAULT_MIN_SIMILARITY
import de.fabianduerkop.cardindex.recognition.Detection
import de.fabianduerkop.cardindex.recognition.DetectorKind
import de.fabianduerkop.cardindex.recognition.EmbedderBackend
import de.fabianduerkop.cardindex.recognition.FrameSignal
import de.fabianduerkop.cardindex.recognition.GateResult
import de.fabianduerkop.cardindex.recognition.LiveScanDecider
import de.fabianduerkop.cardindex.recognition.Quad
import de.fabianduerkop.cardindex.recognition.RecognitionEngine
import de.fabianduerkop.cardindex.recognition.RecognitionResult
import de.fabianduerkop.cardindex.recognition.RefCard
import de.fabianduerkop.cardindex.recognition.ScanEvent
import de.fabianduerkop.cardindex.recognition.ScanGuide
import de.fabianduerkop.cardindex.recognition.judgeMatch
import de.fabianduerkop.cardindex.recognition.outlineGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.opencv.core.Mat
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

sealed interface ScanEngineState {
    data object Loading : ScanEngineState
    data class Ready(val cards: Int, val backend: EmbedderBackend) : ScanEngineState

    /** No language pack is installed and usable; [broken] are the ones that failed their checks. */
    data class NoLanguages(val broken: Set<String>) : ScanEngineState

    data class Failed(val message: String) : ScanEngineState
}

/** What the viewfinder tells the user about the current frame. */
enum class ScanHint {
    /** No card in the guide. */
    LOOKING,

    /** A card, but moving or only partly in the guide. */
    HOLD_STILL,

    /** A card that matches nothing in the installed languages well enough. */
    UNKNOWN,

    /** Identified; reading the number and waiting for a second agreeing frame. */
    READING,

    /** This card was just added or queued; take the next one. */
    DONE,
}

/** The current frame: outline and guide for the overlay, and the card it shows, if identified. */
data class Viewfinder(
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val outline: Quad? = null,
    val hint: ScanHint = ScanHint.LOOKING,
    val card: RefCard? = null,
)

/** The most recent decision, shown in the tray until the next one. */
sealed interface LastScan {
    val card: RefCard

    /** [contributionId]: the crop queued for the open dataset, if the user contributes. */
    data class Added(
        override val card: RefCard,
        val copyId: Long,
        val undone: Boolean = false,
        val contributionId: Long? = null,
    ) : LastScan

    data class Queued(override val card: RefCard, val reason: ReviewReason) : LastScan
}

/**
 * Live scanning (#8): every camera frame (latest only) goes through detect → gate → embed → kNN; cards that are not
 * decided yet also get the number OCR and rerank. [LiveScanDecider] turns frames into one decision per card:
 * confident cards go straight into All Cards (undoable), unsure ones into the review queue. All engine work runs on
 * [worker], so the engine is never used concurrently; database writes run on the IO dispatcher.
 */
class ScanViewModel(application: Application) : AndroidViewModel(application) {
    val worker: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "live-scan") }
    private var engine: RecognitionEngine? = null
    private var detector = DetectorKind.CLASSIC
    private var minOutlineScore = DEFAULT_MIN_OUTLINE_SCORE
    private var frameIntervalMs = 0L

    private val db = CardIndexDatabase.get(application)
    private val scans = ScanRepository(db)
    private val reviewQueue = ReviewQueueRepository(db, application.filesDir)

    private val _engine = MutableStateFlow<ScanEngineState>(ScanEngineState.Loading)
    val engineState: StateFlow<ScanEngineState> = _engine.asStateFlow()
    private val _viewfinder = MutableStateFlow(Viewfinder())
    val viewfinder: StateFlow<Viewfinder> = _viewfinder.asStateFlow()
    private val _lastScan = MutableStateFlow<LastScan?>(null)
    val lastScan: StateFlow<LastScan?> = _lastScan.asStateFlow()

    /** Cards added or queued since the screen opened, minus undone ones. */
    private val _scanned = MutableStateFlow(0)
    val scanned: StateFlow<Int> = _scanned.asStateFlow()
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()
    val reviewCount: StateFlow<Int> = reviewQueue.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val decider = LiveScanDecider<RecognitionResult>()
    private var previousDetection: Detection? = null
    private var lastFrameAt = 0L

    private val packs = application.appContainer.packs
    private val contributions = application.appContainer.contributions
    private val numberReader = application.appContainer::newNumberReader

    init {
        // Reload when a pack or the model is installed, replaced or removed; not when a pack is only marked broken
        // (that comes from loading, and the loaded engine already left it out).
        viewModelScope.launch {
            packs.installed
                .map { p -> p.model?.dir to p.packs.mapValues { it.value.dir } }
                .distinctUntilChanged()
                .collect { worker.execute(::loadEngine) }
        }
    }

    /**
     * Installed language packs; without any, the recognition-lab assets of a debug build (so scanning works before
     * the pack service is deployed).
     */
    private fun loadEngine() {
        val application = getApplication<Application>()
        engine?.close()
        engine = null
        _engine.value = ScanEngineState.Loading
        _engine.value = try {
            val installed = packs.installed.value
            val loaded = if (installed.isEmpty && RecognitionEngine.hasBundledAssets(application)) {
                RecognitionEngine.load(application, numberReader, preferGpu = true)
            } else {
                val load = PackLoader.load(installed)
                packs.reportLoad(load)
                load.loaded?.let { RecognitionEngine.load(it, numberReader, preferGpu = true) }
            }
            if (loaded == null) {
                ScanEngineState.NoLanguages(packs.installed.value.broken)
            } else {
                loaded.let { e ->
                    engine = e
                    detector = if (e.hasLearnedDetector) DetectorKind.LEARNED else DetectorKind.CLASSIC
                    minOutlineScore = if (e.hasLearnedDetector) CornerDetector.DEFAULT_MIN_SCORE else DEFAULT_MIN_OUTLINE_SCORE
                    decider.reset()
                    previousDetection = null
                    // Without the GPU every frame costs several times more; fewer frames keep the phone cool
                    // and the preview smooth, at the price of a slightly slower decision.
                    frameIntervalMs = if (e.info.backend == EmbedderBackend.GPU) 0L else CPU_FRAME_INTERVAL_MS
                    Log.i(TAG, "engine ready: ${e.info.model} on ${e.info.backend}, ${e.index.languages}")
                    ScanEngineState.Ready(e.index.size, e.info.backend)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "recognition engine failed to load", t)
            ScanEngineState.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    fun setPaused(value: Boolean) {
        _paused.value = value
        // After a pause the card in view is a new situation; don't let an old track decide it.
        if (!value) worker.execute { decider.reset(); previousDetection = null }
    }

    /** CameraX analyzer, called on [worker] with the latest frame only. */
    fun analyze(image: ImageProxy) {
        val e = engine
        val now = SystemClock.elapsedRealtime()
        if (e == null || _paused.value || now - lastFrameAt < frameIntervalMs) {
            image.close()
            return
        }
        lastFrameAt = now
        val bitmap = image.use { upright(it.toBitmap(), it.imageInfo.rotationDegrees) }
        val bgr = RecognitionEngine.toBgr(bitmap)
        try {
            val w = bgr.cols()
            val h = bgr.rows()
            val detection = e.detect(bgr, ScanGuide.forFrame(w, h).searchArea, detector)
            val gate = outlineGate(detection, previousDetection, minOutlineScore)
            previousDetection = detection
            var card: RefCard? = null
            val signal: FrameSignal<RecognitionResult> = when (gate) {
                GateResult.NoOutline, is GateResult.LowScore, GateResult.BadShape -> FrameSignal.Empty
                GateResult.Moving -> FrameSignal.Unreadable
                GateResult.Accepted -> {
                    val result = runCatching { identify(e, bgr, detection) }
                        .onFailure { Log.e(TAG, "frame failed", it) }
                        .getOrNull()
                    if (result == null) {
                        FrameSignal.Unreadable
                    } else {
                        card = result.best.card
                        FrameSignal.Identified(
                            key = result.best.card.key,
                            related = result.reranked.take(3).map { it.card.key }.toSet(),
                            similarity = result.best.similarity,
                            // A card already decided skipped the OCR; the decider ignores its verdict anyway.
                            unsure = if (decider.isHandled(result.best.card.key)) {
                                ReviewReason.NUMBER_NOT_READ
                            } else {
                                judgeMatch(result.reranked, result.reading, MIN_SIMILARITY)
                            },
                            payload = result,
                        )
                    }
                }
            }
            val event = decider.onFrame(now, signal)
            val key = (signal as? FrameSignal.Identified)?.key
            val hint = when {
                event != null || (key != null && decider.isHandled(key)) -> ScanHint.DONE
                signal is FrameSignal.Identified -> ScanHint.READING
                gate == GateResult.Accepted -> ScanHint.UNKNOWN
                gate == GateResult.Moving -> ScanHint.HOLD_STILL
                else -> ScanHint.LOOKING
            }
            // Only an outline that passed the score and shape checks is a card; a rejected one (an empty room,
            // a twisted quad) must not be drawn while the hint says there is no card (#55).
            val outline = detection?.quad?.takeIf { gate == GateResult.Accepted || gate == GateResult.Moving }
            _viewfinder.value = Viewfinder(w, h, outline, hint, card)
            event?.let(::onDecision)
        } finally {
            bgr.release()
        }
    }

    /**
     * Embedding and kNN; the number OCR only for a card that still has to be decided. Returns null when nothing in
     * the index is similar enough.
     */
    private fun identify(e: RecognitionEngine, bgr: Mat, detection: Detection?): RecognitionResult? {
        val base = e.identify(bgr, detection, readNumber = false)
        if (base.candidates.first().similarity < MIN_SIMILARITY) return null
        return if (decider.isHandled(base.best.card.key)) base else e.readNumber(bgr, base)
    }

    private fun onDecision(event: ScanEvent<RecognitionResult>) {
        val result = event.payload
        val card = result.best.card
        val checkVariant = needsVariantCheck(card.setId)
        when {
            event is ScanEvent.Add && !checkVariant -> viewModelScope.launch(Dispatchers.IO) {
                runCatching { scans.addFromScan(card) }
                    .onSuccess { id ->
                        // An auto-added card counts as confirmed (issue #14); Undo below withdraws it again.
                        val contribution = runCatching { contributions.contribute(card.cardId, card.lang, jpeg(result.crop)) }
                            .onFailure { Log.e(TAG, "queueing the contribution failed", it) }
                            .getOrNull()
                        _lastScan.value = LastScan.Added(card, id, contributionId = contribution)
                        _scanned.update { it + 1 }
                    }
                    .onFailure { Log.e(TAG, "adding ${card.key} failed", it) }
            }
            else -> {
                val reason = (event as? ScanEvent.Review)?.reason ?: ReviewReason.CHECK_VARIANT
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching {
                        reviewQueue.enqueue(jpeg(result.crop), result.reranked, result.reading, reason, checkVariant)
                    }
                        .onSuccess {
                            _lastScan.value = LastScan.Queued(card, reason)
                            _scanned.update { it + 1 }
                        }
                        .onFailure { Log.e(TAG, "queueing ${card.key} failed", it) }
                }
            }
        }
    }

    /** Takes back the last added card. */
    fun undo() {
        val added = _lastScan.value as? LastScan.Added ?: return
        if (added.undone) return
        viewModelScope.launch(Dispatchers.IO) {
            // An undone scan was a wrong match: it must not end up in the dataset with that label.
            added.contributionId?.let { runCatching { contributions.withdraw(it) } }
            if (scans.undo(added.copyId)) {
                _lastScan.update { if (it == added) added.copy(undone = true) else it }
                _scanned.update { maxOf(0, it - 1) }
            }
        }
    }

    override fun onCleared() {
        worker.execute { engine?.close(); engine = null }
        worker.shutdown()
    }

    companion object {
        private const val TAG = "ScanViewModel"

        /** About 4 frames per second when the embedder runs on the CPU. */
        private const val CPU_FRAME_INTERVAL_MS = 250L
        private val MIN_SIMILARITY = DEFAULT_MIN_SIMILARITY.toFloat()

        fun upright(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
            if (rotationDegrees == 0) return bitmap
            val m = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, m, true)
        }

        private fun jpeg(bitmap: Bitmap): ByteArray =
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }
}
