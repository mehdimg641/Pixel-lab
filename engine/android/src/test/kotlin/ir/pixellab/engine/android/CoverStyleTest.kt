package ir.pixellab.engine.android

import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.fonts.Script
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Curve
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.GradientStop
import ir.pixellab.core.model.Style
import ir.pixellab.core.model.scaled
import ir.pixellab.core.model.TextSpec
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.imaging.Procedural
import ir.pixellab.core.render.EffectRaster
import ir.pixellab.core.render.Raster
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * The reference title, built the way it was actually built.
 *
 * Several rounds went into matching a stock PSD text effect with the physically based 3D renderer,
 * and the reason it kept not matching is that the reference is not a 3D render. It is a layer-style
 * stack — stroke, extrude, overlay, drop shadow — which this repository has implemented all along,
 * in GLSL, where no test could reach it.
 *
 * So this renders that stack on the CPU and writes the picture out. It is the first test in the
 * project that looks at a pixel produced by an *effect* rather than by geometry: everything else
 * checks that the right passes were scheduled with the right parameters, which a stroke on the wrong
 * side of the outline satisfies perfectly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fa")
class CoverStyleTest {

    private val output = File("build/renders").apply { mkdirs() }

    private fun vazirmatn(): FontFile {
        val file = File.createTempFile("vazirmatn", ".ttf")
        file.deleteOnExit()
        checkNotNull(javaClass.classLoader?.getResourceAsStream("vazirmatn.ttf")) {
            "the test font is missing from engine/android/src/test/resources"
        }.use { input -> file.outputStream().use { input.copyTo(it) } }
        return FontFile(
            path = file.absolutePath,
            family = "Vazirmatn",
            subfamily = "Bold",
            postScriptName = "Vazirmatn-Bold",
            fullName = "Vazirmatn Bold",
            weight = 900,
            italic = false,
            axes = mapOf(FontRef.AXIS_WEIGHT to 100f..900f),
            features = emptySet(),
            script = Script.ARABIC,
            hasPersianDigits = true,
            hasTatweel = true,
        )
    }

    /**
     * A tile of flowing paint, tinted into the face's own family.
     *
     * The generator returns coverage rather than colour, deliberately, so the caller decides what to
     * paint with it. Here the coverage picks between a deep teal and a warm peach, which is what
     * turns a grey noise field into the reference's streaked green-and-apricot face.
     */
    private fun paintTexture(): Raster {
        val coverage = Procedural.pattern(Procedural.Pattern.MARBLE, size = 512, repeats = 16, seed = 7)

        // **Brushed first, tinted second**, and the order is the whole lesson. Painting the *tinted*
        // field instead — which is the obvious way round — hands the mode filter a picture that is
        // mostly green with a minority of apricot, and a mode filter's entire job is to vote out the
        // minority. The peach disappeared from every letter and the face came back a flat teal.
        //
        // Brushing the coverage puts the facets in the *shape* of the paint and leaves the palette
        // to decide the colours afterwards, so both ends survive at full strength. That is also what
        // a painter does: the brush makes the marks, the palette says what colour they are.
        val grey = ir.pixellab.core.imaging.Raster(coverage.width, coverage.height, 3)
        for (i in 0 until coverage.width * coverage.height) {
            val v = coverage[i % coverage.width, i / coverage.width, 0].coerceIn(0f, 1f)
            grey.data[i * 3] = v
            grey.data[i * 3 + 1] = v
            grey.data[i * 3 + 2] = v
        }
        val brushed = ir.pixellab.core.imaging.Artistic.oilPaint(
            grey,
            radius = BRUSH_RADIUS,
            // Few levels, because a brush facet is one loaded colour rather than a ramp. This is the
            // number that decides whether it reads as paint at all.
            levels = BRUSH_LEVELS,
        )

        // **Normalised to what the field actually contains, not stretched by a constant.** A fixed
        // contrast number is a guess about a distribution, and the guess was wrong twice here:
        // fractal noise clusters around a half, and the mode filter then narrows it further, so a
        // ×2.6 stretch about 0.5 never reached the warm end and every letter came back flat teal.
        // Measuring the range costs one pass and cannot be wrong about it.
        var low = Float.MAX_VALUE
        var high = -Float.MAX_VALUE
        for (i in 0 until brushed.pixelCount) {
            val v = brushed.data[i * 3]
            if (v < low) low = v
            if (v > high) high = v
        }
        val span = (high - low).coerceAtLeast(1e-4f)

        val pixels = IntArray(coverage.width * coverage.height)
        for (i in pixels.indices) {
            val normalised = ((brushed.data[i * 3] - low) / span).coerceIn(0f, 1f)
            // Then biased toward the cool end, because the reference's apricot is *streaks* through
            // a green face rather than half of it. A straight ramp gives an even mix, which reads as
            // a different material entirely — mottled stone rather than painted metal.
            val t = Math.pow(normalised.toDouble(), WARM_BIAS.toDouble()).toFloat()
            val r = lerp(0.03f, 0.99f, t)
            val g = lerp(0.42f, 0.72f, t)
            val b = lerp(0.40f, 0.52f, t)
            pixels[i] = (0xFF shl 24) or (byteOf(r) shl 16) or (byteOf(g) shl 8) or byteOf(b)
        }
        return Raster(coverage.width, coverage.height, pixels)
    }

