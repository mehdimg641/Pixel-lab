package ir.pixellab.core.imaging

import ir.pixellab.core.model.Adjustment
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.ColorFamily
import ir.pixellab.core.model.ColorStatistics
import ir.pixellab.core.model.Curve
import ir.pixellab.core.model.HdrMethod
import ir.pixellab.core.model.Ramp
import ir.pixellab.core.model.Tone
import ir.pixellab.core.model.Vec3
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Every one of Photoshop's twenty-two adjustments, on the CPU, in float.
 *
 * This exists for three reasons and each of them on its own would justify it.
 *
 * It is the **oracle**. The adjustments the app actually shows on screen are a single GLSL program
 * with a branch per mode, and until this file there was no test in the project that checked what
 * that program produced — only that the right numbers were packed into the right uniform slots. A
 * shader that computes the wrong thing packs its uniforms perfectly. Here the same arithmetic is
 * written where a test can call it with a colour and assert on the answer, and the shader is then
 * checked against it.
 *
 * It is the **export path** for the corrections a shader cannot do at all. Six of the twenty-two —
 * Shadows/Highlights, HDR Toning, Match Color, Equalize, and the two that depend on a histogram —
 * need either a neighbourhood or a whole-image measurement. Photoshop's answer is to keep those six
 * out of its adjustment layers entirely and offer them only as destructive commands under Image.
 * Ours are layers like the rest, which is possible because the measurement is taken once and frozen
 * into the layer, and this is where that measurement is taken.
 *
 * And it is where the **differences from Photoshop that are deliberate** are written down beside the
 * code, rather than discovered later by someone comparing two files.
 *
 * Everything works on straight, non-premultiplied RGB in the document's own space. Alpha is carried
 * through untouched: an adjustment changes colour, never coverage.
 */
object Adjust {

    /**
     * Applies one adjustment to a whole raster.
     *
     * @param lut resolves a Color Lookup layer's cube. Returning null leaves the picture alone,
     *   which is what an unlinked or still-loading table should do — not a black frame.
     */
    fun apply(
        src: Raster,
        adjustment: Adjustment,
        lut: ((ir.pixellab.core.model.AssetId) -> Raster?)? = null,
    ): Raster {
        require(src.channels >= 3) { "an adjustment needs colour, got ${src.channels} channels" }

        // The three that read more than one pixel are answered first, because they cannot be
        // expressed as a per-pixel function and the loop below only knows how to run those.
        when (adjustment) {
            is Adjustment.ShadowsHighlights -> return shadowsHighlights(src, adjustment)
            is Adjustment.HdrToning -> return hdrToning(src, adjustment)
            else -> Unit
        }

        val prepared = Prepared.of(adjustment, lut)
        val out = src.copy()
        for (i in 0 until src.pixelCount) {
            val at = i * src.channels
            val colour = prepared.at(src.data[at], src.data[at + 1], src.data[at + 2])
            out.data[at] = colour.x
            out.data[at + 1] = colour.y
            out.data[at + 2] = colour.z
        }
        return out
    }

    /**
     * One adjustment applied to a single colour.
     *
     * Exposed because it is what a test wants to assert on, and because a colour picker preview
     * genuinely does need to run an adjustment on one swatch rather than on an image.
     */
    fun applyTo(colour: Vec3, adjustment: Adjustment): Vec3 =
        Prepared.of(adjustment, null).at(colour.x, colour.y, colour.z)

    // ---- analysis: the measurements the frozen adjustments carry ----------------------------

    /**
     * The cumulative-histogram mapping an Equalize layer freezes.
     *
     * Photoshop equalises **luminance** and carries the colour along by ratio rather than
     * equalising each channel on its own. Per-channel equalisation is the textbook version and it
     * wrecks the picture: three independently stretched channels no longer agree about grey, so
     * every neutral in the frame acquires a cast.
     *
     * The mapping is built from the cumulative distribution and normalised so the darkest tone
     * present lands on black — otherwise a picture that never reaches black comes back milky, which
     * is precisely the thing equalisation is asked to fix.
     */
    fun equalizeTable(src: Raster, size: Int = TABLE_SIZE): FloatArray {
        require(src.channels >= 3) { "equalise needs colour, got ${src.channels} channels" }
        val counts = IntArray(size)
        for (i in 0 until src.pixelCount) {
            val at = i * src.channels
            val l = luma(src.data[at], src.data[at + 1], src.data[at + 2]).coerceIn(0f, 1f)
            counts[(l * (size - 1) + 0.5f).toInt()]++
        }

        val total = src.pixelCount.toFloat()
        var running = 0f
        val cumulative = FloatArray(size)
        for (i in 0 until size) {
            running += counts[i]
            cumulative[i] = running / total
        }

        // The cumulative value of the darkest bin that holds anything. Subtracting it and rescaling
        // is what puts that tone at black instead of at its own population fraction.
        val floorValue = cumulative.firstOrNull { it > 0f } ?: 0f
        val span = (1f - floorValue).coerceAtLeast(EPSILON)
        return FloatArray(size) { ((cumulative[it] - floorValue) / span).coerceIn(0f, 1f) }
    }

