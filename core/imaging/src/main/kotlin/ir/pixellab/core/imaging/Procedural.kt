package ir.pixellab.core.imaging

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Brush tips and patterns computed rather than shipped.
 *
 * The app is offline and carries no asset pack, so every texture it offers has to be generated. That
 * is a constraint, but it also buys two things a folder of PNGs cannot: a tip is exact at any size
 * rather than resampled from a fixed one, and a pattern is *seamless by construction* rather than by
 * whoever drew it having been careful.
 *
 * Seamlessness is the whole game with patterns and it is easy to get subtly wrong. Every function
 * here is periodic in the tile: it is written so that the value at x and at x + width is the same
 * expression, not merely a similar-looking one, and the tests measure the seam directly.
 */
object Procedural {

    // ---- brush tips ----------------------------------------------------------------------------

    /** The shapes that cover what a design tool needs without a bristle simulation. */
    enum class Tip {
        /** A soft disc — the same falloff the computed round tip uses, as a stamp. */
        ROUND,

        /** Flat-edged, for a chisel nib and for anything that should not look airbrushed. */
        SQUARE,

        /** Broken-edged, the way chalk drags across paper. */
        CHALK,

        /** Droplets of varying size — ink flicked off a loaded brush. */
        SPATTER,

        /** Parallel strands with gaps, which is what makes a dry-brush stroke read as a brush. */
        BRISTLE,
        ;

        val persianLabel: String
            get() = when (this) {
                ROUND -> "گرد"
                SQUARE -> "مربع"
                CHALK -> "گچ"
                SPATTER -> "پاشیده"
                BRISTLE -> "موقلم"
            }
    }

    /**
     * A tip as a single-channel coverage map.
     *
     * Coverage, not colour: a tip says *how much* of the brush's colour lands, and reading colour
     * out of a tip is what makes a textured brush paint grey instead of paint softly.
     *
     * @param seed varies the irregular tips. The same seed always gives the same tip, so a preset
     *   the user saved paints the same way tomorrow.
     */
    fun tip(kind: Tip, size: Int = 128, hardness: Float = 0.7f, seed: Int = 0): Raster {
        require(size >= MIN_TIP) { "a tip needs at least $MIN_TIP pixels, got $size" }
        val out = Raster(size, size, 1)
        val centre = (size - 1) / 2f
        val radius = size / 2f

        for (y in 0 until size) {
            for (x in 0 until size) {
                val dx = (x - centre) / radius
                val dy = (y - centre) / radius
                val distance = sqrt(dx * dx + dy * dy)
                out[x, y, 0] = when (kind) {
                    Tip.ROUND -> falloff(distance, hardness)
                    Tip.SQUARE -> falloff(max(abs(dx), abs(dy)), hardness)
                    Tip.CHALK -> chalk(dx, dy, distance, hardness, seed)
                    Tip.SPATTER -> spatter(dx, dy, seed)
                    Tip.BRISTLE -> bristle(dx, dy, distance, hardness, seed)
                }
            }
        }
        return out
    }

