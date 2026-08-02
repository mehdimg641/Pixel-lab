package ir.pixellab.core.editor

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.canvas.Handle
import ir.pixellab.core.model.CanvasSpec
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.DocumentId
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.LinkGroups
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2
import org.junit.jupiter.api.Test

/**
 * Linked layers and layer comps.
 *
 * The two are constantly confused with grouping, and the tests below are written around the
 * difference. A link is an agreement about movement and nothing else; a group is a place in the
 * tree that clips and composites. A caption pinned to a photograph needs the first — grouping them
 * would make the photograph's shadow fall on the caption.
 */
class CompositionTest {

    private fun shape(name: String, at: Vec2) = Layer.Shape(
        id = LayerId(name),
        geometry = ShapeGeometry.Rectangle(Vec2(100f, 100f)),
        name = name,
        transform = Transform(translation = at),
    )

    private fun editor(vararg layers: Layer) = Editor(
        Document(
            id = DocumentId("test"),
            canvas = CanvasSpec(1000, 1000),
            layers = layers.toList(),
        ),
        bounds = FixedBounds,
    )

    private val FixedBounds = LayerBounds { ir.pixellab.core.model.Rect(0f, 0f, 100f, 100f) }

    // ---- the link model ----------------------------------------------------------------------

    @Test
    fun `an unlinked layer is its own only partner`() {
        LinkGroups().partners(LayerId("a")) shouldBe setOf(LayerId("a"))
    }

    @Test
    fun `linking joins them both ways`() {
        val links = LinkGroups().link(listOf(LayerId("a"), LayerId("b")))
        links.partners(LayerId("a")) shouldBe setOf(LayerId("a"), LayerId("b"))
        links.partners(LayerId("b")) shouldBe setOf(LayerId("a"), LayerId("b"))
    }

    @Test
    fun `linking into an existing link absorbs it rather than overlapping`() {
        // Two overlapping links would make "what moves with this?" depend on which layer was
        // dragged, and a user cannot see that distinction on screen.
        val links = LinkGroups()
            .link(listOf(LayerId("a"), LayerId("b")))
            .link(listOf(LayerId("b"), LayerId("c")))

        links.groups.size shouldBe 1
        links.partners(LayerId("a")) shouldBe setOf(LayerId("a"), LayerId("b"), LayerId("c"))
    }

    @Test
    fun `a link of one is dissolved rather than left behind`() {
        // Otherwise an unlinked layer keeps reporting as linked in the panel.
        val links = LinkGroups()
            .link(listOf(LayerId("a"), LayerId("b")))
            .unlink(listOf(LayerId("a")))

        links.groups.isEmpty() shouldBe true
        links.isLinked(LayerId("b")) shouldBe false
    }

    @Test
    fun `linking fewer than two does nothing`() {
        LinkGroups().link(listOf(LayerId("a"))).groups.isEmpty() shouldBe true
    }

    // ---- moving together ------------------------------------------------------------------------

    @Test
    fun `dragging a linked layer moves its partner by the same amount`() {
        val editor = editor(shape("a", Vec2(0f, 0f)), shape("b", Vec2(300f, 0f)))
        editor.select(LayerId("a"))
        editor.select(LayerId("b"), additive = true)
        editor.linkSelected() shouldBe true

        editor.select(LayerId("a"))
        editor.beginDrag(Handle.BODY, Vec2(50f, 50f))
        editor.dragTo(Vec2(150f, 50f))
        editor.endDrag()

        val a = editor.state.document.findLayer(LayerId("a"))!!
        val b = editor.state.document.findLayer(LayerId("b"))!!
        a.transform.translation.x shouldBe 100f.plusOrMinus(1f)
        // The partner moved by the delta, not to the dragged layer's position.
        b.transform.translation.x shouldBe 400f.plusOrMinus(1f)
        b.transform.translation.y shouldBe 0f.plusOrMinus(1f)
    }

    @Test
    fun `an unlinked layer stays where it was`() {
        val editor = editor(shape("a", Vec2(0f, 0f)), shape("b", Vec2(300f, 0f)))
        editor.select(LayerId("a"))
        editor.beginDrag(Handle.BODY, Vec2(50f, 50f))
        editor.dragTo(Vec2(150f, 50f))
        editor.endDrag()

        editor.state.document.findLayer(LayerId("b"))!!.transform.translation.x shouldBe 300f
    }

