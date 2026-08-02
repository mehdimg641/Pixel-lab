package ir.pixellab.engine.android

import ir.pixellab.core.codec.RasterImage
import ir.pixellab.core.imaging.Blur
import ir.pixellab.core.imaging.RadialBlur
import ir.pixellab.core.imaging.Raster
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.paint.PixelSelection
import ir.pixellab.core.render.toArgb

/**
 * Filters that change a layer's pixels.
 *
 * Deliberately destructive, and deliberately not effects. Photoshop draws the same line: Gaussian
 * Blur is under Filter and lives in the pixels, while a Drop Shadow's blur is under Layer Style and
 * is recomputed every composite. Making these effects instead would put a full-image convolution in
 * the render path of every frame, on a layer the user is dragging — and it would still be wrong,
 * because a blur applied to *part* of a layer has no meaning as a layer-wide style.
 *
 * Every filter here takes an optional selection and blends through its coverage, so the boundary of
 * a feathered selection produces a gradual transition rather than a visible seam. That blend is the
 * single most important thing in this file: it is what makes "blur the background" look like depth
 * of field instead of a cut-out pasted onto a smeared plate.
 */
object PixelFilters {

    /** Photoshop's Gaussian Blur. [radius] is its dialog's radius, in pixels. */
    fun gaussian(source: RasterImage, radius: Float, selection: PixelSelection? = null): RasterImage {
        if (radius <= 0f) return source
        // Photoshop's "radius" is a sigma; taking it as a kernel half-width instead makes every
        // number the user has learnt over years produce roughly a third of the blur they expect.
        val blurred = Blur.gaussian(source.toRaster().premultiplied(), radius).unpremultiplied()
        return blend(source, blurred.toImage(), selection)
    }

    /**
     * Radial blur, spin or zoom.
     *
     * @param centre in the layer's own pixels. Defaulted to the middle only because a caller may not
     *   have asked the user yet; the whole effect depends on it being under the subject.
     */
    fun radial(
        source: RasterImage,
        amount: Float,
        kind: RadialBlur.Kind,
        centre: Vec2 = Vec2(source.width / 2f, source.height / 2f),
        selection: PixelSelection? = null,
    ): RasterImage {
        if (amount <= 0f) return source
        val smeared = RadialBlur.apply(source.toRaster().premultiplied(), centre, amount, kind)
        return blend(source, smeared.unpremultiplied().toImage(), selection)
    }

    /**
     * Every blur here runs on premultiplied colour and comes back.
     *
     * Not a formality. A transparent pixel's colour channels hold whatever was last written there —
     * usually black — and averaging them in unweighted is what puts a dark halo around every blurred
     * cut-out. Weighting each colour by its own alpha first makes the average count only the pixels
     * that are actually there.
     */
    private fun Raster.premultiplied(): Raster {
        val out = copy()
        for (i in 0 until pixelCount) {
            val at = i * channels
            val alpha = out.data[at + 3]
            out.data[at] *= alpha
            out.data[at + 1] *= alpha
            out.data[at + 2] *= alpha
        }
        return out
    }

    private fun Raster.unpremultiplied(): Raster {
        val out = copy()
        for (i in 0 until pixelCount) {
            val at = i * channels
            val alpha = out.data[at + 3]
            if (alpha <= MIN_ALPHA) continue
            out.data[at] /= alpha
            out.data[at + 1] /= alpha
            out.data[at + 2] /= alpha
        }
        return out
    }

    /** Directional blur — Photoshop's Motion Blur. [angle] is degrees, 0 to the right. */
    fun motion(
        source: RasterImage,
        angle: Float,
        distance: Float,
        selection: PixelSelection? = null,
    ): RasterImage {
        if (distance <= 1f) return source
        val smeared = ir.pixellab.core.imaging.MotionBlur
            .apply(source.toRaster().premultiplied(), angle, distance)
        return blend(source, smeared.unpremultiplied().toImage(), selection)
    }

    /** Lens blur with a real aperture shape, which is what gives bokeh its recognisable discs. */
    fun lens(
        source: RasterImage,
        radius: Float,
        blades: Int = 0,
        rotation: Float = 0f,
        selection: PixelSelection? = null,
    ): RasterImage {
        if (radius < 1f) return source
        val blurred = ir.pixellab.core.imaging.LensBlur
            .apply(source.toRaster().premultiplied(), radius, blades, rotation)
        return blend(source, blurred.unpremultiplied().toImage(), selection)
    }

    /**
     * Blur that varies across the frame — tilt-shift and iris.
     *
     * The focal region is centred on the selection when there is one, for the same reason the
     * radial blurs are: a user who has drawn a marquee round a face has already said where the
     * sharp part is.
     */
    fun gradientBlur(
        source: RasterImage,
        shape: ir.pixellab.core.imaging.GradientBlur.Shape,
        radius: Float,
        focus: Float,
        transition: Float,
        angle: Float = 0f,
        centre: Vec2 = Vec2(source.width / 2f, source.height / 2f),
    ): RasterImage {
        if (radius <= 0f) return source
        return ir.pixellab.core.imaging.GradientBlur
            .apply(source.toRaster().premultiplied(), shape, centre, radius, focus, transition, angle)
            .unpremultiplied()
            .toImage()
    }

