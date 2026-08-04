package ir.pixellab.core.imaging

import ir.pixellab.core.model.Tone
import ir.pixellab.core.model.ToneRange
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The brushes that read the picture instead of painting on it.
 *
 * Dodge, burn, sponge, blur and sharpen are the half of Photoshop's toolbar that has no colour: what
 * each dab does depends entirely on the pixels already underneath it. That is why they could not be
 * added to the paint engine as another preset — a dab there carries a colour and knows nothing about
 * its destination — and it is also why they cost almost nothing now. The paint engine already
 * produces exactly what they need: **a coverage mask, accumulated along the stroke with the tip's
 * falloff, the flow, the spacing and the pressure dynamics all already applied.**
 *
 * So the whole tool is: take that mask, take the layer, and run a per-pixel function weighted by
 * coverage. Everything that makes the stroke feel like a brush has already happened upstream.
 */
object ToneBrush {

    /**
     * How strongly a range claims a pixel of the given luminance.
     *
     * A Gaussian rather than a hard band: a band edge shows up as a contour line across a gradient,
     * which is exactly where a dodge is most often used.
     */
    fun weightAt(range: ToneRange, luma: Float): Float {
        val d = (luma - range.centre) / Tone.TONE_RANGE_WIDTH
        return exp(-d * d)
    }

    /**
     * Dodge and burn — lightening and darkening, the darkroom operations.
     *
     * @param amount positive dodges, negative burns. One function rather than two because they are
     *   one operation with a sign, and two implementations would be two places to fix a bug.
     * @param protectTones keeps hue and saturation where they were. Photoshop's checkbox, on by
     *   default there and here: dodging in RGB desaturates as it approaches white, so an
     *   unprotected dodge on skin turns it grey before it turns it bright.
     */
    fun dodgeBurn(
        src: Raster,
        mask: FloatArray,
        amount: Float,
        range: ToneRange = ToneRange.MIDTONES,
        protectTones: Boolean = true,
    ): Raster {
        require(src.channels >= 3) { "dodge and burn need a colour image" }
        require(mask.size == src.pixelCount) { "mask does not match the image" }
        val strength = amount.coerceIn(-1f, 1f)
        if (strength == 0f) return src.copy()

        val out = src.copy()
        for (i in 0 until src.pixelCount) {
            val coverage = mask[i]
            if (coverage <= 0f) continue
            val at = i * src.channels
            val r = src.data[at]
            val g = src.data[at + 1]
            val b = src.data[at + 2]
            val l = luma(r, g, b).coerceIn(0f, 1f)

            val w = coverage * abs(strength) * weightAt(range, l)
            if (w <= 0f) continue

            // A gamma move rather than an add. Adding a constant crushes at the ends and leaves a
            // flat grey patch where a dodge ran out of headroom; a power curve approaches the limit
            // and never reaches it, which is what a darkroom dodge actually does.
            val gamma = if (strength > 0f) 1f - w * MAX_GAMMA else 1f + w * MAX_GAMMA
            val target = l.coerceAtLeast(EPSILON).pow(gamma).coerceIn(0f, 1f)

            if (protectTones) {
                // Scale the three channels by the same ratio, so hue and saturation are untouched
                // and only the level moves.
                val ratio = target / l.coerceAtLeast(EPSILON)
                for (c in 0 until 3) {
                    val v = src.data[at + c]
                    out.data[at + c] = v + ((v * ratio).coerceIn(0f, 1f) - v)
                }
            } else {
                for (c in 0 until 3) {
                    val v = src.data[at + c]
                    out.data[at + c] = v.coerceAtLeast(EPSILON).pow(gamma).coerceIn(0f, 1f)
                }
            }
        }
        return out
    }

    /**
     * Sponge — saturating or desaturating locally.
     *
     * @param amount positive saturates, negative desaturates.
     * @param vibrance protects colours that are already saturated, so a sponge over a face lifts the
     *   muted colours and leaves an already-vivid lipstick alone. Photoshop added this to the sponge
     *   in CS4 for exactly that reason and it is on by default there too.
     */
    fun sponge(src: Raster, mask: FloatArray, amount: Float, vibrance: Boolean = true): Raster {
        require(src.channels >= 3) { "the sponge needs a colour image" }
        require(mask.size == src.pixelCount) { "mask does not match the image" }
        val strength = amount.coerceIn(-1f, 1f)
        if (strength == 0f) return src.copy()

        val out = src.copy()
        for (i in 0 until src.pixelCount) {
            val coverage = mask[i]
            if (coverage <= 0f) continue
            val at = i * src.channels
            val r = src.data[at]
            val g = src.data[at + 1]
            val b = src.data[at + 2]
            val l = luma(r, g, b)

            val spread = max(r, max(g, b)) - min(r, min(g, b))
            // How much room this pixel has left. At full saturation a vibrance-aware sponge does
            // nothing; a plain one keeps pushing and clips a channel, which shifts the hue.
            val room = if (vibrance && strength > 0f) (1f - spread).coerceIn(0f, 1f) else 1f
            val w = coverage * strength * room * MAX_SATURATION
            if (w == 0f) continue

            for (c in 0 until 3) {
                val v = src.data[at + c]
                out.data[at + c] = (v + (v - l) * w).coerceIn(0f, 1f)
            }
        }
        return out
    }

