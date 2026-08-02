package ir.pixellab.core.editor

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.BlendMode
import ir.pixellab.core.model.CanvasSpec
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.DocumentId
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test

/**
 * The canvas itself, layer stacking, and the two opacities.
 *
 * These are the everyday operations — resize the canvas, move a layer one place, change how it
 * blends — and each of them has exactly one way to go wrong quietly. A resize that forgets to move
 * the artwork rearranges the design; a stack move that escapes its group silently unclips a layer;
 * and confusing the two opacities takes the stroke away with the fill.
 */
class EditorCanvasTest {

    private val boxBounds = LayerBounds { Rect(0f, 0f, 200f, 100f) }

    private fun box(id: String, at: Vec2 = Vec2.ZERO) = Layer.Shape(
        id = LayerId(id),
        geometry = ShapeGeometry.Rectangle(Vec2(200f, 100f)),
        name = id,
        transform = Transform(translation = at),
    )

    private fun editor(vararg layers: Layer, width: Int = 1000, height: Int = 1000) = Editor(
        Document(id = DocumentId("d"), canvas = CanvasSpec(width, height), layers = layers.toList()),
        boxBounds,
    )

    private fun at(e: Editor, id: String): Vec2 =
        e.state.document.findLayer(LayerId(id))!!.transform.translation

    // ---- canvas size ---------------------------------------------------------------------------

    @Test
    fun `growing from the top left leaves the artwork where it was`() {
        val e = editor(box("a", at = Vec2(100f, 200f)))
        e.resizeCanvas(1500, 2000, CanvasAnchor.TOP_LEFT)

        e.state.document.canvas.width shouldBe 1500
        at(e, "a") shouldBe Vec2(100f, 200f)
    }

    @Test
    fun `growing from the centre splits the new space on both sides`() {
        val e = editor(box("a", at = Vec2(100f, 200f)))
        e.resizeCanvas(1400, 1600, CanvasAnchor.CENTER)
        // 400 wider and 600 taller, half of each added on the leading side.
        at(e, "a") shouldBe Vec2(300f, 500f)
    }

    @Test
    fun `shrinking from the centre takes half off each side`() {
        val e = editor(box("a", at = Vec2(500f, 500f)))
        e.resizeCanvas(800, 800, CanvasAnchor.CENTER)
        // The same arithmetic as growing, with a negative difference — which is the reason the
        // anchor is a fraction rather than a direction with a sign test.
        at(e, "a") shouldBe Vec2(400f, 400f)
    }

    @Test
    fun `an anchor on the far edge pins the artwork to it`() {
        val e = editor(box("a", at = Vec2(0f, 0f)))
        e.resizeCanvas(1600, 1000, CanvasAnchor.RIGHT)
        at(e, "a").x shouldBe 600f
        at(e, "a").y shouldBe 0f
    }

    @Test
    fun `resizing only moves top level layers`() {
        val e = editor(Layer.Group(id = LayerId("g"), children = listOf(box("child", at = Vec2(50f, 50f)))))
        e.resizeCanvas(1200, 1000, CanvasAnchor.RIGHT)
        // The group moved; moving the child as well would move it twice, because a child's
        // transform is already relative to its parent.
        at(e, "g") shouldBe Vec2(200f, 0f)
        at(e, "child") shouldBe Vec2(50f, 50f)
    }

    @Test
    fun `resizing to the same size does nothing at all`() {
        val e = editor(box("a", at = Vec2(10f, 10f)))
        val before = e.undoDepth
        e.resizeCanvas(1000, 1000, CanvasAnchor.TOP_LEFT)
        // Not merely harmless: recording an undo step for a no-op means the user presses undo and
        // watches nothing happen.
        e.undoDepth shouldBe before
    }