    /** The per-channel mean and standard deviation a Match Color layer transplants. */
    fun statistics(src: Raster): ColorStatistics {
        require(src.channels >= 3) { "statistics need colour, got ${src.channels} channels" }
        val sum = DoubleArray(3)
        val sumSquares = DoubleArray(3)
        for (i in 0 until src.pixelCount) {
            val at = i * src.channels
            for (c in 0 until 3) {
                val v = src.data[at + c].toDouble()
                sum[c] += v
                sumSquares[c] += v * v
            }
        }
        val n = src.pixelCount.toDouble()
        val mean = DoubleArray(3) { sum[it] / n }
        // Population rather than sample deviation: this is the whole picture, not a sample of one.
        val deviation = DoubleArray(3) {
            sqrt(max(0.0, sumSquares[it] / n - mean[it] * mean[it]))
        }
        return ColorStatistics(
            mean = Vec3(mean[0].toFloat(), mean[1].toFloat(), mean[2].toFloat()),
            deviation = Vec3(deviation[0].toFloat(), deviation[1].toFloat(), deviation[2].toFloat()),
            measured = true,
        )
    }

    /**
     * Where a pair of clip fractions actually falls in this picture's luminance.
     *
     * Shadows/Highlights ends with a clip, and a clip is a percentile. Measured here so the layer
     * can carry the answer rather than the question.
     */
    fun clipPoints(src: Raster, blackClip: Float, whiteClip: Float, size: Int = TABLE_SIZE): Pair<Float, Float> {
        require(src.channels >= 3) { "clip points need colour, got ${src.channels} channels" }
        val counts = IntArray(size)
        for (i in 0 until src.pixelCount) {
            val at = i * src.channels
            val l = luma(src.data[at], src.data[at + 1], src.data[at + 2]).coerceIn(0f, 1f)
            counts[(l * (size - 1) + 0.5f).toInt()]++
        }
        val total = src.pixelCount.toFloat()
        return percentile(counts, total, blackClip, size) to
            (1f - percentile(counts.reversedArray(), total, whiteClip, size))
    }

    private fun percentile(counts: IntArray, total: Float, fraction: Float, size: Int): Float {
        if (fraction <= 0f) return 0f
        val target = total * fraction.coerceIn(0f, 1f)
        var running = 0f
        for (i in 0 until size) {
            running += counts[i]
            if (running >= target) return i.toFloat() / (size - 1)
        }
        return 1f
    }

    // ---- the two that read a neighbourhood ---------------------------------------------------

    /**
     * Shadows/Highlights, with Photoshop's own parameter set.
     *
     * The neighbourhood is a blur of the luminance, and the correction at a pixel is driven by that
     * blur rather than by the pixel — which is the whole difference between this and a curve. The
     * two radii are separate because they are separate in the panel, and because they want different
     * values in practice: a large radius on the shadows keeps a face from haloing, and a small one
     * on the highlights is what actually recovers a blown window frame.
     *
     * All three channels are scaled by one gain rather than corrected on their own masks. A
     * per-channel gain shifts the hue of everything it lifts, and that hue shift is precisely the
     * muddy look that gives over-processed work away.
     */
    fun shadowsHighlights(src: Raster, a: Adjustment.ShadowsHighlights): Raster {
        val luminance = src.luminance()
        val shadowLocal = if (a.shadowRadius > 0f) Blur.gaussian(luminance, a.shadowRadius) else luminance
        val highlightLocal =
            if (a.highlightRadius == a.shadowRadius) shadowLocal
            else if (a.highlightRadius > 0f) Blur.gaussian(luminance, a.highlightRadius) else luminance

        val out = src.copy()
        for (i in 0 until src.pixelCount) {
            val at = i * src.channels
            var gain = 1f
            if (a.shadowAmount > 0f) {
                val dark = 1f - shadowLocal.data[i].coerceIn(0f, 1f)
                gain *= 1f + a.shadowAmount * SHADOW_HEADROOM * falloff(dark, a.shadowTone)
            }
            if (a.highlightAmount > 0f) {
                val bright = highlightLocal.data[i].coerceIn(0f, 1f)
                gain *= 1f - a.highlightAmount * HIGHLIGHT_DEPTH * falloff(bright, a.highlightTone)
            }

            var r = src.data[at] * gain
            var g = src.data[at + 1] * gain
            var b = src.data[at + 2] * gain

            if (a.midtoneContrast != 0f) {
                r = midtone(r, a.midtoneContrast)
                g = midtone(g, a.midtoneContrast)
                b = midtone(b, a.midtoneContrast)
            }

            if (a.color != 0f && gain != 1f) {
                // Only in proportion to how far the pixel actually moved: lifting a shadow
                // desaturates it, because the colours were compressed down there, and this puts back
                // what the lift took out without touching a midtone that never moved.
                val l = luma(r, g, b)
                val strength = 1f + a.color * min(abs(gain - 1f), 1f)
                r = l + (r - l) * strength
                g = l + (g - l) * strength
                b = l + (b - l) * strength
            }

            // The clip last, as a levels stretch between the two frozen points. Photoshop's own
            // defaults put these a hundredth of a per cent from the ends, so at rest it does nothing
            // and only a deliberately raised clip has any effect at all.
            if (a.blackPoint > 0f || a.whitePoint < 1f) {
                val span = max(a.whitePoint - a.blackPoint, EPSILON)
                r = (r - a.blackPoint) / span
                g = (g - a.blackPoint) / span
                b = (b - a.blackPoint) / span
            }

            out.data[at] = r.coerceIn(0f, 1f)
            out.data[at + 1] = g.coerceIn(0f, 1f)
            out.data[at + 2] = b.coerceIn(0f, 1f)
        }
        return out
    }

