package ir.pixellab.core.render

import ir.pixellab.core.model.BlendMode
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.Ramp
import ir.pixellab.core.model.Style
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** An ARGB image, straight (non-premultiplied) — the layout the codecs and the 3D renderer share. */
class Raster(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height) { "pixel count does not match ${width}x$height" }
    }

    fun copy() = Raster(width, height, pixels.copyOf())

    companion object {
        fun empty(width: Int, height: Int) = Raster(width, height, IntArray(width * height))
    }
}

/**
 * Layer effects, on the CPU.
 *
 * The effect engine exists as GLSL and runs on a device's GPU, which is right for an editor showing
 * a live canvas. It also meant that nothing in this repository could render an effect: there is no
 * GPU on a build machine, so every test of the effect system checks a *plan* — that the right passes
 * are scheduled with the right parameters — and not one of them has ever looked at a pixel. A stroke
 * that came out on the wrong side of the outline, a shadow blurred along one axis only, a gradient
 * running backwards: none of those change a plan, and all of them ship.
 *
 * This is the other half of that pair, in the same spirit as [Blending] being written twice. It is
 * the oracle: slower than the shader and answerable on any machine, so the arithmetic can be tested
 * and the look can be seen without a phone in the loop. It also gives export a path that does not
 * depend on a GL context at all.
 *
 * Photoshop's stacking order, which is not the order the effects are listed in: the drop shadow and
 * the extrusion sit *behind* the layer, the overlay paints *into* it, and the stroke goes over the
 * top. Get that order wrong and every individual effect can be correct while the picture is not.
 */
object EffectRaster {

    fun apply(source: Raster, style: Style): Raster {
        val effects = style.activeEffects
        if (effects.isEmpty()) return source.copy()

        // The layer itself, with anything that paints into it already applied.
        var layer = source.copy()
        for (effect in effects.filterIsInstance<Effect.Overlay>()) {
            layer = overlay(layer, effect)
        }

        var out = Raster.empty(source.width, source.height)

        // Behind, furthest first: the shadow falls on everything, the extrusion sits on the shadow.
        for (effect in effects.filterIsInstance<Effect.DropShadow>()) {
            out = over(out, dropShadow(source, effect), effect.blendMode, effect.opacity)
        }
        for (effect in effects.filterIsInstance<Effect.Extrude>()) {
            out = over(out, extrude(source, effect), effect.blendMode, effect.opacity)
        }

        out = over(out, layer, BlendMode.NORMAL, 1f)

        // And over the top.
        for (effect in effects.filterIsInstance<Effect.Stroke>()) {
            out = over(out, stroke(source, effect), effect.blendMode, effect.opacity)
        }
        return out
    }

    // ---- the effects ---------------------------------------------------------------------------

    /**
     * Offset copies of the silhouette, ramped from the near colour to the far one.
     *
     * Drawn back to front so the nearest step lands last and covers the ones behind it, which is
     * what makes the stack read as a solid block rather than as a fan of overlapping cut-outs.
     */
    private fun extrude(source: Raster, effect: Effect.Extrude): Raster {
        val out = Raster.empty(source.width, source.height)
        for (step in effect.steps downTo 1) {
            val t = effect.falloff.evaluate(step.toFloat() / effect.steps)
            val colour = mix(fillColour(effect.nearFill), fillColour(effect.farFill), t)
            val alpha = 1f + (effect.farOpacity - 1f) * t
            stamp(
                out = out,
                source = source,
                dx = (effect.stepOffset.x * step).roundToInt(),
                dy = (effect.stepOffset.y * step).roundToInt(),
                colour = colour,
                strength = alpha,
            )
        }
        return out
    }

