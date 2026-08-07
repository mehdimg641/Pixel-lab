package ir.pixellab.core.paint

import ir.pixellab.core.model.Vec2
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Quick Selection — the tool most people reach for first, and the one that made the magic wand feel
 * obsolete.
 *
 * The wand answers "which pixels match *this one*". That is the wrong question on a photograph,
 * because a shirt is fifty shades and a single sample sits at one of them, so the tolerance has to be
 * cranked until the selection leaks into the wall. Quick Selection asks a better one: **the user
 * drags across a region, and the region tells us what it looks like.**
 *
 * Two things follow from that, and both are what make it feel intelligent:
 *
 * - **The model is built from everything under the stroke**, not from a point. A drag across a shirt
 *   collects all fifty shades, so the selection covers the shirt and still stops at the wall.
 * - **It grows outward and stops at edges.** Region growing from the stroke, where the cost of
 *   crossing into a pixel includes how *different* that pixel is from its neighbour — so a soft
 *   gradient is cheap to cross and a hard boundary is not. That is why it snaps to the subject
 *   rather than to a colour.
 *
 * Written against a plain `IntArray` so the whole thing is testable without Android, like every other
 * selection tool here.
 */
object QuickSelect {

    /**
     * Grows a selection out from a stroke.
     *
     * @param stroke the points the finger passed through, in pixels. Every pixel within [radius] of
     *   any of them seeds the region and contributes to its colour model.
     * @param tolerance how far a pixel may sit from the model and still be admitted, in the same
     *   0..255 colour distance the wand's slider means. Larger reaches further into similar tones.
     * @param existing a selection to grow rather than replace, which is what a second drag does.
     */
    fun select(
        pixels: IntArray,
        width: Int,
        height: Int,
        stroke: List<Vec2>,
        radius: Float,
        tolerance: Float = DEFAULT_TOLERANCE,
        existing: PixelSelection? = null,
    ): PixelSelection {
        require(pixels.size >= width * height) { "pixels do not match ${width}x$height" }
        if (stroke.isEmpty() || radius <= 0f) return existing ?: PixelSelection.nothing(width, height)

        val seeds = seedsUnder(stroke, radius, width, height)
        if (seeds.isEmpty()) return existing ?: PixelSelection.nothing(width, height)

        val model = ColourModel.of(pixels, seeds)
        val limit = tolerance.coerceAtLeast(1f)

        // Dijkstra outward from the seeds, where the cost of entering a pixel is how unlike the
        // model it is *plus* how sharp the step into it was. The second term is the edge stop: a
        // boundary is expensive to cross even when both sides resemble the model, which is what
        // keeps a selection of a shirt from flooding into a same-coloured sleeve behind it.
        val cost = FloatArray(width * height) { Float.MAX_VALUE }
        val queue = ArrayDeque<Int>()
        for (seed in seeds) {
            cost[seed] = 0f
            queue += seed
        }

        // A bucketed sweep rather than a heap: the costs are bounded and coarse, and a heap of a few
        // million entries allocates more than the image does. Repeated relaxation over a queue
        // converges in a handful of passes for the connected regions this tool produces.
        var guard = 0
        while (queue.isNotEmpty() && guard < MAX_RELAXATIONS * width * height) {
            val here = queue.removeFirst()
            guard++
            val hx = here % width
            val hy = here / width
            val hereCost = cost[here]
            if (hereCost > limit) continue

            for (step in NEIGHBOURS) {
                val nx = hx + step[0]
                val ny = hy + step[1]
                if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                val there = ny * width + nx

                val unlike = model.distance(pixels[there])
                val gradient = difference(pixels[here], pixels[there])
                // The **worst** barrier along the path, not the sum of them. This is a bottleneck
                // shortest path, and the distinction is the whole behaviour of the tool: summing
                // makes a long gentle gradient cost as much as one hard edge, so a stroke on a
                // shaded wall stops halfway across it for no reason a user could see. Taking the
                // maximum asks the question that actually matters — was there ever a barrier
                // between here and the stroke — and answers it the same however far away "here" is.
                //
                // The gradient joins the colour distance inside that maximum rather than outside it,
                // so a sharp edge blocks even where both sides match the model perfectly.
                val next = max(hereCost, unlike + gradient * EDGE_WEIGHT)
                if (next + EPSILON < cost[there]) {
                    cost[there] = next
                    if (next <= limit) queue += there
                }
            }
        }

        val coverage = ByteArray(width * height)
        for (i in coverage.indices) {
            val c = cost[i]
            if (c >= Float.MAX_VALUE) continue
            // Soft at the boundary for the same reason every other selection here is: a binary edge
            // is visible at any zoom, and the last few percent of the tolerance is exactly where the
            // uncertainty lives.
            val t = ((limit - c) / (limit * SOFT_FRACTION)).coerceIn(0f, 1f)
            coverage[i] = (t * 255f).toInt().coerceIn(0, 255).toByte()
        }

        val grown = PixelSelection(width, height, coverage)
        return existing?.combine(grown, SelectionMode.ADD) ?: grown
    }

