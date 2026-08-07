package ir.pixellab.core.imaging

/**
 * Frequency separation — the technique that separates professional retouching from a blur filter.
 *
 * A blur removes blemishes and skin texture together, which is what makes phone filters look
 * plastic. Separating the image into a low band carrying colour and form and a high band carrying
 * pores and hair lets each be edited alone: blemishes come off the high band, uneven tone off the
 * low band, and the skin keeps its texture.
 *
 * The split is lossless — recombining returns the original exactly.
 */
object FrequencySeparation {

    /** Offset the high band is stored around, matching Photoshop's 50% grey convention. */
    const val PIVOT = 0.5f

    data class Bands(val low: Raster, val high: Raster) {
        /** Reassembles the original image. */
        fun recombine(): Raster {
            val out = Raster(low.width, low.height, low.channels)
            for (i in out.data.indices) out.data[i] = low.data[i] + (high.data[i] - PIVOT)
            return out
        }
    }

    /**
     * Splits into two bands at [radius].
     *
     * The radius decides what counts as texture. Too small and blemishes stay in the low band where
     * smoothing them destroys form; too large and facial structure leaks into the high band and
     * editing it warps the face.
     */
    fun split(image: Raster, radius: Float): Bands {
        val low = Blur.gaussian(image, radius)
        val high = Raster(image.width, image.height, image.channels)
        for (i in high.data.indices) high.data[i] = image.data[i] - low.data[i] + PIVOT
        return Bands(low, high)
    }

    /**
     * Wavelet-style decomposition into several bands.
     *
     * Photoshop offers two bands. Splitting into five or seven separates fine pores from medium
     * wrinkles from broad shadows, so each can be treated independently — the reason professional
     * retouchers reach for third-party plugins. Each level doubles the radius of the previous one.
     *
     * The returned list is fine to coarse, with the final entry the residual base layer.
     */
    fun decompose(image: Raster, levels: Int = 5, baseRadius: Float = 1f): List<Raster> {
        require(levels in 1..10) { "levels must be 1..10, got $levels" }
        val out = ArrayList<Raster>(levels + 1)
        var current = image
        var radius = baseRadius
        repeat(levels) {
            val blurred = Blur.gaussian(current, radius)
            val detail = Raster(image.width, image.height, image.channels)
            for (i in detail.data.indices) detail.data[i] = current.data[i] - blurred.data[i] + PIVOT
            out += detail
            current = blurred
            radius *= 2f
        }
        out += current
        return out
    }

    /** Rebuilds an image from [decompose] output, optionally scaling individual bands. */
    fun recompose(bands: List<Raster>, gains: List<Float> = emptyList()): Raster {
        require(bands.isNotEmpty()) { "need at least the base band" }
        val base = bands.last()
        val out = base.copy()
        for (level in 0 until bands.size - 1) {
            val gain = gains.getOrElse(level) { 1f }
            if (gain == 0f) continue
            val band = bands[level]
            for (i in out.data.indices) out.data[i] += (band.data[i] - PIVOT) * gain
        }
        return out
    }
}

/**
 * Colour decontamination for matted edges.
 *
 * After alpha is estimated, the partially transparent pixels still carry the colour of whatever was
 * behind them. A strand of hair shot against a green wall stays tinted green, and drops onto a new
 * background with a visible halo. Photoshop exposes this as "Decontaminate Colors"; skipping it is
 * the most common reason a cutout looks pasted on.
 *
 * Solves `I = α·F + (1−α)·B` for `F`, given the estimated alpha and a local background estimate.
 */
object Decontaminate {

    /** Below this alpha the division is too ill-conditioned to trust, so colour is borrowed instead. */
    private const val MIN_ALPHA = 0.15f

    /**
     * @param image the original RGBA or RGB pixels
     * @param alpha estimated coverage, single channel
     * @param background local estimate of what was behind the subject, same shape as [image]
     */
    fun foreground(image: Raster, alpha: Raster, background: Raster): Raster {
        require(image.sameShapeAs(alpha) && image.sameShapeAs(background)) { "shapes must match" }
        require(alpha.channels == 1) { "alpha must be single channel" }

        val colourChannels = minOf(3, image.channels)
        val out = image.copy()
        for (i in 0 until image.pixelCount) {
            val a = alpha.data[i]
            if (a >= MIN_ALPHA) {
                val o = i * image.channels
                val ob = i * background.channels
                for (c in 0 until colourChannels) {
                    val recovered = (image.data[o + c] - (1f - a) * background.data[ob + c]) / a
                    out.data[o + c] = recovered.coerceIn(0f, 1f)
                }
            }
        }
        // Pixels too transparent to solve borrow colour from confident neighbours, so the very tips
        // of hair strands do not turn into noise.
        return bleedFromConfident(out, alpha, colourChannels)
    }

