package ir.pixellab.core.imaging

import kotlin.math.ln
import kotlin.math.roundToInt

/** Which channel a histogram counts. */
enum class HistogramChannel {
    RED, GREEN, BLUE, LUMINANCE, ALPHA,
    ;

    val persianLabel: String
        get() = when (this) {
            RED -> "قرمز"
            GREEN -> "سبز"
            BLUE -> "آبی"
            LUMINANCE -> "روشنایی"
            ALPHA -> "شفافیت"
        }
}

/**
 * The distribution of tones in an image.
 *
 * The one readout that turns guessing into measuring. Every exposure decision a person makes by eye
 * — "is this too dark?", "have I blown the highlights?" — has an exact answer, and a histogram is
 * that answer. It is also the only way to see clipping *before* it is baked into an export, because
 * a screen cannot show the difference between 254 and 255.
 *
 * Counts, not fractions: the caller decides how to scale them, and a fraction would lose the one
 * piece of information that matters at the ends of the range — whether the count is zero or one.
 */
class Histogram(val counts: IntArray, val channel: HistogramChannel) {

    init {
        require(counts.size == BINS) { "a histogram has $BINS bins, got ${counts.size}" }
    }

    val total: Long get() = counts.sumOf { it.toLong() }

    /** Pixels at the very bottom of the range: shadow detail that no longer exists. */
    val clippedBlacks: Int get() = counts.first()

    /** And at the top, which is the one that shows as a flat white patch in a print. */
    val clippedWhites: Int get() = counts.last()

    /** The tallest bin, which is what a drawn histogram is scaled against. */
    val peak: Int get() = counts.maxOrNull() ?: 0

    /**
     * Bin heights in 0..1, scaled logarithmically.
     *
     * Linear scaling is the obvious choice and it makes the display useless on any real photograph:
     * a single dominant tone — a sky, a studio backdrop — is often a hundred times taller than
     * everything else, so every other bin flattens to nothing. Photoshop scales logarithmically for
     * exactly this reason, and so does every histogram anyone finds readable.
     */
    fun normalised(): FloatArray {
        val top = peak
        if (top <= 0) return FloatArray(BINS)
        val scale = 1f / ln(1f + top.toFloat())
        return FloatArray(BINS) { ln(1f + counts[it].toFloat()) * scale }
    }

    /**
     * The level below which [fraction] of the pixels sit.
     *
     * What auto-levels is built on: clipping a small fraction at each end and stretching what is
     * left is the whole of the operation, and doing it from the extremes instead would let one
     * stray hot pixel decide the white point for the entire image.
     */
    fun percentile(fraction: Float): Int {
        // At least one pixel, always. A target of zero would be met by the empty bin 0 before any
        // pixel had been counted, so every percentile below one pixel's worth would answer "black"
        // — and on a small image the auto-levels clip fraction rounds to exactly that.
        val target = (total * fraction.coerceIn(0f, 1f)).toLong().coerceAtLeast(1L)
        var running = 0L
        for (bin in 0 until BINS) {
            running += counts[bin]
            if (running >= target) return bin
        }
        return BINS - 1
    }

    /** Mean level, in 0..255. Reported because it is what "is this too dark?" actually asks. */
    fun mean(): Float {
        val count = total
        if (count == 0L) return 0f
        var weighted = 0.0
        for (bin in 0 until BINS) weighted += bin.toDouble() * counts[bin]
        return (weighted / count).toFloat()
    }

    companion object {
        const val BINS = 256

        /**
         * Counts one channel of an image.
         *
         * Fully transparent pixels are skipped for every channel except alpha itself. Counting them
         * means a cut-out subject's histogram is dominated by whatever colour happened to be left
         * behind the transparency — usually black — and the reading describes the file rather than
         * the picture.
         */
        fun of(pixels: IntArray, channel: HistogramChannel): Histogram {
            val counts = IntArray(BINS)
            for (pixel in pixels) {
                val alpha = (pixel ushr 24) and 0xFF
                if (channel == HistogramChannel.ALPHA) {
                    counts[alpha]++
                    continue
                }
                if (alpha == 0) continue
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                val value = when (channel) {
                    HistogramChannel.RED -> red
                    HistogramChannel.GREEN -> green
                    HistogramChannel.BLUE -> blue
                    // Rec. 709, the same weights the renderer uses. A histogram computed with
                    // different weights than the pipeline would disagree with the picture it
                    // describes, which is worse than not having one.
                    else -> (LUMA_R * red + LUMA_G * green + LUMA_B * blue).roundToInt().coerceIn(0, 255)
                }
                counts[value]++
            }
            return Histogram(counts, channel)
        }

        /**
         * Black and white points that clip [fraction] at each end — Photoshop's Auto Levels.
         *
         * A tenth of a percent by default, which is Photoshop's own figure: enough to ignore a few
         * hot pixels and a sensor's black floor, small enough not to throw away real detail.
         * Returns null when the image has no range to stretch, so a caller does not apply an
         * adjustment that would divide by nothing.
         */
        fun autoLevels(histogram: Histogram, fraction: Float = AUTO_CLIP): Pair<Int, Int>? {
            if (histogram.total == 0L) return null
            val black = histogram.percentile(fraction)
            val white = histogram.percentile(1f - fraction)
            return if (white - black < MIN_RANGE) null else black to white
        }

        private const val LUMA_R = 0.2126f
        private const val LUMA_G = 0.7152f
        private const val LUMA_B = 0.0722f

        private const val AUTO_CLIP = 0.001f

        /** Below this the image is nearly one flat tone and stretching it only amplifies noise. */
        private const val MIN_RANGE = 4
    }
}
