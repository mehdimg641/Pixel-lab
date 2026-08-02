package ir.pixellab.core.imaging

import ir.pixellab.core.model.Vec2
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Directional blur — Photoshop's Motion Blur.
 *
 * The same idea as the radial pair and deliberately a separate, simpler routine: there the trail is
 * an arc or a ray and its length depends on where the pixel sits, so the sample count has to be
 * derived per pixel. Here every pixel travels the same distance in the same direction, so the count
 * is decided once and the inner loop is a straight walk. Folding it into the radial sampler would
 * make the common case pay for a generality it never uses.
 */
object MotionBlur {

    /**
     * @param angle degrees, measured the way a compass is read on screen: 0 is to the right.
     * @param distance the full length of the trail in pixels, so it matches the number in
     *   Photoshop's dialog rather than being a half-width the user has to double in their head.
     */
    fun apply(src: Raster, angle: Float, distance: Float): Raster {
        if (distance <= 1f) return src.copy()
        val radians = angle * DEGREES_TO_RADIANS
        val dx = cos(radians)
        val dy = sin(radians)
        val steps = distance.roundToInt().coerceIn(2, MAX_SAMPLES)

        // The trail is the same at every pixel, so its offsets are computed once for the whole
        // image rather than per pixel per channel. Centred, because a motion trail is symmetric
        // about where the subject was — unlike a zoom trail, which streams away from a centre.
        val offsetX = FloatArray(steps)
        val offsetY = FloatArray(steps)
        for (s in 0 until steps) {
            val t = (s.toFloat() / (steps - 1) - 0.5f) * distance
            offsetX[s] = dx * t
            offsetY[s] = dy * t
        }

        val channels = src.channels
        val out = Raster(src.width, src.height, channels)
        val acc = FloatArray(channels)
        val scale = 1f / steps
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                java.util.Arrays.fill(acc, 0f)
                // One coordinate computation per sample, shared by every channel. Fetching each
                // channel through its own bilinear call repeats the floor, the fraction and the
                // four bounds clamps once per channel, which on RGBA is four times the address
                // arithmetic for the same four reads.
                for (s in 0 until steps) sampleInto(src, x + offsetX[s], y + offsetY[s], acc)
                val at = (y * src.width + x) * channels
                for (c in 0 until channels) out.data[at + c] = acc[c] * scale
            }
        }
        return out
    }

    private const val DEGREES_TO_RADIANS = (PI / 180.0).toFloat()
    private const val MAX_SAMPLES = 256
}

/**
 * Lens blur — bokeh with a real aperture shape.
 *
 * The difference from a Gaussian is the one thing people can see without being told: a Gaussian
 * spreads a highlight into a soft smudge, while a lens spreads it into a *disc* with a defined
 * edge, because the aperture is a hole with a shape. Hexagonal blades give hexagonal bokeh. Faking
 * it with a Gaussian is why cheap portrait modes look like a smeared photograph rather than a
 * shallow depth of field.
 *
 * The kernel is built once as a set of offsets and reused for every pixel: a circular kernel of
 * radius 30 is about 2800 taps, and recomputing which of them fall inside the aperture per pixel
 * would multiply the cost by the image size for an answer that never changes.
 */
object LensBlur {

