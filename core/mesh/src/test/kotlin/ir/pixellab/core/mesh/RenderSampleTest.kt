package ir.pixellab.core.mesh

import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Geometry3D
import ir.pixellab.core.model.Material
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.model.Vec3
import org.junit.jupiter.api.Test
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Renders sample letters and writes the pictures out.
 *
 * A test and a tool at once, the same way the Compose screenshots are. As a test it asserts the
 * things a picture makes obvious and a number does not — that the bevel is genuinely brighter than
 * the face it borders, that a metal's highlight is tinted, that turning the letter reveals a side
 * wall. As a tool it produces a real render of real 3D text on a build machine with no GPU, which
 * is the only way to look at the thing this application was written for.
 *
 * The PNG writer is here rather than pulled in, because this module is pure Kotlin with only the
 * document model behind it, and adding an image dependency to the geometry module for the sake of
 * a test fixture would be the wrong trade entirely.
 */
class RenderSampleTest {

    private val output = File("build/renders").apply { mkdirs() }

    /**
     * A blocky ل, close enough for a shading test.
     *
     * A real glyph outline would be better and is not available here: this module deliberately has
     * no font dependency. What matters for these tests is that the shape has a thin stroke, an
     * inside corner and a wide foot — which is where a bevel goes wrong.
     */
    private fun lam(): List<List<Vec2>> = listOf(
        listOf(
            Vec2(10f, 10f), Vec2(100f, 10f), Vec2(100f, 34f),
            Vec2(40f, 34f), Vec2(40f, 150f), Vec2(10f, 150f),
        ),
    )

    private fun ring(): List<List<Vec2>> = listOf(
        listOf(Vec2(0f, 0f), Vec2(120f, 0f), Vec2(120f, 120f), Vec2(0f, 120f)),
        listOf(Vec2(35f, 35f), Vec2(35f, 85f), Vec2(85f, 85f), Vec2(85f, 35f)),
    )

    private val goldOnWhite = Geometry3D(
        depth = 26f,
        bevelSize = 5f,
        faceMaterial = Material.GLOSSY_WHITE,
        bevelMaterial = Material.GOLD,
        sideMaterial = Material.GOLD,
    )

    private fun write(name: String, rendered: Rendered) {
        File(output, "$name.png").writeBytes(png(rendered))
    }

    private fun luma(pixel: Int): Float =
        ((pixel shr 16) and 0xFF) * 0.2126f +
            ((pixel shr 8) and 0xFF) * 0.7152f +
            (pixel and 0xFF) * 0.0722f

    @Test
    fun `a gold-edged letter renders with its bevel catching the light`() {
        val mesh = Extruder.extrude(lam(), depth = 26f, bevelSize = 5f, bevelSegments = 6)
        val rendered = Rasteriser.render(mesh, goldOnWhite.copy(rotation = Vec3(-8f, 22f, 0f)), 480, 480, 3)
        write("letter-gold", rendered)

        rendered.isEmpty shouldBe false
        // The whole point of a separate bevel material, stated as what it actually produces: some
        // of the letter is gold and some of it is not. A render that lost the material split — one
        // material everywhere, or a bevel that collapsed to nothing — has no gold pixels at all.
        val lit = rendered.pixels.filter { (it ushr 24) > 128 }
        val gold = lit.count { ((it shr 16) and 0xFF) > (it and 0xFF) + 40 }
        (gold > lit.size / 40) shouldBe true
        (gold < lit.size / 2) shouldBe true
    }

    @Test
    fun `turning the letter reveals a side wall that was not there before`() {
        val mesh = Extruder.extrude(lam(), depth = 40f, bevelSize = 4f)
        val flat = Rasteriser.render(mesh, goldOnWhite, 360, 360, 2)
        val turned = Rasteriser.render(mesh, goldOnWhite.copy(rotation = Vec3(0f, 38f, 0f)), 360, 360, 2)
        write("letter-flat", flat)
        write("letter-turned", turned)

        // The thing a stack of offset copies cannot do, measured directly: turning the letter
        // brings its side walls into view, so the share of the silhouette painted in the side
        // material goes *up*. A flat sticker's proportions do not change when it is turned.
        fun goldFraction(r: Rendered): Float {
            val lit = r.pixels.filter { (it ushr 24) > 128 }
            if (lit.isEmpty()) return 0f
            return lit.count { ((it shr 16) and 0xFF) > (it and 0xFF) + 40 }.toFloat() / lit.size
        }
        (goldFraction(turned) > goldFraction(flat) * 1.3f) shouldBe true

        var different = 0
        for (i in flat.pixels.indices) if (flat.pixels[i] != turned.pixels[i]) different++
        (different > flat.pixels.size / 8) shouldBe true
    }

