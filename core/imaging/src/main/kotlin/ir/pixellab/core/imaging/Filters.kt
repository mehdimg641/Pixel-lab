package ir.pixellab.core.imaging

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Separable and edge-aware smoothing. */
object Blur {

    /**
     * Box blur by running sums: O(n) regardless of radius.
     *
     * The building block for everything else here — three box passes approximate a Gaussian
     * closely enough that the difference is invisible, at a fraction of the cost.
     */
    fun box(src: Raster, radius: Int): Raster {
        if (radius <= 0) return src.copy()
        val tmp = Raster(src.width, src.height, src.channels)
        val out = Raster(src.width, src.height, src.channels)
        horizontal(src, tmp, radius)
        vertical(tmp, out, radius)
        return out
    }

    /**
     * Gaussian blur via three successive box passes.
     *
     * Radii follow Kovesi's derivation so the result matches a true Gaussian of the requested sigma
     * to within a fraction of a percent.
     */
    fun gaussian(src: Raster, sigma: Float): Raster {
        if (sigma <= 0f) return src.copy()
        var out = src
        for (r in boxRadiiForGaussian(sigma, 3)) out = box(out, r)
        return out
    }

    /** True convolution with a sampled Gaussian; slower, used where exactness matters. */
    fun gaussianExact(src: Raster, sigma: Float): Raster {
        if (sigma <= 0f) return src.copy()
        val radius = kotlin.math.ceil(sigma * 3f).toInt()
        val kernel = FloatArray(radius * 2 + 1)
        var sum = 0f
        for (i in kernel.indices) {
            val d = (i - radius).toFloat()
            kernel[i] = exp(-(d * d) / (2f * sigma * sigma))
            sum += kernel[i]
        }
        for (i in kernel.indices) kernel[i] /= sum

        val tmp = Raster(src.width, src.height, src.channels)
        val out = Raster(src.width, src.height, src.channels)
        convolve(src, tmp, kernel, radius, horizontalPass = true)
        convolve(tmp, out, kernel, radius, horizontalPass = false)
        return out
    }

    /**
     * Bilateral filter — Photoshop's Surface Blur.
     *
     * Smooths within regions of similar value while leaving edges alone. Two uses here matter:
     * flattening skin texture without destroying the eyes and hairline, and removing texture from a
     * luminance channel before it is differentiated into normals, so pores do not become relief.
     *
     * @param sigmaSpace spatial falloff in pixels
     * @param sigmaRange how different two values may be and still be averaged together
     */
    fun bilateral(src: Raster, sigmaSpace: Float, sigmaRange: Float): Raster {
        require(sigmaSpace > 0f && sigmaRange > 0f) { "bilateral sigmas must be positive" }
        val radius = kotlin.math.ceil(sigmaSpace * 2f).toInt().coerceAtLeast(1)
        val spatial = FloatArray((radius * 2 + 1) * (radius * 2 + 1))
        var k = 0
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val d2 = (dx * dx + dy * dy).toFloat()
            spatial[k++] = exp(-d2 / (2f * sigmaSpace * sigmaSpace))
        }

