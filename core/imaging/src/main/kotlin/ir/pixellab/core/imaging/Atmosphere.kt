package ir.pixellab.core.imaging

import ir.pixellab.core.model.Vec2
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Haze removal by the dark channel prior (He, Sun and Tang, CVPR 2009).
 *
 * Hypic calls it Dehaze and puts it in the same scrolling row as brightness. Lightroom and Camera Raw
 * have it under that name too, and it is the one control in that row a curve cannot imitate: haze is
 * not a tone curve but a *depth-dependent* veil, thicker on the far hills than on the near ones, and
 * anything applied uniformly either leaves the distance milky or crushes the foreground to black.
 *
 * The prior is a single observation about photographs of real scenes: **in almost every small patch
 * of a haze-free outdoor image, some pixel is nearly black in at least one colour channel** —
 * shadow, a dark surface, a saturated colour. Where that minimum is *not* near zero, the difference
 * is the airlight that has been added, and its size tells you how much atmosphere the light crossed.
 *
 * The transmission that falls out of that is blocky, because it is a per-patch minimum. Refining it
 * is what [GuidedFilter] is for and why He wrote that paper next: the filter makes the transmission
 * follow the image's own edges, so the sky stops bleeding into a roofline.
 */
object Dehaze {

    /**
     * @param strength 0 leaves the image alone, 1 removes as much haze as the prior can find.
     * @param patch half-width of the neighbourhood the dark channel is taken over. Larger patches
     *   find deeper darks and so estimate more haze, at the cost of halos the guided filter then has
     *   to undo.
     */
    fun apply(src: Raster, strength: Float, patch: Int = DEFAULT_PATCH): Raster {
        require(src.channels >= 3) { "dehaze needs a colour image" }
        val amount = strength.coerceIn(0f, 1f)
        if (amount <= 0f) return src.copy()

        val dark = darkChannel(src, patch)
        val air = atmosphere(src, dark)
        // ω in the paper. Removing *all* of the haze leaves a picture with no aerial perspective at
        // all, which reads as a flat cut-out rather than as a clear day — so even at full strength a
        // little is kept, exactly as every implementation of this does.
        val omega = KEEP_MIN + (1f - KEEP_MIN) * amount

        val coarse = Raster(src.width, src.height, 1)
        for (i in 0 until src.pixelCount) {
            coarse.data[i] = 1f - omega * dark.data[i]
        }
        // Guided by the picture's own luminance, so the transmission map gets the image's edges back
        // after the patch minimum smeared them across the patch width.
        val guide = src.luminance()
        val refined = GuidedFilter.filter(guide, coarse, patch * GUIDE_SCALE, GUIDE_EPSILON)

        val out = Raster(src.width, src.height, src.channels)
        for (i in 0 until src.pixelCount) {
            // Floored, because the recovery divides by it: where the prior says the light is almost
            // entirely airlight there is no scene information left to recover, and dividing by a
            // number approaching zero manufactures noise with enormous confidence.
            val t = max(refined.data[i], MIN_TRANSMISSION)
            val at = i * src.channels
            for (c in 0 until 3) {
                out.data[at + c] = ((src.data[at + c] - air[c]) / t + air[c]).coerceIn(0f, 1f)
            }
            for (c in 3 until src.channels) out.data[at + c] = src.data[at + c]
        }
        return out
    }

    /** Per-pixel minimum over the patch, over the three colour channels. */
    fun darkChannel(src: Raster, patch: Int): Raster {
        // Separably: the minimum over a square is the minimum along rows of the minimum along
        // columns. On a six-megapixel photograph with a fifteen-pixel patch that is the difference
        // between nine hundred reads a pixel and sixty.
        val perPixel = Raster(src.width, src.height, 1)
        for (i in 0 until src.pixelCount) {
            val at = i * src.channels
            perPixel.data[i] = min(src.data[at], min(src.data[at + 1], src.data[at + 2]))
        }
        return minimumFilter(perPixel, patch)
    }

