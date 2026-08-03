package ir.pixellab.engine.android

import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.fonts.Script
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.Geometry3D
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.Material
import ir.pixellab.core.model.Style
import ir.pixellab.core.model.TextSpec
import ir.pixellab.core.model.GradientStop
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2
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
 * Reproducing a commercial text effect, as a way of finding what the engine cannot do.
 *
 * The reference is a stock PSD title: deep extrusion, orange metal on the bevel and side walls, and
 * a face carrying a painterly teal-to-peach texture rather than a flat colour, with a thin bright
 * rim between face and bevel and a soft shadow behind the whole word.
 *
 * Matching it is not the point. Failing to match it in a *specific* way is: each thing this cannot
 * reproduce is a feature the engine is missing, and a picture names them faster than reading the
 * model does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fa")
class ReferenceMatchTest {

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
            // Declared, because the loader drops any axis the file does not claim to have. The real
            // app fills this in from its font scan; a fixture that leaves it empty silently renders
            // every weight as Regular.
            axes = mapOf(FontRef.AXIS_WEIGHT to 100f..900f),
            features = emptySet(),
            script = Script.ARABIC,
            hasPersianDigits = true,
            hasTatweel = true,
        )
    }

    /**
     * The reference's own recipe, in this engine's terms.
     *
     * The letters stand upright and frontal and the depth runs off down-left, which is the whole
     * character of the style and is a *lean*, not a turn. A small rotation is kept only to catch a
     * highlight along the top of the bevel.
     */
    private fun trendLook(size: Float, bevelFraction: Float = 0.035f) = Geometry3D(
        depth = size * 0.40f,
        bevelSize = size * bevelFraction,
        faceMaterial = Material(roughness = 0.30f, clearCoat = 1f),
        faceFill = TEAL_TO_PEACH,
        bevelMaterial = ORANGE,
        sideMaterial = ORANGE,
        // Down and to the right, which is where the reference throws its block.
        extrusionTilt = Vec2(0.34f, -0.30f),
        rotation = Vec3(-3f, 4f, 0f),
        fieldOfView = 22f,
    )

    @Test
    fun `the reference effect, with what the engine has today`() {
        val fonts = FontResolver { vazirmatn() }
        val size = 240f
        val layer = Layer.Text(
            id = LayerId("trend"),
            spec = TextSpec(
                text = "TREND",
                font = FontRef(
                    family = "Vazirmatn",
                    weight = 900,
                    variations = mapOf(FontRef.AXIS_WEIGHT to 900f),
                ),
                size = size,
            ),
            name = "trend",
            transform = Transform(),
            style = Style.PLAIN_BLACK,
        )

        for ((name, bevel) in listOf("nobevel" to 0f, "bevel" to 0.035f)) {
            val rendered = TextTo3D.render(
                layer = layer,
                geometry = trendLook(size, bevel),
                fonts = fonts,
                width = 1200,
                height = 620,
                supersample = 4,
            )
            checkNotNull(rendered) { "the reference word produced no render" }
            write("diag-$name", rendered.width, rendered.height, rendered.pixels)
        }
    }

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
        val ORANGE = Material(
            baseColor = Color(0.96f, 0.52f, 0.11f),
            metallic = 1f,
            roughness = 0.32f,
        )

        /**
         * The face: deep teal at the edges, warming to peach across the middle.
         *
         * Read off the reference rather than invented. Its face is not one colour and not a plain
         * two-stop ramp either — the warm band runs through the centre with green on both sides,
         * which is four stops, and the angle carries it up to the right across the whole word.
         */
        val TEAL_TO_PEACH = Fill.Gradient(
            stops = listOf(
                GradientStop(0f, Color(0.04f, 0.28f, 0.33f)),
                GradientStop(0.22f, Color(0.10f, 0.47f, 0.42f)),
                GradientStop(0.40f, Color(0.28f, 0.62f, 0.45f)),
                GradientStop(0.55f, Color(0.97f, 0.76f, 0.58f)),
                GradientStop(0.70f, Color(0.35f, 0.62f, 0.50f)),
                GradientStop(1f, Color(0.05f, 0.30f, 0.36f)),
            ),
            // Diagonal, not vertical. The reference runs its warm band up across the word rather
            // than banding it in horizontal stripes, and a near-vertical ramp is what stripes look
            // like once the letters are wider than they are tall.
            angle = 28f,
        )
    }
}