    /**
     * Photoshop's hardness, as a fraction of the radius rather than an absolute blur.
     *
     * Measuring it as a fraction is what makes a soft brush stay soft when it is resized, which is
     * the behaviour every painter relies on without ever thinking about it.
     */
    private fun falloff(distance: Float, hardness: Float): Float {
        if (distance >= 1f) return 0f
        val solid = hardness.coerceIn(0f, 1f)
        if (distance <= solid) return 1f
        val t = ((1f - distance) / (1f - solid).coerceAtLeast(EPSILON)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Chalk: a soft disc eaten away by noise, heavier towards the edge where the pressure fails. */
    private fun chalk(dx: Float, dy: Float, distance: Float, hardness: Float, seed: Int): Float {
        val base = falloff(distance, hardness * CHALK_HARDNESS)
        if (base <= 0f) return 0f
        val grain = fbm(dx * CHALK_SCALE, dy * CHALK_SCALE, seed)
        // Bitten hardest at the rim: a chalk stick loses contact at its edges first, so the middle
        // of a stroke stays solid while its border breaks up.
        val bite = CHALK_BITE * distance
        return (base * (1f - bite + bite * grain)).coerceIn(0f, 1f)
    }

    /** Spatter: droplets, each a small soft disc, at positions the hash decides. */
    private fun spatter(dx: Float, dy: Float, seed: Int): Float {
        var coverage = 0f
        for (drop in 0 until SPATTER_DROPS) {
            val angle = hash(drop, seed) * TAU
            // Square-rooted so the droplets spread evenly over the disc's *area* rather than
            // bunching in the middle, which is what a uniform radius would do.
            val reach = sqrt(hash(drop, seed + 1)) * SPATTER_REACH
            val cx = cos(angle) * reach
            val cy = sin(angle) * reach
            val size = SPATTER_MIN + hash(drop, seed + 2) * (SPATTER_MAX - SPATTER_MIN)
            val d = sqrt((dx - cx) * (dx - cx) + (dy - cy) * (dy - cy)) / size
            coverage = max(coverage, falloff(d, SPATTER_HARDNESS))
        }
        return coverage
    }

    /** Bristle: strands across the tip, so a dry stroke shows the hairs that made it. */
    private fun bristle(dx: Float, dy: Float, distance: Float, hardness: Float, seed: Int): Float {
        val base = falloff(distance, hardness)
        if (base <= 0f) return 0f
        val strand = (dy + 1f) * BRISTLE_COUNT / 2f
        val index = strand.toInt()
        val within = strand - index
        // Each strand keeps its own width and its own opacity, so the tip reads as separate hairs
        // rather than as a grating. A uniform comb looks like a printing artefact.
        val width = BRISTLE_MIN_WIDTH + hash(index, seed) * (1f - BRISTLE_MIN_WIDTH)
        val profile = 1f - (abs(within - HALF) * 2f / width).coerceAtMost(1f)
        val strength = BRISTLE_FLOOR + hash(index, seed + 1) * (1f - BRISTLE_FLOOR)
        return base * profile * strength * (1f - abs(dx) * BRISTLE_TAPER).coerceIn(0f, 1f)
    }

    // ---- patterns ------------------------------------------------------------------------------

    /** Tileable fills, all of them periodic in the tile by construction. */
    enum class Pattern {
        STRIPES, CHECKS, DOTS, GRID, CROSSHATCH, NOISE, MARBLE, SHATTER,
        ;

        val persianLabel: String
            get() = when (this) {
                STRIPES -> "راه‌راه"
                CHECKS -> "شطرنجی"
                DOTS -> "خال‌خالی"
                GRID -> "شبکه"
                CROSSHATCH -> "هاشور"
                NOISE -> "نویز"
                MARBLE -> "رنگ روان"
                SHATTER -> "شیشهٔ شکسته"
            }
    }

    /**
     * A pattern as a single-channel coverage map, one tile.
     *
     * Coverage rather than two colours, so the caller decides what to paint with it — a pattern that
     * carried its own colours could not be used as a mask, and half the uses of a pattern are.
     *
     * @param repeats how many periods fit in the tile. An integer, and that is the whole reason the
     *   result tiles: a fractional count leaves a discontinuity exactly at the tile's edge.
     */
    fun pattern(kind: Pattern, size: Int = 128, repeats: Int = 4, seed: Int = 0): Raster {
        require(size >= MIN_TILE) { "a pattern tile needs at least $MIN_TILE pixels, got $size" }
        val periods = repeats.coerceAtLeast(1)
        val out = Raster(size, size, 1)

        for (y in 0 until size) {
            for (x in 0 until size) {
                // In *periods*, not pixels: at x = size this is exactly `periods`, an integer, so
                // every sine and every floor below lands on the same value it had at x = 0.
                val u = x.toFloat() * periods / size
                val v = y.toFloat() * periods / size
                out[x, y, 0] = when (kind) {
                    Pattern.STRIPES -> band(u)
                    Pattern.CHECKS -> if ((floorInt(u) + floorInt(v)) % 2 == 0) 1f else 0f
                    Pattern.DOTS -> dot(u, v)
                    Pattern.GRID -> max(line(u), line(v))
                    Pattern.CROSSHATCH -> max(band(u + v), band(u - v))
                    Pattern.NOISE -> tileableNoise(x, y, size, periods, seed)
                    Pattern.MARBLE -> marble(x, y, size, periods, seed)
                    Pattern.SHATTER -> shatter(u, v, periods, seed)
                }
            }
        }
        return out
    }

    /**
     * Flowing paint: noise used to *displace* the lookup of more noise.
     *
     * Plain fractal noise is cloud, not paint — it has no direction, so it reads as fog wherever it
     * is laid. Warping the domain first is what turns it into strokes: each sample is pulled aside
     * by an amount that itself varies smoothly, so bands of similar value stretch and curl into one
     * another the way pigment does when it is dragged. It is one extra noise lookup per axis and it
     * is the whole difference between a texture that looks painted and one that looks dirty.
     */
    private fun marble(x: Int, y: Int, size: Int, periods: Int, seed: Int): Float {
        val warpX = tileableNoise(x, y, size, periods, seed + WARP_SEED) - HALF
        val warpY = tileableNoise(x, y, size, periods, seed + WARP_SEED * 2) - HALF
        val u = x + warpX * size * MARBLE_WARP
        val v = y + warpY * size * MARBLE_WARP
        // Wrapped back into the tile so the warp cannot walk a sample off the edge and break the
        // seam the tiling depends on.
        val wrappedX = ((u % size) + size) % size
        val wrappedY = ((v % size) + size) % size
        return tileableNoise(wrappedX.toInt(), wrappedY.toInt(), size, periods, seed)
    }

    /**
     * Cracked glass: distance to the nearest of a scattered set of points, minus the next nearest.
     *
     * The difference of the two distances rather than the first alone, which is what puts the value
     * at zero exactly on the boundary between two cells and rising away from it — so the pattern is
     * the *edges* of the cells, a web of cracks, rather than a field of blobs. Taking only the
     * nearest gives the blobs, which is the usual mistake and looks like bubble wrap.
     */
    private fun shatter(u: Float, v: Float, periods: Int, seed: Int): Float {
        var nearest = Float.MAX_VALUE
        var second = Float.MAX_VALUE
        val cellX = floorInt(u)
        val cellY = floorInt(v)
        for (dy in -1..1) {
            for (dx in -1..1) {
                val gx = cellX + dx
                val gy = cellY + dy
                // Wrapped, so a cell at the tile's edge sees the same neighbour its opposite does.
                val wx = ((gx % periods) + periods) % periods
                val wy = ((gy % periods) + periods) % periods
                val hx = hash(wx * PRIME_X + wy * PRIME_Y, seed)
                val hy = hash(wx * PRIME_X + wy * PRIME_Y, seed + 1)
                val px = gx + hx
                val py = gy + hy
                val d = kotlin.math.hypot(px - u, py - v)
                if (d < nearest) {
                    second = nearest
                    nearest = d
                } else if (d < second) {
                    second = d
                }
            }
        }
        return ((second - nearest) * SHATTER_CONTRAST).coerceIn(0f, 1f)
    }

    /** Half on, half off, with a soft shoulder so it does not alias into moiré when scaled down. */
    private fun band(u: Float): Float {
        val phase = u - floorInt(u)
        val edge = min(phase, 1f - phase)
        return (edge * BAND_SOFTNESS).coerceIn(0f, 1f).let { if (phase < HALF) 1f - it else it }
    }

    private fun line(u: Float): Float {
        val phase = u - floorInt(u)
        val distance = min(phase, 1f - phase)
        return (1f - distance / LINE_WIDTH).coerceIn(0f, 1f)
    }

    private fun dot(u: Float, v: Float): Float {
        val du = u - floorInt(u) - HALF
        val dv = v - floorInt(v) - HALF
        return (1f - sqrt(du * du + dv * dv) / DOT_RADIUS).coerceIn(0f, 1f)
    }

    /**
     * Value noise that wraps.
     *
     * Ordinary hashed noise does not tile: the cell at the right edge has no neighbour to the right,
     * so the interpolation reaches into a cell that is not the one on the other side. Taking the
     * lattice coordinate modulo the period makes the two the same cell, and the seam disappears.
     */
    private fun tileableNoise(x: Int, y: Int, size: Int, periods: Int, seed: Int): Float {
        val u = x.toFloat() * periods / size
        val v = y.toFloat() * periods / size
        val x0 = floorInt(u)
        val y0 = floorInt(v)
        val fx = u - x0
        val fy = v - y0
        fun corner(cx: Int, cy: Int) =
            hash(wrap(cx, periods) * PRIME_X + wrap(cy, periods) * PRIME_Y, seed)
        val top = lerp(corner(x0, y0), corner(x0 + 1, y0), smooth(fx))
        val bottom = lerp(corner(x0, y0 + 1), corner(x0 + 1, y0 + 1), smooth(fx))
        return lerp(top, bottom, smooth(fy))
    }

    /** Two octaves is enough for a chalk edge and cheap enough to run per pixel of a tip. */
    private fun fbm(x: Float, y: Float, seed: Int): Float {
        var value = 0f
        var amplitude = 1f
        var total = 0f
        var frequency = 1f
        repeat(FBM_OCTAVES) { octave ->
            value += amplitude * valueNoise(x * frequency, y * frequency, seed + octave)
            total += amplitude
            amplitude *= FBM_GAIN
            frequency *= 2f
        }
        return value / total
    }

    private fun valueNoise(x: Float, y: Float, seed: Int): Float {
        val x0 = floorInt(x)
        val y0 = floorInt(y)
        val fx = x - x0
        val fy = y - y0
        fun corner(cx: Int, cy: Int) = hash(cx * PRIME_X + cy * PRIME_Y, seed)
        val top = lerp(corner(x0, y0), corner(x0 + 1, y0), smooth(fx))
        val bottom = lerp(corner(x0, y0 + 1), corner(x0 + 1, y0 + 1), smooth(fx))
        return lerp(top, bottom, smooth(fy))
    }

    /**
     * A deterministic 0..1 from two integers.
     *
     * Deterministic rather than random on purpose: a brush that painted a different texture on every
     * application would make undo and redo produce different pictures, and a pattern that changed
     * when the document was reopened would be worse still.
     */
    internal fun hash(value: Int, seed: Int): Float {
        var h = value * MIX_A xor (seed * MIX_B)
        h = h xor (h ushr SHIFT_A)
        h *= MIX_C
        h = h xor (h ushr SHIFT_B)
        return (h and MASK).toFloat() / MASK.toFloat()
    }

    /** Positive modulo. Kotlin's `%` keeps the sign, which puts a seam back where one was removed. */
    private fun wrap(value: Int, period: Int): Int = ((value % period) + period) % period

    private fun floorInt(value: Float): Int {
        val truncated = value.toInt()
        return if (value < 0f && value != truncated.toFloat()) truncated - 1 else truncated
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun max(a: Float, b: Float) = if (a > b) a else b

    private const val MIN_TIP = 8
    /** How far the domain warp drags a sample, as a fraction of the tile. */
    private const val MARBLE_WARP = 0.18f

    private const val WARP_SEED = 101

    /** Steepens the cell-boundary falloff so the cracks read as lines rather than as soft valleys. */
    private const val SHATTER_CONTRAST = 3.5f

    private const val MIN_TILE = 8
    private const val HALF = 0.5f
    private const val EPSILON = 1e-4f
    private const val TAU = 6.2831855f

    private const val CHALK_SCALE = 6f
    private const val CHALK_HARDNESS = 0.5f
    private const val CHALK_BITE = 0.9f

    private const val SPATTER_DROPS = 14
    private const val SPATTER_REACH = 0.8f
    private const val SPATTER_MIN = 0.08f
    private const val SPATTER_MAX = 0.26f
    private const val SPATTER_HARDNESS = 0.4f

    private const val BRISTLE_COUNT = 22f
    private const val BRISTLE_MIN_WIDTH = 0.35f
    private const val BRISTLE_FLOOR = 0.4f
    private const val BRISTLE_TAPER = 0.35f

    /** Wider than a pixel at the sizes a pattern is used, so the edge never crawls. */
    private const val BAND_SOFTNESS = 12f
    private const val LINE_WIDTH = 0.08f
    private const val DOT_RADIUS = 0.35f

    private const val FBM_OCTAVES = 3
    private const val FBM_GAIN = 0.5f

    private const val PRIME_X = 374761393
    private const val PRIME_Y = 668265263
    private const val MIX_A = -1640531527
    private const val MIX_B = 1013904223
    private const val MIX_C = -2048144789
    private const val SHIFT_A = 15
    private const val SHIFT_B = 13
    private const val MASK = 0x00FFFFFF
}
