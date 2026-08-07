package ir.pixellab.engine.android

import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.fonts.Script
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.Geometry3D
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.Material
import ir.pixellab.core.model.Style
import ir.pixellab.core.model.TextSpec
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec3
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Renders real Persian words in real 3D, through the path the app itself uses.
 *
 * A test and a tool at once, and it exists because the geometry module's own sample renders cannot
 * answer the question that matters. That module has no font dependency on purpose, so its letters
 * are blocky six-vertex stand-ins — good enough to prove a bevel catches light, and not good enough
 * to tell anyone what the application actually produces. Type is curves. A picture made of straight
 * lines says nothing about how the curves will come out.
 *
 * So this goes through `TextTo3D` with the bundled Vazirmatn, which is the same call the build
 * button makes. What is written into `build/renders` here is a genuine sample of the app's output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fa")
class DimensionalRenderTest {

    private val output = File("build/renders").apply { mkdirs() }

    /**
     * The bundled variable font, unpacked so the platform can open it by path.
     *
     * Loaded from the test's own resources rather than from the app module's assets, because a test
     * that reached across module boundaries for a file would break the moment either moved.
     */
    private fun vazirmatn(): FontFile {
        val file = File.createTempFile("vazirmatn", ".ttf")
        file.deleteOnExit()
        checkNotNull(javaClass.classLoader?.getResourceAsStream("vazirmatn.ttf")) {
            "the test font is missing from engine/android/src/test/resources"
        }.use { input -> file.outputStream().use { input.copyTo(it) } }

        return FontFile(
            path = file.absolutePath,
            family = "Vazirmatn",
            subfamily = "Regular",
            postScriptName = "Vazirmatn-Regular",
            fullName = "Vazirmatn Regular",
            weight = 400,
            italic = false,
            axes = emptyMap(),
            features = emptySet(),
            script = Script.ARABIC,
            hasPersianDigits = true,
            hasTatweel = true,
        )
    }

    private fun layer(text: String, size: Float) = Layer.Text(
        id = LayerId("title"),
        spec = TextSpec(text = text, font = FontRef("Vazirmatn"), size = size),
        name = "تیتر",
        transform = Transform(),
        style = Style.PLAIN_BLACK,
    )

    /** The cover treatment the app was asked for: white face, gold edges, turned into the light. */
    private fun goldCover(size: Float) = Geometry3D(
        depth = size * DEPTH_FRACTION,
        bevelSize = size * BEVEL_FRACTION,
        faceMaterial = Material.GLOSSY_WHITE,
        bevelMaterial = Material.GOLD,
        sideMaterial = Material.GOLD,
        rotation = Vec3(-8f, 18f, 0f),
    )

    @Test
    fun `a Persian headline renders as real three-dimensional type`() {
        val fonts = FontResolver { vazirmatn() }
        val size = 220f

        val rendered = TextTo3D.render(
            layer = layer("کاربیست", size),
            geometry = goldCover(size),
            fonts = fonts,
            width = 1200,
            height = 520,
            supersample = 4,
        )

        checkNotNull(rendered) { "the headline produced no render at all" }
        write("headline-gold", rendered.width, rendered.height, rendered.pixels)

        val lit = rendered.pixels.count { (it ushr 24) > 128 }
        check(lit > rendered.pixels.size / 40) { "the headline is too small to judge: $lit pixels" }

        // The face survived, which is the whole of what «bevelled» means and is not something the
        // geometry can be trusted to report. A bevel that has eaten the stroke produces a mesh that
        // is closed, correctly wound and perfectly valid — it just is not a letter any more. It is
        // two gold shoulders meeting at a ridge, and the only place that shows is a picture.
        //
        // So this counts the face: bright and near-neutral, which the white face is and the gold on
        // every other surface is not. A third of the lit pixels is far below a healthy render and
        // far above the ribbon, which is what makes it a regression guard rather than a tuning knob.
        val face = rendered.pixels.count { pixel ->
            (pixel ushr 24) > 128 &&
                luma(pixel) > BRIGHTEST &&
                kotlin.math.abs(((pixel shr 16) and 0xFF) - (pixel and 0xFF)) < NEUTRAL
        }
        check(face > lit / 3) { "the bevel has eaten the letters: face=$face of lit=$lit" }
    }