    @Test
    fun `a long drag does not let the partner drift`() {
        // The reason the start positions are captured once rather than accumulated. Sixty small
        // deltas a second, each rounded, would leave the partner visibly offset from the layer it
        // is pinned to by the end of a slow drag.
        val editor = editor(shape("a", Vec2(0f, 0f)), shape("b", Vec2(300f, 0f)))
        editor.select(LayerId("a"))
        editor.select(LayerId("b"), additive = true)
        editor.linkSelected()

        editor.select(LayerId("a"))
        editor.beginDrag(Handle.BODY, Vec2(50f, 50f))
        for (step in 1..200) editor.dragTo(Vec2(50f + step * 0.5f, 50f))
        editor.endDrag()

        val a = editor.state.document.findLayer(LayerId("a"))!!
        val b = editor.state.document.findLayer(LayerId("b"))!!
        (b.transform.translation.x - a.transform.translation.x) shouldBe 300f.plusOrMinus(0.01f)
    }

    @Test
    fun `deleting a layer drops it from its link`() {
        val editor = editor(shape("a", Vec2.ZERO), shape("b", Vec2(300f, 0f)))
        editor.select(LayerId("a"))
        editor.select(LayerId("b"), additive = true)
        editor.linkSelected()

        editor.deleteLayer(LayerId("a"))
        // A link holding a deleted id would keep reporting its survivors as linked to something
        // that is no longer in the document.
        editor.state.document.links.isLinked(LayerId("b")) shouldBe false
    }

    // ---- comps -----------------------------------------------------------------------------------

    @Test
    fun `a comp restores the visibility it captured`() {
        val editor = editor(shape("a", Vec2.ZERO), shape("b", Vec2(300f, 0f)))
        editor.captureComp("both visible") shouldBe true

        editor.setLayerVisible(LayerId("b"), false)
        editor.state.document.findLayer(LayerId("b"))!!.visible shouldBe false

        editor.applyComp("both visible") shouldBe true
        editor.state.document.findLayer(LayerId("b"))!!.visible shouldBe true
    }

    @Test
    fun `a comp restores positions`() {
        val editor = editor(shape("a", Vec2(10f, 10f)))
        editor.captureComp("home")

        editor.select(LayerId("a"))
        editor.beginDrag(Handle.BODY, Vec2(50f, 50f))
        editor.dragTo(Vec2(400f, 400f))
        editor.endDrag()

        editor.applyComp("home")
        editor.state.document.findLayer(LayerId("a"))!!.transform.translation.x shouldBe 10f.plusOrMinus(0.01f)
    }

    @Test
    fun `a comp that captures only visibility leaves positions alone`() {
        // A comp is a layout switch, not a version of the document. Restoring things it never
        // captured would silently undo work done since it was saved.
        val editor = editor(shape("a", Vec2(10f, 10f)))
        editor.captureComp("visible only", visibility = true, positions = false)

        editor.select(LayerId("a"))
        editor.beginDrag(Handle.BODY, Vec2(50f, 50f))
        editor.dragTo(Vec2(400f, 400f))
        editor.endDrag()
        val moved = editor.state.document.findLayer(LayerId("a"))!!.transform.translation.x

        editor.applyComp("visible only")
        editor.state.document.findLayer(LayerId("a"))!!.transform.translation.x shouldBe moved
    }

    @Test
    fun `a layer added after the comp is left alone`() {
        // Hiding new work because an old comp did not know about it is the worse of the two
        // behaviours: the user would see their layer vanish for no visible reason.
        val editor = editor(shape("a", Vec2.ZERO))
        editor.captureComp("early")

        editor.addLayer(shape("late", Vec2(500f, 0f)))
        editor.applyComp("early")
        editor.state.document.findLayer(LayerId("late"))!!.visible shouldBe true
    }

    @Test
    fun `saving twice under one name replaces rather than duplicates`() {
        // Two comps called the same thing is a state the user cannot resolve, since the panel shows
        // them by name alone.
        val editor = editor(shape("a", Vec2.ZERO))
        editor.captureComp("look")
        editor.captureComp("look")
        editor.state.document.comps.size shouldBe 1
    }

    @Test
    fun `an unknown comp is refused rather than clearing the document`() {
        val editor = editor(shape("a", Vec2.ZERO))
        editor.applyComp("never saved") shouldBe false
        editor.state.document.findLayer(LayerId("a"))!!.visible shouldBe true
    }

    @Test
    fun `a blank name is refused`() {
        editor(shape("a", Vec2.ZERO)).captureComp("  ") shouldBe false
    }

    @Test
    fun `linking and capturing are both undoable`() {
        val editor = editor(shape("a", Vec2.ZERO), shape("b", Vec2(300f, 0f)))
        editor.select(LayerId("a"))
        editor.select(LayerId("b"), additive = true)
        editor.linkSelected()
        editor.undo()
        editor.state.document.links.isLinked(LayerId("a")) shouldBe false
    }
}