    /** ARGB ints to the float raster the imaging filters work in. */
    private fun toImaging(width: Int, height: Int, pixels: IntArray): ir.pixellab.core.imaging.Raster {
        val out = ir.pixellab.core.imaging.Raster(width, height, 3)
        for (i in pixels.indices) {
            val p = pixels[i]
            out.data[i * 3] = ((p shr 16) and 0xFF) / 255f
            out.data[i * 3 + 1] = ((p shr 8) and 0xFF) / 255f
            out.data[i * 3 + 2] = (p and 0xFF) / 255f
        }
        return out
    }

    private fun fromImaging(source: ir.pixellab.core.imaging.Raster): Raster {
        val pixels = IntArray(source.pixelCount)
        for (i in pixels.indices) {
            val o = i * source.channels
            pixels[i] = (0xFF shl 24) or
                (byteOf(source.data[o]) shl 16) or
                (byteOf(source.data[o + 1]) shl 8) or
                byteOf(source.data[o + 2])
        }
        return Raster(source.width, source.height, pixels)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private fun byteOf(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()

    /** The word as flat white pixels — the silhouette every effect is measured against. */
    private fun silhouette(text: String, width: Int, height: Int): Raster {
        val spec = TextSpec(
            text = text,
            font = FontRef(
                family = "Vazirmatn",
                weight = 900,
                variations = mapOf(FontRef.AXIS_WEIGHT to 900f),
            ),
            // Proportional to the canvas rather than fixed, because the effect sizes below are in
            // pixels: a stroke of twenty and a block of sixty steps describe a *relationship* to
            // the letter, and enlarging the frame without enlarging the type quietly changes the
            // recipe into a different one.
            size = height * TYPE_HEIGHT,
        )
        val bitmap = android.graphics.Bitmap.createBitmap(
            width,
            height,
            android.graphics.Bitmap.Config.ARGB_8888,
        )
        val canvas = android.graphics.Canvas(bitmap)
        val rasterizer = TextRasterizer()
        val typeface = TypefaceLoader().load(vazirmatn(), mapOf(FontRef.AXIS_WEIGHT to 900f))
        val paint = rasterizer.paintFor(spec, typeface ?: android.graphics.Typeface.DEFAULT).apply {
            color = android.graphics.Color.WHITE
        }
        // Leaned rather than rotated, which is what the reference actually does: rotating a title
        // foreshortens its face in the same movement, and these covers keep their letters frontal
        // while the block runs off at an angle. `Transform.skew` carries it in the document; here
        // the canvas applies the same shear so the silhouette the effects see is already leaning.
        canvas.save()
        canvas.translate(0f, height * BASELINE)
        canvas.skew(kotlin.math.tan(Math.toRadians(SKEW.toDouble())).toFloat(), 0f)
        canvas.drawText(text, MARGIN, 0f, paint)
        canvas.restore()

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return Raster(width, height, pixels)
    }

    @Test
    fun `the cover style renders from layer effects alone`() {
        val width = 1800
        val height = 940
        val word = silhouette("TREND", width, height)
        check(word.pixels.any { (it ushr 24) > 0 }) { "the word produced no silhouette" }

        // Two layers, as the recipe describes: a copy underneath carrying the thick gold frame,
        // its chiselled bevel and the extruded block, and the face layer over it. One layer cannot
        // do both — the frame's stroke has to sit *outside* the letter and the face's gradient
        // *inside* it, and a single stroke cannot be on two sides at once.
        // The painterly texture, generated rather than shipped — this app carries no asset pack, so
        // a texture that is not in the APK has to be computed. Domain-warped noise is what makes it
        // read as dragged pigment rather than as fog.
        val texture = paintTexture()
        write("paint-texture", texture)
        val frame = EffectRaster.apply(word, FRAME)
        val face = EffectRaster.apply(word, FACE) { texture }
        val onTransparency = EffectRaster.overComposite(frame, face)
        write("cover-style", onTransparency)
        // And on the ground the recipe puts it on. Kept as a separate file rather than replacing
        // the first: the transparent one is what the app hands to an export with no background,
        // and the composed one is what the finished project looks like.
        val rendered = EffectRaster.overComposite(ground(width, height), onTransparency)
        write("cover-project", rendered)

        // And once at the size a real cover is actually made at, with the style scaled by the same
        // factor — Photoshop's Scale Effects. A style's numbers are in pixels and deliberately do
        // not follow the canvas, so a preset authored for a preview is a hairline on a three
        // thousand pixel cover unless something scales it. Every gradient, bevel shoulder and
        // shadow here has three times the pixels to be smooth in, which is what makes it the
        // honest answer to "what does the app actually produce".
        //
        // Off by default, and the same arrangement the sample-dependent tests use. It is a
        // *diagnostic*: every assertion in this test runs on the preview render above, so in CI
        // this would be four gigabytes of heap and two and a half minutes spent on a picture
        // nobody looks at. Run it with `-Dpixellab.fullRender=true` when the picture is the point.
        if (System.getProperty("pixellab.fullRender") != "true") return
        val big = width * FULL_SIZE
        val tall = height * FULL_SIZE
        val large = silhouette("TREND", big, tall)
        val scale = FULL_SIZE.toFloat()
        val largeFrame = EffectRaster.apply(large, FRAME.scaled(scale))
        val largeFace = EffectRaster.apply(large, FACE.scaled(scale)) { texture }
        write(
            "cover-project-full",
            EffectRaster.overComposite(
                ground(big, tall),
                EffectRaster.overComposite(largeFrame, largeFace),
            ),
        )

        // The three effects each have to have *done* something, and each is checked by the colour
        // only it can produce — the point of rendering rather than inspecting a plan.
        var gold = 0
        var orange = 0
        var teal = 0
        var shadow = 0
        for (pixel in rendered.pixels) {
            if ((pixel ushr 24) < OPAQUE) continue
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            when {
                r > 220 && g in 170..225 && b < 110 -> gold++
                r > 150 && g in 60..150 && b < 80 -> orange++
                // Green-dominant, not blue-dominant: the face's teal measures (24, 120, 96), so a
                // test for "blue beats green" finds none of it. The ramp's own stops say the same
                // thing — every teal in it has more green than blue.
                g > r + CHANNEL_MARGIN && g > b -> teal++
                r < 90 && g < 90 && b < 90 -> shadow++
            }
        }
        check(gold > 0) { "the stroke did not draw" }
        check(orange > gold) { "the extrusion is thinner than its own outline: $orange vs $gold" }
        check(teal > 0) { "the gradient overlay did not reach the face" }
        check(shadow > 0) { "the drop shadow did not draw" }

        // The texture reached the face. Measured as *local* variation rather than as spread: a
        // smooth gradient covers the same range of colours and would satisfy any test of spread,
        // so the question is whether neighbouring pixels differ — which is what a texture is and
        // what a ramp, by construction, is not.
        var restless = 0
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                if ((rendered.pixels[i] ushr 24) < OPAQUE) continue
                val here = (rendered.pixels[i] shr 8) and 0xFF
                val right = (rendered.pixels[i + 1] shr 8) and 0xFF
                val below = (rendered.pixels[i + width] shr 8) and 0xFF
                if (kotlin.math.abs(here - right) > GRAIN || kotlin.math.abs(here - below) > GRAIN) {
                    restless++
                }
            }
        }
        check(restless > teal / 20) {
            "the pattern overlay left the face smooth: $restless restless of $teal face pixels"
        }
    }

