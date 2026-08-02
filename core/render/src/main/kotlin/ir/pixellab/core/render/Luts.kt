package ir.pixellab.core.render

import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Curve
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.GradientStop
import kotlin.math.ln
import kotlin.math.pow

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

    /** Colour of a stop list at [t], honouring each pair's midpoint. */
    fun colorAt(stops: List<GradientStop>, t: Float): Color {
        if (stops.isEmpty()) return Color.TRANSPARENT
        if (stops.size == 1) return stops.first().color
        val clamped = t.coerceIn(0f, 1f)
        if (clamped <= stops.first().position) return stops.first().color
        if (clamped >= stops.last().position) return stops.last().color

        for (i in 0 until stops.size - 1) {
            val a = stops[i]
            val b = stops[i + 1]
            if (clamped < a.position || clamped > b.position) continue
            val span = b.position - a.position
            val local = if (span <= 0f) 1f else (clamped - a.position) / span
            return lerp(a.color, b.color, biased(local, a.midpoint))
        }
        return stops.last().color
    }

    /**
     * Bends 0..1 so that [midpoint] maps to exactly 0.5.
     *
     * The exponent that does it is `log(0.5) / log(midpoint)` — at `t = midpoint` the result is
     * `midpoint^(log 0.5 / log midpoint) = 0.5` by construction. A midpoint of 0.5 leaves the
     * parameter untouched, so the common case costs nothing.
     */
    fun biased(t: Float, midpoint: Float): Float {
        val m = midpoint.coerceIn(MIDPOINT_LIMIT, 1f - MIDPOINT_LIMIT)
        if (m == 0.5f) return t
        val exponent = ln(0.5) / ln(m.toDouble())
        return t.toDouble().pow(exponent).toFloat()
    }

    fun lerp(a: Color, b: Color, t: Float) = Color(
        a.r + (b.r - a.r) * t,
        a.g + (b.g - a.g) * t,
        a.b + (b.b - a.b) * t,
        a.a + (b.a - a.a) * t,
    )

    /**
     * A midpoint at either extreme makes the exponent infinite.
     *
     * Photoshop's own slider stops short of the ends for the same reason; clamping here means a
     * hand-edited or imported file cannot produce a ramp of NaNs.
     */
    private const val MIDPOINT_LIMIT = 0.01f
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
