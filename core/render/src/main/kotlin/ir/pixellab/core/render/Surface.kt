package ir.pixellab.core.render

import kotlin.math.exp
import kotlin.math.sqrt

/**
 * A float RGBA image, straight alpha, in the sRGB space the document is authored in.
 *
 * Every intermediate step of a layer style lives in one of these rather than in bytes, and that is
 * the single largest thing separating this from Photoshop's output. A layer style is not one
 * operation: a face goes through an overlay, a pattern, an inner shadow and a bevel, and the result
 * is composited over an extrusion, over two shadows, under a stroke. Rounding to eight bits at each
 * of those steps is not one rounding error — the errors accumulate, and they accumulate *coherently*
 * across a smooth ramp, which is what turns a gradient into visible bands and shifts a colour far
 * enough from the swatch the user picked to be seen beside the original.
 *
 * Float throughout, converted to bytes exactly once at the end.
 *
 * **Still sRGB, not linear**, and that is deliberate rather than an oversight. Photoshop's blend
 * modes operate on the values as authored, so blending in linear light would produce arithmetically
 * defensible results that differ visibly from the reference for every mode except Normal. Lighting
 * is the exception and is handled where it happens: the bevel converts to linear to compute its
 * shading and converts back, because a Lambert term on gamma-encoded values is simply wrong.
 */
internal class Surface(val width: Int, val height: Int) {

    /** Interleaved RGBA, four floats per pixel. One array rather than four for locality. */
    val data = FloatArray(width * height * CHANNELS)

    fun copy(): Surface {
        val out = Surface(width, height)
        data.copyInto(out.data)
        return out
    }

    fun alphaAt(index: Int) = data[index * CHANNELS + 3]

    companion object {
        const val CHANNELS = 4

        fun of(raster: Raster): Surface {
            val out = Surface(raster.width, raster.height)
            for (i in raster.pixels.indices) {
                val pixel = raster.pixels[i]
                val base = i * CHANNELS
                out.data[base] = ((pixel shr 16) and 0xFF) / 255f
                out.data[base + 1] = ((pixel shr 8) and 0xFF) / 255f
                out.data[base + 2] = (pixel and 0xFF) / 255f
                out.data[base + 3] = ((pixel ushr 24) and 0xFF) / 255f
            }
            return out
        }
    }

    fun toRaster(): Raster {
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val base = i * CHANNELS
            pixels[i] = (byte(data[base + 3]) shl 24) or
                (byte(data[base]) shl 16) or
                (byte(data[base + 1]) shl 8) or
                byte(data[base + 2])
        }
        return Raster(width, height, pixels)
    }

    private fun byte(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
}

/**
 * Signal work a layer style depends on: exact distances and true Gaussians.
 *
 * Both replace approximations that were good enough to look plausible and not good enough to match.
 */
internal object Signal {

    /**
     * The **exact** Euclidean distance from every pixel to the nearest set one.
     *
     * Felzenszwalb and Huttenlocher's transform: the squared distance along one axis is the lower
     * envelope of one parabola per pixel, and that envelope can be swept in linear time. Run down
     * the columns and then across the rows and the result is exact — not approximate, exact — in
     * two passes over the image.
     *
     * This replaces a chamfer transform, which propagates distance in steps of 1 and √2 and is
     * wrong by up to about four per cent on a diagonal. Four per cent sounds harmless and is not:
     * a stroke measured with it is visibly thicker on the diagonals of a letter than on its
     * verticals, and a bevel built on it has a shoulder whose width breathes as the outline turns.
     * Photoshop's do neither.
     */
    fun distance(mask: BooleanArray, width: Int, height: Int): FloatArray {
        val squared = FloatArray(width * height) { if (mask[it]) 0f else INFINITY }

        val column = FloatArray(height)
        for (x in 0 until width) {
            for (y in 0 until height) column[y] = squared[y * width + x]
            val done = envelope(column, height)
            for (y in 0 until height) squared[y * width + x] = done[y]
        }

        val row = FloatArray(width)
        for (y in 0 until height) {
            for (x in 0 until width) row[x] = squared[y * width + x]
            val done = envelope(row, width)
            for (x in 0 until width) squared[y * width + x] = done[x]
        }

        return FloatArray(squared.size) { sqrt(squared[it]) }
    }