    /**
     * @param blades 0 for a circle; 5, 6 or 8 for the polygonal apertures real lenses have.
     * @param highlightThreshold pixels brighter than this are weighted up before averaging, which
     *   is what makes a specular highlight bloom into a visible disc instead of dissolving. Without
     *   it the effect is a slightly odd blur and nothing more.
     */
    fun apply(
        src: Raster,
        radius: Float,
        blades: Int = 0,
        rotation: Float = 0f,
        highlightThreshold: Float = 0.85f,
        highlightGain: Float = 4f,
    ): Raster {
        if (radius < 1f) return src.copy()
        val offsets = kernel(radius, blades, rotation)
        if (offsets.isEmpty()) return src.copy()

        // Unpacked into two int arrays. A list of boxed pairs walked a few thousand times per
        // pixel is an allocation-free loop only in principle: every element is a pointer chase,
        // and at this iteration count that dominates the arithmetic it is carrying.
        val kernelX = IntArray(offsets.size) { offsets[it].first }
        val kernelY = IntArray(offsets.size) { offsets[it].second }

        val channels = src.channels
        val colourChannels = minOf(channels, 3)
        val width = src.width
        val height = src.height
        val out = Raster(width, height, channels)
        val acc = FloatArray(channels)

        // The highlight weight is a property of the source pixel, not of the tap, so it is computed
        // once per pixel rather than once per tap. At a few hundred taps a pixel that is the
        // difference between three reads per tap and one.
        val weights = FloatArray(width * height)
        for (i in weights.indices) {
            val at = i * channels
            var brightest = 0f
            for (c in 0 until colourChannels) {
                val v = src.data[at + c]
                if (v > brightest) brightest = v
            }
            // Brightness measured across the colour channels only; weighting by alpha would make a
            // transparent area bloom, which is not a highlight.
            weights[i] = if (brightest > highlightThreshold) highlightGain else 1f
        }

        for (y in 0 until height) {
            for (x in 0 until width) {
                var weightSum = 0f
                java.util.Arrays.fill(acc, 0f)
                for (k in kernelX.indices) {
                    val sx = (x + kernelX[k]).coerceIn(0, width - 1)
                    val sy = (y + kernelY[k]).coerceIn(0, height - 1)
                    val pixel = sy * width + sx
                    // The row address once per tap rather than once per channel per tap.
                    val at = pixel * channels
                    val weight = weights[pixel]
                    for (c in 0 until channels) acc[c] += src.data[at + c] * weight
                    weightSum += weight
                }
                val to = (y * width + x) * channels
                val scale = 1f / weightSum
                for (c in 0 until channels) out.data[to + c] = acc[c] * scale
            }
        }
        return out
    }

    /** Integer offsets inside the aperture, built once and reused for every pixel. */
    internal fun kernel(radius: Float, blades: Int, rotation: Float): List<Pair<Int, Int>> {
        val r = radius.toInt().coerceAtMost(MAX_RADIUS)
        if (r < 1) return emptyList()
        val out = ArrayList<Pair<Int, Int>>()
        val turn = rotation * (PI / 180.0).toFloat()
        for (dy in -r..r) {
            for (dx in -r..r) {
                if (inside(dx.toFloat(), dy.toFloat(), r.toFloat(), blades, turn)) out += dx to dy
            }
        }
        return out
    }

    private fun inside(dx: Float, dy: Float, radius: Float, blades: Int, rotation: Float): Boolean {
        if (blades < 3) return hypot(dx, dy) <= radius
        // A convex polygon is the intersection of half-planes, one per blade — cheaper and exact
        // compared with walking the outline.
        val step = (2.0 * PI / blades).toFloat()
        // The inradius, so a hexagon of "radius 30" spans the same width as a circle of 30 rather
        // than poking out past it.
        val apothem = radius * cos(step / 2f)
        for (i in 0 until blades) {
            val angle = rotation + i * step
            if (dx * cos(angle) + dy * sin(angle) > apothem) return false
        }
        return true
    }

    /** Beyond this a single kernel is tens of thousands of taps per pixel. */
    private const val MAX_RADIUS = 48
}

/**
 * Blur that varies across the frame — Photoshop's Tilt-Shift and Iris Blur.
 *
 * One routine for both, because they differ only in how "distance from the focal region" is
 * measured: a signed distance from a line, or a radius from an ellipse. Everything else — the
 * transition ramp, the per-pixel blur strength, the way the sharp region is left untouched — is
 * shared, and writing them separately means fixing the ramp twice.
 *
 * Implemented by blending between the original and a fully blurred copy rather than by blurring
 * each pixel by its own radius. A per-pixel radius is the obvious reading and it is both far slower
 * and visibly wrong at the boundary, where neighbouring pixels blurred by different amounts pull
 * from different neighbourhoods and the transition tears.
 */
object GradientBlur {

    enum class Shape {
        /** A band of sharpness across the frame — the miniature-faking one. */
        LINEAR,

        /** An ellipse of sharpness — portrait vignetting. */
        RADIAL,
        ;

        val persianLabel: String get() = if (this == LINEAR) "نواری" else "بیضوی"
    }

