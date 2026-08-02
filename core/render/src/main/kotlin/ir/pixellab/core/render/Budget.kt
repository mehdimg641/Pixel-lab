package ir.pixellab.core.render

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * How much work the renderer should attempt.
 *
 * Four tiers rather than a continuous scale, and that is deliberate: a quality level that slides
 * continuously with the frame rate oscillates. It drops quality, the frame gets cheaper, it raises
 * quality, the frame gets expensive, and the picture pulses visibly while the user is doing nothing.
 * Discrete tiers with hysteresis between them settle.
 */
enum class Quality {
    /** Everything, at full resolution. What a still canvas gets. */
    FULL,

    /** Full resolution, cheaper filtering — the first thing to give up, and the least visible. */
    HIGH,

    /** Half resolution while a gesture is in flight. Restored the moment the finger lifts. */
    INTERACTIVE,

    /** Quarter resolution. A thermal or memory emergency, not a preference. */
    MINIMAL,
    ;

    /** What the render target is scaled by. */
    val scale: Float
        get() = when (this) {
            FULL, HIGH -> 1f
            INTERACTIVE -> 0.5f
            MINIMAL -> 0.25f
        }
}

/**
 * Watches frame times and says what quality the next frame should use.
 *
 * The number that matters is **not the average**. An average of 14 ms hides a frame of 60 ms, and
 * that one frame is the whole of what the user felt — smoothness is judged by the worst frames, not
 * the typical one. So the decision is driven by the 95th percentile and by the count of frames that
 * missed the deadline outright.
 *
 * Pure arithmetic over a ring of samples, so the policy is testable without a device. The platform
 * only has to supply the timestamps.
 */
class FrameBudget(
    /** Nanoseconds per frame. 60 Hz by default; a 120 Hz panel halves it. */
    val targetNanos: Long = SIXTY_HZ,
    val window: Int = DEFAULT_WINDOW,
) {
    init {
        require(window >= MIN_WINDOW) { "a window under $MIN_WINDOW frames is noise, got $window" }
    }

    private val samples = LongArray(window)
    private var count = 0
    private var next = 0

    /** Frames recorded since the last reset, capped at the window. */
    val recorded: Int get() = count

    fun record(durationNanos: Long) {
        samples[next] = durationNanos.coerceAtLeast(0L)
        next = (next + 1) % window
        if (count < window) count++
    }

    fun reset() {
        count = 0
        next = 0
    }

    fun mean(): Long {
        if (count == 0) return 0
        var total = 0L
        for (i in 0 until count) total += samples[i]
        return total / count
    }

    /**
     * The [fraction] percentile, in nanoseconds.
     *
     * Nearest-rank — `ceil(p × n)` — rather than the interpolated variant. The difference is not
     * cosmetic at this window size: over sixteen frames the interpolated form maps the 95th
     * percentile to the *fifteenth* sample, so a single catastrophic frame in sixteen is reported
     * as if it had not happened. Under-reporting the tail is precisely the failure this whole class
     * exists to avoid.
     */
    fun percentile(fraction: Float): Long {
        if (count == 0) return 0
        val sorted = samples.copyOf(count).sortedArray()
        val rank = ceil(fraction.coerceIn(0f, 1f) * count).toInt().coerceIn(1, count)
        return sorted[rank - 1]
    }

    /** Frames that missed the deadline. The number a user would describe as "it stuttered". */
    fun missed(): Int {
        var missed = 0
        for (i in 0 until count) if (samples[i] > targetNanos) missed++
        return missed
    }

    /** How irregular the frame times are. Steady slowness is far less noticeable than jitter. */
    fun jitter(): Long {
        if (count < 2) return 0
        val average = mean().toDouble()
        var sum = 0.0
        for (i in 0 until count) {
            val d = samples[i] - average
            sum += d * d
        }
        return sqrt(sum / count).toLong()
    }

    /**
     * What to render at next, given what is being rendered at now.
     *
     * [current] is passed in so the decision can be asymmetric, and it has to be: dropping quality
     * happens on the first sign of trouble, because the user is already feeling it, while raising
     * it needs a comfortable margin and a full window of evidence. Symmetric thresholds are exactly
     * what makes an adaptive renderer oscillate.
     */
    fun recommend(current: Quality): Quality {
        if (count < window / 2) return current

        val worst = percentile(BAD_FRAME_PERCENTILE)
        val missedFraction = missed().toFloat() / count

        return when {
            worst > targetNanos * SEVERE || missedFraction > SEVERE_FRACTION -> current.worse()
            worst > targetNanos -> if (current == Quality.FULL) Quality.HIGH else current
            // Comfortably inside the budget, and only with a full window behind it.
            count >= window && worst < targetNanos * COMFORTABLE -> current.better()
            else -> current
        }
    }

    private fun Quality.worse(): Quality = when (this) {
        Quality.FULL -> Quality.HIGH
        Quality.HIGH -> Quality.INTERACTIVE
        else -> Quality.MINIMAL
    }

    private fun Quality.better(): Quality = when (this) {
        Quality.MINIMAL -> Quality.INTERACTIVE
        Quality.INTERACTIVE -> Quality.HIGH
        else -> Quality.FULL
    }

    companion object {
        const val SIXTY_HZ = 16_666_667L
        const val ONE_TWENTY_HZ = 8_333_333L

        /** A second at 60 Hz: long enough to see a pattern, short enough to react within one. */
        const val DEFAULT_WINDOW = 60
        private const val MIN_WINDOW = 8

        /** The worst frames are what smoothness is judged by, so this is where the policy looks. */
        private const val BAD_FRAME_PERCENTILE = 0.95f

        /** Half again over budget, or a third of frames missing: not a wobble, a problem. */
        private const val SEVERE = 1.5f
        private const val SEVERE_FRACTION = 0.33f

        /** Two-thirds of the budget before climbing back, which is the hysteresis. */
        private const val COMFORTABLE = 0.66f
    }
}

