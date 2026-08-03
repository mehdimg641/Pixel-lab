package ir.pixellab.engine.android

import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.fonts.Script
import ir.pixellab.core.model.Color
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
            axes = emptyMap(),
            features = emptySet(),
            script = Script.ARABIC,
            hasPersianDigits = true,
            hasTatweel = true,
        )
    }

    /** Orange metal on every edge, teal on the face — as near the reference as flat colour reaches. */
    private fun trendLook(size: Float) = Geometry3D(
        depth = size * 0.30f,
        bevelSize = size * 0.012f,
        faceMaterial = Material(
            baseColor = Color(0.09f, 0.45f, 0.44f),
            roughness = 0.35f,
            clearCoat = 1f,
        ),
        bevelMaterial = ORANGE,
        sideMaterial = ORANGE,
        rotation = Vec3(-6f, 14f, 0f),
        fieldOfView = 28f,
    )

    @Test
    fun `the reference effect, with what the engine has today`() {
        val fonts = FontResolver { vazirmatn() }
        val size = 240f
        val layer = Layer.Text(
            id = LayerId("trend"),
            spec = TextSpec(text = "TREND", font = FontRef("Vazirmatn"), size = size),
            name = "trend",
            transform = Transform(),
            style = Style.PLAIN_BLACK,
        )

        val rendered = TextTo3D.render(
            layer = layer,
            geometry = trendLook(size),
            fonts = fonts,
            width = 1200,
            height = 620,
            supersample = 4,
        )
        checkNotNull(rendered) { "the reference word produced no render" }
        write("reference-attempt", rendered.width, rendered.height, rendered.pixels)
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
    }
}
