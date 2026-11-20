// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.components

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.toSize
import androidx.lifecycle.compose.LifecycleResumeEffect
import kotlinx.coroutines.launch

/**
 * The tilt that drives the viewer's foil light (#167): from the gyroscope when the phone has one, otherwise from a
 * finger held on the card ([touchControlled]).
 */
@Stable
internal class FoilTiltState(
    /** No gyroscope, and motion allowed: touch and hold the card, then drag, to move the light. */
    val touchControlled: Boolean,
) {
    var tilt by mutableStateOf(Tilt.Rest)
}

/** Sampling period of the gyroscope: about 120 Hz, so the light keeps up with a 60 or 120 Hz screen. */
private const val GyroSamplingMicros = 8_333

/**
 * The foil tilt for an open card viewer. With [enabled] false (no page has foil, or reduced motion) the light stays at
 * rest and no sensor is registered. Otherwise the gyroscope is registered only while the app is resumed: it stops when
 * the viewer closes, the screen turns off or the app goes to the background, and the light returns to rest.
 */
@Composable
internal fun rememberFoilTilt(enabled: Boolean): FoilTiltState {
    val context = LocalContext.current
    val sensors = remember(context) { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
    val gyroscope = remember(sensors) { sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) }
    val state = remember(enabled, gyroscope) { FoilTiltState(touchControlled = enabled && gyroscope == null) }
    // Read again when the configuration changes, so a turned phone maps its axes to the new screen.
    val configuration = LocalConfiguration.current
    val view = LocalView.current
    val quarterTurns = remember(configuration, view) { view.display?.rotation ?: 0 }

    LifecycleResumeEffect(state, sensors, gyroscope, quarterTurns) {
        val listener = if (enabled && sensors != null && gyroscope != null) {
            object : SensorEventListener {
                private var lastTimestamp = 0L

                override fun onSensorChanged(event: SensorEvent) {
                    val last = lastTimestamp
                    lastTimestamp = event.timestamp
                    if (last == 0L) return
                    val dt = (event.timestamp - last) / 1_000_000_000f
                    state.tilt = FoilLight.step(state.tilt, event.values[0], event.values[1], quarterTurns, dt)
                }

                override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
            }.also { sensors.registerListener(it, gyroscope, GyroSamplingMicros) }
        } else {
            null
        }
        onPauseOrDispose {
            if (listener != null) sensors?.unregisterListener(listener)
            state.tilt = Tilt.Rest
        }
    }
    return state
}

/** The rounded corners of a printed card, about 3 mm on 63 mm, so the light never spills past the art. */
private val CardCorners = RoundedCornerShape(percent = 5)

/** Pale spectral stops of the holo band, brightest in the middle. Screen-blended, so they only ever lighten. */
private val HoloStops = listOf(
    Color.Transparent,
    Color(0xFF7FE3D2),
    Color(0xFFB9D6FF),
    Color(0xFFFFFFFF),
    Color(0xFFE3BCFF),
    Color(0xFFFFD7B5),
    Color.Transparent,
)

/** The reverse holo band: a pale glint with a hint of colour. */
private val ReverseHoloStops = listOf(
    Color.Transparent,
    Color(0xFFCDEFF0),
    Color(0xFFFFFFFF),
    Color(0xFFE6DDF5),
    Color.Transparent,
)

private val GlareStops = listOf(Color.White.copy(alpha = 0.55f), Color.Transparent)

/**
 * The simulated foil light over a card image in the viewer (ADR 0015): a band and a glare, screen-blended over the art
 * and clipped to the card's corners. It is drawn in the draw phase only, so a moving light never recomposes. Screen
 * readers skip it; the viewer's bar says that the light is simulated.
 *
 * With [FoilTiltState.touchControlled], touch and hold the card, then drag to move the light; it springs back to rest
 * when released. Other gestures (paging, swipe to close, zoom) are left alone until the hold.
 */
@Composable
internal fun FoilLightLayer(finish: FoilFinish, tilt: FoilTiltState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val release = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val touch = if (tilt.touchControlled) {
        Modifier.pointerInput(tilt) {
            fun follow(position: Offset) {
                tilt.tilt = FoilLight.fromTouch(position, size.toSize())
            }
            fun springBack() {
                val from = tilt.tilt
                scope.launch {
                    release.snapTo(Offset(from.x, from.y))
                    release.animateTo(Offset.Zero, spring()) { tilt.tilt = Tilt(value.x, value.y) }
                }
            }
            detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    scope.launch { release.stop() }
                    follow(position)
                },
                onDrag = { change, _ ->
                    change.consume()
                    follow(change.position)
                },
                onDragEnd = ::springBack,
                onDragCancel = ::springBack,
            )
        }
    } else {
        Modifier
    }
    val stops = if (finish == FoilFinish.HOLO) HoloStops else ReverseHoloStops
    Box(
        modifier
            .fillMaxSize()
            .clip(CardCorners)
            .then(touch)
            .drawBehind {
                val current = tilt.tilt
                val alpha = FoilLight.alpha(current, finish)
                val band = FoilLight.band(current, size, finish)
                drawRect(
                    Brush.linearGradient(stops, start = band.start, end = band.end),
                    alpha = alpha,
                    blendMode = BlendMode.Screen,
                )
                drawRect(
                    Brush.radialGradient(GlareStops, center = FoilLight.glareCentre(current, size), radius = size.width * FoilLight.GLARE_RADIUS),
                    alpha = alpha * current.amount,
                    blendMode = BlendMode.Screen,
                )
            },
    )
}
