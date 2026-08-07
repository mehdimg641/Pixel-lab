package ir.pixellab.core.imaging

import ir.pixellab.core.model.Vec3
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The pixel half of automatic face retouching.
 *
 * Everything here takes a **coverage mask** and knows nothing about faces. That split is deliberate:
 * the geometry that decides where a lip is lives in `core:ai`, and these operations are then just
 * image processing that a test can drive with a hand-drawn mask. It also means every one of them
 * works as a manual tool the moment a user paints a selection, with no model involved at all.
 *
 * The thing that separates digital makeup from a coloured blob is stated once here and honoured by
 * every function below: **the picture's own detail has to survive.** A lipstick that replaces colour
 * flattens the lip's texture and highlight and reads instantly as a sticker; one that carries the
 * original luminance through reads as lipstick. So the colour operations work on chroma and leave
 * luminance to the photograph, and the tonal operations work on luminance and leave hue alone.
 */
object FaceRetouch {

    /**
     * Lays a colour into the masked region, keeping the photograph's shading.
     *
     * Lipstick, blush, eyeshadow, eyeliner, brow filler and hair colour are all this one function
     * with a different mask and a different opacity — which is why there are not six of them.
     *
     * @param strength 0 to 1. Above about 0.8 on lips it starts to look painted, which is why the
     *   sheet's default is well below that.
     * @param preserveTexture keeps the ratio of the original luminance to the region's mean, so the
     *   lip's own highlight and shadow come through the colour. Turning it off is a flat fill, which
     *   is occasionally what a graphic treatment wants and never what makeup wants.
     */
    fun tint(
        src: Raster,
        mask: FloatArray,
        colour: Vec3,
        strength: Float,
        preserveTexture: Boolean = true,
    ): Raster {
        require(src.channels >= 3) { "tint needs a colour image" }
        require(mask.size == src.pixelCount) { "mask does not match the image" }
        val amount = strength.coerceIn(0f, 1f)
        if (amount <= 0f) return src.copy()

        // The mean luminance under the mask, which is what the texture ratio is measured against.
        // Taken from the region rather than assumed, because a lip in shade and a lip in sun need
        // the same lipstick to come out the same colour.
        var sum = 0.0
        var weight = 0.0
        for (i in 0 until src.pixelCount) {
            val w = mask[i]
            if (w <= 0f) continue
            val at = i * src.channels
            sum += luma(src.data[at], src.data[at + 1], src.data[at + 2]) * w
            weight += w
        }
        val mean = if (weight > 0.0) (sum / weight).toFloat().coerceAtLeast(EPSILON) else 0.5f
        val out = src.copy()
        for (i in 0 until src.pixelCount) {
            val w = mask[i] * amount
            if (w <= 0f) continue
            val at = i * src.channels
            val l = luma(src.data[at], src.data[at + 1], src.data[at + 2])

            val painted = if (preserveTexture) {
                // Scale the chosen colour by how much brighter or darker this pixel is than the
                // region's mean. Clamped, because a specular highlight on a lip is many times the
                // mean and would otherwise blow the colour out to white.
                val ratio = (l / mean).coerceIn(1f - TEXTURE_RANGE, 1f + TEXTURE_RANGE)
                Vec3(
                    (colour.x * ratio).coerceIn(0f, 1f),
                    (colour.y * ratio).coerceIn(0f, 1f),
                    (colour.z * ratio).coerceIn(0f, 1f),
                )
            } else {
                colour
            }

            out.data[at] = src.data[at] + (painted.x - src.data[at]) * w
            out.data[at + 1] = src.data[at + 1] + (painted.y - src.data[at + 1]) * w
            out.data[at + 2] = src.data[at + 2] + (painted.z - src.data[at + 2]) * w
        }
        return out
    }

