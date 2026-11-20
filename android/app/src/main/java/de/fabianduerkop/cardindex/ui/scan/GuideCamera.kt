// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.scan

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.view.LifecycleCameraController
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import de.fabianduerkop.cardindex.recognition.ScanGuide
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * The optics behind the scan guide (#197). A card that fills the guide must be farther away than the lens can focus,
 * or it is blurry however often the camera refocuses: on the S25 Ultra's main lens (6.3 mm, 9.8 mm sensor, 10 cm
 * closest focus) a filled guide is only about 7 cm away. A little zoom moves it out to where the lens is sharp.
 */
internal object GuideOptics {
    /** A Pokémon card's height. */
    const val CARD_HEIGHT_MM = 88.0

    /** How far beyond the closest focus distance a filled guide should be, so a card held a bit closer stays sharp. */
    const val FOCUS_MARGIN = 1.3

    /**
     * The highest zoom used. Many phones switch a logical camera to a telephoto lens from 2× on, which focuses no
     * closer than 30–80 cm; below that the main sensor is cropped.
     */
    const val MAX_ZOOM = 1.9

    /** The centre of the frame the continuous autofocus weighs: the card's art and name, inside the guide. */
    const val FOCUS_REGION = 0.3

    /**
     * The zoom at which a card filling the guide is [FOCUS_MARGIN] times the closest focus distance away.
     * [sensorLongMm] is the sensor's long side, which the guide's [ScanGuide.HEIGHT] runs along in portrait.
     * [minFocusDiopters] is 0 for a fixed-focus lens: then 1. Never below 1 or above [maxZoom] and [MAX_ZOOM].
     */
    fun zoom(focalLengthMm: Double, sensorLongMm: Double, minFocusDiopters: Double, maxZoom: Double): Double {
        if (focalLengthMm <= 0 || sensorLongMm <= 0 || minFocusDiopters <= 0) return 1.0
        val closestMm = 1000.0 / minFocusDiopters
        // At zoom z a card filling the guide is CARD_HEIGHT · focal · z / (HEIGHT · sensor) away.
        val wanted = FOCUS_MARGIN * closestMm * ScanGuide.HEIGHT * sensorLongMm / (CARD_HEIGHT_MM * focalLengthMm)
        return wanted.coerceIn(1.0, minOf(maxZoom, MAX_ZOOM).coerceAtLeast(1.0))
    }

    /** How far away a card filling the guide is at [zoom], in mm. */
    fun guideDistanceMm(focalLengthMm: Double, sensorLongMm: Double, zoom: Double): Double =
        CARD_HEIGHT_MM * focalLengthMm * zoom / (ScanGuide.HEIGHT * sensorLongMm)

    /** The centred [FOCUS_REGION] of a sensor array [width] × [height]: left, top, right, bottom. */
    fun centreRegion(width: Int, height: Int): IntArray {
        val w = (width * FOCUS_REGION).roundToInt()
        val h = (height * FOCUS_REGION).roundToInt()
        val left = (width - w) / 2
        val top = (height - h) / 2
        return intArrayOf(left, top, left + w, top + h)
    }
}

/** What the scan camera sets each time it opens: the zoom and, where the camera supports it, the focus region. */
internal data class GuideCameraSetup(val zoom: Float, val focusRegion: Rect?)

@OptIn(ExperimentalCamera2Interop::class)
private fun guideCameraSetup(info: CameraInfo): GuideCameraSetup {
    val camera = Camera2CameraInfo.from(info)
    val focal = camera.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull()?.toDouble() ?: 0.0
    val sensor = camera.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
    val sensorLong = sensor?.let { maxOf(it.width, it.height).toDouble() } ?: 0.0
    val diopters = camera.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)?.toDouble() ?: 0.0
    val maxZoom = info.zoomState.value?.maxZoomRatio?.toDouble() ?: 1.0
    val zoom = GuideOptics.zoom(focal, sensorLong, diopters, maxZoom).toFloat()
    val regions = camera.getCameraCharacteristic(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
    val active = camera.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    val region = if (regions > 0 && active != null && diopters > 0) {
        GuideOptics.centreRegion(active.width(), active.height()).let { (l, t, r, b) -> Rect(l, t, r, b) }
    } else {
        null
    }
    return GuideCameraSetup(zoom, region)
}

@OptIn(ExperimentalCamera2Interop::class)
private fun LifecycleCameraController.apply(setup: GuideCameraSetup) {
    setZoomRatio(setup.zoom)
    val control = cameraControl ?: return
    val region = setup.focusRegion ?: return
    // Continuous autofocus keeps running, it only weighs the guide's centre instead of the whole frame.
    Camera2CameraControl.from(control).setCaptureRequestOptions(
        CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(MeteringRectangle(region, MeteringRectangle.METERING_WEIGHT_MAX)))
            .build(),
    )
}

/**
 * Applies the guide's zoom and focus region (#197) whenever the camera opens: closing the camera (app in the
 * background, screen off) resets both.
 */
@Composable
internal fun GuideCamera(controller: LifecycleCameraController, info: CameraInfo?, lifecycleOwner: LifecycleOwner) {
    if (info == null) return
    val setup = remember(info) { guideCameraSetup(info) }
    DisposableEffect(info, lifecycleOwner, setup) {
        val observer = Observer<CameraState> { state -> if (state.type == CameraState.Type.OPEN) controller.apply(setup) }
        info.cameraState.observe(lifecycleOwner, observer)
        onDispose { info.cameraState.removeObserver(observer) }
    }
}

/** One autofocus run on the guide's centre, then back to continuous focus: what a tap on the camera does. */
internal fun LifecycleCameraController.focusOnGuide() {
    val point = SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f, GuideOptics.FOCUS_REGION.toFloat())
    val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
        .setAutoCancelDuration(3, TimeUnit.SECONDS)
        .build()
    cameraControl?.startFocusAndMetering(action)
}