    @Test
    fun `the dots of a Persian letter can float clear of the body`() {
        // The same word twice, one variable. This is §۶.۹.۳ on real type rather than on a stand-in,
        // and «بیست» is the right word for it — three dotted letters and a ی in one string.
        val fonts = FontResolver { vazirmatn() }
        val size = 260f
        val flat = goldCover(size)

        val flush = TextTo3D.render(
            layer = layer("بیست", size),
            geometry = flat,
            fonts = fonts,
            width = 900,
            height = 520,
            supersample = 4,
        )
        val floating = TextTo3D.render(
            layer = layer("بیست", size),
            geometry = flat.copy(markDepth = 0.5f, markLift = 0.9f, markMaterial = Material.CHROME),
            fonts = fonts,
            width = 900,
            height = 520,
            supersample = 4,
        )

        checkNotNull(flush) { "the flush word produced no render" }
        checkNotNull(floating) { "the floating word produced no render" }
        write("word-flush", flush.width, flush.height, flush.pixels)
        write("word-floating", floating.width, floating.height, floating.pixels)

        // Mid-grey is chrome and nothing else here — the palette is gold and near-white, and neither
        // makes a neutral pixel at middling brightness. The reasoning is the geometry module's, and
        // it is repeated on real glyphs because a dot detector that works on a square and fails on a
        // real ب would pass every test in that module.
        fun chrome(pixels: IntArray) = pixels.count { pixel ->
            (pixel ushr 24) > 128 &&
                kotlin.math.abs(((pixel shr 16) and 0xFF) - (pixel and 0xFF)) < NEUTRAL &&
                luma(pixel) in DARKEST..BRIGHTEST
        }
        check(chrome(flush.pixels) < chrome(floating.pixels) / 4) {
            "the dots did not take the mark material: flush=${chrome(flush.pixels)} " +
                "floating=${chrome(floating.pixels)}"
        }
    }

    private fun luma(pixel: Int): Float =
        ((pixel shr 16) and 0xFF) * RED + ((pixel shr 8) and 0xFF) * GREEN + (pixel and 0xFF) * BLUE

    private fun write(name: String, width: Int, height: Int, pixels: IntArray) {
        File(output, "$name.png").writeBytes(png(width, height, pixels))
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
        val compressed = ByteArray(raw.size() + HEADROOM)
        val written = deflater.deflate(compressed)

        val out = java.io.ByteArrayOutputStream()
        val stream = DataOutputStream(out)
        stream.write(SIGNATURE)

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
            writeInt(width)
            writeInt(height)
            write(BIT_DEPTH)
            write(COLOUR_RGBA)
            write(0)
            write(0)
            write(0)
        }
        chunk("IHDR", header.toByteArray())
        chunk("IDAT", compressed.copyOf(written))
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private companion object {
        /** A cover title is deep — a fifth of the cap height reads as carved rather than embossed. */
        const val DEPTH_FRACTION = 0.2f

        /**
         * The app's own default, so these pictures are of what the build button produces.
         *
         * Deliberately not a rounder, larger number. A Persian stroke is thin against the size that
         * names it, and a bevel a third of a stroke turns a word into gold ribbon.
         */
        const val BEVEL_FRACTION = 0.01f

        const val NEUTRAL = 12
        const val DARKEST = 60f
        const val BRIGHTEST = 200f
        const val RED = 0.2126f
        const val GREEN = 0.7152f
        const val BLUE = 0.0722f

        const val BIT_DEPTH = 8
        const val COLOUR_RGBA = 6
        const val HEADROOM = 1024
        val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    }
}