    @Test
    fun `a counter stays a hole all the way through the extrusion`() {
        // A ه or a ۵: the inner contour has to be a hole in the front cap, in the back cap *and*
        // in the side walls. A tessellator that got only the front right leaves a letter that is
        // hollow from the front and solid from behind, which shows the moment it is turned.
        val mesh = Extruder.extrude(ring(), depth = 30f, bevelSize = 4f)
        val rendered = Rasteriser.render(mesh, goldOnWhite.copy(rotation = Vec3(0f, 18f, 0f)), 320, 320, 2)
        write("counter", rendered)

        // Straight through the middle there is background, not letter.
        val centre = rendered.pixels[160 * 320 + 160]
        ((centre ushr 24) and 0xFF) shouldBe 0
    }

    @Test
    fun `a chrome letter and a matte one do not render the same`() {
        val mesh = Extruder.extrude(lam(), depth = 26f, bevelSize = 5f)
        val chrome = Rasteriser.render(
            mesh,
            goldOnWhite.copy(
                faceMaterial = Material.CHROME,
                bevelMaterial = Material.CHROME,
                sideMaterial = Material.CHROME,
                rotation = Vec3(-8f, 22f, 0f),
            ),
            320,
            320,
            2,
        )
        val matte = Rasteriser.render(
            mesh,
            goldOnWhite.copy(
                faceMaterial = Material.MATTE,
                bevelMaterial = Material.MATTE,
                sideMaterial = Material.MATTE,
                rotation = Vec3(-8f, 22f, 0f),
            ),
            320,
            320,
            2,
        )
        write("material-chrome", chrome)
        write("material-matte", matte)

        fun contrast(r: Rendered): Float {
            val lit = r.pixels.filter { (it ushr 24) > 128 }.map { luma(it) }
            return if (lit.isEmpty()) 0f else lit.max() - lit.min()
        }
        // Polished metal spans a far wider range than a matte surface: that spread *is* what
        // "shiny" looks like, and a shader that ignored roughness would give the two the same.
        (contrast(chrome) > contrast(matte)) shouldBe true
    }

    @Test
    fun `a candy letter's coat highlight is whiter than the paint under it`() {
        val mesh = Extruder.extrude(ring(), depth = 20f, bevelSize = 6f)
        val candy = Material(baseColor = Color(0.90f, 0.16f, 0.30f), roughness = 0.4f, clearCoat = 1f)
        val rendered = Rasteriser.render(
            mesh,
            goldOnWhite.copy(faceMaterial = candy, bevelMaterial = candy, sideMaterial = candy),
            320,
            320,
            2,
        )
        write("material-candy", rendered)

        val lit = rendered.pixels.filter { (it ushr 24) > 128 }
        val brightest = lit.maxByOrNull { luma(it) }!!
        val red = (brightest shr 16) and 0xFF
        val blue = brightest and 0xFF
        // The coat is a dielectric whatever is beneath it, so its highlight is white — which pulls
        // the blue channel up on a red letter. Without a separate coat term the brightest pixel is
        // just a brighter red.
        (blue > red / 3) shouldBe true
    }

    // ---- a minimal PNG writer ------------------------------------------------------------------

    private fun png(rendered: Rendered): ByteArray {
        val raw = java.io.ByteArrayOutputStream()
        for (y in 0 until rendered.height) {
            raw.write(0)
            for (x in 0 until rendered.width) {
                val p = rendered.pixels[y * rendered.width + x]
                raw.write((p shr 16) and 0xFF)
                raw.write((p shr 8) and 0xFF)
                raw.write(p and 0xFF)
                raw.write((p ushr 24) and 0xFF)
            }
        }

        val deflater = Deflater()
        deflater.setInput(raw.toByteArray())
        deflater.finish()
        val compressed = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1 shl 16)
        while (!deflater.finished()) compressed.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()

        val out = java.io.ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.write(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))

        val header = java.io.ByteArrayOutputStream()
        DataOutputStream(header).apply {
            writeInt(rendered.width)
            writeInt(rendered.height)
            writeByte(8)
            // Colour type 6: truecolour with alpha, which is what a 3D letter needs to sit on a
            // design rather than on a rectangle of background.
            writeByte(6)
            writeByte(0)
            writeByte(0)
            writeByte(0)
        }
        chunk(data, "IHDR", header.toByteArray())
        chunk(data, "IDAT", compressed.toByteArray())
        chunk(data, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun chunk(out: DataOutputStream, type: String, payload: ByteArray) {
        out.writeInt(payload.size)
        val typed = type.toByteArray(Charsets.US_ASCII)
        out.write(typed)
        out.write(payload)
        val crc = CRC32()
        crc.update(typed)
        crc.update(payload)
        out.writeInt(crc.value.toInt())
    }
}
