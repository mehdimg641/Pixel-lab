package ir.pixellab.core.paint

import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Selecting the subject without a model.
 *
 * Photoshop's Select Subject is a segmentation network, and this is deliberately not that: the whole
 * app is meant to work on a phone with no connection and no download, so a classical pipeline that
 * gets the common case right beats a feature that needs a server to exist at all.
 *
 * The pipeline is the one the saliency literature converged on before deep learning arrived, and it
 * rests on a single observation that holds for almost every photograph a person crops: *the border
 * of the frame is background*. Nobody frames their subject touching all four edges. So:
 *
 *  1. build a colour model of the border band,
 *  2. score every pixel by how far its colour is from that model, weighted towards the middle,
 *  3. threshold the score where it separates best (Otsu),
 *  4. keep the substantial connected regions and fill their holes.
 *
 * What it produces is a *region*, not a matte. The edge is where the quality is, and the edge is
 * handled afterwards by the same trimap-and-alpha-solve refinement the manual cut-out uses — which
 * is why this returns a plain coverage mask and stops.
 */
object SubjectSelection {

    /**
     * @param sensitivity 0..1. Higher takes more of the image, which is what the user reaches for
     *   when the subject is close in colour to what is behind it.
     */
    fun select(
        pixels: IntArray,
        width: Int,
        height: Int,
        sensitivity: Float = 0.5f,
    ): PixelSelection {
        require(pixels.size == width * height) { "expected ${width * height} pixels, got ${pixels.size}" }
        if (width < MIN_SIZE || height < MIN_SIZE) return PixelSelection.nothing(width, height)

        val saliency = saliency(pixels, width, height)
        val threshold = otsu(saliency) * (1.5f - sensitivity.coerceIn(0f, 1f))

        val inside = BooleanArray(pixels.size) { saliency[it] > threshold }
        keepSubstantialRegions(inside, width, height)
        fillHoles(inside, width, height)

        val coverage = ByteArray(pixels.size) { if (inside[it]) 255.toByte() else 0 }
        return PixelSelection(width, height, coverage)
    }

    // ---- saliency --------------------------------------------------------------------------------

