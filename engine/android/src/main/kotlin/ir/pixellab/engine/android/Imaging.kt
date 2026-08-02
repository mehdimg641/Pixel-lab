package ir.pixellab.engine.android

import ir.pixellab.core.codec.RasterImage
import ir.pixellab.core.imaging.Blur
import ir.pixellab.core.imaging.Decontaminate
import ir.pixellab.core.imaging.FrequencySeparation
import ir.pixellab.core.imaging.MovingLeastSquares
import ir.pixellab.core.imaging.PatchMatch
import ir.pixellab.core.imaging.Raster
import ir.pixellab.core.imaging.Trimap
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.paint.PixelSelection
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The bridge between decoded pixels and the imaging algorithms.
 *
 * `core:imaging` works in straight float components because its algorithms chain — a guided filter
 * feeding a distance transform feeding a normal map — and quantising between stages is exactly the
 * banding the whole colour pipeline was designed to avoid. Everything else in the app carries eight-
 * bit ARGB. This is the one place the two meet.
 */
fun RasterImage.toRaster(): Raster {
    val raster = Raster(width, height, CHANNELS)
    for (i in pixels.indices) {
        val p = pixels[i]
        val at = i * CHANNELS
        raster.data[at] = ((p shr 16) and 0xFF) / 255f
        raster.data[at + 1] = ((p shr 8) and 0xFF) / 255f
        raster.data[at + 2] = (p and 0xFF) / 255f
        raster.data[at + 3] = ((p ushr 24) and 0xFF) / 255f
    }
    return raster
}

fun Raster.toImage(): RasterImage {
    require(channels == CHANNELS) { "expected four channels, got $channels" }
    val pixels = IntArray(width * height)
    for (i in pixels.indices) {
        val at = i * CHANNELS
        val r = (data[at].coerceIn(0f, 1f) * 255f).roundToInt()
        val g = (data[at + 1].coerceIn(0f, 1f) * 255f).roundToInt()
        val b = (data[at + 2].coerceIn(0f, 1f) * 255f).roundToInt()
        val a = (data[at + 3].coerceIn(0f, 1f) * 255f).roundToInt()
        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
    }
    return RasterImage(width, height, pixels)
}

/** A selection as the single-channel mask the algorithms expect. */
fun PixelSelection.toRaster(): Raster {
    val raster = Raster(width, height, 1)
    for (i in coverage.indices) raster.data[i] = (coverage[i].toInt() and 0xFF) / 255f
    return raster
}

/** A single-channel result as a greyscale image, ready to become a layer mask asset. */
fun Raster.toMaskImage(): RasterImage {
    require(channels == 1) { "a mask has one channel, got $channels" }
    val pixels = IntArray(width * height) { i ->
        val v = (data[i].coerceIn(0f, 1f) * 255f).roundToInt()
        (v shl 24) or (v shl 16) or (v shl 8) or v
    }
    return RasterImage(width, height, pixels)
}

private const val CHANNELS = 4

/**
 * Cutting a subject out.
 *
 * Deliberately not a model. What Photoshop calls Select Subject is a segmentation network, and the
 * refinement that follows it — the part that actually decides whether hair looks cut out or torn
 * out — is classical: a trimap, an alpha solve over the unknown band, and colour decontamination.
 * That refinement is where nearly all of the visible quality lives, so it runs here, offline, and
 * takes its starting mask from a selection the user made rather than from a guess.
 */
object Cutout {

    /** What a refinement produced. */
    data class Result(val alpha: Raster, val decontaminated: RasterImage)

    /**
     * Refines a rough selection into a real matte.
     *
     * @param band how far either side of the edge is treated as unknown. Hair needs a much wider
     *   band than a shoulder, and one global width either misses strands or spends its time solving
     *   over flat regions — which is why the refine brush exists.
     * @param refineStrokes pixels the user painted as "look harder here", widening the band locally
     */
    fun refine(
        image: RasterImage,
        selection: PixelSelection,
        band: Float = DEFAULT_BAND,
        refineStrokes: PixelSelection? = null,
        radius: Int = DEFAULT_RADIUS,
    ): Result {
        val source = image.toRaster()
        val mask = selection.toRaster()

        var trimap = Trimap.fromMask(mask, band)
        refineStrokes?.let { trimap = Trimap.widen(trimap, it.toRaster()) }

        // The full colour image as the guide, not its luminance: hair separates from a background
        // of similar brightness by chrominance, and a luminance guide cannot see that at all.
        val alpha = Trimap.solveAlpha(trimap, source, radius)

        // The colour under a semi-transparent edge is a mix of subject and background. Leaving it
        // is what gives every naive cut-out its halo — a green fringe on anyone photographed
        // against foliage.
        val background = estimateBackground(source, alpha)
        val foreground = Decontaminate.foreground(source, alpha, background)

        val out = Raster(source.width, source.height, 4)
        for (i in 0 until source.pixelCount) {
            val at = i * 4
            out.data[at] = foreground.data[at]
            out.data[at + 1] = foreground.data[at + 1]
            out.data[at + 2] = foreground.data[at + 2]
            out.data[at + 3] = alpha.data[i] * source.data[at + 3]
        }
        return Result(alpha, out.toImage())
    }

