package ir.pixellab.core.imaging

import kotlin.math.hypot
import kotlin.math.roundToInt
import ir.pixellab.core.model.Vec2

/**
 * Path blur: motion that follows a drawn line rather than a single direction.
 *
 * ### Why this is not just a directional blur
 *
 * A directional blur smears every pixel along the same vector, which is right for a photograph
 * taken from a moving train and wrong for almost everything else. A car turning, a hand swinging, a
 * background whipping past a subject standing still — in all of those the direction of travel
 * *varies across the frame*, and one vector cannot express that.
 *
 * So the input is a path. Every pixel is smeared along the direction of travel at its own location,
 * interpolated from the strokes by inverse-square distance weighting. Near a stroke the smear runs
 * parallel to it; between two strokes running opposite ways the influences cancel and the pixel is
 * left alone, which is a thing no single vector can do either.
 *
 * ### Two decisions that tests forced, and the reasoning that was wrong before them
 *
 * **Distance has to scale the speed directly, not through the weights.** The first version derived
 * speed as a weighted average over the strokes. With one stroke on the canvas that average is the
 * stroke's own speed *at every pixel in the frame* — the weights cancel in the normalisation — so
 * an impulse a hundred pixels away blurred exactly as hard as one lying on the line. [reach] now
 * scales the speed by proximity to the nearest stroke, which is what actually leaves a subject
 * sharp with one stroke drawn along an edge.
 *
 * Photoshop does not do this: there, one path blurs the whole frame and a subject is protected by
 * adding a second path with a low speed over it. That is a better model with a mouse and a large
 * screen, and a worse one with a thumb, which is why [reach] defaults to finite here and can be set
 * to [Float.POSITIVE_INFINITY] for the desktop behaviour.
 *
 * **Speed varies along the path, not along its segments.** Endpoint speeds interpolated per segment
 * make a two-point path — the common case — one segment carrying one constant speed, so the slow
 * end and the fast end came out identical. Speed is now read at the pixel's own projection onto the
 * path by arc length, which is what makes a swing possible: slow at the top of the arc, fast at the
 * bottom.
 */
object PathBlur {

    /**
     * One stroke of motion.
     *
     * @param points at least two, in pixels.
     * @param startSpeed pixels of travel at the first point.
     * @param endSpeed pixels of travel at the last point. Equal to [startSpeed] for constant motion.
     */
    class Path(val points: List<Vec2>, val startSpeed: Float, val endSpeed: Float) {
        init {
            require(points.size >= 2) { "a motion path needs at least two points, got ${points.size}" }
        }

        internal val segments: List<Segment>

        /** Total arc length, so a projection onto any segment converts to a position along the whole. */
        internal val length: Float

        init {
            var travelled = 0f
            segments = buildList {
                for (i in 0 until points.size - 1) {
                    val segment = Segment(points[i], points[i + 1], travelled)
                    travelled += segment.length
                    if (segment.length > 1e-6f) add(segment)
                }
            }
            length = travelled
        }

        /** The speed at arc-length position [at], measured from the first point. */
        internal fun speedAt(at: Float): Float {
            if (length <= 1e-6f) return startSpeed
            val u = (at / length).coerceIn(0f, 1f)
            return startSpeed + (endSpeed - startSpeed) * u
        }
    }

    internal class Segment(val a: Vec2, val b: Vec2, val startsAt: Float) {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = hypot(dx, dy)
        val ux = if (length > 1e-6f) dx / length else 0f
        val uy = if (length > 1e-6f) dy / length else 0f

        /** How far along this segment [p] projects, clamped to its ends. */
        fun projection(px: Float, py: Float): Float {
            if (length <= 1e-6f) return 0f
            return (((px - a.x) * dx + (py - a.y) * dy) / (length * length)).coerceIn(0f, 1f)
        }

        fun distanceTo(px: Float, py: Float): Float {
            if (length <= 1e-6f) return hypot(px - a.x, py - a.y)
            val t = projection(px, py)
            return hypot(px - (a.x + dx * t), py - (a.y + dy * t))
        }
    }

