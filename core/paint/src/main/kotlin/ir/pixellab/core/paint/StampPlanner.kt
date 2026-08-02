package ir.pixellab.core.paint

import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Vec2
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * One dab, fully resolved.
 *
 * Everything a rasteriser needs and nothing it has to work out: by this point pressure, velocity,
 * jitter, taper and scatter have all been folded into plain numbers. The rasteriser then has no
 * opinions, which is what lets the whole of the interesting behaviour be tested without a GPU.
 */
data class Stamp(
    val position: Vec2,
    /** Diameter in canvas units. */
    val size: Float,
    /** Degrees, clockwise. */
    val angle: Float,
    val roundness: Float,
    /** Alpha this single dab carries, before the stroke's opacity ceiling. */
    val flow: Float,
    val color: Color,
)

/**
 * Turns a stroke into dabs.
 *
 * The whole of the brush's character lives here. A rasteriser that decided any of this itself would
 * have to be re-tested on a device; keeping it in one pure function means the difference between an
 * airbrush and a marker is a data change, and every one of those differences is checkable.
 *
 * Jitter is deterministic, seeded from the stroke and the dab index. That is not a detail: a stroke
 * has to look identical when it is undone and redone, when the document is re-rendered, and when it
 * is exported at four times the size. A brush seeded from the clock is a brush whose artwork changes
 * every time it is opened.
 */
class StampPlanner(private val preset: BrushPreset, private val seed: Int = 0) {

    private val stabilizer = StrokeStabilizer(preset.smoothing)
    private var carryOver = 0f
    private var index = 0
    private var distanceTravelled = 0f
    private var lastPosition: Vec2? = null
    private var lastDirection = 0f

    /**
     * Plans the dabs for a batch of new samples.
     *
     * Incremental rather than whole-stroke, because painting has to appear under the finger: a
     * planner that needed the finished stroke would draw nothing until the finger lifted.
     *
     * @param totalLength the stroke's expected total length, for taper. Zero disables tapering,
     *   which is correct while a stroke is still being drawn and its length is not yet known.
     */
    fun plan(points: List<StrokePoint>, totalLength: Float = 0f): List<Stamp> {
        val smoothed = points.mapNotNull(stabilizer::next)
        if (smoothed.isEmpty()) return emptyList()

        val resampled = PathResampler.resample(smoothed, preset.stepDistance, carryOver)
        carryOver = resampled.carryOver

        val stamps = ArrayList<Stamp>(resampled.points.size * preset.count)
        for (point in resampled.points) {
            val previous = lastPosition
            if (previous != null) {
                val delta = point.position - previous
                distanceTravelled += hypot(delta.x, delta.y)
                if (delta.x != 0f || delta.y != 0f) {
                    lastDirection = Math.toDegrees(atan2(delta.y.toDouble(), delta.x.toDouble())).toFloat()
                }
            }
            lastPosition = point.position

            val velocity = velocityOf(point, previous)
            repeat(preset.count) { dab ->
                stamps += stamp(point, velocity, dab, totalLength)
            }
            index++
        }
        return stamps
    }

    /** Ends the stroke at the finger, so a lift does not cut the line short. */
    fun finish(last: StrokePoint, totalLength: Float = 0f): List<Stamp> {
        val point = stabilizer.finish(last)
        val previous = lastPosition
        // A tap is a stroke of one point, and it has to leave a mark. Only when nothing has been
        // laid down yet: otherwise every lift would double the last dab.
        if (previous == null) {
            index++
            return List(preset.count) { stamp(point, velocity = 0f, dab = it, totalLength = totalLength) }
        }
        return plan(listOf(point), totalLength)
    }

    fun reset() {
        stabilizer.reset()
        carryOver = 0f
        index = 0
        distanceTravelled = 0f
        lastPosition = null
        lastDirection = 0f
    }

