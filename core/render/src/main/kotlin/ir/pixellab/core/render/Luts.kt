package ir.pixellab.core.render

import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Curve
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.GradientStop
import ir.pixellab.core.model.Ramp

/**
 * Sampled tables the shaders read as one-dimensional textures.
 *
 * Evaluating a curve per pixel is possible but wasteful — a bevel samples its profile four times per
 * fragment to build a normal — and a gradient with a dozen stops would branch in the inner loop.
 * Both collapse to a texture lookup, and both are pure functions of the model, so the arithmetic
 * that has to match Photoshop is verified here rather than by eye on a device.
 */
object Luts {

    /** 256 is what Photoshop's own contour editor stores, and the difference above it is invisible. */
    const val DEFAULT_SIZE = 256

    /** Samples a contour into a single-channel table. */
    fun curve(curve: Curve, size: Int = DEFAULT_SIZE): FloatArray {
        require(size >= 2) { "a lookup table needs at least two samples, got $size" }
        return FloatArray(size) { i -> curve.evaluate(i.toFloat() / (size - 1)) }
    }

    /**
     * Samples a gradient into a straight (non-premultiplied) ARGB ramp.
     *
     * Three details decide whether an imported gradient matches the file it came from:
     *
     * - **Midpoints.** Photoshop puts a diamond between every pair of stops saying where the 50%
     *   blend falls. Ignoring it is the obvious implementation and it shifts every non-default
     *   gradient in a PSD.
     * - **Interpolation happens in straight alpha**, then the shader premultiplies. Interpolating
     *   premultiplied values darkens the ramp wherever a stop is transparent.
     * - **Reverse flips the ramp, not the stops**, so the midpoints travel with it.
     */
    fun gradient(gradient: Fill.Gradient, size: Int = DEFAULT_SIZE): IntArray {
        require(size >= 2) { "a gradient ramp needs at least two samples, got $size" }
        val stops = gradient.stops.sortedBy { it.position }
        return IntArray(size) { i ->
            val raw = i.toFloat() / (size - 1)
            val flipped = if (gradient.reverse) 1f - raw else raw
            // The interpolation curve shapes the whole ramp before the stops are consulted, which
            // is what turns a linear blend into an eased one without moving any stop.
            colorAt(stops, gradient.interpolation.evaluate(flipped)).toArgb()
        }
    }

    /**
     * The stop ramp, delegated to the model.
     *
     * It moved there because the 3D renderer needs the same arithmetic and lives in a sibling
     * module. Kept exposed here so the existing callers and their tests do not have to care where
     * it went — the point of the move was to have one implementation, not to relocate a name.
     */
    fun colorAt(stops: List<GradientStop>, t: Float): Color = Ramp.colorAt(stops, t)

    fun biased(t: Float, midpoint: Float): Float = Ramp.biased(t, midpoint)

    fun lerp(a: Color, b: Color, t: Float): Color = Ramp.lerp(a, b, t)

}

/** Packs to 0xAARRGGBB, the layout Android bitmaps and `glTexImage2D` both expect. */
fun Color.toArgb(): Int {
    fun byte(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return (byte(a) shl 24) or (byte(r) shl 16) or (byte(g) shl 8) or byte(b)
}

fun colorFromArgb(argb: Int) = Color(
    r = ((argb shr 16) and 0xFF) / 255f,
    g = ((argb shr 8) and 0xFF) / 255f,
    b = (argb and 0xFF) / 255f,
    a = ((argb ushr 24) and 0xFF) / 255f,
)