    /**
     * A blurred copy of the image stands in for the background behind the edge.
     *
     * The true background is not observable — it is behind the subject. A heavy blur of the whole
     * image is a good enough estimate at the only place it is used, which is a band a few pixels
     * wide, and it costs one filter rather than an inpaint of the entire subject.
     */
    private fun estimateBackground(source: Raster, alpha: Raster): Raster {
        val blurred = Blur.gaussian(source, BACKGROUND_BLUR)
        val out = source.copy()
        for (i in 0 until source.pixelCount) {
            if (alpha.data[i] < 0.99f) continue
            // Inside the subject there is no background to sample, so the blurred neighbourhood is
            // used everywhere rather than the subject's own colour bleeding outwards.
            val at = i * source.channels
            for (c in 0 until source.channels) out.data[at + c] = blurred.data[at + c]
        }
        return out
    }

    /** How much of the image is still unresolved, so a slow solve can be predicted rather than felt. */
    fun unknownFraction(selection: PixelSelection, band: Float = DEFAULT_BAND): Float =
        Trimap.unknownFraction(Trimap.fromMask(selection.toRaster(), band))

    /** Wide enough for a shoulder, narrow enough that a portrait solves in well under a second. */
    const val DEFAULT_BAND = 6f

    private const val DEFAULT_RADIUS = 8
    private const val BACKGROUND_BLUR = 12f
}

/**
 * Retouching.
 *
 * Frequency separation rather than a blur, because a blur removes the pores along with the blemish
 * and is what makes retouched skin look like plastic. Splitting texture from tone lets the tone be
 * smoothed and the texture kept, which is what a retoucher actually does by hand.
 */
object Retouch {

    /**
     * Smooths skin while keeping its texture.
     *
     * @param amount 0 leaves the image alone, 1 fully flattens the low frequencies
     * @param radius the boundary between "texture" and "tone", in pixels. Roughly the size of the
     *   features to keep — a pore is one or two pixels at portrait resolution, a blemish is ten.
     */
    fun smoothSkin(image: RasterImage, amount: Float, radius: Float = DEFAULT_RADIUS): RasterImage {
        if (amount <= 0f) return image
        val source = image.toRaster()
        val bands = FrequencySeparation.split(source, radius)

        val flattened = Blur.gaussian(bands.low, radius * TONE_SPREAD)
        val low = ir.pixellab.core.imaging.RasterMath.lerp(bands.low, flattened, amount.coerceIn(0f, 1f))

        val out = ir.pixellab.core.imaging.RasterMath.add(low, bands.high)
        // Alpha is not a frequency. Separating and recombining it would leave a halo wherever the
        // layer is partly transparent.
        for (i in 0 until source.pixelCount) out.data[i * 4 + 3] = source.data[i * 4 + 3]
        return ir.pixellab.core.imaging.RasterMath.clamp(out).toImage()
    }

    /**
     * Fills a painted region from elsewhere in the image.
     *
     * PatchMatch rather than a clone stamp: a clone needs the user to choose a source that matches
     * in texture *and* lighting, which on a face almost never exists. The search finds it.
     */
    fun heal(image: RasterImage, hole: PixelSelection, passes: Int = DEFAULT_PASSES): RasterImage {
        if (hole.isEmpty) return image
        val source = image.toRaster()
        val filled = PatchMatch().inpaint(source, hole.toRaster(), passes)
        for (i in 0 until source.pixelCount) filled.data[i * 4 + 3] = source.data[i * 4 + 3]
        return filled.toImage()
    }

    /**
     * Sharpens by adding back what a blur removed.
     *
     * Unsharp masking, which is the only kind of sharpening that has a meaningful radius — a
     * convolution kernel sharpens at exactly one scale and cannot be told to leave fine noise alone.
     */
    fun sharpen(image: RasterImage, amount: Float, radius: Float = 1.5f): RasterImage {
        if (amount <= 0f) return image
        val source = image.toRaster()
        val blurred = Blur.gaussian(source, radius)
        val out = source.copy()
        for (i in 0 until source.pixelCount) {
            val at = i * 4
            for (c in 0 until 3) {
                val detail = source.data[at + c] - blurred.data[at + c]
                out.data[at + c] = (source.data[at + c] + detail * amount).coerceIn(0f, 1f)
            }
        }
        return out.toImage()
    }