    @Test
    fun `a resize is one undo step and comes back whole`() {
        val e = editor(box("a", at = Vec2(100f, 100f)))
        e.resizeCanvas(2000, 2000, CanvasAnchor.CENTER)
        e.undo()
        e.state.document.canvas.width shouldBe 1000
        at(e, "a") shouldBe Vec2(100f, 100f)
    }

    // ---- crop ----------------------------------------------------------------------------------

    @Test
    fun `cropping moves the artwork with the new origin`() {
        val e = editor(box("a", at = Vec2(300f, 400f)))
        e.cropCanvas(Rect(200f, 200f, 700f, 900f))

        e.state.document.canvas.width shouldBe 500
        e.state.document.canvas.height shouldBe 700
        at(e, "a") shouldBe Vec2(100f, 200f)
    }

    @Test
    fun `a crop rounds outwards`() {
        val e = editor(box("a"))
        e.cropCanvas(Rect(10.4f, 10.6f, 100.1f, 100.9f))
        // Rounding to nearest would clip a pixel off an edge the user had aligned something to, and
        // there is no recovering the content afterwards except by undoing the whole crop.
        e.state.document.canvas.width shouldBe 91
        e.state.document.canvas.height shouldBe 91
        at(e, "a") shouldBe Vec2(-10f, -10f)
    }

    @Test
    fun `a crop smaller than a pixel rounds out to one`() {
        val e = editor(box("a"))
        e.cropCanvas(Rect(10f, 10f, 10.2f, 10.2f))
        // Rounding outwards has a floor of its own: a rectangle that covers part of one pixel
        // covers that pixel, and a zero-sized canvas is not a value the model accepts.
        e.state.document.canvas.width shouldBe 1
        e.state.document.canvas.height shouldBe 1
    }

    @Test
    fun `an inverted crop is refused`() {
        val e = editor(box("a"))
        // A drag that ended left of where it started arrives here inverted, and the arithmetic
        // would otherwise produce a negative size that the canvas rejects with an exception.
        e.cropCanvas(Rect(100f, 100f, 10f, 10f))
        e.state.document.canvas.width shouldBe 1000
    }

    // ---- stacking ------------------------------------------------------------------------------

    @Test
    fun `raising moves a layer one place towards the front`() {
        val e = editor(box("a"), box("b"), box("c"))
        e.raiseLayer(LayerId("a")) shouldBe true
        e.state.document.layers.map { it.id.value } shouldBe listOf("b", "a", "c")
    }

    @Test
    fun `lowering moves a layer one place towards the back`() {
        val e = editor(box("a"), box("b"), box("c"))
        e.lowerLayer(LayerId("c")) shouldBe true
        e.state.document.layers.map { it.id.value } shouldBe listOf("a", "c", "b")
    }

    @Test
    fun `a layer at the front cannot be raised further`() {
        val e = editor(box("a"), box("b"))
        val before = e.undoDepth
        e.raiseLayer(LayerId("b")) shouldBe false
        // And it does not cost an undo step either, or the stack fills with presses that did nothing.
        e.undoDepth shouldBe before
    }

    @Test
    fun `raising inside a group stays inside the group`() {
        val e = editor(
            Layer.Group(id = LayerId("g"), children = listOf(box("x"), box("y"))),
            box("outside"),
        )
        e.raiseLayer(LayerId("x")) shouldBe true
        val group = e.state.document.layers.first() as Layer.Group
        group.children.map { it.id.value } shouldBe listOf("y", "x")
    }

    @Test
    fun `a layer at the top of a group does not escape it`() {
        val e = editor(Layer.Group(id = LayerId("g"), children = listOf(box("x"), box("y"))), box("outside"))
        // Escaping silently would unclip, unmask and unblend the layer, all without the user having
        // asked for any of it.
        e.raiseLayer(LayerId("y")) shouldBe false
        e.state.document.layers.map { it.id.value } shouldBe listOf("g", "outside")
    }

    // ---- reparenting ---------------------------------------------------------------------------

