// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.foundation.focusable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.ui.theme.LumifoilTheme
import de.fabianduerkop.cardindex.ui.theme.Spacing
import kotlin.math.abs
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One image in the [CardViewer] (#166): the official card art or one of the user's own photos of a copy.
 *
 * @property model what Coil loads: a CDN URL or a photo [java.io.File].
 * @property label the short name in the viewer's bar, e.g. "Card image" or "Your photo, 1× Holo, NM".
 * @property description what TalkBack says for the image.
 * @property key stable per image, so the pager keeps each page's zoom while paging.
 * @property foil the simulated light drawn over this image (#167, ADR 0015); only for the official art of a print the
 *   user owns as Holo or Reverse Holo, never for own photos.
 */
@Immutable
data class CardViewerImage(
    val model: Any,
    val label: String,
    val description: String,
    val key: String = model.toString(),
    /** The user's own photo, stored on the phone: when it fails, a connection is not the reason. */
    val ownPhoto: Boolean = false,
    val foil: FoilFinish? = null,
)

/** Side and top/bottom room around the fitted card, so it never sits under the bar or the gesture handle. */
private val ImageGutterHorizontal = Spacing.lg
private val ImageGutterVertical = 72.dp

/** A downward fling faster than this closes the viewer even when the drag was short. */
private val DismissFlingVelocity = 1200.dp

/**
 * The full-screen card viewer (#166): the card alone on the dark camera surface, nothing else on screen but a close
 * button and the image's name. Pinch or double-tap to zoom, drag to pan while zoomed, swipe sideways between
 * [images] (official art, own photos), swipe down to close. Back and the close button close it too.
 *
 * With reduced motion ([rememberReducedMotion]) the double-tap zoom, the spring back after a short swipe and the
 * slide away all snap; what follows the finger still follows it.
 *
 * Images with a [CardViewerImage.foil] get the tilt showcase (#167, ADR 0015): a simulated light over the card, inside
 * its zoom, moved by the gyroscope or, without one, by touching and holding the card. The bar says the light is
 * simulated. With reduced motion the light stays still at rest and no sensor runs. The chrome never moves.
 */
@Composable
fun CardViewer(
    images: List<CardViewerImage>,
    onDismiss: () -> Unit,
    initialPage: Int = 0,
) {
    if (images.isEmpty()) return
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        LightSystemBarIcons()
        val reducedMotion = rememberReducedMotion()
        val foilTilt = rememberFoilTilt(enabled = !reducedMotion && images.any { it.foil != null })
        val camera = LumifoilTheme.colors.camera
        val scope = rememberCoroutineScope()
        val pager = rememberPagerState(initialPage.coerceIn(images.indices)) { images.size }
        var zoomedPage by remember { mutableStateOf<Int?>(null) }
        var dragY by remember { mutableFloatStateOf(0f) }
        // The running spring-back or slide-away; a new drag takes the card from it instead of fighting it.
        var dismissAnimation by remember { mutableStateOf<Job?>(null) }
        val title = stringResource(R.string.viewer_title)
        BoxWithConstraints(Modifier.fillMaxSize().semantics { paneTitle = title }) {
            val height = constraints.maxHeight.toFloat()
            val flingVelocity = with(LocalDensity.current) { DismissFlingVelocity.toPx() }
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = ViewerZoom.backdropAlpha(dragY, height) }
                    .background(camera.background),
            )
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = zoomedPage == null,
                key = { images[it].key },
            ) { page ->
                ZoomablePage(
                    image = images[page],
                    reducedMotion = reducedMotion,
                    dragY = { dragY },
                    onZoomedChange = { zoomed ->
                        zoomedPage = if (zoomed) page else zoomedPage.takeUnless { it == page }
                    },
                    onDismissDrag = { delta ->
                        dismissAnimation?.cancel()
                        dragY = (dragY + delta).coerceAtLeast(0f)
                    },
                    onDismissEnd = { velocity ->
                        // A cancelled swipe (null) always puts the card back, however far it went.
                        val close = velocity != null && ViewerZoom.shouldDismiss(dragY, velocity, height, flingVelocity)
                        dismissAnimation?.cancel()
                        when {
                            close && reducedMotion -> onDismiss()
                            close -> dismissAnimation = scope.launch {
                                animate(dragY, height, animationSpec = tween(SlideAwayMillis)) { value, _ -> dragY = value }
                                onDismiss()
                            }
                            reducedMotion -> dragY = 0f
                            else -> dismissAnimation = scope.launch {
                                animate(dragY, 0f, animationSpec = spring()) { value, _ -> dragY = value }
                            }
                        }
                    },
                    foilTilt = foilTilt,
                )
            }
            val current = images[pager.currentPage]
            ViewerBar(
                label = current.label,
                position = if (images.size > 1) stringResource(R.string.viewer_position, pager.currentPage + 1, images.size) else null,
                foilNote = current.foil?.let { foil ->
                    stringResource(if (foil == FoilFinish.HOLO) R.string.viewer_foil_holo else R.string.viewer_foil_reverse_holo)
                },
                foilHint = if (current.foil != null && foilTilt.touchControlled) stringResource(R.string.viewer_foil_touch) else null,
                onClose = onDismiss,
                modifier = Modifier.align(Alignment.TopStart),
            )
        }
    }
}

