// SPDX-License-Identifier: GPL-3.0-or-later
package de.fabianduerkop.cardindex.recognition

import android.content.res.AssetManager
import android.util.Log
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.TensorBuffer
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Where the embedding model runs. */
enum class EmbedderBackend { CPU, GPU }

/**
 * The card embedding model on LiteRT. Input: 1 × 224 × 224 × 3 float32 RGB in [0, 1], the rectified card squashed
 * to a square; ImageNet normalization is inside the model. Output: the L2-normalized embedding.
 *
 * The CPU path (XNNPACK interpreter) is the reference that RecognitionParityTest compares with Python, and the pack
 * index is built with the same CPU numerics. The GPU path is only used after it agreed with the CPU on a probe image
 * ([create]). It can't agree for a dynamic-range model (int8 weights, `dynamic_wi8_afp32`, the "w8" pack model): the
 * CPU runs it with int8 activations, the GPU in float. On the Galaxy S25 Ultra that gives cosine 0.89 between them,
 * while the GPU stays at 0.998 to the float32 model (issue #54, EmbedderGpuProbe). Float16 or float32 models agree to
 * 0.9999 on either GPU precision.
 */
class Embedder private constructor(private val runner: Runner) : AutoCloseable {
    val backend: EmbedderBackend get() = runner.backend
    val dim: Int get() = runner.dim
    private val input = FloatArray(INPUT * INPUT * 3)
    private val pixels = ByteArray(INPUT * INPUT * 3)