        val out = Raster(src.width, src.height, src.channels)
        val inv2r2 = 1f / (2f * sigmaRange * sigmaRange)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                for (c in 0 until src.channels) {
                    val centre = src[x, y, c]
                    var acc = 0f
                    var weight = 0f
                    var ki = 0
                    for (dy in -radius..radius) {
                        for (dx in -radius..radius) {
                            val v = src.clamped(x + dx, y + dy, c)
                            val dv = v - centre
                            val w = spatial[ki++] * exp(-dv * dv * inv2r2)
                            acc += v * w
                            weight += w
                        }
                    }
                    out[x, y, c] = if (weight > 0f) acc / weight else centre
                }
            }
        }
        return out
    }

    /** Box radii whose triple application approximates a Gaussian of [sigma]. */
    internal fun boxRadiiForGaussian(sigma: Float, passes: Int): IntArray {
        val idealWidth = sqrt((12f * sigma * sigma / passes) + 1f)
        var wl = idealWidth.toInt()
        if (wl % 2 == 0) wl--
        val wu = wl + 2
        val mIdeal = (12f * sigma * sigma - (passes * wl * wl).toFloat() -
            (4f * passes * wl) - (3f * passes)) / (-4f * wl - 4f)
        val m = mIdeal.roundToInt()
        return IntArray(passes) { i -> ((if (i < m) wl else wu) - 1) / 2 }
    }

    private fun horizontal(src: Raster, dst: Raster, radius: Int) {
        val w = src.width
        val ch = src.channels
        val window = (radius * 2 + 1).toFloat()
        for (y in 0 until src.height) {
            for (c in 0 until ch) {
                var sum = 0f
                for (x in -radius..radius) sum += src.clamped(x, y, c)
                for (x in 0 until w) {
                    dst[x, y, c] = sum / window
                    sum += src.clamped(x + radius + 1, y, c) - src.clamped(x - radius, y, c)
                }
            }
        }
    }

    private fun vertical(src: Raster, dst: Raster, radius: Int) {
        val h = src.height
        val ch = src.channels
        val window = (radius * 2 + 1).toFloat()
        for (x in 0 until src.width) {
            for (c in 0 until ch) {
                var sum = 0f
                for (y in -radius..radius) sum += src.clamped(x, y, c)
                for (y in 0 until h) {
                    dst[x, y, c] = sum / window
                    sum += src.clamped(x, y + radius + 1, c) - src.clamped(x, y - radius, c)
                }
            }
        }
    }

    private fun convolve(src: Raster, dst: Raster, kernel: FloatArray, radius: Int, horizontalPass: Boolean) {
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                for (c in 0 until src.channels) {
                    var acc = 0f
                    for (i in kernel.indices) {
                        val d = i - radius
                        acc += kernel[i] * if (horizontalPass) src.clamped(x + d, y, c) else src.clamped(x, y + d, c)
                    }
                    dst[x, y, c] = acc
                }
            }
        }
    }
}

/**
 * Guided image filter (He, Sun and Tang, ECCV 2010).
 *
 * This is what makes hair-level cutouts practical on a phone. Photoshop's Refine Edge is built on
 * it rather than on closed-form matting, because the guided filter is linear in the number of
 * pixels and independent of kernel radius — sub-second on a six-megapixel image where the
 * closed-form solve takes about two minutes.
 *
 * It has a direct theoretical link to the matting Laplacian, so it is not a cheap approximation of
 * matting; it is the same problem restated in a form that can be solved in linear time.
 */
object GuidedFilter {

    /**
     * Refines [input] using the structure of a single-channel [guide].
     *
     * @param radius neighbourhood radius in pixels
     * @param epsilon regularisation; smaller preserves more edge detail, larger smooths more
     */
    fun filter(guide: Raster, input: Raster, radius: Int, epsilon: Float): Raster {
        require(guide.channels == 1 && input.channels == 1) { "grayscale guided filter needs single channels" }
        require(guide.sameShapeAs(input)) { "guide and input must have the same dimensions" }

        val meanI = Blur.box(guide, radius)
        val meanP = Blur.box(input, radius)
        val corrI = Blur.box(RasterMath.multiply(guide, guide), radius)
        val corrIp = Blur.box(RasterMath.multiply(guide, input), radius)

        val a = Raster(guide.width, guide.height, 1)
        val b = Raster(guide.width, guide.height, 1)
        for (i in a.data.indices) {
            val varI = corrI.data[i] - meanI.data[i] * meanI.data[i]
            val covIp = corrIp.data[i] - meanI.data[i] * meanP.data[i]
            a.data[i] = covIp / (varI + epsilon)
            b.data[i] = meanP.data[i] - a.data[i] * meanI.data[i]
        }

        val meanA = Blur.box(a, radius)
        val meanB = Blur.box(b, radius)
        val out = Raster(guide.width, guide.height, 1)
        for (i in out.data.indices) out.data[i] = meanA.data[i] * guide.data[i] + meanB.data[i]
        return out
    }