private const val SlideAwayMillis = 160
private const val DoubleTapZoomMillis = 220

/**
 * The close button and the name of the shown image, on the dark surface; never animated (ADR 0014). [foilNote] says
 * that the light over the card is simulated (ADR 0015), so a screenshot or recording carries it too; [foilHint] says
 * how to move it by touch.
 */
@Composable
private fun ViewerBar(
    label: String,
    position: String?,
    foilNote: String?,
    foilHint: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val camera = LumifoilTheme.colors.camera
    Row(
        modifier
            .fillMaxWidth()
            // A dark scrim under the bar and the status bar: zoomed art passes behind them, and Close and the foil
            // note must keep their contrast over a bright card (#250).
            // At least 85 % behind the whole bar, fading only in the padding under it.
            .drawBehind {
                val fadeFrom = ((size.height - ScrimFade.toPx()) / size.height).coerceIn(0f, 1f)
                drawRect(
                    Brush.verticalGradient(
                        0f to camera.background.copy(alpha = 0.92f),
                        fadeFrom to camera.background.copy(alpha = 0.85f),
                        1f to Color.Transparent,
                    ),
                )
            }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = Spacing.xs, end = Spacing.xs, top = Spacing.xs, bottom = ScrimFade),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onClose,
            modifier = Modifier.focusRing(CircleShape),
            colors = IconButtonDefaults.iconButtonColors(contentColor = camera.content),
        ) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_close))
        }
        Column(Modifier.padding(start = Spacing.xs, end = Spacing.lg)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = camera.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (position != null) {
                Text(position, style = MaterialTheme.typography.bodySmall, color = camera.muted)
            }
            if (foilNote != null) {
                Text(foilNote, style = MaterialTheme.typography.bodySmall, color = camera.muted)
            }
            if (foilHint != null) {
                Text(foilHint, style = MaterialTheme.typography.bodySmall, color = camera.muted)
            }
        }
    }
}

/** The scrim under the viewer's bar fades out over this much padding below the bar's content. */
private val ScrimFade = Spacing.lg

/** The dialog's window draws behind the system bars; on the dark surface their icons must be light in both themes. */
@Composable
private fun LightSystemBarIcons() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    DisposableEffect(window, view) {
        if (window != null) {
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
        onDispose {}
    }
}