    /**
     * The colour of the airlight — what a surface infinitely far away looks like.
     *
     * Taken from the brightest tenth of a percent of the *dark channel*, not of the image. The
     * brightest pixels of the image itself are usually a specular highlight or a light source, and
     * using one of those makes every shadow in the recovered picture take on its colour.
     */
    fun atmosphere(src: Raster, dark: Raster): FloatArray {
        val count = max(1, src.pixelCount / ATMOSPHERE_SAMPLE)
        val indices = (0 until src.pixelCount).sortedByDescending { dark.data[it] }.take(count)
        val air = FloatArray(3)
        var best = -1f
        for (i in indices) {
            val at = i * src.channels
            val luma = src.data[at] + src.data[at + 1] + src.data[at + 2]
            if (luma > best) {
                best = luma
                for (c in 0 until 3) air[c] = src.data[at + c]
            }
        }
        // A fully white airlight makes the division above a no-op; a black one makes it explode.
        for (c in 0 until 3) air[c] = air[c].coerceIn(MIN_AIRLIGHT, MAX_AIRLIGHT)
        return air
    }

    private fun minimumFilter(src: Raster, radius: Int): Raster {
        val r = radius.coerceAtLeast(1)
        val horizontal = Raster(src.width, src.height, 1)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                var m = Float.MAX_VALUE
                for (dx in -r..r) {
                    val v = src.clamped(x + dx, y)
                    if (v < m) m = v
                }
                horizontal.data[y * src.width + x] = m
            }
        }
        val out = Raster(src.width, src.height, 1)
        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                var m = Float.MAX_VALUE
                for (dy in -r..r) {
                    val v = horizontal.clamped(x, y + dy)
                    if (v < m) m = v
                }
                out.data[y * src.width + x] = m
            }
        }
        return out
    }

    private const val DEFAULT_PATCH = 7
    private const val GUIDE_SCALE = 4
    private const val GUIDE_EPSILON = 1e-3f

    /** Even at full strength, a little haze stays — a scene with none reads as a cut-out. */
    private const val KEEP_MIN = 0.35f
    private const val MIN_TRANSMISSION = 0.1f
    private const val ATMOSPHERE_SAMPLE = 1000
    private const val MIN_AIRLIGHT = 0.3f
    private const val MAX_AIRLIGHT = 1f
}

/**
 * Contrast at a chosen size — Photoshop's Clarity and Texture, Hypic's Brilliance and Texture.
 *
 * Five differently-named sliders across the reference apps are the same operation with a different
 * radius, and saying so once is worth more than five implementations: **take the image, take a
 * blurred copy, and push the difference between them.** The blur radius chooses what "detail" means.
 * Small, and it is pores and fabric weave; large, and it is the modelling of a face or the separation
 * of a hill from the sky.
 *
 * Two things make it usable rather than a novelty:
 *
 * - **It works on luminance and puts the colour back untouched.** Pushing local contrast in RGB
 *   drags saturation with it, and the result is the over-cooked HDR look everyone recognises.
 * - **The gain falls off at both ends of the range.** Applied flat, it is the operation that
 *   produces black halos round a skyline and grey ones inside a window, because the difference it
 *   is amplifying is largest exactly where the image is already at its limits.
 */
object LocalContrast {

    /** The named sizes. The radius is what distinguishes them; everything else is shared. */
    enum class Scale(val radius: Float, val persianLabel: String) {
        /** Pores, weave, foliage — Photoshop's Texture. */
        TEXTURE(2f, "بافت"),

        /** Modelling: cheekbones, folds, the edge of a cloud — Photoshop's Clarity. */
        CLARITY(12f, "شفافیت"),

        /** Whole-scene punch, close to a gentle tone-map — Hypic's Brilliance. */
        BRILLIANCE(48f, "درخشندگی"),
    }

