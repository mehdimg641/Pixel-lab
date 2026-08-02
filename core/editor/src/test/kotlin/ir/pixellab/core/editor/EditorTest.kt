package ir.pixellab.core.editor

import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import ir.pixellab.core.canvas.Handle
import ir.pixellab.core.canvas.Viewport
import ir.pixellab.core.model.CanvasSpec
import ir.pixellab.core.model.Color
import ir.pixellab.core.model.Document
import ir.pixellab.core.model.DocumentId
import ir.pixellab.core.model.Effect
import ir.pixellab.core.model.Fill
import ir.pixellab.core.model.Layer
import ir.pixellab.core.model.LayerId
import ir.pixellab.core.model.Rect
import ir.pixellab.core.model.ShapeGeometry
import ir.pixellab.core.model.Style
import ir.pixellab.core.model.Transform
import ir.pixellab.core.model.Vec2
import ir.pixellab.core.model.with
import ir.pixellab.core.render.num
import org.junit.jupiter.api.Test

class EditorTest {

    private val screen = Vec2(1080f, 2400f)

    /** Every test layer is a 200x100 box; bounds come from the host, so the test supplies them. */
    private val boxBounds = LayerBounds { Rect(0f, 0f, 200f, 100f) }

    private fun box(id: String, at: Vec2 = Vec2.ZERO, style: Style = Style.PLAIN_BLACK) = Layer.Shape(
        id = LayerId(id),
        geometry = ShapeGeometry.Rectangle(Vec2(200f, 100f)),
        name = id,
        transform = Transform(translation = at),
        style = style,
    )

    private fun document(vararg layers: Layer) = Document(
        id = DocumentId("d"),
        canvas = CanvasSpec(1000, 1000),
        layers = layers.toList(),
    )

    /** An editor at 1:1 with no pan, so canvas and screen coordinates coincide and read plainly. */
    private fun fixedEditor(vararg layers: Layer): Editor =
        Editor(document(*layers), boxBounds).apply {
            resizeScreen(screen)
            setViewport(Viewport(offset = Vec2.ZERO, zoom = 1f, screenSize = screen))
        }

    // ---- selection -----------------------------------------------------------------------------

    @Test
    fun `a tap selects the topmost layer under the finger`() {
        val e = fixedEditor(box("bottom"), box("top"))
        e.tapCanvas(Vec2(100f, 50f))
        // The document lists layers bottom-up in paint order; walking it forwards would pick the
        // one furthest behind the finger.
        e.state.selection.primary shouldBe LayerId("top")
    }

    @Test
    fun `a tap on empty space clears the selection`() {
        val e = fixedEditor(box("a"))
        e.tapCanvas(Vec2(100f, 50f))
        e.tapCanvas(Vec2(900f, 900f))
        e.state.selection.isEmpty shouldBe true
        e.state.hasSelection shouldBe false
    }

    @Test
    fun `a hidden or locked layer is not selectable`() {
        val e = fixedEditor(
            box("visible"),
            box("hidden").with(visible = false),
            box("locked").with(locked = true),
        )
        e.tapCanvas(Vec2(100f, 50f))
        e.state.selection.primary shouldBe LayerId("visible")
    }

    @Test
    fun `a long press offers every layer under the finger`() {
        val e = fixedEditor(box("bottom"), box("top"))
        e.longPressCanvas(Vec2(100f, 50f))
        // A layer completely covered by another is otherwise unreachable on a touch screen.
        e.state.pickCandidates shouldBe listOf(LayerId("top"), LayerId("bottom"))
        e.select(LayerId("bottom"))
        e.state.selection.primary shouldBe LayerId("bottom")
        e.state.pickCandidates shouldBe emptyList()
    }

    @Test
    fun `an additive tap extends the selection and taps again to remove`() {
        val e = fixedEditor(box("a"), box("b", at = Vec2(400f, 0f)))
        e.tapCanvas(Vec2(100f, 50f))
        e.tapCanvas(Vec2(500f, 50f), additive = true)
        e.state.selection.size shouldBe 2
        e.tapCanvas(Vec2(500f, 50f), additive = true)
        e.state.selection.size shouldBe 1
    }

    // ---- direct manipulation -------------------------------------------------------------------

    @Test
    fun `dragging the body moves the layer`() {
        val e = fixedEditor(box("a"))
        e.select(LayerId("a"))
        e.beginDrag(Handle.BODY, Vec2(100f, 50f))
        e.dragTo(Vec2(400f, 350f))
        e.endDrag()
        e.state.document.findLayer(LayerId("a"))!!.transform.translation shouldBe Vec2(300f, 300f)
    }