    /**
     * The lower envelope of the parabolas rooted at each sample — the one-dimensional transform.
     *
     * `v` holds the parabolas currently on the envelope and `z` the boundaries between them. Each
     * new parabola either intersects the last one to the right of its boundary, in which case it
     * joins the envelope, or to the left, in which case it hides the last one and that one is
     * popped. Every parabola is pushed and popped at most once, which is what makes this linear.
     */
    private fun envelope(f: FloatArray, n: Int): FloatArray {
        val d = FloatArray(n)
        val v = IntArray(n)
        val z = FloatArray(n + 1)
        var k = 0
        v[0] = 0
        z[0] = -INFINITY
        z[1] = INFINITY

        for (q in 1 until n) {
            var s = intersect(f, q, v[k])
            while (s <= z[k]) {
                k--
                s = intersect(f, q, v[k])
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = INFINITY
        }

        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val dx = (q - v[k]).toFloat()
            d[q] = dx * dx + f[v[k]]
        }
        return d
    }

    private fun intersect(f: FloatArray, q: Int, p: Int): Float {
        // Two parabolas of equal curvature meet at one point; this is that point solved directly.
        // Both being infinite means neither is on the envelope, and the difference is a NaN — so
        // the far right is returned instead, which leaves the earlier one in place.
        if (f[q] == INFINITY && f[p] == INFINITY) return INFINITY
        return ((f[q] + q * q) - (f[p] + p * p)) / (2f * q - 2f * p)
    }

    /**
     * A true separable Gaussian.
     *
     * Three box passes approximate one to within a per cent by the central limit theorem, and the
     * per cent is not where they fail. A box blur has a compact support with a hard edge, so a
     * shadow made from three of them ends abruptly at a measurable radius; a Gaussian falls away
     * forever. Beside a Photoshop shadow the box version shows a faint ring where its support stops,
     * most visible exactly where these styles put it — a wide soft shadow under a heavy title.
     *
     * The kernel is truncated at three sigma, which holds 99.7% of the weight, and renormalised so
     * the truncation cannot darken the result.
     */
    fun gaussian(source: FloatArray, width: Int, height: Int, sigma: Float): FloatArray {
        if (sigma <= 0f) return source
        val radius = kotlin.math.ceil(sigma * TRUNCATION).toInt().coerceAtLeast(1)
        val kernel = FloatArray(radius * 2 + 1)
        var total = 0f
        for (i in -radius..radius) {
            val weight = exp(-(i * i) / (2f * sigma * sigma))
            kernel[i + radius] = weight
            total += weight
        }
        for (i in kernel.indices) kernel[i] /= total

        val once = pass(source, width, height, kernel, radius, horizontal = true)
        return pass(once, width, height, kernel, radius, horizontal = false)
    }

    private fun pass(
        source: FloatArray,
        width: Int,
        height: Int,
        kernel: FloatArray,
        radius: Int,
        horizontal: Boolean,
    ): FloatArray {
        val out = FloatArray(source.size)
        val outer = if (horizontal) height else width
        val inner = if (horizontal) width else height

        for (o in 0 until outer) {
            for (i in 0 until inner) {
                var sum = 0f
                for (k in -radius..radius) {
                    // Clamped at the edge rather than wrapped or zeroed: zeroing darkens a shadow
                    // that runs off the canvas, and wrapping brings the opposite side into it.
                    val at = (i + k).coerceIn(0, inner - 1)
                    val index = if (horizontal) o * width + at else at * width + o
                    sum += source[index] * kernel[k + radius]
                }
                out[if (horizontal) o * width + i else i * width + o] = sum
            }
        }
        return out
    }

    /**
     * sRGB's transfer function, both ways.
     *
     * The real curve, with the linear segment near black — not a plain 2.2 power. The two differ
     * most in the darkest few per cent, which is exactly where a bevel's shadow side lives, so the
     * shortcut shows up on the one part of the effect it is used for.
     */
    fun toLinear(v: Float): Float =
        if (v <= 0.04045f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()

    fun toSrgb(v: Float): Float =
        if (v <= 0.0031308f) v * 12.92f else 1.055f * Math.pow(v.toDouble(), 1.0 / 2.4).toFloat() - 0.055f

    private const val INFINITY = 1e20f

    /** Three sigma holds 99.7% of a Gaussian; past that the weights are below a byte's resolution. */
    private const val TRUNCATION = 3f
}
