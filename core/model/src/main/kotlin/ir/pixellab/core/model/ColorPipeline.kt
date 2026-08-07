package ir.pixellab.core.model

import kotlinx.serialization.Serializable
import kotlin.math.pow

/**
 * Colour space a document is authored and stored in.
 *
 * Kept explicit rather than assumed, because an image imported from a modern phone camera is
 * Display P3 and treating it as sRGB silently desaturates it. The renderer converts everything to
 * the document's working space on import and tags the export with it.
 */
@Serializable
enum class WorkingSpace(val displayName: String) {
    SRGB("sRGB"),
    DISPLAY_P3("Display P3"),
    ;

    /** Chromaticity primaries as (xr, yr, xg, yg, xb, yb), for building conversion matrices. */
    val primaries: FloatArray
        get() = when (this) {
            SRGB -> floatArrayOf(0.640f, 0.330f, 0.300f, 0.600f, 0.150f, 0.060f)
            DISPLAY_P3 -> floatArrayOf(0.680f, 0.320f, 0.265f, 0.690f, 0.150f, 0.060f)
        }
}

/**
 * Storage precision for layer and composite buffers.
 *
 * Eight bits per channel is enough for a finished image but not for the middle of a stack. A style
 * from the reference files runs a gradient through ten stacked shadows and a bevel; each pass
 * quantises, and the banding that survives to the final image is not recoverable. Half float costs
 * twice the memory and removes the problem.
 */
@Serializable
enum class Precision(val bitsPerChannel: Int, val bytesPerPixel: Int) {
    /** Legacy path for very large canvases on constrained devices. */
    U8(8, 4),

    /** Default. RGBA16F, the working precision of every professional compositor. */
    F16(16, 8),

    /** Reserved for HDR work; not used by the current pipeline. */
    F32(32, 16),
}

/**
 * Which transfer curve blending happens under.
 *
 * This is the single most consequential decision in the whole renderer and it cannot be changed
 * later without every stored document shifting appearance.
 *
 * - [LINEAR] is physically correct. Glows add like light, gradients do not band, and a 50% grey
 *   really is half the photons. Every 3D renderer and film compositor works this way.
 * - [PERCEPTUAL] blends on the gamma-encoded values, which is what Photoshop does by default. Its
 *   results are "wrong" but they are what designers have calibrated their eyes to, and it is the
 *   only way an imported PSD composites identically.
 *
 * The pipeline supports both because both are needed: [LINEAR] for new work and for anything
 * involving light, [PERCEPTUAL] for PSD fidelity.
 */
@Serializable
enum class BlendSpace { LINEAR, PERCEPTUAL }

@Serializable
data class ColorSettings(
    val workingSpace: WorkingSpace = WorkingSpace.SRGB,
    val precision: Precision = Precision.F16,
    val blendSpace: BlendSpace = BlendSpace.LINEAR,
    /** Ordered dithering on the final 8-bit conversion; removes banding in wide smooth ramps. */
    val exportDither: Boolean = true,
) {
    /** Bytes for one full-canvas buffer, used to budget how many offscreen passes fit in memory. */
    fun bufferBytes(canvas: CanvasSpec): Long =
        canvas.width.toLong() * canvas.height.toLong() * precision.bytesPerPixel
}

/**
 * sRGB transfer functions.
 *
 * Present in the model rather than only in a shader because import, export and colour pickers all
 * need the same conversion, and two implementations of a transfer curve drift.
 */
object Transfer {

    /** Gamma-encoded 0..1 to linear light. */
    fun toLinear(v: Float): Float = when {
        v <= 0.04045f -> v / 12.92f
        else -> ((v + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    }

    /** Linear light to gamma-encoded 0..1. */
    fun toGamma(v: Float): Float = when {
        v <= 0.0031308f -> v * 12.92f
        else -> 1.055f * v.toDouble().pow(1.0 / 2.4).toFloat() - 0.055f
    }

    fun toLinear(c: Color) = Color(toLinear(c.r), toLinear(c.g), toLinear(c.b), c.a)

    fun toGamma(c: Color) = Color(toGamma(c.r), toGamma(c.g), toGamma(c.b), c.a)
}
