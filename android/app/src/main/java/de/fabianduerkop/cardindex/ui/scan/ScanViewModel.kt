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
import de.fabianduerkop.cardindex.data.db.CollectionEntity
import de.fabianduerkop.cardindex.data.db.CollectionSummary
import de.fabianduerkop.cardindex.data.packs.PackLoader
import de.fabianduerkop.cardindex.data.prices.PriceRegion
import de.fabianduerkop.cardindex.data.scan.CopyDefaults
import de.fabianduerkop.cardindex.data.scan.ReviewQueueRepository
import de.fabianduerkop.cardindex.data.scan.ScanRepository
import de.fabianduerkop.cardindex.data.scan.SessionReceipt
import de.fabianduerkop.cardindex.model.PriceSource
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.opencv.core.Mat
import java.io.ByteArrayOutputStream
import java.util.UUID
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

    /** This card was just added; take the next one. */
    DONE,

    /** This card was unsure and went to the review queue; take the next one. */
    QUEUED,
}

/** The current frame: outline and guide for the overlay, and the card it shows, if identified. */
data class Viewfinder(
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val outline: Quad? = null,
    val hint: ScanHint = ScanHint.LOOKING,
    val card: RefCard? = null,
    /** The decision the hint belongs to; the screen shows a tray hit only when the hit carries the same one. */
    val decision: Long = 0,
)


