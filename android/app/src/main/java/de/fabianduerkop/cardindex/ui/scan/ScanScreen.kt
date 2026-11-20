// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.scan

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.data.collections.Milestone
import de.fabianduerkop.cardindex.data.db.CollectionSummary
import de.fabianduerkop.cardindex.data.prices.CollectionValue
import de.fabianduerkop.cardindex.data.scan.FirstGoal
import de.fabianduerkop.cardindex.model.Money
import de.fabianduerkop.cardindex.model.PriceSource
import de.fabianduerkop.cardindex.model.ReviewReason
import de.fabianduerkop.cardindex.model.finishOrder
import de.fabianduerkop.cardindex.recognition.RefCard
import de.fabianduerkop.cardindex.recognition.ScanGuide
import de.fabianduerkop.cardindex.ui.components.ChoiceChip
import de.fabianduerkop.cardindex.ui.components.CollectorNumber
import de.fabianduerkop.cardindex.ui.components.CountBadge
import de.fabianduerkop.cardindex.ui.components.NoticeTone
import de.fabianduerkop.cardindex.ui.components.PrimaryButton
import de.fabianduerkop.cardindex.ui.components.SecondaryButton
import de.fabianduerkop.cardindex.ui.components.StatusNotice
import de.fabianduerkop.cardindex.ui.components.TextAction
import de.fabianduerkop.cardindex.ui.components.focusRing
import de.fabianduerkop.cardindex.ui.components.padCollectorNumber
import de.fabianduerkop.cardindex.ui.components.rememberReducedMotion
import de.fabianduerkop.cardindex.ui.review.VariantChoices
import de.fabianduerkop.cardindex.ui.review.conditionLabel
import de.fabianduerkop.cardindex.ui.review.editionLabel
import de.fabianduerkop.cardindex.ui.review.finishLabel
import de.fabianduerkop.cardindex.ui.theme.CameraColors
import de.fabianduerkop.cardindex.ui.theme.LumifoilTheme
import de.fabianduerkop.cardindex.ui.theme.Spacing
import coil3.compose.AsyncImage
import kotlin.coroutines.resume
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.time.LocalDate
import java.util.Locale

/**
 * Live scan (DESIGN.md, Recognition; Pearl scanner in `design/mockups/lumifoil`, #98): no shutter. A card held in
 * the guide is added to All Cards as soon as its number confirms it, unsure cards go to Review, and the camera never
 * stops for either. A chip in the tray picks a custom collection the scans go into as well (#139). The camera area says what is happening (searching, reading, added, saved for review, not
 * recognized, paused, camera error); the tray below shows the count, the review queue and the latest card with its
 * finish, edition and condition as the user's choice, plus Undo.
 */
