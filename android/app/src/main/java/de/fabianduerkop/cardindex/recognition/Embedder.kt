// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * The card embedding model on LiteRT (CPU, XNNPACK). Input: 1 × 224 × 224 × 3 float32 RGB in [0, 1], the rectified
 * card squashed to a square; ImageNet normalization is inside the model. Output: the L2-normalized embedding.
 */
class Embedder(model: ByteBuffer, threads: Int = 4) : AutoCloseable {
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(threads))
    val dim: Int = interpreter.getOutputTensor(0).shape().last()
    private val input = ByteBuffer.allocateDirect(4 * INPUT * INPUT * 3).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(dim) }
    private val pixels = ByteArray(INPUT * INPUT * 3)

    /** Embeds a BGR `CV_8UC3` card crop (any size; it is resized to 224 × 224 with area interpolation). */
    fun embed(bgrCrop: Mat): FloatArray {
        val resized = Mat()
        Imgproc.resize(bgrCrop, resized, Size(INPUT.toDouble(), INPUT.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val rgb = Mat()
        Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGR2RGB)
        rgb.get(0, 0, pixels)
        resized.release(); rgb.release()
        input.rewind()
        for (b in pixels) input.putFloat((b.toInt() and 0xFF) / 255f)
        input.rewind()
        interpreter.run(input, output)
        val e = output[0].copyOf()
        var n = 0f
        for (v in e) n += v * v
        val inv = 1f / sqrt(n)
        for (i in e.indices) e[i] *= inv
        return e
    }

    override fun close() = interpreter.close()

    companion object {
        const val INPUT = 224
    }
}