    /** Unsharp mask. [threshold] leaves flat areas alone so noise is not amplified with detail. */
    fun sharpen(
        source: RasterImage,
        amount: Float,
        radius: Float,
        threshold: Float = 0f,
        selection: PixelSelection? = null,
    ): RasterImage {
        if (amount <= 0f) return source
        // Straight colour, not premultiplied: sharpening is a per-pixel contrast operation and
        // weighting it by alpha would sharpen the matte's shape into the colours.
        val sharper = ir.pixellab.core.imaging.Sharpen
            .unsharpMask(source.toRaster(), amount, radius, threshold)
        return blend(source, sharper.toImage(), selection)
    }

    /** Darkens or lightens towards the corners, following the frame's own proportions. */
    fun vignette(source: RasterImage, amount: Float, midpoint: Float = 0.5f): RasterImage {
        if (amount == 0f) return source
        return ir.pixellab.core.imaging.Stylise
            .vignette(source.toRaster(), amount, midpoint)
            .toImage()
    }

    fun pixelate(source: RasterImage, blockSize: Int, selection: PixelSelection? = null): RasterImage {
        if (blockSize <= 1) return source
        val blocks = ir.pixellab.core.imaging.Stylise.pixelate(source.toRaster(), blockSize)
        return blend(source, blocks.toImage(), selection)
    }

    /**
     * Film grain.
     *
     * The seed comes from the caller so the same layer grained twice is identical — an effect that
     * changed on every application would make undo and redo produce different pictures.
     */
    fun grain(
        source: RasterImage,
        amount: Float,
        monochrome: Boolean = true,
        seed: Int = 0,
        selection: PixelSelection? = null,
    ): RasterImage {
        if (amount <= 0f) return source
        val grained = ir.pixellab.core.imaging.Stylise.noise(source.toRaster(), amount, monochrome, seed)
        return blend(source, grained.toImage(), selection)
    }

    /**
     * Fills with a colour.
     *
     * Partial coverage is honoured rather than thresholded: a feathered selection filled with a flat
     * colour has to fade into what was there, and a fill that rounded coverage to on-or-off would
     * put a hard edge exactly where the user asked for a soft one.
     *
     * @param preserveTransparency Photoshop's lock-transparency: the fill can only reach pixels the
     *   layer already has, which is how a painted shape is recoloured without spilling around it.
     */
    fun fill(
        source: RasterImage,
        color: Color,
        selection: PixelSelection? = null,
        preserveTransparency: Boolean = false,
    ): RasterImage {
        val fillArgb = color.toArgb()
        val out = IntArray(source.pixels.size)
        for (i in out.indices) {
            val existing = source.pixels[i]
            var coverage = coverageAt(selection, i, source.width)
            if (preserveTransparency) coverage = coverage * ((existing ushr 24) and 0xFF) / 255
            out[i] = mix(existing, fillArgb, coverage)
        }
        return RasterImage(source.width, source.height, out)
    }

    /** Clears to transparent — the delete key, and the other half of what "fill" means in practice. */
    fun clear(source: RasterImage, selection: PixelSelection? = null): RasterImage {
        val out = IntArray(source.pixels.size)
        for (i in out.indices) {
            val coverage = coverageAt(selection, i, source.width)
            val existing = source.pixels[i]
            val alpha = (((existing ushr 24) and 0xFF) * (255 - coverage) / 255) shl 24
            out[i] = alpha or (existing and 0x00FFFFFF)
        }
        return RasterImage(source.width, source.height, out)
    }

    /**
     * Puts a filtered image back through the selection.
     *
     * Interpolating rather than choosing: at coverage 128 the result is genuinely half way between
     * the two, which is what a feathered edge is for. Compositing the filtered version *over* the
     * original would give the same answer only where the filter left alpha alone, and a blur moves
     * alpha around by definition.
     */
    private fun blend(source: RasterImage, filtered: RasterImage, selection: PixelSelection?): RasterImage {
        if (selection == null || selection.isEmpty) return filtered
        val out = IntArray(source.pixels.size)
        for (i in out.indices) {
            out[i] = mix(source.pixels[i], filtered.pixels[i], coverageAt(selection, i, source.width))
        }
        return RasterImage(source.width, source.height, out)
    }

    private fun coverageAt(selection: PixelSelection?, index: Int, width: Int): Int {
        if (selection == null) return 255
        return selection[index % width, index / width]
    }

    private fun mix(from: Int, to: Int, coverage: Int): Int {
        if (coverage <= 0) return from
        if (coverage >= 255) return to
        var result = 0
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            result = result or (((a + (b - a) * coverage / 255) and 0xFF) shl shift)
        }
        return result
    }

    /** Below this the colour recovered by dividing is noise, and dividing by it produces infinities. */
    private const val MIN_ALPHA = 1f / 255f
}
