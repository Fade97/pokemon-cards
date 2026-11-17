// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.scan

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.ui.packs.languageName
import de.fabianduerkop.cardindex.model.ReviewReason
import de.fabianduerkop.cardindex.recognition.ScanGuide
import de.fabianduerkop.cardindex.ui.components.CollectorNumber
import de.fabianduerkop.cardindex.ui.components.PlaceholderText
import de.fabianduerkop.cardindex.ui.components.padCollectorNumber
import de.fabianduerkop.cardindex.ui.theme.IndexTokens
import java.util.Locale

/**
 * Live scan (DESIGN.md, Recognition; mockup c-index "Live scan"): no shutter. A card held in the guide is added to
 * All Cards as soon as its number confirms it, unsure cards go to Review, and the camera never stops for either.
 * The tray shows the count, the review queue (opens [de.fabianduerkop.cardindex.ui.review.ReviewScreen]) and the
 * last decision with Undo.
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
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val engine by viewModel.engineState.collectAsStateWithLifecycle()
    // The lab is a test bench for the models; release builds don't offer it.
    val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        when {
            engine is ScanEngineState.Failed -> Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PlaceholderText(stringResource(R.string.scan_engine_failed, (engine as ScanEngineState.Failed).message))
                if (debuggable) OutlinedButton(onClick = onOpenLab) { Text(stringResource(R.string.scan_open_lab)) }
            }
            engine is ScanEngineState.NoLanguages -> Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PlaceholderText(stringResource(R.string.scan_no_languages))
                val broken = (engine as ScanEngineState.NoLanguages).broken
                if (broken.isNotEmpty()) {
                    PlaceholderText(stringResource(R.string.scan_broken_languages, broken.sorted().joinToString { languageName(it) }))
                }
                Button(onClick = onOpenLanguages) { Text(stringResource(R.string.scan_open_languages)) }
                if (debuggable) OutlinedButton(onClick = onOpenLab) { Text(stringResource(R.string.scan_open_lab)) }
            }
            !granted -> Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PlaceholderText(stringResource(R.string.scan_camera_rationale_live))
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.scan_camera_allow))
                }
            }
            else -> LiveScan(viewModel, engine, onOpenReview, onOpenLab.takeIf { debuggable })
        }
    }
}

@Composable
private fun LiveScan(viewModel: ScanViewModel, engine: ScanEngineState, onOpenReview: () -> Unit, onOpenLab: (() -> Unit)?) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val viewfinder by viewModel.viewfinder.collectAsStateWithLifecycle()
    val paused by viewModel.paused.collectAsStateWithLifecycle()
    var torch by remember { mutableStateOf(false) }

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

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(IndexTokens.Camera)) {
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
                    outline = viewfinder.outline,
                    outlineColor = outlineColor(viewfinder.hint),
                )
            }
            Reading(engine, viewfinder, paused, Modifier.align(Alignment.TopCenter).padding(top = 52.dp, start = 16.dp, end = 16.dp))
            AutoPill(paused, Modifier.align(Alignment.TopStart).padding(12.dp))
            Row(
                Modifier.align(Alignment.BottomCenter).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val pad = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                FilledTonalButton(onClick = { viewModel.setPaused(!paused) }, contentPadding = pad) {
                    Text(stringResource(if (paused) R.string.scan_resume else R.string.scan_pause))
                }
                FilledTonalButton(onClick = { torch = !torch; controller.enableTorch(torch) }, contentPadding = pad) {
                    Text(stringResource(if (torch) R.string.scan_light_off else R.string.scan_light))
                }
                if (onOpenLab != null) {
                    FilledTonalButton(onClick = onOpenLab, contentPadding = pad) { Text(stringResource(R.string.scan_open_lab)) }
                }
            }
        }
        Tray(viewModel, onOpenReview)
    }
}

/** The card in view as the big collector number, or what the user should do. */
@Composable
private fun Reading(engine: ScanEngineState, viewfinder: Viewfinder, paused: Boolean, modifier: Modifier) {
    val card = viewfinder.card
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (card != null && !paused && (viewfinder.hint == ScanHint.READING || viewfinder.hint == ScanHint.DONE)) {
            CollectorNumber(
                localId = card.localId,
                printedTotal = card.official,
                style = MaterialTheme.typography.displayMedium,
                color = Color.White,
                totalColor = IndexTokens.CameraMuted,
            )
            Text(
                stringResource(R.string.scan_card_line, card.name, card.setName, languageName(card.lang)),
                color = IndexTokens.CameraInk,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        } else {
            val text = when {
                engine is ScanEngineState.Loading -> stringResource(R.string.scan_loading)
                paused -> stringResource(R.string.scan_paused)
                viewfinder.hint == ScanHint.HOLD_STILL -> stringResource(R.string.scan_hint_hold_still)
                viewfinder.hint == ScanHint.UNKNOWN -> stringResource(R.string.scan_hint_unknown)
                else -> stringResource(R.string.scan_hint_looking)
            }
            Text(
                text,
                Modifier.background(Color(0x99000000), RoundedCornerShape(999.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
                color = IndexTokens.CameraInk,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun AutoPill(paused: Boolean, modifier: Modifier) {
    Row(
        modifier.background(Color(0x1FFFFFFF), RoundedCornerShape(999.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).background(if (paused) IndexTokens.CameraMuted else LiveDot, CircleShape))
        Text(
            stringResource(if (paused) R.string.scan_paused else R.string.scan_auto),
            color = IndexTokens.CameraInk,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** Count, review queue and the last decision with Undo. */
@Composable
private fun Tray(viewModel: ScanViewModel, onOpenReview: () -> Unit) {
    val scanned by viewModel.scanned.collectAsStateWithLifecycle()
    val toReview by viewModel.reviewCount.collectAsStateWithLifecycle()
    val last by viewModel.lastScan.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(last) {
        val l = last
        if (l != null && !(l is LastScan.Added && l.undone)) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    scanned.toString(),
                    style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
                )
                Text(
                    stringResource(R.string.scan_scanned),
                    Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                ReviewCount(toReview, onOpenReview)
            }
            LastScanRow(last, viewModel::undo)
        }
    }
}

/** The review queue's size; opens it. Skipped cards count too, since they stay reachable there. */
@Composable
private fun ReviewCount(count: Int, onOpen: () -> Unit) {
    val empty = count == 0
    val label = if (empty) stringResource(R.string.scan_nothing_to_review) else pluralStringResource(R.plurals.scan_to_review, count, count)
    val description = stringResource(R.string.scan_open_review)
    Button(
        onClick = onOpen,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "$label. $description" },
        colors = ButtonDefaults.buttonColors(
            containerColor = if (empty) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = if (empty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onTertiaryContainer,
        ),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun LastScanRow(last: LastScan?, onUndo: () -> Unit) {
    val rowModifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 64.dp)
        .background(MaterialTheme.colorScheme.surfaceContainerLow, MaterialTheme.shapes.medium)
        .padding(horizontal = 10.dp, vertical = 8.dp)
        // Spoken when a card is added, queued or taken back, without moving focus off the camera.
        .semantics { liveRegion = LiveRegionMode.Polite }
    if (last == null) {
        Text(
            stringResource(R.string.scan_tray_empty),
            rowModifier,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val title = "${padCollectorNumber(last.card.localId, last.card.official)}/${last.card.official} ${last.card.name}"
    if (last is LastScan.Added && last.undone) {
        Text(stringResource(R.string.scan_removed, title), rowModifier, style = MaterialTheme.typography.bodyMedium)
        return
    }
    Row(rowModifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AsyncImage(
            model = last.card.thumbnailUrl,
            contentDescription = null,
            modifier = Modifier.width(40.dp).aspectRatio(63f / 88f).clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Fit,
        )
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            val detail = when (last) {
                is LastScan.Added -> stringResource(R.string.scan_added, last.card.setName, languageName(last.card.lang))
                is LastScan.Queued -> stringResource(R.string.scan_queued, stringResource(reasonText(last.reason)))
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        if (last is LastScan.Added) {
            val description = stringResource(R.string.scan_undo_description, title)
            OutlinedButton(onClick = onUndo, modifier = Modifier.semantics { contentDescription = description }) {
                Text(stringResource(R.string.scan_undo))
            }
        }
    }
}

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

private val LiveDot = Color(0xFF62D27A)
private val DoneColor = Color(0xFF3DDC84)
private val ReadingColor = Color(0xFF7FB2FF)
private val FaintColor = Color(0x66FFFFFF)

private fun outlineColor(hint: ScanHint): Color = when (hint) {
    ScanHint.DONE -> DoneColor
    ScanHint.READING -> ReadingColor
    else -> FaintColor
}