    /**
     * Colour-guided variant, the one that actually matters for matting.
     *
     * Hair separates from a background by chrominance far more often than by luminance — a dark
     * strand against a green wall is nearly identical in luma. Using all three channels as the
     * guide solves a 3×3 system per pixel and recovers those strands; a grayscale guide loses them.
     */
    fun filterColorGuide(guide: Raster, input: Raster, radius: Int, epsilon: Float): Raster {
        require(guide.channels >= 3) { "colour guided filter needs an RGB guide" }
        require(input.channels == 1) { "input must be single channel" }
        require(guide.sameShapeAs(input)) { "guide and input must have the same dimensions" }

        val n = guide.pixelCount
        val r = guide.channel(0)
        val g = guide.channel(1)
        val bch = guide.channel(2)

        val meanR = Blur.box(r, radius)
        val meanG = Blur.box(g, radius)
        val meanB = Blur.box(bch, radius)
        val meanP = Blur.box(input, radius)

        val corrRr = Blur.box(RasterMath.multiply(r, r), radius)
        val corrRg = Blur.box(RasterMath.multiply(r, g), radius)
        val corrRb = Blur.box(RasterMath.multiply(r, bch), radius)
        val corrGg = Blur.box(RasterMath.multiply(g, g), radius)
        val corrGb = Blur.box(RasterMath.multiply(g, bch), radius)
        val corrBb = Blur.box(RasterMath.multiply(bch, bch), radius)

        val corrRp = Blur.box(RasterMath.multiply(r, input), radius)
        val corrGp = Blur.box(RasterMath.multiply(g, input), radius)
        val corrBp = Blur.box(RasterMath.multiply(bch, input), radius)

        val aR = Raster(guide.width, guide.height, 1)
        val aG = Raster(guide.width, guide.height, 1)
        val aB = Raster(guide.width, guide.height, 1)
        val bb = Raster(guide.width, guide.height, 1)

        for (i in 0 until n) {
            val mr = meanR.data[i]; val mg = meanG.data[i]; val mb = meanB.data[i]
            val mp = meanP.data[i]

            // Symmetric covariance matrix plus epsilon on the diagonal.
            val s11 = corrRr.data[i] - mr * mr + epsilon
            val s12 = corrRg.data[i] - mr * mg
            val s13 = corrRb.data[i] - mr * mb
            val s22 = corrGg.data[i] - mg * mg + epsilon
            val s23 = corrGb.data[i] - mg * mb
            val s33 = corrBb.data[i] - mb * mb + epsilon

            val cr = corrRp.data[i] - mr * mp
            val cg = corrGp.data[i] - mg * mp
            val cb = corrBp.data[i] - mb * mp

            // Cofactor inverse of the 3x3 symmetric matrix.
            val i11 = s22 * s33 - s23 * s23
            val i12 = s13 * s23 - s12 * s33
            val i13 = s12 * s23 - s13 * s22
            val i22 = s11 * s33 - s13 * s13
            val i23 = s13 * s12 - s11 * s23
            val i33 = s11 * s22 - s12 * s12
            val det = s11 * i11 + s12 * i12 + s13 * i13

            if (abs(det) < 1e-12f) {
                aR.data[i] = 0f; aG.data[i] = 0f; aB.data[i] = 0f
                bb.data[i] = mp
                continue
            }
            val inv = 1f / det
            aR.data[i] = (i11 * cr + i12 * cg + i13 * cb) * inv
            aG.data[i] = (i12 * cr + i22 * cg + i23 * cb) * inv
            aB.data[i] = (i13 * cr + i23 * cg + i33 * cb) * inv
            bb.data[i] = mp - aR.data[i] * mr - aG.data[i] * mg - aB.data[i] * mb
        }

        val mA = Blur.box(aR, radius)
        val mB = Blur.box(aG, radius)
        val mC = Blur.box(aB, radius)
        val mD = Blur.box(bb, radius)

        val out = Raster(guide.width, guide.height, 1)
        for (i in 0 until n) {
            out.data[i] = mA.data[i] * r.data[i] + mB.data[i] * g.data[i] +
                mC.data[i] * bch.data[i] + mD.data[i]
        }
        return out
    }
}
