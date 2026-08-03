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
        val coverage = Procedural.pattern(Procedural.Pattern.MARBLE, size = 256, repeats = 7, seed = 7)
        val pixels = IntArray(coverage.width * coverage.height)
        for (y in 0 until coverage.height) {
            for (x in 0 until coverage.width) {
                // Stretched away from the middle before it is used. Fractal noise clusters hard
                // around a half — that is what it is — so mapped straight onto two colours it gives
                // a wash of the average and neither end ever shows. The reference's face is streaks
                // of green *and* apricot, not a blend of them.
                val raw = coverage[x, y, 0].coerceIn(0f, 1f)
                val t = ((raw - 0.5f) * CONTRAST + 0.5f).coerceIn(0f, 1f)
                val r = lerp(0.03f, 0.98f, t)
                val g = lerp(0.42f, 0.80f, t)
                val b = lerp(0.40f, 0.62f, t)
                pixels[y * coverage.width + x] = (0xFF shl 24) or
                    (byteOf(r) shl 16) or (byteOf(g) shl 8) or byteOf(b)
            }
        }
        return Raster(coverage.width, coverage.height, pixels)
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
            size = 250f,
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
        canvas.drawText(text, MARGIN, height * BASELINE, paint)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return Raster(width, height, pixels)
    }

    @Test
    fun `the cover style renders from layer effects alone`() {
        val width = 1200
        val height = 620
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
        val rendered = EffectRaster.overComposite(frame, face)
        write("cover-style", rendered)

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
        const val MARGIN = 60f
        const val BASELINE = 0.62f
        const val OPAQUE = 128

        /** Enough separation that a near-grey does not read as a hue. */
        const val CHANNEL_MARGIN = 40

        /** Pushes fractal noise off its mean so both ends of the ramp actually appear. */
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
                // The texture, over the ramp and blended rather than replacing it — the recipe asks
                // for Overlay at a third to a half, which keeps the gradient's light and dark and
                // lets the pattern only disturb them.
                Effect.Overlay(
                    fill = Fill.Pattern(asset = ir.pixellab.core.model.AssetId("paint")),
                    blendMode = ir.pixellab.core.model.BlendMode.OVERLAY,
                    opacity = 0.6f,
                ),
                // The inset face: a soft dark edge just inside the outline, which is what stops
                // the face reading as a flat sticker laid on top of the block.
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