    /**
     * @param centre the middle of the sharp region, in pixels.
     * @param focus half-width of the fully sharp region.
     * @param transition how far beyond [focus] the blur reaches full strength. Zero would give a
     *   hard edge between sharp and blurred, which no lens produces.
     */
    fun apply(
        src: Raster,
        shape: Shape,
        centre: Vec2,
        radius: Float,
        focus: Float,
        transition: Float,
        angle: Float = 0f,
    ): Raster {
        if (radius <= 0f) return src.copy()
        val blurred = Blur.gaussian(src, radius)
        val out = Raster(src.width, src.height, src.channels)
        val radians = angle * (PI / 180.0).toFloat()
        val nx = -sin(radians)
        val ny = cos(radians)
        val ramp = transition.coerceAtLeast(MIN_TRANSITION)

        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                val dx = x - centre.x
                val dy = y - centre.y
                val distance = when (shape) {
                    Shape.LINEAR -> abs(dx * nx + dy * ny)
                    Shape.RADIAL -> hypot(dx, dy)
                }
                val amount = ((distance - focus) / ramp).coerceIn(0f, 1f)
                // Smoothstep rather than linear: a linear ramp has a visible corner where it
                // reaches full blur, and the eye finds that edge immediately.
                val blend = amount * amount * (3f - 2f * amount)
                for (c in 0 until src.channels) {
                    val sharp = src[x, y, c]
                    out[x, y, c] = sharp + (blurred[x, y, c] - sharp) * blend
                }
            }
        }
        return out
    }

    private const val MIN_TRANSITION = 1f
}

/**
 * Sharpening.
 *
 * All three of Photoshop's are the same operation seen from different angles: subtract a blurred
 * copy to isolate detail, then add it back amplified. Unsharp mask does it in one step, high pass
 * hands the isolated detail over as a layer to blend by hand, and smart sharpen adds a threshold so
 * flat areas — sky, skin — are left alone instead of having their noise amplified.
 */
object Sharpen {

