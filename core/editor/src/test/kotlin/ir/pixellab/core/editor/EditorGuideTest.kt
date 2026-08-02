package ir.pixellab.core.editor

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.canvas.GridSpec
import ir.pixellab.core.canvas.SafeZone
import ir.pixellab.core.model.CanvasSpec
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.DocumentId
import ir.pixellab.core.model.Guide
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.model.decodeDocument
import ir.pixellab.core.model.encode
import org.junit.jupiter.api.Test

/**
 * Guides, the grid and the rulers, through the editor.
 *
 * The rule that shapes all of this is which side of the document boundary each thing falls on: a
 * guide records a decision about the *design* and is saved with it, while the grid records how
 * someone likes to work and is not. Getting that backwards means either losing margins on every
 * save or carrying a stranger's working preferences into your file.
 */
class EditorGuideTest {

    private val boxBounds = LayerBounds { Rect(0f, 0f, 200f, 100f) }

    private fun editor(width: Int = 1000, height: Int = 800) = Editor(
        Document(id = DocumentId("d"), canvas = CanvasSpec(width, height)),
        boxBounds,
    )

    @Test
    fun `a guide is added and comes back on the document`() {
        val e = editor()
        e.addGuide(Guide(vertical = true, position = 240f)) shouldBe 0
        e.state.document.guides.single().position shouldBe 240f
    }

    @Test
    fun `guides survive being saved`() {
        val e = editor()
        e.addGuide(Guide(vertical = true, position = 100f))
        e.addGuide(Guide(vertical = false, position = 50f, locked = true))

        // The whole reason they live on the document: a margin measured once should still be there
        // the next time the file is opened.
        val reopened = decodeDocument(e.state.document.encode())
        reopened.guides.size shouldBe 2
        reopened.guides[1].locked shouldBe true
        reopened.guides[1].vertical shouldBe false
    }

    @Test
    fun `the grid is not saved with the document`() {
        val e = editor()
        e.setGrid(GridSpec(spacing = 37f, visible = true))
        // A working preference, not part of the artwork — carrying it into the file would push it
        // onto whoever opens it next, where it means nothing.
        val encoded = e.state.document.encode()
        encoded.contains("37") shouldBe false
    }

    @Test
    fun `a guide drag is one undo step`() {
        val e = editor()
        e.addGuide(Guide(vertical = true, position = 100f))
        val before = e.undoDepth

        for (step in 1..20) e.moveGuide(0, 100f + step, continuous = true)
        e.endScrub()

        e.undoDepth shouldBe before + 1
        e.state.document.guides.single().position shouldBe 120f
        e.undo()
        e.state.document.guides.single().position shouldBe 100f
    }

    @Test
    fun `a locked guide cannot be moved or removed`() {
        val e = editor()
        e.addGuide(Guide(vertical = true, position = 100f, locked = true))
        e.moveGuide(0, 500f)
        e.removeGuide(0)
        // Locking one is exactly the instruction "do not let me lose this".
        e.state.document.guides.single().position shouldBe 100f
    }

    @Test
    fun `clearing keeps the locked ones`() {
        val e = editor()
        e.addGuide(Guide(vertical = true, position = 10f))
        e.addGuide(Guide(vertical = true, position = 20f, locked = true))
        e.addGuide(Guide(vertical = false, position = 30f))
        e.clearGuides()

        e.state.document.guides.size shouldBe 1
        e.state.document.guides.single().position shouldBe 20f
    }

    @Test
    fun `clearing nothing costs no undo step`() {
        val e = editor()
        val before = e.undoDepth
        e.clearGuides()
        e.undoDepth shouldBe before
    }

    @Test
    fun `an out of range index is ignored rather than crashing`() {
        val e = editor()
        e.moveGuide(3, 100f)
        e.removeGuide(-1)
        e.state.document.guides.isEmpty() shouldBe true
    }

    @Test
    fun `a guide layout divides the canvas evenly`() {
        val e = editor(width = 1200, height = 900)
        e.guideLayout(columns = 3, rows = 3)

        val vertical = e.state.document.guides.filter { it.vertical }.map { it.position }.sorted()
        val horizontal = e.state.document.guides.filter { !it.vertical }.map { it.position }.sorted()
        // Interior lines only: the canvas edges are already there and a guide on top of one is
        // clutter that cannot be seen or grabbed.
        vertical shouldBe listOf(400f, 800f)
        horizontal shouldBe listOf(300f, 600f)
    }

