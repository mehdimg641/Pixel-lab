package ir.pixellab.engine.android

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ir.pixellab.core.ai.SegmentationModel
import java.io.File
import java.nio.FloatBuffer

/**
 * Runs a real segmentation network on the device.
 *
 * Built for the *heavy* models — BiRefNet and RMBG-2.0 — rather than the mobile-sized ones, because
 * that is where the quality is. The difference is not marginal on the images this app exists for: a
 * light network decides "person or not person" well and loses every strand of hair at the edge,
 * while these were trained at high resolution specifically to keep it.
 *
 * They are large. An int8 RMBG-2.0 is around 366 MB and a half-precision BiRefNet around 490 MB, and
 * that memory is **native** rather than on the Java heap — so it sits outside the raster budget
 * entirely and is bounded by the device's RAM instead. On a phone with 8 GB or more that is fine;
 * on a small device it will fail to allocate, which is exactly why loading is attempted rather than
 * assumed and why a failure falls back to the classical path instead of crashing.
 *
 * The session is closed after every inference for the same reason. Holding half a gigabyte resident
 * between cut-outs would make the app the first thing the system kills when anything else needs
 * memory, and the cost of reloading is a second on a task the user does deliberately.
 */
class OnnxSegmentation(
    private val file: File,
    override val inputSize: Int = DEFAULT_INPUT,
) : SegmentationModel {

    override val name: String = file.nameWithoutExtension

    override fun infer(pixels: IntArray, width: Int, height: Int): SegmentationModel.Mask? {
        if (!file.isFile || file.length() < MIN_MODEL_BYTES) return null

        var environment: OrtEnvironment? = null
        var session: OrtSession? = null
        return try {
            environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                // Every core. This is a one-shot operation the user is waiting on, not a background
                // task sharing the device with a render.
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors())
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                // NNAPI where the device has it. Requested rather than required: a driver that
                // rejects an operator makes the whole session fail, so a refusal has to leave the
                // CPU path standing.
                runCatching { addNnapi() }
            }
            session = environment.createSession(file.absolutePath, options)

            val inputName = session.inputNames.firstOrNull() ?: return null
            val tensor = OnnxTensor.createTensor(environment, letterbox(pixels, width, height), SHAPE)
            val output = tensor.use { session.run(mapOf(inputName to it)) }

            output.use { result ->
                val raw = result[0].value
                maskFrom(raw)
            }
        } catch (error: Throwable) {
            // Everything: a missing operator, a native allocation failure, a file that is not a
            // model. All of them happen on a user's device and none of them should be worse than
            // not having installed the model at all.
            null
        } finally {
            runCatching { session?.close() }
        }
    }

    /**
     * Scales the image into the network's square input, keeping its proportions.
     *
     * Letterboxed rather than stretched. Every one of these networks was trained on
     * aspect-preserved crops, and feeding a stretched portrait shifts every feature away from the
     * proportions it learnt — the mask comes back plausible and subtly wrong, which is worse than
     * an obvious failure.
     *
     * Normalised with the ImageNet statistics, because BiRefNet and everything derived from it was
     * trained that way. Skipping the normalisation produces a mask that looks like a heavy vignette.
     */
    internal fun letterbox(pixels: IntArray, width: Int, height: Int): FloatBuffer {
        val buffer = FloatBuffer.allocate(3 * inputSize * inputSize)
        val scale = minOf(inputSize.toFloat() / width, inputSize.toFloat() / height)
        val drawnWidth = (width * scale).toInt().coerceAtLeast(1)
        val drawnHeight = (height * scale).toInt().coerceAtLeast(1)
        val offsetX = (inputSize - drawnWidth) / 2
        val offsetY = (inputSize - drawnHeight) / 2

        // Planar, not interleaved: ONNX wants NCHW, and writing RGB triples into an NCHW tensor
        // produces a mask that looks like the image was passed through a prism.
        val plane = inputSize * inputSize
        for (y in 0 until inputSize) {
            for (x in 0 until inputSize) {
                val at = y * inputSize + x
                val sx = x - offsetX
                val sy = y - offsetY
                if (sx < 0 || sy < 0 || sx >= drawnWidth || sy >= drawnHeight) {
                    // The padding is the normalised mid-grey, not zero. Zero is *black* after
                    // normalisation and the network reads a black border as a real dark region,
                    // which drags the mask outward at the edges.
                    buffer.put(at, PAD_R)
                    buffer.put(plane + at, PAD_G)
                    buffer.put(2 * plane + at, PAD_B)
                    continue
                }
                val source = pixels[
                    (sy / scale).toInt().coerceIn(0, height - 1) * width +
                        (sx / scale).toInt().coerceIn(0, width - 1),
                ]
                buffer.put(at, (((source shr 16) and 0xFF) / FULL - MEAN_R) / STD_R)
                buffer.put(plane + at, (((source shr 8) and 0xFF) / FULL - MEAN_G) / STD_G)
                buffer.put(2 * plane + at, ((source and 0xFF) / FULL - MEAN_B) / STD_B)
            }
        }
        return buffer
    }

    /**
     * Reads the network's output, whatever shape it came in.
     *
     * These models disagree about their output: some emit one tensor, some a list whose first entry
     * is the finest scale, some raw logits and some values already through a sigmoid. Rather than
     * hard-coding one family's convention, the values are inspected — anything outside 0..1 is
     * treated as a logit — which is what lets one class read BiRefNet, RMBG and their many forks.
     */
    private fun maskFrom(raw: Any?): SegmentationModel.Mask? {
        val flat = flatten(raw) ?: return null
        val side = kotlin.math.sqrt(flat.size.toDouble()).toInt()
        if (side * side != flat.size) return null

        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        for (v in flat) {
            if (v < min) min = v
            if (v > max) max = v
        }
        val needsSigmoid = min < 0f || max > 1f

        val confidence = ByteArray(flat.size)
        for (i in flat.indices) {
            val value = if (needsSigmoid) sigmoid(flat[i]) else flat[i]
            confidence[i] = (value.coerceIn(0f, 1f) * FULL + 0.5f).toInt().toByte()
        }
        return SegmentationModel.Mask(side, side, confidence)
    }

    /** Walks whatever nesting the model used down to the floats. */
    private fun flatten(value: Any?): FloatArray? = when (value) {
        is FloatArray -> value
        is Array<*> -> value.firstOrNull()?.let { flatten(it) }
        else -> null
    }

    private fun sigmoid(x: Float) = 1f / (1f + kotlin.math.exp(-x))

    companion object {
        /**
         * The resolution BiRefNet and RMBG-2.0 were trained at.
         *
         * Not a performance knob. Running one of these at a smaller size does not merely cost
         * accuracy, it costs the *thing they were built for* — high-resolution edge detail — and at
         * 512 they are no better than a model a twentieth of the size.
         */
        const val DEFAULT_INPUT = 1024

        /** Anything smaller is not one of these networks, whatever its extension says. */
        private const val MIN_MODEL_BYTES = 1024L * 1024L

        private const val FULL = 255f

        // ImageNet, which is what every model in this family was trained against.
        private const val MEAN_R = 0.485f
        private const val MEAN_G = 0.456f
        private const val MEAN_B = 0.406f
        private const val STD_R = 0.229f
        private const val STD_G = 0.224f
        private const val STD_B = 0.225f

        private val PAD_R = (0.5f - MEAN_R) / STD_R
        private val PAD_G = (0.5f - MEAN_G) / STD_G
        private val PAD_B = (0.5f - MEAN_B) / STD_B

        private val SHAPE = longArrayOf(1, 3, DEFAULT_INPUT.toLong(), DEFAULT_INPUT.toLong())

        /**
         * The largest model in the folder, or none.
         *
         * Largest rather than first, and that is the whole selection policy: within this family
         * size tracks capability almost exactly, so a user who has dropped in both a 25 MB mobile
         * export and a 366 MB professional one wants the second — otherwise the file they went to
         * the trouble of downloading is the one being ignored.
         */
        fun bestIn(directory: File?): OnnxSegmentation? {
            val candidates = directory
                ?.listFiles { file -> file.isFile && file.extension.lowercase() == "onnx" }
                ?.filter { it.length() >= MIN_MODEL_BYTES }
                .orEmpty()
            val chosen = candidates.maxByOrNull { it.length() } ?: return null
            return try {
                OnnxSegmentation(chosen)
            } catch (missing: LinkageError) {
                // The inference runtime is 28 MB of native code that does nothing at all until
                // someone supplies a model, so the default build leaves it out — see the packaging
                // block in `app/android/build.gradle.kts`. A user who *has* dropped a model in gets
                // the classical path and a working app rather than a crash; the build that includes
                // the runtime is one property away.
                null
            }
        }
    }
}
