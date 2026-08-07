package ir.pixellab.core.editor

import io.kotest.matchers.shouldBe
import ir.pixellab.core.model.CanvasSpec
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.DocumentId
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.model.VectorMask
import org.junit.jupiter.api.Test

/**
 * Structure: grouping, clipping, masking and instancing.
 *
 * These four are what a document is made of once it is more than a flat pile of layers, and every
 * one of them is a thing the user does dozens of times in a single cover. The rules that matter are
 * about *position* — a group that jumps to the front reorders the artwork, and a released clip that
 * leaves the layer somewhere new is worse than one that does nothing.
 */
class EditorStructureTest {

    private val boxBounds = LayerBounds { Rect(0f, 0f, 200f, 100f) }

    private fun box(id: String, at: Vec2 = Vec2.ZERO) = Layer.Shape(
        id = LayerId(id),
        geometry = ShapeGeometry.Rectangle(Vec2(200f, 100f)),
        name = id,
        transform = Transform(translation = at),
    )

    private fun editor(vararg layers: Layer) = Editor(
        Document(id = DocumentId("d"), canvas = CanvasSpec(1000, 1000), layers = layers.toList()),
        boxBounds,
    )

    // ---- grouping ----------------------------------------------------------------------------

    @Test
    fun `grouping wraps the chosen layers and selects the group`() {
        val e = editor(box("a"), box("b"), box("c"))
        e.groupLayers(listOf(LayerId("a"), LayerId("b")), LayerId("g"))

        e.state.document.layers.map { it.id.value } shouldBe listOf("g", "c")
        (e.state.document.layers[0] as Layer.Group).children.map { it.id.value } shouldBe listOf("a", "b")
        e.state.selection.primary shouldBe LayerId("g")
    }

    @Test
    fun `the group takes the place of its topmost member`() {
        val e = editor(box("a"), box("b"), box("c"))
        e.groupLayers(listOf(LayerId("a"), LayerId("c")), LayerId("g"))
        // Appending the group instead would put it in front of "b", changing what covers what —
        // and the user grouped those layers precisely because of how they already sat.
        e.state.document.layers.map { it.id.value } shouldBe listOf("b", "g")
    }

    @Test
    fun `grouping keeps the members in their painting order`() {
        val e = editor(box("a"), box("b"), box("c"))
        e.groupLayers(listOf(LayerId("c"), LayerId("a")), LayerId("g"))
        // The order comes from the document, not from the order the user tapped them in.
        (e.state.document.layers.last() as Layer.Group).children.map { it.id.value } shouldBe listOf("a", "c")
    }

    @Test
    fun `grouping nothing changes nothing`() {
        val e = editor(box("a"))
        e.groupLayers(listOf(LayerId("missing")), LayerId("g")) shouldBe null
        // Recording history for an edit that did not happen makes the next undo revert the
        // *previous* edit instead.
        e.state.canUndo shouldBe false
    }

    @Test
    fun `ungrouping leaves the children where the group was`() {
        val e = editor(box("below"), Layer.Group(LayerId("g"), children = listOf(box("a"), box("b"))))
        e.ungroup(LayerId("g"))
        e.state.document.layers.map { it.id.value } shouldBe listOf("below", "a", "b")
        e.state.selection.ids shouldBe listOf(LayerId("a"), LayerId("b"))
    }

    @Test
    fun `grouping and ungrouping is one round trip`() {
        val e = editor(box("a"), box("b"))
        e.groupLayers(listOf(LayerId("a"), LayerId("b")), LayerId("g"))
        e.ungroup(LayerId("g"))
        e.state.document.layers.map { it.id.value } shouldBe listOf("a", "b")
    }

    @Test
    fun `grouping is one undo step`() {
        val e = editor(box("a"), box("b"))
        e.groupLayers(listOf(LayerId("a"), LayerId("b")), LayerId("g"))
        e.undo()
        e.state.document.layers.map { it.id.value } shouldBe listOf("a", "b")
    }