    /**
     * Takes the yellow out and lifts the brightness — teeth whitening, and eye whites.
     *
     * Whitening by raising brightness alone is what makes a mouth look like a light bulb. Real teeth
     * are off-white with a warm cast, and what reads as "whiter" is mostly the *removal of the cast*
     * — so saturation comes down first and brightness second, and the brightness lift is the smaller
     * of the two by design.
     */
    fun whiten(src: Raster, mask: FloatArray, strength: Float): Raster {
        require(src.channels >= 3) { "whiten needs a colour image" }
        require(mask.size == src.pixelCount) { "mask does not match the image" }
        val amount = strength.coerceIn(0f, 1f)
        if (amount <= 0f) return src.copy()

        val out = src.copy()
        for (i in 0 until src.pixelCount) {
            val w = mask[i] * amount
            if (w <= 0f) continue
            val at = i * src.channels
            val r = src.data[at]
            val g = src.data[at + 1]
            val b = src.data[at + 2]
            val l = luma(r, g, b)

            // Desaturate towards the pixel's own luminance, then lift — in that order, so the lift
            // is applied to a neutral rather than to a yellow.
            val neutralR = r + (l - r) * DESATURATE
            val neutralG = g + (l - g) * DESATURATE
            val neutralB = b + (l - b) * DESATURATE
            val lift = (1f - l) * BRIGHTEN

            out.data[at] = r + ((neutralR + lift).coerceIn(0f, 1f) - r) * w
            out.data[at + 1] = g + ((neutralG + lift).coerceIn(0f, 1f) - g) * w
            out.data[at + 2] = b + ((neutralB + lift).coerceIn(0f, 1f) - b) * w
        }
        return out
    }

    /**
     * Opens the shadows and adds a little contrast inside the mask — eye brightening.
     *
     * What an eye actually gains from is *separation*: the iris darker than the white, the catchlight
     * brighter than both. A flat brightness lift does the opposite and turns an eye into a grey
     * smudge. So this is an S-curve about the region's own mean rather than an offset.
     */
    fun brighten(src: Raster, mask: FloatArray, strength: Float): Raster {
        require(src.channels >= 3) { "brighten needs a colour image" }
        require(mask.size == src.pixelCount) { "mask does not match the image" }
        val amount = strength.coerceIn(0f, 1f)
        if (amount <= 0f) return src.copy()

        var sum = 0.0
        var weight = 0.0
        for (i in 0 until src.pixelCount) {
            val w = mask[i]
            if (w <= 0f) continue
            val at = i * src.channels
            sum += luma(src.data[at], src.data[at + 1], src.data[at + 2]) * w
            weight += w
        }
        val pivot = if (weight > 0.0) (sum / weight).toFloat().coerceIn(0.05f, 0.95f) else 0.5f

        val out = src.copy()
        for (i in 0 until src.pixelCount) {
            val w = mask[i] * amount
            if (w <= 0f) continue
            val at = i * src.channels
            val l = luma(src.data[at], src.data[at + 1], src.data[at + 2]).coerceAtLeast(EPSILON)

            // Contrast about the region's mean, plus a small overall lift. Both scale with the
            // slider; the lift is deliberately a third of the contrast, so "brighter" mostly means
            // "more separated".
            val stretched = ((l - pivot) * (1f + CONTRAST) + pivot + LIFT * (1f - l)).coerceIn(0f, 1f)
            val ratio = stretched / l
            for (c in 0 until 3) {
                val v = src.data[at + c]
                out.data[at + c] = v + ((v * ratio).coerceIn(0f, 1f) - v) * w
            }
        }
        return out
    }

    /**
     * Red-eye removal.
     *
     * Not a mask operation like the others — inside the eye mask it still has to find the *pupil*,
     * because desaturating the whole eye takes the iris colour with it. The test is the classic one:
     * a pixel is flash-red when its red channel dominates the other two by a wide margin, which no
     * natural iris does at any hue.
     *
     * The replacement is the pixel's own green-blue average rather than a fixed grey, so a pupil
     * keeps the scene's ambient tint and the eye does not acquire a grey hole.
     */
    fun removeRedEye(src: Raster, mask: FloatArray, strength: Float = 1f): Raster {
        require(src.channels >= 3) { "red-eye removal needs a colour image" }
        require(mask.size == src.pixelCount) { "mask does not match the image" }
        val amount = strength.coerceIn(0f, 1f)
        if (amount <= 0f) return src.copy()

        val out = src.copy()
        for (i in 0 until src.pixelCount) {
            val region = mask[i]
            if (region <= 0f) continue
            val at = i * src.channels
            val r = src.data[at]
            val g = src.data[at + 1]
            val b = src.data[at + 2]
            val other = (g + b) * 0.5f
            if (r <= other * RED_RATIO || r < RED_FLOOR) continue

            // How far past the threshold, so the correction fades in rather than switching on and
            // leaving a hard rim around the pupil.
            val excess = ((r - other * RED_RATIO) / max(RED_RATIO, EPSILON)).coerceIn(0f, 1f)
            val w = region * amount * excess
            out.data[at] = r + (other - r) * w
        }
        return out
    }

