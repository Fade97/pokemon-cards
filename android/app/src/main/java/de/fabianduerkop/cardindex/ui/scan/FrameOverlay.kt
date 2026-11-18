// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.ui.scan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import de.fabianduerkop.cardindex.ui.theme.LumifoilTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import de.fabianduerkop.cardindex.recognition.Quad

/**
 * Draws the card guide (dashed) and the detected outline over a frame shown with ContentScale.Fit, so both line up
 * with what is analysed; a dot marks the outline's first corner. Shared by the Scan screen and the lab.
 */
@Composable
fun FrameOverlay(
    frameWidth: Int,
    frameHeight: Int,
    guide: Quad?,
    outline: Quad?,
    outlineColor: Color,
    modifier: Modifier = Modifier,
    guideColor: Color = LumifoilTheme.colors.camera.guide,
) {
    Canvas(modifier.fillMaxSize()) {
        val fw = frameWidth.toFloat()
        val fh = frameHeight.toFloat()
        val s = minOf(size.width / fw, size.height / fh)
        val ox = (size.width - fw * s) / 2
        val oy = (size.height - fh * s) / 2
        fun map(q: Quad) = q.map { Offset(ox + it.x.toFloat() * s, oy + it.y.toFloat() * s) }
        guide?.let {
            val dash = PathEffect.dashPathEffect(floatArrayOf(18f, 12f))
            drawQuad(map(it), guideColor, Stroke(width = 2.dp.toPx(), pathEffect = dash))
        }
        outline?.let {
            val pts = map(it)
            drawQuad(pts, outlineColor, Stroke(width = 3.dp.toPx()))
            drawCircle(outlineColor, radius = 5.dp.toPx(), center = pts[0])
        }
    }
}

private fun DrawScope.drawQuad(pts: List<Offset>, color: Color, stroke: Stroke) {
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        pts.drop(1).forEach { lineTo(it.x, it.y) }
        close()
    }
    drawPath(path, color, style = stroke)
}