    /** Embeds a BGR `CV_8UC3` card crop (any size; it is resized to 224 × 224 with area interpolation). */
    fun embed(bgrCrop: Mat): FloatArray {
        val resized = Mat()
        Imgproc.resize(bgrCrop, resized, Size(INPUT.toDouble(), INPUT.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val rgb = Mat()
        Imgproc.cvtColor(resized, rgb, Imgproc.COLOR_BGR2RGB)
        rgb.get(0, 0, pixels)
        resized.release(); rgb.release()
        for (i in pixels.indices) input[i] = (pixels[i].toInt() and 0xFF) / 255f
        return normalized(runner.run(input))
    }

    /** Embeds a model input as is (1 × 224 × 224 × 3 RGB in [0, 1]); for the GPU probe test. */
    internal fun embedInput(modelInput: FloatArray): FloatArray = normalized(runner.run(modelInput))

    override fun close() = runner.close()

    private interface Runner : AutoCloseable {
        val backend: EmbedderBackend
        val dim: Int
        fun run(input: FloatArray): FloatArray
    }

    private class CpuRunner(model: ByteBuffer, threads: Int) : Runner {
        private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(threads))
        override val backend = EmbedderBackend.CPU
        override val dim: Int = interpreter.getOutputTensor(0).shape().last()
        private val buffer = ByteBuffer.allocateDirect(4 * INPUT * INPUT * 3).order(ByteOrder.nativeOrder())
        private val output = Array(1) { FloatArray(dim) }

        override fun run(input: FloatArray): FloatArray {
            buffer.rewind()
            buffer.asFloatBuffer().put(input)
            buffer.rewind()
            interpreter.run(buffer, output)
            return output[0].copyOf()
        }

        override fun close() = interpreter.close()
    }

    private class GpuRunner(
        compile: (CompiledModel.Options) -> CompiledModel,
        precision: CompiledModel.GpuOptions.Precision,
    ) : Runner {
        private val model = compile(
            CompiledModel.Options(Accelerator.GPU).apply { gpuOptions = CompiledModel.GpuOptions(precision = precision) },
        )
        private val inputs: List<TensorBuffer> = model.createInputBuffers()
        private val outputs: List<TensorBuffer> = model.createOutputBuffers()
        override val backend = EmbedderBackend.GPU
        override val dim: Int

        init {
            dim = run(FloatArray(INPUT * INPUT * 3)).size
        }

        override fun run(input: FloatArray): FloatArray {
            inputs[0].writeFloat(input)
            model.run(inputs, outputs)
            return outputs[0].readFloat()
        }

        override fun close() {
            inputs.forEach { it.close() }
            outputs.forEach { it.close() }
            model.close()
        }
    }

    companion object {
        const val INPUT = 224
        private const val TAG = "Embedder"

        /** The GPU must reproduce the CPU embedding of the probe at least this closely (cosine). */
        private const val MIN_GPU_AGREEMENT = 0.995f

        /**
         * GPU precision for [create]. On the S25 FP16 matched FP32 (cosine 0.9999) at 1.2 vs. 1.8 ms; FP32 stays the
         * safer default for other GPUs. EmbedderGpuProbe measures both on a device.
         */
        private val GPU_PRECISION = CompiledModel.GpuOptions.Precision.FP32

        /** CPU only: the parity reference. */
        fun cpu(model: ByteBuffer, threads: Int = 4): Embedder = Embedder(CpuRunner(model, threads))

        /** GPU only, no CPU check, throws when the GPU can't run the model; for the GPU probe test. */
        internal fun gpu(modelFile: File, precision: CompiledModel.GpuOptions.Precision): Embedder =
            Embedder(GpuRunner({ options -> CompiledModel.create(modelFile.absolutePath, options) }, precision))

        /**
         * With [preferGpu], tries the GPU and keeps it only if it loads and agrees with the CPU on a probe image;
         * otherwise (no GPU accelerator, unsupported ops, drifting results) the CPU embedder is returned.
         */
        fun create(model: ByteBuffer, assets: AssetManager, assetPath: String, preferGpu: Boolean): Embedder =
            create(model, preferGpu) { options -> CompiledModel.create(assets, assetPath, options) }

        /** As above for a model file in app storage (an installed language-pack model). */
        fun create(model: ByteBuffer, modelFile: File, preferGpu: Boolean): Embedder =
            create(model, preferGpu) { options -> CompiledModel.create(modelFile.absolutePath, options) }

        private fun create(model: ByteBuffer, preferGpu: Boolean, compile: (CompiledModel.Options) -> CompiledModel): Embedder {
            val cpu = CpuRunner(model, threads = 4)
            if (!preferGpu) return Embedder(cpu)
            val gpu = try {
                GpuRunner(compile, GPU_PRECISION)
            } catch (t: Throwable) {
                // UnsatisfiedLinkError on ABIs without the accelerator library, LiteRtException without a usable GPU.
                Log.i(TAG, "GPU embedder unavailable, using the CPU", t)
                return Embedder(cpu)
            }
            val probe = probeImage()
            val agreement = runCatching {
                if (gpu.dim != cpu.dim) return@runCatching -1f
                dot(normalized(cpu.run(probe)), normalized(gpu.run(probe)))
            }.getOrDefault(-1f)
            return if (agreement >= MIN_GPU_AGREEMENT) {
                Log.i(TAG, "GPU embedder agrees with the CPU (cosine $agreement); ${timeMs(gpu, probe)} ms vs ${timeMs(cpu, probe)} ms")
                cpu.close()
                Embedder(gpu)
            } else {
                Log.w(TAG, "GPU embedding differs from the CPU (cosine $agreement), using the CPU")
                gpu.close()
                Embedder(cpu)
            }
        }

        private fun timeMs(runner: Runner, input: FloatArray): Long {
            val t0 = System.nanoTime()
            repeat(3) { runner.run(input) }
            return (System.nanoTime() - t0) / 3_000_000
        }

        /** A deterministic, textured test image: smooth gradients alone would hide precision problems. */
        internal fun probeImage(): FloatArray {
            val out = FloatArray(INPUT * INPUT * 3)
            var seed = 0x2545F491
            for (i in out.indices) {
                seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
                val pixel = i / 3
                val gradient = ((pixel % INPUT) + (pixel / INPUT)) / (2f * INPUT)
                out[i] = 0.6f * gradient + 0.4f * ((seed ushr 8) and 0xFF) / 255f
            }
            return out
        }

        private fun normalized(e: FloatArray): FloatArray {
            var n = 0f
            for (v in e) n += v * v
            val inv = 1f / sqrt(n)
            return FloatArray(e.size) { e[it] * inv }
        }

        private fun dot(a: FloatArray, b: FloatArray): Float {
            var s = 0f
            for (i in a.indices) s += a[i] * b[i]
            return s
        }
    }
}