    /** Pixel indices within [radius] of the stroke — the seeds, and the colour model's sample. */
    private fun seedsUnder(stroke: List<Vec2>, radius: Float, width: Int, height: Int): IntArray {
        val marked = HashSet<Int>()
        val r = radius.coerceAtLeast(1f)
        val rr = r * r
        for (p in stroke) {
            val x0 = max(0, (p.x - r).toInt())
            val y0 = max(0, (p.y - r).toInt())
            val x1 = min(width - 1, (p.x + r).toInt())
            val y1 = min(height - 1, (p.y + r).toInt())
            for (y in y0..y1) {
                for (x in x0..x1) {
                    val dx = x + 0.5f - p.x
                    val dy = y + 0.5f - p.y
                    if (dx * dx + dy * dy <= rr) marked += y * width + x
                }
            }
        }
        return marked.toIntArray()
    }

    /**
     * What the stroke looks like, as a set of clusters.
     *
     * Not a single mean. A drag across a striped shirt collects two very different colours, and their
     * average is a colour that appears nowhere in the picture — so a mean-based model would either
     * select nothing or, with the tolerance raised to compensate, select everything. Clustering keeps
     * the stripes as two answers and asks each pixel for its distance to the *nearest* one.
     */
    private class ColourModel(private val centres: Array<FloatArray>) {

        fun distance(pixel: Int): Float {
            val r = ((pixel shr 16) and 0xFF).toFloat()
            val g = ((pixel shr 8) and 0xFF).toFloat()
            val b = (pixel and 0xFF).toFloat()
            var best = Float.MAX_VALUE
            for (c in centres) {
                val dr = r - c[0]
                val dg = g - c[1]
                val db = b - c[2]
                val d = sqrt(dr * dr + dg * dg + db * db)
                if (d < best) best = d
            }
            return best
        }

        companion object {
            /**
             * K-means over the sampled pixels, seeded by spreading the initial centres apart.
             *
             * Seeded rather than randomised so the same stroke always produces the same selection —
             * a tool that gave a different answer to the same drag would be untrustworthy, and undo
             * followed by redo would produce two different pictures.
             */
            fun of(pixels: IntArray, seeds: IntArray): ColourModel {
                val sample = if (seeds.size <= MAX_SAMPLE) seeds else IntArray(MAX_SAMPLE) {
                    seeds[it * seeds.size / MAX_SAMPLE]
                }
                val k = min(CLUSTERS, sample.size)
                val centres = Array(k) { i ->
                    val p = pixels[sample[i * sample.size / k]]
                    floatArrayOf(
                        ((p shr 16) and 0xFF).toFloat(),
                        ((p shr 8) and 0xFF).toFloat(),
                        (p and 0xFF).toFloat(),
                    )
                }

                repeat(ITERATIONS) {
                    val sums = Array(k) { FloatArray(3) }
                    val counts = IntArray(k)
                    for (index in sample) {
                        val p = pixels[index]
                        val r = ((p shr 16) and 0xFF).toFloat()
                        val g = ((p shr 8) and 0xFF).toFloat()
                        val b = (p and 0xFF).toFloat()
                        var best = 0
                        var bestDistance = Float.MAX_VALUE
                        for (c in centres.indices) {
                            val dr = r - centres[c][0]
                            val dg = g - centres[c][1]
                            val db = b - centres[c][2]
                            val d = dr * dr + dg * dg + db * db
                            if (d < bestDistance) {
                                bestDistance = d
                                best = c
                            }
                        }
                        sums[best][0] += r
                        sums[best][1] += g
                        sums[best][2] += b
                        counts[best]++
                    }
                    for (c in centres.indices) {
                        if (counts[c] == 0) continue
                        for (channel in 0 until 3) centres[c][channel] = sums[c][channel] / counts[c]
                    }
                }
                return ColourModel(centres)
            }
        }
    }

    /** Largest single-channel step between two pixels — a cheap, well-behaved edge measure. */
    private fun difference(a: Int, b: Int): Float {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return max(dr, max(dg, db)).toFloat()
    }

    private val NEIGHBOURS = arrayOf(
        intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1),
    )

    /** Photoshop's default is around here too; it is a distance in 0..255, not a percentage. */
    private const val DEFAULT_TOLERANCE = 40f

    /**
     * How much an edge adds to the cost of crossing it.
     *
     * The number that decides whether the tool feels like a wand or like Quick Selection. Too low and
     * it floods past boundaries; too high and it cannot leave the stroke at all.
     */
    private const val EDGE_WEIGHT = 0.55f

    /** The last fifth of the tolerance is the soft edge. */
    private const val SOFT_FRACTION = 0.2f

    private const val CLUSTERS = 4
    private const val ITERATIONS = 6

    /** Enough to characterise a region; past it the clusters stop moving. */
    private const val MAX_SAMPLE = 2000

    /** A backstop against a pathological image keeping the relaxation queue alive forever. */
    private const val MAX_RELAXATIONS = 8

    private const val EPSILON = 1e-3f
}