    private const val DEFAULT_RADIUS = 8f

    /** How far past the split the tone is flattened; below this the smoothing is barely visible. */
    private const val TONE_SPREAD = 2f

    private const val DEFAULT_PASSES = 4
}

/**
 * Liquify.
 *
 * Moving least squares over control points, rigid by default. Rigid allows rotation but no
 * stretching, which is the only mode that keeps a face recognisable — affine lets a cheek shear, and
 * a sheared cheek is the giveaway in every over-retouched portrait.
 */
class Liquify(private val image: RasterImage) {

    private val points = ArrayList<MovingLeastSquares.ControlPoint>()

    /** How far each stroke's influence reaches, in pixels. */
    var brushSize: Float = DEFAULT_BRUSH

    var mode: MovingLeastSquares.Mode = MovingLeastSquares.Mode.RIGID

    val isEmpty: Boolean get() = points.isEmpty()

    /**
     * Pushes pixels from [from] towards [to].
     *
     * Anchors are added around the stroke as well as at it. Without them the deformation reaches to
     * the edge of the picture and the whole image slides — the single most common complaint about a
     * naive warp tool.
     */
    fun push(from: Vec2, to: Vec2) {
        points += MovingLeastSquares.ControlPoint(from.x, from.y, to.x, to.y)
        anchorAround(from)
    }

    /** Expands or contracts around a point, which is bloat and pucker. */
    fun scale(centre: Vec2, amount: Float) {
        val reach = brushSize
        for (i in 0 until RADIAL_POINTS) {
            val angle = i * 2.0 * Math.PI / RADIAL_POINTS
            val dx = (kotlin.math.cos(angle) * reach).toFloat()
            val dy = (kotlin.math.sin(angle) * reach).toFloat()
            points += MovingLeastSquares.ControlPoint(
                centre.x + dx, centre.y + dy,
                centre.x + dx * (1f + amount), centre.y + dy * (1f + amount),
            )
        }
        anchorAround(centre)
    }

    /** Rotates around a point. */
    fun twirl(centre: Vec2, degrees: Float) {
        val reach = brushSize
        val radians = Math.toRadians(degrees.toDouble())
        for (i in 0 until RADIAL_POINTS) {
            val angle = i * 2.0 * Math.PI / RADIAL_POINTS
            val dx = kotlin.math.cos(angle) * reach
            val dy = kotlin.math.sin(angle) * reach
            val rx = dx * kotlin.math.cos(radians) - dy * kotlin.math.sin(radians)
            val ry = dx * kotlin.math.sin(radians) + dy * kotlin.math.cos(radians)
            points += MovingLeastSquares.ControlPoint(
                centre.x + dx.toFloat(), centre.y + dy.toFloat(),
                centre.x + rx.toFloat(), centre.y + ry.toFloat(),
            )
        }
        anchorAround(centre)
    }

    fun reset() = points.clear()

    fun apply(): RasterImage {
        if (points.isEmpty()) return image
        return MovingLeastSquares.deform(image.toRaster(), points.toList(), mode).toImage()
    }

    /**
     * Pins the image a little way outside the brush.
     *
     * The falloff of a least-squares solve never quite reaches zero, so without a ring of fixed
     * points a nudge to a chin also moves the shoulder.
     */
    private fun anchorAround(centre: Vec2) {
        val reach = brushSize * ANCHOR_REACH
        for (i in 0 until ANCHOR_POINTS) {
            val angle = i * 2.0 * Math.PI / ANCHOR_POINTS
            val x = centre.x + (kotlin.math.cos(angle) * reach).toFloat()
            val y = centre.y + (kotlin.math.sin(angle) * reach).toFloat()
            if (x < 0f || y < 0f || x > image.width || y > image.height) continue
            if (points.any { hypot(it.fromX - x, it.fromY - y) < ANCHOR_MERGE }) continue
            points += MovingLeastSquares.ControlPoint(x, y, x, y)
        }
    }

    private companion object {
        const val DEFAULT_BRUSH = 80f
        const val RADIAL_POINTS = 8
        const val ANCHOR_POINTS = 12

        /** Far enough out that the anchors do not fight the stroke they surround. */
        const val ANCHOR_REACH = 2.5f

        /** Two anchors closer than this add nothing and make the solve slower. */
        const val ANCHOR_MERGE = 6f
    }
}
