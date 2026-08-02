package ir.pixellab.engine.android

import io.kotest.matchers.shouldBe
import ir.pixellab.core.fonts.FontFile
import ir.pixellab.core.fonts.FontScanner
import ir.pixellab.core.model.FontRef
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.TextSpec
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Measurement, which the selection chrome and the renderer both depend on.
 *
 * When these two disagree the handles are drawn somewhere near the letters instead of around them —
 * the single most common visible flaw in a mobile editor, and one that no amount of correct
 * rendering hides.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LayerMeasureTest {

    private fun realFont(): FontFile? {
        val root = System.getProperty("pixellab.samples")?.let(::File) ?: return null
        if (!root.isDirectory) return null
        val paths = root.walkTopDown().maxDepth(6)
            .filter { it.isFile && it.extension.lowercase() in FontLibrary.FONT_EXTENSIONS }
            .map { it.absolutePath }
            .toList()
            .sorted()
        if (paths.isEmpty()) return null
        return FontScanner { path -> runCatching { File(path).readBytes() }.getOrNull() }
            .scan(paths).fonts.firstOrNull()
    }

    private fun measurer(font: FontFile?) = LayerMeasure().apply {
        fonts = FontResolver { font }
    }

    private fun text(id: String, string: String, size: Float = 64f) = Layer.Text(
        id = LayerId(id),
        spec = TextSpec(text = string, font = FontRef(family = "any"), size = size),
    )

    @Test
    fun `a shape measures its declared size`() {
        val measure = measurer(null)
        val box = measure.of(
            Layer.Shape(LayerId("s"), ShapeGeometry.Rectangle(Vec2(400f, 250f))),
        )
        box.width shouldBe 400f
        box.height shouldBe 250f
    }

    @Test
    fun `a degenerate shape still has something to grab`() {
        val measure = measurer(null)
        // A line drawn from a point to itself is a real thing to have on screen mid-edit. A zero
        // box gives a selection with no grabbable edge, which reads as the layer being gone.
        val box = measure.of(
            Layer.Shape(LayerId("s"), ShapeGeometry.Line(Vec2(50f, 50f), Vec2(50f, 50f))),
        )
        (box.width > 0f && box.height > 0f) shouldBe true
    }

    @Test
    fun `text with no resolved font falls back rather than measuring to nothing`() {
        val measure = measurer(null)
        val box = measure.of(text("t", "سلام"))
        // Before the library has been scanned there is no font to shape with, and a zero box means
        // a layer the user cannot select or drag until they restart the app.
        (box.width > 0f && box.height > 0f) shouldBe true
    }

    @Test
    fun `longer text measures wider`() {
        val font = realFont()
        assumeTrue("no sample fonts on this machine", font != null)
        val measure = measurer(font)

        val short = measure.of(text("a", "س"))
        val long = measure.of(text("b", "سلام دنیای زیبا"))
        (long.width > short.width) shouldBe true
    }

    @Test
    fun `a larger size measures taller`() {
        val font = realFont()
        assumeTrue("no sample fonts on this machine", font != null)
        val measure = measurer(font)

        val small = measure.of(text("a", "سلام", size = 32f))
        val large = measure.of(text("b", "سلام", size = 128f))
        (large.height > small.height) shouldBe true
    }

    @Test
    fun `changing the string re-measures rather than returning the cached box`() {
        val font = realFont()
        assumeTrue("no sample fonts on this machine", font != null)
        val measure = measurer(font)

        val id = "t"
        val before = measure.of(text(id, "س"))
        // Same layer id, different content. Caching on the id alone leaves the handles around the
        // old word — which is exactly what a naive revision counter does when nothing bumps it.
        val after = measure.of(text(id, "سلام دنیای زیبا"))
        (after.width > before.width) shouldBe true
    }

    @Test
    fun `replacing the font source drops every cached measurement`() {
        val font = realFont()
        assumeTrue("no sample fonts on this machine", font != null)
        val measure = LayerMeasure()

        val layer = text("t", "سلام")
        val unresolved = measure.of(layer)
        measure.fonts = FontResolver { font }
        val resolved = measure.of(layer)
        // The scan lands after the first frame, so this is the ordinary case rather than an edge
        // one: the placeholder box has to be replaced by the real extent without the user acting.
        (resolved != unresolved) shouldBe true
    }

    @Test
    fun `a group covers what its children cover`() {
        val measure = measurer(null)
        val group = Layer.Group(
            id = LayerId("g"),
            children = listOf(
                Layer.Shape(
                    LayerId("a"),
                    ShapeGeometry.Rectangle(Vec2(100f, 100f)),
                    transform = Transform(translation = Vec2(0f, 0f)),
                ),
                Layer.Shape(
                    LayerId("b"),
                    ShapeGeometry.Rectangle(Vec2(100f, 100f)),
                    transform = Transform(translation = Vec2(300f, 200f)),
                ),
            ),
        )
        val box = measure.of(group)
        // A group has no extent of its own; selecting one and seeing a box around only the first
        // child is the giveaway that the children were measured in their own space.
        box.width shouldBe 400f
        box.height shouldBe 300f
    }

    @Test
    fun `an empty group still reports a box`() {
        val measure = measurer(null)
        val box = measure.of(Layer.Group(LayerId("g")))
        (box.width > 0f) shouldBe true
    }

    @Test
    fun `an image with no known asset falls back`() {
        val measure = measurer(null)
        val box = measure.of(Layer.Image(LayerId("i"), ir.pixellab.core.model.AssetId("missing")))
        (box.width > 0f && box.height > 0f) shouldBe true
    }

    @Test
    fun `an image measures the size its asset reports`() {
        val measure = LayerMeasure(images = ImageSizes { Vec2(800f, 600f) })
        val box = measure.of(Layer.Image(LayerId("i"), ir.pixellab.core.model.AssetId("photo")))
        box.width shouldBe 800f
        box.height shouldBe 600f
    }
}
