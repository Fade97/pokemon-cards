// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.share

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale
import de.fabianduerkop.cardindex.R
import de.fabianduerkop.cardindex.ui.components.PrimaryButton
import de.fabianduerkop.cardindex.ui.theme.LumifoilTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every share image (session receipt #163, set slot map and missing list #170) is 360 × 640 dp laid out at 3x:
 * exactly 1080 × 1920 px, 9:16, the size story and short-video apps expect, and sharp on any phone.
 */
/** Share of the window height a [ShareImagePreview] may take. */
private const val PreviewHeightShare = 0.5f

object ShareImageSize {
    val Width = 360.dp
    val Height = 640.dp
    const val DENSITY = 3f
    const val PIXEL_WIDTH = 1080
    const val PIXEL_HEIGHT = 1920
}

/**
 * The image's own world: always Pearl light, font scale 1 and the fixed share density, so every image looks the same
 * whatever the phone's theme, text size and screen. [content] fills the 360 × 640 dp page on the light background.
 * No card art goes in here (ADR 0009).
 */
@Composable
fun ShareCanvas(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDensity provides Density(ShareImageSize.DENSITY, fontScale = 1f)) {
        LumifoilTheme(darkTheme = false) {
            // The theme doesn't reset the content colour, so text without its own colour would keep the dark sheet's
            // light grey around the preview and vanish on the white page.
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                Box(Modifier.requiredSize(ShareImageSize.Width, ShareImageSize.Height).background(MaterialTheme.colorScheme.background)) {
                    content()
                }
            }
        }
    }
}

/**
 * The image as it will be posted, scaled down to the available width, and recorded into [layer] for [ShareImageButton].
 * It is laid out at its own pixel size and only drawn smaller, so the PNG depends on neither the phone's width nor its
 * density. TalkBack reads [description] instead of the picture. At most [PreviewHeightShare] of the window high, so
 * the share button under it is in view when the sheet opens (#218).
 */
@Composable
fun ShareImagePreview(
    layer: GraphicsLayer,
    description: String,
    modifier: Modifier = Modifier,
    /** The room the caller measured for the preview; it never gets taller than this either (#250). */
    maxHeight: Dp? = null,
    content: @Composable () -> Unit,
) {
    // Less of the window at a large font: the sheet's text grows, so the preview gives up room and the caption and
    // actions under it stay in view (#250).
    // A measured room wins: the caller knows what else must stay in view. Without one, the share of the window.
    val maxHeight = maxHeight
        ?: (LocalConfiguration.current.screenHeightDp.dp * PreviewHeightShare / LocalDensity.current.fontScale.coerceAtLeast(1f))
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val shown = minOf(maxWidth, ShareImageSize.Width, maxHeight * (ShareImageSize.Width / ShareImageSize.Height))
        val density = LocalDensity.current
        val pixels = with(density) { DpSize(ShareImageSize.PIXEL_WIDTH.toDp(), ShareImageSize.PIXEL_HEIGHT.toDp()) }
        val scale = with(density) { shown.toPx() } / ShareImageSize.PIXEL_WIDTH
        Box(
            Modifier
                .size(shown, shown * (ShareImageSize.Height / ShareImageSize.Width))
                // Clipped to the rounded frame: the posted PNG has square corners, the preview must not show them.
                .clip(MaterialTheme.shapes.medium)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
                .clearAndSetSemantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .requiredSize(pixels)
                    .graphicsLayer(scaleX = scale, scaleY = scale)
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    },
            ) {
                content()
            }
        }
    }
}

/**
 * Share: renders [layer] to a 1080 × 1920 PNG named [fileName] and opens the Android share sheet; nothing is uploaded.
 * A failure is said under the button (and to TalkBack) instead of nothing happening.
 */
@Composable
fun ShareImageButton(text: String, layer: GraphicsLayer, fileName: String, chooserTitle: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    Column(modifier) {
        PrimaryButton(
            text,
            enabled = !sharing,
            onClick = {
                sharing = true
                failed = false
                scope.launch {
                    try {
                        shareLayer(context, layer, fileName, chooserTitle)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "sharing $fileName failed", e)
                        failed = true
                    } finally {
                        sharing = false
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        if (failed) {
            Text(
                stringResource(R.string.share_failed),
                Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private suspend fun shareLayer(context: Context, layer: GraphicsLayer, fileName: String, chooserTitle: String) {
    val rendered = layer.toImageBitmap().asAndroidBitmap()
    val file = withContext(Dispatchers.IO) {
        // A software copy: a hardware bitmap can't always be scaled or compressed directly.
        val software = rendered.copy(Bitmap.Config.ARGB_8888, false)
        val png = if (software.width == ShareImageSize.PIXEL_WIDTH && software.height == ShareImageSize.PIXEL_HEIGHT) {
            software
        } else {
            software.scale(ShareImageSize.PIXEL_WIDTH, ShareImageSize.PIXEL_HEIGHT)
        }
        ImageShare.write(context, png, fileName)
    }
    context.startActivity(ImageShare.intent(context, file, chooserTitle))
}

private const val TAG = "ShareImages"