/** One page: the image fitted to the screen, with its own zoom and pan. */
@Composable
private fun ZoomablePage(
    image: CardViewerImage,
    reducedMotion: Boolean,
    dragY: () -> Float,
    onZoomedChange: (Boolean) -> Unit,
    onDismissDrag: (Float) -> Unit,
    onDismissEnd: (velocityY: Float?) -> Unit,
    foilTilt: FoilTiltState,
) {
    var scale by remember { mutableFloatStateOf(ViewerZoom.MIN_SCALE) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var intrinsic by remember { mutableStateOf(Size.Unspecified) }
    var failed by remember { mutableStateOf(false) }
    var animation by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val failedText = stringResource(if (image.ownPhoto) R.string.viewer_photo_failed else R.string.viewer_image_failed)
    val zoomIn = stringResource(R.string.viewer_zoom_in)
    val zoomReset = stringResource(R.string.viewer_zoom_reset)
    val panLabels = listOf(
        stringResource(R.string.viewer_pan_left),
        stringResource(R.string.viewer_pan_right),
        stringResource(R.string.viewer_pan_up),
        stringResource(R.string.viewer_pan_down),
    )
    // Read in composition only when they flip, not on every frame of a pinch.
    val zoomed by remember { derivedStateOf { ViewerZoom.isZoomed(scale) } }
    val canZoomIn by remember { derivedStateOf { scale < ViewerZoom.MAX_SCALE } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val container = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val area = with(density) {
            Size(
                (container.width - 2 * ImageGutterHorizontal.toPx()).coerceAtLeast(1f),
                (container.height - 2 * ImageGutterVertical.toPx()).coerceAtLeast(1f),
            )
        }
        // Until the image has loaded, assume a card's shape, so the first gestures already have the right bounds.
        val content = ViewerZoom.fit(if (intrinsic.isFinite()) intrinsic else Size(CARD_ASPECT, 1f), area)
        val contentDp: Pair<Dp, Dp> = with(density) { content.width.toDp() to content.height.toDp() }

        val panSteps = listOf(
            Offset(container.width / 4, 0f),
            Offset(-container.width / 4, 0f),
            Offset(0f, container.height / 4),
            Offset(0f, -container.height / 4),
        )
        // Bit i is set while pan step i would still move the zoomed image; read in composition, so the TalkBack
        // actions follow the image as it reaches an edge.
        val canPan by remember(content, container) {
            derivedStateOf(structuralEqualityPolicy()) {
                panSteps.foldIndexed(0) { index, mask, step ->
                    if (ViewerZoom.clampOffset(offset + step, scale, content, container) != offset) mask or (1 shl index) else mask
                }
            }
        }
        val panMask = canPan

        fun apply(newScale: Float, newOffset: Offset) {
            scale = newScale
            offset = ViewerZoom.clampOffset(newOffset, newScale, content, container)
            onZoomedChange(ViewerZoom.isZoomed(newScale))
        }

        fun zoomTo(target: Float, focus: Offset) {
            animation?.cancel()
            val endOffset = ViewerZoom.clampOffset(ViewerZoom.zoomAround(offset, scale, target, focus), target, content, container)
            if (reducedMotion) {
                apply(target, endOffset)
                return
            }
            val startScale = scale
            val startOffset = offset
            animation = scope.launch {
                animate(0f, 1f, animationSpec = tween(DoubleTapZoomMillis)) { fraction, _ ->
                    apply(lerp(startScale, target, fraction), lerp(startOffset, endOffset, fraction))
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                // Keyboard and D-pad (#250): the image takes focus; +/- or Enter zoom, 0 or Escape resets, and the arrow
                // keys pan a zoomed card by the same quarter-screen steps as TalkBack's actions. Unzoomed, the arrows
                // are left to the pager, so they still turn the pages.
                .focusRing(RectangleShape, gap = (-4).dp)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val pan = when (event.key) {
                        Key.DirectionLeft -> panSteps[0]
                        Key.DirectionRight -> panSteps[1]
                        Key.DirectionUp -> panSteps[2]
                        Key.DirectionDown -> panSteps[3]
                        else -> null
                    }
                    when {
                        pan != null && ViewerZoom.isZoomed(scale) -> {
                            animation?.cancel()
                            apply(scale, offset + pan)
                            true
                        }
                        event.key == Key.Plus || event.key == Key.Equals || event.key == Key.NumPadAdd ||
                            event.key == Key.Enter || event.key == Key.DirectionCenter -> {
                            if (scale < ViewerZoom.MAX_SCALE) zoomTo((scale * 2f).coerceAtMost(ViewerZoom.MAX_SCALE), Offset.Zero)
                            true
                        }
                        event.key == Key.Minus || event.key == Key.NumPadSubtract -> {
                            zoomTo((scale / 2f).coerceAtLeast(ViewerZoom.MIN_SCALE), Offset.Zero)
                            true
                        }
                        (event.key == Key.Zero || event.key == Key.Escape) && ViewerZoom.isZoomed(scale) -> {
                            zoomTo(ViewerZoom.MIN_SCALE, Offset.Zero)
                            true
                        }
                        else -> false
                    }
                }
                .focusable()
                .pointerInput(content, container) {
                    detectTapGestures(onDoubleTap = { tap -> zoomTo(ViewerZoom.doubleTapTarget(scale), tap - container.center) })
                }
                .pointerInput(content, container) {
                    detectViewerGestures(
                        isZoomed = { ViewerZoom.isZoomed(scale) },
                        onTransform = { focus, pan, zoom ->
                            animation?.cancel()
                            val newScale = ViewerZoom.clampScale(scale * zoom)
                            apply(newScale, ViewerZoom.zoomAround(offset, scale, newScale, focus) + pan)
                        },
                        onDismissDrag = onDismissDrag,
                        onDismissEnd = onDismissEnd,
                    )
                }
                .semantics {
                    // A failed page says so instead of describing a picture that isn't there, and offers no zoom.
                    contentDescription = if (failed) failedText else image.description
                    if (!failed) customActions = buildList {
                        if (canZoomIn) {
                            add(
                                CustomAccessibilityAction(zoomIn) {
                                    zoomTo((scale * 2f).coerceAtMost(ViewerZoom.MAX_SCALE), Offset.Zero)
                                    true
                                },
                            )
                        }
                        if (zoomed) {
                            add(
                                CustomAccessibilityAction(zoomReset) {
                                    zoomTo(ViewerZoom.MIN_SCALE, Offset.Zero)
                                    true
                                },
                            )
                            // Panning was touch-only, so a zoomed card's corners were out of TalkBack's reach (#221):
                            // a quarter of the screen per step, within the same bounds as a drag. Only the
                            // directions that can still move are offered, so none reports a move at an edge (#250).
                            panSteps.forEachIndexed { index, step ->
                                if (panMask and (1 shl index) == 0) return@forEachIndexed
                                add(
                                    CustomAccessibilityAction(panLabels[index]) {
                                        animation?.cancel()
                                        apply(scale, offset + step)
                                        true
                                    },
                                )
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(contentDp.first, contentDp.second)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y + dragY()
                    },
            ) {
                val context = LocalPlatformContext.current
                // The full image, not one sized to the fitted layout, so zooming in shows real detail (the CDN's high
                // image is ~600 px, own photos at most 1200 px; PhotoImport).
                val request = remember(image.model, context) {
                    ImageRequest.Builder(context).data(image.model).size(coil3.size.Size.ORIGINAL).build()
                }
                AsyncImage(
                    model = request,
                    // The page says what it shows, with the zoom actions; the image itself stays silent.
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    onState = { state ->
                        when (state) {
                            is AsyncImagePainter.State.Success -> {
                                intrinsic = state.painter.intrinsicSize
                                failed = false
                            }
                            is AsyncImagePainter.State.Error -> failed = true
                            else -> Unit
                        }
                    },
                )
                // Only over a loaded image: no light on the placeholder bounds or a failed page.
                val foil = image.foil
                if (foil != null && intrinsic.isFinite() && !failed) {
                    FoilLightLayer(foil, foilTilt)
                }
            }
            if (failed) {
                Text(
                    failedText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = LumifoilTheme.colors.camera.muted,
                    textAlign = TextAlign.Center,
                    // The page above already says it to TalkBack.
                    modifier = Modifier.padding(horizontal = Spacing.xxl).clearAndSetSemantics {},
                )
            }
        }
    }
}

private fun Size.isFinite(): Boolean = width.isFinite() && height.isFinite()

private enum class GestureMode { UNDECIDED, TRANSFORM, DISMISS }

/**
 * The viewer's own gestures. Two fingers always pinch and pan. One finger pans a zoomed image; on the fitted image a
 * mostly vertical drag is the swipe to close, and a mostly horizontal one is left to the pager. Double taps are
 * handled separately (detectTapGestures), which gives up as soon as this consumes a move.
 */
private suspend fun PointerInputScope.detectViewerGestures(
    isZoomed: () -> Boolean,
    onTransform: (focus: Offset, pan: Offset, zoom: Float) -> Unit,
    onDismissDrag: (Float) -> Unit,
    onDismissEnd: (velocityY: Float?) -> Unit,
) = awaitEachGesture {
    val first = awaitFirstDown(requireUnconsumed = false)
    val tracker = VelocityTracker().apply { addPointerInputChange(first) }
    val slop = viewConfiguration.touchSlop
    val center = size.toSize().center
    var mode = GestureMode.UNDECIDED
    var travel = Offset.Zero
    while (true) {
        val event = awaitPointerEvent()
        event.changes.firstOrNull()?.let(tracker::addPointerInputChange)
        val pressed = event.changes.count { it.pressed }
        if (pressed == 0) break
        val zoom = event.calculateZoom()
        val pan = event.calculatePan()
        if (mode == GestureMode.UNDECIDED) {
            // The card took the gesture (the foil light's touch and hold, #167): leave it alone.
            if (event.changes.any { it.isConsumed }) return@awaitEachGesture
            travel += pan
            mode = when {
                pressed > 1 -> GestureMode.TRANSFORM
                travel.getDistance() < slop -> GestureMode.UNDECIDED
                isZoomed() -> GestureMode.TRANSFORM
                abs(travel.y) > abs(travel.x) -> GestureMode.DISMISS
                // Sideways on the fitted image: the pager turns the page.
                else -> return@awaitEachGesture
            }
        } else if (mode == GestureMode.DISMISS && pressed > 1) {
            // A second finger during the swipe: put the card back (never close) and pinch instead.
            onDismissEnd(null)
            mode = GestureMode.TRANSFORM
        }
        when (mode) {
            GestureMode.TRANSFORM -> if (zoom != 1f || pan != Offset.Zero) {
                onTransform(event.calculateCentroid(useCurrent = false) - center, pan, zoom)
            }
            GestureMode.DISMISS -> onDismissDrag(pan.y)
            GestureMode.UNDECIDED -> Unit
        }
        if (mode != GestureMode.UNDECIDED) event.changes.forEach { if (it.positionChanged()) it.consume() }
    }
    if (mode == GestureMode.DISMISS) onDismissEnd(tracker.calculateVelocity().y)
}
