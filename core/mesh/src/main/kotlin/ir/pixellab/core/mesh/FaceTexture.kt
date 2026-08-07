package ir.pixellab.core.mesh

import ir.pixellab.core.model.Color
import kotlin.math.floor

/**
 * A picture painted across the face of an extruded letter.
 *
 * **This is the join between the two ways this application makes a title.** The layer-effect path
 * can put a painted texture inside a letter but its depth is a stack of offset copies — no
 * perspective, no lit walls, every letter seen from the same angle. The mesh path has real geometry
 * and could only paint its face with a gradient. A commercial title of this kind needs both at once:
 * a photographed or painted face *inside* real extruded metal. Neither half alone gets there, and
 * the gap between them was the largest single reason a render came out looking like an imitation.
 *
 * Held here rather than as a `Fill.Pattern` for the same reason [LatLong] is not an `AssetId`: this
 * module has no asset store and must not grow one. The caller resolves the pattern to pixels — see
 * `TextTo3D` — and hands the result in. That keeps `core:mesh` free of Android and of the document
 * model's indirection, which is the property that lets the whole renderer be tested without a
 * device.
 *
 * @param repeats how many times the tile covers the letter's own bounds. A tile is authored square
 *   and a word is wide, so mapping one tile across the whole word would stretch every brush stroke
 *   into a smear; repeating instead keeps the paint at its own scale, which is the difference
 *   between visible brushwork and camouflage.
 */
class FaceTexture(
    /** Straight ARGB, row-major, as every decoder in this project produces. */
    private val pixels: IntArray,
    private val width: Int,
    private val height: Int,
    private val repeats: Float = 1f,
    /** Turns the paint on the letter, so the strokes need not run with the baseline. */
    private val rotation: Float = 0f,
) {
    init {
        require(width > 0 && height > 0) { "a face texture needs positive dimensions" }
        require(pixels.size >= width * height) { "texture pixels do not match ${width}x$height" }
        require(repeats > 0f) { "repeats must be positive, got $repeats" }
    }

    private val radians = Math.toRadians(rotation.toDouble())
    private val cos = kotlin.math.cos(radians).toFloat()
    private val sin = kotlin.math.sin(radians).toFloat()

    /**
     * Samples at a point in the letter's own normalised space.
     *
     * Bilinear and wrapping. Wrapping rather than clamping because the tile repeats: clamping would
     * smear the tile's last row across everything past one repeat, which is visible as a streak
     * running off the right of the word — and it is exactly the artefact that makes a tiled texture
     * look like a mistake rather than like a material.
     */
    fun at(u: Float, v: Float): Color {
        // Rotated about the middle, so turning the paint does not also slide it off the letter.
        val cu = (u - HALF) * repeats
        val cv = (v - HALF) * repeats
        val ru = cu * cos - cv * sin + HALF
        val rv = cu * sin + cv * cos + HALF

        val x = wrap(ru) * width - HALF
        val y = wrap(rv) * height - HALF
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = x - x0
        val fy = y - y0

        val c00 = texel(x0, y0)
        val c10 = texel(x0 + 1, y0)
        val c01 = texel(x0, y0 + 1)
        val c11 = texel(x0 + 1, y0 + 1)

        return Color(
            r = mix(mix(c00.r, c10.r, fx), mix(c01.r, c11.r, fx), fy),
            g = mix(mix(c00.g, c10.g, fx), mix(c01.g, c11.g, fx), fy),
            b = mix(mix(c00.b, c10.b, fx), mix(c01.b, c11.b, fx), fy),
        )
    }

    private fun texel(x: Int, y: Int): Color {
        val sx = ((x % width) + width) % width
        val sy = ((y % height) + height) % height
        val p = pixels[sy * width + sx]
        return Color(
            ((p shr 16) and 0xFF) / MAX_CHANNEL,
            ((p shr 8) and 0xFF) / MAX_CHANNEL,
            (p and 0xFF) / MAX_CHANNEL,
        )
    }

    private fun wrap(t: Float): Float {
        val f = t - floor(t)
        return if (f < 0f) f + 1f else f
    }

    private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

    private companion object {
        const val HALF = 0.5f
        const val MAX_CHANNEL = 255f
    }
}