    @Test
    fun `a whole drag is a single undo step`() {
        val e = fixedEditor(box("a"))
        e.select(LayerId("a"))
        e.beginDrag(Handle.BODY, Vec2(100f, 50f))
        // A drag emits a frame every few milliseconds; each one becoming an undo entry would bury
        // everything before it.
        repeat(60) { e.dragTo(Vec2(100f + it * 5f, 50f)) }
        e.endDrag()
        e.undo()
        e.state.document.findLayer(LayerId("a"))!!.transform.translation shouldBe Vec2.ZERO
        e.state.canUndo shouldBe false
    }

    @Test
    fun `a locked layer cannot be dragged`() {
        val e = fixedEditor(box("a").with(locked = true))
        e.select(LayerId("a"))
        e.beginDrag(Handle.BODY, Vec2(100f, 50f))
        e.state.drag shouldBe null
        e.dragTo(Vec2(400f, 350f))
        e.state.document.findLayer(LayerId("a"))!!.transform.translation shouldBe Vec2.ZERO
    }

    @Test
    fun `a move snaps to another layer and reports the guide`() {
        val e = fixedEditor(box("a"), box("b", at = Vec2(0f, 400f)))
        e.select(LayerId("a"))
        e.beginDrag(Handle.BODY, Vec2(100f, 50f))
        // Three canvas units short of aligning with b's left edge.
        e.dragTo(Vec2(103f, 50f))
        e.state.document.findLayer(LayerId("a"))!!.transform.translation.x shouldBe (0f plusOrMinus 0.001f)
        e.state.guides.isNotEmpty() shouldBe true
        e.endDrag()
        e.state.guides shouldBe emptyList()
    }

    @Test
    fun `snapping can be switched off`() {
        val e = fixedEditor(box("a"), box("b", at = Vec2(0f, 400f)))
        e.setSnapEnabled(false)
        e.select(LayerId("a"))
        e.beginDrag(Handle.BODY, Vec2(100f, 50f))
        e.dragTo(Vec2(103f, 50f))
        e.state.document.findLayer(LayerId("a"))!!.transform.translation.x shouldBe (3f plusOrMinus 0.001f)
    }

    @Test
    fun `a resize does not snap`() {
        val e = fixedEditor(box("a"), box("b", at = Vec2(0f, 400f)))
        e.select(LayerId("a"))
        e.beginDrag(Handle.RIGHT, Vec2(200f, 50f))
        e.dragTo(Vec2(253f, 50f))
        // Snapping a resize fights the finger on both axes and the handle stops tracking it.
        e.state.document.findLayer(LayerId("a"))!!.transform.scale.x shouldBe (1.265f plusOrMinus 0.001f)
        e.state.guides shouldBe emptyList()
    }

    @Test
    fun `rotation snaps to the common angles`() {
        val e = fixedEditor(box("a"))
        e.select(LayerId("a"))
        val pivot = Vec2(100f, 50f)
        e.beginDrag(Handle.ROTATE, pivot + Vec2(0f, -80f))
        e.dragTo(pivot + Vec2(78f, 4f))
        e.state.document.findLayer(LayerId("a"))!!.transform.rotation shouldBe (90f plusOrMinus 0.001f)
    }

    // ---- parameters ----------------------------------------------------------------------------

    @Test
    fun `setting a parameter updates the effect`() {
        val e = fixedEditor(box("a", style = Style(effects = listOf(Effect.DropShadow(blur = 10f)))))
        e.setEffectParameter(LayerId("a"), 0, "blur", num(40f))
        e.readEffectParameter(LayerId("a"), 0, "blur")!!.let { (it as ir.pixellab.core.render.ParameterValue.Number).value } shouldBe 40f
    }

    @Test
    fun `a slider scrub collapses into one undo step`() {
        val e = fixedEditor(box("a", style = Style(effects = listOf(Effect.DropShadow(blur = 10f)))))
        repeat(120) { e.setEffectParameter(LayerId("a"), 0, "blur", num(10f + it), continuous = true) }
        e.endScrub()
        e.undo()
        val blur = (e.readEffectParameter(LayerId("a"), 0, "blur") as ir.pixellab.core.render.ParameterValue.Number).value
        blur shouldBe 10f
        e.state.canUndo shouldBe false
    }

    @Test
    fun `two separate scrubs are two undo steps`() {
        val e = fixedEditor(box("a", style = Style(effects = listOf(Effect.DropShadow(blur = 10f)))))
        repeat(5) { e.setEffectParameter(LayerId("a"), 0, "blur", num(20f + it), continuous = true) }
        e.endScrub()
        repeat(5) { e.setEffectParameter(LayerId("a"), 0, "blur", num(80f + it), continuous = true) }
        e.endScrub()
        e.undo()
        val blur = (e.readEffectParameter(LayerId("a"), 0, "blur") as ir.pixellab.core.render.ParameterValue.Number).value
        blur shouldBe 24f
    }

