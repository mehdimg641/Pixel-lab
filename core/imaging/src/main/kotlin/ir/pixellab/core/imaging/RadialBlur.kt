package ir.pixellab.core.imaging

import ir.pixellab.core.model.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Radial blur: spin and zoom.
 *
 * These are one filter, not two, and Photoshop is right to put them behind a single dialog with a
 * Method switch. Both smear each pixel along the path it would travel under a one-parameter family
 * of transforms about a centre — a rotation for spin, a scale for zoom — and everything that decides
 * whether the result looks like a photograph or like a mistake (how many samples, how they are
 * spaced, how the edges behave) is shared. Writing them separately means fixing the sampling twice
 * and getting two different answers.
 *
 * Sampling is the part that matters. A fixed sample count is what produces the ghosted, banded
 * output that gives cheap radial blur away: at the centre of the image the displacement is nothing
 * and eight samples are plenty, while at the far corner the same eight land pixels apart and read as
 * eight copies of the image. The count here is derived per pixel from how far that pixel actually
 * travels, so the cost goes where the blur is.
 */
object RadialBlur {

    enum class Kind {
        /** Rotation about the centre — Photoshop's Spin. */
        SPIN,

        /** Scaling towards the centre — Photoshop's Zoom. */
        ZOOM,
        ;

        val persianLabel: String get() = if (this == SPIN) "چرخشی" else "زوم"
    }

    /**
     * @param centre in pixels, so the caller can put it under the subject's eye rather than at the
     *   middle of the frame — which is the difference between a photograph and a special effect.
     * @param amount 0..1. For spin, a full turn of the slider is [MAX_DEGREES] degrees of rotation;
     *   for zoom it is [MAX_SCALE] of the distance to the centre.
     */
    fun apply(src: Raster, centre: Vec2, amount: Float, kind: Kind): Raster {
        val strength = amount.coerceIn(0f, 1f)
        if (strength <= 0f) return src.copy()

        val out = Raster(src.width, src.height, src.channels)
        val channels = src.channels
        // The trail is walked from the pixel backwards only, not symmetrically about it: a smear
        // centred on the pixel moves the subject half a trail towards the centre, and a face blurred
        // that way comes out visibly shifted.
        val span = if (kind == Kind.SPIN) strength * MAX_DEGREES * (PI / 180f).toFloat() else strength * MAX_SCALE

        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                val dx = x - centre.x
                val dy = y - centre.y
                val radius = hypot(dx, dy)
                // How far this pixel travels along the whole trail, in pixels — arc length for a
                // spin, change in radius for a zoom. The two coincide because [span] is an angle in
                // radians in one case and a fraction of the radius in the other, which is the same
                // reason one sampler covers both.
                val steps = samplesFor(radius * span)

                if (steps <= 1) {
                    for (c in 0 until channels) out[x, y, c] = src[x, y, c]
                    continue
                }

                for (c in 0 until channels) {
                    var acc = 0f
                    for (s in 0 until steps) {
                        val t = s.toFloat() / (steps - 1)
                        val at = when (kind) {
                            Kind.SPIN -> {
                                val angle = -span * t
                                val cosA = cos(angle)
                                val sinA = sin(angle)
                                Vec2(
                                    centre.x + dx * cosA - dy * sinA,
                                    centre.y + dx * sinA + dy * cosA,
                                )
                            }
                            // Towards the centre, so the trail streams outwards from it — the
                            // direction light behaves in every photograph this imitates.
                            Kind.ZOOM -> {
                                val scale = 1f - span * t
                                Vec2(centre.x + dx * scale, centre.y + dy * scale)
                            }
                        }
                        acc += bilinear(src, at.x, at.y, c)
                    }
                    out[x, y, c] = acc / steps
                }
            }
        }
        return out
    }

    /**
     * One sample per pixel of travel, bounded at both ends.
     *
     * The lower bound stops the very centre of the image from being copied through unfiltered while
     * its neighbours are blurred, which shows up as a hard disc. The upper bound is what keeps the
     * filter usable on a large canvas: past it the samples are closer together than the pixels they
     * read, so they cost time and change nothing.
     */
    internal fun samplesFor(travel: Float): Int =
        abs(travel).roundToInt().coerceIn(1, MAX_SAMPLES)

    private fun bilinear(src: Raster, x: Float, y: Float, c: Int): Float {
        val fx = kotlin.math.floor(x)
        val fy = kotlin.math.floor(y)
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val tx = x - fx
        val ty = y - fy
        val a = src.clamped(x0, y0, c)
        val b = src.clamped(x0 + 1, y0, c)
        val d = src.clamped(x0, y0 + 1, c)
        val e = src.clamped(x0 + 1, y0 + 1, c)
        return (a + (b - a) * tx) + ((d + (e - d) * tx) - (a + (b - a) * tx)) * ty
    }

    /** A full turn of the slider gives a fifth of a revolution, which is already a strong effect. */
    private const val MAX_DEGREES = 72f

    /** And 40% of the way to the centre, which streaks a portrait without dissolving it. */
    private const val MAX_SCALE = 0.4f

    /** Beyond this the samples overlap within a pixel and only cost time. */
    private const val MAX_SAMPLES = 96
}
