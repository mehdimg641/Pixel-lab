package ir.pixellab.core.paint

import ir.pixellab.core.model.Vec2
import kotlin.math.hypot

/**
 * One sample from the input device.
 *
 * [pressure] is 0..1 with 1 as full force. A finger reports no pressure at all on most Android
 * devices, so the default is full rather than zero — a brush that fades to nothing under a finger is
 * a brush that appears not to work.
 */
data class StrokePoint(
    val position: Vec2,
    val pressure: Float = 1f,
    /** Degrees from vertical, 0 when unknown. */
    val tilt: Float = 0f,
    /** Direction the stylus leans, in degrees. */
    val orientation: Float = 0f,
    /** Milliseconds since the stroke began, for velocity. */
    val timeMillis: Long = 0L,
)

/**
 * Smooths the path before any dab is placed.
 *
 * A "pulled string": the brush follows an anchor that is dragged behind the finger, so the path is
 * shortened through a corner rather than merely averaged. Averaging is the obvious approach and it
 * has a visible flaw — the line lags *and* still wobbles, because an average of shaky samples is a
 * smaller shake, not a smooth curve.
 *
 * The pull is done in position only. Pressure is passed through untouched: smoothing pressure makes
 * a stroke's ends thicken visibly after the finger has lifted.
 */
class StrokeStabilizer(strength: Float = 0.35f) {

    private val strength = strength.coerceIn(0f, MAX_STRENGTH)
    private var anchor: Vec2? = null

    /**
     * Feeds one raw sample and returns where the brush should actually be.
     *
     * Returns null while the anchor has not moved far enough to be worth a dab, which is what keeps
     * a resting finger from drilling a hole through a low-flow brush.
     */
    fun next(point: StrokePoint): StrokePoint? {
        val current = anchor
        if (current == null) {
            anchor = point.position
            return point
        }
        val delta = point.position - current
        val distance = hypot(delta.x, delta.y)
        if (distance <= 0f) return null

        // The anchor is dragged to sit a fixed fraction of the way behind the finger. At strength 0
        // it lands exactly on the finger, which is the unsmoothed path.
        val moved = current + delta * (1f - strength)
        anchor = moved
        return point.copy(position = moved)
    }

    fun reset() {
        anchor = null
    }

    /**
     * The final sample, so a stroke ends where the finger lifted.
     *
     * Without it every stroke stops short by the smoothing distance, and a tap makes no mark at all
     * — the two complaints that follow any smoothing implemented purely as a filter.
     */
    fun finish(last: StrokePoint): StrokePoint {
        anchor = last.position
        return last
    }

    private companion object {
        /**
         * Full strength would leave the anchor stationary.
         *
         * Capped rather than clamped at 1 so the top of the slider still draws.
         */
        const val MAX_STRENGTH = 0.95f
    }
}

/**
 * Resamples a path to evenly spaced points.
 *
 * Dabs have to be placed at equal *distances*, not at equal numbers of input samples: a device
 * reports at a fixed rate, so a fast stroke gives sparse samples and a slow one gives a pile of
 * them in the same place. Stamping the raw samples is why a fast stroke in a naive brush comes out
 * as a dotted line.
 *
 * Interpolation is Catmull-Rom through the points, which passes through every one of them — a
 * B-spline would round corners the user drew deliberately.
 */
object PathResampler {

    fun resample(points: List<StrokePoint>, step: Float, carryOver: Float = 0f): Resampled {
        if (points.isEmpty()) return Resampled(emptyList(), carryOver)
        if (points.size == 1) {
            // A tap is a stroke of one point, and it has to leave a mark.
            return if (carryOver <= 0f) Resampled(points, step) else Resampled(emptyList(), carryOver)
        }

        // The curve first, densely, then one walk along it. Placing stamps inside the curve loop is
        // the obvious shape and it cannot get the spacing right: a stamp usually falls *between*
        // two subsamples, and rounding it to the nearer one leaves gaps that vary with how fast the
        // device happened to be reporting.
        val dense = densify(points, step)
        if (dense.size < 2) return Resampled(emptyList(), carryOver)

        val out = ArrayList<StrokePoint>()
        var travelled = 0f
        var next = carryOver

        for (i in 1 until dense.size) {
            val from = dense[i - 1]
            val to = dense[i]
            val length = hypot(to.position.x - from.position.x, to.position.y - from.position.y)
            if (length <= 0f) continue

            while (travelled + length >= next) {
                val at = ((next - travelled) / length).coerceIn(0f, 1f)
                out += interpolate(from, to, at)
                next += step
            }
            travelled += length
        }
        return Resampled(out, next - travelled)
    }

