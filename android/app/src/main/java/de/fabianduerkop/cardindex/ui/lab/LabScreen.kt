// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.lab

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.recognition.GateResult
import de.fabianduerkop.cardindex.recognition.ScanGuide
import de.fabianduerkop.cardindex.ui.components.PlaceholderText
import de.fabianduerkop.cardindex.ui.scan.FrameOverlay
import de.fabianduerkop.cardindex.ui.theme.CameraColors
import de.fabianduerkop.cardindex.ui.theme.LumifoilTheme

/**
 * Recognition lab (debug builds only, opened from the Scan screen): live camera recognition with every
 * intermediate result, for testing the model on real cards.
 * The camera view shows the whole analysed frame with a card guide; only the guide area is searched. A card is
 * shown as identified when its similarity reaches the minimum. Candidates, timings and thresholds sit under
 * "Details". A photo (shutter or gallery) runs the same pipeline at higher resolution.
 */
@Composable
fun LabScreen(modifier: Modifier = Modifier, viewModel: LabViewModel = viewModel()) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val engine by viewModel.engineState.collectAsStateWithLifecycle()

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        when {
            engine is EngineState.Failed -> PlaceholderText(
                stringResource(R.string.scan_lab_assets_missing, (engine as EngineState.Failed).message),
                Modifier.padding(18.dp),
            )
            !granted -> Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PlaceholderText(stringResource(R.string.scan_camera_rationale))
                Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.scan_camera_allow))
                }
            }
            else -> LabContent(viewModel, engine)
        }
    }
}

@Composable
private fun LabContent(viewModel: LabViewModel, engine: EngineState) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val live by viewModel.live.collectAsStateWithLifecycle()
    val still by viewModel.still.collectAsStateWithLifecycle()
    val paused by viewModel.paused.collectAsStateWithLifecycle()
    val minSimilarity by viewModel.minSimilarity.collectAsStateWithLifecycle()
    var torch by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }

    val controller = remember {
        val fourThree = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS or CameraController.IMAGE_CAPTURE)
            previewResolutionSelector = ResolutionSelector.Builder().setAspectRatioStrategy(fourThree).build()
            imageAnalysisResolutionSelector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(fourThree)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                )
                .build()
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
            imageAnalysisOutputImageFormat = ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
            imageCaptureMode = ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
            setImageAnalysisAnalyzer(viewModel.worker, viewModel::analyze)
        }
    }
    DisposableEffect(lifecycleOwner) {
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.unbind() }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.recognizeUri(uri)
    }

    Column(Modifier.fillMaxSize()) {
        // Camera or photo: the whole 4:3 frame is visible (fit), so the guide and outlines match what is analysed.
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().background(LumifoilTheme.colors.camera.background)) {
            val s = still
            if (s == null) {
                AndroidView(
                    factory = {
                        PreviewView(it).apply {
                            // TextureView: a SurfaceView would draw outside the clipped box over the result panel.
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FIT_CENTER
                            this.controller = controller
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (live.frameWidth > 0) {
                    FrameOverlay(
                        frameWidth = live.frameWidth,
                        frameHeight = live.frameHeight,
                        guide = ScanGuide.forFrame(live.frameWidth, live.frameHeight).box,
                        outline = live.outline?.quad,
                        outlineColor = outlineColor(live, LumifoilTheme.colors.camera),
                    )
                }
            } else {
                Image(s.photo.asImageBitmap(), contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                s.result?.let {
                    FrameOverlay(
                        frameWidth = it.frameWidth,
                        frameHeight = it.frameHeight,
                        guide = if (s.usedGuide) ScanGuide.forFrame(it.frameWidth, it.frameHeight).box else null,
                        outline = it.quad,
                        outlineColor = when {
                            !it.found -> LumifoilTheme.colors.camera.warning
                            it.best.similarity >= minSimilarity -> LumifoilTheme.colors.camera.done
                            else -> LumifoilTheme.colors.camera.faint
                        },
                    )
                }
            }
            StatusLine(engine, live, still, paused, minSimilarity, Modifier.align(Alignment.TopStart).padding(8.dp))
            CameraControls(
                still = still != null,
                paused = paused,
                torch = torch,
                onCapture = {
                    controller.takePicture(viewModel.worker, object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) = viewModel.onCaptured(image)
                        override fun onError(exception: ImageCaptureException) =
                            viewModel.onCaptureFailed(exception.message ?: "capture failed")
                    })
                },
                onPause = { viewModel.setPaused(!paused) },
                onTorch = { torch = !torch; controller.enableTorch(torch); viewModel.setTorch(torch) },
                onGallery = { pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onBack = viewModel::backToLive,
                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
            )
        }
        // Result below the camera; "Details" adds candidates, timings and the lab settings (scrollable).
        Column(
            Modifier.fillMaxWidth().heightIn(max = if (details) 380.dp else 150.dp).verticalScroll(rememberScrollState()),
        ) {
            val result = still?.result ?: if (still == null) live.result else null
            val identified = result != null && result.best.similarity >= minSimilarity
            ResultPanel(
                result = result,
                identified = identified,
                stable = if (still != null) identified else live.stable,
                outdated = still == null && live.outdated,
                details = details,
                onToggleDetails = { details = !details },
                message = still?.error ?: still?.savedTo?.let { stringResource(R.string.scan_lab_saved, it) },
            )
            if (details) LabSettings(viewModel)
        }
    }
}

@Composable
private fun CameraControls(
    still: Boolean,
    paused: Boolean,
    torch: Boolean,
    onCapture: () -> Unit,
    onPause: () -> Unit,
    onTorch: () -> Unit,
    onGallery: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    val pad = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (still) {
            Button(onClick = onBack, contentPadding = pad) { Text(stringResource(R.string.scan_lab_back_to_live), maxLines = 1) }
        } else {
            FilledTonalButton(onClick = onPause, contentPadding = pad) {
                Text(stringResource(if (paused) R.string.scan_lab_resume else R.string.scan_lab_pause), maxLines = 1)
            }
            Button(onClick = onCapture, contentPadding = pad) { Text(stringResource(R.string.scan_lab_capture), maxLines = 1) }
            FilledTonalButton(onClick = onTorch, contentPadding = pad) {
                Text(stringResource(if (torch) R.string.scan_lab_torch_off else R.string.scan_lab_torch), maxLines = 1)
            }
        }
        FilledTonalButton(onClick = onGallery, contentPadding = pad) { Text(stringResource(R.string.scan_lab_gallery), maxLines = 1) }
    }
}