    @Test
    fun `adding an effect opens its sheet on the new effect`() {
        val e = fixedEditor(box("a"))
        e.select(LayerId("a"))
        e.addEffect(LayerId("a"), Effect.Stroke(6f, Fill.Solid(Color.WHITE)))
        e.state.sheet.content shouldBe SheetContent.EffectParameters(LayerId("a"), 0)
        e.state.sheet.isOpen shouldBe true
    }

    @Test
    fun `removing the effect a sheet is editing closes the sheet`() {
        val e = fixedEditor(box("a", style = Style(effects = listOf(Effect.DropShadow()))))
        e.select(LayerId("a"))
        e.openSheet(SheetContent.EffectParameters(LayerId("a"), 0))
        e.removeEffect(LayerId("a"), 0)
        // A sheet left editing an effect that no longer exists writes to whatever slid into its
        // index, which is a silent corruption of a different effect.
        e.state.sheet.isOpen shouldBe false
    }

    @Test
    fun `reordering effects keeps the stack the user built`() {
        val e = fixedEditor(
            box("a", style = Style(effects = listOf(Effect.Stroke(4f, Fill.Solid(Color.BLACK)), Effect.DropShadow()))),
        )
        e.moveEffect(LayerId("a"), 0, 1)
        val effects = e.state.document.findLayer(LayerId("a"))!!.style.effects
        (effects[0] is Effect.DropShadow) shouldBe true
        e.undo()
        (e.state.document.findLayer(LayerId("a"))!!.style.effects[0] is Effect.Stroke) shouldBe true
    }

    @Test
    fun `an out of range effect index is ignored rather than crashing`() {
        val e = fixedEditor(box("a"))
        e.setEffectParameter(LayerId("a"), 3, "blur", num(10f))
        e.removeEffect(LayerId("a"), 3)
        e.moveEffect(LayerId("a"), 0, 4)
        e.state.canUndo shouldBe false
    }

    // ---- sheets --------------------------------------------------------------------------------

    @Test
    fun `opening a sheet moves the layer out from under it`() {
        val e = fixedEditor(box("a", at = Vec2(0f, 1800f)))
        e.select(LayerId("a"))
        e.openSheet(SheetContent.EffectParameters(LayerId("a"), 0), SheetDetent.HALF)

        val layer = e.state.document.findLayer(LayerId("a"))!!
        val centre = e.state.viewport.toScreen(Vec2(100f, 1850f))
        val sheetTop = screen.y * (1f - SheetDetent.HALF.screenFraction)
        // The single most common flaw in a mobile editor is a panel covering what it adjusts.
        (centre.y < sheetTop) shouldBe true
        layer.transform.translation shouldBe Vec2(0f, 1800f)
    }

    @Test
    fun `closing the sheet restores the framing exactly`() {
        val e = fixedEditor(box("a", at = Vec2(0f, 1800f)))
        val before = e.state.viewport
        e.select(LayerId("a"))
        e.openSheet(SheetContent.EffectParameters(LayerId("a"), 0))
        (e.state.viewport != before) shouldBe true
        e.closeSheet()
        e.state.viewport shouldBe before
    }

    @Test
    fun `growing the sheet keeps the layer visible without compounding the pan`() {
        val e = fixedEditor(box("a", at = Vec2(0f, 1800f)))
        e.select(LayerId("a"))
        e.openSheet(SheetContent.EffectParameters(LayerId("a"), 0), SheetDetent.PEEK)
        e.setSheetDetent(SheetDetent.FULL)
        e.setSheetDetent(SheetDetent.PEEK)
        // Each detent pans from the original framing, so returning to one lands where it did before.
        val atPeekAgain = e.state.viewport
        e.setSheetDetent(SheetDetent.FULL)
        e.setSheetDetent(SheetDetent.PEEK)
        e.state.viewport shouldBe atPeekAgain
    }

    @Test
    fun `a sheet with no subject leaves the canvas alone`() {
        val e = fixedEditor(box("a"))
        val before = e.state.viewport
        e.openSheet(SheetContent.StyleLibrary, SheetDetent.FULL)
        e.state.viewport shouldBe before
    }

    @Test
    fun `clearing the selection closes a sheet that was editing it`() {
        val e = fixedEditor(box("a"))
        e.select(LayerId("a"))
        e.openSheet(SheetContent.LayerParameters(LayerId("a")))
        e.tapCanvas(Vec2(900f, 900f))
        e.state.sheet.isOpen shouldBe false
    }

    // ---- history and view state ----------------------------------------------------------------