    /**
     * The silhouette, spread, blurred, offset and tinted.
     *
     * Spread before blur, because Photoshop's "spread" grows the shape and the blur then softens
     * what growing produced. Blurring first and growing after gives a hard-edged halo instead.
     */
    private fun dropShadow(source: Raster, effect: Effect.DropShadow): Raster {
        var mask = alphaOf(source)
        if (effect.spread > 0f) mask = spread(mask, source.width, source.height, effect.spread)
        if (effect.blur > 0f) mask = blur(mask, source.width, source.height, effect.blur)

        // Photoshop measures the angle as the direction the light comes *from*, so the shadow falls
        // the opposite way. Taking it as the direction of travel puts every shadow on the wrong side.
        val radians = (effect.angle + STRAIGHT) * DEG_TO_RAD
        val dx = (cos(radians) * effect.distance).roundToInt()
        val dy = (-sin(radians) * effect.distance).roundToInt()

        val out = Raster.empty(source.width, source.height)
        for (y in 0 until source.height) {
            for (x in 0 until source.width) {
                val from = sampleAt(mask, source.width, source.height, x - dx, y - dy)
                if (from <= 0f) continue
                var a = from
                if (effect.knockOut) {
                    // The layer's own pixels punch through, so a shadow never shows inside the
                    // shape casting it — which is what stops a soft shadow greying out the letter.
                    a *= 1f - alphaAt(source, x, y)
                }
                if (a <= 0f) continue
                out.pixels[y * out.width + x] = pack(effect.color, a)
            }
        }
        return out
    }

    /**
     * A band following the outline.
     *
     * Measured with a distance transform rather than by dilating the mask, because a dilation with
     * a square kernel puts corners on a round letter and a circular one costs a pass per pixel of
     * width. The distance also carries the antialiasing for free: a pixel a fraction beyond the
     * edge of the band is a fraction covered.
     */
    private fun stroke(source: Raster, effect: Effect.Stroke): Raster {
        val out = Raster.empty(source.width, source.height)
        if (effect.width <= 0f) return out
        val distance = distanceOutside(source)
        val colour = fillColour(effect.fill)

        for (i in out.pixels.indices) {
            val d = distance[i]
            val inside = (source.pixels[i] ushr 24) / 255f
            val coverage = when (effect.position) {
                ir.pixellab.core.model.StrokePosition.OUTSIDE ->
                    (effect.width - d).coerceIn(0f, 1f) * (1f - inside)
                ir.pixellab.core.model.StrokePosition.CENTER ->
                    (effect.width / 2f - d).coerceIn(0f, 1f)
                ir.pixellab.core.model.StrokePosition.INSIDE -> 0f
            }
            if (coverage <= 0f) continue
            out.pixels[i] = pack(colour, coverage)
        }
        return out
    }

    /** A fill painted across the layer, kept inside the layer's own alpha. */
    private fun overlay(layer: Raster, effect: Effect.Overlay): Raster {
        val out = layer.copy()
        val gradient = effect.fill as? Fill.Gradient
        val flat = if (gradient == null) fillColour(effect.fill) else null
        val stops = gradient?.stops?.sortedBy { it.position }

        for (y in 0 until layer.height) {
            for (x in 0 until layer.width) {
                val i = y * layer.width + x
                val a = (layer.pixels[i] ushr 24) and 0xFF
                if (a == 0) continue
                val paint = if (gradient != null && stops != null) {
                    Ramp.colorAt(
                        stops,
                        Ramp.parameterAt(
                            gradient,
                            x.toFloat() / layer.width,
                            y.toFloat() / layer.height,
                        ),
                    )
                } else {
                    flat!!
                }
                val blended = Blending.rgb(
                    effect.blendMode,
                    Triple(
                        ((layer.pixels[i] shr 16) and 0xFF) / 255f,
                        ((layer.pixels[i] shr 8) and 0xFF) / 255f,
                        (layer.pixels[i] and 0xFF) / 255f,
                    ),
                    Triple(paint.r, paint.g, paint.b),
                )
                val k = effect.opacity.coerceIn(0f, 1f)
                out.pixels[i] = (a shl 24) or
                    (byte(lerp(((layer.pixels[i] shr 16) and 0xFF) / 255f, blended.first, k)) shl 16) or
                    (byte(lerp(((layer.pixels[i] shr 8) and 0xFF) / 255f, blended.second, k)) shl 8) or
                    byte(lerp((layer.pixels[i] and 0xFF) / 255f, blended.third, k))
            }
        }
        return out
    }

    // ---- the pieces the effects are built from ---------------------------------------------------