    /**
     * Unsharp mask.
     *
     * @param amount 0..5, where 1 is Photoshop's 100%.
     * @param threshold 0..1. Detail weaker than this is left alone, which is the whole difference
     *   between sharpening a photograph and sharpening its sensor noise.
     */
    fun unsharpMask(src: Raster, amount: Float, radius: Float, threshold: Float = 0f): Raster {
        if (amount <= 0f || radius <= 0f) return src.copy()
        val blurred = Blur.gaussian(src, radius)
        val out = src.copy()
        // Colour only: sharpening alpha carves a hard edge into a soft matte, which shows up as a
        // white line around every cut-out the moment it is composited.
        val channels = minOf(src.channels, 3)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                for (c in 0 until channels) {
                    val original = src[x, y, c]
                    val detail = original - blurred[x, y, c]
                    if (abs(detail) < threshold) continue
                    out[x, y, c] = (original + detail * amount).coerceIn(0f, 1f)
                }
            }
        }
        return out
    }

    /**
     * High pass: the detail on its own, centred on mid grey.
     *
     * Mid grey rather than black because that is what makes the result usable: laid over the
     * original in Overlay or Soft Light, 0.5 is the neutral value that changes nothing, so only the
     * detail has any effect.
     */
    fun highPass(src: Raster, radius: Float): Raster {
        if (radius <= 0f) return Raster(src.width, src.height, src.channels).fill(NEUTRAL)
        val blurred = Blur.gaussian(src, radius)
        val out = Raster(src.width, src.height, src.channels)
        val channels = minOf(src.channels, 3)
        for (i in 0 until src.pixelCount) {
            val at = i * src.channels
            for (c in 0 until channels) {
                out.data[at + c] = (src.data[at + c] - blurred.data[at + c] + NEUTRAL).coerceIn(0f, 1f)
            }
            // Alpha passes through: it is not a frequency, and a high-passed matte is meaningless.
            if (src.channels == 4) out.data[at + 3] = src.data[at + 3]
        }
        return out
    }

    /**
     * Smart Sharpen: the same detail, but faded out of the extremes.
     *
     * The two things it does that an unsharp mask cannot. It knows which blur it is undoing — lens
     * blur has a flat-topped kernel, so removing it needs a wider mask than removing a Gaussian of
     * the same nominal radius, and using the wrong one is where haloes come from. And it fades the
     * sharpening out of the shadows and the highlights, which is where the two artefacts live: a
     * shadow is where the noise is, and a highlight is already near clipping, so a halo there has
     * nowhere to go and shows as a hard white rim.
     *
     * @param fadeShadows 0..1, how much of the sharpening is withheld from the darkest tones.
     * @param fadeHighlights 0..1, the same at the top.
     */
    fun smart(
        src: Raster,
        amount: Float,
        radius: Float,
        lens: Boolean = false,
        fadeShadows: Float = 0.3f,
        fadeHighlights: Float = 0.3f,
    ): Raster {
        if (amount <= 0f || radius <= 0f) return src.copy()
        val blurred = Blur.gaussian(src, if (lens) radius * LENS_WIDENING else radius)
        val out = src.copy()
        val channels = minOf(src.channels, 3)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                // One fade for the whole pixel, from its luminance. Per channel it would sharpen a
                // red edge harder than a blue one and pull the colour apart along every contour.
                val tone = if (channels >= 3) {
                    LUMA_R * src[x, y, 0] + LUMA_G * src[x, y, 1] + LUMA_B * src[x, y, 2]
                } else {
                    src[x, y, 0]
                }
                val fade = (1f - fadeShadows.coerceIn(0f, 1f) * ramp(1f - tone)) *
                    (1f - fadeHighlights.coerceIn(0f, 1f) * ramp(tone))
                if (fade <= 0f) continue
                for (c in 0 until channels) {
                    val original = src[x, y, c]
                    val detail = original - blurred[x, y, c]
                    out[x, y, c] = (original + detail * amount * fade).coerceIn(0f, 1f)
                }
            }
        }
        return out
    }

    /** Full strength through the midtones, tapering only in the last quarter of the range. */
    private fun ramp(distanceIntoRange: Float): Float {
        val t = ((distanceIntoRange - FADE_START) / (1f - FADE_START)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private const val NEUTRAL = 0.5f

    /** A lens blur's kernel is flat-topped, so undoing it needs a mask wider than its radius. */
    private const val LENS_WIDENING = 1.4f

    private const val FADE_START = 0.75f

    private const val LUMA_R = 0.2126f
    private const val LUMA_G = 0.7152f
    private const val LUMA_B = 0.0722f
}

/**
 * The finishing effects — the ones applied last and judged by eye.
 */
object Stylise {

    /**
     * Darkens (or lightens) towards the corners.
     *
     * Measured on the *ellipse inscribed in the frame*, not on distance from the centre in pixels.
     * On a 16:9 canvas a circular falloff reaches the left and right edges long before the top and
     * bottom, so the vignette sits as an obvious oval in the middle rather than following the frame.
     *
     * @param amount negative darkens, positive lightens.
     * @param midpoint where the falloff begins, as a fraction of the way to the corner.
     */
    fun vignette(src: Raster, amount: Float, midpoint: Float = 0.5f, roundness: Float = 1f): Raster {
        if (amount == 0f) return src.copy()
        val out = src.copy()
        val cx = (src.width - 1) / 2f
        val cy = (src.height - 1) / 2f
        val rx = cx.coerceAtLeast(1f) * roundness.coerceAtLeast(MIN_ROUNDNESS)
        val ry = cy.coerceAtLeast(1f) * roundness.coerceAtLeast(MIN_ROUNDNESS)
        val start = midpoint.coerceIn(0f, ALMOST_ONE)
        val channels = minOf(src.channels, 3)

        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                val dx = (x - cx) / rx
                val dy = (y - cy) / ry
                val distance = sqrt(dx * dx + dy * dy)
                val t = ((distance - start) / (1f - start)).coerceIn(0f, 1f)
                val falloff = t * t * (3f - 2f * t)
                val scale = 1f + amount * falloff
                for (c in 0 until channels) {
                    out[x, y, c] = (src[x, y, c] * scale).coerceIn(0f, 1f)
                }
            }
        }
        return out
    }

    /**
     * Mosaic — Photoshop's Pixelate.
     *
     * Averages each block rather than sampling one pixel from it. Sampling is a line shorter and
     * gives a visibly different result on anything detailed: a face pixelated by sampling keeps
     * stray bright pixels that read as noise, while averaging gives the flat blocks the effect is
     * actually for.
     */
    fun pixelate(src: Raster, blockSize: Int): Raster {
        val size = blockSize.coerceAtLeast(1)
        if (size <= 1) return src.copy()
        val out = Raster(src.width, src.height, src.channels)

        var blockY = 0
        while (blockY < src.height) {
            var blockX = 0
            while (blockX < src.width) {
                val acc = FloatArray(src.channels)
                var count = 0
                for (y in blockY until minOf(blockY + size, src.height)) {
                    for (x in blockX until minOf(blockX + size, src.width)) {
                        for (c in 0 until src.channels) acc[c] += src[x, y, c]
                        count++
                    }
                }
                for (c in 0 until src.channels) acc[c] /= count
                for (y in blockY until minOf(blockY + size, src.height)) {
                    for (x in blockX until minOf(blockX + size, src.width)) {
                        for (c in 0 until src.channels) out[x, y, c] = acc[c]
                    }
                }
                blockX += size
            }
            blockY += size
        }
        return out
    }

    /**
     * Film grain.
     *
     * Deterministic from a seed, so the same image grained twice is identical — an effect that
     * changed every time it was applied would make undo and redo produce different pictures, and
     * an export at ×2 would not match what was on screen.
     *
     * @param monochrome true adds the same value to every channel, which is what film does.
     *   Per-channel noise reads as digital sensor noise, which is a different look entirely.
     */
    fun noise(src: Raster, amount: Float, monochrome: Boolean = true, seed: Int = 0): Raster {
        if (amount <= 0f) return src.copy()
        val out = src.copy()
        val channels = minOf(src.channels, 3)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                val shared = gaussianNoise(hash(seed, x, y, 0)) * amount
                for (c in 0 until channels) {
                    val delta = if (monochrome) shared else gaussianNoise(hash(seed, x, y, c)) * amount
                    out[x, y, c] = (src[x, y, c] + delta).coerceIn(0f, 1f)
                }
            }
        }
        return out
    }

    /**
     * Two uniform values summed and centred, which is a triangular distribution.
     *
     * Close enough to Gaussian for grain and far cheaper than a Box-Muller transform, which needs a
     * logarithm and a trig call per sample — on a twelve-megapixel image that is thirty-six million
     * of each.
     */
    private fun gaussianNoise(hash: Int): Float {
        val a = (hash and 0xFFFF) / 65535f
        val b = ((hash ushr 16) and 0xFFFF) / 65535f
        return (a + b) - 1f
    }

    private fun hash(seed: Int, x: Int, y: Int, channel: Int): Int {
        var h = seed * PRIME_SEED
        h = h xor (x * PRIME_X)
        h = h xor (y * PRIME_Y)
        h = h xor (channel * PRIME_C)
        h = h xor (h ushr SHIFT_A)
        h *= PRIME_MIX
        h = h xor (h ushr SHIFT_B)
        return h
    }

    private const val MIN_ROUNDNESS = 0.2f
    private const val ALMOST_ONE = 0.95f

    private const val PRIME_SEED = 0x9E3779B1.toInt()
    private const val PRIME_X = 0x85EBCA6B.toInt()
    private const val PRIME_Y = 0xC2B2AE35.toInt()
    private const val PRIME_C = 0x27D4EB2F
    private const val PRIME_MIX = 0x2545F491
    private const val SHIFT_A = 15
    private const val SHIFT_B = 13
}

