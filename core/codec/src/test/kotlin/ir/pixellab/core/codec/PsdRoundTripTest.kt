package ir.pixellab.core.codec

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.BlendMode
import ir.pixellab.core.model.CanvasSpec
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.DocumentId
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test

/**
 * Writing a PSD, checked by reading it back.
 *
 * The reader is the right oracle here because it is itself validated against eight commercial files
 * from Photoshop — so a round trip that survives it is a file Photoshop's own structure agrees with.
 * Asserting on the bytes instead would only prove the writer is consistent with itself.
 *
 * The point of writing PSD at all is reversibility: work started in this app has to be finishable in
 * Photoshop, and a flattened export is a one-way door.
 */
class PsdRoundTripTest {

    private fun image(width: Int, height: Int, color: Int) =
        RasterImage(width, height, IntArray(width * height) { color })

    private fun document(width: Int = 64, height: Int = 48) = Document(
        id = DocumentId("d"),
        canvas = CanvasSpec(width, height),
        name = "کاور",
    )

    private fun write(vararg layers: PsdLayerSource) =
        PsdWriter.write(document(), layers.toList(), image(64, 48, 0xFF000000.toInt()))

    @Test
    fun `a written file is recognised as a psd`() {
        val bytes = write(PsdLayerSource("لایه", 0, 0, image(10, 10, -1)))
        Format.detect(bytes) shouldBe Format.PSD
    }

    @Test
    fun `the canvas size survives`() {
        val bytes = PsdWriter.write(document(200, 120), emptyList(), image(200, 120, -1))
        val back = PsdReader.read(bytes)
        back.width shouldBe 200
        back.height shouldBe 120
    }

    @Test
    fun `layers come back, in the order they were written`() {
        val bytes = write(
            PsdLayerSource("زیر", 0, 0, image(10, 10, -1)),
            PsdLayerSource("رو", 5, 5, image(10, 10, -1)),
        )
        val back = PsdReader.read(bytes)
        back.layers.size shouldBe 2
        back.layers.map { it.name } shouldBe listOf("زیر", "رو")
    }

    @Test
    fun `a persian layer name survives`() {
        val bytes = write(PsdLayerSource("متن فارسی ۱۲۳", 0, 0, image(4, 4, -1)))
        // The Pascal name field cannot hold Persian at all; every modern reader takes the name from
        // the unicode block, and writing only the Pascal one is why names come back as mojibake
        // from tools that were never tested outside Latin.
        PsdReader.read(bytes).layers.single().name shouldBe "متن فارسی ۱۲۳"
    }

    @Test
    fun `a layer's position survives`() {
        val bytes = write(PsdLayerSource("جابه‌جا", 17, 23, image(12, 9, -1)))
        val layer = PsdReader.read(bytes).layers.single()
        layer.bounds.left shouldBe 17
        layer.bounds.top shouldBe 23
        layer.bounds.width shouldBe 12
        layer.bounds.height shouldBe 9
    }

    @Test
    fun `opacity survives`() {
        val bytes = write(PsdLayerSource("نیمه", 0, 0, image(4, 4, -1), opacity = 0.5f))
        PsdReader.read(bytes).layers.single().opacity shouldBe 0.5f.plusOrMinus(0.01f)
    }

    @Test
    fun `every blend mode round trips`() {
        for (mode in BlendMode.entries) {
            val bytes = write(PsdLayerSource("ترکیب", 0, 0, image(4, 4, -1), blendMode = mode))
            // A key of the wrong length shifts every field after it, so a typo does not produce a
            // wrong blend — it produces an unreadable file.
            PsdReader.read(bytes).layers.single().blendMode shouldBe mode
        }
    }

    @Test
    fun `a hidden layer comes back hidden`() {
        val bytes = write(PsdLayerSource("پنهان", 0, 0, image(4, 4, -1), visible = false))
        // The flag means "hidden", which is the opposite of what the field's name suggests — and
        // getting it backwards makes every layer of an exported file invisible.
        PsdReader.read(bytes).layers.single().visible shouldBe false
    }

    @Test
    fun `a clipped layer comes back clipped`() {
        val bytes = write(
            PsdLayerSource("پایه", 0, 0, image(4, 4, -1)),
            PsdLayerSource("بریده", 0, 0, image(4, 4, -1), clipped = true),
        )
        PsdReader.read(bytes).layers.last().clipping shouldBe true
    }

    @Test
    fun `pixels survive`() {
        val bytes = write(PsdLayerSource("رنگی", 0, 0, image(8, 8, 0xFF3366CC.toInt())))
        val layer = PsdReader.read(bytes).layers.single()

        val red = layer.channels.first { it.channel == PsdChannel.RED }
        val green = layer.channels.first { it.channel == PsdChannel.GREEN }
        val blue = layer.channels.first { it.channel == PsdChannel.BLUE }
        (red.samples[0].toInt() and 0xFF) shouldBe 0x33
        (green.samples[0].toInt() and 0xFF) shouldBe 0x66
        (blue.samples[0].toInt() and 0xFF) shouldBe 0xCC
    }

    @Test
    fun `transparency survives`() {
        val bytes = write(PsdLayerSource("نیمه‌شفاف", 0, 0, image(4, 4, 0x80FF0000.toInt())))
        val alpha = PsdReader.read(bytes).layers.single().channels.first { it.channel == PsdChannel.ALPHA }
        (alpha.samples[0].toInt() and 0xFF) shouldBe 0x80
    }

    @Test
    fun `a file with no layers still opens`() {
        val bytes = PsdWriter.write(document(), emptyList(), image(64, 48, -1))
        // Every flattened export goes down this path, and a zero-length layer section written
        // wrongly is a file that opens as noise.
        PsdReader.read(bytes).layers.size shouldBe 0
    }

    @Test
    fun `the writer says what it cannot carry`() {
        val withText = document().copy(
            layers = listOf(
                Layer.Text(
                    LayerId("t"),
                    ir.pixellab.core.model.TextSpec("سلام", ir.pixellab.core.model.FontRef("A")),
                ),
                Layer.Shape(
                    LayerId("s"),
                    ShapeGeometry.Rectangle(Vec2(10f, 10f)),
                    style = ir.pixellab.core.model.Style(
                        effects = listOf(ir.pixellab.core.model.Effect.DropShadow()),
                    ),
                ),
            ),
        )
        val notes = PsdWriter.describe(withText)
        // Effects are deliberately baked rather than written as live styles: a style Photoshop
        // cannot round-trip exactly is worse than none, because the user sees a shadow at the wrong
        // distance and cannot tell which tool is wrong. Saying so is the whole point.
        (notes.any { "متن" in it } ) shouldBe true
        (notes.any { "افکت" in it }) shouldBe true
    }
}