    /**
     * Distance from each transparent pixel to the nearest opaque one, in pixels.
     *
     * Two chamfer passes — one down the image, one back up — which is linear in the pixel count and
     * accurate to a few per cent of true Euclidean distance. Exact distance needs a great deal more
     * work for an error nobody can see in a stroke a handful of pixels wide.
     */
    private fun distanceOutside(source: Raster): FloatArray {
        val w = source.width
        val h = source.height
        val d = FloatArray(w * h) { if ((source.pixels[it] ushr 24) > HALF_BYTE) 0f else FAR }

        fun relax(at: Int, from: Int, cost: Float) {
            val candidate = d[from] + cost
            if (candidate < d[at]) d[at] = candidate
        }

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (x > 0) relax(i, i - 1, ORTHOGONAL)
                if (y > 0) relax(i, i - w, ORTHOGONAL)
                if (x > 0 && y > 0) relax(i, i - w - 1, DIAGONAL)
                if (x < w - 1 && y > 0) relax(i, i - w + 1, DIAGONAL)
            }
        }
        for (y in h - 1 downTo 0) {
            for (x in w - 1 downTo 0) {
                val i = y * w + x
                if (x < w - 1) relax(i, i + 1, ORTHOGONAL)
                if (y < h - 1) relax(i, i + w, ORTHOGONAL)
                if (x < w - 1 && y < h - 1) relax(i, i + w + 1, DIAGONAL)
                if (x > 0 && y < h - 1) relax(i, i + w - 1, DIAGONAL)
            }
        }
        return d
    }

    private fun alphaOf(source: Raster) =
        FloatArray(source.pixels.size) { ((source.pixels[it] ushr 24) and 0xFF) / 255f }

    /** Grows the mask by a radius, using the same distance field the stroke measures with. */
    private fun spread(mask: FloatArray, width: Int, height: Int, radius: Float): FloatArray {
        val packed = Raster(width, height, IntArray(mask.size) { (byte(mask[it]) shl 24) })
        val distance = distanceOutside(packed)
        return FloatArray(mask.size) { maxOf(mask[it], (radius - distance[it] + 1f).coerceIn(0f, 1f)) }
    }

    /**
     * Three box passes, which is a Gaussian to within a per cent and costs a fraction of one.
     *
     * The radius is split across the passes so the total spread matches the requested blur; running
     * three passes at the full radius blurs three times as far as asked, and a shadow that reaches
     * further than its setting says is the kind of thing that gets tuned around rather than fixed.
     */
    private fun blur(mask: FloatArray, width: Int, height: Int, radius: Float): FloatArray {
        val r = (radius / BOX_PASSES).roundToInt().coerceAtLeast(1)
        var current = mask
        repeat(BOX_PASSES) {
            current = boxPass(current, width, height, r, horizontal = true)
            current = boxPass(current, width, height, r, horizontal = false)
        }
        return current
    }

    private fun boxPass(
        source: FloatArray,
        width: Int,
        height: Int,
        radius: Int,
        horizontal: Boolean,
    ): FloatArray {
        val out = FloatArray(source.size)
        val span = radius * 2 + 1
        val outer = if (horizontal) height else width
        val inner = if (horizontal) width else height

        for (o in 0 until outer) {
            fun index(i: Int) = if (horizontal) o * width + i else i * width + o
            // A running sum, so the cost is one add and one subtract per pixel rather than one per
            // tap — the difference between a blur that is usable at export size and one that is not.
            var sum = 0f
            for (i in -radius..radius) sum += source[index(i.coerceIn(0, inner - 1))]
            for (i in 0 until inner) {
                out[index(i)] = sum / span
                val leaving = source[index((i - radius).coerceIn(0, inner - 1))]
                val arriving = source[index((i + radius + 1).coerceIn(0, inner - 1))]
                sum += arriving - leaving
            }
        }
        return out
    }

    /** Draws the source's silhouette in one colour, offset, over whatever is already there. */
    private fun stamp(out: Raster, source: Raster, dx: Int, dy: Int, colour: Color, strength: Float) {
        for (y in 0 until out.height) {
            val sy = y - dy
            if (sy < 0 || sy >= source.height) continue
            for (x in 0 until out.width) {
                val sx = x - dx
                if (sx < 0 || sx >= source.width) continue
                val a = ((source.pixels[sy * source.width + sx] ushr 24) and 0xFF) / 255f * strength
                if (a <= 0f) continue
                out.pixels[y * out.width + x] = overPixel(out.pixels[y * out.width + x], pack(colour, a))
            }
        }
    }

    private fun over(under: Raster, above: Raster, mode: BlendMode, opacity: Float): Raster {
        val out = under.copy()
        val k = opacity.coerceIn(0f, 1f)
        for (i in out.pixels.indices) {
            val src = above.pixels[i]
            val a = ((src ushr 24) and 0xFF) / 255f * k
            if (a <= 0f) continue
            val dst = out.pixels[i]
            val blended = if (mode == BlendMode.NORMAL || (dst ushr 24) == 0) {
                src
            } else {
                val rgb = Blending.rgb(
                    mode,
                    Triple(
                        ((dst shr 16) and 0xFF) / 255f,
                        ((dst shr 8) and 0xFF) / 255f,
                        (dst and 0xFF) / 255f,
                    ),
                    Triple(
                        ((src shr 16) and 0xFF) / 255f,
                        ((src shr 8) and 0xFF) / 255f,
                        (src and 0xFF) / 255f,
                    ),
                )
                ((src ushr 24) shl 24) or
                    (byte(rgb.first) shl 16) or (byte(rgb.second) shl 8) or byte(rgb.third)
            }
            out.pixels[i] = overPixel(dst, withAlpha(blended, a))
        }
        return out
    }

    /** Straight-alpha source-over. */
    private fun overPixel(under: Int, above: Int): Int {
        val sa = ((above ushr 24) and 0xFF) / 255f
        if (sa <= 0f) return under
        val da = ((under ushr 24) and 0xFF) / 255f
        val outA = sa + da * (1f - sa)
        if (outA <= 0f) return 0
        fun channel(shift: Int): Int {
            val s = ((above shr shift) and 0xFF) / 255f
            val d = ((under shr shift) and 0xFF) / 255f
            return byte((s * sa + d * da * (1f - sa)) / outA)
        }
        return (byte(outA) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    private fun sampleAt(mask: FloatArray, width: Int, height: Int, x: Int, y: Int): Float =
        if (x < 0 || y < 0 || x >= width || y >= height) 0f else mask[y * width + x]

    private fun alphaAt(source: Raster, x: Int, y: Int): Float =
        ((source.pixels[y * source.width + x] ushr 24) and 0xFF) / 255f

    private fun fillColour(fill: Fill): Color = when (fill) {
        is Fill.Solid -> fill.color
        // The midpoint of the ramp, which is the honest single answer where one colour is wanted
        // from a gradient. Callers that can use the whole ramp sample it themselves.
        is Fill.Gradient -> Ramp.colorAt(fill.stops.sortedBy { it.position }, HALF)
        else -> Color.BLACK
    }

    private fun pack(colour: Color, alpha: Float): Int =
        (byte(alpha * colour.a.coerceIn(0f, 1f).let { if (it == 0f) 1f else it }) shl 24) or
            (byte(colour.r) shl 16) or (byte(colour.g) shl 8) or byte(colour.b)

    private fun withAlpha(pixel: Int, alpha: Float) =
        (byte(alpha) shl 24) or (pixel and 0xFFFFFF)

    private fun mix(a: Color, b: Color, t: Float) = Color(
        a.r + (b.r - a.r) * t,
        a.g + (b.g - a.g) * t,
        a.b + (b.b - a.b) * t,
        a.a + (b.a - a.a) * t,
    )

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun byte(v: Float) = (v.coerceIn(0f, 1f) * 255f + HALF).toInt()

    private const val HALF = 0.5f
    private const val HALF_BYTE = 127
    private const val STRAIGHT = 180f
    private const val DEG_TO_RAD = 0.017453292f
    private const val FAR = 1e9f

    /** Chamfer weights: 1 across an edge and √2 across a corner, the classic 3-4 pair normalised. */
    private const val ORTHOGONAL = 1f
    private const val DIAGONAL = 1.41421356f

    /** Three box passes approximate a Gaussian; the fourth is not worth its cost. */
    private const val BOX_PASSES = 3
}