    private fun bleedFromConfident(image: Raster, alpha: Raster, colourChannels: Int): Raster {
        val out = image.copy()
        val w = image.width
        for (y in 0 until image.height) {
            for (x in 0 until w) {
                val i = y * w + x
                if (alpha.data[i] >= MIN_ALPHA) continue
                var weight = 0f
                val acc = FloatArray(colourChannels)
                for (dy in -2..2) for (dx in -2..2) {
                    val nx = x + dx
                    val ny = y + dy
                    if (nx !in 0 until w || ny !in 0 until image.height) continue
                    val ni = ny * w + nx
                    val na = alpha.data[ni]
                    if (na < MIN_ALPHA) continue
                    val o = ni * image.channels
                    for (c in 0 until colourChannels) acc[c] += image.data[o + c] * na
                    weight += na
                }
                if (weight > 0f) {
                    val o = i * image.channels
                    for (c in 0 until colourChannels) out.data[o + c] = acc[c] / weight
                }
            }
        }
        return out
    }
}

/**
 * Trimap construction and refinement — the front half of Refine Edge.
 *
 * A trimap marks each pixel as definitely foreground, definitely background, or unknown. Alpha is
 * then solved only over the unknown band, which is what keeps the cost manageable: on a portrait
 * that band is typically a few percent of the image.
 */
object Trimap {

    const val BACKGROUND = 0f
    const val UNKNOWN = 0.5f
    const val FOREGROUND = 1f

    /**
     * Builds a trimap by widening the edge of a binary-ish [mask] by [band] pixels either side.
     *
     * Uses the signed distance field rather than repeated morphology so the band width is exact and
     * does not depend on how many dilation passes were run.
     */
    fun fromMask(mask: Raster, band: Float): Raster {
        require(band > 0f) { "band must be positive, got $band" }
        val signed = DistanceTransform.signed(mask)
        val out = Raster(mask.width, mask.height, 1)
        for (i in out.data.indices) {
            val d = signed.data[i]
            out.data[i] = when {
                d < -band -> FOREGROUND
                d > band -> BACKGROUND
                else -> UNKNOWN
            }
        }
        return out
    }

    /**
     * Widens the unknown band wherever the user painted, which is the refine-edge brush.
     *
     * Hair needs a much wider band than a shoulder, and forcing one global width either misses
     * strands or wastes time solving over flat regions.
     */
    fun widen(trimap: Raster, strokes: Raster): Raster {
        require(trimap.sameShapeAs(strokes)) { "stroke mask must match the trimap" }
        val out = trimap.copy()
        for (i in out.data.indices) if (strokes.data[i] > 0.5f) out.data[i] = UNKNOWN
        return out
    }

    /** Fraction of pixels left unknown; drives the cost estimate before solving. */
    fun unknownFraction(trimap: Raster): Float {
        var count = 0
        for (v in trimap.data) if (v > 0.25f && v < 0.75f) count++
        return count.toFloat() / trimap.pixelCount
    }

    /**
     * Solves alpha over the unknown band using the guided filter, keeping the known regions fixed.
     *
     * @param guide the source image; pass all three channels so hair separates by chrominance
     */
    fun solveAlpha(trimap: Raster, guide: Raster, radius: Int = 8, epsilon: Float = 1e-4f): Raster {
        val initial = Raster(trimap.width, trimap.height, 1)
        for (i in initial.data.indices) {
            initial.data[i] = when {
                trimap.data[i] >= 0.75f -> 1f
                trimap.data[i] <= 0.25f -> 0f
                else -> 0.5f
            }
        }
        val refined = if (guide.channels >= 3) {
            GuidedFilter.filterColorGuide(guide, initial, radius, epsilon)
        } else {
            GuidedFilter.filter(guide, initial, radius, epsilon)
        }
        // Known regions are constraints, not suggestions — the filter must not soften them.
        val out = Raster(trimap.width, trimap.height, 1)
        for (i in out.data.indices) {
            out.data[i] = when {
                trimap.data[i] >= 0.75f -> 1f
                trimap.data[i] <= 0.25f -> 0f
                else -> refined.data[i].coerceIn(0f, 1f)
            }
        }
        return out
    }
}
