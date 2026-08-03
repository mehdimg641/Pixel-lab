package ir.pixellab.core.model

import kotlin.math.ln
import kotlin.math.pow

/**
 * How a list of gradient stops becomes a colour.
 *
 * This lives beside the model rather than inside the raster engine because it is not a raster
 * concern: it is the meaning of a `Fill.Gradient`, and two unrelated renderers need it. The 2D
 * engine bakes it into a lookup table for its shaders; the 3D one samples it per fragment across a
 * letter's face, where a 256-entry table would band across a poster-sized headline.
 *
 * Duplicating it in both was the alternative and it is the kind of duplication that goes wrong
 * quietly — a gradient authored in one and rendered by the other would drift by a midpoint, which
 * reads as "the 3D text does not match the layer behind it" long before anyone suspects the ramp.
 */
object Ramp {

    /**
     * Colour of a stop list at [t], honouring each pair's midpoint.
     *
     * Photoshop puts a diamond between every pair of stops saying where the halfway blend falls.
     * Ignoring it is the obvious implementation, and it shifts every non-default gradient in an
     * imported file.
     */
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
     * Where along a gradient a point in normalised 0..1 space falls.
     *
     * Normalised rather than in pixels so the same gradient describes a caption and a poster
     * headline, and so a 3D letter can hand it object-space coordinates without either side knowing
     * what units the other is using.
     */
    fun parameterAt(gradient: Fill.Gradient, x: Float, y: Float): Float {
        val cx = x - HALF - gradient.offset.x
        val cy = y - HALF - gradient.offset.y
        val scale = if (gradient.scale <= 0f) 1f else gradient.scale

        val radians = gradient.angle * DEG_TO_RAD
        val cos = kotlin.math.cos(radians)
        val sin = kotlin.math.sin(radians)

        val raw = when (gradient.type) {
            // Projected onto the gradient's axis, then shifted so the ramp spans the layer rather
            // than starting at its middle.
            GradientType.LINEAR -> (cx * cos + cy * sin) / scale + HALF
            GradientType.RADIAL -> kotlin.math.hypot(cx, cy) * 2f / scale
            GradientType.REFLECTED -> kotlin.math.abs(cx * cos + cy * sin) * 2f / scale
            GradientType.DIAMOND -> (kotlin.math.abs(cx) + kotlin.math.abs(cy)) * 2f / scale
            // A full turn maps to the whole ramp, measured from the gradient's own angle so that
            // turning the gradient turns the sweep rather than only its start.
            GradientType.ANGULAR -> {
                val theta = kotlin.math.atan2(cy, cx) - radians
                val turns = theta / TAU
                turns - kotlin.math.floor(turns)
            }
        }

        val flipped = if (gradient.reverse) 1f - raw else raw
        return gradient.interpolation.evaluate(flipped.coerceIn(0f, 1f))
    }

    /** Colour of a gradient at a point in normalised 0..1 space. */
    fun colorAt(gradient: Fill.Gradient, x: Float, y: Float): Color =
        colorAt(gradient.stops.sortedBy { it.position }, parameterAt(gradient, x, y))

    /**
     * A midpoint at either extreme makes the exponent infinite.
     *
     * Photoshop's own slider stops short of the ends for the same reason; clamping here means a
     * hand-edited or imported file cannot produce a ramp of NaNs.
     */
    private const val MIDPOINT_LIMIT = 0.01f
    private const val HALF = 0.5f
    private const val DEG_TO_RAD = 0.017453292f
    private const val TAU = 6.2831855f
}