    @Test
    fun `undo and redo walk the document back and forward`() {
        val e = fixedEditor(box("a"))
        e.select(LayerId("a"))
        e.beginDrag(Handle.BODY, Vec2(100f, 50f))
        e.dragTo(Vec2(200f, 50f))
        e.endDrag()
        e.state.canUndo shouldBe true
        e.undo()
        e.state.document.findLayer(LayerId("a"))!!.transform.translation shouldBe Vec2.ZERO
        e.state.canRedo shouldBe true
        e.redo()
        e.state.document.findLayer(LayerId("a"))!!.transform.translation.x shouldBe (100f plusOrMinus 0.001f)
    }

    @Test
    fun `deleting the selected layer drops it from the selection`() {
        val e = fixedEditor(box("a"), box("b", at = Vec2(400f, 0f)))
        e.select(LayerId("a"))
        e.select(LayerId("b"), additive = true)
        e.deleteLayer(LayerId("a"))
        // A contextual bar still holding a deleted layer would act on nothing, silently.
        e.state.selection.ids shouldBe listOf(LayerId("b"))
        e.undo()
        e.state.document.findLayer(LayerId("a")) shouldBe e.state.document.layers.first()
    }

    @Test
    fun `redo after an undo restores what was deleted`() {
        val e = fixedEditor(box("a"))
        e.select(LayerId("a"))
        e.deleteLayer(LayerId("a"))
        e.undo()
        e.redo()
        e.state.document.findLayer(LayerId("a")) shouldBe null
        // And the selection does not come back pointing at nothing.
        e.state.selection.isEmpty shouldBe true
    }

    @Test
    fun `a duplicate lands above the original and takes the selection`() {
        val e = fixedEditor(box("a"))
        e.select(LayerId("a"))
        e.duplicateLayer(LayerId("a"), LayerId("a2"))
        e.state.document.layers.map { it.id } shouldBe listOf(LayerId("a"), LayerId("a2"))
        // Selecting the copy is what makes duplicate-and-nudge one motion.
        e.state.selection.primary shouldBe LayerId("a2")
    }

    @Test
    fun `bring to front and send to back move within paint order`() {
        val e = fixedEditor(box("a"), box("b"), box("c"))
        e.bringToFront(LayerId("a"))
        e.state.document.layers.map { it.id.value } shouldBe listOf("b", "c", "a")
        e.sendToBack(LayerId("c"))
        e.state.document.layers.map { it.id.value } shouldBe listOf("c", "b", "a")
    }

    @Test
    fun `hiding a layer takes it out of reach of a tap`() {
        val e = fixedEditor(box("a"))
        e.setLayerVisible(LayerId("a"), false)
        e.tapCanvas(Vec2(100f, 50f))
        e.state.selection.isEmpty shouldBe true
    }

    @Test
    fun `loading a project does not leave the previous document one undo away`() {
        val e = fixedEditor(box("a"))
        e.select(LayerId("a"))
        e.deleteLayer(LayerId("a"))
        e.replaceDocument(document(box("z")), recordHistory = false)
        // Undo here would take the user back into a file they already closed.
        e.state.canUndo shouldBe false
        e.state.selection.isEmpty shouldBe true
    }

    @Test
    fun `the viewport is never part of the undo history`() {
        val e = fixedEditor(box("a"))
        e.transformViewport(Vec2(100f, 0f), 1.5f, 10f, Vec2(500f, 500f))
        // The camera is not a document edit; recording it would make undo scroll the view instead
        // of reversing the last change.
        e.state.canUndo shouldBe false
    }

    @Test
    fun `the effects bypass is a view state and never a document edit`() {
        val e = fixedEditor(box("a", style = Style(effects = listOf(Effect.DropShadow()))))
        e.setEffectsBypassed(true)
        e.state.effectsBypassed shouldBe true
        e.state.document.findLayer(LayerId("a"))!!.style.effects.single().enabled shouldBe true
        e.state.canUndo shouldBe false
    }

    @Test
    fun `the first measure fits the canvas on screen`() {
        val e = Editor(
            Document(id = DocumentId("d"), canvas = CanvasSpec(2000, 2000), layers = listOf(box("a"))),
            boxBounds,
        )
        e.resizeScreen(screen)
        val shown = e.state.viewport.screenBounds(Vec2(2000f, 2000f))
        (shown.width <= screen.x) shouldBe true
        (shown.width > screen.x - 60f) shouldBe true
    }

    @Test
    fun `a later resize keeps the framing instead of refitting`() {
        val e = fixedEditor(box("a"))
        e.transformViewport(Vec2.ZERO, 3f, 0f, Vec2(540f, 1200f))
        val zoomed = e.state.viewport.zoom
        // A keyboard opening must not throw away where the user was looking.
        e.resizeScreen(Vec2(1080f, 1400f))
        e.state.viewport.zoom shouldBe zoomed
    }
}