@Composable
private fun StatusLine(
    engine: EngineState,
    live: LiveState,
    still: StillState?,
    paused: Boolean,
    minSimilarity: Float,
    modifier: Modifier,
) {
    val gate = live.gate
    val text = when {
        engine is EngineState.Loading -> stringResource(R.string.scan_lab_loading)
        still != null && still.result == null && still.error == null -> stringResource(R.string.scan_lab_working)
        still != null -> stringResource(R.string.scan_lab_photo)
        paused -> stringResource(R.string.scan_lab_paused)
        gate is GateResult.NoOutline -> stringResource(R.string.scan_lab_gate_none)
        gate is GateResult.LowScore -> stringResource(R.string.scan_lab_gate_low, gate.score)
        gate is GateResult.BadShape -> stringResource(R.string.scan_lab_gate_shape)
        gate is GateResult.Moving -> stringResource(R.string.scan_lab_gate_moving)
        live.noMatchSimilarity != null -> stringResource(R.string.scan_lab_no_match, live.noMatchSimilarity, minSimilarity)
        engine is EngineState.Ready -> stringResource(
            R.string.scan_lab_status, engine.cards, engine.unseenCards, live.framesPerSecond,
        )
        else -> ""
    }
    if (text.isNotEmpty()) {
        Text(
            text,
            modifier.background(LumifoilTheme.colors.camera.scrim, MaterialTheme.shapes.small).padding(horizontal = 8.dp, vertical = 4.dp),
            color = LumifoilTheme.colors.camera.content,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}


/** Green: identified and stable; blue: identified; faint white: outline rejected or match too weak. */
private fun outlineColor(live: LiveState, camera: CameraColors): Color = when {
    live.outdated -> camera.faint
    live.stable -> camera.done
    else -> camera.reading
}