    /**
     * HDR Toning, all four of Photoshop's methods.
     *
     * The first three are global curves and are written as such. [HdrMethod.LOCAL_ADAPTATION] is the
     * one anyone actually opens the dialog for, and it works by splitting the log-luminance into a
     * slowly-varying base and the detail riding on it, compressing only the base, and putting the
     * detail back. That split is why it can flatten a twelve-stop scene without flattening a face.
     *
     * The split uses a **guided filter**, not a Gaussian. A Gaussian base blurs across every edge in
     * the picture, so compressing it leaves a bright halo along each one — and that halo is the
     * single thing that makes tone-mapped work look tone-mapped. The guided filter's base follows
     * edges, so the compression stops where the edge does.
     *
     * Working in the log domain is the second half of it. Compressing a ratio is what a stop is; a
     * subtraction in linear light would darken the shadows it is supposed to be opening.
     */
    fun hdrToning(src: Raster, a: Adjustment.HdrToning): Raster {
        val out = src.copy()
        val exposed = FloatArray(3)
        val gain = 2f.pow(a.exposure)

        val base: FloatArray? = if (a.method == HdrMethod.LOCAL_ADAPTATION) localBase(src, a.radius) else null

        for (i in 0 until src.pixelCount) {
            val at = i * src.channels
            for (c in 0 until 3) exposed[c] = max(src.data[at + c] * gain, 0f)

            var r = exposed[0]
            var g = exposed[1]
            var b = exposed[2]

            when (a.method) {
                HdrMethod.EXPOSURE_AND_GAMMA -> Unit

                // Fits the range onto the screen by compressing the top and leaving the bottom
                // alone, which is why it never touches the shadows and never needs a radius.
                HdrMethod.HIGHLIGHT_COMPRESSION -> {
                    val l = max(luma(r, g, b), EPSILON)
                    val scale = (l / (1f + l)) / l
                    r *= scale; g *= scale; b *= scale
                }

                // Photoshop's own naming: the histogram of the scene is flattened globally. A single
                // logarithmic curve is what that amounts to for a picture already in 0..1.
                HdrMethod.EQUALIZE_HISTOGRAM -> {
                    val l = max(luma(r, g, b), EPSILON)
                    val scale = (ln(1f + l * LOG_KNEE) / ln(1f + LOG_KNEE)) / l
                    r *= scale; g *= scale; b *= scale
                }

                HdrMethod.LOCAL_ADAPTATION -> {
                    // Floored at the same value the base was, and that agreement matters more than
                    // it looks. The detail term is the *difference* between the two, so flooring one
                    // and not the other makes a single black pixel produce a detail of six log units
                    // where the truth is nearly zero — which comes back as a pit of pure black
                    // wherever the picture touches the bottom of its range.
                    val l = max(luma(r, g, b), LOG_FLOOR_VALUE)
                    val logL = ln(l)
                    val logBase = base!![i]
                    val detail = logL - logBase

                    // Strength is how hard the base is squeezed; detail is how much of the
                    // high-frequency goes back on top, and above one it is the local-contrast
                    // control the panel calls Detail.
                    val compressed = logBase / max(a.strength, EPSILON) + detail * (1f + a.detail)
                    val scale = exp(compressed) / l
                    r *= scale; g *= scale; b *= scale
                }
            }

            // Shadow and Highlight ride on top of whichever method ran, exactly as the panel has
            // them: two more sliders below the method, acting on the already-mapped tone.
            if (a.shadow != 0f || a.highlight != 0f) {
                val l = luma(r, g, b).coerceIn(0f, 1f)
                val lift = a.shadow * (1f - l) * (1f - l)
                val pull = a.highlight * l * l
                val scale = (1f + lift + pull)
                r *= scale; g *= scale; b *= scale
            }

            if (a.gamma != 1f) {
                val exponent = 1f / max(a.gamma, EPSILON)
                r = max(r, 0f).pow(exponent)
                g = max(g, 0f).pow(exponent)
                b = max(b, 0f).pow(exponent)
            }

            if (a.vibrance != 0f || a.saturation != 0f) {
                val v = vibrance(Vec3(r, g, b), a.vibrance, a.saturation)
                r = v.x; g = v.y; b = v.z
            }

            if (a.toningCurve != Curve.LINEAR) {
                r = a.toningCurve.evaluate(r.coerceIn(0f, 1f))
                g = a.toningCurve.evaluate(g.coerceIn(0f, 1f))
                b = a.toningCurve.evaluate(b.coerceIn(0f, 1f))
            }

            out.data[at] = r.coerceIn(0f, 1f)
            out.data[at + 1] = g.coerceIn(0f, 1f)
            out.data[at + 2] = b.coerceIn(0f, 1f)
        }
        return out
    }

