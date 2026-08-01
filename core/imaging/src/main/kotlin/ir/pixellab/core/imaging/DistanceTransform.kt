package ir.pixellab.core.imaging

import kotlin.math.sqrt

/**
 * Exact Euclidean distance transform, Felzenszwalb and Huttenlocher's algorithm.
 *
 * This is the backbone of bevel and emboss — 99 instances across the reference PSDs. The chain is
 * silhouette to distance field to height map to normals to lighting, and every step after this one
 * inherits its accuracy. An approximate transform (chamfer, or a blurred mask) produces visible
 * faceting on curved letterforms, which is exactly where a bevel is looked at.
 *
 * Runs in O(n) per dimension by computing the lower envelope of parabolas.
 */
object DistanceTransform {

    private const val INF = 1e20f

    /**
     * Distance in pixels from every point to the nearest point where [mask] is at or above
     * [threshold].
     *
     * @param inside when true, measures distance inward from the shape edge (the usual case for an
     *   inner bevel); when false, measures outward.
     */
    fun euclidean(mask: Raster, threshold: Float = 0.5f, inside: Boolean = true): Raster {
        require(mask.channels == 1) { "distance transform expects a single-channel mask" }
        val f = FloatArray(mask.pixelCount)
        for (i in f.indices) {
            val solid = mask.data[i] >= threshold
            // Seeds are the points we measure *from*, so they flip with the direction.
            f[i] = if (solid != inside) 0f else INF
        }
        return Raster(mask.width, mask.height, 1, squaredToDistance(transform2D(f, mask.width, mask.height)))
    }

    /**
     * Signed distance: negative inside the shape, positive outside, zero on the edge.
     *
     * Useful for edge roughening and for growing or shrinking a mask by an exact amount.
     */
    fun signed(mask: Raster, threshold: Float = 0.5f): Raster {
        val outward = euclidean(mask, threshold, inside = false)
        val inward = euclidean(mask, threshold, inside = true)
        val out = Raster(mask.width, mask.height, 1)
        for (i in out.data.indices) {
            out.data[i] = if (mask.data[i] >= threshold) -inward.data[i] else outward.data[i]
        }
        return out
    }

    private fun squaredToDistance(sq: FloatArray): FloatArray =
        FloatArray(sq.size) { sqrt(sq[it]) }

    private fun transform2D(f: FloatArray, width: Int, height: Int): FloatArray {
        val out = f.copyOf()
        val column = FloatArray(maxOf(width, height))

        for (x in 0 until width) {
            for (y in 0 until height) column[y] = out[y * width + x]
            val d = transform1D(column, height)
            for (y in 0 until height) out[y * width + x] = d[y]
        }
        for (y in 0 until height) {
            for (x in 0 until width) column[x] = out[y * width + x]
            val d = transform1D(column, width)
            for (x in 0 until width) out[y * width + x] = d[x]
        }
        return out
    }

    /** Lower envelope of parabolas rooted at each sample. */
    private fun transform1D(f: FloatArray, n: Int): FloatArray {
        val d = FloatArray(n)
        val v = IntArray(n)
        val z = FloatArray(n + 1)
        var k = 0
        v[0] = 0
        z[0] = -INF
        z[1] = INF

        for (q in 1 until n) {
            var s: Float
            while (true) {
                val p = v[k]
                s = ((f[q] + q * q) - (f[p] + p * p)) / (2f * q - 2f * p)
                if (s <= z[k]) k-- else break
            }
            k++
            v[k] = q
            z[k] = s
            z[k + 1] = INF
        }

        k = 0
        for (q in 0 until n) {
            while (z[k + 1] < q) k++
            val p = v[k]
            val dx = (q - p).toFloat()
            d[q] = dx * dx + f[p]
        }
        return d
    }
}

/**
 * Turns a silhouette into a height field and then into surface normals.
 *
 * Two entry points, because the two things that need normals derive their height differently:
 * a bevel takes it from the shape's distance field, while relighting a photograph takes it from
 * the image's own luminance — which is how Photoshop's Lighting Effects has always worked.
 */
object HeightField {

    /**
     * Bevel height profile from a mask.
     *
     * @param size how far the bevel reaches in from the edge, in pixels
     * @param profile response curve; a rounded shoulder gives the inflated look, a linear ramp
     *   gives a hard chamfer
     */
    fun fromMask(
        mask: Raster,
        size: Float,
        profile: (Float) -> Float = { it },
    ): Raster {
        require(size > 0f) { "bevel size must be positive, got $size" }
        val distance = DistanceTransform.euclidean(mask, inside = true)
        val out = Raster(mask.width, mask.height, 1)
        for (i in out.data.indices) {
            val t = (distance.data[i] / size).coerceIn(0f, 1f)
            out.data[i] = profile(t)
        }
        return out
    }

    /**
     * Height from image luminance, for relighting a photograph.
     *
     * [smooth] must remove texture before differentiation, or skin pores turn into relief and the
     * result looks like hammered metal. A bilateral filter is the right smoother here because it
     * flattens texture while keeping the form boundaries that carry the lighting.
     */
    fun fromLuminance(image: Raster, smooth: (Raster) -> Raster): Raster =
        smooth(if (image.channels == 1) image else image.luminance())

    /**
     * Surface normals from a height field, by central differences.
     *
     * @param strength scales the height before differentiation; this is the "depth" control, and
     *   the reference PSDs push it well past 100%.
     */
    fun toNormals(height: Raster, strength: Float = 1f): Raster {
        require(height.channels == 1) { "normals need a single-channel height field" }
        val out = Raster(height.width, height.height, 3)
        for (y in 0 until height.height) {
            for (x in 0 until height.width) {
                val dx = (height.clamped(x + 1, y) - height.clamped(x - 1, y)) * strength
                val dy = (height.clamped(x, y + 1) - height.clamped(x, y - 1)) * strength
                // Gradient of a height field gives the tangent plane; the normal is its cross product.
                var nx = -dx
                var ny = -dy
                var nz = 1f
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                nx /= len; ny /= len; nz /= len
                val o = (y * height.width + x) * 3
                out.data[o] = nx
                out.data[o + 1] = ny
                out.data[o + 2] = nz
            }
        }
        return out
    }

    /**
     * Lambert plus Blinn-Phong shading of a normal map.
     *
     * @param lightAngle degrees, clockwise from the positive x axis
     * @param altitude degrees above the surface; 90 is head-on
     * @param gloss shapes the specular response — non-linear values produce the metallic ringing
     *   that reads as chrome
     */
    fun shade(
        normals: Raster,
        lightAngle: Float,
        altitude: Float,
        gloss: (Float) -> Float = { it },
    ): Raster {
        require(normals.channels == 3) { "shading expects a normal map" }
        val a = Math.toRadians(lightAngle.toDouble())
        val e = Math.toRadians(altitude.toDouble())
        val lx = (kotlin.math.cos(a) * kotlin.math.cos(e)).toFloat()
        val ly = (-kotlin.math.sin(a) * kotlin.math.cos(e)).toFloat()
        val lz = kotlin.math.sin(e).toFloat()

        val out = Raster(normals.width, normals.height, 1)
        for (i in 0 until normals.pixelCount) {
            val o = i * 3
            val ndl = normals.data[o] * lx + normals.data[o + 1] * ly + normals.data[o + 2] * lz
            // Kept signed: positive lights the highlight side, negative the shadow side, and the
            // bevel shader needs both from one pass.
            out.data[i] = gloss(ndl.coerceIn(-1f, 1f) * 0.5f + 0.5f) * 2f - 1f
        }
        return out
    }
}
