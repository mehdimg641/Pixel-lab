package ir.pixellab.core.mesh

import ir.pixellab.core.model.Vec3
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2

/**
 * What a reflective surface sees when it looks in a direction.
 *
 * `LightRig.environment`, `environmentIntensity` and `environmentRotation` have been in the model
 * since wave 14, described in their own comment as "image-based lighting — chrome and polished metal
 * are unreachable without it", and read by nothing at all. The renderer synthesised a studio and
 * ignored the fields. That is the worst shape a gap can take: the document could name an
 * environment, save it, and lose it in silence.
 *
 * This is the seam. [Studio] is the generated one, still the default and still the right default —
 * the app ships no assets, and a computed studio is the reason a gold letter looks like gold on the
 * first launch. [LatLong] is the other half: a real photographed environment, when the user has one.
 */
fun interface EnvironmentMap {
    /**
     * @param direction where the surface is looking, in world space and not necessarily normalised.
     * @param roughness 0 for a mirror, 1 for fully diffuse. A real prefiltered map stores this as a
     *   mip chain; both implementations here honour it, by different means.
     */
    fun sample(direction: Vec3, roughness: Float): Vec3
}

/**
 * An equirectangular photograph of a place, which is what every `.hdr` and 360° `.jpg` holds.
 *
 * The mapping is the standard one: longitude across, latitude down. Its one subtlety is that the
 * poles are squeezed into single rows, so a naive sampler shows a pinch at the top of a sphere —
 * harmless here, because a letter's bevel reflects the horizon and the ceiling rather than the
 * zenith.
 *
 * **Roughness is a prefilter, not a fudge.** A rough metal reflects the *average of a cone* of
 * directions, and averaging at sample time would need hundreds of taps per pixel. Instead the map is
 * reduced once into a small chain of progressively blurred copies, and roughness picks between two
 * of them — which is exactly what a GPU does with a prefiltered cubemap, and for the same reason.
 */
