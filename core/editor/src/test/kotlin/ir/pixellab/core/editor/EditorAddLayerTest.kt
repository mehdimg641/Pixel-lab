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
import ir.pixellab.core.model.with
import org.junit.jupiter.api.Test

/**
 * Creating layers.
 *
 * Until this existed the app could edit a document but never grow one — the text tool had nothing
 * to call. The rules that matter are that a new layer becomes the selection, that adding is one
 * undo step, and that the id it gets is one nothing else is using.
 */
class EditorAddLayerTest {

    private val boxBounds = LayerBounds { Rect(0f, 0f, 200f, 100f) }

    private fun box(id: String) = Layer.Shape(
        id = LayerId(id),
        geometry = ShapeGeometry.Rectangle(Vec2(200f, 100f)),
        name = id,
    )

    private fun editor(vararg layers: Layer) = Editor(
        Document(id = DocumentId("d"), canvas = CanvasSpec(1000, 1000), layers = layers.toList()),
        boxBounds,
    )

    @Test
    fun `an added layer goes on top and becomes the selection`() {
        val e = editor(box("a"))
        e.addLayer(box("b"))

        // Last in the list is painted last, which is what "on top" means here.
        e.state.document.layers.map { it.id.value } shouldBe listOf("a", "b")
        // Not a convenience: a new layer lands in the middle of the canvas with nothing to
        // distinguish it, and the next drag has to move it rather than whatever was selected before.
        e.state.selection.primary shouldBe LayerId("b")
    }

    @Test
    fun `adding is one undo step`() {
        val e = editor(box("a"))
        e.addLayer(box("b"))
        e.undo()

        // A mis-tap on the text tool must cost one press, not leave an empty layer behind.
        e.state.document.layers.map { it.id.value } shouldBe listOf("a")
        e.state.canRedo shouldBe true
    }

    @Test
    fun `a new id avoids every id already in the document`() {
        val e = editor(box("text-1"), box("text-2"))
        e.nextLayerId("text") shouldBe LayerId("text-3")
    }

    @Test
    fun `a new id avoids ids inside groups too`() {
        val group = Layer.Group(id = LayerId("g"), children = listOf(box("text-1")))
        val e = editor(group)
        // Counting top-level layers would give "text-1" here and quietly produce two layers that
        // answer to the same id — the state where an edit lands on the wrong one.
        e.nextLayerId("text") shouldBe LayerId("text-2")
    }

    @Test
    fun `a new id is free when nothing shares the prefix`() {
        editor(box("shape-1")).nextLayerId("text") shouldBe LayerId("text-1")
    }

    @Test
    fun `duplicating twice does not collide after a delete`() {
        val e = editor(box("shape-1"))
        e.duplicateLayer(LayerId("shape-1"), e.nextLayerId("shape-1"))
        e.deleteLayer(LayerId("shape-1-1"))
        val second = e.nextLayerId("shape-1")
        // The old counting scheme reused "shape-1-1" here, because the count had gone back down.
        e.duplicateLayer(LayerId("shape-1"), second)
        e.state.document.walk().map { it.id.value }.toSet().size shouldBe
            e.state.document.walk().count()
    }

    @Test
    fun `replacing a layer keeps its place and records one step`() {
        val e = editor(box("a"), box("b"))
        e.replaceLayer(LayerId("a")) { (it as Layer.Shape).copy(geometry = ShapeGeometry.Ellipse(Vec2(50f, 50f))) }

        e.state.document.layers.map { it.id.value } shouldBe listOf("a", "b")
        (e.state.document.findLayer(LayerId("a")) as Layer.Shape).geometry shouldBe
            ShapeGeometry.Ellipse(Vec2(50f, 50f))
        e.undo()
        (e.state.document.findLayer(LayerId("a")) as Layer.Shape).geometry shouldBe
            ShapeGeometry.Rectangle(Vec2(200f, 100f))
    }

    @Test
    fun `replacing a layer that is gone changes nothing`() {
        val e = editor(box("a"))
        e.replaceLayer(LayerId("missing")) { it.with(name = "x") }
        // Recording history for an edit that did not happen makes the next undo revert the *previous*
        // edit instead, which is the worst kind of undo bug: it looks like data loss.
        e.state.canUndo shouldBe false
    }

    @Test
    fun `an added layer keeps the transform it was given`() {
        val e = editor()
        e.addLayer(box("a").copy(transform = Transform(translation = Vec2(400f, 450f))))
        e.state.document.layers.single().transform.translation shouldBe Vec2(400f, 450f)
    }
}