    /**
     * How unlike the border every pixel is, in 0..1.
     *
     * The distance is looked up through a coarse colour cube rather than measured against a palette
     * per pixel. A border band on a six-megapixel photograph holds tens of thousands of distinct
     * colours, and comparing each of six million pixels against all of them is minutes of work; the
     * cube is built once and answers in one array read.
     */
    internal fun saliency(pixels: IntArray, width: Int, height: Int): FloatArray {
        val background = borderColorCube(pixels, width, height)
        val distanceToBackground = distanceField(background)

        val cx = (width - 1) / 2f
        val cy = (height - 1) / 2f
        // Wide enough that an off-centre subject is not thrown away, narrow enough that a bright
        // corner of sky is not mistaken for one. A prior this mild only breaks ties.
        val sigma = hypot(cx, cy) * CENTRE_SIGMA
        val inv2s2 = 1f / (2f * sigma * sigma)

        val out = FloatArray(pixels.size)
        var peak = 0f
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val argb = pixels[i]
                // Transparent pixels are not subject: on a cut-out that has already been through
                // this once, the discarded background must not come back.
                if ((argb ushr 24) and 0xFF < TRANSPARENT) continue
                val dx = x - cx
                val dy = y - cy
                val prior = exp(-(dx * dx + dy * dy) * inv2s2)
                val value = distanceToBackground[bin(argb)] * prior
                out[i] = value
                if (value > peak) peak = value
            }
        }
        if (peak > 0f) for (i in out.indices) out[i] /= peak
        return out
    }

    /**
     * Which coarse colours appear in the band around the edge of the frame.
     *
     * A band rather than a one-pixel outline: a single row of pixels is dominated by whatever the
     * camera did to the very edge of the sensor, and a vignette or a compression artefact there
     * would define the whole background model.
     */
    private fun borderColorCube(pixels: IntArray, width: Int, height: Int): BooleanArray {
        val band = (minOf(width, height) / BORDER_DIVISOR).coerceAtLeast(1)
        val counts = IntArray(BINS * BINS * BINS)
        var total = 0
        for (y in 0 until height) {
            val edgeRow = y < band || y >= height - band
            for (x in 0 until width) {
                if (!edgeRow && x >= band && x < width - band) continue
                val argb = pixels[y * width + x]
                if ((argb ushr 24) and 0xFF < TRANSPARENT) continue
                counts[bin(argb)]++
                total++
            }
        }
        // A colour that appears a handful of times in the band is the subject's hair overlapping the
        // edge, not the background; taking it would punch a hole through the subject.
        val floor = (total / RARE_FRACTION).coerceAtLeast(1)
        return BooleanArray(counts.size) { counts[it] >= floor }
    }

    /**
     * Distance from every cell of the colour cube to the nearest background cell, in 0..1.
     *
     * A three-dimensional chamfer sweep: two passes over 4096 cells, against the alternative of
     * comparing every cell to every background cell. Exact enough at this resolution that the extra
     * accuracy of a true Euclidean transform would not change a single thresholded pixel.
     */
    private fun distanceField(background: BooleanArray): FloatArray {
        val far = (BINS * 3).toFloat()
        val d = FloatArray(background.size) { if (background[it]) 0f else far }
        if (background.none { it }) return FloatArray(background.size) { 1f }

        fun at(r: Int, g: Int, b: Int) = (r * BINS + g) * BINS + b

        for (r in 0 until BINS) for (g in 0 until BINS) for (b in 0 until BINS) {
            val i = at(r, g, b)
            var v = d[i]
            if (r > 0) v = minOf(v, d[at(r - 1, g, b)] + 1f)
            if (g > 0) v = minOf(v, d[at(r, g - 1, b)] + 1f)
            if (b > 0) v = minOf(v, d[at(r, g, b - 1)] + 1f)
            d[i] = v
        }
        for (r in BINS - 1 downTo 0) for (g in BINS - 1 downTo 0) for (b in BINS - 1 downTo 0) {
            val i = at(r, g, b)
            var v = d[i]
            if (r < BINS - 1) v = minOf(v, d[at(r + 1, g, b)] + 1f)
            if (g < BINS - 1) v = minOf(v, d[at(r, g + 1, b)] + 1f)
            if (b < BINS - 1) v = minOf(v, d[at(r, g, b + 1)] + 1f)
            d[i] = v
        }
        val scale = 1f / sqrt(3f * BINS * BINS)
        return FloatArray(d.size) { (d[it] * scale).coerceIn(0f, 1f) }
    }

    private fun bin(argb: Int): Int {
        val r = ((argb shr 16) and 0xFF) * BINS / 256
        val g = ((argb shr 8) and 0xFF) * BINS / 256
        val b = (argb and 0xFF) * BINS / 256
        return (r * BINS + g) * BINS + b
    }

    // ---- thresholding ----------------------------------------------------------------------------

    /**
     * Otsu's threshold: the split that minimises variance within the two halves.
     *
     * A fixed cut cannot work here, because the scale of the saliency map depends entirely on the
     * photograph — a red coat against grey concrete separates at a value that would take the whole
     * frame in a picture of a grey coat against grey concrete. Otsu asks the histogram where the
     * gap actually is.
     */
    internal fun otsu(values: FloatArray): Float {
        val histogram = IntArray(LEVELS)
        for (v in values) histogram[(v.coerceIn(0f, 1f) * (LEVELS - 1)).roundToInt()]++

        val total = values.size.toDouble()
        var sum = 0.0
        for (i in 0 until LEVELS) sum += i.toDouble() * histogram[i]

        var sumBelow = 0.0
        var weightBelow = 0.0
        var best = 0.0
        var bestLevel = 0
        for (level in 0 until LEVELS) {
            weightBelow += histogram[level]
            if (weightBelow == 0.0) continue
            val weightAbove = total - weightBelow
            if (weightAbove <= 0.0) break
            sumBelow += level.toDouble() * histogram[level]
            val meanBelow = sumBelow / weightBelow
            val meanAbove = (sum - sumBelow) / weightAbove
            val between = weightBelow * weightAbove * (meanBelow - meanAbove) * (meanBelow - meanAbove)
            if (between > best) {
                best = between
                bestLevel = level
            }
        }
        return bestLevel.toFloat() / (LEVELS - 1)
    }

    // ---- regions ---------------------------------------------------------------------------------

    /**
     * Drops the specks, keeps the subject — and keeps a second subject when there is one.
     *
     * Keeping only the largest region is the version that fails on the photograph people actually
     * bring: two people side by side, and one of them silently disappears.
     */
    private fun keepSubstantialRegions(inside: BooleanArray, width: Int, height: Int) {
        val label = IntArray(inside.size) { -1 }
        val areas = ArrayList<Int>()
        val stack = IntArray(inside.size)

        for (start in inside.indices) {
            if (!inside[start] || label[start] >= 0) continue
            val id = areas.size
            var area = 0
            var top = 0
            label[start] = id
            stack[top++] = start
            // Labelled on the way in rather than on the way out, so no pixel is ever on the stack
            // twice and the stack can be sized once at the number of pixels.
            while (top > 0) {
                val i = stack[--top]
                area++
                val x = i % width
                val y = i / width
                if (x > 0) top = claim(inside, label, stack, top, i - 1, id)
                if (x < width - 1) top = claim(inside, label, stack, top, i + 1, id)
                if (y > 0) top = claim(inside, label, stack, top, i - width, id)
                if (y < height - 1) top = claim(inside, label, stack, top, i + width, id)
            }
            areas += area
        }
        if (areas.isEmpty()) return

        val largest = areas.max()
        val floor = maxOf((largest * COMPANION_FRACTION).toInt(), (inside.size * SPECK_FRACTION).toInt(), 1)
        for (i in inside.indices) {
            if (inside[i] && areas[label[i]] < floor) inside[i] = false
        }
    }

    /**
     * Anything enclosed by the subject belongs to it.
     *
     * A gap between an arm and a body is background and stays out; the dark inside of a hood is
     * background-*coloured* but is enclosed, and taking it out leaves a hole straight through the
     * person. The test is reachability from the frame edge, which distinguishes the two exactly.
     */
    private fun fillHoles(inside: BooleanArray, width: Int, height: Int) {
        val reachable = BooleanArray(inside.size)
        val stack = IntArray(inside.size)
        var top = 0

        for (x in 0 until width) {
            top = seed(inside, reachable, stack, top, x)
            top = seed(inside, reachable, stack, top, (height - 1) * width + x)
        }
        for (y in 0 until height) {
            top = seed(inside, reachable, stack, top, y * width)
            top = seed(inside, reachable, stack, top, y * width + width - 1)
        }

        while (top > 0) {
            val i = stack[--top]
            val x = i % width
            val y = i / width
            if (x > 0) top = seed(inside, reachable, stack, top, i - 1)
            if (x < width - 1) top = seed(inside, reachable, stack, top, i + 1)
            if (y > 0) top = seed(inside, reachable, stack, top, i - width)
            if (y < height - 1) top = seed(inside, reachable, stack, top, i + width)
        }

        for (i in inside.indices) if (!inside[i] && !reachable[i]) inside[i] = true
    }

    private fun seed(
        inside: BooleanArray,
        reachable: BooleanArray,
        stack: IntArray,
        top: Int,
        i: Int,
    ): Int {
        if (inside[i] || reachable[i]) return top
        reachable[i] = true
        stack[top] = i
        return top + 1
    }

    private fun claim(
        inside: BooleanArray,
        label: IntArray,
        stack: IntArray,
        top: Int,
        i: Int,
        id: Int,
    ): Int {
        if (!inside[i] || label[i] >= 0) return top
        label[i] = id
        stack[top] = i
        return top + 1
    }

    /** Below this there is no border band to learn from and no subject worth finding. */
    private const val MIN_SIZE = 8

    /** Sixteen levels a channel: coarse enough to merge sensor noise, fine enough to keep hue. */
    private const val BINS = 16

    /** A twenty-fifth of the short edge — a band, not an outline. */
    private const val BORDER_DIVISOR = 25

    /** Below one part in this many, a border colour is the subject overlapping the edge. */
    private const val RARE_FRACTION = 400

    /** Alpha under this is treated as absent rather than as a dark pixel. */
    private const val TRANSPARENT = 8

    /** The centre prior's width, as a fraction of the half-diagonal. */
    private const val CENTRE_SIGMA = 0.75f

    /** A region this large relative to the biggest is a second subject, not a speck. */
    private const val COMPANION_FRACTION = 0.25f

    /** And nothing under this fraction of the frame survives at all. */
    private const val SPECK_FRACTION = 0.001f

    private const val LEVELS = 256
}
