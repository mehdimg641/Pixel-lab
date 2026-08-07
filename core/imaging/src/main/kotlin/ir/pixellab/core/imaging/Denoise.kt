package ir.pixellab.core.imaging

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Noise reduction that keeps the edges.
 *
 * A blur removes noise perfectly and removes the picture with it. Everything here is built on the
 * same idea instead: average a pixel only with the neighbours that *look like it*, so a flat wall
 * smooths out while the line between the wall and the window stays a line.
 *
 * The luminance/chrominance split matters more than the filter does. Sensor noise is mostly in the
 * colour, it is the part that looks worst — magenta and green blotches in a shadow — and the eye
 * has almost no spatial acuity for colour, so chroma can be smoothed far harder than luma without
 * anyone seeing detail go. Denoising the three RGB channels equally is the mistake that turns a
 * noisy photograph into a plastic one.
 */
object Denoise {

    /**
     * Photoshop's Reduce Noise.
     *
     * @param strength 0..1, how far the luminance is smoothed. This is the one that costs detail.
     * @param colorStrength 0..1, and safe to run much higher than [strength] for the reason above.
     * @param preserveDetail 0..1. Raises the range threshold so only differences smaller than the
     *   noise are averaged; at 1 almost nothing is touched.
     */
    fun reduceNoise(
        src: Raster,
        strength: Float = 0.5f,
        colorStrength: Float = 0.6f,
        preserveDetail: Float = 0.5f,
    ): Raster {
        require(src.channels >= 3) { "noise reduction needs colour, got ${src.channels} channels" }
        if (strength <= 0f && colorStrength <= 0f) return src

        val out = src.copy()
        if (strength > 0f) {
            // Bilateral on luminance only. Run per channel it would shift hue wherever the three
            // channels happened to average different neighbours, which is a colour fringe on every
            // edge — the exact artefact the filter is supposed to remove.
            val luma = src.luminance()
            val smoothed = bilateral(
                luma,
                spatial = LUMA_RADIUS * strength.coerceIn(0f, 1f),
                range = rangeFor(preserveDetail, LUMA_RANGE),
            )
            for (i in 0 until src.pixelCount) {
                val at = i * src.channels
                // Applied as a difference rather than a replacement, so colour is carried through
                // untouched and only the brightness moves.
                val delta = smoothed.data[i] - luma.data[i]
                for (c in 0 until 3) out.data[at + c] = (out.data[at + c] + delta).coerceIn(0f, 1f)
            }
        }

        if (colorStrength > 0f) {
            val amount = colorStrength.coerceIn(0f, 1f)
            val blurred = Blur.gaussian(out, CHROMA_RADIUS * amount)
            for (i in 0 until src.pixelCount) {
                val at = i * src.channels
                val before = LUMA_R * out.data[at] + LUMA_G * out.data[at + 1] + LUMA_B * out.data[at + 2]
                val after = LUMA_R * blurred.data[at] + LUMA_G * blurred.data[at + 1] +
                    LUMA_B * blurred.data[at + 2]
                // The blurred colour, put back at the original brightness: all of the chroma
                // smoothing, none of the luma smoothing.
                for (c in 0 until 3) {
                    out.data[at + c] = (blurred.data[at + c] + (before - after)).coerceIn(0f, 1f)
                }
            }
        }
        return out
    }

    /**
     * Photoshop's Dust & Scratches: a median, applied only where the pixel is an outlier.
     *
     * The threshold is what makes it usable. A plain median removes dust and every fine detail with
     * it — hair, eyelashes, film grain that was wanted — because it cannot tell a speck from a
     * texture. Restricting the replacement to pixels that differ from their median by more than
     * [threshold] leaves everything that is part of a pattern alone, since a textured region's
     * pixels are all outliers together and none of them stands out from the median.
     */
    fun dustAndScratches(src: Raster, radius: Int = 2, threshold: Float = 0.1f): Raster {
        if (radius < 1) return src
        val out = src.copy()
        val window = FloatArray((2 * radius + 1) * (2 * radius + 1))

        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                for (c in 0 until minOf(src.channels, 3)) {
                    var count = 0
                    for (dy in -radius..radius) {
                        for (dx in -radius..radius) {
                            window[count++] = src.clamped(x + dx, y + dy, c)
                        }
                    }
                    val median = median(window, count)
                    val value = src[x, y, c]
                    if (abs(value - median) > threshold) out[x, y, c] = median
                }
            }
        }
        return out
    }

    /**
     * Weights a neighbour by both how far away it is and how different it is.
     *
     * The second factor is the whole filter: a neighbour across an edge contributes almost nothing,
     * so the average never reaches over the edge and the edge survives.
     */
    internal fun bilateral(src: Raster, spatial: Float, range: Float): Raster {
        require(src.channels == 1) { "bilateral here works on one channel" }
        if (spatial <= 0f) return src

        val radius = (spatial * KERNEL_SIGMAS).roundToInt().coerceIn(1, MAX_RADIUS)
        val spatialWeights = FloatArray(2 * radius + 1) { i ->
            val d = (i - radius).toFloat()
            exp(-(d * d) / (2f * spatial * spatial))
        }
        val rangeScale = -1f / (2f * range * range)

        val out = src.like()
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                val centre = src[x, y]
                var sum = 0f
                var weightSum = 0f
                for (dy in -radius..radius) {
                    val wy = spatialWeights[dy + radius]
                    for (dx in -radius..radius) {
                        val sample = src.clamped(x + dx, y + dy)
                        val difference = sample - centre
                        val weight = wy * spatialWeights[dx + radius] *
                            exp(difference * difference * rangeScale)
                        sum += sample * weight
                        weightSum += weight
                    }
                }
                out[x, y, 0] = if (weightSum > 0f) sum / weightSum else centre
            }
        }
        return out
    }

    /**
     * How different two pixels may be and still be averaged together.
     *
     * Below the noise floor, so only differences the sensor invented are smoothed away; a real edge
     * is far larger than this and is left alone.
     */
    private fun rangeFor(preserveDetail: Float, base: Float): Float =
        base * (1f - preserveDetail.coerceIn(0f, 1f) * DETAIL_REACH)

    /**
     * Selection rather than a full sort.
     *
     * A median over a 5×5 window is 25 elements and this runs once per pixel per channel; sorting
     * would be several times the work for an answer that only needs the middle element.
     */
    private fun median(values: FloatArray, count: Int): Float {
        val work = values.copyOf(count)
        val target = count / 2
        var low = 0
        var high = count - 1
        while (low < high) {
            val pivot = work[(low + high) / 2]
            var i = low
            var j = high
            while (i <= j) {
                while (work[i] < pivot) i++
                while (work[j] > pivot) j--
                if (i <= j) {
                    val swap = work[i]
                    work[i] = work[j]
                    work[j] = swap
                    i++
                    j--
                }
            }
            if (target <= j) high = j else if (target >= i) low = i else break
        }
        return work[target]
    }

    /** Chroma can take several times the luma's radius before anyone sees colour detail go. */
    private const val CHROMA_RADIUS = 6f
    private const val LUMA_RADIUS = 3f

    /** Roughly the noise floor of a phone sensor at a high ISO, in 0..1. */
    private const val LUMA_RANGE = 0.08f

    /** How far preserving detail can pull the range threshold down. Never to zero, or it is a no-op. */
    private const val DETAIL_REACH = 0.85f

    private const val KERNEL_SIGMAS = 2f
    private const val MAX_RADIUS = 12

    private const val LUMA_R = 0.2126f
    private const val LUMA_G = 0.7152f
    private const val LUMA_B = 0.0722f
}