    /** @param amount −1 flattens, 0 is the original, +1 is the strongest push. */
    fun apply(src: Raster, scale: Scale, amount: Float): Raster =
        applyWithRadius(src, scale.radius, amount)

    fun applyWithRadius(src: Raster, radius: Float, amount: Float): Raster {
        require(src.channels >= 3) { "local contrast needs a colour image" }
        val gain = amount.coerceIn(-1f, 1f)
        if (gain == 0f || radius <= 0f) return src.copy()

        val luma = src.luminance()
        val base = Blur.gaussian(luma, radius)
        val out = Raster(src.width, src.height, src.channels)

        for (i in 0 until src.pixelCount) {
            val l = luma.data[i]
            val detail = l - base.data[i]
            // The falloff. A pixel already near black or near white has no room for its detail to be
            // amplified into, and amplifying it anyway is precisely how a halo is made.
            val room = headroom(l)
            val target = (l + detail * gain * MAX_GAIN * room).coerceIn(0f, 1f)

            val at = i * src.channels
            if (l <= EPSILON) {
                // A black pixel has no ratio to preserve. Adding the change equally to the three
                // channels keeps it neutral instead of dividing by nothing and inventing a hue.
                val delta = target - l
                for (c in 0 until 3) out.data[at + c] = (src.data[at + c] + delta).coerceIn(0f, 1f)
            } else {
                val ratio = target / l
                for (c in 0 until 3) out.data[at + c] = (src.data[at + c] * ratio).coerceIn(0f, 1f)
            }
            for (c in 3 until src.channels) out.data[at + c] = src.data[at + c]
        }
        return out
    }

    /** One at mid-grey, zero at both ends, smooth between — so nothing is pushed off the scale. */
    private fun headroom(l: Float): Float {
        val d = 1f - abs(l * 2f - 1f)
        return d * d
    }

    /** Past this the effect is stronger than any of the reference apps' maximum and reads as damage. */
    private const val MAX_GAIN = 1.5f
    private const val EPSILON = 1e-4f
}

/**
 * Crepuscular rays — Hypic's Tyndall effect, and what a photographer calls god rays.
 *
 * Real light shafts are sunlight scattered by dust or mist along its path, so they *radiate from the
 * source* and are brightest where the source is brightest. That is the whole recipe: keep only what
 * is brighter than a threshold, smear it radially outwards from a point, and add it back.
 *
 * It is *not* [RadialBlur] with `ZOOM`, which was the obvious reuse and is wrong twice. That filter
 * caps its reach at forty per cent of a pixel's radius — right for a zoom blur, where a longer smear
 * is camera shake — and it averages its samples evenly, so a ray would be as bright at the far end as
 * at the source. Rays need to cross the whole frame and to die along the way. So this carries its own
 * accumulator, the volumetric-scattering one (Mitchell, *GPU Gems 3*): march from the pixel towards
 * the source, and multiply in a decay at every step.
 *
 * Three things make it read as light rather than as a smudge:
 *
 * - **Only the highlights are marched.** Marching everything is a zoom blur.
 * - **It is added, not blended.** Light adds; scattered light does not occlude what is behind it.
 * - **The decay compounds per step**, so brightness falls off along the ray's own length. A uniform
 *   ray that stops at the frame edge reads as a lens artefact, not as air.
 */
object LightRays {

