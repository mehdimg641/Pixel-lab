package ir.pixellab.core.text

import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.TextWarp
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.model.WarpStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Photoshop's Warp Text, as a coordinate map.
 *
 * A function from a point in the text's own box to where that point ends up. Expressing it this way
 * rather than as an operation on glyphs is what lets it be applied to the shaped *outline* — which
 * is the difference between the letters bending and the letters staying upright while their
 * baseline bends. The second is what every naive warp produces, and it looks like a row of flags.
 *
 * All fifteen styles, and they are not arbitrary: each is a specific pair of horizontal and vertical
 * displacement curves, and using one curve for all of them collapses Arc, Flag and Fish into the
 * same shape.
 */
object TextWarper {

    /**
     * Maps one point.
     *
     * @param point in the text's own coordinates
     * @param bounds the text's extent, which is what the normalised parameters are measured against
     */
    fun map(point: Vec2, bounds: Rect, warp: TextWarp): Vec2 {
        if (!warp.isActive) return point
        if (bounds.width <= 0f || bounds.height <= 0f) return point

        // Normalised to -1..1 across the box, so a warp behaves the same on a word and a headline.
        val u = ((point.x - bounds.left) / bounds.width) * 2f - 1f
        val v = ((point.y - bounds.top) / bounds.height) * 2f - 1f
        val (du, dv) = displacement(if (warp.horizontalAxis) u else v, if (warp.horizontalAxis) v else u, warp)

        val shiftedU = if (warp.horizontalAxis) u + du else u + dv
        val shiftedV = if (warp.horizontalAxis) v + dv else v + du

        // Photoshop's own horizontal and vertical distortion, applied after the style. They are a
        // perspective-style taper rather than a second warp, which is why they compose with every
        // style rather than replacing it.
        val taperedU = shiftedU * (1f + warp.horizontal * shiftedV)
        val taperedV = shiftedV * (1f + warp.vertical * shiftedU)

        return Vec2(
            bounds.left + (taperedU + 1f) / 2f * bounds.width,
            bounds.top + (taperedV + 1f) / 2f * bounds.height,
        )
    }

    /**
     * The displacement for one style, in normalised space.
     *
     * @param along the coordinate the warp runs along, -1..1
     * @param across the perpendicular one
     * @return the shift along each axis
     */
    private fun displacement(along: Float, across: Float, warp: TextWarp): Pair<Float, Float> {
        val bend = warp.bend
        return when (warp.style) {
            WarpStyle.NONE -> 0f to 0f

            // A single arc: the whole line bows, and the bow is strongest at the middle.
            WarpStyle.ARC -> 0f to bend * (1f - along * along) * (across + 1f) / 2f * ARC_REACH

            // Only the bottom edge bows; the top stays flat, which is what "lower" means.
            WarpStyle.ARC_LOWER -> 0f to bend * (1f - along * along) * ((across + 1f) / 2f) * ARC_REACH

            WarpStyle.ARC_UPPER -> 0f to bend * (1f - along * along) * ((1f - across) / 2f) * -ARC_REACH

            // Both edges bow the same way, so the text keeps its thickness through the curve.
            WarpStyle.ARCH -> 0f to bend * (1f - along * along) * ARC_REACH

            // Both edges bow *apart*, so the middle is fatter than the ends.
            WarpStyle.BULGE -> 0f to bend * (1f - along * along) * across * ARC_REACH

            WarpStyle.SHELL_LOWER -> 0f to bend * along.let { it * it } * ((across + 1f) / 2f) * ARC_REACH
            WarpStyle.SHELL_UPPER -> 0f to bend * along.let { it * it } * ((1f - across) / 2f) * -ARC_REACH

            // A flag ripples along its length and the ripple travels with the far edge.
            WarpStyle.FLAG -> 0f to bend * sin(along * PI).toFloat() * WAVE_REACH

            // A wave is two frequencies, which is what stops it reading as a plain sine.
            WarpStyle.WAVE -> 0f to bend * (sin(along * PI) + sin(along * PI * 2) / 2f).toFloat() * WAVE_REACH / 2f

            // A fish is fat in the middle and pointed at both ends.
            WarpStyle.FISH -> 0f to bend * (1f - abs(along)) * across * ARC_REACH

            // A rise tilts: the far end lifts and the near end stays.
            WarpStyle.RISE -> 0f to bend * (along + 1f) / 2f * ARC_REACH

            // A fisheye pushes outwards from the centre in both directions.
            WarpStyle.FISHEYE -> {
                val radius = kotlin.math.sqrt(along * along + across * across).coerceAtMost(1f)
                val push = bend * (1f - radius * radius) * FISHEYE_REACH
                (along * push) to (across * push)
            }

            // Inflate pushes the edges out and leaves the middle alone.
            WarpStyle.INFLATE -> 0f to bend * across * abs(across) * ARC_REACH

            // Squeeze is inflate's opposite: the middle narrows.
            WarpStyle.SQUEEZE -> 0f to -bend * across * (1f - abs(along)) * ARC_REACH

            // A twist rotates by an amount that grows along the run.
            WarpStyle.TWIST -> {
                val angle = bend * along * PI.toFloat() * TWIST_REACH
                val rotatedAlong = along * cos(angle) - across * sin(angle)
                val rotatedAcross = along * sin(angle) + across * cos(angle)
                (rotatedAlong - along) to (rotatedAcross - across)
            }
        }
    }

    /**
     * How far a warped outline can travel outside its original box.
     *
     * The renderer needs this before it rasterises: a warp that reaches past the texture is clipped,
     * and the clip appears as a flat edge across the middle of a letter. Measured by mapping the box
     * itself rather than by a formula per style, so a style added later cannot forget to declare it.
     */
    fun expandedBounds(bounds: Rect, warp: TextWarp): Rect {
        if (!warp.isActive) return bounds
        var box: Rect? = null
        for (i in 0..SAMPLES) {
            for (j in 0..SAMPLES) {
                val point = Vec2(
                    bounds.left + bounds.width * i / SAMPLES,
                    bounds.top + bounds.height * j / SAMPLES,
                )
                val mapped = map(point, bounds, warp)
                val at = Rect(mapped.x, mapped.y, mapped.x, mapped.y)
                box = box?.union(at) ?: at
            }
        }
        return box ?: bounds
    }

    /** How far a warp can push, as a fraction of the box. Photoshop's slider tops out here too. */
    private const val ARC_REACH = 0.8f
    private const val WAVE_REACH = 0.6f
    private const val FISHEYE_REACH = 0.5f
    private const val TWIST_REACH = 0.5f

    /** A grid this fine catches the extremes of every style without measuring the outline itself. */
    private const val SAMPLES = 12
}