/** Bilinear sampling with edge clamping, shared by the directional and gradient blurs. */
/**
 * Bilinear over every channel at once, accumulated into [acc].
 *
 * The single-channel form below is the readable one and this is the one the hot loops use. The
 * difference is not the reads — those are the same four per channel — it is that the floor, the
 * fraction and the four bounds clamps happen once for the pixel instead of once per channel.
 */
internal fun sampleInto(src: Raster, x: Float, y: Float, acc: FloatArray) {
    val fx = kotlin.math.floor(x)
    val fy = kotlin.math.floor(y)
    val tx = x - fx
    val ty = y - fy
    val x0 = fx.toInt().coerceIn(0, src.width - 1)
    val y0 = fy.toInt().coerceIn(0, src.height - 1)
    val x1 = (x0 + 1).coerceAtMost(src.width - 1)
    val y1 = (y0 + 1).coerceAtMost(src.height - 1)

    val channels = src.channels
    val topLeft = (y0 * src.width + x0) * channels
    val topRight = (y0 * src.width + x1) * channels
    val bottomLeft = (y1 * src.width + x0) * channels
    val bottomRight = (y1 * src.width + x1) * channels

    for (c in 0 until channels) {
        val top = src.data[topLeft + c] + (src.data[topRight + c] - src.data[topLeft + c]) * tx
        val bottom = src.data[bottomLeft + c] + (src.data[bottomRight + c] - src.data[bottomLeft + c]) * tx
        acc[c] += top + (bottom - top) * ty
    }
}

internal fun bilinear(src: Raster, x: Float, y: Float, c: Int): Float {
    val fx = kotlin.math.floor(x)
    val fy = kotlin.math.floor(y)
    val x0 = fx.toInt()
    val y0 = fy.toInt()
    val tx = x - fx
    val ty = y - fy
    val top = src.clamped(x0, y0, c) + (src.clamped(x0 + 1, y0, c) - src.clamped(x0, y0, c)) * tx
    val bottom = src.clamped(x0, y0 + 1, c) + (src.clamped(x0 + 1, y0 + 1, c) - src.clamped(x0, y0 + 1, c)) * tx
    return top + (bottom - top) * ty
}