    /**
     * Blur and sharpen brushes.
     *
     * @param amount positive sharpens, negative blurs.
     *
     * The blurred copy is made once for the whole layer rather than per dab. That looks wasteful and
     * is the opposite: a stroke lays down hundreds of dabs and a per-dab blur would filter the same
     * neighbourhood hundreds of times, while one pass over the layer is bounded by the layer's size
     * however long the stroke is.
     */
    fun focus(src: Raster, mask: FloatArray, amount: Float, radius: Float = DEFAULT_RADIUS): Raster {
        require(src.channels >= 3) { "the focus brushes need a colour image" }
        require(mask.size == src.pixelCount) { "mask does not match the image" }
        val strength = amount.coerceIn(-1f, 1f)
        if (strength == 0f) return src.copy()

        val blurred = Blur.gaussian(src, radius)
        val out = src.copy()
        for (i in 0 until src.pixelCount) {
            val coverage = mask[i]
            if (coverage <= 0f) continue
            val at = i * src.channels
            for (c in 0 until 3) {
                val sharp = src.data[at + c]
                val soft = blurred.data[at + c]
                // One expression for both directions: towards the blurred copy is a blur, away from
                // it is an unsharp mask. Sharpening is literally the negative of blurring here, which
                // is the identity the unsharp mask has always been built on.
                val target = if (strength < 0f) {
                    sharp + (soft - sharp) * (-strength)
                } else {
                    sharp + (sharp - soft) * strength * SHARPEN_GAIN
                }
                out.data[at + c] = sharp + (target.coerceIn(0f, 1f) - sharp) * coverage
            }
        }
        return out
    }

    /**
     * Smudge — dragging colour along the stroke.
     *
     * The one that is genuinely not a mask operation, because it depends on the *direction* the
     * finger moved and on the order the dabs were laid. So it takes the path rather than the mask:
     * each step picks up colour where it starts and lays it down a little further along, exactly as
     * a finger through wet paint does.
     *
     * @param path the stroke's points in order, in pixels.
     * @param strength how much colour is carried, 0 to 1. At 1 the pickup never fades and a stroke
     *   drags one colour across the whole picture, which is why the sheet's maximum is below that.
     */
    fun smudge(
        src: Raster,
        path: List<ir.pixellab.core.model.Vec2>,
        radius: Float,
        strength: Float,
    ): Raster {
        require(src.channels >= 3) { "smudge needs a colour image" }
        val amount = strength.coerceIn(0f, 1f)
        if (amount <= 0f || path.size < 2 || radius <= 0f) return src.copy()

        val out = src.copy()
        // The colour currently on the finger, seeded from where the stroke began.
        val carried = FloatArray(3)
        var loaded = false

        for (step in path.indices) {
            val p = path[step]
            val x0 = max(0, (p.x - radius).toInt())
            val y0 = max(0, (p.y - radius).toInt())
            val x1 = min(src.width - 1, (p.x + radius).toInt())
            val y1 = min(src.height - 1, (p.y + radius).toInt())
            if (x1 < x0 || y1 < y0) continue

            if (!loaded) {
                sampleInto(out, carried, p, radius)
                loaded = true
                continue
            }

            for (y in y0..y1) {
                for (x in x0..x1) {
                    val dx = (x + 0.5f - p.x) / radius
                    val dy = (y + 0.5f - p.y) / radius
                    val d = dx * dx + dy * dy
                    if (d >= 1f) continue
                    // Soft falloff, so the smear has no edge — a hard-edged smudge looks like a
                    // paste rather than a drag.
                    val w = (1f - d) * (1f - d) * amount
                    val at = (y * src.width + x) * src.channels
                    for (c in 0 until 3) {
                        out.data[at + c] += (carried[c] - out.data[at + c]) * w
                    }
                }
            }
            // Pick up what is now under the finger, mixed with what it was already carrying. The
            // mixing is what makes a long smudge fade rather than stamping one colour forever.
            val fresh = FloatArray(3)
            sampleInto(out, fresh, p, radius)
            for (c in 0 until 3) carried[c] += (fresh[c] - carried[c]) * (1f - amount)
        }
        return out
    }

    private fun sampleInto(src: Raster, into: FloatArray, at: ir.pixellab.core.model.Vec2, radius: Float) {
        val x0 = max(0, (at.x - radius * 0.5f).toInt())
        val y0 = max(0, (at.y - radius * 0.5f).toInt())
        val x1 = min(src.width - 1, (at.x + radius * 0.5f).toInt())
        val y1 = min(src.height - 1, (at.y + radius * 0.5f).toInt())
        var n = 0
        val sum = FloatArray(3)
        for (y in y0..y1) {
            for (x in x0..x1) {
                val i = (y * src.width + x) * src.channels
                for (c in 0 until 3) sum[c] += src.data[i + c]
                n++
            }
        }
        if (n == 0) return
        for (c in 0 until 3) into[c] = sum[c] / n
    }

    private fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

    /** At full pressure and full coverage, a dab is worth this much of a gamma move. */
    private const val MAX_GAMMA = 0.55f

    private const val MAX_SATURATION = 0.6f

    private const val DEFAULT_RADIUS = 2.5f
    private const val SHARPEN_GAIN = 1.5f

    private const val EPSILON = 1e-4f
}
