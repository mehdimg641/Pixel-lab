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

        // The torn edge comes first, and it changes the *silhouette* rather than the finished
        // picture. Applying it last was the obvious arrangement and it destroyed everything behind
        // the layer: a glow is faint by construction, so eroding the composite against a threshold
        // wiped the glow out entirely and left the erosion looking like a bug in the glow.
        //
        // Roughening the outline first is also what the effect is for. A flame, a torn sheet and a
        // spray-painted stencil all have a shadow and a glow that follow the ragged edge, not the
        // clean one it was cut from.
        val silhouette = effects.filterIsInstance<Effect.EdgeRoughen>()
            .fold(source) { current, effect -> roughen(current, effect) }

        // Everything from here to the final conversion is float. A style is not one operation —
        // this face passes through an overlay, a pattern, an inner shadow and a bevel, and the
        // result is composited over an extrusion, over two shadows, under a stroke. Rounding to
        // eight bits at each of those is not one rounding error: the errors accumulate, and they
        // accumulate *coherently* across a smooth ramp, which is exactly what bands a gradient and
        // walks a colour off the swatch that was picked.
        //
        // It starts from the layer's own paint, before any effect touches it. That step was missing,
        // and its absence was invisible in exactly the way that matters: the fill is where a style's
        // *colour* comes from, so half the catalogue — every gold, every chrome, every gradient face
        // — rendered as whatever the silhouette happened to be, usually white, with the effects
        // correctly drawn around it. Each effect looked right on its own and the picture was wrong.
        val base = fillOf(silhouette, style, patterns)

        // The layer itself, with everything that paints *into* it already applied, in the order the
        // slots say: the overlay lays the colour down, satin folds a sheen across it, the two inner
        // effects work its edges, and the bevel lights the result. A bevel computed before the
        // colour exists would shade a surface nobody sees.
        var layer = base.copy()
        for (effect in effects.filterIsInstance<Effect.Overlay>()) {
            layer = overlay(layer, effect, patterns)
        }
        for (effect in effects.filterIsInstance<Effect.Satin>()) {
            layer = satin(layer, silhouette, effect)
        }
        for (effect in effects.filterIsInstance<Effect.InnerGlow>()) {
            layer = innerGlow(layer, silhouette, effect)
        }
        for (effect in effects.filterIsInstance<Effect.InnerShadow>()) {
            layer = innerShadow(layer, silhouette, effect)
        }
        for (effect in effects.filterIsInstance<Effect.Bevel>()) {
            layer = bevel(layer, silhouette, effect)
        }

        var out = Surface(silhouette.width, silhouette.height)

        // Behind, furthest first, in [PassSlot]'s own order — which is Photoshop's. The shadow falls
        // on everything, the glow sits on the shadow, the extrusion on the glow.
        for (effect in effects.filterIsInstance<Effect.DropShadow>()) {
            out = over(out, dropShadow(silhouette, effect), effect.blendMode, effect.opacity)
        }
        for (effect in effects.filterIsInstance<Effect.OuterGlow>()) {
            out = over(out, outerGlow(silhouette, effect), effect.blendMode, effect.opacity)
        }
        for (effect in effects.filterIsInstance<Effect.Extrude>()) {
            out = over(out, extrude(silhouette, effect), effect.blendMode, effect.opacity)
        }
        for (effect in effects.filterIsInstance<Effect.Reflection>()) {
            out = over(out, reflection(layer, effect), effect.blendMode, effect.opacity)
        }

        out = over(out, layer, BlendMode.NORMAL, 1f)

        // And over the top.
        for (effect in effects.filterIsInstance<Effect.Stroke>()) {
            out = over(out, stroke(silhouette, effect), effect.blendMode, effect.opacity)
        }

        // The post slot works on the finished layer rather than on its silhouette, which is why
        // these come last and why they are the only ones that can change what is already drawn.
        for (effect in effects) {
            out = when (effect) {
                is Effect.Noise -> noise(out, effect)
                is Effect.ChromaticOffset -> chromaticOffset(out, effect)
                // Everything else has already been drawn above, and the edge was roughened before
                // anything was. Named rather than left to an else so that a new effect fails the
                // build here instead of silently never rendering — which is exactly how seven of
                // these came to be missing in the first place.
                is Effect.Stroke, is Effect.DropShadow, is Effect.InnerShadow, is Effect.OuterGlow,
                is Effect.InnerGlow, is Effect.Bevel, is Effect.Satin, is Effect.Overlay,
                is Effect.Extrude, is Effect.Reflection, is Effect.EdgeRoughen,
                    -> out
                // Frosted glass reads the destination buffer, not the layer, so it belongs to the
                // compositor rather than to a layer's own style. There is nothing sensible for it to
                // do here, and inventing something would be worse than doing nothing.
                is Effect.BackdropBlur -> out
            }
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
     * The layer's silhouette, painted with the style's own fill.
     *
     * Fill opacity rather than layer opacity, and the distinction is the whole reason it is a
     * separate number: dropping the fill to nothing leaves every effect at full strength, which is
     * what makes hollow text — a stroke and a shadow with nothing between them — possible at all.
     * Layer opacity would fade the stroke along with the face and give a ghost instead.
     */
    private fun fillOf(
        source: Raster,
        style: Style,
        patterns: (ir.pixellab.core.model.AssetId) -> Raster?,
    ): Surface {
        val out = Surface.of(source)
        val fill = style.fill
        // Frosted glass reads the buffer beneath the layer, which a per-layer compositor does not
        // have. Left as the silhouette's own colour rather than guessed at.
        if (fill is Fill.Backdrop) return out

        val gradient = fill as? Fill.Gradient
        val stops = gradient?.stops?.sortedBy { it.position }
        val pattern = (fill as? Fill.Pattern)?.let { p -> patterns(p.asset)?.let { p to it } }
        val flat = if (gradient == null && pattern == null) fillColour(fill) else null
        val opacity = style.fillOpacity.coerceIn(0f, 1f)

        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                val i = y * out.width + x
                val base = i * Surface.CHANNELS
                if (out.data[base + 3] <= 0f) continue
                val paint = when {
                    gradient != null -> Ramp.colorAt(
                        stops.orEmpty(),
                        Ramp.parameterAt(gradient, x.toFloat() / out.width, y.toFloat() / out.height),
                    )
                    pattern != null -> tileAt(pattern.second, pattern.first, x, y)
                    else -> flat!!
                }
                out.data[base] = paint.r
                out.data[base + 1] = paint.g
                out.data[base + 2] = paint.b
                out.data[base + 3] *= paint.a * opacity
            }
        }
        return out
    }

    /**
     * A glow spreading outwards from the silhouette.
     *
     * A shadow with the offset taken out is the tempting shortcut and it is wrong in two ways that
     * both show. A glow is *contoured*: the falloff runs through a curve rather than straight down,
     * which is what lets a neon sign have a hot core and a long faint halo instead of one linear
     * fade. And the layer's own pixels are knocked out unconditionally, so the glow never tints the
     * shape it surrounds — a shadow only does that when asked.
     */
    private fun outerGlow(source: Raster, effect: Effect.OuterGlow): Surface {
        val alpha = alphaOf(source)
        var mask = alpha
        if (effect.spread > 0f) mask = spread(mask, source.width, source.height, effect.spread)
        if (effect.blur > 0f) mask = blur(mask, source.width, source.height, effect.blur)

        val out = Surface(source.width, source.height)
        val gradient = effect.fill as? Fill.Gradient
        val stops = gradient?.stops?.sortedBy { it.position }
        val flat = if (gradient == null) fillColour(effect.fill) else null

        for (i in mask.indices) {
            val shaped = effect.contour.evaluate(mask[i].coerceIn(0f, 1f))
            // Outside only. A glow that painted over its own shape would grey out every letter it
            // is supposed to be lighting from behind.
            val a = shaped * (1f - alpha[i])
            if (a <= 0f) continue
            // A gradient glow is read along the falloff rather than across the canvas: the ramp is
            // the distance from the shape, which is what makes a two-colour glow shift with radius.
            val colour = if (stops != null) Ramp.colorAt(stops, 1f - shaped) else flat!!
            write(out, i, colour, a)
        }
        return out
    }

    /**
     * A glow living inside the silhouette, from its edge or from its centre.
     *
     * The two sources are genuinely different measurements and not a flipped sign. From the edge,
     * the glow is the blurred *hole* outside the shape seen through it — bright at the rim, fading
     * inwards. From the centre it is the distance field itself — brightest deep inside, fading out
     * to the rim. Photoshop offers both because they do opposite jobs: an edge glow lines a letter,
     * a centre glow inflates it.
     */
    private fun innerGlow(layer: Surface, source: Raster, effect: Effect.InnerGlow): Surface {
        val w = layer.width
        val h = layer.height
        val alpha = alphaOf(source)

        var field = when (effect.source) {
            ir.pixellab.core.model.GlowSource.EDGE -> FloatArray(alpha.size) { 1f - alpha[it] }
            ir.pixellab.core.model.GlowSource.CENTER -> {
                // Normalised by the deepest point so the centre reaches full strength whatever the
                // letter's weight; an unnormalised field makes a thin stroke glow barely at all.
                val inside = distanceInside(source)
                val deepest = inside.max().coerceAtLeast(1f)
                FloatArray(inside.size) { inside[it] / deepest }
            }
        }
        if (effect.choke > 0f) field = spread(field, w, h, effect.choke)
        if (effect.blur > 0f) field = blur(field, w, h, effect.blur)

        val out = layer.copy()
        val colour = fillColour(effect.fill)
        for (i in field.indices) {
            if (alpha[i] <= 0f) continue
            val strength = effect.contour.evaluate(field[i].coerceIn(0f, 1f)) * alpha[i] * effect.opacity
            if (strength <= 0f) continue
            paintOn(out, i, colour, strength, effect.blendMode)
        }
        return out
    }

    /**
     * Satin: the shape folded against itself.
     *
     * Two offset copies of the blurred silhouette, subtracted. That difference is zero wherever the
     * shape is uniform and rises wherever it curves, so what appears is a soft band following every
     * bend of the letterform — the folded-cloth sheen the effect is named for. Nothing else in the
     * stack produces a pattern that depends on the shape's *curvature* rather than on its edge.
     */
    private fun satin(layer: Surface, source: Raster, effect: Effect.Satin): Surface {
        val w = layer.width
        val h = layer.height
        val alpha = alphaOf(source)
        val soft = if (effect.blur > 0f) blur(alpha, w, h, effect.blur) else alpha

        val radians = effect.angle * DEG_TO_RAD
        val dx = cos(radians) * effect.distance
        val dy = -sin(radians) * effect.distance

        val out = layer.copy()
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                if (alpha[i] <= 0f) continue
                val a = sampleAt(soft, w, h, (x + dx).roundToInt(), (y + dy).roundToInt())
                val b = sampleAt(soft, w, h, (x - dx).roundToInt(), (y - dy).roundToInt())
                var v = kotlin.math.abs(a - b)
                if (effect.invert) v = 1f - v
                v = effect.contour.evaluate(v.coerceIn(0f, 1f))
                paintOn(out, i, effect.color, v * alpha[i] * effect.opacity, effect.blendMode)
            }
        }
        return out
    }

    /**
     * A mirrored copy below the layer, fading away.
     *
     * Mirrored about the bottom of what is actually drawn rather than about the middle of the
     * buffer. The buffer is padded by however much bleed the effect stack asked for, so reflecting
     * about its centre floats the reflection somewhere in the empty margin and the gap parameter
     * then means nothing.
     */
    private fun reflection(layer: Surface, effect: Effect.Reflection): Surface {
        val w = layer.width
        val h = layer.height
        val out = Surface(w, h)

        var bottom = -1
        for (y in h - 1 downTo 0) {
            if ((0 until w).any { layer.alphaAt(y * w + it) > 0f }) {
                bottom = y
                break
            }
        }
        if (bottom < 0) return out

        val start = bottom + effect.gap.roundToInt() + 1
        val span = (h * effect.height).roundToInt().coerceAtLeast(1)
        for (y in start until minOf(h, start + span)) {
            val t = (y - start).toFloat() / span
            val fade = effect.startOpacity + (effect.endOpacity - effect.startOpacity) * t
            if (fade <= 0f) continue
            val from = bottom - (y - start)
            if (from < 0) break
            for (x in 0 until w) {
                val src = from * w + x
                val a = layer.alphaAt(src) * fade
                if (a <= 0f) continue
                val srcBase = src * Surface.CHANNELS
                val base = (y * w + x) * Surface.CHANNELS
                out.data[base] = layer.data[srcBase]
                out.data[base + 1] = layer.data[srcBase + 1]
                out.data[base + 2] = layer.data[srcBase + 2]
                out.data[base + 3] = a
            }
        }
        return if (effect.blur > 0f) blurSurface(out, effect.blur) else out
    }

    /**
     * Grain over the finished layer.
     *
     * Coloured noise is three independent draws, not one draw tinted. That distinction is the whole
     * difference between glitter — thousands of facets each catching the light at its own angle —
     * and a dirty print.
     *
     * The hash is a pure function of the coordinate, so the grain is identical every render. Noise
     * that moved between frames would make a still document shimmer on screen and differ from its
     * own export.
     */
    private fun noise(surface: Surface, effect: Effect.Noise): Surface {
        val out = surface.copy()
        val scale = if (effect.scale <= 0f) 1f else effect.scale
        for (y in 0 until surface.height) {
            for (x in 0 until surface.width) {
                val i = y * surface.width + x
                if (out.alphaAt(i) <= 0f) continue
                val u = (x / scale).toInt()
                val v = (y / scale).toInt()
                val mono = hash(u, v, 0) - HALF
                val base = i * Surface.CHANNELS
                val dr = mono * effect.amount
                val dg = (if (effect.monochrome) mono else hash(u, v, 17) - HALF) * effect.amount
                val db = (if (effect.monochrome) mono else hash(u, v, 43) - HALF) * effect.amount
                val k = effect.opacity.coerceIn(0f, 1f)
                out.data[base] = lerp(out.data[base], (out.data[base] + dr).coerceIn(0f, 1f), k)
                out.data[base + 1] = lerp(out.data[base + 1], (out.data[base + 1] + dg).coerceIn(0f, 1f), k)
                out.data[base + 2] = lerp(out.data[base + 2], (out.data[base + 2] + db).coerceIn(0f, 1f), k)
            }
        }
        return out
    }

    /**
     * Each channel sampled from a different place — the RGB split.
     *
     * Alpha takes the *maximum* of the three rather than any one of them, so the fringe reaches as
     * far as the furthest channel. Taking one channel's alpha clips the other two against a
     * silhouette they no longer share, and the split then stops dead at the original outline.
     */
    private fun chromaticOffset(surface: Surface, effect: Effect.ChromaticOffset): Surface {
        val w = surface.width
        val h = surface.height
        val out = Surface(w, h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val base = i * Surface.CHANNELS
                val red = channelAt(surface, x - effect.redOffset.x, y - effect.redOffset.y, 0)
                val green = channelAt(surface, x - effect.greenOffset.x, y - effect.greenOffset.y, 1)
                val blue = channelAt(surface, x - effect.blueOffset.x, y - effect.blueOffset.y, 2)
                out.data[base] = red.first
                out.data[base + 1] = green.first
                out.data[base + 2] = blue.first
                out.data[base + 3] = maxOf(red.second, green.second, blue.second)
            }
        }
        return out
    }

    /**
     * Fractal noise added to the distance field, eroding the silhouette.
     *
     * Displacing the *field* rather than fading the alpha is what makes this a torn edge instead of
     * a soft one. Fading gives every boundary pixel the same partial coverage and reads as blur;
     * moving the boundary itself takes bites out of the outline, which is what a flame, a torn sheet
     * of paper and a spray-painted stencil all actually have.
     */
    private fun roughen(source: Raster, effect: Effect.EdgeRoughen): Raster {
        val w = source.width
        val h = source.height
        val set = BooleanArray(w * h) { (source.pixels[it] ushr 24) > HALF_BYTE }
        val outside = Signal.distance(set, w, h)
        val inside = Signal.distance(BooleanArray(w * h) { !set[it] }, w, h)

        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                // Signed: negative inside the shape, positive outside, which is the field the GPU
                // path displaces and the only form in which "erode" is a single addition.
                val signed = outside[i] - inside[i]
                val displaced = signed + fbm(x, y, effect.detail, effect.seed) * effect.amount
                val coverage = (HALF - displaced).coerceIn(0f, 1f)
                val alpha = (((source.pixels[i] ushr 24) and 0xFF) * coverage).toInt().coerceIn(0, 255)
                pixels[i] = (alpha shl 24) or (source.pixels[i] and 0xFFFFFF)
            }
        }
        return Raster(w, h, pixels)
    }

    /** One channel and its alpha at a fractional position, bilinearly. */
    private fun channelAt(surface: Surface, x: Float, y: Float, channel: Int): Pair<Float, Float> {
        val x0 = kotlin.math.floor(x).toInt()
        val y0 = kotlin.math.floor(y).toInt()
        val fx = x - x0
        val fy = y - y0

        fun at(px: Int, py: Int, c: Int): Float {
            if (px < 0 || py < 0 || px >= surface.width || py >= surface.height) return 0f
            return surface.data[(py * surface.width + px) * Surface.CHANNELS + c]
        }

        fun sample(c: Int): Float {
            val top = at(x0, y0, c) * (1f - fx) + at(x0 + 1, y0, c) * fx
            val bottom = at(x0, y0 + 1, c) * (1f - fx) + at(x0 + 1, y0 + 1, c) * fx
            return top * (1f - fy) + bottom * fy
        }
        return sample(channel) to sample(3)
    }

    /** Blurs a surface's alpha and colour together, for the reflection's soft copy. */
    private fun blurSurface(surface: Surface, radius: Float): Surface {
        val out = Surface(surface.width, surface.height)
        for (c in 0 until Surface.CHANNELS) {
            val plane = FloatArray(surface.width * surface.height) {
                surface.data[it * Surface.CHANNELS + c]
            }
            val blurred = blur(plane, surface.width, surface.height, radius)
            for (i in blurred.indices) out.data[i * Surface.CHANNELS + c] = blurred[i]
        }
        return out
    }

    /**
     * A stable hash of a lattice point.
     *
     * The same one the GPU path uses, so a grain generated here and a grain generated there land on
     * the same pixels — otherwise an exported document's noise would not match its own preview.
     */
    private fun hash(x: Int, y: Int, salt: Int): Float {
        var n = x * HASH_X + y * HASH_Y + salt * HASH_SALT
        n = n xor (n shl 13)
        n = n xor (n ushr 17)
        n = n xor (n shl 5)
        return (n and HASH_MASK).toFloat() / HASH_MASK
    }

    /** Four octaves of value noise; [detail] decides how much each finer one contributes. */
    private fun fbm(x: Int, y: Int, detail: Float, seed: Int): Float {
        var total = 0f
        var amplitude = HALF
        var frequency = 1
        val persistence = lerp(0.2f, 0.7f, detail.coerceIn(0f, 1f))
        for (octave in 0 until FBM_OCTAVES) {
            total += (hash(x / frequency, y / frequency, seed + octave) - HALF) * amplitude
            frequency *= 2
            amplitude *= persistence
        }
        return total
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

    /**
     * A large odd multiplier per axis, which is what keeps a lattice hash from repeating along one.
     *
     * The same three the GLSL uses, so grain generated here lands on the same pixels as grain
     * generated on the device — otherwise an export would not match the preview it came from.
     */
    private const val HASH_X = 374761393
    private const val HASH_Y = 668265263
    private const val HASH_SALT = 2246822519.toInt()

    /** Twenty-four bits: finer than a float's mantissa needs and far finer than eight-bit output. */
    private const val HASH_MASK = 0xFFFFFF

    /** Four octaves. Past that the finest is below a pixel and only costs time. */
    private const val FBM_OCTAVES = 4
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