    @Test
    fun `moving into a group puts the layer on top of it`() {
        val e = editor(Layer.Group(id = LayerId("g"), children = listOf(box("x"))), box("a"))
        e.moveIntoGroup(LayerId("a"), LayerId("g")) shouldBe true

        e.state.document.layers.map { it.id.value } shouldBe listOf("g")
        (e.state.document.layers.first() as Layer.Group).children.map { it.id.value } shouldBe listOf("x", "a")
        e.state.selection.primary shouldBe LayerId("a")
    }

    @Test
    fun `a group cannot be moved into itself or into its own child`() {
        val inner = Layer.Group(id = LayerId("inner"), children = listOf(box("x")))
        val e = editor(Layer.Group(id = LayerId("outer"), children = listOf(inner)))

        e.moveIntoGroup(LayerId("outer"), LayerId("outer")) shouldBe false
        // The document would stop being a tree, and every walk over it would then run forever.
        e.moveIntoGroup(LayerId("outer"), LayerId("inner")) shouldBe false
    }

    @Test
    fun `moving out of a group leaves the layer directly above it`() {
        val e = editor(
            box("below"),
            Layer.Group(id = LayerId("g"), children = listOf(box("x"), box("y"))),
            box("above"),
        )
        e.moveOutOfGroup(LayerId("y")) shouldBe true
        // Above the group, not below it: the layer was drawn on top of the group's contents a
        // moment ago, and dropping it underneath them makes it disappear.
        e.state.document.layers.map { it.id.value } shouldBe listOf("below", "g", "y", "above")
        (e.state.document.layers[1] as Layer.Group).children.map { it.id.value } shouldBe listOf("x")
    }

    @Test
    fun `a top level layer has no group to leave`() {
        val e = editor(box("a"))
        e.moveOutOfGroup(LayerId("a")) shouldBe false
    }

    // ---- the two opacities ---------------------------------------------------------------------

    @Test
    fun `fill opacity is separate from layer opacity`() {
        val e = editor(box("a"))
        e.setLayerOpacity(LayerId("a"), 0.5f)
        e.setFillOpacity(LayerId("a"), 0f)

        val layer = e.state.document.findLayer(LayerId("a"))!!
        // The hollow-title trick: the fill goes to nothing and every effect stays at full strength.
        layer.opacity shouldBe 0.5f.plusOrMinus(1e-4f)
        layer.style.fillOpacity shouldBe 0f
    }

    @Test
    fun `a scrub of either opacity is one undo step`() {
        val e = editor(box("a"))
        val before = e.undoDepth
        for (step in 1..10) e.setFillOpacity(LayerId("a"), 1f - step / 10f, continuous = true)
        e.endScrub()
        e.undoDepth shouldBe before + 1
    }

    @Test
    fun `blend mode is a plain edit`() {
        val e = editor(box("a"))
        e.setLayerBlendMode(LayerId("a"), BlendMode.MULTIPLY)
        e.state.document.findLayer(LayerId("a"))!!.blendMode shouldBe BlendMode.MULTIPLY
        e.undo()
        e.state.document.findLayer(LayerId("a"))!!.blendMode shouldBe BlendMode.NORMAL
    }

    @Test
    fun `every blend mode has its own Persian name`() {
        val names = BlendMode.entries.map { it.persianLabel }
        // A picker with two identical entries is one the user cannot use; and there are 27 of them,
        // which is exactly the sort of list where a duplicate goes unnoticed.
        names.distinct().size shouldBe BlendMode.entries.size
        names.none { it.isBlank() } shouldBe true
    }

    @Test
    fun `every anchor has its own Persian name and offset`() {
        CanvasAnchor.entries.map { it.persianLabel }.distinct().size shouldBe CanvasAnchor.entries.size
        CanvasAnchor.entries
            .map { it.offsetFor(100, 100, 300, 300) }
            .distinct().size shouldBe CanvasAnchor.entries.size
    }
}
