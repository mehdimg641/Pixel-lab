package ir.pixellab.core.imaging

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * PatchMatch (Barnes, Shechtman, Finkelstein and Goldman, SIGGRAPH 2009).
 *
 * The algorithm behind Photoshop's Content-Aware Fill. It is not a neural network — it finds
 * approximate nearest-neighbour patches by exploiting two observations: random sampling alone
 * turns up some good matches, and natural images are coherent, so a good match at one pixel is
 * probably a good match at its neighbours too. Alternating propagation and random search converges
 * one to two orders of magnitude faster than exhaustive search.
 *
 * Choosing this over an inpainting model is deliberate. It needs no weights, runs offline, is
 * faster, and is what the reference implementation actually uses.
 */
class PatchMatch(
    private val patchRadius: Int = 3,
    private val iterations: Int = 5,
    seed: Long = 0x5DEECE66DL,
) {
    private var rng = seed

    /** Nearest-neighbour field: for each target pixel, the source pixel its patch matched. */
    class Correspondence(val width: Int, val height: Int) {
        val offsetX = IntArray(width * height)
        val offsetY = IntArray(width * height)
        val cost = FloatArray(width * height) { Float.MAX_VALUE }

        fun set(index: Int, sx: Int, sy: Int, c: Float) {
            offsetX[index] = sx
            offsetY[index] = sy
            cost[index] = c
        }
    }

    /**
     * Fills the region where [hole] is set, by repeatedly finding similar patches elsewhere in
     * [image] and averaging their contributions.
     *
     * @param hole single-channel mask; values above 0.5 are removed and rebuilt
     * @param passes coarse-to-fine passes; more passes propagate structure further into big holes
     */
    fun inpaint(image: Raster, hole: Raster, passes: Int = 4): Raster {
        require(image.sameShapeAs(hole)) { "hole mask must match the image" }
        require(hole.channels == 1) { "hole mask must be single channel" }

        val result = image.copy()
        // Seed the hole with a blurred version of its surroundings so the first search has
        // something better than noise to match against.
        seedHole(result, hole)

        repeat(passes) {
            val field = search(result, hole)
            vote(result, hole, field)
        }
        return result
    }

    /** Builds the nearest-neighbour field from hole pixels to valid source pixels. */
    internal fun search(image: Raster, hole: Raster): Correspondence {
        val w = image.width
        val h = image.height
        val field = Correspondence(w, h)

        // Random initialisation, restricted to source patches that contain no hole.
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (hole.data[i] <= 0.5f) continue
                var sx: Int
                var sy: Int
                var tries = 0
                do {
                    sx = nextInt(w)
                    sy = nextInt(h)
                    tries++
                } while (isHole(hole, sx, sy) && tries < 32)
                field.set(i, sx, sy, patchDistance(image, hole, x, y, sx, sy))
            }
        }

        for (iteration in 0 until iterations) {
            val forward = iteration % 2 == 0
            val xs = if (forward) 0 until w else w - 1 downTo 0
            val step = if (forward) -1 else 1
            for (y in if (forward) 0 until h else h - 1 downTo 0) {
                for (x in xs) {
                    val i = y * w + x
                    if (hole.data[i] <= 0.5f) continue

                    // Propagate: a neighbour's match, shifted by one, is often a good match here.
                    propagate(image, hole, field, x, y, x + step, y, -step, 0)
                    propagate(image, hole, field, x, y, x, y + step, 0, -step)

                    // Random search in a window that halves each round.
                    var radius = max(w, h)
                    while (radius >= 1) {
                        val cx = field.offsetX[i] + nextInt(radius * 2 + 1) - radius
                        val cy = field.offsetY[i] + nextInt(radius * 2 + 1) - radius
                        if (cx in 0 until w && cy in 0 until h && !isHole(hole, cx, cy)) {
                            val d = patchDistance(image, hole, x, y, cx, cy)
                            if (d < field.cost[i]) field.set(i, cx, cy, d)
                        }
                        radius /= 2
                    }
                }
            }
        }
        return field
    }

    private fun propagate(
        image: Raster,
        hole: Raster,
        field: Correspondence,
        x: Int,
        y: Int,
        nx: Int,
        ny: Int,
        dx: Int,
        dy: Int,
    ) {
        val w = image.width
        if (nx !in 0 until w || ny !in 0 until image.height) return
        val ni = ny * w + nx
        if (hole.data[ni] <= 0.5f) return
        val cx = field.offsetX[ni] + dx
        val cy = field.offsetY[ni] + dy
        if (cx !in 0 until w || cy !in 0 until image.height) return
        if (isHole(hole, cx, cy)) return
        val i = y * w + x
        val d = patchDistance(image, hole, x, y, cx, cy)
        if (d < field.cost[i]) field.set(i, cx, cy, d)
    }

    /**
     * Accumulates every matched patch's contribution into the hole, weighted by match quality.
     *
     * Weighting matters more than it looks. A uniform average over all overlapping patches pulls
     * every hole pixel towards the mean of the image, which erases exactly the structure the fill
     * exists to continue — a striped texture comes back as flat grey. Weighting by
     * `exp(-cost / sigma)` lets the patches that actually fit dominate, so edges and repeating
     * detail survive.
     */
    private fun vote(image: Raster, hole: Raster, field: Correspondence) {
        val w = image.width
        val h = image.height
        val ch = image.channels
        val acc = FloatArray(w * h * ch)
        val weight = FloatArray(w * h)

        // Scale the falloff to the costs actually present, so it adapts to image contrast.
        var best = Float.MAX_VALUE
        for (i in 0 until w * h) {
            if (hole.data[i] > 0.5f && field.cost[i] < best) best = field.cost[i]
        }
        val sigma = max(best, 1e-4f) * 2f

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (hole.data[i] <= 0.5f) continue
                val cost = field.cost[i]
                if (cost >= Float.MAX_VALUE) continue
                val quality = kotlin.math.exp(-(cost / sigma).toDouble()).toFloat()
                val sx = field.offsetX[i]
                val sy = field.offsetY[i]
                for (dy in -patchRadius..patchRadius) {
                    for (dx in -patchRadius..patchRadius) {
                        val tx = x + dx
                        val ty = y + dy
                        if (tx !in 0 until w || ty !in 0 until h) continue
                        val ti = ty * w + tx
                        if (hole.data[ti] <= 0.5f) continue
                        for (c in 0 until ch) {
                            acc[ti * ch + c] += image.clamped(sx + dx, sy + dy, c) * quality
                        }
                        weight[ti] += quality
                    }
                }
            }
        }

        for (i in 0 until w * h) {
            if (weight[i] <= 1e-8f) continue
            for (c in 0 until ch) image.data[i * ch + c] = acc[i * ch + c] / weight[i]
        }
    }

    /** Sum of absolute differences, skipping any source pixel that is itself part of the hole. */
    private fun patchDistance(image: Raster, hole: Raster, tx: Int, ty: Int, sx: Int, sy: Int): Float {
        var sum = 0f
        var count = 0
        for (dy in -patchRadius..patchRadius) {
            for (dx in -patchRadius..patchRadius) {
                val px = tx + dx
                val py = ty + dy
                if (px !in 0 until image.width || py !in 0 until image.height) continue
                if (isHole(hole, px, py)) continue
                if (isHole(hole, sx + dx, sy + dy)) return Float.MAX_VALUE
                for (c in 0 until min(3, image.channels)) {
                    sum += abs(image.clamped(px, py, c) - image.clamped(sx + dx, sy + dy, c))
                }
                count++
            }
        }
        return if (count == 0) Float.MAX_VALUE else sum / count
    }

    private fun isHole(hole: Raster, x: Int, y: Int): Boolean =
        hole.clamped(x.coerceIn(0, hole.width - 1), y.coerceIn(0, hole.height - 1)) > 0.5f

    /** Fills the hole with a smooth interpolation of its boundary, as a starting point. */
    private fun seedHole(image: Raster, hole: Raster) {
        val filled = image.copy()
        repeat(24) {
            val next = filled.copy()
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    val i = y * image.width + x
                    if (hole.data[i] <= 0.5f) continue
                    for (c in 0 until image.channels) {
                        next[x, y, c] = (
                            filled.clamped(x - 1, y, c) + filled.clamped(x + 1, y, c) +
                                filled.clamped(x, y - 1, c) + filled.clamped(x, y + 1, c)
                            ) / 4f
                    }
                }
            }
            System.arraycopy(next.data, 0, filled.data, 0, filled.data.size)
        }
        System.arraycopy(filled.data, 0, image.data, 0, image.data.size)
    }

    // Deterministic generator: reproducible output matters for regression tests, and the platform
    // Random would make failures impossible to reproduce.
    private fun nextInt(bound: Int): Int {
        rng = (rng * 0x5DEECE66DL + 0xBL) and ((1L shl 48) - 1)
        return ((rng ushr 17) % bound).toInt().let { if (it < 0) it + bound else it }
    }
}