    /**
     * The edge-following base layer, in log luminance.
     *
     * Filtered in a **normalised** log domain rather than the raw one, and that normalisation is not
     * cosmetic. The guided filter's regularisation is compared against the local variance of its
     * guide, so what counts as "an edge rather than texture" is set by the guide's units. Raw
     * natural-log luminance spans about five and a half, which puts every ordinary gradient's
     * variance far above any sensible epsilon — the filter then passes the picture through almost
     * unchanged, the base keeps the detail it was supposed to give up, and compressing the base
     * compresses the detail with it. The operator degenerates into a global gamma, which is exactly
     * the thing it exists to avoid.
     *
     * Mapped to 0..1 first, the epsilon means what the literature says it means.
     */
    private fun localBase(src: Raster, radius: Float): FloatArray {
        val logL = Raster(src.width, src.height, 1)
        for (i in 0 until src.pixelCount) {
            val at = i * src.channels
            val l = max(luma(src.data[at], src.data[at + 1], src.data[at + 2]), LOG_FLOOR_VALUE)
            logL.data[i] = (ln(l) - LOG_FLOOR) / -LOG_FLOOR
        }
        val window = max(radius.toInt(), 1)
        val base = GuidedFilter.filter(logL, logL, window, GUIDE_EPSILON)
        for (i in base.data.indices) base.data[i] = base.data[i] * -LOG_FLOOR + LOG_FLOOR
        return base.data
    }

    // ---- the twenty that are per-pixel -------------------------------------------------------

    /**
     * An adjustment with everything that does not depend on the pixel already worked out.
     *
     * Sampling a curve, resolving a gradient ramp or unpacking Selective Color's nine families once
     * per image rather than once per pixel is the difference between this being usable on a
     * twenty-megapixel document and not.
     */
    private class Prepared(private val evaluate: (Float, Float, Float) -> Vec3) {

        fun at(r: Float, g: Float, b: Float): Vec3 = evaluate(r, g, b)