    /**
     * Subdivides the path finely enough that the walk along it is straight-line accurate.
     *
     * Sampled against the step rather than at a fixed count: a long segment under a tight spacing
     * needs many subsamples, and a short one under a loose spacing needs almost none.
     */
    private fun densify(points: List<StrokePoint>, step: Float): List<StrokePoint> {
        val dense = ArrayList<StrokePoint>()
        dense += points.first()
        for (i in 0 until points.size - 1) {
            val p0 = points[(i - 1).coerceAtLeast(0)]
            val p1 = points[i]
            val p2 = points[i + 1]
            val p3 = points[(i + 2).coerceAtMost(points.size - 1)]

            val length = segmentLength(p0.position, p1.position, p2.position, p3.position)
            if (length <= 0f) continue
            val samples = ((length / step.coerceAtLeast(MIN_STEP)) * SUBSAMPLES_PER_STEP)
                .toInt()
                .coerceIn(1, MAX_SUBSAMPLES)

            for (s in 1..samples) {
                val t = s.toFloat() / samples
                dense += StrokePoint(
                    position = catmullRom(p0.position, p1.position, p2.position, p3.position, t),
                    // Interpolated, because pressure changes over the length of a segment and a
                    // stroke that took its pressure from the last raw sample steps in visible bands
                    // wherever the device reported slowly.
                    pressure = lerp(p1.pressure, p2.pressure, t),
                    tilt = lerp(p1.tilt, p2.tilt, t),
                    orientation = lerp(p1.orientation, p2.orientation, t),
                    timeMillis = p1.timeMillis + ((p2.timeMillis - p1.timeMillis) * t).toLong(),
                )
            }
        }
        return dense
    }

    private fun interpolate(from: StrokePoint, to: StrokePoint, t: Float) = StrokePoint(
        position = Vec2(
            from.position.x + (to.position.x - from.position.x) * t,
            from.position.y + (to.position.y - from.position.y) * t,
        ),
        pressure = lerp(from.pressure, to.pressure, t),
        tilt = lerp(from.tilt, to.tilt, t),
        orientation = lerp(from.orientation, to.orientation, t),
        timeMillis = from.timeMillis + ((to.timeMillis - from.timeMillis) * t).toLong(),
    )

    /** Points at even spacing, plus how far into the next step the path ended. */
    data class Resampled(val points: List<StrokePoint>, val carryOver: Float)

    /**
     * Approximate arc length of one Catmull-Rom segment.
     *
     * Chord subdivision rather than an integral: the curve is at most a few tens of units long, and
     * a closed form for Catmull-Rom arc length does not exist anyway.
     */
    private fun segmentLength(p0: Vec2, p1: Vec2, p2: Vec2, p3: Vec2): Float {
        var length = 0f
        var previous = p1
        for (i in 1..LENGTH_SAMPLES) {
            val at = catmullRom(p0, p1, p2, p3, i.toFloat() / LENGTH_SAMPLES)
            length += hypot(at.x - previous.x, at.y - previous.y)
            previous = at
        }
        return length
    }

    fun catmullRom(p0: Vec2, p1: Vec2, p2: Vec2, p3: Vec2, t: Float): Vec2 {
        val t2 = t * t
        val t3 = t2 * t
        return Vec2(
            0.5f * ((2f * p1.x) + (-p0.x + p2.x) * t +
                (2f * p0.x - 5f * p1.x + 4f * p2.x - p3.x) * t2 +
                (-p0.x + 3f * p1.x - 3f * p2.x + p3.x) * t3),
            0.5f * ((2f * p1.y) + (-p0.y + p2.y) * t +
                (2f * p0.y - 5f * p1.y + 4f * p2.y - p3.y) * t2 +
                (-p0.y + 3f * p1.y - 3f * p2.y + p3.y) * t3),
        )
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private const val LENGTH_SAMPLES = 8
    private const val MIN_STEP = 0.01f

    /** Four subsamples per stamp: fine enough that a curve is straight between them. */
    private const val SUBSAMPLES_PER_STEP = 4

    /** A guard against a single very long segment under a very tight spacing. */
    private const val MAX_SUBSAMPLES = 512
}
