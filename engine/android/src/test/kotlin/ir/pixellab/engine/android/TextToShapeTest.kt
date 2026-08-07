package ir.pixellab.engine.android

import io.kotest.matchers.shouldBe
import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.fonts.Script
import ir.pixellab.core.model.BlendMode
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.Style
import ir.pixellab.core.model.TextSpec
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Turning words into geometry.
 *
 * The conversion is a one-way door, so the tests are about what survives it. Two things matter: the
 * layer must look identical the instant after it is converted — same place, same style, same
 * effects — and the letters must come out joined, because a Persian headline whose letters have
 * separated is worse than no conversion at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "fa")
class TextToShapeTest {

    private fun stubFont() = FontFile(
        path = "/nonexistent/Stub.ttf",
        family = "Stub",
        subfamily = "Regular",
        postScriptName = "Stub-Regular",
        fullName = "Stub Regular",
        weight = 400,
        italic = false,
        axes = emptyMap(),
        features = emptySet(),
        script = Script.BOTH,
        hasPersianDigits = true,
        hasTatweel = true,
    )

    private val fonts = FontResolver { stubFont() }

    /** The layer's geometry is declared as the interface; every assertion here is about the path. */
    private fun pathOf(shape: Layer.Shape) = shape.geometry as ir.pixellab.core.model.ShapeGeometry.Path

    private fun text(
        string: String = "سلام دنیا",
        style: Style = Style.PLAIN_BLACK,
    ) = Layer.Text(
        id = LayerId("title"),
        spec = TextSpec(text = string, font = FontRef("Stub"), size = 72f),
        name = "تیتر",
        transform = Transform(translation = Vec2(120f, 240f)),
        opacity = 0.6f,
        blendMode = BlendMode.MULTIPLY,
        style = style,
    )

    @Test
    fun `a text layer becomes a shape with real geometry`() {
        val shape = TextToShape.convert(text(), fonts)!!
        pathOf(shape).contours.isNotEmpty() shouldBe true
        pathOf(shape).contours.all { it.nodes.size >= 2 } shouldBe true
    }

    @Test
    fun `nothing about how it looks changes at the moment of conversion`() {
        val source = text(
            style = Style(
                fill = Fill.Solid(Color.WHITE),
                fillOpacity = 0.5f,
                effects = listOf(Effect.Stroke(8f, Fill.Solid(Color.BLACK))),
            ),
        )
        val shape = TextToShape.convert(source, fonts)!!

        // If any of these were dropped the layer would visibly jump the instant the user converted,
        // which reads as the tool having broken their work rather than changed its representation.
        shape.transform shouldBe source.transform
        shape.opacity shouldBe source.opacity
        shape.blendMode shouldBe source.blendMode
        shape.name shouldBe source.name
        shape.style.effects.size shouldBe 1
        shape.style.fillOpacity shouldBe 0.5f
    }

    @Test
    fun `persian letters stay joined`() {
        // A joined run of four letters is one connected outline; glyph-by-glyph outlining would
        // give four separate ones. That is the failure this whole app exists to avoid, and it is
        // invisible in a thumbnail — it only shows once the shape is stroked or extruded.
        val joined = TextToShape.convert(text("سلام"), fonts)!!
        val separated = TextToShape.convert(text("س ل ا م"), fonts)!!
        (pathOf(joined).contours.size < pathOf(separated).contours.size) shouldBe true
    }

    @Test
    fun `empty and unresolvable text convert to nothing rather than to an invisible layer`() {
        TextToShape.convert(text("   "), fonts) shouldBe null
        TextToShape.convert(text(), FontResolver.NONE) shouldBe null
    }

    @Test
    fun `the offer matches what the conversion would do`() {
        // A button that is enabled and then does nothing is worse than one that is greyed out.
        TextToShape.canConvert(text(), fonts) shouldBe true
        TextToShape.canConvert(text("  "), fonts) shouldBe false
        TextToShape.canConvert(text(), FontResolver.NONE) shouldBe false
        TextToShape.canConvert(
            Layer.Shape(id = LayerId("s"), geometry = ir.pixellab.core.model.ShapeGeometry.Ellipse(Vec2(10f, 10f))),
            fonts,
        ) shouldBe false
    }

    @Test
    fun `a new id can be given so the original can be kept alongside`() {
        val shape = TextToShape.convert(text(), fonts, id = LayerId("title-outlined"))!!
        shape.id shouldBe LayerId("title-outlined")
    }
}
