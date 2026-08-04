package ir.pixellab.core.imaging

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The filter gallery: making a photograph look like it was made by hand.
 *
 * Every reference app has a row of these and most of them are a colour curve with a paper texture
 * laid over the top — which is why they all look like the same filter at different hues, and why
 * none of them survives being zoomed in on. The four here are the real algorithms, and each one is
 * doing something structural to the picture rather than something to its colours:
 *
 * - [oilPaint] replaces each pixel with the *most common* tone near it, so edges become brush ends.
 * - [waterColour] pushes colour to flat regions and darkens where regions meet, which is the pigment
 *   pooling at the edge of a wash.
 * - [colouredPencil] finds the picture's own gradients and draws along them.
 * - [crystallize] partitions the picture into cells and averages each, which is stained glass.
 *
 * All four are separable-free and O(radius²) per pixel; the radii that look good are small (2–8), so
 * that is affordable. The one that is not is [crystallize], which is O(cells) with a spatial bucket.
 */
object Artistic {

    /**
     * Oil paint — Photoshop's, and Kuwahara's before it.
     *
     * The idea that makes this work and that a blur cannot: for each pixel, look at the tones nearby,
     * find which tone occurs *most often*, and take the average colour of the pixels that had it.
     * The mode rather than the mean is the whole thing. A mean smears an edge; a mode picks a side.
     * That is why the result has visible brush ends and flat facets instead of looking out of focus.
     *
     * @param radius how far a brush stroke reaches, in pixels.
     * @param levels how many tones are distinguished. Fewer means broader, flatter strokes — this is
     *   Photoshop's Stylization slider, and it is the one that changes the character rather than the
     *   scale.
     */
    fun oilPaint(src: Raster, radius: Int = 4, levels: Int = 20): Raster {
        require(src.channels >= 3) { "oil paint needs colour, got ${src.channels} channels" }
        require(radius >= 1) { "radius must be at least 1, got $radius" }
        require(levels in 2..256) { "levels must be 2..256, got $levels" }

        val out = src.copy()
        val luma = src.luminance()
        // Reused across pixels rather than allocated per pixel: at 12 megapixels that is twelve
        // million short-lived arrays, and the allocation costs more than the filter does.
        val count = IntArray(levels)
        val sums = FloatArray(levels * 3)

        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                count.fill(0)
                sums.fill(0f)

                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        // A disc, not a square. A square window makes the strokes rectangular and
                        // axis-aligned, which reads as a compression artefact rather than as paint.
                        if (dx * dx + dy * dy > radius * radius) continue
                        val sx = (x + dx).coerceIn(0, src.width - 1)
                        val sy = (y + dy).coerceIn(0, src.height - 1)
                        val bin = (luma[sx, sy] * (levels - 1)).toInt().coerceIn(0, levels - 1)
                        count[bin]++
                        val o = src.index(sx, sy)
                        sums[bin * 3] += src.data[o]
                        sums[bin * 3 + 1] += src.data[o + 1]
                        sums[bin * 3 + 2] += src.data[o + 2]
                    }
                }

                var best = 0
                for (i in 1 until levels) if (count[i] > count[best]) best = i
                val n = max(1, count[best]).toFloat()
                val o = out.index(x, y)
                out.data[o] = sums[best * 3] / n
                out.data[o + 1] = sums[best * 3 + 1] / n
                out.data[o + 2] = sums[best * 3 + 2] / n
            }
        }
        return out
    }

    /**
     * Watercolour.
     *
     * Two things make a watercolour recognisable and neither is a colour shift. Pigment settles into
     * **flat washes** with hard boundaries, and it **pools darker where two washes meet** because the
     * water carries pigment to the drying edge.
     *
     * So: a bilateral filter for the washes — it flattens within a region and refuses to cross an
     * edge, which is what a wash is — and then the picture's own gradient magnitude multiplied back
     * in to darken the boundaries. The second half is what most implementations leave out, and
     * without it the result is just a soft photograph.
     *
     * @param detail 0 keeps almost nothing of the original texture, 1 keeps most of it.
     * @param pooling how strongly pigment gathers at the edges.
     */
    fun waterColour(
        src: Raster,
        radius: Int = 5,
        detail: Float = 0.35f,
        pooling: Float = 0.5f,
    ): Raster {
        require(src.channels >= 3) { "watercolour needs colour, got ${src.channels} channels" }

        // The washes. A wide range sigma flattens hard; the spatial radius sets how large a wash is.
        val washed = Blur.bilateral(src, sigmaSpace = radius.toFloat(), sigmaRange = WASH_RANGE)
        val out = washed.copy()

        // A second pass, because one bilateral leaves too much texture to read as paint and two
        // leaves the edges exactly where they were — which is what makes the boundaries printable.
        val flattened = Blur.bilateral(washed, sigmaSpace = radius.toFloat(), sigmaRange = WASH_RANGE)

        // **The gradient of the wash, not of the photograph.** Pigment pools where two washes meet,
        // and a wash boundary is a feature of the flattened picture. Reading the source's gradient
        // instead — which is what this did first — pools on every speck of sensor noise, so a
        // slightly grainy photograph comes out uniformly muddy instead of edge-darkened. The
        // difference is invisible on a clean studio shot and ruins every phone photograph taken
        // indoors, which is most of them.
        val edges = gradientMagnitude(flattened.luminance())
        val keep = detail.coerceIn(0f, 1f)
        val pool = pooling.coerceIn(0f, 1f)

        for (i in 0 until src.pixelCount) {
            // Where the edge is strong the pigment is darker; elsewhere untouched. Multiplicative
            // rather than subtractive so a dark wash does not go black and a light one still shows
            // its boundary — which is exactly how the pigment behaves.
            val darkening = 1f - pool * min(1f, edges.data[i] * EDGE_GAIN)
            val o = i * src.channels
            for (c in 0 until min(3, src.channels)) {
                val painted = flattened.data[o + c] * (1f - keep) + washed.data[o + c] * keep
                out.data[o + c] = (painted * darkening).coerceIn(0f, 1f)
            }
        }
        return out
    }

    /**
     * Coloured pencil.
     *
     * A pencil follows the form: the strokes run *along* what is being drawn, not in one fixed
     * direction. So the stroke direction at each pixel is taken from the picture's own gradient,
     * turned ninety degrees — the direction of no change, which is the direction a contour runs — and
     * the pixel is smeared along it. Strokes in a single fixed direction are what a hatching filter
     * does, and it is why hatching filters make every photograph look like the same drawing.
     *
     * The paper shows through where the picture is light, which is what makes it read as a drawing
     * on paper rather than as a photograph with lines on it.
     *
     * @param length how long a pencil stroke is, in pixels.
     * @param pressure how dark the strokes are against the paper.
     */
    fun colouredPencil(
        src: Raster,
        length: Int = 6,
        pressure: Float = 0.7f,
        paper: Float = 0.92f,
    ): Raster {
        require(src.channels >= 3) { "coloured pencil needs colour, got ${src.channels} channels" }
        require(length >= 1) { "length must be at least 1, got $length" }

        val luma = src.luminance()
        val out = src.copy()
        val force = pressure.coerceIn(0f, 1f)
        val flow = strokeField(luma, length)

        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                val i = y * src.width + x
                val angle = flow.data[i * 2]
                val coherence = flow.data[i * 2 + 1]
                // A flat area has no contour to follow, so the direction there is arbitrary and
                // picking one would comb every sky in the same direction. A deterministic hash of
                // the position instead: the strokes scatter the way a hand's would.
                val chosen = if (coherence < COHERENCE_THRESHOLD) hashAngle(x, y) else angle
                val dx = cos(chosen)
                val dy = sin(chosen)

                var r = 0f
                var g = 0f
                var b = 0f
                var n = 0
                for (t in -length..length) {
                    val sx = (x + dx * t).toInt().coerceIn(0, src.width - 1)
                    val sy = (y + dy * t).toInt().coerceIn(0, src.height - 1)
                    val o = src.index(sx, sy)
                    r += src.data[o]
                    g += src.data[o + 1]
                    b += src.data[o + 2]
                    n++
                }
                val inv = 1f / n
                val o = out.index(x, y)
                // The stroke's colour, then lifted toward the paper by how light the picture is —
                // a pencil leaves the paper showing in the highlights and cannot draw white.
                val lift = (luma[x, y] * paper).coerceIn(0f, 1f) * (1f - force)
                out.data[o] = (r * inv * (1f - lift) + lift).coerceIn(0f, 1f)
                out.data[o + 1] = (g * inv * (1f - lift) + lift).coerceIn(0f, 1f)
                out.data[o + 2] = (b * inv * (1f - lift) + lift).coerceIn(0f, 1f)
            }
        }
        return out
    }

    /**
     * Crystallize — the picture as coloured cells, like stained glass.
     *
     * Photoshop's is a Voronoi partition of jittered cell centres, and jittered rather than a plain
     * grid is the entire difference between stained glass and a mosaic of squares. Each cell takes
     * the average colour of the pixels inside it.
     *
     * Nearest-centre is found by looking only at the nine grid neighbours rather than at every
     * centre. With centres jittered by at most half a cell, no pixel's nearest centre can lie
     * further than one cell away, so the answer is exact rather than approximate — which matters,
     * because a wrong nearest centre shows up as a single pixel of the wrong colour on a cell
     * boundary and reads as damage.
     *
     * @param size roughly how wide a cell is, in pixels.
     */
    fun crystallize(src: Raster, size: Int = 12, seed: Int = 0): Raster {
        require(size >= 2) { "cell size must be at least 2, got $size" }

        val columns = (src.width + size - 1) / size
        val rows = (src.height + size - 1) / size
        val centreX = FloatArray(columns * rows)
        val centreY = FloatArray(columns * rows)
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val i = row * columns + column
                // Half a cell of jitter, so a centre never leaves its own grid square — which is
                // what makes the nine-neighbour search exact.
                centreX[i] = (column + HALF + jitter(column, row, seed) * HALF) * size
                centreY[i] = (row + HALF + jitter(row, column, seed + 1) * HALF) * size
            }
        }

        val channels = src.channels
        val sums = FloatArray(columns * rows * channels)
        val counts = IntArray(columns * rows)
        val owner = IntArray(src.pixelCount)

        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                val column = x / size
                val row = y / size
                var best = -1
                var bestDistance = Float.MAX_VALUE
                for (ry in max(0, row - 1)..min(rows - 1, row + 1)) {
                    for (rx in max(0, column - 1)..min(columns - 1, column + 1)) {
                        val i = ry * columns + rx
                        val ddx = centreX[i] - x
                        val ddy = centreY[i] - y
                        val d = ddx * ddx + ddy * ddy
                        if (d < bestDistance) {
                            bestDistance = d
                            best = i
                        }
                    }
                }
                owner[y * src.width + x] = best
                counts[best]++
                val o = src.index(x, y)
                for (c in 0 until channels) sums[best * channels + c] += src.data[o + c]
            }
        }

        val out = src.like()
        for (i in 0 until src.pixelCount) {
            val cell = owner[i]
            val n = max(1, counts[cell]).toFloat()
            for (c in 0 until channels) out.data[i * channels + c] = sums[cell * channels + c] / n
        }
        return out
    }

    /**
     * Which way the strokes run, and how sure the picture is about it.
     *
     * From the **smoothed structure tensor** rather than from the per-pixel gradient, and the
     * difference is the one thing that makes this look drawn rather than scratched. A raw gradient
     * is zero on either side of an edge — one pixel in from a hard boundary the picture is flat —
     * so those pixels fall back to a random direction and smear *across* the edge they are next to,
     * dragging the dark side into the light one. A hand does the opposite: the strokes beside a form
     * follow that form.
     *
     * Averaging `gx²`, `gx·gy` and `gy²` over a neighbourhood — which is what the tensor is — lets a
     * flat pixel inherit the orientation of the structure near it. Averaging the gradient *vectors*
     * instead would not work, because a ridge has opposite gradients on its two sides and they
     * cancel; the products do not, which is the whole reason the tensor is built from them.
     *
     * @return two floats per pixel: the contour angle, and the coherence in 0..1 — how strongly
     *   oriented the neighbourhood is, so a caller can tell a contour from a flat area.
     */
    private fun strokeField(luma: Raster, radius: Int): Raster {
        val products = Raster(luma.width, luma.height, 3)
        for (y in 0 until luma.height) {
            for (x in 0 until luma.width) {
                val gx = luma.clamped(x + 1, y) - luma.clamped(x - 1, y)
                val gy = luma.clamped(x, y + 1) - luma.clamped(x, y - 1)
                val o = products.index(x, y)
                products.data[o] = gx * gx
                products.data[o + 1] = gx * gy
                products.data[o + 2] = gy * gy
            }
        }
        // The smoothing radius is the stroke length: a stroke should follow the form over the
        // distance it actually covers, not over one pixel.
        val tensor = Blur.gaussian(products, max(1f, radius / 2f))

        val out = Raster(luma.width, luma.height, 2)
        for (i in 0 until luma.pixelCount) {
            val jxx = tensor.data[i * 3]
            val jxy = tensor.data[i * 3 + 1]
            val jyy = tensor.data[i * 3 + 2]
            // Dominant orientation of the tensor — the direction of greatest change. A quarter turn
            // off it is the contour, which is where the pencil goes.
            val dominant = 0.5f * kotlin.math.atan2(2f * jxy, jxx - jyy)
            out.data[i * 2] = dominant + QUARTER_TURN

            // Eigenvalue spread over their sum: 1 where the neighbourhood is a clean edge, 0 where
            // it is flat or isotropic.
            val trace = jxx + jyy
            val difference = sqrt((jxx - jyy) * (jxx - jyy) + 4f * jxy * jxy)
            out.data[i * 2 + 1] = if (trace < TENSOR_EPSILON) 0f else difference / trace
        }
        return out
    }

    /** Central-difference gradient magnitude of a single-channel raster. */
    private fun gradientMagnitude(source: Raster): Raster {
        val out = source.like(1)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val gx = source.clamped(x + 1, y) - source.clamped(x - 1, y)
                val gy = source.clamped(x, y + 1) - source.clamped(x, y - 1)
                out.data[y * source.width + x] = sqrt(gx * gx + gy * gy)
            }
        }
        return out
    }

    /**
     * A stroke direction for a flat area, from the position alone.
     *
     * Deterministic so the same picture filters to the same result twice — a filter whose output
     * moved between two runs would make before-and-after comparison meaningless, and would show as
     * crawling if it were ever animated.
     */
    private fun hashAngle(x: Int, y: Int): Float {
        var h = x * PRIME_X xor y * PRIME_Y
        h = h xor (h ushr SHIFT)
        return (abs(h % HASH_RANGE) / HASH_RANGE.toFloat()) * TAU
    }

    /** Deterministic jitter in −1..1 for a cell centre. */
    private fun jitter(a: Int, b: Int, seed: Int): Float {
        var h = a * PRIME_X xor b * PRIME_Y xor seed * PRIME_SEED
        h = h xor (h ushr SHIFT)
        return (abs(h % HASH_RANGE) / HASH_RANGE.toFloat()) * 2f - 1f
    }

    /**
     * Below this the neighbourhood has no dominant direction, so the angle is noise.
     *
     * On coherence rather than on gradient magnitude: a faint but clean edge is worth following and
     * a strong but isotropic patch of grain is not, and magnitude cannot tell those apart.
     */
    private const val COHERENCE_THRESHOLD = 0.35f

    /** A tensor this small is numerically zero; dividing by its trace would be dividing by noise. */
    private const val TENSOR_EPSILON = 1e-9f

    private const val QUARTER_TURN = 1.5707964f

    /** How hard a wash flattens. Wide, because a wash is meant to lose the picture's texture. */
    private const val WASH_RANGE = 0.25f

    /** Gradient magnitudes are small; this brings a visible edge to full pooling. */
    private const val EDGE_GAIN = 6f

    private const val HALF = 0.5f
    private const val TAU = 6.2831855f
    private const val PRIME_X = 374761393
    private const val PRIME_Y = 668265263
    private const val PRIME_SEED = 1274126177
    private const val SHIFT = 13
    private const val HASH_RANGE = 65536
}
