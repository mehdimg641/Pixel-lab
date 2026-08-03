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

        val rendered = EffectRaster.apply(word, COVER)
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

        /**
         * The reference's recipe, effect for effect.
         *
         * Read off the picture rather than guessed at: a teal-to-peach ramp across the face, a
         * bright gold line round the outside, a deep block thrown down and right, and a soft shadow
         * under the whole word.
         */
        val COVER = Style(
            fill = Fill.Solid(Color.WHITE),
            effects = listOf(
                Effect.DropShadow(
                    color = Color(0.10f, 0.06f, 0.03f, 0.55f),
                    angle = 125f,
                    distance = 26f,
                    blur = 34f,
                    spread = 2f,
                ),
                Effect.Extrude(
                    steps = 60,
                    stepOffset = Vec2(0.9f, 0.9f),
                    nearFill = Fill.Solid(Color(0.98f, 0.62f, 0.13f)),
                    farFill = Fill.Solid(Color(0.38f, 0.14f, 0.03f)),
                    falloff = Curve.LINEAR,
                ),
                Effect.Overlay(
                    fill = Fill.Gradient(
                        stops = listOf(
                            GradientStop(0f, Color(0.04f, 0.30f, 0.34f)),
                            GradientStop(0.30f, Color(0.13f, 0.52f, 0.43f)),
                            GradientStop(0.52f, Color(0.97f, 0.76f, 0.58f)),
                            GradientStop(0.72f, Color(0.20f, 0.55f, 0.47f)),
                            GradientStop(1f, Color(0.04f, 0.28f, 0.36f)),
                        ),
                        angle = 74f,
                    ),
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