    /**
     * @param paths one or more strokes. Several is the normal case: a path down each edge of the
     *   frame and none through the middle is how a subject is left sharp.
     * @param amount 0..1, scaling every stroke's speed. The slider the user actually holds.
     * @param reach how far a stroke's motion carries, in pixels. A pixel this far from the nearest
     *   stroke blurs at half strength. [Float.POSITIVE_INFINITY] gives the desktop behaviour, where
     *   one stroke moves the whole frame. Defaults to a quarter of the image diagonal, which is
     *   about the neighbourhood a thumb-drawn stroke reads as covering.
     */
    fun apply(
        src: Raster,
        paths: List<Path>,
        amount: Float,
        reach: Float = hypot(src.width.toFloat(), src.height.toFloat()) * 0.25f,
    ): Raster {
        val strength = amount.coerceIn(0f, 1f)
        if (strength <= 0f || paths.isEmpty()) return src.copy()
        if (paths.all { it.segments.isEmpty() }) return src.copy()

        val out = Raster(src.width, src.height, src.channels)
        val channels = src.channels
        val accumulator = FloatArray(channels)
        val reachSquared = if (reach.isInfinite()) Float.POSITIVE_INFINITY else reach * reach

        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                val px = x.toFloat()
                val py = y.toFloat()

                var wx = 0f
                var wy = 0f
                var wSpeed = 0f
                var total = 0f
                var nearest = Float.MAX_VALUE

                for (path in paths) {
                    for (segment in path.segments) {
                        val d = segment.distanceTo(px, py)
                        if (d < nearest) nearest = d
                        // Inverse-square rather than linear: linear leaves a visible influence
                        // halfway across the frame from a short stroke.
                        val w = 1f / (d * d + SOFTEN)
                        // Summed *signed*, so two strokes running opposite ways cancel where they
                        // meet rather than averaging into a diagonal that matches neither.
                        wx += segment.ux * w
                        wy += segment.uy * w
                        // Read at this pixel's own projection onto the path, by arc length.
                        wSpeed += path.speedAt(segment.startsAt + segment.length * segment.projection(px, py)) * w
                        total += w
                    }
                }
                if (total <= 0f) {
                    for (c in 0 until channels) out[x, y, c] = src[x, y, c]
                    continue
                }

                val dirLength = hypot(wx, wy)
                // The proximity term the weights cannot supply, because they normalise away.
                val proximity =
                    if (reachSquared.isInfinite()) 1f else reachSquared / (nearest * nearest + reachSquared)
                val speed = wSpeed / total * strength * proximity

                // A near-zero resultant means the influences cancelled — the pixel sits between
                // strokes pulling opposite ways, and the honest answer there is no blur at all.
                if (dirLength <= 1e-6f || speed < 0.5f) {
                    for (c in 0 until channels) out[x, y, c] = src[x, y, c]
                    continue
                }
                val ux = wx / dirLength
                val uy = wy / dirLength

                val steps = samplesFor(speed)
                accumulator.fill(0f)
                for (s in 0 until steps) {
                    // Centred on the pixel: motion blur is symmetric about where the subject was
                    // during the exposure, unlike a radial smear which trails from it.
                    val t = if (steps == 1) 0f else s.toFloat() / (steps - 1) - 0.5f
                    val sx = px + ux * speed * t
                    val sy = py + uy * speed * t
                    for (c in 0 until channels) accumulator[c] += bilinear(src, sx, sy, c)
                }
                for (c in 0 until channels) out[x, y, c] = accumulator[c] / steps
            }
        }
        return out
    }

    /**
     * One sample per pixel of travel, bounded.
     *
     * The same rule [RadialBlur] uses, and for the same reason: a fixed count bands badly where the
     * travel is long, and wastes time where it is short.
     */
    internal fun samplesFor(travel: Float): Int = travel.roundToInt().coerceIn(1, MAX_SAMPLES)

    /** Keeps a pixel lying exactly on a stroke from dividing by zero. */
    private const val SOFTEN = 1f

    private const val MAX_SAMPLES = 64
}