    private fun stamp(point: StrokePoint, velocity: Float, dab: Int, totalLength: Float): Stamp {
        val random = Random(seed, index, dab)
        val taper = taperAt(totalLength)

        val size = preset.size *
            apply(preset.sizeDynamic, point, velocity, random.next(SALT_SIZE)) *
            taper
        val angle = preset.angle + directionOffset() +
            (apply(preset.angleDynamic, point, velocity, random.next(SALT_ANGLE)) - 1f) * FULL_TURN
        val roundness = (preset.roundness * apply(preset.roundnessDynamic, point, velocity, random.next(SALT_ROUND)))
            .coerceIn(MIN_ROUNDNESS, 1f)
        val flow = (preset.flow * apply(preset.flowDynamic, point, velocity, random.next(SALT_FLOW)) * taper)
            .coerceIn(0f, 1f)

        return Stamp(
            position = scatter(point.position, random),
            size = size.coerceAtLeast(MIN_SIZE),
            angle = angle,
            roundness = roundness,
            flow = flow,
            color = colorFor(random),
        )
    }

    /**
     * A dynamic's multiplier, between its minimum and 1.
     *
     * Jitter is added on top of the control rather than replacing it, which is what Photoshop does
     * and what makes "pressure with a little jitter" expressible at all.
     */
    private fun apply(dynamic: Dynamic, point: StrokePoint, velocity: Float, random: Float): Float {
        if (!dynamic.isActive) return 1f

        val driven = when (dynamic.control) {
            ControlSource.NONE -> 1f
            ControlSource.PRESSURE -> point.pressure
            // Faster means smaller, which is how a real nib behaves and how a taper appears without
            // a stylus at all — the reason this matters on a phone.
            ControlSource.VELOCITY -> 1f - min(1f, velocity / VELOCITY_FULL)
            ControlSource.TILT -> 1f - min(1f, abs(point.tilt) / MAX_TILT)
            ControlSource.DIRECTION -> ((lastDirection / FULL_TURN) + 1f) % 1f
            ControlSource.FADE -> 1f - min(1f, index.toFloat() / dynamic.fadeSteps.coerceAtLeast(1))
            ControlSource.RANDOM -> random
        }
        val controlled = if (dynamic.inverted) 1f - driven else driven
        val jittered = if (dynamic.jitter > 0f) {
            controlled * (1f - dynamic.jitter) + random * dynamic.jitter
        } else {
            controlled
        }
        return dynamic.minimum + jittered.coerceIn(0f, 1f) * (1f - dynamic.minimum)
    }

    /** The tip follows the path when the angle is driven by direction, as a chisel nib does. */
    private fun directionOffset(): Float =
        if (preset.angleDynamic.control == ControlSource.DIRECTION) lastDirection else 0f

    private fun scatter(position: Vec2, random: Random): Vec2 {
        if (preset.scatter <= 0f) return position
        val reach = preset.size * preset.scatter
        val across = (random.next(SALT_SCATTER_X) * 2f - 1f) * reach
        val along = if (preset.scatterBothAxes) (random.next(SALT_SCATTER_Y) * 2f - 1f) * reach else 0f

        // Across the stroke, not across the canvas: a spray that always scattered along the x axis
        // would visibly comb itself whenever the stroke turned.
        val radians = Math.toRadians(lastDirection.toDouble())
        val dirX = kotlin.math.cos(radians).toFloat()
        val dirY = kotlin.math.sin(radians).toFloat()
        return Vec2(
            position.x + (-dirY) * across + dirX * along,
            position.y + dirX * across + dirY * along,
        )
    }

    private fun colorFor(random: Random): Color {
        var color = if (preset.colorJitter > 0f && random.next(SALT_COLOR) < preset.colorJitter) {
            preset.secondaryColor
        } else {
            preset.color
        }
        if (preset.hueJitter > 0f || preset.saturationJitter > 0f || preset.brightnessJitter > 0f) {
            color = ColorJitter.apply(
                color,
                hue = (random.next(SALT_HUE) * 2f - 1f) * preset.hueJitter,
                saturation = (random.next(SALT_SAT) * 2f - 1f) * preset.saturationJitter,
                brightness = (random.next(SALT_VAL) * 2f - 1f) * preset.brightnessJitter,
            )
        }
        return color
    }

    /**
     * How much the stroke is thinned at this point along it.
     *
     * Needs the total length, which is only known once the stroke is finished — so while it is
     * being drawn there is no taper and the finished stroke is re-planned. Guessing the length
     * instead makes a stroke visibly change thickness the moment the finger lifts.
     */
    private fun taperAt(totalLength: Float): Float {
        if (totalLength <= 0f) return 1f
        if (preset.taperStart <= 0f && preset.taperEnd <= 0f) return 1f
        val at = distanceTravelled / totalLength
        val start = if (preset.taperStart > 0f) min(1f, at / preset.taperStart) else 1f
        val end = if (preset.taperEnd > 0f) min(1f, (1f - at) / preset.taperEnd) else 1f
        return min(start, end).coerceIn(TAPER_FLOOR, 1f)
    }