class LatLong(
    /** Straight ARGB, row-major, as every decoder in this project produces. */
    pixels: IntArray,
    private val width: Int,
    private val height: Int,
    /** Multiplies the result. A photograph is a relative measurement; this is the exposure. */
    private val intensity: Float = 1f,
    /** Degrees, turning the world about the vertical axis so a highlight can be placed. */
    private val rotation: Float = 0f,
) : EnvironmentMap {

    init {
        require(width > 0 && height > 0) { "an environment needs positive dimensions" }
        require(pixels.size >= width * height) { "environment pixels do not match ${width}x$height" }
    }

    /**
     * The chain, coarsest last. Level zero is the image; each level is half the previous.
     *
     * Stopped at a handful of levels because past that the map is a single colour and another
     * halving buys nothing. The last level *is* the average, which is where a real prefiltered
     * reflection converges at maximum roughness.
     */
    private val levels: List<FloatArray> = buildLevels(pixels)

    private val sizes: List<Pair<Int, Int>> = buildSizes()

    override fun sample(direction: Vec3, roughness: Float): Vec3 {
        val d = direction.normalised()
        // Longitude from the horizontal components, latitude from the vertical one. The rotation is
        // applied here rather than by turning the pixels, so changing it is free.
        val longitude = atan2(d.z, d.x) + rotation * DEG_TO_RAD
        val latitude = acos(d.y.coerceIn(-1f, 1f))
        val u = ((longitude / TAU) + 0.5f).let { it - kotlin.math.floor(it) }
        val v = (latitude / PI.toFloat()).coerceIn(0f, 1f)

        // Between two levels rather than snapping to one, or a slowly roughening surface would step
        // through visible bands as it crossed each boundary.
        val depth = (roughness.coerceIn(0f, 1f) * (levels.size - 1))
        val lower = depth.toInt().coerceIn(0, levels.lastIndex)
        val upper = (lower + 1).coerceAtMost(levels.lastIndex)
        val t = depth - lower

        val a = bilinear(lower, u, v)
        val b = bilinear(upper, u, v)
        return Vec3(
            (a.x + (b.x - a.x) * t) * intensity,
            (a.y + (b.y - a.y) * t) * intensity,
            (a.z + (b.z - a.z) * t) * intensity,
        )
    }

    private fun bilinear(level: Int, u: Float, v: Float): Vec3 {
        val (w, h) = sizes[level]
        val data = levels[level]
        val x = u * w - 0.5f
        val y = v * h - 0.5f
        val x0 = kotlin.math.floor(x).toInt()
        val y0 = kotlin.math.floor(y).toInt()
        val fx = x - x0
        val fy = y - y0

        fun at(px: Int, py: Int, channel: Int): Float {
            // Wrapped in longitude and clamped in latitude, because the world joins up going round
            // and does not going over the pole. Clamping both would put a seam down the back of
            // every reflection.
            val cx = ((px % w) + w) % w
            val cy = py.coerceIn(0, h - 1)
            return data[(cy * w + cx) * 3 + channel]
        }

        val out = FloatArray(3)
        for (c in 0 until 3) {
            val top = at(x0, y0, c) * (1f - fx) + at(x0 + 1, y0, c) * fx
            val bottom = at(x0, y0 + 1, c) * (1f - fx) + at(x0 + 1, y0 + 1, c) * fx
            out[c] = top * (1f - fy) + bottom * fy
        }
        return Vec3(out[0], out[1], out[2])
    }

    private fun buildLevels(pixels: IntArray): List<FloatArray> {
        // Linear light, not the stored bytes. An environment is a measurement of radiance and gets
        // multiplied by a BRDF; averaging or reflecting gamma-encoded values makes a bright sky
        // reflect darker than it is, which is the difference between chrome and grey plastic.
        var w = width
        var h = height
        var level = FloatArray(w * h * 3)
        for (i in 0 until w * h) {
            val p = pixels[i]
            level[i * 3] = toLinear(((p shr 16) and 0xFF) / 255f)
            level[i * 3 + 1] = toLinear(((p shr 8) and 0xFF) / 255f)
            level[i * 3 + 2] = toLinear((p and 0xFF) / 255f)
        }

        val chain = ArrayList<FloatArray>()
        chain += level
        // Down to a single texel, not to two. The last level *is* the average of the whole map,
        // which is where a real prefiltered reflection converges at maximum roughness — stopping at
        // 2×1 leaves the coarsest level still holding a left half and a right half, so a fully rough
        // metal keeps reflecting a direction instead of the room's mean colour.
        while (chain.size < LEVELS && (w > 1 || h > 1)) {
            val nw = (w / 2).coerceAtLeast(1)
            val nh = (h / 2).coerceAtLeast(1)
            val next = FloatArray(nw * nh * 3)
            for (y in 0 until nh) {
                for (x in 0 until nw) {
                    for (c in 0 until 3) {
                        var sum = 0f
                        var n = 0
                        for (dy in 0..1) {
                            for (dx in 0..1) {
                                val sx = (x * 2 + dx).coerceAtMost(w - 1)
                                val sy = (y * 2 + dy).coerceAtMost(h - 1)
                                sum += level[(sy * w + sx) * 3 + c]
                                n++
                            }
                        }
                        next[(y * nw + x) * 3 + c] = sum / n
                    }
                }
            }
            chain += next
            level = next
            w = nw
            h = nh
        }
        return chain
    }

    private fun buildSizes(): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var w = width
        var h = height
        repeat(levels.size) {
            out += w to h
            w = (w / 2).coerceAtLeast(1)
            h = (h / 2).coerceAtLeast(1)
        }
        return out
    }

    private fun toLinear(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f
        else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    private companion object {
        /** Past six halvings the map is a colour, and another costs a pass for nothing. */
        const val LEVELS = 7
        const val TAU = (2.0 * PI).toFloat()
        const val DEG_TO_RAD = 0.017453292f
    }
}

/**
 * The generated studio — the default, and deliberately so.
 *
 * The app ships no asset pack, so the environment a metal reflects has to be computed, and for this
 * job that is barely a constraint: a photographic studio is a big soft source above and to one side,
 * a darker floor, and a mid-grey surround. All three are a function of the reflected direction's
 * height, plus one lobe for the softbox.
 *
 * The gradient across that height is the whole effect. A chrome letter reads as chrome because its
 * curved bevel sweeps from reflecting the bright ceiling to reflecting the dark floor within a few
 * pixels, and a single flat ambient colour cannot produce that at any intensity.
 */
object Studio : EnvironmentMap {
    override fun sample(direction: Vec3, roughness: Float) = Pbr.studio(direction, roughness)
}