/**
 * Image deformation using moving least squares (Schaefer, McPhail and Warren, SIGGRAPH 2006).
 *
 * The mathematics behind Liquify and behind face reshaping. The user moves a few control points and
 * the algorithm produces a smooth deformation that honours them exactly and decays with distance.
 *
 * The rigid variant is the one that matters for faces: it forbids local stretching, so a jawline
 * can be narrowed without the eyes shearing.
 */
object MovingLeastSquares {

    data class ControlPoint(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float)

    enum class Mode {
        /** Allows shear and non-uniform scale; the most freedom, and the most distortion. */
        AFFINE,

        /** Uniform scale and rotation only. */
        SIMILARITY,

        /** Rotation only — no stretching. The right default for faces. */
        RIGID,
    }

    /**
     * Deforms [image] so that each control point's source lands on its destination.
     *
     * Solved on a coarse grid and interpolated between vertices, which is how an interactive tool
     * stays responsive; solving per pixel would be far slower for no visible gain.
     *
     * @param alpha falloff exponent; higher values make each point's influence more local
     * @param gridStep spacing of the solved lattice in pixels
     */
    fun deform(
        image: Raster,
        points: List<ControlPoint>,
        mode: Mode = Mode.RIGID,
        alpha: Float = 2f,
        gridStep: Int = 8,
    ): Raster {
        if (points.isEmpty()) return image.copy()
        require(gridStep >= 1) { "grid step must be at least 1" }

        val cols = image.width / gridStep + 2
        val rows = image.height / gridStep + 2
        val mapX = FloatArray(cols * rows)
        val mapY = FloatArray(cols * rows)

        for (gy in 0 until rows) {
            for (gx in 0 until cols) {
                val px = (gx * gridStep).toFloat()
                val py = (gy * gridStep).toFloat()
                val (sx, sy) = solve(px, py, points, mode, alpha)
                mapX[gy * cols + gx] = sx
                mapY[gy * cols + gx] = sy
            }
        }

        val out = Raster(image.width, image.height, image.channels)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val fx = x.toFloat() / gridStep
                val fy = y.toFloat() / gridStep
                val gx = fx.toInt().coerceIn(0, cols - 2)
                val gy = fy.toInt().coerceIn(0, rows - 2)
                val tx = fx - gx
                val ty = fy - gy
                val i00 = gy * cols + gx
                val sx = bilinear(mapX, i00, cols, tx, ty)
                val sy = bilinear(mapY, i00, cols, tx, ty)
                for (c in 0 until image.channels) out[x, y, c] = sample(image, sx, sy, c)
            }
        }
        return out
    }

    /**
     * Maps a destination point back to where it should be sampled from.
     *
     * Note the direction: control points are given as source-to-destination, so the inverse map
     * used for resampling swaps them.
     */
    internal fun solve(
        x: Float,
        y: Float,
        points: List<ControlPoint>,
        mode: Mode,
        alpha: Float,
    ): Pair<Float, Float> {
        var wSum = 0f
        var pcx = 0f; var pcy = 0f
        var qcx = 0f; var qcy = 0f
        val weights = FloatArray(points.size)

        for (i in points.indices) {
            val p = points[i]
            val dx = p.toX - x
            val dy = p.toY - y
            val d2 = dx * dx + dy * dy
            // Exactly on a control point: return its source directly and avoid dividing by zero.
            if (d2 < 1e-8f) return p.fromX to p.fromY
            val w = 1f / Math.pow(d2.toDouble(), alpha.toDouble() / 2.0).toFloat()
            weights[i] = w
            wSum += w
            pcx += w * p.toX; pcy += w * p.toY
            qcx += w * p.fromX; qcy += w * p.fromY
        }
        if (wSum <= 0f) return x to y
        pcx /= wSum; pcy /= wSum
        qcx /= wSum; qcy /= wSum

        var a = 0f; var b = 0f
        var mu = 0f
        for (i in points.indices) {
            val p = points[i]
            val px = p.toX - pcx
            val py = p.toY - pcy
            val qx = p.fromX - qcx
            val qy = p.fromY - qcy
            val w = weights[i]
            a += w * (px * qx + py * qy)
            b += w * (px * qy - py * qx)
            mu += w * (px * px + py * py)
        }

        val vx = x - pcx
        val vy = y - pcy

        return when (mode) {
            Mode.AFFINE, Mode.SIMILARITY -> {
                if (mu < 1e-8f) return qcx to qcy
                val sx = (a * vx + b * vy) / mu
                val sy = (-b * vx + a * vy) / mu
                (qcx + sx) to (qcy + sy)
            }
            Mode.RIGID -> {
                val norm = Math.sqrt((a * a + b * b).toDouble()).toFloat()
                if (norm < 1e-8f) return qcx to qcy
                // Normalising by |(a,b)| instead of mu removes the scale term, leaving rotation only.
                val len = Math.sqrt((vx * vx + vy * vy).toDouble()).toFloat()
                val sx = (a * vx + b * vy) / norm * len / max(len, 1e-8f)
                val sy = (-b * vx + a * vy) / norm * len / max(len, 1e-8f)
                (qcx + sx) to (qcy + sy)
            }
        }
    }

    private fun bilinear(map: FloatArray, i00: Int, cols: Int, tx: Float, ty: Float): Float {
        val v00 = map[i00]
        val v10 = map[i00 + 1]
        val v01 = map[i00 + cols]
        val v11 = map[i00 + cols + 1]
        val top = v00 + (v10 - v00) * tx
        val bottom = v01 + (v11 - v01) * tx
        return top + (bottom - top) * ty
    }

    private fun sample(image: Raster, x: Float, y: Float, c: Int): Float {
        val x0 = kotlin.math.floor(x).toInt()
        val y0 = kotlin.math.floor(y).toInt()
        val tx = x - x0
        val ty = y - y0
        val v00 = image.clamped(x0, y0, c)
        val v10 = image.clamped(x0 + 1, y0, c)
        val v01 = image.clamped(x0, y0 + 1, c)
        val v11 = image.clamped(x0 + 1, y0 + 1, c)
        val top = v00 + (v10 - v00) * tx
        val bottom = v01 + (v11 - v01) * tx
        return top + (bottom - top) * ty
    }
}