    /**
     * Skin smoothing confined to a mask.
     *
     * The smoothing itself is [FrequencySeparation], which already keeps pore texture rather than
     * blurring it away. What this adds is the mask — and the mask is what makes the difference
     * between "smooth skin" and "a soft photograph", because eyes, lips and hair must stay sharp or
     * the face reads as plastic.
     */
    fun smoothSkin(src: Raster, mask: FloatArray, strength: Float, radius: Float = 6f): Raster {
        require(src.channels >= 3) { "skin smoothing needs a colour image" }
        require(mask.size == src.pixelCount) { "mask does not match the image" }
        val amount = strength.coerceIn(0f, 1f)
        if (amount <= 0f) return src.copy()

        val bands = FrequencySeparation.split(src, radius)
        val out = src.copy()
        for (i in 0 until src.pixelCount) {
            val w = mask[i] * amount
            if (w <= 0f) continue
            val at = i * src.channels
            for (c in 0 until 3) {
                // The low frequency plus a reduced share of the high frequency. Keeping some detail
                // rather than none is the entire difference from a blur.
                val low = bands.low.data[at + c]
                val high = bands.high.data[at + c]
                val smoothed = low + high * (1f - w * DETAIL_REMOVED)
                out.data[at + c] = smoothed.coerceIn(0f, 1f)
            }
        }
        return out
    }

    /**
     * Cuts specular shine on skin — AirBrush's DeGlare, Hypic's counterpart.
     *
     * Oily highlights are small, very bright and *desaturated* relative to the skin around them. All
     * three have to be in the test or the correction eats the natural highlight on a cheekbone,
     * which is what makes a face look three-dimensional.
     */
    fun reduceShine(src: Raster, mask: FloatArray, strength: Float): Raster {
        require(src.channels >= 3) { "shine reduction needs a colour image" }
        require(mask.size == src.pixelCount) { "mask does not match the image" }
        val amount = strength.coerceIn(0f, 1f)
        if (amount <= 0f) return src.copy()

        // The skin's own colour, from the masked region, so the correction pulls towards *this*
        // face rather than towards a fixed idea of skin.
        val mean = FloatArray(3)
        var weight = 0.0
        for (i in 0 until src.pixelCount) {
            val w = mask[i]
            if (w <= 0f) continue
            val at = i * src.channels
            for (c in 0 until 3) mean[c] += src.data[at + c] * w
            weight += w
        }
        if (weight <= 0.0) return src.copy()
        for (c in 0 until 3) mean[c] = (mean[c] / weight.toFloat()).coerceIn(0f, 1f)
        val meanLuma = luma(mean[0], mean[1], mean[2])

        val out = src.copy()
        for (i in 0 until src.pixelCount) {
            val region = mask[i]
            if (region <= 0f) continue
            val at = i * src.channels
            val r = src.data[at]
            val g = src.data[at + 1]
            val b = src.data[at + 2]
            val l = luma(r, g, b)
            if (l <= meanLuma + SHINE_MARGIN) continue

            // Bright *and* washed out. Saturation measured as spread across the channels; a bright
            // cheek keeps its warmth, a wet-looking forehead does not.
            val spread = max(r, max(g, b)) - min(r, min(g, b))
            val washed = 1f - (spread / max(SHINE_SATURATION, EPSILON)).coerceIn(0f, 1f)
            val excess = ((l - meanLuma - SHINE_MARGIN) / (1f - meanLuma - SHINE_MARGIN))
                .coerceIn(0f, 1f)
            val w = region * amount * washed * sqrt(excess)
            if (w <= 0f) continue

            for (c in 0 until 3) {
                val v = src.data[at + c]
                out.data[at + c] = v + (mean[c] - v) * w
            }
        }
        return out
    }

    private fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /** How far a pixel's own brightness may push the applied colour. Past this a highlight blows. */
    private const val TEXTURE_RANGE = 0.45f

    private const val DESATURATE = 0.7f
    private const val BRIGHTEN = 0.25f

    private const val CONTRAST = 0.45f
    private const val LIFT = 0.15f

    /** Flash red dominates the other channels by this much; no natural iris comes close. */
    private const val RED_RATIO = 1.6f
    private const val RED_FLOOR = 0.2f

    /** At full strength three quarters of the fine detail goes and a quarter stays. */
    private const val DETAIL_REMOVED = 0.75f

    /** How far above the skin's mean a pixel must be before it counts as shine. */
    private const val SHINE_MARGIN = 0.12f

    /** Channel spread at which a highlight counts as fully coloured rather than washed out. */
    private const val SHINE_SATURATION = 0.25f

    private const val EPSILON = 1e-4f
}