    /**
     * The grained, vignetted ground — `Library.coverProject`'s own background layer, rendered.
     *
     * A radial ramp rather than a darkening filter, because the whole point of the ground is that a
     * user can drag its centre or change its two greys without redoing anything above it. And the
     * grain is not decoration: a flat grey at this size bands visibly, since eight bits across a
     * slow ramp is a step every few pixels, and a little noise breaks the banding up.
     */
    private fun ground(width: Int, height: Int): Raster {
        val opaque = Raster(width, height, IntArray(width * height) { -1 })
        return EffectRaster.apply(
            opaque,
            ir.pixellab.core.editor.Library.coverProject().layers
                .first { it.id.value == "cover-ground" }.style,
        )
    }

    private fun write(name: String, raster: Raster) {
        File(output, "$name.png").writeBytes(png(raster.width, raster.height, raster.pixels))
    }

    private fun png(width: Int, height: Int, pixels: IntArray): ByteArray {
        val raw = java.io.ByteArrayOutputStream()
        for (y in 0 until height) {
            raw.write(0)
            for (x in 0 until width) {
                val pixel = pixels[y * width + x]
                raw.write((pixel shr 16) and 0xFF)
                raw.write((pixel shr 8) and 0xFF)
                raw.write(pixel and 0xFF)
                raw.write((pixel ushr 24) and 0xFF)
            }
        }
        val deflater = Deflater()
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val compressed = ByteArray(raw.size() + 1024)
        val written = deflater.deflate(compressed)

        val out = java.io.ByteArrayOutputStream()
        val stream = DataOutputStream(out)
        stream.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))
        fun chunk(type: String, body: ByteArray) {
            stream.writeInt(body.size)
            val tag = type.toByteArray(Charsets.US_ASCII)
            stream.write(tag)
            stream.write(body)
            val crc = CRC32()
            crc.update(tag)
            crc.update(body)
            stream.writeInt(crc.value.toInt())
        }
        val header = java.io.ByteArrayOutputStream()
        DataOutputStream(header).apply {
            writeInt(width); writeInt(height); write(8); write(6); write(0); write(0); write(0)
        }
        chunk("IHDR", header.toByteArray())
        chunk("IDAT", compressed.copyOf(written))
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private companion object {
        const val MARGIN = 110f

        /** The word fills most of the frame, as it does on every cover of this kind. */
        const val TYPE_HEIGHT = 0.42f

        /** Three times the preview, which puts the long edge past five thousand pixels. */
        const val FULL_SIZE = 3
        const val BASELINE = 0.62f

        /** Degrees of horizontal lean, matching `Library.coverProject`'s own transform. */
        const val SKEW = -8f
        const val OPAQUE = 128

        /** Enough separation that a near-grey does not read as a hue. */
        const val CHANNEL_MARGIN = 40

        /** Pushes fractal noise off its mean so both ends of the ramp actually appear. */
        /** Short facets: a brush end has to be visible at the size the face is seen at. */
        const val BRUSH_RADIUS = 5

        /** A loaded brush lays down one colour, not a ramp; this is what makes it read as paint. */
        const val BRUSH_LEVELS = 10

        /** Above one, so the warm colour stays a minority of streaks rather than half the face. */
        const val WARM_BIAS = 2.2f

        const val CONTRAST = 2.6f

        /** A step between neighbours larger than a ramp of this size could produce on its own. */
        const val GRAIN = 3

        /**
         * The reference's recipe, effect for effect.
         *
         * Read off the picture rather than guessed at: a teal-to-peach ramp across the face, a
         * bright gold line round the outside, a deep block thrown down and right, and a soft shadow
         * under the whole word.
         */
        /**
         * The lower layer: the gold frame, its chiselled edge, the extruded block and the shadows.
         *
         * Its stroke is *outside* and thick, which is what puts a band of gold round every letter
         * wider than the letter itself — the frame the face then sits inside.
         */
        val FRAME = Style(
            fill = Fill.Solid(Color.WHITE),
            effects = listOf(
                // Two shadows, as the recipe specifies: one tight and dark to seat the word, one
                // wide and faint for the depth of the room. A single shadow can be one or the
                // other and reads as a sticker either way.
                Effect.DropShadow(
                    color = Color(0.04f, 0.03f, 0.02f),
                    angle = 125f,
                    distance = 10f,
                    blur = 5f,
                    opacity = 0.60f,
                ),
                Effect.DropShadow(
                    color = Color(0.06f, 0.05f, 0.04f),
                    angle = 125f,
                    distance = 35f,
                    blur = 25f,
                    opacity = 0.30f,
                ),
                Effect.Extrude(
                    steps = 60,
                    stepOffset = Vec2(0.9f, 0.9f),
                    nearFill = Fill.Solid(Color(0.98f, 0.62f, 0.13f)),
                    farFill = Fill.Solid(Color(0.38f, 0.14f, 0.03f)),
                    falloff = Curve.LINEAR,
                ),
                // The frame itself: a wide outside stroke ramped orange to gold.
                Effect.Stroke(
                    width = 20f,
                    position = ir.pixellab.core.model.StrokePosition.OUTSIDE,
                    fill = Fill.Gradient(
                        stops = listOf(
                            GradientStop(0f, Color(0.902f, 0.494f, 0.133f)),
                            GradientStop(1f, Color(0.945f, 0.769f, 0.059f)),
                        ),
                        angle = 90f,
                    ),
                ),
            ),
        )

        /** The upper layer: the teal face, its texture, its inset edge and its fine gold edge. */
        val FACE = Style(
            fill = Fill.Solid(Color.WHITE),
            effects = listOf(
                Effect.Overlay(
                    fill = Fill.Gradient(
                        stops = listOf(
                            GradientStop(0f, Color(0.051f, 0.231f, 0.275f)),
                            GradientStop(0.55f, Color(0f, 0.659f, 0.588f)),
                            GradientStop(1f, Color(0.878f, 0.624f, 0.404f)),
                        ),
                        angle = 45f,
                    ),
                ),
                // The texture, over the ramp — and blended **Normal**, not Overlay.
                //
                // Overlay was the obvious choice and it is why the face came back flat teal three
                // renders running: Overlay transfers *contrast*, not colour. A peach texture laid
                // over a teal base with Overlay gives lighter and darker teal, because the blend
                // reads the texture as a luminance mask and keeps the base's hue. The apricot
                // streaks are the most recognisable thing about this reference, and no opacity of
                // Overlay could ever have produced them.
                //
                // Normal at a bit over half lets the paint's own colour arrive while the gradient
                // underneath still supplies the overall run from dark corner to lit corner.
                Effect.Overlay(
                    fill = Fill.Pattern(asset = ir.pixellab.core.model.AssetId("paint")),
                    opacity = 0.72f,
                ),
                // The inset face: a soft dark edge just inside the outline, which is what stops
                // the face reading as a flat sticker laid on top of the block.
                // The sheen. Satin folds the shape against itself — two offset copies of the
                // blurred silhouette, subtracted — so what appears is a soft band following every
                // bend of the letterform rather than a straight highlight laid across it. That is
                // the difference between a lit surface and a gradient drawn on top of one.
                Effect.Satin(
                    color = Color(0.85f, 1f, 0.96f),
                    angle = 125f,
                    distance = 26f,
                    blur = 34f,
                    blendMode = ir.pixellab.core.model.BlendMode.SCREEN,
                    opacity = 0.32f,
                ),
                Effect.InnerShadow(
                    color = Color(0.06f, 0.16f, 0.18f),
                    angle = 125f,
                    distance = 5f,
                    blur = 16f,
                    opacity = 0.55f,
                ),
                // The rim that makes it solid. A height field lit from one direction, not a pair of
                // offset copies — the highlight has to follow the curve of a bowl all the way round.
                Effect.Bevel(
                    depth = 160f,
                    size = 13f,
                    angle = 125f,
                    altitude = 42f,
                    profile = Curve.ROUNDED,
                    highlightColor = Color(1f, 0.97f, 0.86f),
                    highlightOpacity = 0.7f,
                    shadowColor = Color(0.10f, 0.20f, 0.22f),
                    shadowOpacity = 0.5f,
                ),
                Effect.Stroke(
                    width = 5f,
                    fill = Fill.Solid(Color(1f, 0.82f, 0.24f)),
                ),
            ),
        )
    }
}