@Composable
fun ScanScreen(
    modifier: Modifier = Modifier,
    onOpenReview: () -> Unit = {},
    onOpenLab: () -> Unit = {},
    onOpenLanguages: () -> Unit = {},
    onOpenCollections: () -> Unit = {},
    onOpenSet: (setId: String) -> Unit = {},
    viewModel: ScanViewModel = viewModel(),
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasCameraPermission(context)) }
    // Set after the user said no once: Android may stop asking, so the screen also offers the app settings.
    var denied by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        denied = !it
    }
    // Coming back from the app settings (or a revoked permission) must show the right state without a restart.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { granted = hasCameraPermission(context) }
    val engine by viewModel.engineState.collectAsStateWithLifecycle()
    // The lab is a test bench for the models; release builds don't offer it.
    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        when {
            engine is ScanEngineState.Failed -> ScanMessage {
                StatusNotice(
                    stringResource(R.string.scan_engine_failed, (engine as ScanEngineState.Failed).message),
                    tone = NoticeTone.ERROR,
                )
                if (debuggable) SecondaryButton(stringResource(R.string.scan_open_lab), onOpenLab)
            }
            engine is ScanEngineState.NoLanguages -> ScanMessage {
                StatusNotice(stringResource(R.string.scan_no_languages), tone = NoticeTone.WARNING)
                val broken = (engine as ScanEngineState.NoLanguages).broken
                if (broken.isNotEmpty()) {
                    StatusNotice(
                        stringResource(R.string.scan_broken_languages, broken.sorted().joinToString { languageName(it) }),
                        tone = NoticeTone.ERROR,
                    )
                }
                PrimaryButton(stringResource(R.string.scan_open_languages), onOpenLanguages)
                if (debuggable) SecondaryButton(stringResource(R.string.scan_open_lab), onOpenLab)
            }
            !granted -> ScanMessage {
                if (denied) {
                    StatusNotice(stringResource(R.string.scan_camera_denied), tone = NoticeTone.WARNING)
                } else {
                    StatusNotice(stringResource(R.string.scan_camera_rationale_live))
                }
                PrimaryButton(
                    stringResource(if (denied) R.string.scan_camera_ask_again else R.string.scan_camera_allow),
                    onClick = { permission.launch(Manifest.permission.CAMERA) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (denied) {
                    SecondaryButton(
                        stringResource(R.string.scan_camera_settings),
                        onClick = { context.startActivity(appSettings(context)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            else -> LiveScan(viewModel, engine, onOpenReview, onOpenLab.takeIf { debuggable }, onOpenCollections, onOpenSet)
        }
    }
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun appSettings(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** A full-screen state instead of the camera: title, notices and actions, scrollable for large text. */
@Composable
private fun ScanMessage(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Spacing.gutter, vertical = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            stringResource(R.string.scan_title),
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        content()
    }
}

/** Why the camera can't show a picture, if it can't; null while it works. */
private enum class CameraProblem { NONE_FOUND, IN_USE, DISABLED, DO_NOT_DISTURB, FAILED }

@Composable
private fun LiveScan(
    viewModel: ScanViewModel,
    engine: ScanEngineState,
    onOpenReview: () -> Unit,
    onOpenLab: (() -> Unit)?,
    onOpenCollections: () -> Unit,
    onOpenSet: (setId: String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewfinder by viewModel.viewfinder.collectAsStateWithLifecycle()
    val paused by viewModel.paused.collectAsStateWithLifecycle()
    val tray by viewModel.tray.collectAsStateWithLifecycle()
    val priceSource by viewModel.priceSource.collectAsStateWithLifecycle()
    var torch by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<CameraProblem?>(null) }
    // The hit on screen, once its copy is saved: its price and whether it was wanted (#162).
    val hit = hitFor(tray.latest, viewfinder.decision)
    val halo = valueHalo(hit, viewfinder.hint, priceSource)

    val controller = remember {
        val fourThree = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            previewResolutionSelector = ResolutionSelector.Builder().setAspectRatioStrategy(fourThree).build()
            imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(fourThree)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                )
                .build()
            // While a frame is analysed newer ones replace each other: the decision is always about the card
            // in view now, never about a backlog.
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            imageAnalysisOutputImageFormat = ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
            setImageAnalysisAnalyzer(viewModel.worker, viewModel::analyze)
            // A tap refocuses on the guide's centre, not where the finger lands; the zoom is set for the guide (#197).
            isTapToFocusEnabled = false
            isPinchToZoomEnabled = false
        }
    }
    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }
    val cameraInfo = CameraProblemWatcher(controller, lifecycleOwner) { problem = it }
    GuideCamera(controller, cameraInfo, lifecycleOwner)
    var focusTaps by remember { mutableIntStateOf(0) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The preview keeps at least about half the screen; a tall tray (large text) scrolls instead.
        val trayMax = maxHeight * 0.5f
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(LumifoilTheme.colors.camera.background)) {
                AndroidView(
                    factory = {
                        PreviewView(it).apply {
                            // TextureView: a SurfaceView would draw outside the clipped box over the tray.
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            // Fit: the whole analysed frame is visible, so guide and outline line up with it.
                            scaleType = PreviewView.ScaleType.FIT_CENTER
                            this.controller = controller
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (viewfinder.frameWidth > 0) {
                    FrameOverlay(
                        frameWidth = viewfinder.frameWidth,
                        frameHeight = viewfinder.frameHeight,
                        guide = ScanGuide.forFrame(viewfinder.frameWidth, viewfinder.frameHeight).box,
                        // A paused camera shows no live outline: nothing is being read.
                        outline = viewfinder.outline.takeIf { !paused },
                        outlineColor = outlineColor(viewfinder.hint, LumifoilTheme.colors.camera),
                        halo = { halo.value },
                    )
                }
                // Over the picture, under the buttons: a tap anywhere refocuses on the guide (#197).
                Box(
                    Modifier.fillMaxSize().pointerInput(controller) {
                        detectTapGestures {
                            controller.focusOnGuide()
                            focusTaps++
                        }
                    },
                )
                FocusCue(focusTaps, Modifier.align(Alignment.Center))
                LiveStatus(paused, Modifier.align(Alignment.TopStart).padding(Spacing.md))
                CameraReading(
                    engine,
                    viewfinder,
                    hit = hit,
                    priceSource = priceSource,
                    paused = paused,
                    problem = problem,
                    onResume = { viewModel.setPaused(false) },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp, start = Spacing.lg, end = Spacing.lg),
                )
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(Spacing.md),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    CameraButton(
                        stringResource(if (paused) R.string.scan_resume else R.string.scan_pause),
                        icon = if (paused) R.drawable.ic_play else R.drawable.ic_pause,
                        onClick = { viewModel.setPaused(!paused) },
                    )
                    CameraButton(
                        stringResource(if (torch) R.string.scan_light_off else R.string.scan_light),
                        onClick = { torch = !torch; controller.enableTorch(torch) },
                    )
                    if (onOpenLab != null) CameraButton(stringResource(R.string.scan_open_lab), onClick = onOpenLab)
                }
            }
            Tray(viewModel, onOpenReview, onOpenCollections, onOpenSet, Modifier.heightIn(max = trayMax))
        }
    }
}

/**
 * The brief halo of a valuable hit (#168): in, a short hold, out, about 1.2 s, once per decision. It never holds up
 * anything: the next decision or the card leaving the frame stops it at once, and a card that moves and settles again
 * doesn't replay it. A price that arrives after the card was added still plays it, while the card is in view. With
 * animations off there is no halo; the hint text and the haptic carry the reveal.
 */
@Composable
private fun valueHalo(hit: LastScan.Added?, hint: ScanHint, priceSource: PriceSource): Animatable<Float, *> {
    val reducedMotion = rememberReducedMotion()
    val halo = remember { Animatable(0f) }
    var played by remember { mutableLongStateOf(0L) }
    val decision = hit?.decision?.takeIf { showsValueHalo(hit, hint, priceSource, reducedMotion) }
    LaunchedEffect(decision) {
        if (decision == null || decision == played) {
            halo.snapTo(0f)
            return@LaunchedEffect
        }
        played = decision
        halo.snapTo(0f)
        halo.animateTo(1f, tween(HALO_IN_MS))
        halo.animateTo(0f, tween(HALO_OUT_MS, delayMillis = HALO_HOLD_MS))
    }
    return halo
}

private const val HALO_IN_MS = 150
private const val HALO_HOLD_MS = 450
private const val HALO_OUT_MS = 600

/** The gap between the two pulses of a valuable hit's haptic: one felt rhythm, not two separate buzzes. */
private const val DOUBLE_PULSE_GAP_MS = 110L

/**
 * Reports camera errors (another app holds the camera, a policy or privacy switch turned it off, no camera, a
 * failure) from CameraX's camera state; null again once the camera opens.
 */
@Composable
private fun CameraProblemWatcher(
    controller: LifecycleCameraController,
    lifecycleOwner: LifecycleOwner,
    onProblem: (CameraProblem?) -> Unit,
): CameraInfo? {
    val context = LocalContext.current
    var cameraInfo by remember { mutableStateOf<CameraInfo?>(null) }
    LaunchedEffect(controller) {
        val ready = suspendCancellableCoroutine { cont ->
            val future = controller.initializationFuture
            future.addListener({ cont.resume(runCatching { future.get() }.isSuccess) }, ContextCompat.getMainExecutor(context))
        }
        if (!ready) {
            onProblem(CameraProblem.FAILED)
            return@LaunchedEffect
        }
        if (!controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
            onProblem(CameraProblem.NONE_FOUND)
            return@LaunchedEffect
        }
        // The camera info appears once the controller has bound the camera.
        repeat(50) {
            controller.cameraInfo?.let { cameraInfo = it; return@LaunchedEffect }
            delay(100)
        }
    }
    val info = cameraInfo
    DisposableEffect(info, lifecycleOwner) {
        if (info == null) return@DisposableEffect onDispose {}
        val observer = Observer<CameraState> { state -> onProblem(state.error?.let { problemOf(it.code) }) }
        info.cameraState.observe(lifecycleOwner, observer)
        onDispose { info.cameraState.removeObserver(observer) }
    }
    return info
}

/**
 * A ring at the guide's centre after a tap (#197): the camera heard it and is focusing there. Fades out; with
 * reduced motion it shows briefly and goes.
 */
@Composable
private fun FocusCue(taps: Int, modifier: Modifier) {
    val alpha = remember { Animatable(0f) }
    val reducedMotion = rememberReducedMotion()
    LaunchedEffect(taps) {
        if (taps == 0) return@LaunchedEffect
        alpha.snapTo(1f)
        if (reducedMotion) {
            delay(FOCUS_CUE_MS.toLong())
            alpha.snapTo(0f)
        } else {
            alpha.animateTo(0f, tween(FOCUS_CUE_MS, delayMillis = FOCUS_CUE_HOLD_MS))
        }
    }
    val color = LumifoilTheme.colors.camera.content
    Canvas(modifier.size(64.dp)) {
        drawCircle(color.copy(alpha = alpha.value), style = Stroke(2.dp.toPx()))
    }
}

private const val FOCUS_CUE_HOLD_MS = 300
private const val FOCUS_CUE_MS = 600

private fun problemOf(code: Int): CameraProblem = when (code) {
    CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE -> CameraProblem.IN_USE
    CameraState.ERROR_CAMERA_DISABLED -> CameraProblem.DISABLED
    CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> CameraProblem.DO_NOT_DISTURB
    else -> CameraProblem.FAILED
}

/**
 * "Live · on device" or "Paused", top left over the camera. The dot repeats what the text says. Dark scrim and a faint
 * outline, so it reads on a bright photo and on the black letterbox alike.
 */
@Composable
private fun LiveStatus(paused: Boolean, modifier: Modifier) {
    val camera = LumifoilTheme.colors.camera
    Row(
        modifier
            .background(camera.scrim, CircleShape)
            .border(1.dp, camera.faint, CircleShape)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs + Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Box(Modifier.size(8.dp).background(if (paused) camera.muted else camera.live, CircleShape))
        Text(
            stringResource(if (paused) R.string.scan_paused else R.string.scan_live),
            color = camera.content,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * What the camera sees: the card's collector number when it is read (the strongest type over the camera), or a short
 * line about what to do. Paused and camera errors show as a notice with what to do next.
 */
@Composable
private fun CameraReading(
    engine: ScanEngineState,
    viewfinder: Viewfinder,
    hit: LastScan.Added?,
    priceSource: PriceSource,
    paused: Boolean,
    problem: CameraProblem?,
    onResume: () -> Unit,
    modifier: Modifier,
) {
    val camera = LumifoilTheme.colors.camera
    // Announced by TalkBack as it changes, without taking focus from the camera controls.
    Column(
        modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        val card = viewfinder.card
        val identified = viewfinder.hint == ScanHint.READING || viewfinder.hint == ScanHint.DONE || viewfinder.hint == ScanHint.QUEUED
        when {
            problem != null -> StatusNotice(stringResource(problemText(problem)), tone = NoticeTone.ERROR)
            paused -> StatusNotice(
                stringResource(R.string.scan_paused_notice),
                actionLabel = stringResource(R.string.scan_resume),
                onAction = onResume,
            )
            engine is ScanEngineState.Loading -> HintPill(stringResource(R.string.scan_loading))
            card != null && identified -> {
                CollectorNumber(
                    localId = card.localId,
                    printedTotal = card.official,
                    style = MaterialTheme.typography.displayMedium,
                    color = camera.content,
                    totalColor = camera.muted,
                )
                Text(
                    stringResource(R.string.scan_card_line, card.name, card.setName, languageName(card.lang)),
                    color = camera.content,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val done = hit?.takeIf { viewfinder.hint == ScanHint.DONE }
                // Said in words too, so the reveal doesn't depend on the halo or the haptic (#168).
                val valuable = done?.isValuable(priceSource) == true
                val threshold = HitReveal.threshold(priceSource).let { Money.formatWhole(it.amountMinor, it.currency) }
                // Its price under the name, quiet type: the number stays the result (#162, ADR 0014).
                done?.ownQuote(priceSource)?.let { quote ->
                    Text(
                        stringResource(priceLine(quote.source), Money.format(quote.amountMinor, quote.currency)),
                        color = camera.content,
                        style = MaterialTheme.typography.titleSmall,
                        textAlign = TextAlign.Center,
                    )
                }
                HintPill(
                    when {
                        done?.wishlistFound == true && valuable -> stringResource(R.string.scan_hint_wishlist_valuable, threshold)
                        done?.wishlistFound == true -> stringResource(R.string.scan_hint_wishlist)
                        valuable -> stringResource(R.string.scan_hint_valuable, threshold)
                        viewfinder.hint == ScanHint.DONE -> stringResource(R.string.scan_hint_added)
                        viewfinder.hint == ScanHint.QUEUED -> stringResource(R.string.scan_hint_queued)
                        else -> stringResource(R.string.scan_hint_reading)
                    },
                    dot = outlineColor(viewfinder.hint, camera),
                )
            }
            else -> HintPill(
                stringResource(
                    when (viewfinder.hint) {
                        ScanHint.HOLD_STILL -> R.string.scan_hint_hold_still
                        ScanHint.UNKNOWN -> R.string.scan_hint_unknown
                        else -> R.string.scan_hint_looking
                    },
                ),
            )
        }
    }
}

/** "€8.40 · Cardmarket trend": the hit's price with its market, as the card screen names it. */
private fun priceLine(source: PriceSource): Int = when (source) {
    PriceSource.CARDMARKET -> R.string.scan_price_cardmarket
    PriceSource.TCGPLAYER -> R.string.scan_price_tcgplayer
}

private fun problemText(problem: CameraProblem): Int = when (problem) {
    CameraProblem.NONE_FOUND -> R.string.scan_camera_none
    CameraProblem.IN_USE -> R.string.scan_camera_in_use
    CameraProblem.DISABLED -> R.string.scan_camera_disabled
    CameraProblem.DO_NOT_DISTURB -> R.string.scan_camera_dnd
    CameraProblem.FAILED -> R.string.scan_camera_failed
}

/** A line of text on the camera scrim, so it stays readable on a bright photo; [dot] marks the reading state. */
@Composable
private fun HintPill(text: String, dot: Color? = null) {
    val camera = LumifoilTheme.colors.camera
    Row(
        Modifier.background(camera.scrim, CircleShape).border(1.dp, camera.faint, CircleShape).padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        if (dot != null) Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(text, color = camera.content, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

/**
 * A control over the camera: light text on the dark camera scrim with a faint outline (visible on the black
 * letterbox too), 48 dp high, with the focus ring.
 */
@Composable
private fun CameraButton(text: String, onClick: () -> Unit, icon: Int? = null) {
    val camera = LumifoilTheme.colors.camera
    val shape = CircleShape
    Button(
        onClick = onClick,
        modifier = Modifier.focusRing(shape).heightIn(min = Spacing.touchTarget),
        shape = shape,
        colors = ButtonDefaults.buttonColors(containerColor = camera.scrim, contentColor = camera.content),
        border = BorderStroke(1.dp, camera.faint),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
    ) {
        if (icon != null) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(text)
    }
}

/** The latest card first (its collector number is the strongest type here), then the count and the review queue. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Tray(
    viewModel: ScanViewModel,
    onOpenReview: () -> Unit,
    onOpenCollections: () -> Unit,
    onOpenSet: (setId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tray by viewModel.tray.collectAsStateWithLifecycle()
    val toReview by viewModel.reviewCount.collectAsStateWithLifecycle()
    val priceSource by viewModel.priceSource.collectAsStateWithLifecycle()
    val introSeen by viewModel.introSeen.collectAsStateWithLifecycle()
    val firstGoal by viewModel.firstGoal.collectAsStateWithLifecycle()
    val milestone by viewModel.milestone.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    // Only a new decision buzzes (or a price that makes the hit on screen valuable); Undo and coming back to the screen
    // don't. Each kind of hit has its own feel, so the hand can tell them apart without looking (#161, #162, #168).
    // Read only in the effect, so keeping it here recomposes nothing.
    var felt by remember { mutableStateOf(tray) }
    LaunchedEffect(tray) {
        val onScreen = hitFor(tray.latest, viewModel.viewfinder.value.decision) != null
        val feel = hapticBetween(felt, tray, priceSource, hitOnScreen = onScreen)
        felt = tray
        when (feel) {
            null -> Unit
            HitFeel.QUEUED -> haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            HitFeel.WISHLIST -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            HitFeel.ADDED -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            // Two quick confirms. The next change of the tray cancels the second one rather than waiting for it.
            HitFeel.VALUABLE -> {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                delay(DOUBLE_PULSE_GAP_MS)
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            }
        }
    }
    // A set milestone (#169) gets its own, stronger feel, once per milestone.
    val milestoneNow by viewModel.milestone.collectAsStateWithLifecycle()
    var feltMilestone by remember { mutableStateOf(milestoneNow) }
    LaunchedEffect(milestoneNow) {
        if (milestoneNow != null && milestoneNow != feltMilestone) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        feltMilestone = milestoneNow
    }
    var editing by remember { mutableStateOf<LastScan.Added?>(null) }
    val target by viewModel.target.collectAsStateWithLifecycle()
    var choosingTarget by remember { mutableStateOf(false) }
    var showingReceipt by remember { mutableStateOf(false) }

    val sheetShape = MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))
    Surface(modifier, color = MaterialTheme.colorScheme.surface, shape = sheetShape) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = Spacing.gutter, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            LatestScan(tray, introSeen, onUndo = viewModel::undo, onChange = { editing = it })
            firstGoal?.let { FirstGoalCard(it, onOpenCollections) }
            milestone?.let { MilestoneCard(it, onOpenSet = { onOpenSet(it.set.id) }) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Column(Modifier.weight(1f)) {
                    Text(
                        sessionCount(tray),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    sessionValue(tray.value(priceSource))?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                ReviewQueueButton(toReview, onOpenReview)
            }
            ScanTargetChip(target?.name, onClick = { choosingTarget = true })
            // The session receipt (#163), once something was added.
            if (tray.added > 0) TextAction(stringResource(R.string.scan_receipt_open), onClick = { showingReceipt = true })
        }
    }

    if (showingReceipt) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { showingReceipt = false }, sheetState = sheet) {
            val receipt by viewModel.receipt.collectAsStateWithLifecycle()
            val current = receipt
            when {
                current == null -> Unit
                current.isEmpty -> Text(
                    stringResource(R.string.receipt_empty),
                    Modifier.padding(horizontal = Spacing.gutter).padding(bottom = Spacing.xl),
                    style = MaterialTheme.typography.bodyMedium,
                )
                else -> SessionReceiptSheet(current, today = LocalDate.now(), onDone = { showingReceipt = false })
            }
        }
    }

    if (choosingTarget) {
        val collections by viewModel.collections.collectAsStateWithLifecycle()
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { choosingTarget = false }, sheetState = sheet) {
            ScanTargetSheet(
                collections = collections,
                chosenId = target?.id,
                onChoose = {
                    viewModel.chooseTarget(it)
                    choosingTarget = false
                },
            )
        }
    }

    val added = editing
    if (added != null) {
        val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { editing = null }, sheetState = sheet) {
            // The latest version of that copy's choices, so chips update while the sheet is open.
            val current = tray.history.filterIsInstance<LastScan.Added>().firstOrNull { it.copyId == added.copyId } ?: added
            VariantSheet(
                current,
                onChoose = { viewModel.chooseVariant(current.copyId, it) },
                onDone = { editing = null },
            )
        }
    }
}

/**
 * "€48.20" for the session's added cards in the main market (#162), with "· 2 without a EUR price" when some have
 * none there. With no price at all it says only "3 without a EUR price", never €0.00; null before anything was added.
 */
@Composable
private fun sessionValue(value: CollectionValue): String? {
    val unpriced = value.unpricedCopies.takeIf { it > 0 }?.let {
        pluralStringResource(R.plurals.scan_session_unpriced, it, it, value.currency)
    }
    // Nothing priced yet (offline, or only cards this market doesn't price): still say how many have no price.
    if (value.pricedCopies == 0) return unpriced
    val amount = Money.format(value.amountMinor, value.currency)
    return if (unpriced == null) amount else "$amount · $unpriced"
}

/**
 * A set milestone this session reached (#169): "50 % of 151 (EN)", or "151 (EN) complete" with the certificate a tap
 * away on the set screen. Calm like the first goal: a tinted card and a haptic, no confetti.
 */
@Composable
private fun MilestoneCard(milestone: Milestone, onOpenSet: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val setLabel = milestoneSetLabel(milestone)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(colors.secondaryContainer)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            if (milestone.complete) {
                stringResource(R.string.milestone_complete, setLabel)
            } else {
                stringResource(R.string.milestone_reached, milestone.level, setLabel)
            },
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface,
        )
        Text(
            stringResource(R.string.set_share_progress, milestone.progress.base, milestone.progress.printedTotal),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        TextAction(
            stringResource(if (milestone.complete) R.string.milestone_certificate else R.string.milestone_open_set),
            onClick = onOpenSet,
        )
    }
}

/** "151 (EN)": the set and the language its milestone counts. */
internal fun milestoneSetLabel(milestone: Milestone): String = "${milestone.set.name} (${milestone.language.uppercase()})"

/**
 * The first-run goal (#174): "First goal: 3 of 10 cards" with a thin bar while it runs, then what the first cards are
 * worth and the way to see them. Calm like the rest of the tray (ADR 0014): no confetti, the number is the reward.
 */
@Composable
private fun FirstGoalCard(goal: FirstGoal.State, onOpenCollections: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(colors.secondaryContainer)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        when (goal) {
            is FirstGoal.State.Progress -> {
                Text(
                    stringResource(R.string.first_goal_progress, goal.cards, FirstGoal.TARGET),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                )
                Text(stringResource(R.string.first_goal_hint), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                LinearProgressIndicator(
                    progress = { (goal.cards.toFloat() / FirstGoal.TARGET).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.xxs),
                    color = colors.primary,
                    trackColor = colors.surface,
                    drawStopIndicator = {},
                )
            }
            is FirstGoal.State.Reached -> {
                Text(
                    stringResource(R.string.first_goal_reached, FirstGoal.TARGET),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                )
                val value = goal.value
                Text(
                    if (value.pricedCopies > 0) {
                        stringResource(R.string.first_goal_value, Money.format(value.amountMinor, value.currency))
                    } else {
                        stringResource(R.string.first_goal_no_price)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                )
                TextAction(stringResource(R.string.first_goal_open), onClick = onOpenCollections)
            }
        }
    }
}

/** "37 added this session · 3 need checking" (#161): added and queued cards apart, the queued part only when any. */
@Composable
private fun sessionCount(tray: ScanTray): String {
    val added = pluralStringResource(R.plurals.scan_session_added, tray.added, tray.added)
    if (tray.queued == 0) return added
    return added + " · " + pluralStringResource(R.plurals.scan_session_queued, tray.queued, tray.queued)
}

/**
 * "Scanning into: All cards" (#139): where the next scans go, and the way to change it. A quiet outlined control
 * like the empty review button; the chosen collection's name is the one thing that stands out.
 */
@Composable
private fun ScanTargetChip(target: String?, onClick: () -> Unit) {
    val name = target ?: stringResource(R.string.collections_all_cards)
    val description = stringResource(R.string.scan_target_description, name)
    val shape = MaterialTheme.shapes.small
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.focusRing(shape).heightIn(min = Spacing.touchTarget).semantics { contentDescription = description },
        shape = shape,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurfaceVariant),
        border = BorderStroke(1.dp, if (target != null) colors.primary else colors.outlineVariant),
        contentPadding = PaddingValues(start = Spacing.md, end = Spacing.sm),
    ) {
        Text(stringResource(R.string.scan_target_label), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(Spacing.xs))
        Text(
            name,
            Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.labelLarge,
            color = if (target != null) colors.primary else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Icon(
            painterResource(R.drawable.ic_chevron_right),
            contentDescription = null,
            modifier = Modifier.padding(start = Spacing.xs).size(ButtonDefaults.IconSize),
        )
    }
}

/**
 * Where scans go besides All Cards: All cards only, or one custom collection. The choice is kept (also after a
 * restart) until the user changes it or deletes that collection.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanTargetSheet(collections: List<CollectionSummary>, chosenId: Long?, onChoose: (Long?) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            stringResource(R.string.scan_target_title),
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(if (collections.isEmpty()) R.string.scan_target_none else R.string.scan_target_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            ChoiceChip(
                selected = chosenId == null,
                onClick = { onChoose(null) },
                label = stringResource(R.string.collections_only_all_cards),
            )
            collections.forEach { collection ->
                ChoiceChip(
                    selected = collection.id == chosenId,
                    onClick = { onChoose(collection.id) },
                    label = collection.name,
                )
            }
        }
    }
}

/** The review queue's entry: tonal with a count badge while scans wait, quiet when the queue is empty. */
@Composable
private fun ReviewQueueButton(count: Int, onOpen: () -> Unit) {
    val description = if (count == 0) {
        stringResource(R.string.scan_review_empty_description)
    } else {
        pluralStringResource(R.plurals.scan_review_description, count, count)
    }
    val shape = MaterialTheme.shapes.medium
    val colors = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onOpen,
        modifier = Modifier.focusRing(shape).heightIn(min = Spacing.touchTarget).semantics { contentDescription = description },
        shape = shape,
        colors = if (count > 0) {
            ButtonDefaults.outlinedButtonColors(containerColor = colors.secondaryContainer, contentColor = colors.primary)
        } else {
            ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurfaceVariant)
        },
        border = BorderStroke(1.dp, if (count > 0) colors.primary else colors.outlineVariant),
        contentPadding = PaddingValues(start = Spacing.lg, end = if (count > 0) Spacing.md else Spacing.lg),
    ) {
        Text(stringResource(if (count > 0) R.string.scan_review else R.string.scan_nothing_to_review))
        if (count > 0) {
            Spacer(Modifier.width(Spacing.sm))
            CountBadge(count)
        }
    }
}

/**
 * The newest decision of this session: thumbnail, collector number, name, what happened, and for an added card the
 * finish, edition and condition it was saved with, marked as defaults or as the user's choice. Undo takes back this
 * decision and then shows the one before it; with nothing left, Undo is off.
 */
@Composable
private fun LatestScan(tray: ScanTray, introSeen: Boolean, onUndo: () -> Unit, onChange: (LastScan.Added) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val rowModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 72.dp)
        .background(colors.surfaceContainerLow, MaterialTheme.shapes.large)
        .padding(Spacing.md)
        // Spoken when a card is added, queued or taken back, without moving focus off the camera.
        .semantics { liveRegion = LiveRegionMode.Polite }
    val last = tray.latest
    val removed = tray.removed
    val failed = tray.failed
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (failed != null) {
            StatusNotice(stringResource(R.string.scan_save_failed, cardTitle(failed)), tone = NoticeTone.ERROR)
        }
        if (removed != null) {
            Text(
                stringResource(R.string.scan_removed, cardTitle(removed)),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        if (last == null) {
            Text(
                stringResource(
                    when {
                        removed != null -> R.string.scan_tray_nothing_left
                        // The long explanation only until the first scan ever (#162).
                        introSeen -> R.string.scan_tray_ready
                        else -> R.string.scan_tray_empty
                    },
                ),
                rowModifier,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            return
        }
        Row(rowModifier, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            AsyncImage(
                model = last.card.thumbnailUrl,
                contentDescription = null,
                modifier = Modifier.width(48.dp).aspectRatio(63f / 88f).clip(MaterialTheme.shapes.small),
                contentScale = ContentScale.Fit,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                CollectorNumber(
                    localId = last.card.localId,
                    printedTotal = last.card.official,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    last.card.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = when (last) {
                    is LastScan.Added -> stringResource(R.string.scan_added, last.card.setName, languageName(last.card.lang))
                    is LastScan.Queued -> stringResource(R.string.scan_queued, stringResource(reasonText(last.reason)))
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (last is LastScan.Added) {
                    val v = last.variant
                    val summary = stringResource(
                        if (v.chosen) R.string.scan_variant_chosen else R.string.scan_variant_default,
                        stringResource(finishLabel(v.finish)),
                        stringResource(editionLabel(v.edition)),
                        stringResource(conditionLabel(v.condition)),
                    )
                    Text(summary, style = MaterialTheme.typography.bodySmall, color = colors.onSurface)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            if (last is LastScan.Added) {
                TextAction(
                    stringResource(R.string.scan_variant_change),
                    onClick = { onChange(last) },
                    contentDescription = stringResource(R.string.scan_variant_change_description, cardTitle(last.card)),
                )
            }
            TextAction(
                stringResource(R.string.scan_undo),
                onClick = onUndo,
                enabled = tray.canUndo,
                contentDescription = stringResource(
                    if (last is LastScan.Added) R.string.scan_undo_description else R.string.scan_undo_queued_description,
                    cardTitle(last.card),
                ),
            )
        }
    }
}

/** Finish, edition and condition of the latest added copy, as the user's choice. Saved at once. */
@Composable
private fun VariantSheet(added: LastScan.Added, onChoose: (CopyVariant) -> Unit, onDone: () -> Unit) {
    val v = added.variant
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.gutter)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            cardTitle(added.card),
            Modifier.semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            stringResource(R.string.variant_not_detected),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        VariantChoices(
            finishes = finishOrder(added.card.printings),
            finish = v.finish,
            edition = v.edition,
            condition = v.condition,
            finishChosen = v.finishChosen,
            editionChosen = v.editionChosen,
            conditionChosen = v.conditionChosen,
            onFinish = { onChoose(v.withFinish(it)) },
            onEdition = { onChoose(v.withEdition(it)) },
            onCondition = { onChoose(v.withCondition(it)) },
        )
        PrimaryButton(stringResource(R.string.scan_variant_done), onDone, Modifier.fillMaxWidth().padding(top = Spacing.md))
    }
}

/** "006/165 Charizard ex" for messages and TalkBack. */
private fun cardTitle(card: RefCard): String = "${padCollectorNumber(card.localId, card.official)}/${card.official} ${card.name}"

/** Short reason for the tray ("Saved for review: …"). */
fun reasonText(reason: ReviewReason): Int = when (reason) {
    ReviewReason.NUMBER_NOT_READ -> R.string.review_reason_number_not_read
    ReviewReason.NUMBER_DIFFERS -> R.string.review_reason_number_differs
    ReviewReason.SIMILAR_CARDS -> R.string.review_reason_similar_cards
    ReviewReason.CHECK_VARIANT -> R.string.review_reason_check_variant
}

/** "German" for `de`, in the device language. */
fun languageName(lang: String): String =
    Locale.forLanguageTag(lang).getDisplayLanguage(Locale.getDefault()).ifEmpty { lang.uppercase(Locale.ROOT) }

private fun outlineColor(hint: ScanHint, camera: CameraColors): Color = when (hint) {
    ScanHint.DONE -> camera.done
    ScanHint.READING -> camera.reading
    ScanHint.QUEUED -> camera.warning
    else -> camera.faint
}