    private fun velocityOf(point: StrokePoint, previous: Vec2?): Float {
        val from = previous ?: return 0f
        val delta = point.position - from
        return hypot(delta.x, delta.y)
    }

    /**
     * A hash, not a generator.
     *
     * The value for a given dab has to be the same however the stroke was reached — planned live,
     * re-planned after a lift, replayed by undo, or re-rendered for an export. A stateful generator
     * gives a different answer the second time through, and the artwork changes under the user.
     */
    private class Random(private val seed: Int, private val index: Int, private val dab: Int) {
        fun next(salt: Int): Float {
            var h = seed * PRIME_SEED + index * PRIME_INDEX + dab * PRIME_DAB + salt * PRIME_SALT
            h = h xor (h ushr 15)
            h *= MIX_A
            h = h xor (h ushr 13)
            h *= MIX_B
            h = h xor (h ushr 16)
            return (h ushr 8).toFloat() / MAX_24_BIT
        }

        private companion object {
            const val PRIME_SEED = 0x9E3779B1.toInt()
            const val PRIME_INDEX = 0x85EBCA77.toInt()
            const val PRIME_DAB = 0xC2B2AE3D.toInt()
            const val PRIME_SALT = 0x27D4EB2F
            const val MIX_A = 0x85EBCA6B.toInt()
            const val MIX_B = 0xC2B2AE35.toInt()
            const val MAX_24_BIT = 16777216f
        }
    }

    private companion object {
        const val SALT_SIZE = 1
        const val SALT_ANGLE = 2
        const val SALT_ROUND = 3
        const val SALT_FLOW = 4
        const val SALT_SCATTER_X = 5
        const val SALT_SCATTER_Y = 6
        const val SALT_COLOR = 7
        const val SALT_HUE = 8
        const val SALT_SAT = 9
        const val SALT_VAL = 10

        const val FULL_TURN = 360f

        /** Speed at which a velocity control reaches its minimum, in canvas units per sample. */
        const val VELOCITY_FULL = 60f

        const val MAX_TILT = 90f

        /** A dab thinner than this is a line, and the rasteriser cannot antialias it usefully. */
        const val MIN_ROUNDNESS = 0.05f

        /** Sub-pixel dabs cost the same as visible ones and contribute nothing. */
        const val MIN_SIZE = 0.5f

        /** A taper that reached zero would end the stroke in nothing at all. */
        const val TAPER_FLOOR = 0.02f
    }
}

/** Hue, saturation and brightness jitter, in the space people actually think about colour in. */
internal object ColorJitter {

    fun apply(color: Color, hue: Float, saturation: Float, brightness: Float): Color {
        if (hue == 0f && saturation == 0f && brightness == 0f) return color
        val (h, s, v) = toHsv(color)
        return fromHsv(
            ((h + hue) % 1f + 1f) % 1f,
            (s + saturation).coerceIn(0f, 1f),
            (v + brightness).coerceIn(0f, 1f),
            color.a,
        )
    }

    private fun toHsv(color: Color): Triple<Float, Float, Float> {
        val max = maxOf(color.r, color.g, color.b)
        val min = minOf(color.r, color.g, color.b)
        val delta = max - min
        val h = when {
            delta == 0f -> 0f
            max == color.r -> ((color.g - color.b) / delta / 6f + 1f) % 1f
            max == color.g -> ((color.b - color.r) / delta + 2f) / 6f
            else -> ((color.r - color.g) / delta + 4f) / 6f
        }
        return Triple(h, if (max == 0f) 0f else delta / max, max)
    }

    private fun fromHsv(h: Float, s: Float, v: Float, a: Float): Color {
        val i = (h * 6f).toInt()
        val f = h * 6f - i
        val p = v * (1f - s)
        val q = v * (1f - f * s)
        val t = v * (1f - (1f - f) * s)
        return when (i % 6) {
            0 -> Color(v, t, p, a)
            1 -> Color(q, v, p, a)
            2 -> Color(p, v, t, a)
            3 -> Color(p, q, v, a)
            4 -> Color(t, p, v, a)
            else -> Color(v, p, q, a)
        }
    }
}