    @Test
    fun `a group's isolation can be switched without touching its children`() {
        val e = editor(Layer.Group(LayerId("g"), children = listOf(box("a"))))
        e.setGroupPassThrough(LayerId("g"), passThrough = false)
        val group = e.state.document.findLayer(LayerId("g")) as Layer.Group
        group.passThrough shouldBe false
        group.children.size shouldBe 1
    }

    // ---- clipping ----------------------------------------------------------------------------

    @Test
    fun `clipping a layer does not move it`() {
        val e = editor(box("base"), box("over", at = Vec2(40f, 30f)))
        e.setClipped(LayerId("over"), true)
        val over = e.state.document.findLayer(LayerId("over"))!!
        // Photoshop clips in place. A clip that repositioned the layer would be indistinguishable
        // from a bug at the moment the user is trying to line something up.
        over.clipped shouldBe true
        over.transform.translation shouldBe Vec2(40f, 30f)
        e.state.document.layers.map { it.id.value } shouldBe listOf("base", "over")
    }

    @Test
    fun `releasing a clip is one undo away`() {
        val e = editor(box("base"), box("over"))
        e.setClipped(LayerId("over"), true)
        e.setClipped(LayerId("over"), false)
        e.state.document.findLayer(LayerId("over"))!!.clipped shouldBe false
        e.undo()
        e.state.document.findLayer(LayerId("over"))!!.clipped shouldBe true
    }

    // ---- masks -------------------------------------------------------------------------------

    @Test
    fun `a vector mask attaches to the layer and can be taken off again`() {
        val e = editor(box("a"))
        e.setVectorMask(LayerId("a"), VectorMask(ShapeGeometry.Ellipse(Vec2(200f, 100f))))
        (e.state.document.findLayer(LayerId("a"))!!.vectorMask != null) shouldBe true

        e.setVectorMask(LayerId("a"), null)
        // Removing a mask has to be an edit like any other, or it cannot be undone — and a mask is
        // exactly the kind of thing a user removes by accident.
        e.state.document.findLayer(LayerId("a"))!!.vectorMask shouldBe null
        e.undo()
        (e.state.document.findLayer(LayerId("a"))!!.vectorMask != null) shouldBe true
    }

    // ---- instances ---------------------------------------------------------------------------

    @Test
    fun `an instance references its source rather than copying it`() {
        val e = editor(box("source", at = Vec2(20f, 20f)))
        e.addInstance(LayerId("source"), LayerId("copy"))

        val instance = e.state.document.findLayer(LayerId("copy")) as Layer.Instance
        instance.source shouldBe LayerId("source")
        // It starts on top of its source, which is where a duplicate starts too; the user drags it
        // where they want it, and the reference is what makes editing the original update it.
        instance.transform.translation shouldBe Vec2(20f, 20f)
        e.state.selection.primary shouldBe LayerId("copy")
    }

    @Test
    fun `editing the source is what an instance is for`() {
        val e = editor(box("source"))
        e.addInstance(LayerId("source"), LayerId("copy"))
        e.replaceLayer(LayerId("source")) {
            (it as Layer.Shape).copy(geometry = ShapeGeometry.Ellipse(Vec2(50f, 50f)))
        }
        // Nothing about the instance changed, and that is the point: it holds an id, not pixels.
        (e.state.document.findLayer(LayerId("copy")) as Layer.Instance).source shouldBe LayerId("source")
        (e.state.document.findLayer(LayerId("source")) as Layer.Shape).geometry shouldBe
            ShapeGeometry.Ellipse(Vec2(50f, 50f))
    }

    @Test
    fun `an instance of a layer that is not there is refused`() {
        val e = editor(box("a"))
        e.addInstance(LayerId("gone"), LayerId("copy")) shouldBe null
        e.state.document.layers.size shouldBe 1
    }
}