/**
 * Whether an operation's pixels will fit.
 *
 * Sits beside [MemoryBudget], which answers the same question for a whole document graph. This one
 * is about a single raster operation — a placed photograph, a filter's working copy, a 3D bake —
 * where the caller has a width and a height in hand and needs to know what to do about them.
 *
 * The cap is not a nicety on this device class. Android gives an app a heap measured in a few
 * hundred megabytes, and a 48-megapixel photograph at 32-bit float is 768 MB before anything is
 * drawn — so the choice is between refusing, downscaling, and being killed by the system with the
 * user's work unsaved. Refusing is bad and being killed is unforgivable, which leaves downscaling.
 */
class RasterBudget(val limitBytes: Long = DEFAULT_LIMIT) {

    init {
        require(limitBytes > 0) { "a budget must be positive, got $limitBytes" }
    }

    /** Bytes for one buffer of this size at this depth. */
    fun bytesFor(width: Int, height: Int, bytesPerPixel: Int = ARGB_8888): Long =
        width.toLong() * height * bytesPerPixel

    fun fits(width: Int, height: Int, bytesPerPixel: Int = ARGB_8888, buffers: Int = 1): Boolean =
        bytesFor(width, height, bytesPerPixel) * buffers <= limitBytes

    /**
     * The largest whole-pixel scale that fits, as a fraction of the requested size.
     *
     * Returns 1 when the request already fits, so the caller can use the result unconditionally
     * rather than branching — a branch that is skipped in the common case is a branch nobody tests.
     */
    fun scaleToFit(width: Int, height: Int, bytesPerPixel: Int = ARGB_8888, buffers: Int = 1): Float {
        val needed = bytesFor(width, height, bytesPerPixel) * buffers
        if (needed <= limitBytes) return 1f
        // Area scales with the square of the linear factor, so the linear factor is the square root.
        return sqrt(limitBytes.toDouble() / needed).toFloat().coerceIn(MIN_SCALE, 1f)
    }

    /**
     * Halves the size until it fits.
     *
     * Whole halvings rather than an exact fit, because a power-of-two reduction averages a fixed
     * block of pixels — which is both fast and free of the ringing a general resampler leaves on a
     * photograph.
     */
    fun halvingsToFit(width: Int, height: Int, bytesPerPixel: Int = ARGB_8888, buffers: Int = 1): Int {
        var step = 0
        var w = width
        var h = height
        while (step < MAX_HALVINGS && bytesFor(w, h, bytesPerPixel) * buffers > limitBytes) {
            w = (w / 2).coerceAtLeast(1)
            h = (h / 2).coerceAtLeast(1)
            step++
        }
        return step
    }

    companion object {
        const val ARGB_8888 = 4
        const val RGBA_F16 = 8
        const val RGBA_F32 = 16

        /**
         * The figure the specification names, and it is the right order of magnitude.
         *
         * An Android app's heap on a mid-range device is a few hundred megabytes and the system
         * kills whatever exceeds it without warning. Leaving headroom for the composite, the undo
         * stack and the platform itself is what this number is.
         */
        const val DEFAULT_LIMIT = 400L * 1024 * 1024

        private const val MIN_SCALE = 0.05f
        private const val MAX_HALVINGS = 6
    }
}