/**
 * Live scanning (#8): every camera frame (latest only) goes through detect → gate → embed → kNN; cards that are not
 * decided yet also get the number OCR and rerank. [LiveScanDecider] turns frames into one decision per card:
 * confident cards go straight into All Cards and the chosen scan target (undoable), unsure ones into the review queue. All engine work runs on
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
    private val _tray = MutableStateFlow(ScanTray())
    val tray: StateFlow<ScanTray> = _tray.asStateFlow()
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()
    val reviewCount: StateFlow<Int> = reviewQueue.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val decider = LiveScanDecider<RecognitionResult>()

    /** Cards the decider handled by queueing them for review; worker thread only, cleared with the decider. */
    private val queuedKeys = HashSet<String>()

    /** Per card key, the number of its latest decision: a second copy of a print must not show the first one's hit. */
    private val decisions = HashMap<String, Long>()
    private var lastDecision = 0L
    private var previousDetection: Detection? = null
    private var lastFrameAt = 0L

    private val scanTarget = application.appContainer.scanTarget

    /** The custom collection scans also go into (#139); null is All Cards only. */
    val target: StateFlow<CollectionEntity?> = scanTarget.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Custom collections the user can scan into. */
    val collections: StateFlow<List<CollectionSummary>> = application.appContainer.collections.observeCustomCollections()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val packs = application.appContainer.packs
    private val prices = application.appContainer.prices
    private val settings = application.appContainer.settings

    /** The user's main market, for the price of a hit and the session total (#162). */
    val priceSource: StateFlow<PriceSource> = application.appContainer.primaryPriceSource
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PriceRegion.defaultSource())

    /** This visit of the Scan screen: its copies go into the session ledger under this id (#163). */
    private val sessionId: String = UUID.randomUUID().toString()
    private val sessions = db.scanSessionDao()

    /**
     * The receipt of this session (#163), from the ledger as the collection is now, priced in the main market.
     * Only computed while the receipt is on screen.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val receipt: StateFlow<SessionReceipt?> = combine(sessions.observeSession(sessionId), prices.observeAll(), priceSource) { copies, all, source ->
        Triple(copies, all, source)
    }
        .mapLatest { (copies, all, source) ->
            val setIds = copies.map { it.card.setId }.distinct()
            val before = if (setIds.isEmpty()) emptyList() else sessions.otherCopiesInSets(sessionId, setIds)
            SessionReceipt.build(copies, before, all, source)
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether the user has scanned before; until then the tray explains how scanning works (#162). */
    val introSeen: StateFlow<Boolean> = settings.scanIntroSeenFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settings.scanIntroSeen)
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
                    queuedKeys.clear()
                    decisions.clear()
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
        if (!value) worker.execute { decider.reset(); queuedKeys.clear(); decisions.clear(); previousDetection = null }
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
            if (event != null && goesToReview(event)) queuedKeys += event.payload.best.card.key
            val decision = event?.let { ++lastDecision }
            if (event != null && decision != null) decisions[event.payload.best.card.key] = decision
            val handledKey = event?.payload?.best?.card?.key ?: key?.takeIf { decider.isHandled(it) }
            val hint = when {
                handledKey != null -> if (handledKey in queuedKeys) ScanHint.QUEUED else ScanHint.DONE
                signal is FrameSignal.Identified -> ScanHint.READING
                gate == GateResult.Accepted -> ScanHint.UNKNOWN
                gate == GateResult.Moving -> ScanHint.HOLD_STILL
                else -> ScanHint.LOOKING
            }
            // Only an outline that passed the score and shape checks is a card; a rejected one (an empty room,
            // a twisted quad) must not be drawn while the hint says there is no card (#55).
            val outline = detection?.quad?.takeIf { gate == GateResult.Accepted || gate == GateResult.Moving }
            _viewfinder.value = Viewfinder(w, h, outline, hint, card, handledKey?.let { decisions[it] } ?: 0)
            if (event != null && decision != null) onDecision(event, decision)
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

    /** An unsure card, or one whose set needs a variant check, goes to review instead of All Cards. */
    private fun goesToReview(event: ScanEvent<RecognitionResult>): Boolean =
        event !is ScanEvent.Add || event.payload.best.card.let { needsVariantCheck(it.setId, it.printings) }

    private fun onDecision(event: ScanEvent<RecognitionResult>, decision: Long) {
        val result = event.payload
        val card = result.best.card
        val checkVariant = needsVariantCheck(card.setId, card.printings)
        when {
            !goesToReview(event) -> viewModelScope.launch(Dispatchers.IO) {
                runCatching { scans.addHit(card, scanTarget.uuid, sessionId) }
                    .onSuccess { hit ->
                        val id = hit.copyId
                        // An auto-added card counts as confirmed (issue #14); Undo below withdraws it again.
                        val contribution = runCatching { contributions.contribute(card.cardId, card.lang, jpeg(result.crop)) }
                            .onFailure { Log.e(TAG, "queueing the contribution failed", it) }
                            .getOrNull()
                        // The tray shows the finish the copy got: a single-printing card's own one (#143).
                        val variant = CopyVariant(finish = CopyDefaults.finishFor(card.printings))
                        val cached = runCatching { prices.cached(card.cardId) }.getOrDefault(emptyList())
                        _tray.update {
                            it.record(
                                LastScan.Added(
                                    card,
                                    id,
                                    contributionId = contribution,
                                    variant = variant,
                                    prices = cached,
                                    wishlistFound = hit.wishlistFound,
                                    decision = decision,
                                ),
                            )
                        }
                        markIntroSeen()
                        // A card nobody owned before has no cached price yet: ask the price service once, then
                        // show it on the hit and in the total. Offline it simply stays without a price.
                        if (cached.isEmpty()) fetchPrice(card, id)
                    }
                    .onFailure {
                        Log.e(TAG, "adding ${card.key} failed", it)
                        onSaveFailed(card)
                    }
            }
            else -> {
                val reason = (event as? ScanEvent.Review)?.reason ?: ReviewReason.CHECK_VARIANT
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching {
                        reviewQueue.enqueue(jpeg(result.crop), result.reranked, result.reading, reason, checkVariant)
                    }
                        .onSuccess { itemId ->
                            _tray.update { it.record(LastScan.Queued(card, reason, itemId)) }
                            markIntroSeen()
                        }
                        .onFailure {
                            Log.e(TAG, "queueing ${card.key} failed", it)
                            onSaveFailed(card)
                        }
                }
            }
        }
    }

    /** The first decision ever ends the tray's first-run explanation (#162); later ones don't write again. */
    private fun markIntroSeen() {
        if (!settings.scanIntroSeen) settings.scanIntroSeen = true
    }

    /** Fetches the prices of [card] and puts them on copy [copyId]'s tray entry; failures leave it unpriced. */
    private suspend fun fetchPrice(card: RefCard, copyId: Long) {
        runCatching {
            prices.refresh(listOf(card.cardId))
            val fetched = prices.cached(card.cardId)
            if (fetched.isNotEmpty()) _tray.update { it.withPrices(copyId, fetched) }
        }.onFailure { Log.w(TAG, "fetching the price of ${card.key} failed", it) }
    }

    /**
     * Nothing was saved for [card] (#126): the tray says so instead of showing it as added or queued, and the decider
     * forgets the card, so holding it in view again retries.
     */
    private fun onSaveFailed(card: RefCard) {
        _tray.update { it.failed(card) }
        // The worker is gone once the screen is cleared; then there is no session left to retry in.
        runCatching {
            worker.execute {
                decider.forget(card.key)
                queuedKeys -= card.key
            }
        }
    }

    /**
     * Takes back the newest decision of this session: removes exactly the copy it added, or the review item it queued.
     * The tray then shows the decision before it, with the finish the user chose for it. The entry leaves the history
     * at once, so every press takes back one decision and a press with an empty history does nothing.
     */
    fun undo() {
        var taken: LastScan? = null
        _tray.update { tray ->
            val result = tray.undo()
            taken = result?.first
            result?.second ?: tray
        }
        val scan = taken ?: return
        viewModelScope.launch(Dispatchers.IO) {
            when (scan) {
                is LastScan.Added -> {
                    // An undone scan was a wrong match: it must not end up in the dataset with that label.
                    scan.contributionId?.let { runCatching { contributions.withdraw(it) } }
                    runCatching { scans.undo(scan.copyId) }.onFailure { Log.e(TAG, "undoing copy ${scan.copyId} failed", it) }
                }
                // Already confirmed or discarded in Review: that was the user's decision, so it stays.
                is LastScan.Queued -> runCatching { reviewQueue.discard(scan.itemId) }
                    .onFailure { Log.e(TAG, "undoing review item ${scan.itemId} failed", it) }
            }
        }
    }

    /** Later scans go into custom collection [collectionId] too; null is All Cards only. Earlier scans stay where they are. */
    fun chooseTarget(collectionId: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { scanTarget.choose(collectionId) }.onFailure { Log.e(TAG, "choosing scan target $collectionId failed", it) }
        }
    }

    /** The user's finish, edition and condition for a copy this session added; saved to that copy only. */
    fun chooseVariant(copyId: Long, variant: CopyVariant) {
        _tray.update { it.withVariant(copyId, variant) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { scans.setVariant(copyId, variant.finish, variant.edition, variant.condition) }
                .onFailure { Log.e(TAG, "saving the variant of copy $copyId failed", it) }
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
