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

    /**
     * @param patterns supplies the pixels for a [Fill.Pattern]. Patterns are referenced by asset id
     *   rather than carried inline — a texture is shared between layers and would otherwise be
     *   copied into every one of them — so the compositor cannot resolve one on its own. Leaving it
     *   out means a pattern fill draws nothing, which is the honest outcome for a texture that is
     *   not there and better than substituting a colour the document never asked for.
     */
    fun apply(
        source: Raster,
        style: Style,
        patterns: (ir.pixellab.core.model.AssetId) -> Raster? = { null },
    ): Raster {
        val effects = style.activeEffects
        if (effects.isEmpty()) return source.copy()

        // The layer itself, with everything that paints *into* it already applied. Order matters
        // among these too: the overlay lays the colour down, the inner shadow darkens its edges,
        // and the bevel lights the result — a bevel computed before the colour exists would shade
        // a surface nobody sees.
        var layer = source.copy()
        for (effect in effects.filterIsInstance<Effect.Overlay>()) {
            layer = overlay(layer, effect, patterns)
        }
        for (effect in effects.filterIsInstance<Effect.InnerShadow>()) {
            layer = innerShadow(layer, source, effect)
        }
        for (effect in effects.filterIsInstance<Effect.Bevel>()) {
            layer = bevel(layer, source, effect)
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

    /**
     * Stacks one styled layer over another.
     *
     * A single layer cannot carry this family of style. The frame's stroke has to sit *outside* the
     * letter and the face's gradient *inside* it, and one stroke cannot be on two sides at once —
     * which is exactly why the Photoshop recipes for these titles all begin by duplicating the text
     * layer. Exposed so a caller can build that stack without reimplementing source-over.
     */
    fun overComposite(under: Raster, above: Raster): Raster = over(under, above, BlendMode.NORMAL, 1f)

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
                dx = effect.stepOffset.x * step,
                dy = effect.stepOffset.y * step,
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

        // Both fields, because a stroke can sit on either side of the outline and each side is a
        // different measurement. An inside stroke used to draw nothing at all here — the position
        // was in the model, accepted by the panel, and silently produced an empty layer.
        val outside = distanceOutside(source)
        val insideDistance = distanceInside(source)
        val gradient = effect.fill as? Fill.Gradient
        val stops = gradient?.stops?.sortedBy { it.position }
        val flat = if (gradient == null) fillColour(effect.fill) else null

        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                val i = y * out.width + x
                val alpha = ((source.pixels[i] ushr 24) and 0xFF) / 255f
                val coverage = when (effect.position) {
                    ir.pixellab.core.model.StrokePosition.OUTSIDE ->
                        (effect.width - outside[i]).coerceIn(0f, 1f) * (1f - alpha)
                    ir.pixellab.core.model.StrokePosition.CENTER ->
                        (effect.width / 2f - minOf(outside[i], insideDistance[i]))
                            .coerceIn(0f, 1f)
                    ir.pixellab.core.model.StrokePosition.INSIDE ->
                        (effect.width - insideDistance[i]).coerceIn(0f, 1f) * alpha
                }
                if (coverage <= 0f) continue
                val colour = if (gradient != null && stops != null) {
                    Ramp.colorAt(
                        stops,
                        Ramp.parameterAt(
                            gradient,
                            x.toFloat() / out.width,
                            y.toFloat() / out.height,
                        ),
                    )
                } else {
                    flat!!
                }
                out.pixels[i] = pack(colour, coverage)
            }
        }
        return out
    }

    /** A fill painted across the layer, kept inside the layer's own alpha. */
    private fun overlay(
        layer: Raster,
        effect: Effect.Overlay,
        patterns: (ir.pixellab.core.model.AssetId) -> Raster?,
    ): Raster {
        val out = layer.copy()
        val gradient = effect.fill as? Fill.Gradient
        val pattern = (effect.fill as? Fill.Pattern)?.let { fill -> patterns(fill.asset)?.let { fill to it } }
        val flat = if (gradient == null && pattern == null) fillColour(effect.fill) else null
        val stops = gradient?.stops?.sortedBy { it.position }

        for (y in 0 until layer.height) {
            for (x in 0 until layer.width) {
                val i = y * layer.width + x
                val a = (layer.pixels[i] ushr 24) and 0xFF
                if (a == 0) continue
                val paint = when {
                    gradient != null && stops != null -> Ramp.colorAt(
                        stops,
                        Ramp.parameterAt(
                            gradient,
                            x.toFloat() / layer.width,
                            y.toFloat() / layer.height,
                        ),
                    )
                    pattern != null -> tileAt(pattern.second, pattern.first, x, y)
                    else -> flat!!
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

    /**
     * Bevel and emboss — the effect that makes a flat shape read as a solid one.
     *
     * Built the way Photoshop builds it, which is a *height field and a light* rather than a pair of
     * offset copies. Every naive implementation draws the shape once lightened up and left, once
     * darkened down and right, and the result gives itself away instantly: the highlight is the
     * letter's own silhouette rather than its edge, so a round bowl gets a crescent where it should
     * get a rim that follows the curve all the way round.
     *
     * Three steps. The distance from each pixel to the outside of the shape, clamped to the bevel's
     * size, gives a ramp from edge to interior. The profile curve shapes that ramp — the whole
     * difference between a rounded shoulder and a chiselled one lives here, and nothing else. Then
     * the surface normal is the gradient of that height field, and the shading is one dot product
     * against a light placed by the effect's own angle and altitude.
     *
     * The two halves go on with different blend modes because they are different things: the
     * highlight screens light onto the surface and the shadow multiplies it away, so a black face
     * still takes a highlight and a white one still takes a shadow. Painting both with normal alpha
     * is the other classic mistake, and it turns every bevel grey.
     */
    private fun bevel(layer: Raster, source: Raster, effect: Effect.Bevel): Raster {
        val w = layer.width
        val h = layer.height
        val size = effect.size.coerceAtLeast(1f)
        val inside = distanceInside(source)

        var height = FloatArray(inside.size) {
            effect.profile.evaluate((inside[it] / size).coerceIn(0f, 1f))
        }
        // Smoothed before the gradient is taken, and the amount is what the technique *is*.
        //
        // One pixel is the floor and is not optional: a chamfer distance field advances in steps of
        // 1 and √2, so its surface is faceted at the scale of a pixel. That is invisible in the
        // field itself and glaring in its derivative — the normal flips between a handful of
        // orientations across a shoulder and the bevel comes out mottled, reading as noise rather
        // than as a surface.
        //
        // Above that floor the smoothing is the difference between the techniques, and it is the
        // only difference. A chisel keeps the ramp linear so the shoulder stays flat and its corners
        // stay mitred, which is the faceted, cut look; smoothing the same ramp rounds the shoulder
        // and rounds the corners with it. Photoshop's soften then rides on top of whichever.
        val technique = when (effect.technique) {
            ir.pixellab.core.model.BevelTechnique.CHISEL_HARD -> 0f
            ir.pixellab.core.model.BevelTechnique.CHISEL_SOFT -> size * CHISEL_SOFTNESS
            ir.pixellab.core.model.BevelTechnique.SMOOTH -> size * SMOOTH_SOFTNESS
        }
        height = blur(height, w, h, (effect.soften + technique + 1f).coerceAtLeast(1f))

        // A light on the unit sphere: azimuth round the plane, altitude up out of it.
        val azimuth = effect.angle * DEG_TO_RAD
        val altitude = effect.altitude * DEG_TO_RAD
        val lx = cos(altitude) * cos(azimuth)
        val ly = -cos(altitude) * sin(azimuth)
        val lz = sin(altitude)

        // Photoshop's depth is a percentage and runs well past 100 on purpose; it scales how far the
        // height climbs across the bevel, which is what turns a soft swell into an inflated dome.
        val relief = (effect.depth / HUNDRED) * size * DEPTH_GAIN
        val out = layer.copy()

        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val alpha = (source.pixels[i] ushr 24) and 0xFF
                if (alpha == 0) continue

                // Central differences, clamped at the border so the frame's edge does not read as a
                // cliff and light up every letter that touches it.
                val dx = (heightAt(height, w, h, x + 1, y) - heightAt(height, w, h, x - 1, y)) * relief
                val dy = (heightAt(height, w, h, x, y + 1) - heightAt(height, w, h, x, y - 1)) * relief
                val length = kotlin.math.sqrt(dx * dx + dy * dy + 4f)
                val nx = -dx / length
                val ny = -dy / length
                val nz = 2f / length

                var lambert = nx * lx + ny * ly + nz * lz
                if (effect.direction == ir.pixellab.core.model.BevelDirection.DOWN) lambert = -lambert
                // Against a flat surface's response, so an unbevelled interior stays exactly as it
                // was painted instead of being tinted by the light everywhere.
                val flat = lz
                val delta = effect.glossContour.evaluate(abs(lambert - flat).coerceIn(0f, 1f))
                if (delta <= 0f) continue

                out.pixels[i] = if (lambert > flat) {
                    paintOn(
                        out.pixels[i],
                        effect.highlightColor,
                        delta * effect.highlightOpacity,
                        effect.highlightBlend,
                    )
                } else {
                    paintOn(
                        out.pixels[i],
                        effect.shadowColor,
                        delta * effect.shadowOpacity,
                        effect.shadowBlend,
                    )
                }
            }
        }
        return out
    }

    /**
     * A shadow cast onto the inside of the shape by its own edge.
     *
     * The same arithmetic as the drop shadow with the mask inverted, and then clipped back to the
     * shape — which is the whole of what makes it read as an inset face rather than as a smudge
     * round the outside.
     */
    private fun innerShadow(layer: Raster, source: Raster, effect: Effect.InnerShadow): Raster {
        val w = layer.width
        val h = layer.height
        var hole = FloatArray(source.pixels.size) {
            1f - ((source.pixels[it] ushr 24) and 0xFF) / 255f
        }
        if (effect.blur > 0f) hole = blur(hole, w, h, effect.blur)

        val radians = (effect.angle + STRAIGHT) * DEG_TO_RAD
        val dx = (cos(radians) * effect.distance).roundToInt()
        val dy = (-sin(radians) * effect.distance).roundToInt()

        val out = layer.copy()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val alpha = ((source.pixels[i] ushr 24) and 0xFF) / 255f
                if (alpha <= 0f) continue
                val darkness = sampleAt(hole, w, h, x - dx, y - dy) * alpha
                if (darkness <= 0f) continue
                out.pixels[i] = paintOn(
                    out.pixels[i],
                    effect.color,
                    darkness * effect.opacity,
                    effect.blendMode,
                )
            }
        }
        return out
    }

    /**
     * Samples a pattern tile at a canvas position, repeating it.
     *
     * The modulo is taken twice because Kotlin's remainder keeps the sign of its left operand, so a
     * negative offset lands outside the tile and throws. Scale divides rather than multiplies: a
     * scale of two means the tile is drawn twice as large, which is half as many repeats across the
     * same span, and getting that backwards makes every pattern shrink when the user enlarges it.
     */
    private fun tileAt(tile: Raster, fill: Fill.Pattern, x: Int, y: Int): Color {
        val sx = if (fill.scale.x == 0f) 1f else fill.scale.x
        val sy = if (fill.scale.y == 0f) 1f else fill.scale.y
        val u = ((x - fill.offset.x) / sx).toInt()
        val v = ((y - fill.offset.y) / sy).toInt()
        val tx = ((u % tile.width) + tile.width) % tile.width
        val ty = ((v % tile.height) + tile.height) % tile.height
        val pixel = tile.pixels[ty * tile.width + tx]
        return Color(
            ((pixel shr 16) and 0xFF) / 255f,
            ((pixel shr 8) and 0xFF) / 255f,
            (pixel and 0xFF) / 255f,
        )
    }

    /** Blends one colour onto one pixel, keeping the pixel's own alpha. */
    private fun paintOn(pixel: Int, colour: Color, strength: Float, mode: BlendMode): Int {
        val k = strength.coerceIn(0f, 1f)
        if (k <= 0f) return pixel
        val backdrop = Triple(
            ((pixel shr 16) and 0xFF) / 255f,
            ((pixel shr 8) and 0xFF) / 255f,
            (pixel and 0xFF) / 255f,
        )
        val blended = Blending.rgb(mode, backdrop, Triple(colour.r, colour.g, colour.b))
        return (pixel and ALPHA_MASK) or
            (byte(lerp(backdrop.first, blended.first, k)) shl 16) or
            (byte(lerp(backdrop.second, blended.second, k)) shl 8) or
            byte(lerp(backdrop.third, blended.third, k))
    }

    private fun heightAt(height: FloatArray, width: Int, h: Int, x: Int, y: Int): Float =
        height[y.coerceIn(0, h - 1) * width + x.coerceIn(0, width - 1)]

    /** Distance from each opaque pixel to the nearest transparent one — the bevel's own ramp. */
    private fun distanceInside(source: Raster): FloatArray {
        val inverted = Raster(
            source.width,
            source.height,
            IntArray(source.pixels.size) {
                if ((source.pixels[it] ushr 24) > HALF_BYTE) 0 else (0xFF shl 24)
            },
        )
        return distanceOutside(inverted)
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

    /**
     * Draws the source's silhouette in one colour, offset by a *fractional* amount.
     *
     * Sub-pixel, and that is the difference between an extrusion and a staircase. Rounding each
     * step to whole pixels means several consecutive steps land on the same one — at an offset
     * below a pixel, most of them do — so a sixty-step block is a handful of distinct positions
     * with hard jumps between them, and its edge steps rather than ramps. Photoshop's duplicates
     * sit wherever the transform puts them and are resampled; these do the same.
     *
     * Bilinear against the source's alpha, so an edge landing between two pixels is shared between
     * them and the block's silhouette stays as smooth as the letter's own.
     */
    private fun stamp(out: Raster, source: Raster, dx: Float, dy: Float, colour: Color, strength: Float) {
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                val a = sampleAlpha(source, x - dx, y - dy) * strength
                if (a <= 0f) continue
                out.pixels[y * out.width + x] = overPixel(out.pixels[y * out.width + x], pack(colour, a))
            }
        }
    }

    /** The source's alpha at a fractional position, bilinearly. */
    private fun sampleAlpha(source: Raster, x: Float, y: Float): Float {
        val x0 = kotlin.math.floor(x).toInt()
        val y0 = kotlin.math.floor(y).toInt()
        val fx = x - x0
        val fy = y - y0

        fun at(px: Int, py: Int): Float {
            if (px < 0 || py < 0 || px >= source.width || py >= source.height) return 0f
            return ((source.pixels[py * source.width + px] ushr 24) and 0xFF) / 255f
        }

        val top = at(x0, y0) * (1f - fx) + at(x0 + 1, y0) * fx
        val bottom = at(x0, y0 + 1) * (1f - fx) + at(x0 + 1, y0 + 1) * fx
        return top * (1f - fy) + bottom * fy
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

    private const val ALPHA_MASK = -0x1000000
    private const val HUNDRED = 100f

    /**
     * How steeply the height field climbs for a given depth.
     *
     * Photoshop's depth of 100% is a pronounced bevel rather than a barely visible one, so the
     * gradient needs a multiplier above one to match; below this the default reads as a smudge.
     */
    private const val DEPTH_GAIN = 4f

    /** A chisel-soft shoulder is eased just enough to lose its facets without going round. */
    private const val CHISEL_SOFTNESS = 0.12f

    /** A smooth shoulder is rounded across a third of the bevel, which is where it stops reading as cut. */
    private const val SMOOTH_SOFTNESS = 0.33f
}
