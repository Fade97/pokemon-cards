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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import coil3.compose.AsyncImage
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.model.ReviewReason
import de.fabianduerkop.cardindex.recognition.RefCard
import de.fabianduerkop.cardindex.recognition.ScanGuide
import de.fabianduerkop.cardindex.ui.components.CollectorNumber
import de.fabianduerkop.cardindex.ui.components.CountBadge
import de.fabianduerkop.cardindex.ui.components.NoticeTone
import de.fabianduerkop.cardindex.ui.components.PrimaryButton
import de.fabianduerkop.cardindex.ui.components.SecondaryButton
import de.fabianduerkop.cardindex.ui.components.StatusNotice
import de.fabianduerkop.cardindex.ui.components.TextAction
import de.fabianduerkop.cardindex.ui.components.focusRing
import de.fabianduerkop.cardindex.ui.components.padCollectorNumber
import de.fabianduerkop.cardindex.ui.review.VariantChoices
import de.fabianduerkop.cardindex.ui.review.conditionLabel
import de.fabianduerkop.cardindex.ui.review.editionLabel
import de.fabianduerkop.cardindex.ui.review.finishLabel
import de.fabianduerkop.cardindex.ui.theme.CameraColors
import de.fabianduerkop.cardindex.ui.theme.LumifoilTheme
import de.fabianduerkop.cardindex.ui.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Live scan (DESIGN.md, Recognition; Pearl scanner in `design/mockups/lumifoil`, #98): no shutter. A card held in
 * the guide is added to All Cards as soon as its number confirms it, unsure cards go to Review, and the camera never
 * stops for either. The camera area says what is happening (searching, reading, added, saved for review, not
 * recognized, paused, camera error); the tray below shows the count, the review queue and the latest card with its
 * finish, edition and condition as the user's choice, plus Undo.
 */
@Composable
fun ScanScreen(
    modifier: Modifier = Modifier,
    onOpenReview: () -> Unit = {},
    onOpenLab: () -> Unit = {},
    onOpenLanguages: () -> Unit = {},
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
            else -> LiveScan(viewModel, engine, onOpenReview, onOpenLab.takeIf { debuggable })
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
private fun LiveScan(viewModel: ScanViewModel, engine: ScanEngineState, onOpenReview: () -> Unit, onOpenLab: (() -> Unit)?) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewfinder by viewModel.viewfinder.collectAsStateWithLifecycle()
    val paused by viewModel.paused.collectAsStateWithLifecycle()
    var torch by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<CameraProblem?>(null) }

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
        }
    }
    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }
    CameraProblemWatcher(controller, lifecycleOwner) { problem = it }

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
                    )
                }
                LiveStatus(paused, Modifier.align(Alignment.TopStart).padding(Spacing.md))
                CameraReading(
                    engine,
                    viewfinder,
                    paused,
                    problem,
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
            Tray(viewModel, onOpenReview, Modifier.heightIn(max = trayMax))
        }
    }
}

/**
 * Reports camera errors (another app holds the camera, a policy or privacy switch turned it off, no camera, a
 * failure) from CameraX's camera state; null again once the camera opens.
 */
@Composable
private fun CameraProblemWatcher(
    controller: LifecycleCameraController,
    lifecycleOwner: LifecycleOwner,
    onProblem: (CameraProblem?) -> Unit,
) {
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
}

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
                HintPill(
                    stringResource(
                        when (viewfinder.hint) {
                            ScanHint.DONE -> R.string.scan_hint_added
                            ScanHint.QUEUED -> R.string.scan_hint_queued
                            else -> R.string.scan_hint_reading
                        },
                    ),
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
private fun Tray(viewModel: ScanViewModel, onOpenReview: () -> Unit, modifier: Modifier = Modifier) {
    val tray by viewModel.tray.collectAsStateWithLifecycle()
    val toReview by viewModel.reviewCount.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    // Only a new decision buzzes; Undo and coming back to the screen don't.
    var felt by remember { mutableLongStateOf(tray.decisions) }
    LaunchedEffect(tray.decisions) {
        if (tray.decisions > felt) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        felt = tray.decisions
    }
    var editing by remember { mutableStateOf<LastScan.Added?>(null) }

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
            LatestScan(tray, onUndo = viewModel::undo, onChange = { editing = it })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    pluralStringResource(R.plurals.scan_scanned_count, tray.scanned, tray.scanned),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ReviewQueueButton(toReview, onOpenReview)
            }
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
private fun LatestScan(tray: ScanTray, onUndo: () -> Unit, onChange: (LastScan.Added) -> Unit) {
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
                stringResource(if (removed != null) R.string.scan_tray_nothing_left else R.string.scan_tray_empty),
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