        companion object {

            fun of(
                adjustment: Adjustment,
                lut: ((ir.pixellab.core.model.AssetId) -> Raster?)?,
            ): Prepared = when (adjustment) {

                is Adjustment.BrightnessContrast -> Prepared { r, g, b ->
                    val f = { v: Float -> (v + adjustment.brightness - 0.5f) * (1f + adjustment.contrast) + 0.5f }
                    clamped(f(r), f(g), f(b))
                }

                is Adjustment.Levels -> {
                    val perChannel = List(3) { i ->
                        adjustment.perChannel.getOrNull(i)?.let { table(TABLE_SIZE) { v -> levels(v, it) } }
                    }
                    Prepared { r, g, b ->
                        var x = levels(r, adjustment)
                        var y = levels(g, adjustment)
                        var z = levels(b, adjustment)
                        perChannel[0]?.let { x = sample(it, x) }
                        perChannel[1]?.let { y = sample(it, y) }
                        perChannel[2]?.let { z = sample(it, z) }
                        clamped(x, y, z)
                    }
                }

                is Adjustment.Curves -> {
                    val composite = table(TABLE_SIZE) { adjustment.rgb.evaluate(it) }
                    val red = table(TABLE_SIZE) { adjustment.red.evaluate(it) }
                    val green = table(TABLE_SIZE) { adjustment.green.evaluate(it) }
                    val blue = table(TABLE_SIZE) { adjustment.blue.evaluate(it) }
                    // Per channel first, then the composite: the order the panel implies, and the
                    // order that makes a composite S-curve behave the same however the channels
                    // were set.
                    Prepared { r, g, b ->
                        clamped(
                            sample(composite, sample(red, r)),
                            sample(composite, sample(green, g)),
                            sample(composite, sample(blue, b)),
                        )
                    }
                }

                is Adjustment.HueSaturation -> Prepared { r, g, b ->
                    val red = r.coerceIn(0f, 1f)
                    val green = g.coerceIn(0f, 1f)
                    val blue = b.coerceIn(0f, 1f)
                    val hsl = toHsl(red, green, blue)
                    val hue: Float
                    val saturation: Float
                    if (adjustment.colorize) {
                        // Colorize replaces the hue outright rather than rotating it, which is the
                        // whole point of the checkbox.
                        hue = fract(adjustment.hue)
                        saturation = adjustment.saturation.coerceIn(0f, 1f)
                    } else {
                        hue = fract(hsl.x + adjustment.hue)
                        saturation = (hsl.y * (1f + adjustment.saturation)).coerceIn(0f, 1f)
                    }
                    val lightness = (
                        hsl.z + adjustment.lightness *
                            (if (adjustment.lightness > 0f) 1f - hsl.z else hsl.z)
                        ).coerceIn(0f, 1f)
                    val shifted = toRgb(hue, saturation, lightness)

                    // Master leaves the weight at one, so the whole picture moves. A family weights
                    // by how close this pixel's hue is to the family's centre, which is the only
                    // thing separating "deepen the sky" from "turn every skin tone cyan".
                    val centre = adjustment.rangeCentre
                    if (centre == null) {
                        shifted
                    } else {
                        val w = hueBandWeight(hsl.x, hsl.y, centre)
                        Vec3(
                            red + (shifted.x - red) * w,
                            green + (shifted.y - green) * w,
                            blue + (shifted.z - blue) * w,
                        )
                    }
                }

                is Adjustment.Exposure -> {
                    val stops = 2f.pow(adjustment.exposure)
                    val exponent = 1f / max(adjustment.gamma, EPSILON)
                    Prepared { r, g, b ->
                        clamped(
                            max(r * stops + adjustment.offset, 0f).pow(exponent),
                            max(g * stops + adjustment.offset, 0f).pow(exponent),
                            max(b * stops + adjustment.offset, 0f).pow(exponent),
                        )
                    }
                }

                is Adjustment.Vibrance -> Prepared { r, g, b ->
                    val v = vibrance(Vec3(r, g, b), adjustment.vibrance, adjustment.saturation)
                    clamped(v.x, v.y, v.z)
                }

                is Adjustment.ColorBalance -> Prepared { r, g, b ->
                    val balanced = balance(
                        Vec3(r, g, b), adjustment.shadows, adjustment.midtones, adjustment.highlights,
                    )
                    if (!adjustment.preserveLuminosity) return@Prepared clamped(balanced.x, balanced.y, balanced.z)
                    val before = luma(r, g, b)
                    val after = max(luma(balanced.x, balanced.y, balanced.z), EPSILON)
                    val k = before / after
                    clamped(balanced.x * k, balanced.y * k, balanced.z * k)
                }

                is Adjustment.BlackWhite -> {
                    val w = FloatArray(6) { adjustment.weights.getOrElse(it) { DEFAULT_BW[it] } }
                    Prepared { r, g, b ->
                        val v = blackWhite(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f), w)
                        Vec3(v, v, v)
                    }
                }

                is Adjustment.GradientMap -> {
                    val stops = adjustment.gradient.stops.sortedBy { it.position }
                    val ramp = Array(TABLE_SIZE) { Ramp.colorAt(stops, it.toFloat() / (TABLE_SIZE - 1)) }
                    val reverse = adjustment.gradient.reverse
                    Prepared { r, g, b ->
                        val l = luma(r, g, b).coerceIn(0f, 1f)
                        val t = if (reverse) 1f - l else l
                        val c = ramp[(t * (TABLE_SIZE - 1) + 0.5f).toInt()]
                        Vec3(c.r, c.g, c.b)
                    }
                }

                is Adjustment.PhotoFilter -> {
                    val tint = adjustment.color
                    val density = adjustment.density.coerceIn(0f, 1f)
                    Prepared { r, g, b ->
                        // A filter over a lens multiplies; it does not blend towards a colour. The
                        // difference shows on anything already saturated, where a blend would drag
                        // it towards the filter and a multiply only darkens what the glass absorbs.
                        var x = r + (r * tint.r - r) * density
                        var y = g + (g * tint.g - g) * density
                        var z = b + (b * tint.b - b) * density
                        if (adjustment.preserveLuminosity) {
                            val k = luma(r, g, b) / max(luma(x, y, z), EPSILON)
                            x *= k; y *= k; z *= k
                        }
                        clamped(x, y, z)
                    }
                }

                Adjustment.Invert -> Prepared { r, g, b -> clamped(1f - r, 1f - g, 1f - b) }

                is Adjustment.Posterize -> {
                    val levels = max(adjustment.levels, 2).toFloat()
                    Prepared { r, g, b ->
                        val f = { v: Float -> kotlin.math.floor(v.coerceIn(0f, 1f) * levels) / (levels - 1f) }
                        clamped(f(r), f(g), f(b))
                    }
                }

                is Adjustment.Threshold -> Prepared { r, g, b ->
                    val v = if (luma(r, g, b) >= adjustment.level) 1f else 0f
                    Vec3(v, v, v)
                }

                is Adjustment.ColorLookup -> {
                    val cube = lut?.invoke(adjustment.asset)
                    val amount = adjustment.amount.coerceIn(0f, 1f)
                    if (cube == null) Prepared { r, g, b -> Vec3(r, g, b) } else Prepared { r, g, b ->
                        val looked = lookup(cube, r, g, b)
                        clamped(
                            r + (looked.x - r) * amount,
                            g + (looked.y - g) * amount,
                            b + (looked.z - b) * amount,
                        )
                    }
                }

                is Adjustment.SelectiveColor -> {
                    val ink = Array(ColorFamily.entries.size) { FloatArray(4) }
                    val byFamily = adjustment.ranges.associateBy { it.family }
                    for ((index, family) in ColorFamily.entries.withIndex()) {
                        byFamily[family]?.let {
                            ink[index][0] = it.cyan
                            ink[index][1] = it.magenta
                            ink[index][2] = it.yellow
                            ink[index][3] = it.black
                        }
                    }
                    Prepared { r, g, b -> selectiveColor(r, g, b, ink, adjustment.absolute) }
                }

                is Adjustment.ChannelMixer -> {
                    // Monochrome is not a flag: it *is* the same recipe in all three rows, and
                    // writing it that way means there is one path rather than two.
                    val rows = if (adjustment.monochrome) {
                        List(3) { adjustment.gray }
                    } else {
                        listOf(adjustment.red, adjustment.green, adjustment.blue)
                    }
                    Prepared { r, g, b ->
                        clamped(
                            rows[0].red * r + rows[0].green * g + rows[0].blue * b + rows[0].constant,
                            rows[1].red * r + rows[1].green * g + rows[1].blue * b + rows[1].constant,
                            rows[2].red * r + rows[2].green * g + rows[2].blue * b + rows[2].constant,
                        )
                    }
                }

                // Photoshop's Desaturate is Hue/Saturation at −100, so its grey is HSL lightness —
                // the midpoint of the brightest and darkest channel — and *not* a weighted luma.
                // Pure red becomes mid grey here and dark grey under a luma conversion, so mixing
                // the two up is invisible until two files are opened side by side.
                Adjustment.Desaturate -> Prepared { r, g, b ->
                    val v = (max(r, max(g, b)) + min(r, min(g, b))) * 0.5f
                    clamped(v, v, v)
                }

                is Adjustment.MatchColor -> {
                    val stats = adjustment.statistics
                    Prepared { r, g, b -> matchColor(r, g, b, stats, adjustment) }
                }

                is Adjustment.ReplaceColor -> Prepared { r, g, b -> replaceColor(r, g, b, adjustment) }

                is Adjustment.Equalize -> {
                    val frozen = adjustment.table
                    if (frozen.isEmpty()) Prepared { r, g, b -> Vec3(r, g, b) } else Prepared { r, g, b ->
                        val l = luma(r, g, b).coerceIn(0f, 1f)
                        val mapped = frozen[(l * (frozen.size - 1) + 0.5f).toInt()]
                        // Scaled rather than replaced, so the hue survives the redistribution. A
                        // per-channel equalisation is the textbook version and it puts a cast on
                        // every neutral in the frame.
                        val k = mapped / max(l, EPSILON)
                        clamped(r * k, g * k, b * k)
                    }
                }

                is Adjustment.ShadowsHighlights, is Adjustment.HdrToning ->
                    // Answered before this point; a neighbourhood has no per-pixel form.
                    error("${adjustment::class.simpleName} is not a per-pixel adjustment")
            }
        }
    }

    // ---- the shared arithmetic ---------------------------------------------------------------

    fun luma(r: Float, g: Float, b: Float) = LUMA_R * r + LUMA_G * g + LUMA_B * b

    private fun clamped(r: Float, g: Float, b: Float) =
        Vec3(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))

    private fun fract(v: Float) = v - kotlin.math.floor(v)

    /**
     * How much a pixel belongs to a hue family — Photoshop's Hue/Saturation range sliders.
     *
     * Two guards, and the tool is unusable without either. **Distance is measured the short way
     * round the wheel**, so the reds band reaches both 350° and 10° instead of splitting in two at
     * the seam. And **a near-grey pixel is excluded**: it has no hue worth calling a hue, and
     * rotating one swings whatever direction its noise happened to lean, which turns a smooth wall
     * mottled.
     */
    fun hueBandWeight(hue: Float, saturation: Float, centre: Float): Float {
        val raw = abs(fract(hue - centre + 0.5f) - 0.5f)
        val band = when {
            raw <= Tone.HUE_BAND_CORE -> 1f
            raw >= Tone.HUE_BAND_EDGE -> 0f
            else -> {
                val t = (Tone.HUE_BAND_EDGE - raw) / (Tone.HUE_BAND_EDGE - Tone.HUE_BAND_CORE)
                t * t * (3f - 2f * t)
            }
        }
        val grey = (saturation / Tone.HUE_BAND_MIN_SATURATION).coerceIn(0f, 1f)
        return band * grey
    }

    private fun table(size: Int, f: (Float) -> Float) = FloatArray(size) { f(it.toFloat() / (size - 1)) }

    private fun sample(table: FloatArray, v: Float) =
        table[(v.coerceIn(0f, 1f) * (table.size - 1) + 0.5f).toInt()]

    private fun levels(v: Float, a: Adjustment.Levels): Float {
        val range = max(a.inputWhite - a.inputBlack, EPSILON)
        val n = ((v - a.inputBlack) / range).coerceIn(0f, 1f).pow(1f / max(a.gamma, EPSILON))
        return a.outputBlack + n * (a.outputWhite - a.outputBlack)
    }

    private fun midtone(v: Float, amount: Float) = ((v - 0.5f) * (1f + amount) + 0.5f).coerceIn(0f, 1f)

    /**
     * How strongly a tone belongs to the range being corrected.
     *
     * `tone` is a *width*, read the way the panel's slider reads: at half, the shadow correction
     * reaches the tones below half and stops, so an eighty-five per cent highlight is untouched by a
     * shadow lift. Smoothstep rather than a straight ramp because a linear ramp is continuous but
     * its slope is not, and that kink shows as a faint edge across a clear sky.
     */
    internal fun falloff(distanceIntoRange: Float, tone: Float): Float {
        val width = tone.coerceIn(MIN_TONE, 1f)
        val t = ((distanceIntoRange.coerceIn(0f, 1f) - (1f - width)) / width).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Photoshop's colour balance shifts each range along three opposed axes. */
    private fun balance(c: Vec3, shadows: Vec3, midtones: Vec3, highlights: Vec3): Vec3 {
        val l = luma(c.x, c.y, c.z)
        // Overlapping weights, so a change to the midtones does not stop dead at a boundary and
        // leave a visible band across a gradient.
        val sw = (1f - l * 2f).coerceIn(0f, 1f)
        val hw = (l * 2f - 1f).coerceIn(0f, 1f)
        val mw = 1f - sw - hw
        return Vec3(
            c.x + shadows.x * sw + midtones.x * mw + highlights.x * hw,
            c.y + shadows.y * sw + midtones.y * mw + highlights.y * hw,
            c.z + shadows.z * sw + midtones.z * mw + highlights.z * hw,
        )
    }

    /**
     * Vibrance protects what is already saturated, which is what keeps skin from going orange when
     * a landscape is pushed. Saturation does not, and is applied on top.
     */
    private fun vibrance(c: Vec3, vibrance: Float, saturation: Float): Vec3 {
        val mx = max(c.x, max(c.y, c.z))
        val mn = min(c.x, min(c.y, c.z))
        val amount = 1f + vibrance * (1f - (mx - mn)) + saturation
        val l = luma(c.x, c.y, c.z)
        val k = max(amount, 0f)
        return Vec3(l + (c.x - l) * k, l + (c.y - l) * k, l + (c.z - l) * k)
    }

    /**
     * Six-way monochrome mixing, as the Black and White panel does it.
     *
     * The six chromatic weights sum to exactly the pixel's saturation, so adding them to the
     * darkest channel reproduces the original luminance when every slider sits at its neutral —
     * which is the property that makes the panel's numbers mean what they say.
     */
    private fun blackWhite(r: Float, g: Float, b: Float, w: FloatArray): Float {
        val mn = min(r, min(g, b))
        val weight =
            w[0] * max(0f, min(r - g, r - b)) +
                w[1] * max(0f, min(r, g) - b) +
                w[2] * max(0f, min(g - r, g - b)) +
                w[3] * max(0f, min(g, b) - r) +
                w[4] * max(0f, min(b - r, b - g)) +
                w[5] * max(0f, min(r, b) - g)
        return (mn + weight).coerceIn(0f, 1f)
    }

    /**
     * Per-family CMYK correction, through a full black separation and back.
     *
     * Without the K separation the black slider has nothing to act on, and it is the slider that
     * makes a shadow deeper rather than merely darker.
     */
    private fun selectiveColor(r: Float, g: Float, b: Float, ink: Array<FloatArray>, absolute: Boolean): Vec3 {
        val red = r.coerceIn(0f, 1f)
        val green = g.coerceIn(0f, 1f)
        val blue = b.coerceIn(0f, 1f)
        val mx = max(red, max(green, blue))
        val mn = min(red, min(green, blue))

        val w = FloatArray(ColorFamily.entries.size)
        w[0] = max(0f, min(red - green, red - blue))
        w[1] = max(0f, min(red, green) - blue)
        w[2] = max(0f, min(green - red, green - blue))
        w[3] = max(0f, min(green, blue) - red)
        w[4] = max(0f, min(blue - red, blue - green))
        w[5] = max(0f, min(red, blue) - green)
        val whites = ((mn - 0.5f) * 2f).coerceIn(0f, 1f)
        val blacks = ((0.5f - mx) * 2f).coerceIn(0f, 1f)
        w[6] = whites
        w[7] = (1f - whites - blacks).coerceIn(0f, 1f)
        w[8] = blacks

        val shift = FloatArray(4)
        for (family in w.indices) for (c in 0 until 4) shift[c] += w[family] * ink[family][c]

        var k = 1f - mx
        val denominator = max(mx, EPSILON)
        var cyan = (mx - red) / denominator
        var magenta = (mx - green) / denominator
        var yellow = (mx - blue) / denominator
        if (absolute) {
            cyan += shift[0]; magenta += shift[1]; yellow += shift[2]; k += shift[3]
        } else {
            // Relative: a ten per cent push on a pixel holding half its cyan adds five, so the
            // correction cannot invent ink where there was none — which is the whole reason this
            // mode is the safe one on skin.
            cyan += cyan * shift[0]; magenta += magenta * shift[1]
            yellow += yellow * shift[2]; k += k * shift[3]
        }
        val black = 1f - k.coerceIn(0f, 1f)
        return clamped(
            (1f - cyan.coerceIn(0f, 1f)) * black,
            (1f - magenta.coerceIn(0f, 1f)) * black,
            (1f - yellow.coerceIn(0f, 1f)) * black,
        )
    }

    /** A 512x512 strip holding a 64-cube, which is what every LUT file on disk is. */
    private fun lookup(cube: Raster, r: Float, g: Float, b: Float): Vec3 {
        val slices = SLICES
        val blue = b.coerceIn(0f, 1f) * (slices - 1)
        val lower = kotlin.math.floor(blue).toInt()
        val upper = min(lower + 1, slices - 1)
        val t = blue - lower
        val low = cubeTexel(cube, r, g, lower)
        val high = cubeTexel(cube, r, g, upper)
        return Vec3(
            low.x + (high.x - low.x) * t,
            low.y + (high.y - low.y) * t,
            low.z + (high.z - low.z) * t,
        )
    }

    private fun cubeTexel(cube: Raster, r: Float, g: Float, slice: Int): Vec3 {
        val x = (slice % SLICES_PER_ROW) * SLICES + (r.coerceIn(0f, 1f) * (SLICES - 1) + 0.5f).toInt()
        val y = (slice / SLICES_PER_ROW) * SLICES + (g.coerceIn(0f, 1f) * (SLICES - 1) + 0.5f).toInt()
        val cx = x.coerceIn(0, cube.width - 1)
        val cy = y.coerceIn(0, cube.height - 1)
        return Vec3(cube[cx, cy, 0], cube[cx, cy, 1], cube[cx, cy, 2])
    }

    /**
     * Reinhard's colour transfer: match the mean and the spread of each channel.
     *
     * `Luminance` and `Color Intensity` are the panel's two scales on the result — the first on how
     * far the tone moves, the second on how far the colour does — and `Fade` blends the whole thing
     * back towards the original, which is what makes it usable rather than absolute.
     */
    private fun matchColor(
        r: Float,
        g: Float,
        b: Float,
        stats: ColorStatistics,
        a: Adjustment.MatchColor,
    ): Vec3 {
        if (!stats.measured) return Vec3(r, g, b)
        val source = Vec3(r, g, b)
        val l = luma(r, g, b)

        // The tone and the colour are moved separately so the two sliders are independent: the
        // luminance carries the mean, and what is left over carries the spread.
        val targetLuma = luma(stats.mean.x, stats.mean.y, stats.mean.z)
        val movedLuma = l + (targetLuma - l) * a.luminance

        val deviation = Vec3(
            max(stats.deviation.x, EPSILON),
            max(stats.deviation.y, EPSILON),
            max(stats.deviation.z, EPSILON),
        )
        val average = max((deviation.x + deviation.y + deviation.z) / 3f, EPSILON)
        // Neutralize pulls every channel onto one spread, which is exactly what removes a cast:
        // a cast *is* one channel sitting at a different mean from the others.
        val scale = Vec3(
            if (a.neutralize) average else deviation.x,
            if (a.neutralize) average else deviation.y,
            if (a.neutralize) average else deviation.z,
        )

        var x = movedLuma + (source.x - l) * (1f + (scale.x / average - 1f) * a.colorIntensity)
        var y = movedLuma + (source.y - l) * (1f + (scale.y / average - 1f) * a.colorIntensity)
        var z = movedLuma + (source.z - l) * (1f + (scale.z / average - 1f) * a.colorIntensity)

        val fade = a.fade.coerceIn(0f, 1f)
        x += (r - x) * fade
        y += (g - y) * fade
        z += (b - z) * fade
        return clamped(x, y, z)
    }

    /**
     * Selects a colour by distance and moves it in HSL.
     *
     * The selection is a distance from a picked colour rather than a slice of the hue wheel, which
     * is what lets it take one blue out of a sky and leave the rest. Feathered rather than switched,
     * because a hard mask puts a visible outline around everything it replaces.
     */
    private fun replaceColor(r: Float, g: Float, b: Float, a: Adjustment.ReplaceColor): Vec3 {
        val dr = r - a.target.r
        val dg = g - a.target.g
        val db = b - a.target.b
        val distance = sqrt(dr * dr + dg * dg + db * db) / SQRT_THREE
        val width = max(a.fuzziness, EPSILON)
        var mask = (1f - distance / width).coerceIn(0f, 1f)
        // Localized Color Clusters tightens the falloff around the picked colour, which is what
        // stops a wide fuzziness leaking into a neighbouring hue.
        if (a.localized) mask *= mask
        if (mask <= 0f) return Vec3(r, g, b)

        val hsl = toHsl(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
        val moved = toRgb(
            fract(hsl.x + a.hue),
            (hsl.y * (1f + a.saturation)).coerceIn(0f, 1f),
            (hsl.z + a.lightness * (if (a.lightness > 0f) 1f - hsl.z else hsl.z)).coerceIn(0f, 1f),
        )
        return clamped(
            r + (moved.x - r) * mask,
            g + (moved.y - g) * mask,
            b + (moved.z - b) * mask,
        )
    }

    // ---- HSL ---------------------------------------------------------------------------------

    internal fun toHsl(r: Float, g: Float, b: Float): Vec3 {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val l = (mx + mn) * 0.5f
        val d = mx - mn
        if (d < EPSILON) return Vec3(0f, 0f, l)
        val s = if (l > 0.5f) d / (2f - mx - mn) else d / (mx + mn)
        val h = when (mx) {
            r -> (g - b) / d + if (g < b) 6f else 0f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        }
        return Vec3(h / 6f, s, l)
    }

    internal fun toRgb(h: Float, s: Float, l: Float): Vec3 {
        if (s < EPSILON) return Vec3(l, l, l)
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        return Vec3(hueChannel(p, q, h + 1f / 3f), hueChannel(p, q, h), hueChannel(p, q, h - 1f / 3f))
    }

    private fun hueChannel(p: Float, q: Float, tRaw: Float): Float {
        var t = tRaw
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }

    /** Photoshop's Color Lookup ships 64-slice cubes laid out eight across in a 512-pixel strip. */
    private const val SLICES = 64
    private const val SLICES_PER_ROW = 8

    private const val TABLE_SIZE = 256
    private const val EPSILON = 1e-5f
    private const val MIN_TONE = 0.05f
    private const val SQRT_THREE = 1.7320508f

    // What these mean lives in the model, because the shader computes the same corrections and the
    // two implementations have to agree to the last digit.
    private const val SHADOW_HEADROOM = Tone.SHADOW_HEADROOM
    private const val HIGHLIGHT_DEPTH = Tone.HIGHLIGHT_DEPTH
    private const val LOG_KNEE = Tone.LOG_KNEE
    private const val GUIDE_EPSILON = Tone.GUIDE_EPSILON
    private const val LOG_FLOOR_VALUE = Tone.LOG_FLOOR_VALUE
    private val LOG_FLOOR = -Tone.LOG_SPAN

    private const val LUMA_R = 0.2126f
    private const val LUMA_G = 0.7152f
    private const val LUMA_B = 0.0722f

    private val DEFAULT_BW = floatArrayOf(0.4f, 0.6f, 0.4f, 0.6f, 0.2f, 0.8f)
}

/** Convenience for the callers that hold a [Color] rather than three floats. */
fun Adjust.applyTo(colour: Color, adjustment: Adjustment): Color {
    val v = applyTo(Vec3(colour.r, colour.g, colour.b), adjustment)
    return Color(v.x, v.y, v.z, colour.a)
}
