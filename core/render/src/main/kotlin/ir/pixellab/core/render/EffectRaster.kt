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

        // Everything from here to the final conversion is float. A style is not one operation —
        // this face passes through an overlay, a pattern, an inner shadow and a bevel, and the
        // result is composited over an extrusion, over two shadows, under a stroke. Rounding to
        // eight bits at each of those is not one rounding error: the errors accumulate, and they
        // accumulate *coherently* across a smooth ramp, which is exactly what bands a gradient and
        // walks a colour off the swatch that was picked.
        val base = Surface.of(source)

        // The layer itself, with everything that paints *into* it already applied. Order matters
        // among these too: the overlay lays the colour down, the inner shadow darkens its edges,
        // and the bevel lights the result — a bevel computed before the colour exists would shade
        // a surface nobody sees.
        var layer = base.copy()
        for (effect in effects.filterIsInstance<Effect.Overlay>()) {
            layer = overlay(layer, effect, patterns)
        }
        for (effect in effects.filterIsInstance<Effect.InnerShadow>()) {
            layer = innerShadow(layer, source, effect)
        }
        for (effect in effects.filterIsInstance<Effect.Bevel>()) {
            layer = bevel(layer, source, effect)
        }

        var out = Surface(source.width, source.height)

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
        return out.toRaster()
    }

    /**
     * Stacks one styled layer over another.
     *
     * A single layer cannot carry this family of style. The frame's stroke has to sit *outside* the
     * letter and the face's gradient *inside* it, and one stroke cannot be on two sides at once —
     * which is exactly why the Photoshop recipes for these titles all begin by duplicating the text
     * layer. Exposed so a caller can build that stack without reimplementing source-over.
     */
    fun overComposite(under: Raster, above: Raster): Raster =
        over(Surface.of(under), Surface.of(above), BlendMode.NORMAL, 1f).toRaster()

    // ---- the effects ---------------------------------------------------------------------------

    /**
     * Offset copies of the silhouette, ramped from the near colour to the far one.
     *
     * Drawn back to front so the nearest step lands last and covers the ones behind it, which is
     * what makes the stack read as a solid block rather than as a fan of overlapping cut-outs.
     */
    private fun extrude(source: Raster, effect: Effect.Extrude): Surface {
        val out = Surface(source.width, source.height)
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
    private fun dropShadow(source: Raster, effect: Effect.DropShadow): Surface {
        var mask = alphaOf(source)
        if (effect.spread > 0f) mask = spread(mask, source.width, source.height, effect.spread)
        if (effect.blur > 0f) mask = blur(mask, source.width, source.height, effect.blur)

        // Photoshop measures the angle as the direction the light comes *from*, so the shadow falls
        // the opposite way. Taking it as the direction of travel puts every shadow on the wrong side.
        val radians = (effect.angle + STRAIGHT) * DEG_TO_RAD
        val dx = (cos(radians) * effect.distance).roundToInt()
        val dy = (-sin(radians) * effect.distance).roundToInt()

        val out = Surface(source.width, source.height)
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
                write(out, y * out.width + x, effect.color, a)
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
    private fun stroke(source: Raster, effect: Effect.Stroke): Surface {
        val out = Surface(source.width, source.height)
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
                write(out, i, colour, coverage)
            }
        }
        return out
    }

    /** A fill painted across the layer, kept inside the layer's own alpha. */
    private fun overlay(
        layer: Surface,
        effect: Effect.Overlay,
        patterns: (ir.pixellab.core.model.AssetId) -> Raster?,
    ): Surface {
        val out = layer.copy()
        val gradient = effect.fill as? Fill.Gradient
        val pattern = (effect.fill as? Fill.Pattern)?.let { fill -> patterns(fill.asset)?.let { fill to it } }
        val flat = if (gradient == null && pattern == null) fillColour(effect.fill) else null
        val stops = gradient?.stops?.sortedBy { it.position }

        for (y in 0 until layer.height) {
            for (x in 0 until layer.width) {
                val i = y * layer.width + x
                if (layer.alphaAt(i) <= 0f) continue
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
                val base = i * Surface.CHANNELS
                val backdrop = Triple(
                    layer.data[base],
                    layer.data[base + 1],
                    layer.data[base + 2],
                )
                val blended = Blending.rgb(effect.blendMode, backdrop, Triple(paint.r, paint.g, paint.b))
                val k = effect.opacity.coerceIn(0f, 1f)
                out.data[base] = lerp(backdrop.first, blended.first, k)
                out.data[base + 1] = lerp(backdrop.second, blended.second, k)
                out.data[base + 2] = lerp(backdrop.third, blended.third, k)
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
    private fun bevel(layer: Surface, source: Raster, effect: Effect.Bevel): Surface {
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

                // Deliberately *not* converted to linear light before blending.
                //
                // Shading in linear is the physically correct thing and it is the wrong thing here.
                // Photoshop computes Bevel and Emboss in the document's own space, so a linear
                // pipeline would produce a defensible result that differs visibly from the
                // reference at every setting — a brighter lip and a softer shoulder than the file
                // being matched. Fidelity to the reference and physical correctness point in
                // opposite directions on this one, and the reference wins.
                if (lambert > flat) {
                    paintOn(out, i, effect.highlightColor, delta * effect.highlightOpacity, effect.highlightBlend)
                } else {
                    paintOn(out, i, effect.shadowColor, delta * effect.shadowOpacity, effect.shadowBlend)
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
    private fun innerShadow(layer: Surface, source: Raster, effect: Effect.InnerShadow): Surface {
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
                paintOn(out, i, effect.color, darkness * effect.opacity, effect.blendMode)
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

    /** Blends one colour onto one pixel in place, keeping the pixel's own alpha. */
    private fun paintOn(surface: Surface, index: Int, colour: Color, strength: Float, mode: BlendMode) {
        val k = strength.coerceIn(0f, 1f)
        if (k <= 0f) return
        val base = index * Surface.CHANNELS
        val backdrop = Triple(surface.data[base], surface.data[base + 1], surface.data[base + 2])
        val blended = Blending.rgb(mode, backdrop, Triple(colour.r, colour.g, colour.b))
        surface.data[base] = lerp(backdrop.first, blended.first, k)
        surface.data[base + 1] = lerp(backdrop.second, blended.second, k)
        surface.data[base + 2] = lerp(backdrop.third, blended.third, k)
    }

    /** Sets one pixel to a colour at a coverage, replacing whatever was there. */
    private fun write(surface: Surface, index: Int, colour: Color, alpha: Float) {
        val base = index * Surface.CHANNELS
        surface.data[base] = colour.r
        surface.data[base + 1] = colour.g
        surface.data[base + 2] = colour.b
        surface.data[base + 3] = alpha.coerceIn(0f, 1f) * colour.a.let { if (it == 0f) 1f else it }
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
     * Exact Euclidean distance from each transparent pixel to the nearest opaque one.
     *
     * Exact, not approximate — see [Signal.distance]. A chamfer transform stood here and was wrong
     * by up to four per cent on a diagonal, which is enough to make a stroke visibly thicker on the
     * diagonal of a letter than on its stem, and to give a bevel a shoulder whose width breathes as
     * the outline turns. Photoshop's does neither.
     */
    private fun distanceOutside(source: Raster): FloatArray =
        Signal.distance(
            BooleanArray(source.pixels.size) { (source.pixels[it] ushr 24) > HALF_BYTE },
            source.width,
            source.height,
        )

    private fun alphaOf(source: Raster) =
        FloatArray(source.pixels.size) { ((source.pixels[it] ushr 24) and 0xFF) / 255f }

    /**
     * Grows the mask by a radius, using the same exact distance field the stroke measures with.
     *
     * Straight to a boolean mask rather than through a packed image: the distance transform only
     * ever asked whether a pixel was set, so packing the coverage into bytes to unpack it again was
     * a rounding step that decided nothing.
     */
    private fun spread(mask: FloatArray, width: Int, height: Int, radius: Float): FloatArray {
        val distance = Signal.distance(
            BooleanArray(mask.size) { mask[it] > HALF },
            width,
            height,
        )
        return FloatArray(mask.size) { maxOf(mask[it], (radius - distance[it] + 1f).coerceIn(0f, 1f)) }
    }

    /**
     * A true Gaussian — see [Signal.gaussian].
     *
     * Photoshop states a blur as a radius and means a Gaussian whose visible reach is about that
     * far, so the radius is converted to a standard deviation rather than used as one: a sigma of
     * the stated radius blurs roughly three times too far.
     */
    private fun blur(mask: FloatArray, width: Int, height: Int, radius: Float): FloatArray =
        Signal.gaussian(mask, width, height, radius / SIGMA_PER_RADIUS)

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
    private fun stamp(out: Surface, source: Raster, dx: Float, dy: Float, colour: Color, strength: Float) {
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                val a = sampleAlpha(source, x - dx, y - dy) * strength
                if (a <= 0f) continue
                val index = y * out.width + x
                val base = index * Surface.CHANNELS
                val da = out.data[base + 3]
                val outA = a + da * (1f - a)
                if (outA <= 0f) continue
                out.data[base] = (colour.r * a + out.data[base] * da * (1f - a)) / outA
                out.data[base + 1] = (colour.g * a + out.data[base + 1] * da * (1f - a)) / outA
                out.data[base + 2] = (colour.b * a + out.data[base + 2] * da * (1f - a)) / outA
                out.data[base + 3] = outA
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

    /**
     * Source-over in float, with an optional blend mode for the colour.
     *
     * The alpha arithmetic is straight rather than premultiplied throughout. Premultiplying is the
     * faster convention and it loses colour wherever alpha is near zero — a soft shadow's outermost
     * pixels carry almost no alpha, and their colour is the thing being asked for.
     */
    private fun over(under: Surface, above: Surface, mode: BlendMode, opacity: Float): Surface {
        val out = under.copy()
        val k = opacity.coerceIn(0f, 1f)
        for (i in 0 until under.width * under.height) {
            val base = i * Surface.CHANNELS
            val sa = above.data[base + 3] * k
            if (sa <= 0f) continue
            val da = out.data[base + 3]

            var sr = above.data[base]
            var sg = above.data[base + 1]
            var sb = above.data[base + 2]
            // The blend only applies where there is a backdrop to blend with. Over nothing, every
            // mode reduces to the source — and running Multiply against a transparent black would
            // otherwise darken the first thing laid down on an empty canvas.
            if (mode != BlendMode.NORMAL && da > 0f) {
                val blended = Blending.rgb(
                    mode,
                    Triple(out.data[base], out.data[base + 1], out.data[base + 2]),
                    Triple(sr, sg, sb),
                )
                sr = blended.first
                sg = blended.second
                sb = blended.third
            }

            val outA = sa + da * (1f - sa)
            if (outA <= 0f) {
                out.data[base + 3] = 0f
                continue
            }
            out.data[base] = (sr * sa + out.data[base] * da * (1f - sa)) / outA
            out.data[base + 1] = (sg * sa + out.data[base + 1] * da * (1f - sa)) / outA
            out.data[base + 2] = (sb * sa + out.data[base + 2] * da * (1f - sa)) / outA
            out.data[base + 3] = outA
        }
        return out
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

    private fun mix(a: Color, b: Color, t: Float) = Color(
        a.r + (b.r - a.r) * t,
        a.g + (b.g - a.g) * t,
        a.b + (b.b - a.b) * t,
        a.a + (b.a - a.a) * t,
    )

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private const val HALF = 0.5f
    private const val HALF_BYTE = 127
    private const val STRAIGHT = 180f
    private const val DEG_TO_RAD = 0.017453292f

    /**
     * Photoshop's blur radius against a Gaussian's standard deviation.
     *
     * Its slider is a reach, not a sigma. Three sigma covers essentially all of a Gaussian, so a
     * radius divided by three gives a blur whose visible extent matches the number the user typed.
     */
    private const val SIGMA_PER_RADIUS = 3f

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