    @Test
    fun `a guide layout with a margin adds the four edges`() {
        val e = editor(width = 1000, height = 1000)
        e.guideLayout(columns = 2, rows = 1, margin = 50f)

        val vertical = e.state.document.guides.filter { it.vertical }.map { it.position }.sorted()
        // The two margins plus the single interior column line, which sits inside the margins
        // rather than across the whole canvas.
        vertical shouldBe listOf(50f, 500f, 950f)
    }

    @Test
    fun `running a layout twice replaces rather than accumulates`() {
        val e = editor(width = 1200, height = 900)
        e.guideLayout(columns = 3, rows = 1)
        e.guideLayout(columns = 4, rows = 1)

        // Changing your mind about the number of columns is the normal way this is used; appending
        // would leave the first attempt behind as clutter to clear by hand.
        e.state.document.guides.filter { it.vertical }.map { it.position }.sorted() shouldBe
            listOf(300f, 600f, 900f)
    }

    @Test
    fun `a layout keeps guides that were locked`() {
        val e = editor(width = 1200, height = 900)
        e.addGuide(Guide(vertical = true, position = 7f, locked = true))
        e.guideLayout(columns = 2, rows = 1)
        e.state.document.guides.any { it.position == 7f } shouldBe true
    }

    @Test
    fun `a guide can be grabbed within the touch radius`() {
        val e = editor()
        e.addGuide(Guide(vertical = true, position = 300f))
        e.addGuide(Guide(vertical = false, position = 100f))

        e.guideAt(Vec2(306f, 500f), tolerance = 10f) shouldBe 0
        e.guideAt(Vec2(500f, 104f), tolerance = 10f) shouldBe 1
        e.guideAt(Vec2(500f, 500f), tolerance = 10f) shouldBe null
    }

    @Test
    fun `the nearest guide wins when two are close together`() {
        val e = editor()
        e.addGuide(Guide(vertical = true, position = 300f))
        e.addGuide(Guide(vertical = true, position = 306f))
        e.guideAt(Vec2(305f, 0f), tolerance = 20f) shouldBe 1
    }

    @Test
    fun `locking every guide is one step and undoes together`() {
        val e = editor()
        e.addGuide(Guide(vertical = true, position = 10f))
        e.addGuide(Guide(vertical = false, position = 20f))
        val before = e.undoDepth

        e.setGuidesLocked(true)
        e.state.document.guides.all { it.locked } shouldBe true
        e.undoDepth shouldBe before + 1

        e.undo()
        e.state.document.guides.none { it.locked } shouldBe true
    }

    @Test
    fun `the rulers and the safe zone are view state, never a document edit`() {
        val e = editor()
        val before = e.undoDepth
        e.setRulersVisible(true)
        e.setSafeZone(SafeZone.STORY)
        e.setGrid(GridSpec(visible = true))

        e.state.showRulers shouldBe true
        e.state.safeZone shouldBe SafeZone.STORY
        e.state.grid.visible shouldBe true
        // None of the three describes the artwork, so none of them belongs on the undo stack.
        e.undoDepth shouldBe before
    }

    @Test
    fun `a dragged layer snaps to a guide`() {
        val e = Editor(
            Document(id = DocumentId("d"), canvas = CanvasSpec(1000, 1000)),
            boxBounds,
        )
        e.resizeScreen(Vec2(1000f, 1000f))
        e.setViewport(
            ir.pixellab.core.canvas.Viewport(offset = Vec2.ZERO, zoom = 1f, screenSize = Vec2(1000f, 1000f)),
        )
        // Well clear of the canvas centre: a 200-wide layer whose left edge sits four units from
        // the guide would have its right edge four units from the centre line, and the canvas
        // centre outranks a guide — correctly, but the test would then be measuring the ranking.
        e.addGuide(Guide(vertical = true, position = 250f))
        e.addLayer(
            ir.pixellab.core.model.Layer.Shape(
                id = ir.pixellab.core.model.LayerId("a"),
                geometry = ir.pixellab.core.model.ShapeGeometry.Rectangle(Vec2(200f, 100f)),
                transform = ir.pixellab.core.model.Transform(translation = Vec2(0f, 600f)),
            ),
        )

        e.beginDrag(ir.pixellab.core.canvas.Handle.BODY, Vec2(100f, 650f))
        e.dragTo(Vec2(354f, 650f))

        // Dragged to 254 and pulled the last four units onto the guide.
        e.state.document.findLayer(ir.pixellab.core.model.LayerId("a"))!!
            .transform.translation.x shouldBe 250f.plusOrMinus(0.01f)
        e.state.guides.any { it.kind == ir.pixellab.core.canvas.SnapGuide.Kind.GUIDE } shouldBe true
    }
}