    /**
     * @param source where the light is, in pixels. Usually the sun, in or just off frame.
     * @param threshold what counts as a highlight worth scattering.
     * @param length how far the rays reach, as a fraction of the image's diagonal.
     * @param intensity how much is added back.
     */
    fun apply(
        src: Raster,
        source: Vec2,
        threshold: Float = 0.75f,
        length: Float = 0.4f,
        intensity: Float = 0.6f,
        tint: FloatArray? = null,
    ): Raster {
        require(src.channels >= 3) { "light rays need a colour image" }
        if (intensity <= 0f || length <= 0f) return src.copy()

        // The mask: the highlights, and only them, with a soft entry so a ray does not begin at a
        // hard edge where a pixel happens to cross the threshold.
        val highlights = Raster(src.width, src.height, src.channels)
        for (i in 0 until src.pixelCount) {
            val at = i * src.channels
            val luma = 0.2126f * src.data[at] + 0.7152f * src.data[at + 1] + 0.0722f * src.data[at + 2]
            val w = smoothstep(threshold, min(1f, threshold + SOFTNESS), luma)
            for (c in 0 until 3) highlights.data[at + c] = src.data[at + c] * w
            for (c in 3 until src.channels) highlights.data[at + c] = src.data[at + c]
        }

        // The march always runs the whole way to the source; [length] sets how fast the ray dies
        // instead of how far it is allowed to go. That is the difference between a ray that reaches
        // the sun and one that stops in mid-air, and it is the mistake the zoom blur's fixed cap
        // forces on anything built out of it.
        val perStep = Math.pow(DECAY_AT_FULL.toDouble(), 1.0 / length.coerceIn(MIN_LENGTH, 1f)).toFloat()
        // Normalised by the weights actually used, so the slider means the same thing at any length
        // rather than doubling as a brightness control.
        var weightSum = 0f
        var w = 1f
        for (s in 0 until SAMPLES) {
            weightSum += w
            w *= perStep
        }

        val out = Raster(src.width, src.height, src.channels)
        val accumulated = FloatArray(3)

        for (y in 0 until src.height) {
            for (x in 0 until src.width) {
                val at = (y * src.width + x) * src.channels
                val stepX = (x - source.x) / SAMPLES
                val stepY = (y - source.y) / SAMPLES

                accumulated.fill(0f)
                var decay = 1f
                var px = x.toFloat()
                var py = y.toFloat()
                for (s in 0 until SAMPLES) {
                    px -= stepX
                    py -= stepY
                    for (c in 0 until 3) {
                        accumulated[c] += bilinear(highlights, px, py, c) * decay
                    }
                    decay *= perStep
                }

                // Distance falls out of the march rather than being applied afterwards: a pixel far
                // from the source spends a smaller fraction of its path inside the highlight, so it
                // collects less. That is also physically the right shape.
                val k = intensity * GAIN / weightSum
                for (c in 0 until 3) {
                    val added = accumulated[c] * k * (tint?.getOrNull(c) ?: 1f)
                    out.data[at + c] = (src.data[at + c] + added).coerceIn(0f, 1f)
                }
                for (c in 3 until src.channels) out.data[at + c] = src.data[at + c]
            }
        }
        return out
    }

    private fun bilinear(src: Raster, x: Float, y: Float, c: Int): Float {
        val fx = kotlin.math.floor(x)
        val fy = kotlin.math.floor(y)
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val tx = x - fx
        val ty = y - fy
        val top = src.clamped(x0, y0, c) * (1f - tx) + src.clamped(x0 + 1, y0, c) * tx
        val bottom = src.clamped(x0, y0 + 1, c) * (1f - tx) + src.clamped(x0 + 1, y0 + 1, c) * tx
        return top + (bottom - top) * ty
    }

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** How wide the band is between "not a highlight" and "fully a highlight". */
    private const val SOFTNESS = 0.2f

    /**
     * Steps along each ray. Enough that a ray crossing a phone-sized frame is continuous rather than
     * a dotted line, and few enough that the whole filter stays one pass over the image.
     */
    private const val SAMPLES = 48

    /** Per-step survival at full length. Shorter lengths raise this to a power and die sooner. */
    private const val DECAY_AT_FULL = 0.985f

    /** Below this the exponent runs away and every ray is a dot at the source. */
    private const val MIN_LENGTH = 0.05f

    /** Scattered light can be brighter than the average of what it scattered from. */
    private const val GAIN = 3f
}
